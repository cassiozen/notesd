package notes.app.agent

import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

/** The agent at work in the open note, and its change landing, as run updates and applies arrive. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentPresenceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val conn get() = notesd.sockets.last()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val resolver get() = ApplicationProvider.getApplicationContext<NotesApp>().contentResolver

    @After
    fun tearDown() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scenario?.close()
        graph.agentLink.socket.reset()
        graph.shutDown()
        notesd.close()
    }

    /** Groceries open, the caret at the end of its body. */
    private fun open(): NoteEditText {
        notesd.feeds[""] = listOf(RemoteNote("n1", "Groceries", "Milk\n", revision = 1, changeSeq = 1))
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
        return graph.openNotes.note("n1")!!.view.value!!.apply { setSelection(text!!.length) }
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

    private fun run(status: String) =
        conn.send("""{"type":"run","runId":"run1","noteId":"n1","trigger":"menu","status":"$status"}""")

    /** The run adds Eggs, and waits for the editor's answer. */
    private fun addEggs() {
        val before = conn.got("result").size
        conn.send(
            """{"type":"apply","id":"a${before + 1}","noteId":"n1","runId":"run1",""" +
                """"before":{"title":"Groceries","content":"Milk\n"},""" +
                """"after":{"title":"Groceries","content":"Milk\n\n- Eggs\n"},""" +
                """"op":{"kind":"insert","at":"end","text":"- Eggs"}}"""
        )
        until { conn.got("result").size > before }
    }

    private fun body(editor: NoteEditText) = editor.readForAgent().fields.content

    private val undoToast = hasText("Notes agent changed this note")

    @Test
    fun `a run going shows the agent where it will write until it ends, and screen readers hear it`() {
        val editor = open()
        val caret = editor.selectionStart
        val tall = editor.layout.height
        run("running")
        until { editor.agentPresence.marker != null }
        assertEquals("Notes agent" to caret, editor.agentPresence.marker)
        compose.onNode(hasContentDescription("Agent is writing…")).assertExists()
        // Room under its line, and nothing in what's saved.
        until { editor.layout.height > tall }
        assertEquals("Milk\n", body(editor))

        run("failed")
        until { editor.agentPresence.marker == null }
        compose.onNode(hasContentDescription("Agent is writing…")).assertDoesNotExist()

        // From the title, it writes at the top of the body.
        editor.setSelection(3)
        run("running")
        until { editor.agentPresence.marker != null }
        assertEquals("Groceries\n".length, editor.agentPresence.marker!!.second)
    }

    @Test
    fun `its change ends the wait, is highlighted, and Undo takes back just it`() {
        val editor = open()
        run("running")
        until { editor.agentPresence.marker != null }
        addEggs()
        until { editor.agentPresence.marker == null }
        val range = editor.agentPresence.highlighted!!
        val put = editor.text!!.substring(range.first, range.last + 1)
        assertTrue("Eggs" in put && "Milk" !in put, put)

        compose.waitUntilExactlyOneExists(undoToast, 5_000)
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        until { body(editor) == "Milk\n" }
        compose.waitUntilDoesNotExist(undoToast, 5_000)
        assertNull(editor.agentPresence.highlighted)
    }

    @Test
    fun `typing or six seconds take the Undo away, and the highlight fades in three`() {
        val editor = open()
        addEggs()
        compose.waitUntilExactlyOneExists(undoToast, 5_000)
        editor.text!!.append("!")
        compose.waitUntilDoesNotExist(undoToast, 5_000)
        assertNull(editor.agentPresence.highlighted)

        conn.send(
            """{"type":"apply","id":"b1","noteId":"n1","runId":"run1",""" +
                """"before":{"title":"Groceries","content":"Milk\n"},""" +
                """"after":{"title":"Groceries","content":"Oat milk\n"},""" +
                """"op":{"kind":"replace","find":"Milk","replace":"Oat milk"}}"""
        )
        compose.waitUntilExactlyOneExists(undoToast, 5_000)
        assertTrue(editor.agentPresence.highlightLevel() > 0.9f)
        looper.idleFor(Duration.ofMillis(3_001))
        assertEquals(0f, editor.agentPresence.highlightLevel())
        looper.idleFor(Duration.ofMillis(3_000))
        compose.waitUntilDoesNotExist(undoToast, 5_000)
        assertTrue("Oat milk" in body(editor))
    }

    @Test
    fun `with animations off, the highlight doesn't fade`() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val editor = open()
        addEggs()
        until { editor.agentPresence.highlighted != null }
        looper.idleFor(Duration.ofMillis(4_000))
        assertEquals(1f, editor.agentPresence.highlightLevel())
    }
}
