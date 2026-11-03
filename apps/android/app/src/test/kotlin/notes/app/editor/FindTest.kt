package notes.app.editor

import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class FindTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)

    private var state = FindState()

    private fun editor(body: String, title: String = "T"): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote(title, Markdown.parse(body)))
        e.onFindState = { state = it }
        e.setSelection(0)
        return e
    }

    private fun NoteEditText.body() = Markdown.serialize(document())

    private fun NoteEditText.selected() = text!!.substring(selectionStart, selectionEnd)

    /** The current match's text, read from the highlight. */
    private fun NoteEditText.currentText(): String {
        val t = text!!
        val span = t.getSpans(0, t.length, FindSpan::class.java).single { it.current }
        return t.substring(t.getSpanStart(span), t.getSpanEnd(span))
    }

    private fun NoteEditText.currentStart(): Int {
        val t = text!!
        return t.getSpanStart(t.getSpans(0, t.length, FindSpan::class.java).single { it.current })
    }

    private fun NoteEditText.highlights() = text!!.getSpans(0, text!!.length, FindSpan::class.java).size

    @Test
    fun `matches ignore case and span formatting, and include the title`() {
        val e = editor("Pick up the **bi**ke. A *Bike* again.\n", title = "Bike shop")
        e.find("bike")
        assertEquals(3, state.count)
        assertEquals(3, e.highlights())
        e.findNext()
        assertEquals("bike", e.currentText())
        e.findNext()
        assertEquals("Bike", e.currentText())
    }

    @Test
    fun `nothing found, and an empty query, find nothing`() {
        val e = editor("Milk\n")
        e.find("bread")
        assertEquals(FindState(0, -1), state)
        e.find("")
        assertEquals(0, state.count)
        assertEquals(0, e.highlights())
    }

    @Test
    fun `the first match is the one at or after the cursor`() {
        val e = editor("one two one two one\n")
        val second = e.text.toString().indexOf("two")
        e.setSelection(second)
        e.find("one")
        assertEquals(FindState(3, 1), state)
        assertEquals(e.text.toString().indexOf("one", second), e.currentStart())
    }

    @Test
    fun `next and previous wrap around`() {
        val e = editor("a b a b a\n")
        e.find("a")
        assertEquals(0, state.index)
        e.findPrevious()
        assertEquals(2, state.index)
        e.findNext()
        assertEquals(0, state.index)
        e.findNext()
        e.findNext()
        e.findNext()
        assertEquals(0, state.index)
        assertEquals(3, state.count)
    }

    @Test
    fun `the query changing keeps looking from where find started`() {
        val e = editor("cat car cart\n")
        e.setSelection(e.text.toString().indexOf("car"))
        e.find("c")
        assertEquals("c", e.currentText())
        assertEquals(e.text.toString().indexOf("car"), e.currentStart())
        e.find("cart")
        assertEquals(e.text.toString().indexOf("cart"), e.currentStart())
        e.find("ca")
        // Back to the match at the start, not the one "cart" was on.
        assertEquals(e.text.toString().indexOf("car"), e.currentStart())
    }

    @Test
    fun `replace swaps the current match, keeps its formatting, and moves to the next`() {
        val e = editor("Buy **milk** and milk.\n")
        e.find("milk")
        assertEquals(FindState(2, 0), state)
        e.replace("oat milk")
        assertEquals("Buy **oat milk** and milk.\n", e.body())
        // The inserted text holds the query, but the next match is the one after it.
        assertEquals(FindState(2, 1), state)
        assertEquals(e.text.toString().lastIndexOf("milk"), e.currentStart())
        e.replace("oat milk")
        assertEquals("Buy **oat milk** and oat milk.\n", e.body())
    }

    @Test
    fun `replace keeps a link on the text it replaces`() {
        val e = editor("See [the docs](https://example.com) now.\n")
        e.find("the docs")
        e.replace("the manual")
        assertEquals("See [the manual](https://example.com) now.\n", e.body())
    }

    @Test
    fun `replace all is one undo step`() {
        val e = editor("red, Red, RED\n")
        var changes = 0
        e.onChange = { changes++ }
        e.find("red")
        assertEquals(0, changes)
        assertEquals(3, e.replaceAll("blue"))
        // One change, as typing reports it: the screen saves on it.
        assertEquals(1, changes)
        assertEquals("blue, blue, blue\n", e.body())
        assertEquals(FindState(0, -1, replaced = 3), state)
        e.undo()
        assertEquals("red, Red, RED\n", e.body())
        // Undo brought the matches back.
        assertEquals(3, state.count)
    }

    @Test
    fun `replace all reaches the title and leaves code alone`() {
        val e = editor("Some foo, and `foo` code.\n\n```\nfoo\n```\n", title = "foo")
        e.find("foo")
        assertEquals(4, state.count)
        assertEquals(2, e.replaceAll("bar"))
        assertEquals("bar", e.document().title)
        assertEquals("Some bar, and `foo` code.\n\n```\nfoo\n```\n", e.body())
    }

    @Test
    fun `highlights follow edits while finding`() {
        val e = editor("milk\n")
        e.find("milk")
        assertEquals(1, state.count)
        val end = e.text!!.length
        e.text!!.insert(end, " and more milk")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, state.count)
        assertEquals(2, e.highlights())
    }

    @Test
    fun `highlights never reach the document, the undo stack or a copy, and go when find ends`() {
        val e = editor("**milk** and milk\n")
        val before = e.body()
        e.find("milk")
        assertEquals(before, e.body())
        val snapshot = UndoStack.snapshot(e.text!!, 0, 0).text
        assertEquals(0, snapshot.getSpans(0, snapshot.length, FindSpan::class.java).size)
        val copy = SpannableStringBuilder(e.text)
        assertEquals(0, copy.getSpans(0, copy.length, FindSpan::class.java).size)
        // Typing while finding records a step; undoing it doesn't bring a highlight back in from the snapshot.
        e.text!!.insert(e.text!!.length, "!")
        e.undo()
        assertEquals(before, e.body())
        e.endFind()
        assertEquals(0, e.highlights())
        assertFalse(e.finding)
    }

    @Test
    fun `ending find selects the current match`() {
        val e = editor("one two two\n")
        e.find("two")
        e.findNext()
        e.endFind()
        assertEquals("two", e.selected())
        assertEquals(e.text.toString().lastIndexOf("two"), e.selectionStart)
    }

    @Test
    fun `a selection on one line is what to find`() {
        val e = editor("one two\n\nthree\n")
        val s = e.text.toString().indexOf("two")
        e.setSelection(s, s + 3)
        assertEquals("two", e.selectedLine())
        e.setSelection(s, e.text.toString().indexOf("three") + 2)
        assertEquals(null, e.selectedLine())
        e.setSelection(s)
        assertEquals(null, e.selectedLine())
        assertTrue(Find.matches(e.text!!, "TWO").isNotEmpty())
    }
}
