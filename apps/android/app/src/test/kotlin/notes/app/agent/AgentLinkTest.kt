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
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
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

/** When the agent socket is open, and what it says of the notes open here, against a fake notesd. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentLinkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val notesd = FakeNotesd()
    private val socket get() = graph.agentLink.socket

    @After
    fun tearDown() {
        scenario?.close()
        socket.reset()
        graph.shutDown()
        notesd.close()
    }

    private fun signedIn() {
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", "", mapOf("menu" to "Tidy."))
        notesd.start()
        notesd.signIn(graph)
        graph.agentLink.start()
    }

    /** Waits for [condition], the main looper kept going. */
    private fun until(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never came true" }
            if (scenario != null) compose.waitForIdle()
            looper.idle()
            Thread.sleep(5)
        }
    }

    /** Keeps the main looper going for [ms]: for what mustn't happen. */
    private fun idleFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `open in the foreground while the space shown has its agent on`() {
        signedIn()
        idleFor(300)
        assertEquals(0, notesd.sockets.size)

        graph.foreground.value = true
        until { socket.connected.value }
        graph.switchSpace("work")
        until { notesd.sockets[0].closed && !socket.connected.value }
        graph.switchSpace(Spaces.DEFAULT)
        until { notesd.sockets.size == 2 && socket.connected.value }

        graph.foreground.value = false
        until { notesd.sockets[1].closed }
        graph.foreground.value = true
        until { notesd.sockets.size == 3 && socket.connected.value }
    }

    @Test
    fun `a space's new notes are done ahead only while it's shown with a rule for them`() {
        notesd.agents[Spaces.DEFAULT] = AgentConfig(Spaces.DEFAULT, true, "p", "", mapOf("noteCreated" to "Start it."))
        notesd.agents["work"] = AgentConfig("work", true, "p", "", mapOf("editDone" to "Tidy."))
        notesd.start()
        notesd.signIn(graph)
        graph.agentLink.start()
        graph.foreground.value = true
        until { socket.connected.value }
        val conn = notesd.sockets.single()
        until { conn.got("prepare") == listOf("""{"type":"prepare","spaceId":"${Spaces.DEFAULT}"}""") }

        // Both have their agent on: the same socket, but work has no rule for new notes.
        graph.switchSpace("work")
        idleFor(300)
        assertEquals(1, conn.got("prepare").size)
        graph.switchSpace(Spaces.DEFAULT)
        until { conn.got("prepare").size == 2 }
        assertEquals(1, notesd.sockets.size)
    }

    @Test
    fun `AI set up while away opens it on coming back`() {
        notesd.aiConfigured = false
        signedIn()
        graph.foreground.value = true
        until { notesd.requests.count { it == "GET /api/v1/settings/ai" } == 1 }
        idleFor(300)
        assertEquals(0, notesd.sockets.size)

        synchronized(notesd) { notesd.aiConfigured = true }
        graph.foreground.value = false
        graph.foreground.value = true
        until { socket.connected.value }
    }

    @Test
    fun `signing out closes it at once, and no note open before is heard of after`() {
        signedIn()
        graph.foreground.value = true
        until { socket.connected.value }
        socket.opened("n1", Spaces.DEFAULT)
        until { notesd.sockets[0].got("editor.opened").size == 1 }

        runBlocking { graph.signOut() }
        until { notesd.sockets[0].closed }
        socket.closed("n1", 3)

        notesd.signIn(graph)
        until { notesd.sockets.size == 2 && socket.connected.value }
        idleFor(200)
        assertEquals(emptyList(), notesd.sockets[1].got)
        assertEquals(emptyList(), notesd.sockets[0].got("editor.closed"))
    }

    @Test
    fun `a note open is said, and once its last save is pushed, its close says the revision it showed`() {
        notesd.feeds[""] = listOf(RemoteNote("n1", "Groceries", "Milk\n", revision = 1, changeSeq = 1))
        signedIn()
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        graph.push = { graph.sync.sync() }
        graph.setOnboardingDismissed(true)
        graph.foreground.value = true
        until { socket.connected.value }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Groceries") and hasClickAction(), 5_000)
        compose.onNode(hasText("Groceries") and hasClickAction()).performClick()
        until { notesd.sockets[0].got("editor.opened").isNotEmpty() }
        assertEquals(
            listOf("""{"type":"editor.opened","noteId":"n1","spaceId":"default"}"""),
            notesd.sockets[0].got("editor.opened")
        )

        until { graph.openNotes.note("n1")?.view?.value != null }
        val editor = graph.openNotes.note("n1")!!.view.value!!
        editor.text!!.append(" and eggs")
        compose.onNode(hasContentDescription("Back")).performClick()

        until { notesd.sockets[0].got("editor.closed").isNotEmpty() }
        assertEquals(
            listOf("""{"type":"editor.closed","noteId":"n1","revision":2,"spaceId":"default"}"""),
            notesd.sockets[0].got("editor.closed")
        )
        assertEquals("Milk and eggs\n", notesd.feeds[""]!!.single().content)
    }
}
