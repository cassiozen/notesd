package notes.app.agent

import android.os.Looper
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
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.NoteEditText
import notes.app.notes.editIdleMs
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

/** The runs an editor asks for: a new note, starting to edit, and the space's command in the note's menu. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentTriggersTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val conn get() = notesd.sockets.last()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @After
    fun tearDown() {
        editIdleMs = 3 * 60_000L
        scenario?.close()
        graph.agentLink.socket.reset()
        graph.shutDown()
        notesd.close()
    }

    /** Signed in, Groceries synced, the default space's agent on with [triggers]; the list shown. */
    private fun launch(triggers: Map<String, String>, menuLabel: String = "") {
        notesd.feeds[""] = listOf(RemoteNote("n1", "Groceries", "Milk\n", revision = 1, changeSeq = 1))
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", menuLabel, triggers)
        notesd.start()
        notesd.signIn(graph)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        graph.setOnboardingDismissed(true)
        graph.foreground.value = true
        graph.agentLink.start()
        until { graph.agentLink.socket.connected.value }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Opens Groceries, the caret at the end of its body. */
    private fun open(title: String = "Groceries", id: String = "n1"): NoteEditText {
        compose.waitUntilExactlyOneExists(hasText(title) and hasClickAction(), 5_000)
        compose.onNode(hasText(title) and hasClickAction()).performClick()
        until { conn.got("editor.opened").any { id in it } }
        return editorOf(id).apply { setSelection(text!!.length) }
    }

    private fun editorOf(id: String): NoteEditText {
        until { graph.openNotes.note(id)?.view?.value != null }
        return graph.openNotes.note(id)!!.view.value!!
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            if (scenario != null) compose.waitForIdle()
            looper.idle()
            Thread.sleep(5)
        }
    }

    /** Keeps the main looper going for [ms] of real time: for what mustn't happen. */
    private fun idleFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            looper.idle()
            Thread.sleep(5)
        }
    }

    private fun requests() = conn.got("run.request").map { Json.parseToJsonElement(it).jsonObject }

    private fun triggers() = requests().map { it["trigger"]!!.jsonPrimitive.content }

    @Test
    fun `starting to edit asks once, and again after a pause, and the agent's changes don't count`() {
        editIdleMs = 60_000
        launch(mapOf("editStarted" to "Tidy."))
        val editor = open()
        conn.send(
            """{"type":"apply","id":"a1","noteId":"n1","runId":"run1",""" +
                """"before":{"title":"Groceries","content":"Milk\n"},""" +
                """"after":{"title":"Groceries","content":"Milk\n\n- Eggs\n"}}"""
        )
        until { conn.got("result").isNotEmpty() }
        idleFor(200)
        assertEquals(emptyList(), triggers())

        editor.text!!.append(" and bread")
        until { triggers().isNotEmpty() }
        val asked = requests().single()
        assertEquals("n1", asked["noteId"]!!.jsonPrimitive.content)
        // What the editor shows: the run needn't ask.
        assertTrue("and bread" in asked["note"]!!.jsonObject["content"]!!.jsonPrimitive.content)

        editor.text!!.append("!")
        idleFor(200)
        assertEquals(listOf("editStarted"), triggers())

        looper.idleFor(Duration.ofMillis(60_001))
        editor.text!!.append("!")
        until { triggers().size == 2 }
    }

    @Test
    fun `a new note asks once that it was created, and its typing isn't starting to edit`() {
        launch(mapOf("noteCreated" to "Start it.", "editStarted" to "Tidy."))
        compose.onNode(hasContentDescription("New note")).performClick()
        until { triggers().isNotEmpty() }
        assertEquals(listOf("noteCreated"), triggers())
        val id = requests().single()["noteId"]!!.jsonPrimitive.content

        editorOf(id).text!!.append("Plans")
        idleFor(300)
        assertEquals(listOf("noteCreated"), triggers())
    }

    @Test
    fun `nothing is asked for a trigger the space's agent has no prompt for`() {
        launch(mapOf("menu" to "Tidy."), menuLabel = "Tidy up")
        open().text!!.append(" and bread")
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("New note"), 5_000)
        compose.onNode(hasContentDescription("New note")).performClick()
        idleFor(300)
        assertEquals(emptyList(), triggers())
    }

    @Test
    fun `the note's menu offers the space's command and asks for its run, but never for a locked note`() {
        launch(mapOf("menu" to "Tidy."), menuLabel = "Tidy up")
        open()
        compose.onNode(hasContentDescription("More")).performClick()
        compose.onNode(hasText("Tidy up") and hasClickAction()).performClick()
        until { triggers().isNotEmpty() }
        assertEquals(listOf("menu"), triggers())

        val created = Keyring.create("correct horse", iterations = 10_000)
        onMain {
            graph.sync.lockKeys.replace(listOf(created.entry))
            graph.locks.refresh()
            graph.locks.unlock("correct horse")
            graph.store.save("bank", "Bank", LockedNotes.encrypt(created.key, "PIN 1234\n"))
        }
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Bank") and hasClickAction(), 5_000)
        compose.onNode(hasText("Bank") and hasClickAction()).performClick()
        until { graph.openNotes.note("bank") != null }
        compose.onNode(hasContentDescription("More")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Share") and hasClickAction(), 5_000)
        compose.onNode(hasText("Tidy up")).assertDoesNotExist()
    }

    @Test
    fun `the command that couldn't run says why`() {
        launch(mapOf("menu" to "Tidy."), menuLabel = "Tidy up")
        open()
        compose.onNode(hasContentDescription("More")).performClick()
        // Gone to the background meanwhile: there's no socket to ask on.
        graph.foreground.value = false
        until { !graph.agentLink.socket.connected.value }
        compose.onNode(hasText("Tidy up") and hasClickAction()).performClick()
        compose.waitUntilExactlyOneExists(hasText("The agent didn't run: it isn't on for this space."), 5_000)
    }

    /** Main-scope work resumes through the looper, which only this thread turns: blocking would wait for itself. */
    private fun <T> onMain(block: suspend () -> T): T {
        val done = graph.scope.async { block() }
        while (!done.isCompleted) {
            looper.idle()
            Thread.sleep(1)
        }
        return runBlocking { done.await() }
    }
}
