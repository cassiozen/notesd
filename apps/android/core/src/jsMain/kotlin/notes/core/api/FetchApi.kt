package notes.core.api

import kotlinx.coroutines.await
import kotlinx.serialization.SerializationException
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
        if (method != "GET") csrfToken()?.let { headers["X-CSRF-Token"] = it }
        val init =
            RequestInit(method = method, headers = headers, body = body, credentials = RequestCredentials.SAME_ORIGIN)
        val res = try {
            // globalThis, not window: the engine may run in a worker.
            fetch("/api/v1$path", init).await()
        } catch (e: Throwable) {
            return ApiResult.Unreachable(e)
        }
        val text = try {
            res.text().await()
        } catch (e: Throwable) {
            return ApiResult.Unreachable(e)
        }
        val code = res.status.toInt()
        if (code in 200..299) {
            return try {
                ApiResult.Ok(decode(text))
            } catch (_: SerializationException) {
                unexpected(code)
            } catch (_: IllegalArgumentException) {
                unexpected(code)
            }
        }
        val error = try {
            json.decodeFromString<ErrorBody>(text)
        } catch (_: SerializationException) {
            ErrorBody()
        } catch (_: IllegalArgumentException) {
            ErrorBody()
        }
        return when {
            code == 401 && error.error == "unauthenticated" -> ApiResult.Unauthenticated
            code == 409 -> ApiResult.Conflict(error.error, error.message, error.current)
            code == 429 -> ApiResult.RateLimited(res.headers.get("Retry-After")?.trim()?.toLongOrNull() ?: 60)
            else -> ApiResult.Rejected(code, error.error.ifEmpty { "http_$code" }, error.message, error.field)
        }
    }

    private fun unexpected(code: Int) =
        ApiResult.Rejected(code, "unexpected_response", "The server's answer wasn't understood.", null)

    private fun fetch(url: String, init: RequestInit): kotlin.js.Promise<org.w3c.fetch.Response> =
        js("globalThis").fetch(url, init).unsafeCast<kotlin.js.Promise<org.w3c.fetch.Response>>()

    private fun enc(s: String): String = js("encodeURIComponent")(s).unsafeCast<String>()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
