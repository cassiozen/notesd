package notes.core.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
        assertEquals(ApiClient.UNEXPECTED_RESPONSE, assertIs<ApiResult.Rejected>(api.status()).error)
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
