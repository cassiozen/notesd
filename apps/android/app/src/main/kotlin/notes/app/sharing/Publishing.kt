package notes.app.sharing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.PublishSettings
import notes.core.api.SpaceTrait
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.api.unexpectedMessage
import notes.core.db.NoteStore
import notes.core.db.Space
import notes.core.sync.SyncEngine
import notes.core.sync.SyncOutcome

/**
 * A space as a public website (§7.10): online calls, each followed by a sync so the space's `published` trait,
 * and the lock rules that come with it, reach this phone. They run on [scope], so a screen closing mid-call
 * doesn't leave the server changed and this phone not knowing.
 */
class Publishing(
    private val api: () -> ApiClient?,
    private val engine: SyncEngine,
    private val store: NoteStore,
    private val scope: CoroutineScope
) {
    private val _expected = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Published or not, by space id, as this phone just asked: shown at once (the glyph, the lock rules), until
     * the space list agrees ([settle]) or the call fails.
     */
    val expected: StateFlow<Map<String, Boolean>> = _expected

    /** Drops what [list] now says too, so a later change from elsewhere shows as it is. */
    fun settle(list: List<Space>) {
        _expected.update { expected ->
            expected.filter { (id, on) ->
                val space = list.firstOrNull { it.id == id } ?: return@filter false
                space.traits.any { it.key == KEY } != on
            }
        }
        callOf.keys.retainAll(_expected.value.keys)
    }

    /** Its address and whether it's up; a [Shared.Failed] with [NOT_PUBLISHED] if it never was. */
    suspend fun settings(spaceId: String): Shared<PublishSettings> {
        val r = api()?.publishSettings(spaceId) ?: return Shared.Failed(NOT_CONNECTED)
        if (r is ApiResult.Rejected && r.error == "not_published") return Shared.Failed(NOT_PUBLISHED)
        return if (r is ApiResult.Ok) Shared.Done(r.value) else Shared.Failed(publishFailure(r))
    }

    /** Publishes [space] at an address from its name, built from this server's address as the app reaches it. */
    suspend fun publish(space: Space): Shared<PublishSettings> = background {
        // Said here with the count: the server only knows the notes that reached it.
        lockedCount(space.id).takeIf { it > 0 }?.let { return@background Shared.Failed(publishLocked(it)) }
        val client = api() ?: return@background Shared.Failed(NOT_CONNECTED)
        val call = expect(space.id, true)
        val r = client.publish(space.id, origin = client.origin)
        if (r !is ApiResult.Ok) unexpect(space.id, call)
        if (r is ApiResult.Conflict && r.code == "space_has_locked") {
            return@background Shared.Failed(publishLocked(lockedCount(space.id)))
        }
        after(r)
    }

    /** Moves the site to [handle]; the old address stops working. */
    suspend fun changeHandle(spaceId: String, handle: String): Shared<PublishSettings> = background {
        after(api()?.publish(spaceId, handle = handle) ?: return@background Shared.Failed(NOT_CONNECTED))
    }

    /** Takes the site down; its address stays the space's. */
    suspend fun stop(spaceId: String): Shared<Unit> = background {
        val client = api() ?: return@background Shared.Failed(NOT_CONNECTED)
        val call = expect(spaceId, false)
        val r = client.unpublish(spaceId)
        if (r !is ApiResult.Ok) unexpect(spaceId, call)
        after(r)
    }

    // Which call each expectation is: a call that fails clears only its own, not a newer one's.
    private var calls = 0
    private val callOf = HashMap<String, Int>()

    /** Called on [scope], so one at a time. */
    private fun expect(spaceId: String, on: Boolean): Int {
        callOf[spaceId] = ++calls
        _expected.update { it + (spaceId to on) }
        return calls
    }

    private fun unexpect(spaceId: String, call: Int) {
        if (callOf[spaceId] != call) return
        callOf -= spaceId
        _expected.update { it - spaceId }
    }

    /** Signed out: what was expected was another session's. */
    fun forget() {
        callOf.clear()
        _expected.value = emptyMap()
    }

    private suspend fun lockedCount(spaceId: String) = store.lockedCount(spaceId)

    private suspend fun <T> after(r: ApiResult<T>): Shared<T> {
        if (r !is ApiResult.Ok) return Shared.Failed(publishFailure(r))
        if (engine.sync() != SyncOutcome.Done) return Shared.Failed(Sharing.BEHIND)
        return Shared.Done(r.value)
    }

    private suspend fun <T> background(block: suspend () -> Shared<T>): Shared<T> = scope.async { block() }.await()

    companion object {
        const val KEY = "published"

        /** Until the server says, a published space has no address: only that it's public. */
        val PUBLISHING = SpaceTrait(KEY, "Published", "Publishing…", exposure = "Public", audience = listOf("public"))

        /** [space] as [on] (expected, null for nothing) shows it. */
        fun shown(space: Space, on: Boolean?): Space {
            val published = space.traits.firstOrNull { it.key == KEY }
            return when {
                on == true && published == null -> {
                    // After sharing, before the rest: the server's order (spaces.TraitOrder).
                    val at = space.traits.indexOfFirst { it.key != "shared" }.let {
                        if (it <
                            0
                        ) {
                            space.traits.size
                        } else {
                            it
                        }
                    }
                    space.copy(traits = space.traits.toMutableList().apply { add(at, PUBLISHING) })
                }

                on == false && published != null ->
                    space.copy(traits = space.traits - published, leaving = space.leaving + published)

                else -> space
            }
        }

        const val NOT_CONNECTED = "Connect to a notesd server to publish spaces."
        const val NOT_PUBLISHED = "This space isn't published."
        const val HANDLE_TAKEN = "Another space has that address. Try another."
        const val HANDLE_RULES = "Use 1–64 lowercase letters, digits and hyphens, not starting or ending with a hyphen."
    }
}

/** Why a space with locked notes can't be published (§7.8). */
fun publishLocked(count: Int): String = when (count) {
    0 -> "Unlock its locked notes to publish it."
    1 -> "Unlock its locked note to publish it."
    else -> "Unlock its $count locked notes to publish it."
}

/** A publishing call's refusal, in words. */
internal fun publishFailure(r: ApiResult<*>): String = when (r) {
    is ApiResult.Ok -> ""

    is ApiResult.Unreachable -> Sharing.OFFLINE

    ApiResult.Unauthenticated -> "Sign in again to publish this space."

    is ApiResult.RateLimited -> "Too many attempts. Wait a moment and try again."

    is ApiResult.UpgradeRequired -> UPGRADE_REQUIRED_MESSAGE

    is ApiResult.Unexpected -> unexpectedMessage(r.status)

    is ApiResult.Conflict -> when (r.code) {
        "handle_taken" -> Publishing.HANDLE_TAKEN
        "space_has_locked" -> publishLocked(0)
        "space_default" -> "This space can't be published: everyone keeps one of their own."
        else -> failure(r)
    }

    is ApiResult.Rejected -> when {
        r.error == "invalid_request" && r.field == "handle" -> Publishing.HANDLE_RULES
        r.error == "not_published" -> Publishing.NOT_PUBLISHED
        else -> failure(r)
    }
}
