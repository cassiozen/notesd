package notes.app.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import notes.app.account.Account
import notes.app.account.Profile
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
class ProfileTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()
    private val sent = mutableListOf<String>()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun account(name: String) =
        """{"id":1,"username":"me","isAdmin":true,"createdAt":1,"pending":false,"displayName":"$name","avatar":""}"""

    private val session =
        """{"session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1},""" +
            """"userId":1,"username":"me","isAdmin":true,"displayName":"Maria","avatar":""}"""

    @Test
    fun `the name follows the server, and is changed from Server`() {
        serve { r ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/auth/session" -> json(200, session)

                "PATCH /api/v1/profile" -> json(200, account("Ana"))

                "GET /api/v1/users" -> json(
                    200,
                    """{"users":[${account("Maria")},{"id":2,"username":"sam","isAdmin":false,""" +
                        """"createdAt":1,"pending":false,"displayName":"Sam","avatar":""}]}"""
                )

                else -> json(404, """{"error":"no_such_endpoint"}""")
            }
        }
        signIn()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        // Changed on another device: opening Settings asks.
        compose.waitUntilExactlyOneExists(hasText("Maria"))
        compose.onNode(hasText("Maria")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Shown as Maria"))
        // People: their chosen name, with the username beside how they joined.
        compose.waitUntilExactlyOneExists(hasText("Sam"))
        compose.waitUntilExactlyOneExists(hasText("sam · Joined", substring = true))

        compose.onNode(hasText("Name and photo") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction() and hasText("Name"))
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Ana")
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Shown as Ana"))
        assertEquals(Profile(1, "Ana", ""), (graph.accounts.account.value as Account.SignedIn).profile)
        assertEquals(listOf("""{"displayName":"Ana"}"""), synchronized(sent) { sent.filter { "displayName" in it } })
    }

    @Test
    fun `an invitation can suggest a name`() {
        serve { r ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/users" -> json(200, """{"users":[${account("")}]}""")

                "POST /api/v1/users" -> json(
                    201,
                    """{"user":{"id":3,"username":"mum","isAdmin":false,"createdAt":1,"pending":true,""" +
                        """"displayName":"Mum","avatar":""},"link":{"token":"tok","expiresAt":9}}"""
                )

                else -> json(404, """{"error":"no_such_endpoint"}""")
            }
        }
        signIn()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("me"))
        compose.onNode(hasText("me")).performClick()
        compose.onNode(hasText("Invite someone") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput("mum")
        compose.onNode(hasSetTextAction() and hasText("Name (optional)")).performTextInput("Mum")
        compose.onNode(hasText("Invite") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Send Mum this link"))
        assertEquals(
            listOf("""{"username":"mum","displayName":"Mum"}"""),
            synchronized(sent) { sent.filter { "username" in it } }
        )
    }

    private fun serve(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.body?.utf8()?.takeIf { it.isNotEmpty() }?.let { synchronized(sent) { sent += it } }
                return answer(request)
            }
        }
    }

    /** Signed in to [server] as "me", an admin, with no name yet as far as this phone knows. */
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
    }
}
