package notes.app.agent

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.NoteEditText
import notes.app.shutDown
import notes.core.api.AgentConfig
import notes.core.api.RemoteNote
import notes.core.crypto.Keyring
import notes.core.crypto.LockedNotes
import notes.core.db.Spaces
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A run reading and changing the note open here, as the server asks over the agent socket. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class OpenNoteEditorsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val conn get() = notesd.sockets.last()

    @After
    fun tearDown() {
        scenario?.close()
        graph.agentLink.socket.reset()
        graph.shutDown()
        notesd.close()
    }

    /** Groceries open in the editor, synced at revision 1, the caret at the end of its body. */
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
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            compose.waitForIdle()
            looper.idle()
            Thread.sleep(5)
        }
    }

    /** Store work hops to Main, which this thread is: blocking here would wait for itself. */
    private fun <T> offMain(block: suspend () -> T): T {
        val done = CoroutineScope(Dispatchers.IO).async { block() }
        val looper = shadowOf(Looper.getMainLooper())
        while (!done.isCompleted) {
            looper.idle()
            Thread.sleep(1)
        }
        return runBlocking { done.await() }
    }

    /** Asks as the server would, and waits for the answer. */
    private fun ask(message: String): String {
        val before = conn.got("result").size
        conn.send(message)
        until { conn.got("result").size > before }
        return conn.got("result").last()
    }

    private fun apply(id: String, before: String, after: String, op: String) = ask(
        """{"type":"apply","id":"$id","noteId":"n1","runId":"run1",""" +
            """"before":{"title":"Groceries","content":"$before"},"after":{"title":"Groceries","content":"$after"},""" +
            """"op":$op}"""
    )

    private fun body(editor: NoteEditText) = editor.readForAgent().fields.content

    @Test
    fun `a read gets the note as the editor shows it, typing not saved included`() {
        val editor = open()
        assertEquals(
            """{"type":"result","id":"r1","spaceId":"default","title":"Groceries","content":"Milk\n",""" +
                """"cursor":4,"revision":1}""",
            ask("""{"type":"read","id":"r1","noteId":"n1"}""")
        )

        editor.text!!.append(" and eggs")
        editor.setSelection(editor.text!!.length)
        assertEquals(
            """{"type":"result","id":"r2","spaceId":"default","title":"Groceries","content":"Milk and eggs\n",""" +
                """"cursor":13,"revision":null}""",
            ask("""{"type":"read","id":"r2","noteId":"n1"}""")
        )
    }

    @Test
    fun `a change lands as one step that undo takes back, and is saved`() {
        val editor = open()
        assertEquals(
            """{"type":"result","id":"a1","before":{"title":"Groceries","content":"Milk\n"},"revision":1,""" +
                """"shows":{"title":"Groceries","content":"Milk\n\n- Eggs\n"}}""",
            apply("a1", "Milk\\n", "Milk\\n\\n- Eggs\\n", """{"kind":"insert","at":"end","text":"- Eggs"}""")
        )
        assertEquals("Milk\n\n- Eggs\n", body(editor))
        until { offMain { graph.store.get("n1")?.content } == "Milk\n\n- Eggs\n" }

        editor.undo()
        assertEquals("Milk\n", body(editor))
    }

    @Test
    fun `typed since the run read it, the change is made to what shows`() {
        val editor = open()
        editor.text!!.append(" and bread")
        val answer = apply("a1", "Milk\\n", "Milk\\n\\n- Eggs\\n", """{"kind":"insert","at":"end","text":"- Eggs"}""")
        assertEquals(
            """{"type":"result","id":"a1","before":{"title":"Groceries","content":"Milk and bread\n"},""" +
                """"revision":null,"shows":{"title":"Groceries","content":"Milk and bread\n\n- Eggs\n"}}""",
            answer
        )

        // The user's typing is a step of its own.
        editor.undo()
        assertEquals("Milk and bread\n", body(editor))
    }

    @Test
    fun `a change that no longer fits is refused, and the text left alone`() {
        val editor = open()
        editor.text!!.append(" and bread")
        assertEquals(
            """{"type":"result","id":"a1","error":"changed"}""",
            apply("a1", "Milk\\n", "Oat milk\\n", """{"kind":"replace","find":"Butter","replace":"Oat butter"}""")
        )
        assertEquals("Milk and bread\n", body(editor))
    }

    @Test
    fun `a note not open here, or locked, is refused`() {
        open()
        assertEquals(
            """{"type":"result","id":"r1","error":"not_open"}""",
            ask("""{"type":"read","id":"r1","noteId":"elsewhere"}""")
        )

        val created = Keyring.create("correct horse", iterations = 10_000)
        offMain {
            graph.sync.lockKeys.replace(listOf(created.entry))
            graph.store.save("bank", "Bank", LockedNotes.encrypt(created.key, "PIN 1234\n"))
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Bank") and hasClickAction(), 5_000)
        compose.onNode(hasText("Bank") and hasClickAction()).performClick()
        until { graph.openNotes.note("bank") != null }
        assertEquals(
            """{"type":"result","id":"r2","error":"locked"}""",
            ask("""{"type":"read","id":"r2","noteId":"bank"}""")
        )
        assertEquals(
            """{"type":"result","id":"a1","error":"locked"}""",
            ask(
                """{"type":"apply","id":"a1","noteId":"bank","runId":"run1",""" +
                    """"before":{"title":"Bank","content":"x"},"after":{"title":"Bank","content":"y"}}"""
            )
        )
    }
}
