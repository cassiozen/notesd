package notes.core.markdown

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.DocGen
import notes.core.doc.Document

/** parse(serialize(doc)) == doc for random documents an editor can produce ([DocGen]). */
class MarkdownPropertyTest {
    @Test
    fun `random documents survive a round trip`() {
        val failures = ArrayList<String>()
        for (seed in 1L..3000L) {
            val doc = DocGen(Random(seed)).document()
            val md = Markdown.serialize(doc)
            val back = Markdown.parse(md)
            if (back != doc && failures.size < 5) {
                val i = doc.blocks.indices.firstOrNull { doc.blocks[it] != back.blocks.getOrNull(it) } ?: 0
                val block = Markdown.serialize(Document(listOf(doc.blocks[i])))
                failures += "seed $seed block $i\n${block.quoted()}\n${doc.blocks[i]}\n${back.blocks.getOrNull(i)}"
            }
        }
        assertEquals(emptyList(), failures)
    }
}
