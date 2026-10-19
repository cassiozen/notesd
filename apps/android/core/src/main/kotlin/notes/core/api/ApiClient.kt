package notes.core.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * notesd's JSON API (server/README.md) for one server. Authenticated
 * calls send the device's bearer token, refresh it shortly before it expires,
 * and on a 401 refresh once and resend the identical request, so an
 * operationId inside it stays the same. Only one refresh runs at a time.
 */
class ApiClient(
    val baseUrl: HttpUrl,
    private val credentials: Credentials? = null,
    private val http: OkHttpClient = defaultHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val refreshLock = Mutex()

    /** `/healthz`: is a server answering at this address at all. */
    suspend fun health(): ApiResult<Unit> = send(get("healthz")) { }

    suspend fun status(): ApiResult<AuthStatus> = send(get("api/v1/auth/status")) { json.decodeFromString(it) }

    /** Creates the owner account on a server that has none yet. */
    suspend fun setup(username: String, password: String): ApiResult<Unit> = send(
        post(
            "api/v1/auth/setup",
            buildJsonObject {
                put("username", username)
                put("password", password)
            }
        )
    ) { }

    suspend fun login(username: String, password: String, deviceName: String): ApiResult<SignIn> = send(
        post(
            "api/v1/auth/login",
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("client", "device")
                put("deviceName", deviceName)
            }
        )
    ) { json.decodeFromString(it) }

    /** Ends this device's session on the server. */
    suspend fun logout(): ApiResult<Unit> = authed({ post("api/v1/auth/logout", JsonObject(emptyMap()), it) }) { }

    /** The body is sent as given: the sync engine builds it once and resends it unchanged on retries. */
    suspend fun createNote(body: JsonObject): ApiResult<MutationResult> =
        authed({ request("POST", url("api/v1/notes"), body, it) }) { json.decodeFromString(it) }

    suspend fun updateNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        authed({ request("PUT", noteUrl(id), body, it) }) { json.decodeFromString(it) }

    suspend fun deleteNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        authed({ request("DELETE", noteUrl(id), body, it) }) { json.decodeFromString(it) }

    /** Changes after [since]. [generation] is the one stored from the last page, null before the first sync. */
    suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage> = authed({ access ->
        val url = url("api/v1/sync").newBuilder()
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .apply { if (generation != null) addQueryParameter("generation", generation) }
            .build()
        Request.Builder().url(url).header("Authorization", "Bearer $access").build()
    }) { json.decodeFromString(it) }

    suspend fun emailSettings(): ApiResult<EmailSettings> =
        authed({ request("GET", url("api/v1/settings/email"), null, it) }) { json.decodeFromString(it) }

    /** A null [apiKey] keeps the saved one. */
    suspend fun saveEmailSettings(from: String, apiKey: String?): ApiResult<EmailSettings> = authed({
        request(
            "PUT",
            url("api/v1/settings/email"),
            buildJsonObject {
                put("from", from)
                if (apiKey != null) put("apiKey", apiKey)
            },
            it
        )
    }) { json.decodeFromString(it) }

    suspend fun deleteEmailSettings(): ApiResult<Unit> =
        authed({ request("DELETE", url("api/v1/settings/email"), null, it) }) { }

    /** Emails the server's copy, which must be at [revision] (409 stale_revision otherwise). */
    suspend fun emailNote(id: String, to: List<String>, revision: Long): ApiResult<Unit> = authed({
        request(
            "POST",
            noteUrl(id).newBuilder().addPathSegment("email").build(),
            buildJsonObject {
                put("to", JsonArray(to.map(::JsonPrimitive)))
                put("revision", revision)
            },
            it
        )
    }) { }

    /** The locked-notes keyring. */
    suspend fun lockKeys(): ApiResult<List<LockKey>> =
        authed({ request("GET", url("api/v1/locks"), null, it) }) { json.decodeFromString<LockKeys>(it).keys }

    /** Creates or replaces a keyring entry; [verifier] proves this device holds the key (the first password entry sets it). */
    suspend fun putLockKey(key: LockKey, verifier: String): ApiResult<LockKey> = authed({
        request(
            "PUT",
            url("api/v1/locks/keys").newBuilder().addPathSegment(key.id).build(),
            buildJsonObject {
                put("kind", key.kind)
                key.credentialId?.let { id -> put("credentialId", id) }
                put("salt", key.salt)
                put("iterations", key.iterations)
                put("wrapped", key.wrapped)
                put("verifier", verifier)
            },
            it
        )
    }) { json.decodeFromString(it) }

    /** Forgets the keyring: notes locked so far can never be opened again. */
    suspend fun resetLocks(accountPassword: String): ApiResult<Unit> = authed({
        request("DELETE", url("api/v1/locks"), buildJsonObject { put("accountPassword", accountPassword) }, it)
    }) { }

    /** Every space (§7.7), the default first. */
    suspend fun spaces(): ApiResult<List<RemoteSpace>> =
        authed({ request("GET", url("api/v1/spaces"), null, it) }) { json.decodeFromString<RemoteSpaces>(it).spaces }

    /** Creates or renames a space; the answer has the name as stored (a clash gets a number). */
    suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace> = authed({
        request(
            "PUT",
            spaceUrl(id),
            buildJsonObject {
                put("name", name)
                put("icon", icon)
            },
            it
        )
    }) { json.decodeFromString(it) }

    /** Deletes an empty space: a Conflict (space_not_empty) while it holds notes. */
    suspend fun deleteSpace(id: String): ApiResult<Unit> = authed({ request("DELETE", spaceUrl(id), null, it) }) { }

    /** Uploads an image (§7.6); [id] is the SHA-256 of [bytes], so a retry stores nothing new. */
    suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit> = authed({ access ->
        Request.Builder()
            .url(imageUrl(id))
            .put(bytes.toRequestBody(type.toMediaType()))
            .header("Authorization", "Bearer $access")
            .build()
    }) { }

    /** Downloads an image with the type it is stored as. */
    suspend fun getImage(id: String): ApiResult<ImageFile> = authedReply({ access ->
        Request.Builder().url(imageUrl(id)).header("Authorization", "Bearer $access").build()
    }) { ImageFile(it.type ?: "application/octet-stream", it.bytes) }

    // --- plumbing ------------------------------------------------------------

    private suspend fun <T> authed(build: (access: String) -> Request, decode: (String) -> T): ApiResult<T> =
        authedReply(build) { decode(it.text) }

    private suspend fun <T> authedReply(build: (access: String) -> Request, decode: (Reply) -> T): ApiResult<T> {
        val creds = credentials ?: return ApiResult.Unauthenticated
        var tokens = creds.tokens() ?: return ApiResult.Unauthenticated
        if (tokens.accessExpiresAt - clock() < REFRESH_EARLY_MS) {
            when (val r = refresh(tokens)) {
                is ApiResult.Ok -> tokens = r.value
                else -> return r.failure()
            }
        }
        val first = sendReply(build(tokens.access), decode)
        if (first != ApiResult.Unauthenticated) return first
        return when (val r = refresh(tokens)) {
            is ApiResult.Ok -> sendReply(build(r.value.access), decode)
            else -> r.failure()
        }
    }

    /** New tokens for [stale], unless another caller already replaced them. */
    private suspend fun refresh(stale: Tokens): ApiResult<Tokens> = refreshLock.withLock {
        val creds = credentials ?: return ApiResult.Unauthenticated
        val current = creds.tokens() ?: return ApiResult.Unauthenticated
        if (current.access != stale.access) return ApiResult.Ok(current)
        val body = buildJsonObject { put("refreshToken", current.refresh) }
        when (val r = send(post("api/v1/auth/refresh", body)) { json.decodeFromString<SignIn>(it) }) {
            is ApiResult.Ok -> {
                // Refresh tokens aren't rotated; keep ours if the answer leaves it out.
                val next =
                    Tokens(
                        r.value.accessToken,
                        r.value.accessExpiresAt,
                        r.value.refreshToken.ifEmpty {
                            current.refresh
                        }
                    )
                creds.refreshed(next)
                ApiResult.Ok(next)
            }

            ApiResult.Unauthenticated -> {
                creds.revoked()
                ApiResult.Unauthenticated
            }

            else -> r.failure()
        }
    }

    /** An answer as received: JSON for most endpoints, bytes for images. */
    private class Reply(val code: Int, val retryAfter: String?, val type: String?, val bytes: ByteArray) {
        val text get() = bytes.decodeToString()
    }

    private suspend fun <T> send(request: Request, decode: (String) -> T): ApiResult<T> =
        sendReply(request) { decode(it.text) }

    private suspend fun <T> sendReply(request: Request, decode: (Reply) -> T): ApiResult<T> = withContext(io) {
        val reply = try {
            http.newCall(request).execute().use {
                val type = it.body.contentType()?.let { t -> "${t.type}/${t.subtype}" }
                Reply(it.code, it.header("Retry-After"), type, it.body.bytes())
            }
        } catch (e: IOException) {
            return@withContext ApiResult.Unreachable(e)
        }
        val code = reply.code
        val retryAfter = reply.retryAfter
        if (code in 200..299) {
            return@withContext try {
                ApiResult.Ok(decode(reply))
            } catch (_: SerializationException) {
                unexpected(code)
            } catch (_: IllegalArgumentException) {
                unexpected(code)
            }
        }
        // Only an error's body is text here: a success may be an image.
        val error = try {
            json.decodeFromString<ErrorBody>(reply.text)
        } catch (_: SerializationException) {
            ErrorBody()
        } catch (_: IllegalArgumentException) {
            ErrorBody()
        }
        when {
            code == 401 && error.error == "unauthenticated" -> ApiResult.Unauthenticated
            code == 409 -> ApiResult.Conflict(error.error, error.message, error.current)
            code == 429 -> ApiResult.RateLimited(retryAfter?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S)
            else -> ApiResult.Rejected(code, error.error.ifEmpty { "http_$code" }, error.message, error.field)
        }
    }

    private fun unexpected(code: Int) =
        ApiResult.Rejected(code, UNEXPECTED_RESPONSE, "The server's answer wasn't understood.", null)

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private fun get(path: String) = Request.Builder().url(url(path)).build()

    private fun post(path: String, body: JsonObject, access: String? = null) = request("POST", url(path), body, access)

    private fun request(method: String, url: HttpUrl, body: JsonObject?, access: String?) = Request.Builder()
        .url(url)
        .method(method, body?.toString()?.toRequestBody(JSON))
        .apply { if (access != null) header("Authorization", "Bearer $access") }
        .build()

    private fun noteUrl(id: String) = url("api/v1/notes").newBuilder().addPathSegment(id).build()

    private fun imageUrl(id: String) = url("api/v1/images").newBuilder().addPathSegment(id).build()

    private fun spaceUrl(id: String) = url("api/v1/spaces").newBuilder().addPathSegment(id).build()

    companion object {
        /** A 2xx answer that isn't the expected JSON: something other than notesd is at this address. */
        const val UNEXPECTED_RESPONSE = "unexpected_response"

        private const val REFRESH_EARLY_MS = 60_000L
        private const val DEFAULT_RETRY_AFTER_S = 60L
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** A non-Ok result, retyped: none of them carry a value. */
private fun <T> ApiResult<*>.failure(): ApiResult<T> = when (this) {
    is ApiResult.Ok -> error("not a failure")
    is ApiResult.Conflict -> this
    ApiResult.Unauthenticated -> ApiResult.Unauthenticated
    is ApiResult.RateLimited -> this
    is ApiResult.Rejected -> this
    is ApiResult.Unreachable -> this
}
