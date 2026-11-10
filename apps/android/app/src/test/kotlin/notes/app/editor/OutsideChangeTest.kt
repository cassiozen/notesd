package notes.app.editor

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A change from outside (sync, an agent) made to the note open: only what differs, as one undo step. */
@RunWith(AndroidJUnit4::class)
class OutsideChangeTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)
    private val looper = shadowOf(Looper.getMainLooper())

    private fun editor(body: String, at: String? = null): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote("T", Markdown.parse(body)))
        val text = e.text.toString()
        e.setSelection(if (at == null) text.length else text.indexOf(at) + at.length)
        return e
    }

    private fun NoteEditText.type(s: String) {
        text!!.replace(selectionStart, selectionEnd, s)
        looper.idle()
    }

    private fun NoteEditText.body() = Markdown.serialize(document())

    /** [body] made to the editor from outside, and whether it changed anything. */
    private fun NoteEditText.outside(body: String, byAgent: Boolean = true, title: String = "T"): Boolean {
        var changed: Boolean? = null
        CoroutineScope(Dispatchers.Main).launch {
            changed = applyOutside(byAgent) { Document.forNote(title, Markdown.parse(body)) }
        }
        looper.idle()
        return changed ?: error("still waiting")
    }

    /** The text before the cursor, from the start of its line. */
    private fun NoteEditText.beforeCursor(): String {
        val t = text.toString()
        return t.substring(t.lastIndexOf('\n', selectionStart - 1) + 1, selectionStart)
    }

    @Test
    fun `undo takes back only the change, and typing either side is a step of its own`() {
        val e = editor("First\n\nSecond\n", at = "Second")
        e.type(" line")
        assertTrue(e.outside("# Added\n\nFirst\n\nSecond line\n"))
        e.type("!")
        assertEquals("# Added\n\nFirst\n\nSecond line!\n", e.body())
        e.undo()
        assertEquals("# Added\n\nFirst\n\nSecond line\n", e.body())
        e.undo()
        assertEquals("First\n\nSecond line\n", e.body())
        e.undo()
        assertEquals("First\n\nSecond\n", e.body())
    }

    @Test
    fun `the cursor stays with the text it was in`() {
        val e = editor("First\n\nSecond\n", at = "Sec")
        e.outside("# Added\n\nFirst\n\nSecond\n")
        assertEquals("Sec", e.beforeCursor())
        // In the block that changed, where its text is the same.
        e.outside("# Added\n\nFirst\n\nSecond, and more\n")
        assertEquals("Sec", e.beforeCursor())
        val f = editor("Hello world\n", at = "Hello")
        f.outside("Hello there world\n")
        assertEquals("Hello", f.beforeCursor())
    }

    @Test
    fun `a list keeps being one list, and its items keep their markers`() {
        val e = editor("- Milk\n- Tea\n\nAfter\n", at = "Milk")
        e.outside("- Milk\n- Tea\n- Bread\n\nAfter\n")
        assertEquals("- Milk\n- Tea\n- Bread\n\nAfter\n", e.body())
        e.outside("- Milk\n- Oats\n- Tea\n- Bread\n\nAfter\n")
        assertEquals("- Milk\n- Oats\n- Tea\n- Bread\n\nAfter\n", e.body())
        val n = editor("1. One\n2. Two\n")
        n.outside("1. One\n2. Two\n3. Three\n")
        assertEquals("1. One\n2. Two\n3. Three\n", n.body())
        val tasks = editor("- [x] Done\n- [ ] Todo\n")
        tasks.outside("- [x] Done\n- [ ] Todo\n- [ ] Next\n")
        assertEquals("- [x] Done\n- [ ] Todo\n- [ ] Next\n", tasks.body())
    }

    @Test
    fun `raw blocks, quotes and the end marker hold`() {
        val raw = "Intro\n\n```kotlin\nval x = 1\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |\n"
        val e = editor(raw, at = "Intro")
        e.outside("Intro, changed\n\n```kotlin\nval x = 1\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |\n")
        assertEquals(Markdown.canonical("Intro, changed\n\n" + raw.substringAfter("Intro\n\n")), e.body())
        val q = editor("> Quoted\n>\n> more\n\nAfter\n")
        q.outside("> Quoted\n>\n> more\n>\n> and more\n\nAfter\n")
        assertEquals("> Quoted\n>\n> more\n>\n> and more\n\nAfter\n", q.body())
        // An empty last item keeps its end marker.
        val m = editor("- Milk\n- \n")
        val before = m.body()
        m.outside(before, title = "Groceries")
        assertEquals(before, m.body())
        assertEquals("Groceries", m.document().title)
        assertTrue(endMarkerStart(m.text!!) >= 0)
    }

    @Test
    fun `the blocks kept keep their spans`() {
        val e = editor("A [link](https://example.com) here\n\nSecond\n")
        val link = e.text!!.getSpans(0, e.text!!.length, LinkSpan::class.java).single()
        e.outside("A [link](https://example.com) here\n\nSecond, changed\n")
        assertSame(link, e.text!!.getSpans(0, e.text!!.length, LinkSpan::class.java).single())
    }

    @Test
    fun `an agent's change is reported as not the user's, a synced one isn't, and the same text is no change`() {
        val e = editor("Body\n")
        val seen = ArrayList<Boolean>()
        e.onChange = { seen += it }
        e.outside("Body\n\n#tag\n", byAgent = true)
        e.outside("Body\n\n#tag\n\nMore\n", byAgent = false)
        assertFalse(e.outside("Body\n\n#tag\n\nMore\n"))
        e.type("!")
        assertEquals(listOf(false, true), seen)
    }

    /** An IME composing [word] at the cursor, as the user types it. */
    private fun NoteEditText.compose(word: String): InputConnection {
        val ic = onCreateInputConnection(EditorInfo())!!
        ic.setComposingText(word, 1)
        looper.idle()
        return ic
    }

    @Test
    fun `it waits for the IME to finish composing`() {
        val e = editor("Body\n")
        val ic = e.compose(" wor")
        var done = false
        CoroutineScope(Dispatchers.Main).launch {
            // As an agent's change is made: to the text once the word's done.
            e.applyOutside(byAgent = true) { Document.forNote("T", Markdown.parse(it.fields.content + "\n#tag\n")) }
            done = true
        }
        looper.idleFor(Duration.ofMillis(500))
        assertFalse(done)
        ic.setComposingText(" word", 1)
        ic.finishComposingText()
        looper.idleFor(Duration.ofMillis(100))
        assertTrue(done)
        assertEquals("Body word\n\n#tag\n", e.body())
    }

    @Test
    fun `it doesn't wait for ever`() {
        val e = editor("Body\n")
        e.compose(" wor")
        var done = false
        CoroutineScope(Dispatchers.Main).launch {
            e.applyOutside(byAgent = true) { Document.forNote("T", Markdown.parse(it.fields.content + "\n#tag\n")) }
            done = true
        }
        looper.idleFor(Duration.ofMillis(3_100))
        assertTrue(done)
        assertEquals("Body wor\n\n#tag\n", e.body())
    }
}
