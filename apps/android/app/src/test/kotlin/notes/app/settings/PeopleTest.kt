package notes.app.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class PeopleTest {
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

    private val me = """{"id":1,"username":"me","isAdmin":true,"createdAt":1,"pending":false}"""
    private val sam = """{"id":2,"username":"sam","isAdmin":false,"createdAt":1,"pending":false}"""
    private val alex = """{"id":3,"username":"alex","isAdmin":false,"createdAt":1,"pending":true,"linkExpiresAt":${
        System.currentTimeMillis() + 7 * 86_400_000L
    }}"""

    @Test
    fun `an admin invites someone, makes another an admin, and removes them`() {
        serve { r ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/users" -> json(200, """{"users":[$me,$sam]}""")

                "POST /api/v1/users" -> json(
                    201,
                    """{"user":$alex,"link":{"token":"tok","expiresAt":9,"pending":true}}"""
                )

                "PATCH /api/v1/users/2" -> json(200, sam.replace("\"isAdmin\":false", "\"isAdmin\":true"))

                "DELETE /api/v1/users/2" -> MockResponse.Builder().code(204).build()

                else -> json(404, """{"error":"no_such_endpoint"}""")
            }
        }
        openServer(isAdmin = true)
        compose.waitUntilExactlyOneExists(hasText("sam"))
        compose.onNodeWithText("Admin · ", substring = true).assertExists()

        compose.onNode(hasText("Invite someone") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput("alex")
        compose.onNode(hasText("Invite") and hasClickAction()).performClick()
        val url = server.url("/").toString().trimEnd('/') + "/setup#tok"
        compose.waitUntilExactlyOneExists(hasText(url))
        compose.onNode(hasText("Send alex this link")).assertExists()
        compose.onNode(hasText("Done") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Invited · link expires in 7 days"))

        compose.onNode(hasText("sam") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Make admin"))
        compose.onNodeWithText("Make admin").performClick()
        compose.waitUntilExactlyOneExists(hasText("sam is now an admin."))

        compose.onNode(hasText("sam") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Remove from server…"))
        compose.onNodeWithText("Remove from server…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Remove sam?"))
        compose.onNode(hasText("Remove") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("sam was removed."))
        compose.waitUntilDoesNotExist(hasText("sam") and hasClickAction())

        assertEquals(
            listOf(
                "GET /api/v1/users",
                "POST /api/v1/users",
                "PATCH /api/v1/users/2",
                // Fresh before asking: the row may be stale.
                "GET /api/v1/users",
                "DELETE /api/v1/users/2"
            ),
            synchronized(sent) { sent.filter { "/users" in it } }
        )
    }

    @Test
    fun `a new link for someone who has joined since the list loaded says it's a reset`() {
        serve { r ->
            when ("${r.method} ${r.url.encodedPath}") {
                "GET /api/v1/users" -> json(200, """{"users":[$me,$alex]}""")

                "POST /api/v1/users/3/link" -> json(
                    200,
                    """{"user":${alex.replace(
                        "\"pending\":true",
                        "\"pending\":false"
                    )},"link":{"token":"t2","expiresAt":9}}"""
                )

                else -> json(404, """{"error":"no_such_endpoint"}""")
            }
        }
        openServer(isAdmin = true)
        compose.waitUntilExactlyOneExists(hasText("Invited · link expires in 7 days"))
        compose.onNode(hasText("alex") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Share new link"))
        compose.onNodeWithText("Share new link").performClick()
        compose.waitUntilExactlyOneExists(hasText("choose a new password", substring = true))
        compose.onNode(hasText("Done") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilDoesNotExist(hasText("Invited", substring = true))
    }

    @Test
    fun `someone who isn't an admin never sees People`() {
        serve { json(403, """{"error":"admin_only","message":"only an admin can do this"}""") }
        // Signed in as one, since demoted: the server's answer wins, and is kept.
        openServer(isAdmin = true)
        compose.waitUntil { graph.accounts.account.value == Account.SignedIn(server.url("/").toString(), "me") }
        compose.waitUntilDoesNotExist(hasText("People"))
        assertEquals(false, (graph.accounts.account.value as Account.SignedIn).isAdmin)
    }

    @Test
    fun `an admin is told when the server can't be reached, and can try again`() {
        var up = false
        serve { if (up) json(200, """{"users":[$me,$sam]}""") else json(503, """{"error":"unavailable"}""") }
        openServer(isAdmin = true)
        compose.waitUntilExactlyOneExists(hasText("Couldn't reach the server"))
        up = true
        compose.onNode(hasText("Try again") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("sam"))
    }

    private fun serve(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(sent) { sent += "${request.method} ${request.url.encodedPath}" }
                return answer(request)
            }
        }
    }

    /** Signed in to [server] as "me", on Settings' Server page. */
    private fun openServer(isAdmin: Boolean) {
        server.start()
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE, isAdmin)
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("me"))
        compose.onNodeWithText("me").performClick()
        compose.waitUntilExactlyOneExists(hasText("Change password") and hasClickAction())
    }
}
