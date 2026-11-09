package notes.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** 426: this app is older than the server accepts. [minVersion] when the server said. */
    data class UpgradeRequired(val minVersion: String?) : ApiResult<Nothing>

    /**
     * Not an answer notesd gives: no notesd error body (a proxy's or portal's page), a redirect, or a
     * success whose body isn't what the endpoint returns. [status] 0: a redirect the browser hid.
     */
    data class Unexpected(val status: Int) : ApiResult<Nothing>
}

/** What this app is, sent with every request so the server can ask for an upgrade (`android`/`ios`/`web`). */
data class ClientInfo(val platform: String, val version: String) {
    /** `X-Notesd-Client`. */
    val header: String get() = "$platform/${version.removePrefix("v")}"

    /** The native apps' `User-Agent`. */
    val userAgent: String get() = "notesd-$header"
}

/** The words for [ApiResult.Unexpected], shown when sync stops on one. */
fun unexpectedMessage(status: Int): String {
    val http = if (status == 0) "a redirect" else "HTTP $status"
    return "Your server answered something notesd didn't expect ($http). If you sign in to a proxy or Wi-Fi " +
        "portal first, do that, then sync again."
}

const val UPGRADE_REQUIRED_MESSAGE =
    "Your notesd server needs a newer version of this app. Update notesd to keep syncing; your changes are kept here."

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

/** [serverVersion] and [apiVersion] are absent from servers before 1.0. */
@Serializable
data class AuthStatus(val setupRequired: Boolean, val serverVersion: String? = null, val apiVersion: Int? = null)

@Serializable
data class Session(val id: String, val kind: String, val name: String, val createdAt: Long, val lastUsedAt: Long)

@Serializable
data class SignIn(
    val session: Session,
    val username: String = "",
    val accessToken: String = "",
    val accessExpiresAt: Long = 0,
    val refreshToken: String = "",
    val refreshExpiresAt: Long = 0,
    /** May change server-wide settings and manage accounts. */
    val isAdmin: Boolean = false,
    /** The name chosen to show instead of the username; "" for none. */
    val displayName: String = "",
    /** The photo's version, for [ApiClient.avatar]; "" for none. */
    val avatar: String = "",
    /** The account's id, as notes' updatedBy and shared spaces' members name it (§7.9). 0 from older servers. */
    val userId: Long = 0
) {
    val tokens: Tokens get() = Tokens(accessToken, accessExpiresAt, refreshToken)
}

/** An account, as an admin sees it. [pending]: invited, no password chosen yet. */
@Serializable
data class Person(
    val id: Long,
    val username: String,
    val isAdmin: Boolean = false,
    val createdAt: Long = 0,
    val pending: Boolean = false,
    /** When its setup link stops working; null without one. */
    val linkExpiresAt: Long? = null,
    /** As on [SignIn]. */
    val displayName: String = "",
    val avatar: String = ""
)

/** A one-time link's secret; the app builds the URL around it. Shown once: the server keeps only a hash. */
@Serializable
data class SetupLink(val token: String, val expiresAt: Long)

/** A link just made, with its account as it is now: [Person.pending] tells an invitation from a reset. */
@Serializable
data class IssuedLink(val user: Person, val link: SetupLink)

@Serializable
internal data class People(val users: List<Person> = emptyList())

@Serializable
internal data class ErrorBody(
    val error: String = "",
    val message: String = "",
    val field: String? = null,
    val current: JsonObject? = null,
    val minVersion: String? = null
)

private const val DEFAULT_RETRY_AFTER_S = 60L
private val errorJson = Json { ignoreUnknownKeys = true }

/** An HTTP answer as a result. [errorText] is read for errors only: a success may be an image. */
internal fun <T> apiResult(code: Int, retryAfter: String?, errorText: () -> String, decode: () -> T): ApiResult<T> {
    // SerializationException is an IllegalArgumentException.
    if (code in 200..299) {
        return try {
            ApiResult.Ok(decode())
        } catch (_: IllegalArgumentException) {
            ApiResult.Unexpected(code)
        }
    }
    // Before the body: a proxy's 429 or 502 page means the same as notesd's.
    if (code == 429) return ApiResult.RateLimited(retryAfter?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S)
    val error = try {
        errorJson.decodeFromString<ErrorBody>(errorText())
    } catch (_: IllegalArgumentException) {
        ErrorBody()
    }
    if (code >= 500) return ApiResult.Rejected(code, error.error.ifEmpty { "http_$code" }, error.message, error.field)
    // A notesd error always names its code; anything else came from something in between.
    if (code !in 400..499 || error.error.isEmpty()) return ApiResult.Unexpected(code)
    return when {
        code == 401 && error.error == "unauthenticated" -> ApiResult.Unauthenticated

        // Cookie sessions: a token from an older one. Signing in again gives a good one; the write waits for it.
        code == 403 && error.error == "csrf_failed" -> ApiResult.Unauthenticated

        code == 426 && error.error == "upgrade_required" -> ApiResult.UpgradeRequired(error.minVersion)

        code == 409 -> ApiResult.Conflict(error.error, error.message, error.current)

        else -> ApiResult.Rejected(code, error.error, error.message, error.field)
    }
}

/** Refusals of a queued change that a retry can't change: the change is dropped, and the user told why. */
val PERMANENT_ERRORS = setOf(
    "invalid_request",
    "hash_mismatch",
    "image_too_large",
    "unsupported_image",
    "space_default",
    "not_space_owner",
    "space_exists",
    "locked_in_exposed_space"
)

/**
 * The server's Resend settings for emailing notes. The key never comes back; [keyHint] is its last 4 characters.
 * [editable]: whether this account may change them (admins). Servers from before accounts had roles leave it out;
 * their one account is the owner.
 */
@Serializable
data class EmailSettings(
    val configured: Boolean = false,
    val from: String = "",
    val keyHint: String = "",
    val editable: Boolean = true
)

/** One wrapped copy of the locked-notes key (§10.1); nothing here opens without its secret. */
@Serializable
data class LockKey(
    val id: String,
    val kind: String,
    val credentialId: String? = null,
    val salt: String,
    val iterations: Int = 0,
    val wrapped: String,
    val createdAt: Long = 0,
    /**
     * How the entry's key is derived from its secret, e.g. `{"name":"pbkdf2-sha256","iterations":600000}`.
     * Kept whole: a newer app's may carry parameters this one doesn't know. Null from servers before 1.0.
     */
    val kdf: JsonObject? = null
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
    val spaceId: String = "default",
    /** The account that last changed it (§7.9); null for changes from before that was recorded. */
    val updatedBy: Long? = null,
    /** On a tombstone a move to another file left: the space the note went to (§7.9). */
    val movedTo: String? = null
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
    val noteCount: Int = 0,
    /** Absent from servers older than traits: none. */
    val traits: List<SpaceTrait> = emptyList(),
    /** Who's in a shared space (§7.9); null for the account's own. */
    val shared: SharedSpace? = null
)

/** A shared space's people, as this account sees them (§7.9). [role] is `owner` or `member`. */
@Serializable
data class SharedSpace(
    val role: String,
    val members: List<Member> = emptyList(),
    /** Pending invitations; only the owner gets them. */
    val invites: List<Invitee> = emptyList()
) {
    val isOwner: Boolean get() = role == "owner"
}

/** An account as other accounts see it: with the name and photo ([avatar], its version) it chose, if any. */
@Serializable
data class Member(val id: Long, val username: String, val displayName: String = "", val avatar: String = "") {
    /** How the apps name someone: their chosen name, or else their username. */
    val name: String get() = displayName.ifEmpty { username }
}

/** A pending invitation into a shared space, as its owner sees it. */
@Serializable
data class Invitee(
    val id: String,
    val userId: Long,
    val username: String,
    val displayName: String = "",
    val avatar: String = ""
) {
    val name: String get() = displayName.ifEmpty { username }
}

/** An invitation waiting for this account (§7.9). */
@Serializable
data class RemoteInvite(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val spaceIcon: String = "",
    val from: Member,
    val createdAt: Long = 0
)

@Serializable
internal data class RemoteInvites(val invites: List<RemoteInvite> = emptyList())

@Serializable
internal data class RemotePeople(val people: List<Member> = emptyList())

/**
 * How a space is published (§7.10): its address ([url], from [origin] and [handle]) and whether the site is up.
 * Its custom CSS is the web app's to change.
 */
@Serializable
data class PublishSettings(
    val handle: String,
    val url: String,
    val origin: String,
    val published: Boolean,
    val css: String = ""
)

/**
 * A feature on for a whole space (§7.8), worked out by the server. [exposure] is the sentence a move
 * into the space asks with, null when the trait keeps notes to the account; [audience] is who it reaches
 * ("user:3", "public"), what a move compares; [problem] is set when it needs attention.
 */
@Serializable
data class SpaceTrait(
    val key: String,
    val label: String = "",
    val summary: String = "",
    val exposure: String? = null,
    val problem: String? = null,
    val audience: List<String>? = null
)

@Serializable
internal data class RemoteSpaces(val spaces: List<RemoteSpace> = emptyList())

/** One page of the change feed: latest state per note, ascending changeSeq. */
@Serializable
data class SyncPage(val changes: List<RemoteNote>, val nextCursor: Long, val hasMore: Boolean, val generation: String)

/** The answer to an image upload: what the server stored. */
@Serializable
internal data class StoredImage(val id: String, val contentType: String)

/** An image upload's answer, checked: notesd's Image for [id], else not notesd. */
internal fun checkStoredImage(id: String, body: String) {
    require(errorJson.decodeFromString<StoredImage>(body).id == id) { "Not this image" }
}

/** An image's bytes as stored on the server (§7.6), with their type: an image type, or [LOCKED_IMAGE]. */
class ImageFile(val type: String, val bytes: ByteArray)

/** The type a locked note's images are stored as: ciphertext. Later versions add a suffix (`.v2`). */
const val LOCKED_IMAGE = "application/vnd.notesd.locked"

/** A locked image of any version; only [LOCKED_IMAGE] itself opens here. */
fun isLockedImageType(type: String) = type == LOCKED_IMAGE || type.startsWith("$LOCKED_IMAGE.")

/**
 * The calls a sync cycle makes: the JVM's [ApiClient] with device tokens, the browser's with its session cookie.
 * Every call is at the account's address, which finds a note in whichever file it is (§7.9), except a shared
 * space's change feed: [inSpace]'s [sync].
 */
interface SyncApi {
    /** A shared space's feed: only [sync] is per file. */
    fun inSpace(spaceId: String): SyncApi

    /** The invitations waiting for this account. */
    suspend fun invites(): ApiResult<List<RemoteInvite>>

    suspend fun createNote(body: JsonObject): ApiResult<MutationResult>

    suspend fun updateNote(id: String, body: JsonObject): ApiResult<MutationResult>

    suspend fun deleteNote(id: String, body: JsonObject): ApiResult<MutationResult>

    /** Changes after [since]. [generation] is the one stored from the last page, null before the first sync. */
    suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage>

    suspend fun lockKeys(): ApiResult<List<LockKey>>

    suspend fun spaces(): ApiResult<List<RemoteSpace>>

    suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace>

    suspend fun deleteSpace(id: String): ApiResult<Unit>

    suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit>
}

/** Images added on this device, uploaded before the notes that show them (§7.6). */
interface PendingImages {
    /**
     * Uploads every waiting image; the first failure a retry might fix stops it (an [ApiResult.Unexpected]
     * counted against that image). Ok holds why any were refused for good.
     */
    suspend fun upload(client: SyncApi): ApiResult<List<String>>

    companion object {
        /** A retry won't change these; only notesd's own codes, not a bare 400/413/415 from something in between. */
        val REFUSED = setOf("hash_mismatch", "image_too_large", "unsupported_image", "invalid_request")
    }
}
