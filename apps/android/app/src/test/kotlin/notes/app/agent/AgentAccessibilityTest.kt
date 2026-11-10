package notes.app.agent

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.auditAccessibility
import notes.app.editor.AgentCardView
import notes.app.editor.NoteEditText
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.AgentRun
import notes.core.api.DiffLine
import notes.core.api.NoteDiff
import notes.core.api.RemoteNote
import notes.core.api.RunEdit
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The agent's screens and what it shows in a note, as TalkBack meets them: everything tappable named and 48dp, and
 * cards, which TalkBack reads after the note, saying where in it they are.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AgentAccessibilityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val conn get() = notesd.sockets.last()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @After
    fun tearDown() {
        scenario?.close()
        graph.agentLink.socket.reset()
        graph.shutDown()
        notesd.close()
    }

    private fun launch() {
        notesd.feeds[""] =
            listOf(RemoteNote("n1", "Groceries", "Milk\n\nBread **fresh**\n\nEggs\n", revision = 1, changeSeq = 1))
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", "Tidy up", mapOf("menu" to "Tidy."))
        notesd.start()
        notesd.signIn(graph)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        graph.setOnboardingDismissed(true)
        graph.foreground.value = true
        graph.agentLink.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Groceries") and hasClickAction(), 5_000)
    }

    private fun open(): NoteEditText {
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        until { notesd.sockets.isNotEmpty() && conn.got("editor.opened").isNotEmpty() }
        until { graph.openNotes.note("n1")?.view?.value != null }
        return graph.openNotes.note("n1")!!.view.value!!
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            compose.waitForIdle()
            looper.idle()
            Thread.sleep(5)
        }
    }

    private fun views(v: View): List<View> = listOf(v) +
        ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } }.orEmpty())

    @Test
    fun `a note with the agent at work, a card and a suggestion`() {
        launch()
        val editor = open()
        editor.setSelection(editor.text!!.indexOf("Milk") + 4)
        conn.send("""{"type":"run","runId":"r1","noteId":"n1","trigger":"menu","status":"running"}""")
        conn.send(
            """{"type":"overlays","noteId":"n1","overlays":[""" +
                """{"id":"s1","runId":"r1","noteId":"n1","kind":"suggestion","place":{"after":"Bread **fresh**"},""" +
                """"body":"Fresher","change":{"kind":"replace","find":"Bread","replace":"Rye bread"}},""" +
                """{"id":"c1","runId":"r1","noteId":"n1","kind":"card","place":{"at":"top"},"body":"Looks good."}]}"""
        )
        until { editor.agentCards.placed.size == 2 && editor.agentCards.cardFor("s1")!!.height > 0 }
        compose.waitForIdle()
        looper.idle()
        val frame = editor.parent as View
        compose.auditAccessibility("note with the agent", views = frame)

        // Read top to bottom, each saying where it is.
        val layer = (frame as ViewGroup).getChildAt(1) as ViewGroup
        val cards = (0 until layer.childCount).map { layer.getChildAt(it) as AgentCardView }
        assertEquals(listOf("c1", "s1"), cards.map { it.shown.id })
        fun said(card: AgentCardView) = views(card).filterIsInstance<TextView>().first().contentDescription
        assertEquals("Notes agent: a note, at the top", said(cards[0]))
        assertEquals("Notes agent: a suggested change, under “Bread fresh”", said(cards[1]))
        assertEquals("Notes agent: a suggested change", ViewCompat.getAccessibilityPaneTitle(cards[1]))
    }

    @Test
    fun `the Undo after the agent's change, and the note's menu with its command`() {
        launch()
        open()
        conn.send(
            """{"type":"apply","id":"a1","noteId":"n1","runId":"r1",""" +
                """"before":{"title":"Groceries","content":"Milk\n\nBread **fresh**\n\nEggs\n"},""" +
                """"after":{"title":"Groceries","content":"Milk\n\nBread **fresh**\n\nEggs\n\n- Jam\n"}}"""
        )
        compose.waitUntilExactlyOneExists(hasText("Notes agent changed this note"), 5_000)
        compose.auditAccessibility("the agent's Undo")

        compose.onNode(hasContentDescription("More")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Tidy up") and hasClickAction(), 5_000)
        compose.auditAccessibility("the note's menu")
    }

    @Test
    fun `the agent's part of Space info, Activity and a run`() {
        notesd.runs += AgentRun(
            "r1",
            "newNoteDone",
            "done",
            Spaces.DEFAULT,
            "n1",
            "Groceries",
            outcome = "edited",
            message = "Tagged it.",
            model = "m",
            createdAt = System.currentTimeMillis() - 60_000,
            edits = listOf(RunEdit("n1", "edit", "Groceries", undoable = true)),
            diffs = listOf(NoteDiff("n1", "edit", "groceries", "Groceries", listOf(DiffLine("+", "#food"))))
        )
        launch()
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Spaces"))
        compose.onNode(hasText("Spaces") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Notes") and hasClickAction())
        compose.onNode(hasText("Notes") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Activity…") and hasClickAction(), 5_000)
        compose.auditAccessibility("Space info with its agent")
        compose.onNode(hasText("Delete space…") and hasClickAction()).performScrollTo()
        compose.auditAccessibility("Space info with its agent, further down")
        compose.onNode(hasText("Activity…") and hasClickAction()).performScrollTo()

        compose.onNode(hasText("Activity…") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Groceries", substring = true) and hasClickAction(), 5_000)
        compose.auditAccessibility("Activity")

        compose.onNode(hasText("Groceries", substring = true) and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("Tagged it."), 5_000)
        // Back, and Undo.
        compose.auditAccessibility("a run", minControls = 2)
    }
}
