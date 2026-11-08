package notes.app.notes

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.account.Account
import notes.app.lastShown
import notes.app.sharing.Shared
import notes.app.sharing.Sharing
import notes.app.shutDown
import notes.core.api.Member
import notes.core.api.RemoteNote
import notes.core.db.Spaces
import notes.core.sync.EmailOutcome
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Shared spaces (§7.9) on a phone, against a fake notesd: invitations, sharing, members, who changed what. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class SharedSpacesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val ana = Member(2, "ana")
    private val ben = Member(3, "ben")
    private val cy = Member(4, "cy")
    private val hour = 3_600_000L

    @After
    fun tearDown() {
        scenario?.close()
        graph.shutDown()
        notesd.close()
    }

    /** Signed in to the fake notesd and synced with it, on the list showing [first]. */
    private fun launch(first: String, withId: Boolean = true, synced: suspend () -> Unit = {}) {
        notesd.start()
        notesd.signIn(graph, withId)
        graph.setOnboardingDismissed(true)
        // Before the activity: its flows re-query on Main as the sync writes.
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        runBlocking { synced() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        graph.watchAccount()
        compose.waitUntilAtLeastOneExists(hasText(first), 5_000)
    }

    /**
     * [block] on IO, the main looper idled meanwhile: the screen's flows re-query on every write and release the
     * database's locks back on Main, so a store call made here waits on them.
     */
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

    /** Waits for [condition], read off Main, to hold. */
    private fun until(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!offMain { condition() }) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            compose.waitForIdle()
        }
    }

    private fun note(id: String, title: String, space: String, by: Long, ago: Long) = RemoteNote(
        id,
        title,
        "$title\n",
        revision = 1,
        changeSeq = 1,
        createdAt = System.currentTimeMillis() - ago,
        updatedAt = System.currentTimeMillis() - ago,
        spaceId = space,
        updatedBy = by
    )

    private fun openSwitcher(name: String) {
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Space: $name"))
        compose.onNodeWithContentDescription("Space: $name").performClick()
        compose.waitUntilExactlyOneExists(hasText("New space…"))
    }

    private fun openSpaceInfo(name: String, until: String = "Shared") {
        compose.onNodeWithContentDescription("Sidebar").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Settings"))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNodeWithText("Spaces").performClick()
        compose.waitUntilExactlyOneExists(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText(until))
    }

    /** A page's button, scrolled to. */
    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    /** A dialog's button. */
    private fun answer(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()

    @Test
    fun `signing in gives the engine the account's id, which a server from before shared spaces never said`() {
        launch("All Notes")
        until { graph.sync.store.userId() == 1L }
        assertEquals(1L, (graph.accounts.account.value as Account.SignedIn).userId)
    }

    @Test
    fun `an account from before the app kept its id finds it among a shared space's members`() {
        notesd.memberOf("fam", "Family", ana)
        launch("All Notes", withId = false)
        compose.waitUntil { (graph.accounts.account.value as Account.SignedIn).userId == 1L }
        until { graph.sync.store.userId() == 1L }
    }

    @Test
    fun `an invitation is joined from the switcher, which then shows the space and its notes`() {
        notesd.invite("i1", "club", "Book club", ana, icon = "open-book")
        notesd.feeds["club"] = listOf(note("b1", "Piranesi", "club", by = 2, ago = hour))
        launch("All Notes")
        openSwitcher("Notes")
        compose.onNodeWithText("ana invited you to Book club").assertExists()
        compose.onNodeWithContentDescription("Join Book club").performClick()
        compose.waitUntilExactlyOneExists(hasText("Piranesi"), 10_000)
        assertEquals("club", graph.space.value)
        assertTrue("POST /api/v1/invites/i1/accept" in notesd.requests)
        // Joined: no longer an invitation.
        until { graph.sync.spaces.invites().isEmpty() }
    }

    @Test
    fun `a declined invitation leaves the switcher`() {
        notesd.invite("i1", "club", "Book club", ana)
        launch("All Notes")
        openSwitcher("Notes")
        compose.onNodeWithContentDescription("Decline Book club").performClick()
        compose.waitUntilDoesNotExist(hasText("ana invited you to Book club"))
        assertTrue("POST /api/v1/invites/i1/decline" in notesd.requests)
        assertEquals(Spaces.DEFAULT, graph.space.value)
    }

    @Test
    fun `Turn on shares a space of your own with someone from the server's people`() {
        runBlocking { graph.sync.spaces.create("Family") }
        launch("All Notes")
        openSpaceInfo("Family")
        button("Turn on").performClick()
        compose.waitUntilExactlyOneExists(hasText("Share Family"))
        // Not you.
        compose.onNode(hasText("me") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("ana") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Invited ana"))
        assertTrue(notesd.requests.any { it.matches(Regex("POST /api/v1/spaces/.+/invites")) })
    }

    @Test
    fun `the default space can't be shared`() {
        launch("All Notes")
        openSpaceInfo("Notes", until = "Name and icon")
        compose.onNodeWithText("Turn on").assertDoesNotExist()
    }

    @Test
    fun `offline, the people picker says it needs a connection`() {
        runBlocking { graph.sync.spaces.create("Family") }
        launch("All Notes")
        openSpaceInfo("Family")
        notesd.server.close()
        button("Turn on").performClick()
        compose.waitUntilExactlyOneExists(hasText("This needs a connection. Try again when you're online."))
    }

    @Test
    fun `the owner removes a member, makes another the owner, and cancels an invitation`() {
        notesd.owned("fam", "Family", listOf(ana, ben), invitees = listOf(cy))
        launch("All Notes")
        openSpaceInfo("Family")
        compose.onNodeWithText("With ana and ben · invited cy").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sharing"))
        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithText("You").assertExists()

        compose.onNodeWithContentDescription("Cancel cy's invitation").performClick()
        compose.waitUntilExactlyOneExists(hasText("cy's invitation was cancelled."))
        compose.onNodeWithText("Hasn't joined yet").assertDoesNotExist()

        compose.onNode(hasText("ana") and hasClickAction()).performClick()
        compose.onNodeWithText("Remove from space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Remove ana?"))
        answer("Remove")
        compose.waitUntilExactlyOneExists(hasText("ana was removed."))
        compose.onNode(hasText("ana") and hasClickAction()).assertDoesNotExist()

        compose.onNode(hasText("ben") and hasClickAction()).performClick()
        compose.onNodeWithText("Make owner…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Make ben the owner?"))
        answer("Make owner")
        compose.waitUntilExactlyOneExists(hasText("ben owns Family now."))
        // A member now: leaving is what's left to do.
        compose.waitUntilExactlyOneExists(hasText("Leave space"))
        assertEquals(
            listOf(
                "DELETE /api/v1/spaces/fam/invites/inv-4",
                "DELETE /api/v1/spaces/fam/members/2",
                "PUT /api/v1/spaces/fam/owner"
            ),
            notesd.requests.filter { "/spaces/fam/" in it && !it.endsWith("/sync") }
        )
    }

    @Test
    fun `stopping sharing leaves the space yours alone`() {
        notesd.owned("fam", "Family", listOf(ana), invitees = listOf(ben))
        launch("All Notes")
        openSpaceInfo("Family")
        compose.onNodeWithText("With ana · invited ben").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sharing"))
        button("Stop sharing").performClick()
        compose.waitUntilExactlyOneExists(hasText("Stop sharing Family?"))
        compose.onAllNodes(hasText("Stop sharing") and hasClickAction()).onLast().performClick()
        compose.waitUntilExactlyOneExists(hasText("Family is yours alone again."))
        assertEquals(emptyList(), offMain { graph.sync.spaces.list() }.first { it.id == "fam" }.traits)
    }

    @Test
    fun `stopping sharing that fails partway still shows who's left`() {
        notesd.owned("fam", "Family", listOf(ana, ben))
        notesd.failing += "DELETE /api/v1/spaces/fam/members/3"
        launch("All Notes")
        val space = offMain { graph.sync.spaces.list() }.first { it.id == "fam" }
        assertTrue(offMain { graph.sharing.stopSharing(space, 1) } is Shared.Failed)
        // ana's removal went through before ben's failed.
        val members = offMain { graph.sync.spaces.list() }.first { it.id == "fam" }.shared!!.members
        assertEquals(listOf(1L, 3L), members.map { it.id })
    }

    @Test
    fun `a change made on the server but not synced here isn't reported done`() {
        notesd.owned("fam", "Family", listOf(ana))
        launch("All Notes")
        notesd.failing += "GET /api/v1/sync"
        assertEquals(Shared.Failed(Sharing.BEHIND), offMain { graph.sharing.remove("fam", 2) })
        assertTrue("DELETE /api/v1/spaces/fam/members/2" in notesd.requests)
    }

    @Test
    fun `a member leaves, warned that unsynced changes go with the space's notes`() {
        notesd.memberOf("fam", "Family", ana)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 2, ago = hour))
        launch("All Notes")
        offMain { graph.store.save("f1", "Picnic", "Picnic and games\n") }
        openSpaceInfo("Family")
        compose.onNodeWithText("Only its owner, ana, can rename or delete it.").assertExists()
        compose.onNodeWithText("Name and icon").assertDoesNotExist()
        compose.onNodeWithText("With ana").performClick()
        compose.waitUntilExactlyOneExists(hasText("Sharing"))
        button("Leave space").performClick()
        compose.waitUntilExactlyOneExists(hasText("1 note in Family has changes that haven't synced", substring = true))
        answer("Leave")
        // The space is gone: back past its pages.
        compose.waitUntilDoesNotExist(hasText("Sharing"))
        until { graph.store.get("f1") == null }
        assertTrue("DELETE /api/v1/spaces/fam/members/1" in notesd.requests)
    }

    @Test
    fun `another member's change is named on the row, in the editor and in the switcher`() {
        notesd.memberOf("fam", "Family", ana, ben)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 2, ago = 2 * hour))
        // Joined before ana's change: news.
        launch("All Notes") { graph.lastShown("fam", System.currentTimeMillis() - 3 * hour) }
        until { graph.sync.store.userId() == 1L }
        openSwitcher("Notes")
        compose.waitUntilExactlyOneExists(hasText("ana edited a note · 2h"), 5_000)
        compose.onNodeWithText("ana edited a note · 2h").performClick()
        compose.waitUntilExactlyOneExists(hasText("ana · 2h"))
        compose.onNodeWithText("Picnic").performClick()
        compose.waitUntilExactlyOneExists(hasText("Edited by ana · 2h"))
        // Shown: no longer news.
        until { graph.sync.spaces.news().isEmpty() }
    }

    @Test
    fun `your own change looks as before`() {
        notesd.memberOf("fam", "Family", ana)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 1, ago = 2 * hour))
        launch("All Notes")
        graph.switchSpace("fam")
        compose.waitUntilExactlyOneExists(hasText("Picnic"))
        compose.onNodeWithText("· 2h", substring = true).assertDoesNotExist()
        assertNull(offMain { graph.sync.spaces.news() }["fam"])
    }

    @Test
    fun `moving a note out of a shared space says who will no longer see it`() {
        notesd.memberOf("fam", "Family", ana, ben)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 2, ago = hour))
        launch("All Notes")
        graph.switchSpace("fam")
        compose.waitUntilExactlyOneExists(hasText("Picnic"))
        compose.onNodeWithText("Picnic").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Move to space…").performClick()
        compose.waitUntilExactlyOneExists(hasText("Move to space"))
        compose.onNode(hasText("Notes") and hasClickAction() and !hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Move “Picnic” to Notes?"))
        compose.onNodeWithText("It will no longer be:").assertExists()
        compose.onNodeWithText("Visible to ana and ben").assertExists()
        answer("Move")
        until { graph.store.spaceOf("f1") == Spaces.DEFAULT }
    }

    @Test
    fun `Lock in a shared space explains instead of locking`() {
        notesd.memberOf("fam", "Family", ana)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 2, ago = hour))
        launch("All Notes")
        graph.switchSpace("fam")
        compose.waitUntilExactlyOneExists(hasText("Picnic"))
        compose.onNodeWithText("Picnic").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Lock note").performClick()
        compose.waitUntilExactlyOneExists(hasText("Locked notes can't be in a shared space", substring = true))
        answer("OK")
        assertEquals("Picnic\n", offMain { graph.store.get("f1") }!!.content)
    }

    @Test
    fun `a shared space's note is emailed at its space's address`() {
        notesd.memberOf("fam", "Family", ana)
        notesd.feeds["fam"] = listOf(note("f1", "Picnic", "fam", by = 2, ago = hour))
        notesd.start()
        notesd.signIn(graph)
        assertEquals(EmailOutcome.Sent, runBlocking { graph.mailer.send("f1", listOf("sam@example.org")) })
        assertTrue("POST /api/v1/spaces/fam/notes/f1/email" in notesd.requests)
    }
}
