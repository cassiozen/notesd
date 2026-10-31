package notes.core.sync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import notes.core.api.ApiResult

/** Emailing through a real notesd, with Resend faked. */
class NoteMailerTest {
    private val resend = MockWebServer().apply { start() }
    private val server = TestServer(resendUrl = resend.url("/").toString().trimEnd('/'))
    private val clients = ArrayList<TestClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
        resend.close()
    }

    private suspend fun client() = TestClient(server).also { clients += it }.signIn()

    private fun accepted() =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"id":"e1"}""").build()

    private fun sentText(): String {
        val body = Json.decodeFromString<JsonObject>(resend.takeRequest().body!!.utf8())
        return body["text"]!!.jsonPrimitive.content
    }

    private suspend fun TestClient.configure() {
        assertIs<ApiResult.Ok<*>>(api.saveEmailSettings("Me <notes@example.com>", "re_123456789"))
    }

    @Test
    fun `sends the text as it is here, syncing it first`() = runBlocking<Unit> {
        val a = client()
        a.configure()
        assertEquals("6789", (a.api.emailSettings() as ApiResult.Ok).value.keyHint)
        a.save("n", "Plans", "Bike\n")
        resend.enqueue(accepted())

        assertEquals(EmailOutcome.Sent, a.mailer().send("n", listOf("sam@example.org")))
        assertEquals("# Plans\n\nBike\n", sentText())
        assertEquals(0, a.notes.pendingNotes())
    }

    @Test
    fun `a change from another device is synced in, then sent`() = runBlocking<Unit> {
        val a = client()
        val b = client()
        a.configure()
        a.save("n", "Plans", "Bike\n")
        assertEquals(SyncOutcome.Done, a.sync())
        assertEquals(SyncOutcome.Done, b.sync())
        b.save("n", "Plans", "Bike and park\n")
        assertEquals(SyncOutcome.Done, b.sync())
        resend.enqueue(accepted())

        assertEquals(EmailOutcome.Sent, a.mailer().send("n", listOf("sam@example.org")))
        assertEquals("# Plans\n\nBike and park\n", sentText())
    }

    @Test
    fun `offline, nothing is sent`() = runBlocking<Unit> {
        val a = client()
        a.configure()
        a.save("n", "Plans", "Bike\n")
        a.offline = true
        assertEquals(EmailOutcome.Offline, a.mailer().send("n", listOf("sam@example.org")))
        assertEquals(0, resend.requestCount)
    }

    @Test
    fun `refusals say why`() = runBlocking<Unit> {
        val a = client()
        a.save("n", "Plans", "Bike\n")
        val notSetUp = a.mailer().send("n", listOf("sam@example.org"))
        assertEquals(EmailOutcome.Failed("Add a Resend API key in Settings to send email."), notSetUp)

        a.configure()
        assertEquals(
            EmailOutcome.Failed("Nope isn't an email address.", "to"),
            a.mailer().send("n", listOf("nope"))
        )
        resend.enqueue(
            MockResponse.Builder().code(403).addHeader("Content-Type", "application/json")
                .body(
                    """{"statusCode":403,"name":"validation_error","message":"The example.com domain is not verified."}"""
                )
                .build()
        )
        assertEquals(
            EmailOutcome.Failed("Resend didn't send it: The example.com domain is not verified."),
            a.mailer().send("n", listOf("sam@example.org"))
        )
    }
}
