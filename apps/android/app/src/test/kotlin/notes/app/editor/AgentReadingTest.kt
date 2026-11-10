package notes.app.editor

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentReadingTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)

    /** An editor holding [title] and [content], the caret where U+E001 was in either. */
    private fun editor(title: String, content: String): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote(title, Markdown.parse(content)))
        val at = e.text.toString().indexOf(CARET)
        e.text!!.delete(at, at + 1)
        e.setSelection(at)
        return e
    }

    @Test
    fun `the shared cases`() {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        val cases = JSONObject(File(dir, "agent-fixtures/cursor.json").readText()).getJSONArray("cases")
        val failures = (0 until cases.length()).mapNotNull {
            val c = cases.getJSONObject(it)
            val want = if (c.isNull("cursor")) null else c.getInt("cursor")
            val got = editor(c.getString("title"), c.getString("content")).readForAgent().cursor
            if (got == want) null else "${c.getString("name")}: $got, want $want"
        }
        assertEquals(emptyList(), failures)
        check(cases.length() > 0)
    }

    @Test
    fun `the text is what saving writes`() {
        val read = editor("Plan", "* Milk\n* Te${CARET}a").readForAgent()
        assertEquals("Plan", read.fields.title)
        assertEquals("- Milk\n- Tea\n", read.fields.content)
    }

    @Test
    fun `no caret with a selection, or in a raw block`() {
        val e = editor("T", "Some te${CARET}xt")
        e.setSelection(1, 4)
        assertNull(e.readForAgent().cursor)
        assertNull(editor("T", "Before\n\n```\nco${CARET}de\n```\n").readForAgent().cursor)
    }

    @Test
    fun `a pending bold toggle isn't in the saved text, so it isn't in the caret either`() {
        val e = editor("T", "Dear$CARET Sam")
        e.toggleBold()
        assertEquals(4, e.readForAgent().cursor)
    }

    private companion object {
        const val CARET = ''
    }
}
