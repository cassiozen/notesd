package notes.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.ListItem
import notes.core.doc.Mark
import notes.core.doc.TaskItem

/**
 * Cases where commonmark-java and the web's markdown-it pipeline differ, found by
 * running random Markdown through both. Expected values are the web codec's output.
 *
 * Known remaining differences, all in input no editor produces: tab expansion inside
 * raw blocks in lists, a code span broken across a lazy blockquote line, and a few
 * markdown-it/commonmark-java disagreements on malformed link destinations and on
 * empty list items separated by several blank lines.
 */
class MarkdownCompatTest {
    @Test
    fun `canonical output matches the web codec`() {
        val cases =
            listOf(
                "***[**b**](https://q)\"#***b******b***" to "[***b***](https://q)*\"#**b***b\\*\\*\\*\n",
                "~~a~~~~b~~" to "~~ab~~\n",
                "~~~`` info\ncode\n~~~" to "~~~`` info\ncode\n~~~\n",
                "```~ info\ncode\n```" to "```~ info\ncode\n```\n",
                "![x](javascript:y)" to "!\\[x\\](javascript:y)\n",
                "[x](javascript:alert(1)) z" to "\\[x\\](javascript:alert(1)) z\n",
                "- [x] \nnext line" to "- [x]  next line\n",
                "**a **b** c**" to "**a b** c\n",
                "__[**b**](https://q)>__" to "[**b**](https://q)>\n",
                "    code\n    \n\n- after" to "    code\n\n- after\n",
                "Para\n```\ncode\n```" to "Para\n\n```\ncode\n```\n",
                "word\n***" to "word\n\n***\n",
                "a `` b" to "a \\`\\` b\n",
                "x <https://a.b> y" to "x <https://a.b> y\n",
                "[a](<https://ex.com/ä b>)" to "[a](https://ex.com/%C3%A4%20b)\n",
                "[a](https://ä.example/ö)" to "[a](https://xn--4ca.example/%C3%B6)\n",
                "<div>\n*hi*\n</div>" to "<div> *hi* </div>\n",
                "1) x" to "1. x\n",
                "+ a" to "- a\n",
                "#### four" to "### four\n",
                "Setext\n===" to "# Setext\n",
                "** a **" to "\\*\\* a \\*\\*\n",
                "a_b_c _d_" to "a_b_c *d*\n",
                "&amp; &copy;" to "& ©\n",
                "[ref]\n\n[ref]: https://x.y" to "[ref](https://x.y)\n",
                "- # h" to "-\n\n  # h\n",
                "- [ ] a\n- b" to "- \\[ \\] a\n- b\n",
                "about ~5" to "about \\~5\n",
                "foo![x](y)" to "foo![x](y)\n"
            )
        val failures =
            cases.mapNotNull { (md, expected) ->
                val got = Markdown.canonical(md)
                if (got == expected) null else "${md.quoted()}: expected ${expected.quoted()} got ${got.quoted()}"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `parse builds the web document tree`() {
        val md = "# Head\n\n- [x] **Done** [it](https://x.y \"T\")\n  1. nested\n\n> quote\\\n> `code`\n"
        val link = Mark.Link("https://x.y", "T")
        assertEquals(
            Document(
                listOf(
                    Block.Heading(1, listOf(Inline.Text("Head"))),
                    Block.TaskList(
                        listOf(
                            TaskItem(
                                checked = true,
                                blocks =
                                    listOf(
                                        Block.Paragraph(
                                            listOf(
                                                Inline.Text("Done", listOf(Mark.Bold)),
                                                Inline.Text(" "),
                                                Inline.Text("it", listOf(link))
                                            )
                                        ),
                                        Block.OrderedList(
                                            1,
                                            listOf(ListItem(listOf(Block.Paragraph(listOf(Inline.Text("nested"))))))
                                        )
                                    )
                            )
                        )
                    ),
                    Block.Blockquote(
                        listOf(
                            Block.Paragraph(
                                listOf(Inline.Text("quote"), Inline.HardBreak(), Inline.RawInline("`code`"))
                            )
                        )
                    )
                )
            ),
            Markdown.parse(md)
        )
    }

    @Test
    fun `empty input is one empty paragraph`() {
        assertEquals(Document.EMPTY, Markdown.parse(""))
        assertEquals("", Markdown.serialize(Document.EMPTY))
    }

    @Test
    fun `title line is skipped when serializing`() {
        val doc = Document.forNote("My\nnote", Markdown.parse("Body\n"))
        assertEquals("My note", doc.title)
        assertEquals("Body\n", Markdown.serialize(doc))
    }
}
