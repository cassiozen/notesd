package notes.core.api

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.khronos.webgl.Int8Array
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.SAME_ORIGIN

/**
 * [SyncApi] for the web app: same origin, the session cookie, and the CSRF
 * token on every mutation (§10). The bodies are the sync engine's, sent as given.
 */
class FetchApi(private val csrfToken: () -> String?) : SyncApi {
    override suspend fun createNote(body: JsonObject): ApiResult<MutationResult> =
        send("POST", "/notes", body.toString()) { json.decodeFromString(it) }

    override suspend fun updateNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        send("PUT", "/notes/${enc(id)}", body.toString()) { json.decodeFromString(it) }

    override suspend fun deleteNote(id: String, body: JsonObject): ApiResult<MutationResult> =
        send("DELETE", "/notes/${enc(id)}", body.toString()) { json.decodeFromString(it) }

    override suspend fun sync(since: Long, limit: Int, generation: String?): ApiResult<SyncPage> {
        val gen = generation?.let { "&generation=${enc(it)}" }.orEmpty()
        return send("GET", "/sync?since=$since&limit=$limit$gen", null) { json.decodeFromString(it) }
    }

    override suspend fun lockKeys(): ApiResult<List<LockKey>> =
        send("GET", "/locks", null) { json.decodeFromString<LockKeys>(it).keys }

    override suspend fun spaces(): ApiResult<List<RemoteSpace>> =
        send("GET", "/spaces", null) { json.decodeFromString<RemoteSpaces>(it).spaces }

    override suspend fun putSpace(id: String, name: String, icon: String): ApiResult<RemoteSpace> = send(
        "PUT",
        "/spaces/${enc(id)}",
        buildJsonObject {
            put("name", name)
            put("icon", icon)
        }.toString()
    ) { json.decodeFromString(it) }

    override suspend fun deleteSpace(id: String): ApiResult<Unit> = send("DELETE", "/spaces/${enc(id)}", null) { }

    override suspend fun putImage(id: String, bytes: ByteArray, type: String): ApiResult<Unit> =
        send("PUT", "/images/${enc(id)}", bytes.unsafeCast<Int8Array>(), type) { }

    private suspend fun <T> send(
        method: String,
        path: String,
        body: Any?,
        contentType: String = "application/json",
        decode: (String) -> T
    ): ApiResult<T> {
        val headers = js("({})")
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = contentType
        if (method != "GET") {
            // Opened offline, the page has no token until the server answers it: wait, as if offline.
            // Sent without one, the write would be refused and dropped as if for good.
            val token = csrfToken()?.takeIf { it.isNotEmpty() }
                ?: return ApiResult.Unreachable(IllegalStateException("No session token yet"))
            headers["X-CSRF-Token"] = token
        }
        val init =
            RequestInit(method = method, headers = headers, body = body, credentials = RequestCredentials.SAME_ORIGIN)
        val (res, text) = try {
            // globalThis, not window: the engine may run in a worker.
            val r = fetch("/api/v1$path", init).await()
            r to r.text().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ApiResult.Unreachable(e)
        }
        return apiResult(res.status.toInt(), res.headers.get("Retry-After"), { text }) { decode(text) }
    }

    private fun fetch(url: String, init: RequestInit): kotlin.js.Promise<org.w3c.fetch.Response> =
        js("globalThis").fetch(url, init).unsafeCast<kotlin.js.Promise<org.w3c.fetch.Response>>()

    private fun enc(s: String): String = js("encodeURIComponent")(s).unsafeCast<String>()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
