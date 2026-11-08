package notes.core.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * Redirects aren't followed: notesd never sends one, so one comes from
 * something in between (a portal's sign-in page) and is
 * [ApiResult.Unexpected]. Every request says which app sent it ([client]),
 * so the server can ask for an upgrade.
 */
class ApiClient(
    val baseUrl: HttpUrl,
    private val credentials: Credentials? = null,
    http: OkHttpClient = defaultHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    client: ClientInfo? = null
) : SyncApi {
    private val refreshLock = Mutex()

    private val http: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (client != null) {
                addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-Notesd-Client", client.header)
                            .header("User-Agent", client.userAgent)
                            .build()
                    )
                }
            }
        }
        .build()

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

    /** The account's password; the server signs out every other session, this one stays. */
    suspend fun changePassword(current: String, next: String): ApiResult<Unit> = authed({
        post(
            "api/v1/auth/password",
            buildJsonObject {
                put("currentPassword", current)
                put("newPassword", next)
            },
            it
        )
    }) { }

    /** Ends this device's session on the server. */
    suspend fun logout(): ApiResult<Unit> = authed({ post("api/v1/auth/logout", JsonObject(emptyMap()), it) }) { }

    /** The body is sent as given: the sync engine builds it once and resends it unchanged on retries. */
    override suspend fun createNote(body: JsonObject): ApiResult<MutationResult> =
        authed({ request("POST", url("api/v1/notes"), body, it) }) { json.decodeFromString(it) }

    override suspend fun updateNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        authed({ request("PUT", noteUrl(id), body, it) }) { json.decodeFromString(it) }

    override suspend fun deleteNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        authed({ request("DELETE", noteUrl(id), body, it) }) { json.decodeFromString(it) }

    override suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage> =
        sync(FILE, since, limit, generation)

    override fun inSpace(spaceId: String): SyncApi = SpaceApi(spaceId)

    override suspend fun invites(): ApiResult<List<RemoteInvite>> =
        authed({ request("GET", url("api/v1/invites"), null, it) }) { json.decodeFromString<RemoteInvites>(it).invites }

    /** Every account's id and username, to invite into a space (§7.9). */
    suspend fun accounts(): ApiResult<List<Member>> =
        authed({ request("GET", url("api/v1/people"), null, it) }) { json.decodeFromString<RemotePeople>(it).people }

    /** Invites an account into a space this account owns, sharing it the first time (§7.9). */
    suspend fun inviteToSpace(spaceId: String, userId: Long): ApiResult<Invitee> = authed({
        post("api/v1/spaces/${seg(spaceId)}/invites", buildJsonObject { put("userId", userId) }, it)
    }) { json.decodeFromString(it) }

    suspend fun cancelInvite(spaceId: String, inviteId: String): ApiResult<Unit> = authedReply({
        request("DELETE", url("api/v1/spaces/${seg(spaceId)}/invites/${seg(inviteId)}"), null, it)
    }) { require(it.code == 204) { "Not a delete" } }

    /** Joins the space; the answer is the space as this account now sees it. */
    suspend fun acceptInvite(inviteId: String): ApiResult<RemoteSpace> = authed({
        post("api/v1/invites/${seg(inviteId)}/accept", JsonObject(emptyMap()), it)
    }) { json.decodeFromString(it) }

    suspend fun declineInvite(inviteId: String): ApiResult<Unit> = authedReply({
        request("POST", url("api/v1/invites/${seg(inviteId)}/decline"), JsonObject(emptyMap()), it)
    }) { require(it.code == 204) { "Not a decline" } }

    /** The owner removes a member, or ([userId] this account's) a member leaves. */
    suspend fun removeMember(spaceId: String, userId: Long): ApiResult<Unit> = authedReply({
        request("DELETE", url("api/v1/spaces/${seg(spaceId)}/members/$userId"), null, it)
    }) { require(it.code == 204) { "Not a delete" } }

    /** The owner hands the space to another member. */
    suspend fun transferSpace(spaceId: String, userId: Long): ApiResult<Unit> = authedReply({
        request("PUT", url("api/v1/spaces/${seg(spaceId)}/owner"), buildJsonObject { put("userId", userId) }, it)
    }) { require(it.code == 204) { "Not a handover" } }

    private fun seg(s: String) =
        HttpUrl.Builder().scheme("http").host("x").addPathSegment(s).build().encodedPath.drop(1)

    /** A shared space's change feed: the one call that's per file (§7.9). */
    private inner class SpaceApi(private val spaceId: String) : SyncApi by this@ApiClient {
        private val file = "api/v1/spaces/${seg(spaceId)}"

        override fun inSpace(spaceId: String): SyncApi = this@ApiClient.inSpace(spaceId)

        override suspend fun sync(since: Long, limit: Int, generation: String?) = sync(file, since, limit, generation)
    }

    /** Changes after [since]. [generation] is the one stored from the last page, null before the first sync. */
    private suspend fun sync(file: String, since: Long, limit: Int, generation: String?): ApiResult<SyncPage> =
        authed({ access ->
            val url = url("$file/sync").newBuilder()
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
    override suspend fun lockKeys(): ApiResult<List<LockKey>> =
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
                key.kdf?.let { kdf -> put("kdf", kdf) }
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
    override suspend fun spaces(): ApiResult<List<RemoteSpace>> =
        authed({ request("GET", url("api/v1/spaces"), null, it) }) { json.decodeFromString<RemoteSpaces>(it).spaces }

    /** Creates or renames a space; the answer has the name as stored (a clash gets a number). */
    override suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace> = authed({
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
    override suspend fun deleteSpace(id: String): ApiResult<Unit> =
        authedReply({ request("DELETE", spaceUrl(id), null, it) }) { require(it.code == 204) { "Not a delete" } }

    /** Uploads an image (§7.6); [id] is the SHA-256 of [bytes], so a retry stores nothing new. */
    override suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit> = authed({ access ->
        Request.Builder()
            .url(imageUrl(id))
            .put(bytes.toRequestBody(type.toMediaType()))
            .header("Authorization", "Bearer $access")
            .build()
    }) { checkStoredImage(id, it) }

    /** Downloads an image with the type it is stored as. */
    suspend fun getImage(id: String): ApiResult<ImageFile> = authedReply({ access ->
        Request.Builder().url(imageUrl(id)).header("Authorization", "Bearer $access").build()
    }) { ImageFile(it.type ?: "application/octet-stream", it.bytes) }

    /** Admins only: everyone else gets 403 admin_only. */
    suspend fun people(): ApiResult<List<Person>> =
        authed({ request("GET", url("api/v1/users"), null, it) }) { json.decodeFromString<People>(it).users }

    suspend fun invite(username: String): ApiResult<IssuedLink> =
        authed({ post("api/v1/users", buildJsonObject { put("username", username) }, it) }) {
            json.decodeFromString(it)
        }

    /** Replaces the account's link; its password keeps working until the new one is used. */
    suspend fun newSetupLink(id: Long): ApiResult<IssuedLink> = authed({
        request("POST", userUrl(id).newBuilder().addPathSegment("link").build(), JsonObject(emptyMap()), it)
    }) { json.decodeFromString(it) }

    suspend fun setAdmin(id: Long, isAdmin: Boolean): ApiResult<Person> =
        authed({ request("PATCH", userUrl(id), buildJsonObject { put("isAdmin", isAdmin) }, it) }) {
            json.decodeFromString(it)
        }

    suspend fun removePerson(id: Long): ApiResult<Unit> =
        authedReply({ request("DELETE", userUrl(id), null, it) }) { require(it.code == 204) { "Not a delete" } }

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
        apiResult(reply.code, reply.retryAfter, { reply.text }) { decode(reply) }
    }

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

    private fun userUrl(id: Long) = url("api/v1/users").newBuilder().addPathSegment(id.toString()).build()

    companion object {
        /** The account's own feed's prefix; a shared space's is `api/v1/spaces/<id>` (§7.9). */
        private const val FILE = "api/v1"
        private const val REFRESH_EARLY_MS = 60_000L
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
    is ApiResult.UpgradeRequired -> this
    is ApiResult.Unexpected -> this
}
