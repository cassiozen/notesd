package notes.app.agent

import android.os.Looper
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import kotlin.test.assertTrue
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

/** What runs show beside the note open: cards between its blocks, placed as they say, answered with × and ✗. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class AgentCardsTest {
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
        return openNote("Groceries", "n1").apply { setSelection(text!!.indexOf("Milk") + 4) }
    }

    private fun openNote(title: String, id: String): NoteEditText {
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

    private fun overlays(noteId: String, vararg list: String) =
        conn.send("""{"type":"overlays","noteId":"$noteId","overlays":[${list.joinToString(",")}]}""")

    private fun card(id: String, place: String, body: String = id) =
        """{"id":"$id","runId":"r1","noteId":"n1","kind":"card","place":$place,"body":"$body"}"""

    private fun views(v: View): List<View> = listOf(v) +
        ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } }.orEmpty())

    private fun texts(v: View) = views(v).filterIsInstance<TextView>().map { it.text }

    private fun button(v: View, name: String) = views(v).first { it.contentDescription == name }

    @Test
    fun `cards go at the top, the end, the caret or under the block quoted, and never in the text`() {
        val editor = open()
        overlays(
            "n1",
            card("top", """{"at":"top"}"""),
            card("quote", """{"after":"Bread **fresh**"}"""),
            card("end", """{"at":"end"}"""),
            card("here", """{"at":"cursor"}""")
        )
        until { editor.agentCards.placed.size == 4 }
        val t = editor.text.toString()
        val milk = t.indexOf("Milk") + 4
        val bread = t.indexOf("Bread fresh") + "Bread fresh".length
        assertEquals(
            listOf("top" to t.indexOf('\n'), "quote" to bread, "end" to t.length, "here" to milk),
            editor.agentCards.placed
        )
        assertEquals(content, editor.readForAgent().fields.content)

        // Between the lines: under Bread, above Eggs.
        val quote = editor.agentCards.cardFor("quote")!!
        until { quote.height > 0 && quote.translationY > 0 }
        val layout = editor.layout
        val breadLine = layout.getLineForOffset(bread)
        val eggsLine = layout.getLineForOffset(t.indexOf("Eggs"))
        assertTrue(quote.translationY > layout.getLineTop(breadLine) + editor.totalPaddingTop)
        assertTrue(quote.translationY + quote.height <= layout.getLineTop(eggsLine) + editor.totalPaddingTop)

        // Typing carries them along, each still under its block.
        editor.text!!.insert(t.indexOf("Milk"), "Oat ")
        editor.text!!.insert(t.indexOf("Bread") + 4, "Rye ")
        val now = editor.text.toString()
        assertEquals(now.indexOf("Oat Milk") + 8, editor.agentCards.placed.single { it.first == "here" }.second)
        assertEquals(
            now.indexOf("Rye Bread fresh") + "Rye Bread fresh".length,
            editor.agentCards.placed.single { it.first == "quote" }.second
        )
    }

    @Test
    fun `a card shows its Markdown with only safe links, and a suggestion its reason and change`() {
        val editor = open()
        overlays(
            "n1",
            card("c1", """{"at":"top"}""", "See [docs](https://example.com) and [this](javascript:void)"),
            """{"id":"s1","runId":"r1","noteId":"n1","kind":"suggestion","place":{"after":"Eggs"},""" +
                """"body":"More precise","change":{"kind":"replace","find":"Eggs","replace":"Eggs (6)"}}"""
        )
        until { editor.agentCards.placed.size == 2 }
        val c1 = editor.agentCards.cardFor("c1")!!
        val shown = texts(c1).last() as Spanned
        // A link the editor doesn't allow is never one: it shows as its source, as in a note.
        assertEquals("See docs and [this](javascript:void)", shown.toString().trim())
        assertEquals(listOf("https://example.com"), shown.getSpans(0, shown.length, URLSpan::class.java).map { it.url })
        assertEquals(
            "Notes agent: a note",
            views(c1).first {
                it is TextView && it.text == "Notes agent"
            }.contentDescription
        )

        val s1 = editor.agentCards.cardFor("s1")!!
        val (_, why, change) = texts(s1).filter { it.isNotEmpty() && it != "✗" }
        assertEquals("More precise", why.toString())
        assertEquals("Eggs → Eggs (6)", change.toString())
        val struck = (change as Spanned).getSpans(0, change.length, StrikethroughSpan::class.java).single()
        assertEquals("Eggs", change.subSequence(change.getSpanStart(struck), change.getSpanEnd(struck)).toString())
    }

    @Test
    fun `× dismisses a card and ✗ rejects a suggestion, taking it away at once`() {
        val editor = open()
        overlays(
            "n1",
            card("c1", """{"at":"top"}"""),
            """{"id":"s1","runId":"r1","noteId":"n1","kind":"suggestion","place":{"at":"end"},""" +
                """"body":"","change":{"kind":"insert","at":"end","text":"- Jam"}}"""
        )
        until { editor.agentCards.placed.size == 2 }
        button(editor.agentCards.cardFor("c1")!!, "Dismiss").performClick()
        until { editor.agentCards.placed.map { it.first } == listOf("s1") }
        button(editor.agentCards.cardFor("s1")!!, "Reject the change").performClick()
        until { editor.agentCards.placed.isEmpty() }
        assertEquals(
            listOf(
                """{"type":"overlay.answer","noteId":"n1","id":"c1","answer":"dismiss"}""",
                """{"type":"overlay.answer","noteId":"n1","id":"s1","answer":"reject"}"""
            ),
            conn.got("overlay.answer")
        )
    }

    @Test
    fun `a locked note shows none`() {
        open()
        val created = Keyring.create("correct horse", iterations = 10_000)
        onMain {
            graph.sync.lockKeys.replace(listOf(created.entry))
            graph.locks.refresh()
            graph.locks.unlock("correct horse")
            graph.store.save("bank", "Bank", LockedNotes.encrypt(created.key, "PIN 1234\n"))
        }
        compose.onNode(hasContentDescription("Back")).performClick()
        val bank = openNote("Bank", "bank")
        conn.send(
            """{"type":"overlays","noteId":"bank","overlays":[""" +
                """{"id":"c1","runId":"r1","noteId":"bank","kind":"card","place":{"at":"top"},"body":"PIN?"}]}"""
        )
        until { graph.agentLink.socket.overlays.value["bank"] != null }
        compose.waitForIdle()
        looper.idle()
        assertEquals(emptyList(), bank.agentCards.placed)
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
