package notes.app.notes

import android.content.Intent
import android.content.pm.LabeledIntent
import android.os.Build
import android.service.chooser.ChooserAction
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.settings.Sender
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
class EmailTest {
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

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `the share sheet gets an Email action that comes back to the note`() {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), null)
        addEmailAction(ApplicationProvider.getApplicationContext(), chooser, "n1")
        @Suppress("DEPRECATION")
        val actions = chooser.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS)!!
        assertEquals(listOf("Email"), actions.map { (it as ChooserAction).label.toString() })
        assertNull(chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `before custom actions, Email is a target of its own`() {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), null)
        addEmailAction(ApplicationProvider.getApplicationContext(), chooser, "n1")
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.single() as LabeledIntent
        assertEquals("Email", target.nonLocalizedLabel)
        assertEquals(MainActivity.ACTION_EMAIL_NOTE, target.action)
        assertEquals("n1", target.getStringExtra(MainActivity.EXTRA_NOTE_ID))
    }

    @Test
    fun `choosing Email opens the note and asks where to send it`() {
        graph.setOnboardingDismissed(true)
        runBlocking { graph.store.save("n1", "Plans", "Bike\n") }
        scenario = ActivityScenario.launch(emailIntent(ApplicationProvider.getApplicationContext(), "n1"))
        compose.waitUntilExactlyOneExists(hasText("Email this note"))
        compose.onNodeWithText("To").performTextInput("sam@example.org")
        compose.onNode(hasText("Send") and hasClickAction()).performClick()
        // Not connected to a server: nothing can send it.
        compose.waitUntilExactlyOneExists(
            hasText("This note hasn't reached the server yet. Try again once it's synced.")
        )
    }

    /** Settings → Integrations, signed in to a server whose email settings are [settings] until changed. */
    private fun openIntegrations(settings: String, puts: MutableList<String> = ArrayList()) {
        var current = settings
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/api/v1/settings/email") return MockResponse.Builder().code(404).build()
                when (request.method) {
                    "PUT" -> {
                        val body = request.body!!.utf8()
                        puts += body
                        val from = Json.parseToJsonElement(body).jsonObject["from"]!!.jsonPrimitive.content
                        current = buildJsonObject {
                            put("configured", true)
                            put("from", from)
                            put("keyHint", "6789")
                            put("editable", true)
                        }.toString()
                    }

                    "DELETE" -> {
                        current = """{"configured":false,"from":"","keyHint":"","editable":true}"""
                        return MockResponse.Builder().code(204).build()
                    }
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(current)
                    .build()
            }
        }
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
        compose.waitUntilExactlyOneExists(hasText("Integrations"))
        compose.onNodeWithText("Integrations").performClick()
    }

    /** A dialog's button: the row behind it can have one with the same text. */
    private fun dialogButton(label: String) = compose.onNode(
        hasText(label) and hasClickAction() and !hasContentDescription("$label Resend")
    ).performScrollTo()

    @Test
    fun `Resend connects in two steps, the key checked before the sender`() {
        val puts = ArrayList<String>()
        openIntegrations("""{"configured":false,"from":"","keyHint":"","editable":true}""", puts)
        compose.waitUntilExactlyOneExists(hasContentDescription("Connect Resend"))
        compose.onNode(hasContentDescription("Connect Resend")).performClick()

        dialogButton("Continue").performClick()
        compose.waitUntilExactlyOneExists(hasText("Paste your Resend API key."))
        compose.onNodeWithText("API key").performTextInput("sk_wrong")
        dialogButton("Continue").performClick()
        compose.waitUntilExactlyOneExists(hasText("A Resend API key starts with re_."))
        compose.onNodeWithText("API key").performTextClearance()
        compose.onNodeWithText("API key").performTextInput("re_123456789")

        // Typed in one go, as a paste is: no Continue needed.
        compose.waitUntilExactlyOneExists(hasText("Who it's from"))
        dialogButton("Connect").performClick()
        compose.waitUntilExactlyOneExists(hasText("Enter the address emails come from."))
        compose.onNodeWithText("Name").performTextInput("Me, Myself")
        compose.onNodeWithText("Address").performTextInput("notes@example.com")
        dialogButton("Connect").performClick()
        compose.waitUntilExactlyOneExists(hasText("Ready to send"))

        assertEquals(1, puts.size)
        val sent = Json.parseToJsonElement(puts[0]).jsonObject
        assertEquals("re_123456789", sent["apiKey"]?.jsonPrimitive?.content)
        assertEquals("\"Me, Myself\" <notes@example.com>", sent["from"]?.jsonPrimitive?.content)
        dialogButton("Done").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sending as \"Me, Myself\" <notes@example.com>"))
    }

    @Test
    fun `Resend's sender changes, keeping the key, and it disconnects`() {
        val puts = ArrayList<String>()
        openIntegrations(
            """{"configured":true,"from":"Me <notes@example.com>","keyHint":"6789","editable":true}""",
            puts
        )
        compose.waitUntilExactlyOneExists(hasContentDescription("Manage Resend"))
        compose.onNode(hasContentDescription("Manage Resend")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved · …6789"))
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("Notes")
        dialogButton("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sending as Notes <notes@example.com>"))
        val sent = Json.parseToJsonElement(puts.single()).jsonObject
        assertNull(sent["apiKey"])

        compose.onNode(hasContentDescription("Manage Resend")).performClick()
        dialogButton("Disconnect…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Disconnect Resend?"))
        compose.onNode(hasText("Disconnect") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Connect Resend"))
        assertEquals(false, graph.email.settings.value?.configured)
    }

    @Test
    fun `someone who isn't an admin sees Resend without a way to connect it`() {
        openIntegrations("""{"configured":false,"from":"","keyHint":"","editable":false}""")
        compose.waitUntilExactlyOneExists(hasText("An admin can connect it.", substring = true))
        compose.onNode(hasContentDescription("Connect Resend")).assertDoesNotExist()
    }

    @Test
    fun `a sender's name is quoted when the server would refuse it bare, and read back`() {
        assertEquals("Me <a@b.c>", Sender.format("Me", "a@b.c"))
        assertEquals("a@b.c", Sender.format(" ", " a@b.c "))
        assertEquals("\"Doe, J. \\\"Jo\\\"\" <a@b.c>", Sender.format("Doe, J. \"Jo\"", "a@b.c"))
        assertEquals("Doe, J. \"Jo\"" to "a@b.c", Sender.parse("\"Doe, J. \\\"Jo\\\"\" <a@b.c>"))
        assertEquals("" to "a@b.c", Sender.parse("a@b.c"))
    }
}
