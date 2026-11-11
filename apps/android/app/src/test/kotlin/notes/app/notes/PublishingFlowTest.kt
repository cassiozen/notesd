package notes.app.notes

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import notes.core.api.Member
import notes.core.api.PublishSettings
import notes.core.api.RemoteSpace
import notes.core.crypto.LockedNotes
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Publishing a space (§7.10) from Space info, against a fake notesd: on, its address, a new handle, off. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class PublishingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()

    // Started first: a test's setup needs the server's address.
    @Before
    fun setUp() = notesd.start()

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        notesd.close()
    }

    /** The server's own origin, as the app reaches it. */
    private val origin get() = notesd.server.url("/").toString().trimEnd('/')

    private fun launch() {
        notesd.signIn(graph)
        graph.setOnboardingDismissed(true)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        scenario = ActivityScenario.launch(MainActivity::class.java)
        graph.watchAccount()
        compose.waitUntilAtLeastOneExists(hasText("All Notes"), 5_000)
    }

    /** Family, on the server already, published at [handle] when it's given. */
    private fun family(handle: String? = null) {
        notesd.spaces["fam"] = RemoteSpace("fam", "Family")
        if (handle != null) publishOnServer("fam", handle)
    }

    private fun publishOnServer(id: String, handle: String) {
        val settings = PublishSettings(handle, "$origin/@$handle/", origin, published = true)
        notesd.published[id] = settings
        notesd.spaces[id] = notesd.withPublished(notesd.spaces.getValue(id), settings)
    }

    /** [name]'s Space info, until [until] shows; then, with [page], its Website row pressed, on its page. */
    private fun openSpaceInfo(name: String, until: String = "Website", page: Boolean = true) {
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Settings"))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNodeWithText("Spaces").performClick()
        compose.waitUntilExactlyOneExists(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText(until))
        if (page) {
            compose.onNode(hasText("Website") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasContentDescription("Back") and hasText(name), 5_000)
        }
    }

    /** Whether Family shows published, as the space list has it: what every screen's glyphs and rows read. */
    private fun shownPublished() = graph.spaces.value.first { it.id == "fam" }.traits.any { it.key == "published" }

    /** A button, once it can be pressed, scrolled to and pressed. */
    private fun press(label: String) {
        compose.waitUntilExactlyOneExists(hasText(label) and hasClickAction() and isEnabled(), 10_000)
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    }

    /** The go-ahead of the confirmation, opened in place, titled [title]. */
    private fun confirm(title: String, action: String) {
        compose.waitUntilExactlyOneExists(hasText(title, substring = true))
        compose.onNode(hasText(action) and hasClickAction() and hasAnySibling(hasText(title, substring = true)))
            .performScrollTo()
            .performClick()
    }

    private val host get() = origin.substringAfter("://")

    /** The handle's field: the page's name is a field too. */
    private val addressField = hasSetTextAction() and !hasContentDescription("Name")

    @Test
    fun `publishes a space of your own from Space info, at the address the app reaches the server by`() {
        family()
        launch()
        openSpaceInfo("Family")
        compose.onNodeWithText("Publish Family as a website anyone can read", substring = true).assertExists()
        compose.onNodeWithText("Custom CSS", substring = true).assertDoesNotExist()
        press("Publish")
        compose.waitUntilExactlyOneExists(hasContentDescription("Address: $host/@family"), 10_000)
        assertEquals(PublishSettings("family", "$origin/@family/", origin, published = true), notesd.published["fam"])
        // Synced after: the trait is here, and Stop publishing with it.
        compose.waitUntilExactlyOneExists(hasText("Stop publishing…"))
    }

    @Test
    fun `it shows published at once, before the server answers, and the address once it has`() {
        family()
        notesd.held += "PUT /api/v1/spaces/fam/publish"
        launch()
        openSpaceInfo("Family")
        press("Publish")
        // The server hasn't answered: the space shows published, its address to come.
        compose.waitUntilExactlyOneExists(hasText("Publishing…"), 2_000)
        assertTrue(shownPublished())
        notesd.release()
        compose.waitUntilExactlyOneExists(hasContentDescription("Address: $host/@family"), 10_000)
        compose.waitUntilDoesNotExist(hasText("Publishing…"), 10_000)
    }

    @Test
    fun `stopping shows it unpublished at once`() {
        family(handle = "family")
        notesd.held += "DELETE /api/v1/spaces/fam/publish"
        launch()
        openSpaceInfo("Family")
        press("Stop publishing…")
        confirm("Stop publishing Family?", "Stop publishing")
        compose.waitUntilExactlyOneExists(hasText("Publish") and hasClickAction(), 2_000)
        assertFalse(shownPublished())
        notesd.release()
        until { notesd.published.getValue("fam").published.not() }
        compose.onNode(hasText("Publish") and hasClickAction()).assertExists()
    }

    @Test
    fun `a refusal shows it unpublished again`() {
        family()
        notesd.lockedIn += "fam"
        launch()
        openSpaceInfo("Family")
        press("Publish")
        compose.waitUntilExactlyOneExists(hasText("Unlock its locked notes to publish it."), 10_000)
        assertFalse(shownPublished())
        compose.onNode(hasText("Publish") and hasClickAction()).assertExists()
    }

    /** Waits for [condition], checked off the screen's thread. */
    private fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!synchronized(notesd) { condition() }) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `says why a space with locked notes can't be published`() {
        family()
        notesd.lockedIn += "fam"
        launch()
        openSpaceInfo("Family")
        press("Publish")
        compose.waitUntilExactlyOneExists(hasText("Unlock its locked notes to publish it."), 10_000)
    }

    @Test
    fun `a locked note on this phone refuses publishing before asking the server`() {
        family()
        launch()
        offMain {
            graph.store.save("diary", "Diary", LockedNotes.encrypt(ByteArray(32), "Dear diary\n"), spaceId = "fam")
        }
        openSpaceInfo("Family")
        press("Publish")
        compose.waitUntilExactlyOneExists(hasText("Unlock its locked note to publish it."), 10_000)
        assertFalse(synchronized(notesd) { notesd.requests.any { it.endsWith("/publish") } })
    }

    /** [block] off the screen's thread, which writes to the database release locks on. */
    private fun <T> offMain(block: suspend () -> T): T {
        val result = CoroutineScope(Dispatchers.IO).async { block() }
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (!result.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "still waiting" }
            looper.idle()
            Thread.sleep(5)
        }
        return runBlocking { result.await() }
    }

    @Test
    fun `the default space can't be published`() {
        launch()
        openSpaceInfo("Notes", until = "Delete space…", page = false)
        compose.onNodeWithText("Website").assertDoesNotExist()
    }

    @Test
    fun `moves the site to another address once asked, and says when another space has it`() {
        family(handle = "family")
        notesd.spaces["other"] = RemoteSpace("other", "Other")
        publishOnServer("other", "taken")
        launch()
        openSpaceInfo("Family")
        compose.waitUntilExactlyOneExists(hasContentDescription("Address: $host/@family"), 10_000)

        press("Change address")
        compose.onNode(addressField).performTextReplacement("taken")
        press("Save")
        confirm("Move the site to $host/@taken?", "Change address")
        compose.waitUntilExactlyOneExists(hasText("Another space has that address. Try another."), 10_000)

        // Cancelled, only the question goes: the handle stays as typed.
        compose.onNode(addressField).performTextReplacement("keep-me")
        press("Save")
        confirm("Move the site to $host/@keep-me?", "Cancel")
        compose.waitUntilExactlyOneExists(addressField and hasText("keep-me"))

        compose.onNode(addressField).performTextReplacement("Our-Days")
        press("Save")
        confirm("Move the site to $host/@our-days?", "Change address")
        compose.waitUntilExactlyOneExists(hasContentDescription("Address: $host/@our-days"), 10_000)
        assertEquals("our-days", notesd.published.getValue("fam").handle)
    }

    @Test
    fun `a new address typed and not yet moved to is asked about on Back`() {
        family(handle = "family")
        launch()
        openSpaceInfo("Family")
        press("Change address")
        compose.onNode(addressField).performTextReplacement("our-days")
        // The page asks from the frame after the typing, as it would by the time a person's Back arrives.
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilExactlyOneExists(hasText("Your changes to the address aren't saved.", substring = true))
        // Above the page, not in its scroll.
        compose.onNode(hasText("Discard") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Website") and hasClickAction())
        assertEquals("family", notesd.published.getValue("fam").handle)
    }

    @Test
    fun `stops publishing once asked, the address staying the space's`() {
        family(handle = "family")
        launch()
        openSpaceInfo("Family")
        press("Stop publishing…")
        confirm("Stop publishing Family?", "Stop publishing")
        compose.waitUntilExactlyOneExists(hasText("Publish") and hasClickAction(), 10_000)
        assertEquals(false, notesd.published.getValue("fam").published)
        assertEquals("family", notesd.published.getValue("fam").handle)
    }

    @Test
    fun `a member sees where the space is, and nothing to change`() {
        notesd.memberOf("fam", "Family", Member(2, "ana"))
        publishOnServer("fam", "family")
        launch()
        openSpaceInfo("Family", page = false)
        compose.waitUntilExactlyOneExists(hasText("At $host/@family"))
        // Its row says where; there's no page to open.
        compose.onNode(hasText("Website") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Stop publishing…").assertDoesNotExist()
        compose.onNodeWithText("Change address").assertDoesNotExist()
    }
}
