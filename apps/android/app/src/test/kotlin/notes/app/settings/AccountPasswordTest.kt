package notes.app.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.account.Account
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
class AccountPasswordTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        server.close()
    }

    @Test
    fun `too many tries says to wait, not to try again now`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(429).addHeader("Retry-After", "30").build()
        }
        openServer()
        compose.onNodeWithText("Current password").performTextInput("whatever it is")
        compose.onNodeWithText("New password").performTextInput("a brand new one")
        compose.onNodeWithText("Confirm new password").performTextInput("a brand new one")
        compose.onNode(hasText("Change") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Too many attempts. Wait a moment and try again."))
    }

    /** Signed in to [server], with Server's Change password dialog open. */
    private fun openServer() {
        server.start()
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE)
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("me"))
        compose.onNodeWithText("me").performClick()
        compose.waitUntilExactlyOneExists(hasText("Change password") and hasClickAction())
        compose.onNode(hasText("Change password") and hasClickAction()).performClick()
    }

    @Test
    fun `the account password changes from Server, after a wrong current one is refused`() {
        val sent = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/api/v1/auth/password") return MockResponse.Builder().code(404).build()
                val body = request.body!!.utf8()
                sent += body
                val current = Json.parseToJsonElement(body).jsonObject["currentPassword"]!!.jsonPrimitive.content
                return if (current == "the right one") {
                    MockResponse.Builder().code(204).build()
                } else {
                    MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                        .body("""{"error":"invalid_credentials","message":"invalid username or password"}""").build()
                }
            }
        }
        openServer()
        compose.onNodeWithText("Current password").performTextInput("not it")
        compose.onNodeWithText("New password").performTextInput("a brand new one")
        compose.onNodeWithText("Confirm new password").performTextInput("a brand new one")
        compose.onNode(hasText("Change") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("That isn't your current password."))
        // Refused, not signed out: the phone keeps its session.
        assertEquals("me", (graph.accounts.account.value as? Account.SignedIn)?.username)

        compose.onNodeWithText("Current password").performTextClearance()
        compose.onNodeWithText("Current password").performTextInput("the right one")
        compose.onNode(hasText("Change") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Password changed.", substring = true))
        assertEquals(2, sent.size)
        val last = Json.parseToJsonElement(sent[1]).jsonObject
        assertEquals("a brand new one", last["newPassword"]?.jsonPrimitive?.content)
    }
}
