package notes.app.account

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
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
class OnboardingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val server = MockWebServer().apply { start() }
    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var activity: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        activity?.close()
        graph.database.close()
        server.close()
    }

    private fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(code)
        .body(body)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()

    private val loggedIn = json(
        200,
        """{"session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1},"username":"me",""" +
            """"accessToken":"a","accessExpiresAt":${System.currentTimeMillis() + 3_600_000},"refreshToken":"r"}"""
    )

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    private fun enterServer() {
        compose.waitUntilExactlyOneExists(hasText("Connect to your server"))
        compose.onNodeWithText("Server address").performTextInput(server.url("/").toString())
        button("Continue").performClick()
    }

    @Test
    fun `first run can skip straight to the notes`() {
        launch()
        compose.waitUntilExactlyOneExists(hasText("Use without a server"))
        compose.onNodeWithText("Use without a server").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
        assertEquals(true, graph.onboardingDismissed.value)
    }

    @Test
    fun `sign in, after a wrong password`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.enqueue(json(200, """{"setupRequired":false}"""))
        server.enqueue(json(401, """{"error":"invalid_credentials","message":"invalid username or password"}"""))
        server.enqueue(loggedIn)
        launch()
        enterServer()
        compose.waitUntilExactlyOneExists(hasText("Sign in") and hasClickAction())
        compose.onNodeWithText("Username").performTextInput("me")
        compose.onNodeWithText("Password").performTextInput("wrong-one")
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("Wrong username or password."))
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
        assertEquals(Account.SignedIn(server.url("/").toString(), "me"), graph.accounts.account.value)
    }

    @Test
    fun `a new server goes straight to creating the account`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.enqueue(json(200, """{"setupRequired":true}"""))
        server.enqueue(json(201, """{"status":"created"}"""))
        server.enqueue(loggedIn)
        launch()
        enterServer()
        compose.waitUntilExactlyOneExists(hasText("Create your account"))
        compose.onNodeWithText("Username").performTextInput("me")
        compose.onNodeWithText("Password").performTextInput("long enough")
        compose.onNodeWithText("Confirm password").performTextInput("long enough")
        button("Create account").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes yet"))
        assertEquals(Account.SignedIn(server.url("/").toString(), "me"), graph.accounts.account.value)
    }

    @Test
    fun `signed out, notes stay and a banner offers sign-in to the same server`() {
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", 0, "r", 0)
            )
            graph.accounts.revoked()
            graph.store.save("n", "Kept", "")
        }
        server.enqueue(loggedIn)
        launch()
        compose.waitUntilExactlyOneExists(hasText("Kept"))
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sign in again"))
        compose.onNodeWithText("Password").performTextInput("pw")
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("Kept"))
        compose.waitUntil { graph.accounts.account.value is Account.SignedIn }
    }

    @Test
    fun `signed out, someone else can't sign in and take this phone's notes`() {
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", 0, "r", 0)
            )
            graph.accounts.revoked()
            graph.store.save("n", "Kept", "")
        }
        // The username field is fixed, so this is the server answering with another account than the one typed.
        server.enqueue(
            json(
                200,
                """{"session":{"id":"s","kind":"device","name":"P","createdAt":1,"lastUsedAt":1},"username":"kid",""" +
                    """"accessToken":"a","accessExpiresAt":${System.currentTimeMillis() + 3_600_000},"refreshToken":"r"}"""
            )
        )
        launch()
        compose.waitUntilExactlyOneExists(hasText("Kept"))
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sign in again"))
        compose.onNodeWithText("Password").performTextInput("pw")
        button("Sign in").performClick()
        compose.waitUntilExactlyOneExists(hasText("This phone has me's notes", substring = true))
        assertEquals(Account.SignedOut(server.url("/").toString(), "me"), graph.accounts.account.value)
    }
}
