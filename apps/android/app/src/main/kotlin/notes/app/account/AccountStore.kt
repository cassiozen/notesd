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

sealed interface Account {
    /** Never connected (or all data removed): notes live on this device only. */
    data object None : Account

    /**
     * [isAdmin]: as of sign-in, or the last time the server said; People asks it again. Null: not known, for an
     * account signed in before the app kept it.
     */
    data class SignedIn(val server: String, val username: String, val isAdmin: Boolean? = false) : Account

    /**
     * Connected once, no session now (signed out, or the session was revoked).
     * The server stays fixed: these notes belong to it.
     */
    data class SignedOut(val server: String, val username: String) : Account
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
        val isAdmin: Boolean? = null
    )

    private val lock = Mutex()
    private var saved: Saved? = load()
    private val _account = MutableStateFlow(accountOf(saved))
    val account: StateFlow<Account> = _account

    suspend fun signedIn(server: String, signIn: SignIn) = write {
        Saved(server, signIn.username, signIn.accessToken, signIn.accessExpiresAt, signIn.refreshToken, signIn.isAdmin)
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
            s.access != null && s.refresh != null -> Account.SignedIn(s.server, s.username, s.isAdmin)
            else -> Account.SignedOut(s.server, s.username)
        }
    }
}
