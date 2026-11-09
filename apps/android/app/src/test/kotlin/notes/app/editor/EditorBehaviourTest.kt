package notes.app.editor

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import notes.app.R
import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.ListKind
import notes.core.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class EditorBehaviourTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)

    /** An editor holding [body] under the title "T", with the cursor at the end of [at] (or the end). */
    private fun editor(body: String, at: String? = null): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote("T", Markdown.parse(body)))
        val text = e.text.toString()
        e.setSelection(if (at == null) text.length else text.indexOf(at) + at.length)
        return e
    }

    private fun NoteEditText.cursorAt(offsetOfStartOf: String) = setSelection(text.toString().indexOf(offsetOfStartOf))

    private fun NoteEditText.key(code: Int, meta: Int = 0) {
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
        onKeyDown(code, down)
        onKeyUp(code, KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun NoteEditText.enter() = key(KeyEvent.KEYCODE_ENTER)

    private fun NoteEditText.backspace() = key(KeyEvent.KEYCODE_DEL)

    /** What an IME's commitText does: replace the selection. */
    private fun NoteEditText.type(s: String) {
        text!!.replace(selectionStart, selectionEnd, s)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun NoteEditText.body() = Markdown.serialize(document())

    @Test
    fun `enter in a list item starts the next item`() {
        val e = editor("- Milk\n")
        e.enter()
        e.type("Bread")
        assertEquals("- Milk\n- Bread\n", e.body())
    }

    @Test
    fun `enter on an empty item ends the list`() {
        val e = editor("- Milk\n")
        e.enter()
        e.enter()
        e.type("After")
        assertEquals("- Milk\n\nAfter\n", e.body())
    }

    @Test
    fun `enter on an empty nested item outdents it`() {
        val e = editor("- Parent\n  - Child\n")
        e.enter()
        e.enter()
        e.type("Sibling")
        assertEquals("- Parent\n  - Child\n- Sibling\n", e.body())
    }

    @Test
    fun `enter in a task list starts an unchecked task`() {
        val e = editor("- [x] Done\n")
        e.enter()
        e.type("Next")
        assertEquals("- [x] Done\n- [ ] Next\n", e.body())
    }

    @Test
    fun `numbers continue and renumber`() {
        val e = editor("1. One\n2. Two\n", at = "One")
        e.enter()
        e.type("Inserted")
        assertEquals("1. One\n2. Inserted\n3. Two\n", e.body())
    }

    @Test
    fun `enter in a heading continues with a paragraph`() {
        val e = editor("# Head\n")
        e.enter()
        e.type("Body")
        assertEquals("# Head\n\nBody\n", e.body())
    }

    @Test
    fun `enter at the start of a heading keeps the heading`() {
        val e = editor("Intro\n\n# Head\n")
        e.cursorAt("Head")
        e.enter()
        assertEquals("Intro\n\n# Head\n", e.body())
        assertEquals(Block.Heading(1, listOf(Inline.Text("Head"))), e.document().blocks.last())
    }

    @Test
    fun `enter in the title starts the body`() {
        val e = NoteEditText(context)
        e.type("Groceries")
        e.enter()
        e.type("Milk")
        assertEquals("Groceries", e.document().title)
        assertEquals("Milk\n", e.body())
    }

    @Test
    fun `quotes continue and an empty line leaves them`() {
        val e = editor("> Quoted\n")
        e.enter()
        e.type("More")
        e.enter()
        e.enter()
        e.type("Out")
        assertEquals("> Quoted\n>\n> More\n\nOut\n", e.body())
    }

    @Test
    fun `backspace at the start of an item removes the marker, then joins`() {
        val e = editor("Para\n\n- Item\n")
        e.cursorAt("Item")
        e.backspace()
        assertEquals("Para\n\nItem\n", e.body())
        e.backspace()
        assertEquals("ParaItem\n", e.body())
    }

    @Test
    fun `backspace at the start of a nested item outdents it`() {
        val e = editor("- One\n  - Two\n")
        e.cursorAt("Two")
        e.backspace()
        assertEquals("- One\n- Two\n", e.body())
    }

    @Test
    fun `tab indents under the previous item, shift-tab brings it back`() {
        val e = editor("- One\n- Two\n- Three\n", at = "Two")
        e.key(KeyEvent.KEYCODE_TAB)
        assertEquals("- One\n  - Two\n- Three\n", e.body())
        e.key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        assertEquals("- One\n- Two\n- Three\n", e.body())
    }

    @Test
    fun `outdenting a middle child takes the later children with it`() {
        val e = editor("- P\n  - A\n  - B\n  - C\n", at = "B")
        e.outdent()
        assertEquals("- P\n  - A\n- B\n  - C\n", e.body())
    }

    @Test
    fun `typing at the end of bold stays bold until bold is turned off`() {
        val e = editor("**Bold**\n")
        e.type(" more")
        assertEquals("**Bold more**\n", e.body())
        e.toggleBold()
        e.type(" plain")
        assertEquals("**Bold more** plain\n", e.body())
    }

    @Test
    fun `bold toggled at the cursor applies to what is typed`() {
        val e = editor("Start\n")
        e.type(" ")
        e.toggleBold()
        e.type("strong")
        assertEquals("Start **strong**\n", e.body())
    }

    @Test
    fun `formatting commands`() {
        val e = editor("Line\n")
        e.setSelection(e.text.toString().indexOf("Line"), e.text!!.length)
        e.toggleItalic()
        assertEquals("*Line*\n", e.body())
        e.toggleList(ListKind.Task)
        assertEquals("- [ ] *Line*\n", e.body())
        e.toggleList(ListKind.Ordered)
        assertEquals("1. *Line*\n", e.body())
        e.toggleQuote()
        assertEquals("> 1. *Line*\n", e.body())
        e.toggleHeading(2)
        assertEquals("> ## *Line*\n", e.body())
        e.toggleHeading(2)
        assertEquals("> *Line*\n", e.body())
    }

    @Test
    fun `a list joins the list right above it`() {
        val e = editor("- One\n\nTwo\n", at = "Two")
        e.toggleList(ListKind.Bullet)
        assertEquals("- One\n- Two\n", e.body())
    }

    @Test
    fun `the title takes no marks`() {
        val e = editor("Body\n")
        e.setSelection(0, e.text!!.length)
        e.toggleBold()
        assertEquals("T", e.document().title)
        assertEquals("**Body**\n", e.body())
    }

    @Test
    fun `links can be added, edited and removed`() {
        val e = editor("See docs\n")
        val start = e.text.toString().indexOf("docs")
        e.setSelection(start, start + 4)
        e.setLink("example.com/docs")
        assertEquals("See [docs](https://example.com/docs)\n", e.body())
        e.setSelection(start + 1)
        e.setLink("https://example.org")
        assertEquals("See [docs](https://example.org)\n", e.body())
        e.removeLink(start, start + 4)
        assertEquals("See docs\n", e.body())
    }

    @Test
    fun `shift-enter is a hard break`() {
        val e = editor("one\n")
        e.key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
        e.type("two")
        assertEquals("one\\\ntwo\n", e.body())
    }

    @Test
    fun `raw blocks are read-only and kept byte for byte`() {
        val md = "Before\n\n```go\nfmt.Println(\"*hi*\")\n```\n\nAfter\n"
        val e = editor(md, at = "Println")
        e.type("X")
        assertEquals(md, e.body())
        e.backspace()
        assertEquals(md, e.body())
        // Typing right after the block starts a new line.
        val end = e.text.toString().indexOf("\nAfter")
        e.setSelection(end)
        e.type("Z")
        assertEquals("Before\n\n```go\nfmt.Println(\"*hi*\")\n```\n\nZ\n\nAfter\n", e.body())
    }

    @Test
    fun `backspace next to a raw block doesn't merge into it`() {
        val md = "```\ncode\n```\n\nAfter\n"
        val e = editor(md)
        e.cursorAt("After")
        e.backspace()
        assertEquals(md, e.body())
    }

    @Test
    fun `a raw inline run is deleted whole`() {
        val e = editor("Run `go test` now\n", at = "`go test`")
        e.backspace()
        assertEquals("Run  now\n", e.body())
    }

    @Test
    fun `undo restores structure, redo reapplies it`() {
        val e = editor("- Milk\n")
        e.enter()
        e.type("Bread")
        e.toggleList(ListKind.Bullet)
        assertEquals("- Milk\n\nBread\n", e.body())
        e.undo()
        assertEquals("- Milk\n- Bread\n", e.body())
        e.undo()
        e.undo()
        assertEquals("- Milk\n", e.body())
        e.redo()
        e.redo()
        assertEquals("- Milk\n- Bread\n", e.body())
    }

    @Test
    fun `checking a task`() {
        val e = editor("- [ ] Todo\n")
        e.toggleTaskAt(e.text.toString().indexOf("Todo"))
        assertEquals("- [x] Todo\n", e.body())
    }

    // An empty last block needs the end marker: without it, Android's Layout draws no marker on the last line and
    // indents the line above instead.

    private fun NoteEditText.laidOut(): android.text.Layout {
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        layout(0, 0, 1080, measuredHeight)
        return layout
    }

    @Test
    fun `a new empty task at the end is indented, and the line above isn't`() {
        val e = editor("Para\n")
        e.enter()
        e.toggleList(ListKind.Task)
        val l = e.laidOut()
        assertEquals(0, l.getParagraphLeft(1))
        assertEquals(e.editorStyle.itemIndent.toInt(), l.getParagraphLeft(l.lineCount - 1))
        assertEquals("Para\n\n- [ ]\n", e.body())
        assertEquals(e.text!!.length - 1, e.selectionStart)
    }

    @Test
    fun `a task added to an empty body leaves the title alone`() {
        val e = editor("")
        e.toggleList(ListKind.Task)
        val l = e.laidOut()
        assertEquals(0, l.getParagraphLeft(0))
        assertEquals(e.editorStyle.itemIndent.toInt(), l.getParagraphLeft(l.lineCount - 1))
    }

    @Test
    fun `typing into the empty item drops the marker`() {
        val e = editor("Para\n")
        e.enter()
        e.toggleList(ListKind.Task)
        e.type("milk")
        assertEquals("T\nPara\nmilk", e.text.toString())
        assertEquals("Para\n\n- [ ] milk\n", e.body())
    }

    @Test
    fun `enter and backspace on the empty last item still leave the list`() {
        val e = editor("- [ ] a\n")
        e.enter()
        e.enter()
        assertEquals("- [ ] a\n", e.body())
        assertEquals(-1, e.text.toString().indexOf(END_MARKER))
        e.backspace()
        e.enter()
        assertEquals("- [ ] a\n- [ ]\n", e.body())
        e.backspace()
        assertEquals(-1, e.text.toString().indexOf(END_MARKER))
        assertEquals("- [ ] a\n", e.body())
    }

    @Test
    fun `the cursor can't go past the marker`() {
        val e = editor("Para\n")
        e.enter()
        e.toggleList(ListKind.Bullet)
        e.setSelection(e.text!!.length)
        assertEquals(e.text!!.length - 1, e.selectionStart)
    }
}
