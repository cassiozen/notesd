package notes.app.agent

import androidx.compose.ui.test.ComposeTimeoutException
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
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.AgentRun
import notes.core.api.DiffLine
import notes.core.api.Member
import notes.core.api.NoteDiff
import notes.core.api.RemoteNote
import notes.core.api.RunEdit
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity, a run, and a failed run's notice in its note, against a fake notesd. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class ActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val ana = Member(2, "ana")
    private val now = System.currentTimeMillis()

    @After
    fun tearDown() {
        scenario?.close()
        graph.agentLink.socket.reset()
        graph.shutDown()
        notesd.close()
        activityPollMs = 5_000L
    }

    private fun run(
        id: String,
        note: String,
        title: String,
        space: String = Spaces.DEFAULT,
        status: String = "done",
        outcome: String = "edited",
        failure: String = "",
        forUser: Long = 1,
        edits: List<RunEdit> = emptyList(),
        diffs: List<NoteDiff> = emptyList()
    ) = AgentRun(
        id,
        "newNoteDone",
        status,
        space,
        note,
        title,
        outcome = outcome,
        failure = failure,
        message = "Tagged it.",
        model = "m",
        toolCalls = 2,
        promptTokens = 1200,
        completionTokens = 34,
        createdAt = now - 60_000,
        edits = edits,
        forUserId = forUser,
        diffs = diffs
    )

    /** [socket]: the agent socket open, and the run updates it hears. */
    private fun launch(until: String, socket: Boolean = false) {
        notesd.agents[Spaces.DEFAULT] =
            AgentConfig(Spaces.DEFAULT, true, "Tags.", "", mapOf("newNoteDone" to "Tag it."))
        notesd.start()
        notesd.signIn(graph)
        graph.setOnboardingDismissed(true)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        if (socket) {
            graph.foreground.value = true
            graph.agentLink.start()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText(until), 5_000)
        if (socket) {
            try {
                compose.waitUntil(5_000) { graph.agentLink.socket.connected.value }
            } catch (e: ComposeTimeoutException) {
                // Seen only on CI: says which precondition of the socket opening was missing.
                throw AssertionError("agent socket never connected: ${socketState()}", e)
            }
        }
    }

    private fun socketState() = "foreground=${graph.foreground.value}, " +
        "aiConfigured=${graph.ai.cache.value.value?.configured}, " +
        "agentOn=${graph.agents.cache.value.value?.get(Spaces.DEFAULT)?.enabled}, " +
        "socketRequests=${notesd.requests.count { it.endsWith("/agent/socket") }}, sockets=${notesd.sockets.size}"

    /** The server telling every socket of the account of a run. */
    private fun update(run: String, note: String, status: String, failure: String = "") = notesd.sockets.last().send(
        """{"type":"run","runId":"$run","noteId":"$note","trigger":"menu","status":"$status","failure":"$failure"}"""
    )

    private fun openActivity() {
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNodeWithText("Spaces").performClick()
        compose.waitUntilExactlyOneExists(hasText("Notes") and hasClickAction())
        compose.onNode(hasText("Notes") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Agent") and hasClickAction(), 5_000)
        button("Agent").performClick()
        compose.waitUntilExactlyOneExists(hasText("Activity") and hasClickAction(), 5_000)
        button("Activity").performClick()
        compose.waitUntilExactlyOneExists(hasText("Space") and hasClickAction())
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    @Test
    fun `a space's runs, its usage, more of them, and whom each was for`() {
        notesd.memberOf("fam", "Family", ana)
        notesd.runs += run("r1", "n1", "Groceries")
        notesd.runs += run("r2", "n2", "Trip", space = "fam", forUser = 2)
        notesd.runs += run("r3", "n3", "", status = "failed", outcome = "", failure = "daily_cap")
        notesd.runsPerPage = 1
        notesd.tokensToday = 4321
        launch("All Notes")
        openActivity()

        compose.waitUntilExactlyOneExists(hasText("Groceries", substring = true))
        // Usage is its own request: it can land after the runs.
        compose.waitUntilExactlyOneExists(hasText("Today in this space: 4,321 of 1,000,000 tokens"))
        // Narrowed to the space: the other's run isn't in it.
        compose.onNodeWithText("Trip", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Changed the note", substring = true).assertExists()
        button("Show more").performClick()
        compose.waitUntilExactlyOneExists(hasText("Failed: today’s tokens were used up", substring = true))
        compose.onNodeWithText("Untitled").assertExists()
        synchronized(notesd) { notesd.runsPerPage = 50 }

        button("Space").performClick()
        compose.waitUntilExactlyOneExists(hasText("All spaces") and hasClickAction())
        compose.onNode(hasText("All spaces") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Trip", substring = true))
        compose.onNodeWithText("New note done · Family · for ana", substring = true).assertExists()
        compose.onNodeWithText("Today in this space", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a run shows its lines and undoes what it can, saying why not the rest`() {
        notesd.runs += run(
            "r1",
            "n1",
            "Groceries",
            edits = listOf(
                RunEdit("n1", "edit", "Groceries", undoable = true),
                RunEdit("n2", "edit", "Pantry", blocked = "changed")
            ),
            diffs = listOf(
                NoteDiff(
                    "n1",
                    "edit",
                    "groceries",
                    "Groceries",
                    listOf(DiffLine("…", count = 3), DiffLine("-", "milk"), DiffLine("+", "oat milk"))
                ),
                NoteDiff("n2", "edit", "Pantry", "Pantry", listOf(DiffLine("+", "#food")))
            )
        )
        launch("All Notes")
        openActivity()
        compose.waitUntilExactlyOneExists(hasText("Groceries", substring = true) and hasClickAction())
        compose.onNode(hasText("Groceries", substring = true) and hasClickAction()).performClick()

        compose.waitUntilExactlyOneExists(hasText("Tagged it."))
        compose.onNodeWithText("1,234").assertExists()
        compose.onNodeWithText("Title: “groceries” → “Groceries”").assertExists()
        compose.onNodeWithText("3 unchanged lines").assertExists()
        compose.onNode(hasContentDescription("Removed: milk")).assertExists()
        compose.onNode(hasContentDescription("Added: oat milk")).assertExists()
        compose.onNodeWithText(
            "Can't undo: The note changed since, so undoing would lose those changes."
        ).performScrollTo()

        button("Undo").performClick()
        compose.waitUntilExactlyOneExists(hasText("Undone 1 of 2."))
        // The message shows before the run is fetched again with its new status.
        compose.waitUntilExactlyOneExists(hasText("Partly undone"))
        compose.onNode(hasText("Undo") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `a run still going is fetched again until it ends`() {
        activityPollMs = 200
        notesd.runs += run("r1", "n1", "Groceries", status = "running", outcome = "")
        launch("All Notes")
        openActivity()
        compose.waitUntilExactlyOneExists(hasText("Running"))
        synchronized(notesd) { notesd.runs[0] = notesd.runs[0].copy(status = "done", outcome = "no_change") }
        compose.waitUntilExactlyOneExists(hasText("No change"), 5_000)
    }

    @Test
    fun `a note's failed run says so, opens in Activity, and stays dismissed`() {
        notesd.feeds[""] = listOf(
            RemoteNote("n1", "Groceries", "Groceries\n", revision = 1, changeSeq = 1, createdAt = now, updatedAt = now)
        )
        notesd.runs += run("r1", "n1", "Groceries", status = "failed", outcome = "", failure = "provider")
        launch("Groceries")
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        val notice = "The agent's last run on this note failed: the AI provider failed."
        compose.waitUntilExactlyOneExists(hasText(notice), 5_000)

        compose.onNode(hasText("Activity") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Tagged it."))
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Space") and hasClickAction())
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText(notice))

        compose.onNode(hasText("Dismiss") and hasClickAction()).performClick()
        compose.waitUntilDoesNotExist(hasText(notice))
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Groceries") and hasClickAction())
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        compose.waitUntil(3_000) { notesd.requests.count { it == "GET /api/v1/agent/runs" } >= 2 }
        compose.waitForIdle()
        compose.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test
    fun `a run still going is fetched again as it ends, without waiting to poll`() {
        activityPollMs = 60_000
        notesd.runs += run("r1", "n1", "Groceries", status = "running", outcome = "")
        launch("All Notes", socket = true)
        openActivity()
        compose.waitUntilExactlyOneExists(hasText("Running"))
        synchronized(notesd) { notesd.runs[0] = notesd.runs[0].copy(status = "done", outcome = "no_change") }
        update("r1", "n1", "done")
        compose.waitUntilExactlyOneExists(hasText("No change"), 5_000)

        compose.onNode(hasText("Groceries", substring = true) and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Tagged it."))
        synchronized(notesd) { notesd.runs[0] = notesd.runs[0].copy(outcome = "undone") }
        update("r1", "n1", "done")
        compose.waitUntilExactlyOneExists(hasText("Undone"), 5_000)
    }

    @Test
    fun `a run on the open note failing says so as it ends, and the next run takes it away`() {
        notesd.feeds[""] = listOf(
            RemoteNote("n1", "Groceries", "Groceries\n", revision = 1, changeSeq = 1, createdAt = now, updatedAt = now)
        )
        launch("Groceries", socket = true)
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { notesd.requests.count { it == "GET /api/v1/agent/runs" } >= 1 }
        val notice = "The agent's last run on this note failed: it took too long."

        synchronized(notesd) {
            notesd.runs.add(0, run("r1", "n1", "Groceries", status = "failed", outcome = "", failure = "timeout"))
        }
        update("r1", "n1", "failed", "timeout")
        compose.waitUntilExactlyOneExists(hasText(notice), 5_000)

        update("r2", "n1", "running")
        compose.waitUntilDoesNotExist(hasText(notice), 5_000)
    }
}
