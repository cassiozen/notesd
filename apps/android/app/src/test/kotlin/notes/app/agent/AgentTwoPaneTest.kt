package notes.app.agent

import android.os.Looper
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.NoteEditText
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.AgentRun
import notes.core.api.RemoteNote
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The agent in a note shown beside the list, on a tablet or an unfolded phone. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class, qualifiers = "w1280dp-h800dp")
class AgentTwoPaneTest {
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
        notesd.feeds[""] = listOf(
            RemoteNote("n1", "Groceries", "Milk\n\nEggs\n", revision = 1, changeSeq = 1),
            RemoteNote("n2", "Trip", "Lisbon\n", revision = 1, changeSeq = 2)
        )
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", "", mapOf("menu" to "Tidy."))
        notesd.start()
        notesd.signIn(graph)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        graph.setOnboardingDismissed(true)
        graph.foreground.value = true
        graph.agentLink.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun open(title: String, id: String): NoteEditText {
        compose.waitUntilExactlyOneExists(hasText(title) and hasClickAction(), 5_000)
        compose.onNode(hasText(title) and hasClickAction()).performClick()
        until { notesd.sockets.isNotEmpty() && conn.got("editor.opened").any { id in it } }
        until { graph.openNotes.note(id)?.view?.value != null }
        return graph.openNotes.note(id)!!.view.value!!
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

    @Test
    fun `the note's cards and marker sit in its pane, and opening another note takes them away`() {
        launch()
        val groceries = open("Groceries", "n1")
        conn.send("""{"type":"run","runId":"r1","noteId":"n1","trigger":"menu","status":"running"}""")
        conn.send(
            """{"type":"overlays","noteId":"n1","overlays":[""" +
                """{"id":"c1","runId":"r1","noteId":"n1","kind":"card","place":{"after":"Milk"},"body":"Oat?"}]}"""
        )
        until { groceries.agentCards.placed.size == 1 && groceries.agentPresence.marker != null }
        val card = groceries.agentCards.cardFor("c1")!!
        until { card.width > 0 }
        // In the note's pane, as wide as its text: not the window, the list beside it taking the rest.
        val window = scenario!!.let { s ->
            var w = 0
            s.onActivity { w = it.window.decorView.width }
            w
        }
        assertEquals(groceries.width - groceries.totalPaddingLeft - groceries.totalPaddingRight, card.width)
        assertTrue(card.width < window / 2, "the card is ${card.width}px in a ${window}px window")
        assertTrue(card.visibility == View.VISIBLE)

        val trip = open("Trip", "n2")
        until { conn.got("editor.closed").any { "n1" in it } }
        assertEquals(setOf("n2"), graph.openNotes.ids.value)
        assertEquals(null, graph.agentLink.socket.overlays.value["n1"])
        assertEquals(emptyList(), trip.agentCards.placed)
        assertEquals(null, trip.agentPresence.marker)
    }

    @Test
    fun `the Undo and a failed run's notice show beside the list`() {
        notesd.runs += AgentRun("r0", "menu", "failed", Spaces.DEFAULT, "n1", "Groceries", failure = "provider")
        launch()
        open("Groceries", "n1")
        compose.waitUntilAtLeastOneExists(hasText("The agent's last run on this note failed", substring = true), 5_000)
        conn.send(
            """{"type":"apply","id":"a1","noteId":"n1","runId":"r1",""" +
                """"before":{"title":"Groceries","content":"Milk\n\nEggs\n"},""" +
                """"after":{"title":"Groceries","content":"Milk\n\nEggs\n\n- Jam\n"}}"""
        )
        compose.waitUntilExactlyOneExists(hasText("Notes agent changed this note"), 5_000)
    }
}
