package notes.app.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.BuildConfig
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.account.Account
import notes.app.runOffMain
import notes.app.settings.shownStatus
import notes.app.settings.syncSummary
import notes.app.shutDown
import notes.core.api.Session
import notes.core.api.SignIn
import notes.core.api.UPGRADE_REQUIRED_MESSAGE
import notes.core.crypto.UnsupportedVersionException
import notes.core.sync.SyncOutcome
import notes.core.sync.SyncStatus
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the list says when sync can't get a change through, and a note locked by a newer notesd. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class SyncBannersTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()
    private val requests = ArrayList<RecordedRequest>()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        server.close()
    }

    /** Signed in to a server that answers every request with [answer]. */
    private fun signedIn(answer: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return answer()
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
    }

    @Test
    fun `a change the server keeps answering oddly is parked, and Retry puts it back`() {
        // A Wi-Fi portal's page, for every request.
        signedIn { MockResponse.Builder().code(200).body("<html>Sign in to the Wi-Fi</html>").build() }
        runBlocking {
            graph.store.save("n1", "Plans", "Bike\n")
            repeat(3) { assertEquals(SyncOutcome.Failed, graph.sync.sync()) }
            assertEquals(1, graph.sync.parked())
            // Kept, and still counted as not synced.
            assertEquals(1, graph.store.pendingNotes())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("1 change couldn’t sync. It’s kept on this device."))
        compose.onNode(hasText("Retry") and hasClickAction()).performClick()
        compose.waitUntilDoesNotExist(hasText("1 change couldn’t sync. It’s kept on this device."))
        assertEquals(0, compose.runOffMain { graph.sync.parked() })
        // Every request named the app.
        assertEquals(
            setOf("android/${BuildConfig.VERSION_NAME}"),
            synchronized(requests) { requests.map { it.headers["X-Notesd-Client"] }.toSet() }
        )
    }

    @Test
    fun `a server that needs a newer app says so and keeps the changes`() {
        signedIn {
            MockResponse.Builder().code(426).addHeader("Content-Type", "application/json")
                .body("""{"error":"upgrade_required","message":"update notesd","minVersion":"9.0.0"}""").build()
        }
        runBlocking {
            graph.store.save("n1", "Plans", "Bike\n")
            assertEquals(SyncOutcome.UpgradeRequired, graph.sync.sync())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText(UPGRADE_REQUIRED_MESSAGE))
        assertEquals(1, compose.runOffMain { graph.store.pendingNotes() })
    }

    @Test
    fun `Sync & data says how sync is doing, and what's waiting`() {
        signedIn {
            MockResponse.Builder().code(426).addHeader("Content-Type", "application/json")
                .body("""{"error":"upgrade_required","message":"update notesd","minVersion":"9.0.0"}""").build()
        }
        runBlocking {
            graph.store.save("n1", "Plans", "Bike\n")
            assertEquals(SyncOutcome.UpgradeRequired, graph.sync.sync())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        // The list says it in a word or two.
        compose.waitUntilExactlyOneExists(hasText("Update notesd"))
        compose.onNodeWithText("Sync & data").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Update notesd · 1 waiting"))
        compose.onNode(hasText(UPGRADE_REQUIRED_MESSAGE)).assertExists()
        compose.onNode(hasText("Sync now") and hasClickAction()).assertExists()
    }

    @Test
    fun `sync's state in a word or two, as on iOS`() {
        assertEquals("Up to date", syncSummary(SyncStatus.Synced(1), 0))
        assertEquals("2 waiting", syncSummary(SyncStatus.Synced(1), 2))
        assertEquals("Offline", syncSummary(SyncStatus.Offline, 2))
        assertEquals("", syncSummary(SyncStatus.NotConnected, 0))
        assertEquals("Sync problem", syncSummary(SyncStatus.Failed("x"), 0))
    }

    @Test
    fun `signed out or with nothing kept, the last cycle's state isn't what's shown`() {
        val synced = SyncStatus.Synced(1)
        assertEquals(SyncStatus.SignedOut, shownStatus(Account.SignedOut("https://n.example", "me"), synced))
        assertEquals(SyncStatus.NotConnected, shownStatus(Account.None, synced))
        assertEquals(synced, shownStatus(Account.SignedIn("https://n.example", "me"), synced))
    }

    @Test
    fun `a failed cycle doesn't count as the last sync`() {
        signedIn { MockResponse.Builder().code(200).body("<html>Sign in to the Wi-Fi</html>").build() }
        runBlocking {
            graph.store.save("n1", "Plans", "Bike\n")
            assertEquals(SyncOutcome.Failed, graph.sync.sync())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sync & data", substring = true))
        compose.onNodeWithText("Sync & data").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Sync now") and hasClickAction())
        compose.onNodeWithText("Last synced").assertDoesNotExist()
    }

    @Test
    fun `a note locked by a newer notesd opens read-only`() {
        graph.setOnboardingDismissed(true)
        runBlocking { graph.store.save("n1", "Future", "notesd:locked:v2:c2VhbGVk") }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Future"))
        compose.onNodeWithText("Future").performClick()
        compose.waitUntilExactlyOneExists(hasText(UnsupportedVersionException.UNSUPPORTED_VERSION_MESSAGE))
        // No unlocking, no lock toggle, nothing to edit.
        compose.onNode(hasText("Unlock") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription("More")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Lock now")).assertDoesNotExist()
    }
}
