package notes.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The outcome of one request. Transport failures are values too: callers decide what offline means. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 409: the server's current state is in [current] (a note, for note conflicts). */
    data class Conflict(val code: String, val message: String, val current: JsonObject?) : ApiResult<Nothing>

    /** No usable session: never signed in, or the session was revoked. */
    data object Unauthenticated : ApiResult<Nothing>

    data class RateLimited(val retryAfterSeconds: Long) : ApiResult<Nothing>

    /** Any other error answer, e.g. a wrong password (401 on login) or invalid input (400). */
    data class Rejected(val status: Int, val error: String, val message: String, val field: String?) :
        ApiResult<Nothing>

    data class Unreachable(val cause: Throwable) : ApiResult<Nothing>
}

/** Device tokens. [accessExpiresAt] is Unix ms. */
data class Tokens(val access: String, val accessExpiresAt: Long, val refresh: String)

/** Where the client keeps the session's tokens; implemented by the app with encrypted storage. */
interface Credentials {
    suspend fun tokens(): Tokens?

    /** A refresh succeeded. */
    suspend fun refreshed(tokens: Tokens)

    /** The server refused the refresh token: the session is over. Local notes are not this interface's business. */
    suspend fun revoked()
}

@Serializable
data class AuthStatus(val setupRequired: Boolean)

@Serializable
data class Session(val id: String, val kind: String, val name: String, val createdAt: Long, val lastUsedAt: Long)

@Serializable
data class SignIn(
    val session: Session,
    val username: String = "",
    val accessToken: String = "",
    val accessExpiresAt: Long = 0,
    val refreshToken: String = "",
    val refreshExpiresAt: Long = 0
) {
    val tokens: Tokens get() = Tokens(accessToken, accessExpiresAt, refreshToken)
}

@Serializable
internal data class ErrorBody(
    val error: String = "",
    val message: String = "",
    val field: String? = null,
    val current: JsonObject? = null
)

/** The server's Resend settings for emailing notes. The key never comes back; [keyHint] is its last 4 characters. */
@Serializable
data class EmailSettings(val configured: Boolean = false, val from: String = "", val keyHint: String = "")

/** One wrapped copy of the locked-notes key (§10.1); nothing here opens without its secret. */
@Serializable
data class LockKey(
    val id: String,
    val kind: String,
    val credentialId: String? = null,
    val salt: String,
    val iterations: Int = 0,
    val wrapped: String,
    val createdAt: Long = 0
)

@Serializable
data class LockKeys(val keys: List<LockKey> = emptyList())

/** A note as the server has it. A tombstone has [deletedAt] set and empty title and content. */
@Serializable
data class RemoteNote(
    val id: String,
    val title: String = "",
    val content: String = "",
    val revision: Long,
    val changeSeq: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val conflictOf: String? = null,
    /** A locked note's images as the server records them (§7.6); absent for a plain note. */
    val imageIds: List<String>? = null,
    /** The space the note is in (§7.7). */
    val spaceId: String = "default"
)

/** The answer to a create, update or delete. */
@Serializable
data class MutationResult(
    val id: String,
    val status: String,
    val revision: Long,
    val changeSeq: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Creates and updates: the space the note is in now, the default if the one sent is gone (§7.7). */
    val spaceId: String? = null
)

/** A space as the server stores it (§7.7). */
@Serializable
data class RemoteSpace(
    val id: String,
    val name: String,
    val icon: String = "",
    val createdAt: Long = 0,
    val noteCount: Int = 0
)

@Serializable
internal data class RemoteSpaces(val spaces: List<RemoteSpace> = emptyList())

/** One page of the change feed: latest state per note, ascending changeSeq. */
@Serializable
data class SyncPage(val changes: List<RemoteNote>, val nextCursor: Long, val hasMore: Boolean, val generation: String)

/** An image's bytes as stored on the server (§7.6), with their type: an image type, or [LOCKED_IMAGE]. */
class ImageFile(val type: String, val bytes: ByteArray)

/** The type a locked note's images are stored as: ciphertext. */
const val LOCKED_IMAGE = "application/vnd.notesd.locked"
