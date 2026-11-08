package notes.app.account

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.api.Credentials
import notes.core.api.SignIn
import notes.core.api.Tokens

/** An account's name and photo, as the server last said; [userId] 0 until it has (signed in before the app kept it). */
data class Profile(val userId: Long = 0, val displayName: String = "", val avatar: String = "") {
    /** How the apps name someone: their chosen name, or else their username. */
    fun name(username: String) = displayName.ifEmpty { username }
}

sealed interface Account {
    /** Never connected (or all data removed): notes live on this device only. */
    data object None : Account

    /**
     * [isAdmin]: as of sign-in, or the last time the server said; People asks it again. Null: not known, for an
     * account signed in before the app kept it. [userId] likewise: the account's id, as shared spaces name it (§7.9).
     */
    data class SignedIn(
        val server: String,
        val username: String,
        val isAdmin: Boolean? = false,
        val userId: Long? = null,
        val profile: Profile = Profile()
    ) : Account

    /**
     * Connected once, no session now (signed out, or the session was revoked).
     * The server stays fixed: these notes belong to it.
     */
    data class SignedOut(val server: String, val username: String, val profile: Profile = Profile()) : Account
}

/**
 * The server, username and device tokens, in one file sealed with a Keystore
 * key. Read once at startup (small, and the start screen depends on it), then
 * kept in memory. A file that can't be opened (key gone after a restore onto
 * a new phone) counts as never connected.
 */
class AccountStore(private val file: File, private val sealer: Sealer) : Credentials {
    @Serializable
    private data class Saved(
        val server: String,
        val username: String,
        val access: String? = null,
        val accessExpiresAt: Long = 0,
        val refresh: String? = null,
        // Absent from files written before the app kept it: unknown, not "no".
        val isAdmin: Boolean? = null,
        val userId: Long? = null,
        val displayName: String = "",
        val avatar: String = ""
    ) {
        val profile get() = Profile(userId ?: 0, displayName, avatar)
    }

    private val lock = Mutex()
    private var saved: Saved? = load()
    private val _account = MutableStateFlow(accountOf(saved))
    val account: StateFlow<Account> = _account

    suspend fun signedIn(server: String, signIn: SignIn) = write {
        Saved(
            server,
            signIn.username,
            signIn.accessToken,
            signIn.accessExpiresAt,
            signIn.refreshToken,
            signIn.isAdmin,
            // 0 from a server older than shared spaces.
            signIn.userId.takeIf { it > 0 },
            signIn.displayName,
            signIn.avatar
        )
    }

    /**
     * [id] is [of]'s, found out after signing in on a server that didn't say. Kept only if [of] is still the account:
     * the lookup may have outlasted it.
     */
    suspend fun userIdFound(of: Account.SignedIn, id: Long) = write {
        if (it == null || it.server != of.server || it.username != of.username || it.userId != null) return@write it
        it.copy(userId = id)
    }

    /** The name or photo changed, here or on another device. */
    suspend fun profileChanged(profile: Profile) {
        if (lock.withLock { saved?.profile } == profile) return
        write {
            it?.copy(
                userId = profile.userId.takeIf { id -> id > 0 } ?: it.userId,
                displayName = profile.displayName,
                avatar = profile.avatar
            )
        }
    }

    /** What the server last said: another admin can change it at any time. */
    suspend fun adminChanged(isAdmin: Boolean) {
        if ((account.value as? Account.SignedIn)?.isAdmin == isAdmin) return
        write { it?.copy(isAdmin = isAdmin) }
    }

    /** Forgets the tokens, keeps the server and username. */
    suspend fun signOut() = write { it?.copy(access = null, accessExpiresAt = 0, refresh = null) }

    suspend fun clear() = write { null }

    override suspend fun tokens(): Tokens? = lock.withLock {
        val s = saved ?: return null
        Tokens(s.access ?: return null, s.accessExpiresAt, s.refresh ?: return null)
    }

    override suspend fun refreshed(tokens: Tokens) = write {
        it?.copy(access = tokens.access, accessExpiresAt = tokens.accessExpiresAt, refresh = tokens.refresh)
    }

    override suspend fun revoked() = signOut()

    private suspend fun write(change: (Saved?) -> Saved?) = lock.withLock {
        val next = change(saved)
        if (next == saved) return@withLock
        withContext(Dispatchers.IO) {
            if (next == null) {
                file.delete()
            } else {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(sealer.seal(json.encodeToString(next).encodeToByteArray()))
                if (!tmp.renameTo(file)) error("Couldn't save the account")
            }
        }
        saved = next
        _account.value = accountOf(next)
    }

    private fun load(): Saved? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Saved>(sealer.open(file.readBytes()).decodeToString())
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun accountOf(s: Saved?): Account = when {
            s == null -> Account.None

            s.access != null && s.refresh != null ->
                Account.SignedIn(s.server, s.username, s.isAdmin, s.userId, s.profile)

            else -> Account.SignedOut(s.server, s.username, s.profile)
        }
    }
}
