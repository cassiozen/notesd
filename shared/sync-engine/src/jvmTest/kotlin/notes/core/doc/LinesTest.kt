package notes.core.doc

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.DocGen
import notes.core.Fixtures
import notes.core.markdown.Markdown

class LinesTest {
    @Test
    fun `fixtures survive flattening`() {
        val failures = (Fixtures.roundtrip + Fixtures.plaintext).mapNotNull { c ->
            val doc = Document.forNote("Title", Markdown.parse(c.markdown))
            val back = linesToDocument(doc.toLines())
            if (back == doc) null else "${c.name}: $doc became $back"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `random documents survive flattening`() {
        val failures = (1L..2000L).mapNotNull { seed ->
            val doc = DocGen(Random(seed)).document()
            val back = linesToDocument(doc.toLines())
            if (back == doc) null else "seed $seed: $doc became $back"
        }
        assertEquals(emptyList(), failures.take(3))
    }

    @Test
    fun `adjacent lists, multi-paragraph items and quoted lists keep their shape`() {
        val md = "- One\n\n* Two\n\n- a\n\n  b\n\n> - q\n> - r\n"
        val lines = Markdown.parse(md).toLines()
        assertEquals(4, lines.mapNotNull { it.item?.listId }.distinct().size)
        assertEquals(md, Markdown.serialize(linesToDocument(lines)))
    }
}
