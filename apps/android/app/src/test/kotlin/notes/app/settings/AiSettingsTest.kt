package notes.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import notes.core.api.Session
import notes.core.api.SignIn
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AiSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()

    /** "METHOD path body" of each settings request. */
    private val sent = mutableListOf<String>()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private val off = """{"configured":false,"from":"","keyHint":"","editable":true}"""

    private fun ai(configured: Boolean, editable: Boolean = true) = if (configured) {
        """{"configured":true,"baseUrl":"https://api.example.com/v1","model":"small-model","keyHint":"1234",""" +
            """"dailyTokenCap":5000,"editable":$editable}"""
    } else {
        """{"configured":false,"baseUrl":"","model":"","keyHint":"","dailyTokenCap":1000000,"editable":$editable}"""
    }

    /** Not a row's: those say whose they are ("Connect Resend"). */
    private fun button(label: String) = compose.onNode(
        hasText(label) and hasClickAction() and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)
    ).performScrollTo()

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()

    @Test
    fun `an admin tests what's typed, then connects AI`() {
        var tests = 0
        serve { r, body ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/settings/ai" -> json(200, ai(false))

                "POST /api/v1/settings/ai/test" -> if (++tests == 1) {
                    json(502, """{"error":"ai_test_failed","message":"connection refused"}""")
                } else {
                    json(200, """{"ok":true,"model":"small-model-2025","latencyMs":1240}""")
                }

                "PUT /api/v1/settings/ai" -> json(200, ai(true))

                else -> other(r, body)
            }
        }
        openIntegrations()
        compose.waitUntilExactlyOneExists(hasContentDescription("Connect AI"))
        compose.onNode(hasContentDescription("Connect AI")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Connect AI"))

        button("Connect").performClick()
        compose.onNodeWithText("Enter the API's address.").assertExists()
        compose.onNodeWithText("Paste the provider's API key.").assertExists()
        compose.onNodeWithText("Enter the model's name.").assertExists()

        field("API address").performTextInput("https://api.example.com/v1")
        field("API key").performTextInput("sk-test-1234")
        field("Model").performTextInput("small-model")
        field("Daily tokens per space").performTextClearance()
        field("Daily tokens per space").performTextInput("5000")
        button("Test").performClick()
        compose.waitUntilExactlyOneExists(hasText("The test failed: Connection refused."))
        button("Test").performClick()
        compose.waitUntilExactlyOneExists(hasText("Connected. small-model-2025 called a tool, in 1.2 s."))

        button("Connect").performClick()
        compose.waitUntilExactlyOneExists(hasText("small-model at api.example.com"))
        compose.onNode(hasContentDescription("Manage AI")).assertExists()

        val input = """{"baseUrl":"https://api.example.com/v1","model":"small-model","apiKey":"sk-test-1234",""" +
            """"dailyTokenCap":5000}"""
        assertEquals(
            listOf(
                "POST /api/v1/settings/ai/test $input",
                "POST /api/v1/settings/ai/test $input",
                "PUT /api/v1/settings/ai $input"
            ),
            synchronized(sent) { sent.filter { "/settings/ai" in it && !it.startsWith("GET") } }
        )
    }

    @Test
    fun `managing keeps the saved key, shows the server's refusal on its field, and disconnects`() {
        var configured = true
        serve { r, body ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/settings/ai" -> json(200, ai(configured))

                "PUT /api/v1/settings/ai" -> json(
                    400,
                    """{"error":"invalid_request","message":"the model name is too long","field":"model"}"""
                )

                "DELETE /api/v1/settings/ai" -> {
                    configured = false
                    MockResponse.Builder().code(204).build()
                }

                else -> other(r, body)
            }
        }
        openIntegrations()
        compose.waitUntilExactlyOneExists(hasText("small-model at api.example.com"))
        compose.onNode(hasContentDescription("Manage AI")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved · …1234"))

        button("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("The model name is too long."))

        button("Disconnect…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Disconnect AI?"))
        compose.onNode(hasText("Disconnect") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Connect AI"))

        assertEquals(
            listOf(
                """PUT /api/v1/settings/ai {"baseUrl":"https://api.example.com/v1","model":"small-model",""" +
                    """"dailyTokenCap":5000}""",
                "DELETE /api/v1/settings/ai "
            ),
            synchronized(sent) { sent.filter { it.startsWith("PUT /api/v1/settings/ai") || it.startsWith("DELETE") } }
        )
    }

    @Test
    fun `everyone else sees the state without the controls`() {
        var configured = true
        serve { r, body ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/settings/ai" -> json(200, ai(configured, editable = false))
                else -> other(r, body)
            }
        }
        openIntegrations()
        compose.waitUntilExactlyOneExists(hasText("small-model at api.example.com"))
        compose.onNodeWithText("On").assertExists()
        compose.onNode(hasContentDescription("Manage AI")).assertDoesNotExist()

        configured = false
        runBlocking { graph.ai.cache.refresh() }
        compose.waitUntilExactlyOneExists(hasText("An admin can connect it.", substring = true))
        compose.onNode(hasContentDescription("Connect AI")).assertDoesNotExist()
    }

    @Test
    fun `the account's time zone is picked from a searchable list`() {
        serve { r, body ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/settings/account" -> json(
                    200,
                    """{"timeZone":"America/Sao_Paulo","timeZoneSource":"auto"}"""
                )

                "PUT /api/v1/settings/account" -> json(200, """{"timeZone":"Asia/Tokyo","timeZoneSource":"user"}""")

                else -> other(r, body)
            }
        }
        signIn()
        compose.onNodeWithText("me").performClick()
        compose.waitUntilExactlyOneExists(hasText("America/Sao Paulo"))
        compose.onNodeWithText("Set from the first device that connected.", substring = true).assertExists()

        button("Time zone").performClick()
        compose.onNode(hasSetTextAction() and hasText("Search")).performTextInput("tok")
        compose.waitUntilExactlyOneExists(hasText("Asia/Tokyo") and hasClickAction())
        compose.onNode(hasText("Asia/Tokyo") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Asia/Tokyo"))
        compose.onNodeWithText("Set from the first device that connected.", substring = true).assertDoesNotExist()
        assertEquals(
            listOf("""PUT /api/v1/settings/account {"timeZone":"Asia/Tokyo"}"""),
            synchronized(sent) { sent.filter { it.startsWith("PUT /api/v1/settings/account") } }
        )
    }

    /** Everything else a signed-in app asks for, answered as an empty server would. */
    private fun other(r: RecordedRequest, body: String): MockResponse = when ("${r.method} ${r.url.encodedPath}") {
        "GET /api/v1/settings/email" -> json(200, off)
        "GET /api/v1/settings/account" -> json(200, """{"timeZone":"UTC","timeZoneSource":"default"}""")
        else -> json(404, """{"error":"no_such_endpoint","message":"$body"}""")
    }

    private fun serve(answer: (RecordedRequest, String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                synchronized(sent) { sent += "${request.method} ${request.url.encodedPath} $body" }
                return answer(request, body)
            }
        }
    }

    /** Signed in to [server] as "me", an admin, on Settings' list. */
    private fun signIn() {
        server.start()
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE, true)
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("me"))
    }

    private fun openIntegrations() {
        signIn()
        compose.waitUntilAtLeastOneExists(hasText("Integrations") and hasClickAction())
        compose.onNode(hasText("Integrations") and hasClickAction()).performClick()
    }
}
