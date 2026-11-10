package notes.app.agent

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.NoteEditText
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.RemoteNote
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A suggestion beside the note, accepted as the agent's change, or found stale. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentSuggestionsTest {
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

    private val content = "Milk\n\nBread **fresh**\n\nEggs\n"

    /** Groceries open, the caret at the end of Milk. */
    private fun open(): NoteEditText {
        notesd.feeds[""] = listOf(RemoteNote("n1", "Groceries", content, revision = 1, changeSeq = 1))
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", "", mapOf("menu" to "Tidy."))
        notesd.start()
        notesd.signIn(graph)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        graph.setOnboardingDismissed(true)
        graph.foreground.value = true
        graph.agentLink.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Groceries") and hasClickAction(), 5_000)
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        until { notesd.sockets.isNotEmpty() && conn.got("editor.opened").isNotEmpty() }
        until { graph.openNotes.note("n1")?.view?.value != null }
        return graph.openNotes.note("n1")!!.view.value!!.apply { setSelection(text!!.indexOf("Milk") + 4) }
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

    private fun suggest(editor: NoteEditText, place: String, change: String) {
        conn.send(
            """{"type":"overlays","noteId":"n1","overlays":[{"id":"s1","runId":"r1","noteId":"n1",""" +
                """"kind":"suggestion","place":$place,"body":"","change":$change}]}"""
        )
        until { editor.agentCards.placed.isNotEmpty() }
    }

    private fun views(v: View): List<View> = listOf(v) +
        ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } }.orEmpty())

    private fun accept(editor: NoteEditText) =
        views(editor.agentCards.cardFor("s1")!!).first { it.contentDescription == "Accept the change" }.performClick()

    private fun body(editor: NoteEditText) = editor.readForAgent().fields.content

    private fun answers() = conn.got("overlay.answer")

    @Test
    fun `✓ makes the change as the agent's, one step undo takes back, and answers as an apply would`() {
        val editor = open()
        suggest(editor, """{"after":"Eggs"}""", """{"kind":"replace","find":"Eggs","replace":"Eggs (6)"}""")
        accept(editor)
        until { answers().isNotEmpty() }
        val after = "Milk\n\nBread **fresh**\n\nEggs (6)\n"
        assertEquals(
            listOf(
                """{"type":"overlay.answer","noteId":"n1","id":"s1","answer":"accept",""" +
                    """"before":{"title":"Groceries","content":"Milk\n\nBread **fresh**\n\nEggs\n"},"revision":1,""" +
                    """"shows":{"title":"Groceries","content":"Milk\n\nBread **fresh**\n\nEggs (6)\n"}}"""
            ),
            answers()
        )
        assertEquals(after, body(editor))
        assertEquals(emptyList(), editor.agentCards.placed)
        assertNotNull(editor.agentPresence.highlighted)

        editor.undo()
        assertEquals(content, body(editor))
    }

    @Test
    fun `an insert at the cursor goes where its card is anchored, wherever the caret went`() {
        val editor = open()
        suggest(editor, """{"at":"cursor"}""", """{"kind":"insert","at":"cursor","text":" and oats"}""")
        editor.setSelection(editor.text!!.length)
        accept(editor)
        until { answers().isNotEmpty() }
        assertEquals("Milk and oats\n\nBread **fresh**\n\nEggs\n", body(editor))
    }

    @Test
    fun `its quote gone from its block, ✓ answers stale and changes nothing`() {
        val editor = open()
        suggest(editor, """{"after":"Eggs"}""", """{"kind":"insert","at":"end","text":"- Jam"}""")
        val t = editor.text!!
        val eggs = t.indexOf("Eggs")
        t.replace(eggs, eggs + 4, "Ham")
        accept(editor)
        until { answers().isNotEmpty() }
        assertEquals("""{"type":"overlay.answer","noteId":"n1","id":"s1","answer":"stale"}""", answers().single())
        assertEquals("Milk\n\nBread **fresh**\n\nHam\n", body(editor))
    }

    @Test
    fun `a suggested replacement whose text changed is answered stale once typing pauses`() {
        val editor = open()
        suggest(editor, """{"after":"Bread"}""", """{"kind":"replace","find":"Bread","replace":"Rye bread"}""")
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(emptyList(), answers())

        val t = editor.text!!
        val bread = t.indexOf("Bread")
        t.replace(bread, bread + 5, "Brad")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        until { answers().isNotEmpty() }
        assertEquals("""{"type":"overlay.answer","noteId":"n1","id":"s1","answer":"stale"}""", answers().single())
        assertEquals(emptyList(), editor.agentCards.placed)
    }
}
