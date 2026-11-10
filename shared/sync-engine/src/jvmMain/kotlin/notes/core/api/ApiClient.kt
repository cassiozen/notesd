package notes.core.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
                            .apply { client.timeZone?.let { header("X-Notesd-Time-Zone", it) } }
                            .build()
                    )
                }
            }
        }
        .build()

    private val slowHttp: OkHttpClient = this.http.newBuilder().readTimeout(SLOW_READ_S, TimeUnit.SECONDS).build()

    // A quiet socket is normal, so no read timeout; our own pings find a dead one (a phone changing networks).
    private val socketHttp: OkHttpClient = this.http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(SOCKET_PING_S, TimeUnit.SECONDS)
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

    /** This session, with whose it is as of now: name, photo, admin. */
    suspend fun session(): ApiResult<SignIn> =
        authed({ request("GET", url("api/v1/auth/session"), null, it) }) { json.decodeFromString(it) }

    /** "" goes back to the username. Answers with the account as it is now, as the two below do. */
    suspend fun setDisplayName(name: String): ApiResult<Person> =
        authed({ request("PATCH", url("api/v1/profile"), buildJsonObject { put("displayName", name) }, it) }) {
            json.decodeFromString(it)
        }

    /** A square JPEG of at most 512 px a side. */
    suspend fun setAvatar(jpeg: ByteArray): ApiResult<Person> = authed({ access ->
        Request.Builder()
            .url(url("api/v1/profile/avatar"))
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .header("Authorization", "Bearer $access")
            .build()
    }) { json.decodeFromString(it) }

    suspend fun removeAvatar(): ApiResult<Person> =
        authed({ request("DELETE", url("api/v1/profile/avatar"), null, it) }) { json.decodeFromString(it) }

    /** Someone's photo at [version]; 404 for one this account may not see, or that's gone. */
    suspend fun avatar(userId: Long, version: String): ApiResult<ByteArray> = authedReply({ access ->
        val avatarUrl = userUrl(userId).newBuilder().addPathSegment("avatar").addQueryParameter("v", version).build()
        Request.Builder().url(avatarUrl).header("Authorization", "Bearer $access").build()
    }) { it.bytes }

    /** Ends this device's session on the server. */
    suspend fun logout(): ApiResult<Unit> = authed({ post("api/v1/auth/logout", JsonObject(emptyMap()), it) }) { }

    /** The body is sent as given: the sync engine builds it once and resends it unchanged on retries. */
    override suspend fun createNote(body: JsonObject, origin: String?): ApiResult<MutationResult> =
        authed({ request("POST", url("api/v1/notes"), body, it, origin) }) { json.decodeFromString(it) }

    override suspend fun updateNote(id: String, body: JsonObject, origin: String?): ApiResult<MutationResult> =
        authed({ request("PUT", noteUrl(id), body, it, origin) }) { json.decodeFromString(it) }

    override suspend fun deleteNote(id: String, body: JsonObject, origin: String?): ApiResult<MutationResult> =
        authed({ request("DELETE", noteUrl(id), body, it, origin) }) { json.decodeFromString(it) }

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

    /**
     * This server's origin, as the app reaches it: a published space's absolute links (its feed) are made from it,
     * since the server can't know its own public address (§7.10).
     */
    val origin: String
        get() {
            val host = if (':' in baseUrl.host) "[${baseUrl.host}]" else baseUrl.host
            val port = if (baseUrl.port == HttpUrl.defaultPort(baseUrl.scheme)) "" else ":${baseUrl.port}"
            return "${baseUrl.scheme}://$host$port"
        }

    /** How a space is published; `404 not_published` if it never was (§7.10). */
    suspend fun publishSettings(spaceId: String): ApiResult<PublishSettings> = authed({
        request("GET", url("api/v1/spaces/${seg(spaceId)}/publish"), null, it)
    }) { json.decodeFromString(it) }

    /** Publishes the space, or changes its [handle]; a null field stays as it is. The first time needs [origin]. */
    suspend fun publish(spaceId: String, handle: String? = null, origin: String? = null): ApiResult<PublishSettings> =
        authed({
            val body = buildJsonObject {
                handle?.let { h -> put("handle", h) }
                origin?.let { o -> put("origin", o) }
            }
            request("PUT", url("api/v1/spaces/${seg(spaceId)}/publish"), body, it)
        }) { json.decodeFromString(it) }

    /** Takes the space's site down; its handle stays its own. */
    suspend fun unpublish(spaceId: String): ApiResult<Unit> = authedReply({
        request("DELETE", url("api/v1/spaces/${seg(spaceId)}/publish"), null, it)
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

    /** An id as one path segment: a `/`, `%` or space in it encoded. */
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

    /** The server's AI provider; everyone may read it, only admins change it. */
    suspend fun aiSettings(): ApiResult<AiSettings> =
        authed({ request("GET", url("api/v1/settings/ai"), null, it) }) { lenient.decodeFromString(it) }

    suspend fun saveAiSettings(input: AiSettingsInput): ApiResult<AiSettings> =
        authed({ request("PUT", url("api/v1/settings/ai"), input.json(), it) }) { lenient.decodeFromString(it) }

    /** Stops every space's agent until it's connected again. */
    suspend fun deleteAiSettings(): ApiResult<Unit> = authedReply({
        request("DELETE", url("api/v1/settings/ai"), null, it)
    }) { require(it.code == 204) { "Not a delete" } }

    /**
     * Calls the model with [input] before it's saved, or with the saved settings when null. A failed call is
     * Rejected 502 `ai_test_failed`, its message the provider's reason.
     */
    suspend fun testAiSettings(input: AiSettingsInput?): ApiResult<AiTestResult> = authed(
        { access ->
            Request.Builder()
                .url(url("api/v1/settings/ai/test"))
                .post(input?.json()?.toString()?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody())
                .header("Authorization", "Bearer $access")
                .build()
        },
        slow = true
    ) { lenient.decodeFromString(it) }

    /** Every space whose agent has been set up. */
    suspend fun agentConfigs(): ApiResult<List<AgentConfig>> =
        authed({ request("GET", url("api/v1/agent/spaces"), null, it) }) {
            lenient.decodeFromString<AgentConfigs>(it).spaces
        }

    /**
     * Replaces a space's agent; Rejected 404 `not_found` when the server doesn't have the space. A null
     * [referenceNotes] keeps the saved ones.
     */
    suspend fun putAgentConfig(
        spaceId: String,
        enabled: Boolean,
        prompt: String,
        menuLabel: String,
        triggers: Map<String, String>,
        referenceNotes: List<String>?
    ): ApiResult<AgentConfig> = authed({
        request(
            "PUT",
            url("api/v1/agent/spaces").newBuilder().addPathSegment(spaceId).build(),
            buildJsonObject {
                put("enabled", enabled)
                put("prompt", prompt)
                put("menuLabel", menuLabel)
                put("triggers", JsonObject(triggers.mapValues { (_, v) -> JsonPrimitive(v) }))
                if (referenceNotes != null) put("referenceNotes", JsonArray(referenceNotes.map(::JsonPrimitive)))
            },
            it
        )
    }) { lenient.decodeFromString(it) }

    /** The activity log, newest first: a [space]'s, a [note]'s, or every run; [cursor] from the page before. */
    suspend fun agentRuns(
        space: String? = null,
        note: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): ApiResult<RunsPage> = authed({
        val url = url("api/v1/agent/runs").newBuilder()
            .apply {
                space?.let { s -> addQueryParameter("space", s) }
                note?.let { n -> addQueryParameter("note", n) }
                limit?.let { l -> addQueryParameter("limit", l.toString()) }
                cursor?.takeIf { c -> c.isNotEmpty() }?.let { c -> addQueryParameter("cursor", c) }
            }
            .build()
        request("GET", url, null, it)
    }) { lenient.decodeFromString(it) }

    /** A run with its diffs. */
    suspend fun agentRun(id: String): ApiResult<AgentRun> =
        authed({ request("GET", runUrl(id), null, it) }) { lenient.decodeFromString(it) }

    /** Conflict `nothing_to_undo` when none of its changes can be: the run's edits then say why. */
    suspend fun undoAgentRun(id: String): ApiResult<UndoResult> = authed({
        request("POST", runUrl(id).newBuilder().addPathSegment("undo").build(), JsonObject(emptyMap()), it)
    }) {
        lenient.decodeFromString(it)
    }

    /** A space's today, against the daily cap per space. */
    suspend fun agentUsage(space: String): ApiResult<AgentUsage> = authed({
        request("GET", url("api/v1/agent/usage").newBuilder().addQueryParameter("space", space).build(), null, it)
    }) { lenient.decodeFromString(it) }

    suspend fun accountSettings(): ApiResult<AccountSettings> =
        authed({ request("GET", url("api/v1/settings/account"), null, it) }) { lenient.decodeFromString(it) }

    /** [timeZone] is IANA; the server refuses one it doesn't know (Rejected 400, field `timeZone`). */
    suspend fun setTimeZone(timeZone: String): ApiResult<AccountSettings> = authed({
        request("PUT", url("api/v1/settings/account"), buildJsonObject { put("timeZone", timeZone) }, it)
    }) { lenient.decodeFromString(it) }

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

    /** [displayName]: a suggestion, which they can change on the link's page. */
    suspend fun invite(username: String, displayName: String = ""): ApiResult<IssuedLink> = authed({
        val body = buildJsonObject {
            put("username", username)
            if (displayName.isNotEmpty()) put("displayName", displayName)
        }
        post("api/v1/users", body, it)
    }) { json.decodeFromString(it) }

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

    /** The access token the socket's upgrade last answered 401 to: the next open refreshes it first. */
    private val socketRefused = AtomicReference<String?>(null)

    /**
     * Opens the agent socket (docs/contributing/agent-clients.md) with the bearer token, refreshed first when
     * it's about to expire or the last upgrade refused it. Sends no `Origin`: that's for a browser's cookie.
     */
    suspend fun agentSocket(listener: WebSocketListener): ApiResult<WebSocket> {
        val creds = credentials ?: return ApiResult.Unauthenticated
        var tokens = creds.tokens() ?: return ApiResult.Unauthenticated
        if (tokens.accessExpiresAt - clock() < REFRESH_EARLY_MS || socketRefused.get() == tokens.access) {
            when (val r = refresh(tokens)) {
                is ApiResult.Ok -> tokens = r.value
                else -> return r.failure()
            }
        }
        val access = tokens.access
        val request = Request.Builder().url(
            url("api/v1/agent/socket")
        ).header("Authorization", "Bearer $access").build()
        val watching = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket, response)

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosing(webSocket, code, reason)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(webSocket, code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) socketRefused.set(access)
                listener.onFailure(webSocket, t, response)
            }
        }
        return ApiResult.Ok(socketHttp.newWebSocket(request, watching))
    }

    // --- plumbing ------------------------------------------------------------

    private suspend fun <T> authed(
        build: (access: String) -> Request,
        slow: Boolean = false,
        decode: (String) -> T
    ): ApiResult<T> = authedReply(build, slow) { decode(it.text) }

    /** [slow]: an answer that may take minutes (a model loading), past the usual read timeout. */
    private suspend fun <T> authedReply(
        build: (access: String) -> Request,
        slow: Boolean = false,
        decode: (Reply) -> T
    ): ApiResult<T> {
        val creds = credentials ?: return ApiResult.Unauthenticated
        var tokens = creds.tokens() ?: return ApiResult.Unauthenticated
        if (tokens.accessExpiresAt - clock() < REFRESH_EARLY_MS) {
            when (val r = refresh(tokens)) {
                is ApiResult.Ok -> tokens = r.value
                else -> return r.failure()
            }
        }
        val first = sendReply(build(tokens.access), slow, decode)
        if (first != ApiResult.Unauthenticated) return first
        return when (val r = refresh(tokens)) {
            is ApiResult.Ok -> sendReply(build(r.value.access), slow, decode)
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
        sendReply(request, slow = false) { decode(it.text) }

    private suspend fun <T> sendReply(request: Request, slow: Boolean, decode: (Reply) -> T): ApiResult<T> =
        withContext(io) {
            val reply = try {
                (if (slow) slowHttp else http).newCall(request).execute().use {
                    val type = it.body.contentType()?.let { t -> "${t.type}/${t.subtype}" }
                    Reply(it.code, it.header("Retry-After"), type, it.body.bytes())
                }
            } catch (e: IOException) {
                return@withContext ApiResult.Unreachable(e)
            }
            apiResult(reply.code, reply.retryAfter, { reply.text }) { decode(reply) }
        }

    /** [path] as written: its ids already through [seg], which a second encoding would turn `%20` into `%2520`. */
    private fun url(path: String) = baseUrl.newBuilder().addEncodedPathSegments(path).build()

    private fun get(path: String) = Request.Builder().url(url(path)).build()

    private fun post(path: String, body: JsonObject, access: String? = null) = request("POST", url(path), body, access)

    private fun request(method: String, url: HttpUrl, body: JsonObject?, access: String?, origin: String? = null) =
        Request.Builder()
            .url(url)
            .method(method, body?.toString()?.toRequestBody(JSON))
            .apply { if (access != null) header("Authorization", "Bearer $access") }
            .apply { if (origin != null) header(WriteOrigin.HEADER, origin) }
            .build()

    private fun noteUrl(id: String) = url("api/v1/notes").newBuilder().addPathSegment(id).build()

    private fun imageUrl(id: String) = url("api/v1/images").newBuilder().addPathSegment(id).build()

    private fun spaceUrl(id: String) = url("api/v1/spaces").newBuilder().addPathSegment(id).build()

    private fun runUrl(id: String) = url("api/v1/agent/runs").newBuilder().addPathSegment(id).build()

    private fun AiSettingsInput.json() = buildJsonObject {
        put("baseUrl", baseUrl)
        put("model", model)
        apiKey?.let { put("apiKey", it) }
        dailyTokenCap?.let { put("dailyTokenCap", it) }
    }

    private fun userUrl(id: Long) = url("api/v1/users").newBuilder().addPathSegment(id.toString()).build()

    companion object {
        /** The account's own feed's prefix; a shared space's is `api/v1/spaces/<id>` (§7.9). */
        private const val FILE = "api/v1"
        private const val REFRESH_EARLY_MS = 60_000L
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        // Go sends an empty list or map as null.
        private val lenient = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /** A model loading on a small server can take minutes to answer; the server waits 2.5. */
        private const val SLOW_READ_S = 160L
        private const val SOCKET_PING_S = 30L

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
