package notes.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.Fixtures

class MarkdownFixtureTest {
    @Test
    fun `canonical Markdown round-trips byte for byte`() {
        val failures =
            Fixtures.roundtrip.mapNotNull { c ->
                val out = Markdown.serialize(Markdown.parse(c.markdown))
                if (out == c.markdown) null else "${c.name}: expected ${c.markdown.quoted()} got ${out.quoted()}"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `any Markdown round-trips semantically`() {
        val failures =
            (Fixtures.plaintext + Fixtures.roundtrip).mapNotNull { c ->
                val doc = Markdown.parse(c.markdown)
                val again = Markdown.parse(Markdown.serialize(doc))
                if (again == doc) null else "${c.name}: $doc became $again"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `canonical is idempotent`() {
        val messy =
            listOf(
                "* item\n* other\n",
                "__bold__ and _it_\n",
                "trailing spaces   \nnext\n",
                "a\r\nb\r\n\r\n- c\r\n",
                "+ plus\n+ list\n",
                "1) one\n2) two\n",
                "Setext\n======\n",
                "#### deep heading\n",
                "> quote\nlazy line\n",
                "- [X] Upper\n- [ ] lower\n",
                "  - indented\n",
                "line  \nhard\n",
                "***\n",
                "<https://example.com>\n",
                "[ref]\n\n[ref]: https://example.com\n"
            )
        val failures =
            (Fixtures.roundtrip.map { it.markdown } + Fixtures.plaintext.map { it.markdown } + messy).mapNotNull { md ->
                val once = Markdown.canonical(md)
                val twice = Markdown.canonical(once)
                if (once == twice) null else "${md.quoted()}: ${once.quoted()} then ${twice.quoted()}"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `parse never throws`() {
        val random = java.util.Random(7)
        val alphabet = "ab *_~`[]()<>!#>-+.1)\\\n\t |:x@é"
        repeat(5000) {
            val s = buildString { repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            Markdown.canonical(s)
        }
    }
}

internal fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\n", "\\n") + "\""
