package notes.app.sharing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.Member
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.api.unexpectedMessage
import notes.core.db.NoteStore
import notes.core.db.Space
import notes.core.sync.SyncEngine
import notes.core.sync.SyncOutcome

/** What a membership call came to: done, or why not, in words. */
sealed interface Shared<out T> {
    data class Done<T>(val value: T) : Shared<T>

    data class Failed(val message: String) : Shared<Nothing>
}

/**
 * Who's in a shared space (§7.9): online calls, each followed by a sync so the space list, its notes and the
 * invitations here catch up. They run on [scope], so a screen closing mid-call doesn't leave the server changed
 * and this device not knowing.
 */
class Sharing(
    private val api: () -> ApiClient?,
    private val engine: SyncEngine,
    private val store: NoteStore,
    private val scope: CoroutineScope
) {
    /** Every account on the server, to invite. */
    suspend fun accounts(): Shared<List<Member>> = call { accounts() }

    /**
     * Invites [userId] into [space]. The first invitation shares it, so the space and its notes go up first:
     * the server moves what it has.
     */
    suspend fun invite(space: Space, userId: Long): Shared<Unit> = background {
        if (space.shared == null) {
            locked(space.id)?.let { return@background Shared.Failed(it) }
            if (engine.sync() == SyncOutcome.Offline) return@background Shared.Failed(OFFLINE)
        }
        val r = api()?.inviteToSpace(space.id, userId) ?: return@background Shared.Failed(NOT_CONNECTED)
        if (r is ApiResult.Conflict && r.code == "space_has_locked") {
            return@background Shared.Failed(locked(space.id) ?: failure(r))
        }
        when (val done = after(r)) {
            is Shared.Done -> Shared.Done(Unit)
            is Shared.Failed -> done
        }
    }

    suspend fun cancelInvite(spaceId: String, inviteId: String): Shared<Unit> =
        change { cancelInvite(spaceId, inviteId) }

    suspend fun remove(spaceId: String, userId: Long): Shared<Unit> = change { removeMember(spaceId, userId) }

    /** Makes [userId] the owner; this account stays a member. */
    suspend fun makeOwner(spaceId: String, userId: Long): Shared<Unit> = change { transferSpace(spaceId, userId) }

    /** This account leaves; the space's notes leave the device with the sync that follows. */
    suspend fun leave(spaceId: String, me: Long): Shared<Unit> = change { removeMember(spaceId, me) }

    /** Removes every member but the owner and cancels every invitation: the space is the owner's alone again. */
    suspend fun stopSharing(space: Space, me: Long): Shared<Unit> = background {
        val client = api() ?: return@background Shared.Failed(NOT_CONNECTED)
        val shared = space.shared ?: return@background Shared.Done(Unit)
        val calls = shared.invites.map { suspend { client.cancelInvite(space.id, it.id) } } +
            shared.members.filter { it.id != me }.map { suspend { client.removeMember(space.id, it.id) } }
        var changed = false
        for (call in calls) {
            val r = call()
            if (r is ApiResult.Ok || r.gone()) {
                changed = true
                continue
            }
            // The ones before went through: synced anyway, so the page shows who's still in.
            if (changed) engine.sync()
            return@background Shared.Failed(failure(r))
        }
        after(ApiResult.Ok(Unit))
    }

    /** Joins the space; once this returns [Shared.Done], the space and its notes are here. */
    suspend fun join(inviteId: String): Shared<String> = background {
        val r = api()?.acceptInvite(inviteId) ?: return@background Shared.Failed(NOT_CONNECTED)
        when (val done = after(r)) {
            is Shared.Done -> Shared.Done(done.value.id)
            is Shared.Failed -> done
        }
    }

    suspend fun decline(inviteId: String): Shared<Unit> = change { declineInvite(inviteId) }

    /** How many notes in [spaceId] have changes the server hasn't confirmed: kept as copies if this account leaves. */
    suspend fun unsynced(spaceId: String): Int = store.list(space = spaceId).count { store.get(it.id)?.dirty == true }

    private suspend fun locked(spaceId: String): String? =
        store.lockedCount(spaceId).takeIf { it > 0 }?.let(::lockedMessage)

    private suspend fun <T> call(block: suspend ApiClient.() -> ApiResult<T>): Shared<T> {
        val r = api()?.block() ?: return Shared.Failed(NOT_CONNECTED)
        return if (r is ApiResult.Ok) Shared.Done(r.value) else Shared.Failed(failure(r))
    }

    private suspend fun change(block: suspend ApiClient.() -> ApiResult<Unit>): Shared<Unit> = background {
        after(api()?.block() ?: return@background Shared.Failed(NOT_CONNECTED))
    }

    /** Syncs after a call that went through, so this device shows what it did; failed if it couldn't. */
    private suspend fun <T> after(r: ApiResult<T>): Shared<T> {
        if (r !is ApiResult.Ok) return Shared.Failed(failure(r))
        if (engine.sync() != SyncOutcome.Done) return Shared.Failed(BEHIND)
        return Shared.Done(r.value)
    }

    private suspend fun <T> background(block: suspend () -> Shared<T>): Shared<T> = scope.async { block() }.await()

    private fun ApiResult<*>.gone() = this is ApiResult.Rejected && status == 404

    companion object {
        const val OFFLINE = "This needs a connection. Try again when you're online."
        const val NOT_CONNECTED = "Connect to a notesd server to share spaces."
        const val BEHIND = "Done, but this phone couldn't sync to show it yet. It will with the next sync."
    }
}

/** Why a space with locked notes can't be shared (§7.9). */
fun lockedMessage(count: Int): String =
    if (count == 1) "Unlock its locked note to share it." else "Unlock its $count locked notes to share it."

/** A membership call's refusal, in words. */
internal fun failure(r: ApiResult<*>): String = when (r) {
    is ApiResult.Ok -> ""

    is ApiResult.Unreachable -> Sharing.OFFLINE

    ApiResult.Unauthenticated -> "Sign in again to change who's in this space."

    is ApiResult.RateLimited -> "Too many attempts. Wait a moment and try again."

    is ApiResult.UpgradeRequired -> UPGRADE_REQUIRED_MESSAGE

    is ApiResult.Unexpected -> unexpectedMessage(r.status)

    is ApiResult.Conflict -> when (r.code) {
        "owner_cannot_leave" -> "As its owner, make someone else the owner first, or stop sharing it."
        "space_default" -> "This space can't be shared: everyone keeps one of their own."
        else -> sentence(r.message)
    }

    is ApiResult.Rejected -> when {
        r.error == "not_space_owner" -> "Only the space's owner can do that."
        r.status == 404 -> "That's no longer there. It may have changed on another device."
        else -> sentence(r.message)
    }
}

private fun sentence(s: String): String {
    if (s.isBlank()) return "Something went wrong. Try again."
    val c = s.replaceFirstChar { it.uppercase() }
    return if (c.endsWith('.')) c else "$c."
}
