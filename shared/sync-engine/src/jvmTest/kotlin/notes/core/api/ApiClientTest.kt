package notes.core.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class ApiClientTest {
    private val server = MockWebServer().apply { start() }
    private var now = 1_000_000L

    private class FakeCredentials(var tokens: Tokens?) : Credentials {
        var revoked = false

        override suspend fun tokens() = tokens

        override suspend fun refreshed(tokens: Tokens) {
            this.tokens = tokens
        }

        override suspend fun revoked() {
            revoked = true
            tokens = null
        }
    }

    private val creds = FakeCredentials(Tokens("a1", now + 3_600_000, "r1"))
    private val api = ApiClient(server.url("/"), creds, clock = { now })

    @AfterTest
    fun stop() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(code)
        .body(body)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()

    private val unauthenticated = json(401, """{"error":"unauthenticated","message":"authentication required"}""")
    private fun refreshed(access: String) = json(
        200,
        """{"session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1},"accessToken":"$access","accessExpiresAt":${now + 3_600_000},"refreshToken":"r1"}"""
    )

    @Test
    fun `status and login`() = runBlocking<Unit> {
        server.enqueue(json(200, """{"setupRequired":false}"""))
        assertEquals(ApiResult.Ok(AuthStatus(false)), api.status())
        server.enqueue(
            json(
                200,
                """{"session":{"id":"s1","kind":"device","name":"Pixel","createdAt":1,"lastUsedAt":2},"username":"me","accessToken":"a","accessExpiresAt":5,"refreshToken":"r","refreshExpiresAt":6}"""
            )
        )
        val login = assertIs<ApiResult.Ok<SignIn>>(api.login("me", "secret", "Pixel"))
        assertEquals(Tokens("a", 5, "r"), login.value.tokens)
        server.takeRequest()
        val sent = server.takeRequest()
        assertEquals("/api/v1/auth/login", sent.url.encodedPath)
        assertEquals(
            """{"username":"me","password":"secret","client":"device","deviceName":"Pixel"}""",
            sent.body!!.utf8()
        )
    }

    @Test
    fun `a wrong password is a rejection, not a lost session`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":"invalid_credentials","message":"invalid username or password"}"""))
        val r = assertIs<ApiResult.Rejected>(api.login("me", "nope", "P"))
        assertEquals("invalid_credentials", r.error)
    }

    @Test
    fun `rate limiting reports when to retry`() = runBlocking<Unit> {
        server.enqueue(json(429, """{"error":"rate_limited"}""", "Retry-After" to "42"))
        assertEquals(ApiResult.RateLimited(42), api.login("me", "x", "P"))
    }

    @Test
    fun `an unreachable server is a value`() = runBlocking<Unit> {
        server.close()
        assertIs<ApiResult.Unreachable>(api.status())
    }

    @Test
    fun `something that isn't notesd`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("<html>router</html>").build())
        assertEquals(ApiResult.Unexpected(200), api.status())
    }

    private fun html(code: Int) = MockResponse.Builder().code(code).body("<html>Sign in to the Wi-Fi</html>").build()

    @Test
    fun `only notesd's own errors are classified, anything else is unexpected`() = runBlocking<Unit> {
        val body = buildJsonObject { put("operationId", "op") }
        for (code in listOf(400, 401, 403, 404, 409, 413, 415, 426)) {
            server.enqueue(html(code))
            assertEquals(ApiResult.Unexpected(code), api.updateNote("n", body), "HTTP $code")
        }
        // A proxy's 5xx or 429 page means what notesd's would.
        server.enqueue(html(502))
        assertEquals("http_502", assertIs<ApiResult.Rejected>(api.updateNote("n", body)).error)
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "7").body("<html/>").build())
        assertEquals(ApiResult.RateLimited(7), api.updateNote("n", body))
        // An unknown code is notesd's: the caller decides what it means for its request.
        server.enqueue(json(422, """{"error":"something_new","message":"m"}"""))
        assertEquals(ApiResult.Rejected(422, "something_new", "m", null), api.updateNote("n", body))
        // A success that isn't what the endpoint returns.
        server.enqueue(json(200, """{"hello":"world"}"""))
        assertEquals(ApiResult.Unexpected(200), api.updateNote("n", body))
    }

    @Test
    fun `redirects aren't followed`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/portal")).build())
        assertEquals(ApiResult.Unexpected(302), api.sync(0, 10, null))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an upgrade the server asks for`() = runBlocking<Unit> {
        server.enqueue(
            json(426, """{"error":"upgrade_required","message":"update notesd","minVersion":"1.2.0"}""")
        )
        assertEquals(ApiResult.UpgradeRequired("1.2.0"), api.sync(0, 10, null))
        server.enqueue(json(426, """{"error":"upgrade_required","message":"update notesd"}"""))
        assertEquals(ApiResult.UpgradeRequired(null), api.sync(0, 10, null))
    }

    @Test
    fun `a space delete is a 204 and an image upload answers with the image`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(ApiResult.Ok(Unit), api.deleteSpace("s"))
        server.enqueue(json(200, "{}"))
        assertEquals(ApiResult.Unexpected(200), api.deleteSpace("s"))

        val id = "a".repeat(64)
        server.enqueue(json(201, """{"id":"$id","contentType":"image/png","size":3,"createdAt":1}"""))
        assertEquals(ApiResult.Ok(Unit), api.putImage(id, byteArrayOf(1, 2, 3), "image/png"))
        server.enqueue(MockResponse.Builder().code(200).body("<html/>").build())
        assertEquals(ApiResult.Unexpected(200), api.putImage(id, byteArrayOf(1, 2, 3), "image/png"))
        server.enqueue(json(201, """{"id":"${"b".repeat(64)}","contentType":"image/png"}"""))
        assertEquals(ApiResult.Unexpected(201), api.putImage(id, byteArrayOf(1, 2, 3), "image/png"))
    }

    @Test
    fun `every request says which app sent it`() = runBlocking<Unit> {
        val client = ClientInfo("android", "v1.0.0", "Europe/Lisbon")
        val app = ApiClient(server.url("/"), creds, clock = { now }, client = client)
        server.enqueue(json(200, """{"setupRequired":false,"serverVersion":"v1.0.0","apiVersion":1}"""))
        assertEquals(ApiResult.Ok(AuthStatus(false, "v1.0.0", 1)), app.status())
        server.enqueue(MockResponse.Builder().code(204).build())
        app.logout()
        repeat(2) {
            val sent = server.takeRequest()
            assertEquals("android/1.0.0", sent.headers["X-Notesd-Client"])
            assertEquals("notesd-android/1.0.0", sent.headers["User-Agent"])
            assertEquals("Europe/Lisbon", sent.headers["X-Notesd-Time-Zone"])
        }
    }

    @Test
    fun `a keyring entry goes with its kdf`() = runBlocking<Unit> {
        val kdf = buildJsonObject {
            put("name", "pbkdf2-sha256")
            put("iterations", 600000)
        }
        val key = LockKey("password", "password", salt = "s", iterations = 600000, wrapped = "w", kdf = kdf)
        server.enqueue(
            json(
                200,
                """{"id":"password","kind":"password","salt":"s","iterations":600000,"wrapped":"w","createdAt":1,"kdf":$kdf}"""
            )
        )
        assertEquals(kdf, assertIs<ApiResult.Ok<LockKey>>(api.putLockKey(key, "v")).value.kdf)
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(kdf, sent["kdf"])
    }

    @Test
    fun `a 401 refreshes once and resends the same request`() = runBlocking<Unit> {
        server.enqueue(unauthenticated)
        server.enqueue(refreshed("a2"))
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(ApiResult.Ok(Unit), api.logout())
        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer a1", first.headers["Authorization"])
        assertEquals("/api/v1/auth/refresh", refresh.url.encodedPath)
        assertEquals("""{"refreshToken":"r1"}""", refresh.body!!.utf8())
        assertEquals("Bearer a2", retry.headers["Authorization"])
        assertEquals(first.body!!.utf8(), retry.body!!.utf8())
        assertEquals("a2", creds.tokens!!.access)
    }

    @Test
    fun `a token about to expire is refreshed first`() = runBlocking<Unit> {
        creds.tokens = Tokens("a1", now + 30_000, "r1")
        server.enqueue(refreshed("a2"))
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(ApiResult.Ok(Unit), api.logout())
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer a2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a refused refresh ends the session`() = runBlocking<Unit> {
        server.enqueue(unauthenticated)
        server.enqueue(unauthenticated)
        assertEquals(ApiResult.Unauthenticated, api.logout())
        assertEquals(true, creds.revoked)
        assertNull(creds.tokens)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refresh that can't reach the server keeps the session`() = runBlocking<Unit> {
        creds.tokens = Tokens("a1", now + 30_000, "r1")
        server.close()
        assertIs<ApiResult.Unreachable>(api.logout())
        assertEquals(false, creds.revoked)
        assertEquals("a1", creds.tokens!!.access)
    }

    @Test
    fun `concurrent 401s share one refresh`() = runBlocking<Unit> {
        // Answered by what each request is, not arrival order: the calls race, so
        // one may refresh before the other's first request reaches the server.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/api/v1/auth/refresh" -> refreshed("a2")
                request.headers["Authorization"] == "Bearer a2" -> MockResponse.Builder().code(204).build()
                else -> unauthenticated
            }
        }
        val results = listOf(async { api.logout() }, async { api.logout() }).awaitAll()
        assertEquals(listOf(ApiResult.Ok(Unit), ApiResult.Ok(Unit)), results)
        val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
        assertEquals(1, paths.count { it == "/api/v1/auth/refresh" })
    }

    @Test
    fun `no session, no request`() = runBlocking<Unit> {
        assertEquals(ApiResult.Unauthenticated, ApiClient(server.url("/")).logout())
        assertEquals(0, server.requestCount)
    }
}
