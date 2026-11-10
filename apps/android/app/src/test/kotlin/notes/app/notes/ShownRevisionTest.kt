package notes.app.notes

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import notes.app.FakeNotesd
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.shutDown
import notes.core.agent.Fields
import notes.core.api.RemoteNote
import notes.core.doc.Document
import notes.core.markdown.Markdown
import notes.core.sync.SyncOutcome
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What an agent needs of the open note: the server's revision of what it shows, and its changes saved at once. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class ShownRevisionTest {
    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private val notesd = FakeNotesd()

    @After
    fun tearDown() {
        graph.shutDown()
        notesd.close()
    }

    /** [block] on IO, the main looper kept going meanwhile: saves and store reads hop back to Main. */
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

    private var syncs = 0

    private fun opened(): EditorViewModel {
        // Another client's Markdown style, which this one saves as "- Milk".
        notesd.feeds[""] = listOf(RemoteNote("n1", "Groceries", "* Milk\n", revision = 3, changeSeq = 1))
        notesd.start()
        notesd.signIn(graph)
        assertEquals(SyncOutcome.Done, runBlocking { graph.sync.sync() })
        val vm = EditorViewModel(graph.store, "n1", graph.scope, graph.locks, requestSync = { syncs++ })
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.document.value == null) {
            check(System.currentTimeMillis() < deadline) { "never loaded" }
            looper.idle()
            Thread.sleep(5)
        }
        return vm
    }

    @Test
    fun `the stored revision while the editor shows it, in any Markdown style`() {
        val vm = opened()
        assertEquals(3L, offMain { vm.shownRevision(Fields("Groceries", "- Milk\n")) })
        assertNull(offMain { vm.shownRevision(Fields("Groceries", "- Milk\n- Tea\n")) })
        assertNull(offMain { vm.shownRevision(Fields("Shopping", "- Milk\n")) })
    }

    @Test
    fun `none while a change is unsaved, or saved and not yet synced`() {
        val vm = opened()
        vm.edited()
        assertNull(offMain { vm.shownRevision(Fields("Groceries", "- Milk\n")) })
        val doc = Document.forNote("Groceries", Markdown.parse("- Milk\n- Tea\n"))
        vm.save(doc)
        offMain { vm.saved() }
        assertNull(offMain { vm.shownRevision(Fields("Groceries", "- Milk\n- Tea\n")) })
    }

    @Test
    fun `an agent's change is saved and synced at once`() {
        val vm = opened()
        vm.edited()
        vm.saveNow(Document.forNote("Groceries", Markdown.parse("- Milk\n\n#food\n")))
        val deadline = System.currentTimeMillis() + 5_000
        while (syncs == 0) {
            check(System.currentTimeMillis() < deadline) { "never synced" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("- Milk\n\n#food\n", offMain { graph.store.get("n1") }?.content)
    }
}
