package notes.core

import java.util.Random
import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.ListItem
import notes.core.doc.Mark
import notes.core.doc.TaskItem
import notes.core.doc.normalize

/**
 * Random documents an editor can produce. The generator stays inside what the
 * dialect can express, which the web's serializer shares: marks change at word
 * boundaries and a space between words is marked only when both neighbours carry
 * the same marks; no leading/trailing whitespace in a block; no hard break in a
 * heading (a heading is one line); a line after a hard break starts with a letter
 * (a "- " or "#" there isn't escaped); and a nested ordered list starts at 1 (inside a
 * list nothing separates it from the text above, and CommonMark won't let another
 * number interrupt a paragraph).
 */
class DocGen(private val r: Random) {
    private val letters =
        "abcdefghijklmnopqrstuvwxyzéü日🎉שלום".codePoints().toArray().map {
            String(
                Character.toChars(it)
            )
        }
    private val symbols = "*_`[]\\~#-+>!:,?\"'".map { it.toString() }
    private val links = listOf(Mark.Link("https://example.com/a"), Mark.Link("mailto:me@x.y", "Mail"))

    fun document(): Document = Document(blocks(depth = 0, count = 1 + r.nextInt(5)))

    private fun blocks(depth: Int, count: Int): List<Block> = List(count) { block(depth) }

    private fun block(depth: Int): Block = when (r.nextInt(if (depth > 2) 3 else 9)) {
        0, 1 -> {
            paragraph()
        }

        2 -> {
            Block.Heading(1 + r.nextInt(3), inlines(lettersOnly = true))
        }

        3 -> {
            Block.BulletList(List(1 + r.nextInt(3)) { ListItem(itemBlocks(depth)) })
        }

        4 -> {
            val start = if (depth > 0) 1 else 1 + r.nextInt(12)
            Block.OrderedList(start, List(1 + r.nextInt(3)) { ListItem(itemBlocks(depth)) })
        }

        5 -> {
            Block.TaskList(List(1 + r.nextInt(3)) { TaskItem(r.nextBoolean(), itemBlocks(depth)) })
        }

        6 -> {
            Block.Blockquote(blocks(depth + 1, 1 + r.nextInt(2)))
        }

        7 -> {
            Block.RawBlock(
                listOf("```\ncode *x*\n\n  more\n```", "---", "| a | b |\n| - | - |\n| 1 | 2 |").random()
            )
        }

        else -> {
            paragraph()
        }
    }

    private fun itemBlocks(depth: Int): List<Block> = listOf(paragraph()) + List(r.nextInt(2)) { block(depth + 1) }

    private fun paragraph() = Block.Paragraph(inlines(lettersOnly = false))

    private fun inlines(lettersOnly: Boolean): List<Inline> {
        val out = ArrayList<Inline>()
        var prevMarks = emptyList<Mark>()
        var afterBreak = false
        repeat(1 + r.nextInt(6)) { i ->
            val marks = marks()
            if (i > 0) {
                val shared = if (prevMarks == marks) marks else emptyList()
                if (!lettersOnly && !afterBreak && r.nextInt(8) == 0) {
                    out += Inline.HardBreak(shared)
                    afterBreak = true
                } else {
                    out += Inline.Text(" ", shared)
                    afterBreak = false
                }
            }
            out +=
                if (!afterBreak && r.nextInt(10) == 0) {
                    Inline.RawInline(listOf("`code`", "``a ` b``", "![alt](p.png)").random(), marks)
                } else {
                    Inline.Text(word(lettersOnly || afterBreak), marks)
                }
            afterBreak = false
            prevMarks = marks
        }
        return out.normalize()
    }

    private fun word(lettersOnly: Boolean): String {
        val chars = if (lettersOnly) letters else letters + symbols
        return letters.random() + (1 until 1 + r.nextInt(5)).joinToString("") { chars.random() }.trimEnd('\\')
    }

    private fun marks(): List<Mark> = Mark.setOf(
        buildList {
            if (r.nextInt(4) == 0) add(Mark.Bold)
            if (r.nextInt(4) == 0) add(Mark.Italic)
            if (r.nextInt(5) == 0) add(Mark.Strike)
            if (r.nextInt(6) == 0) add(links.random())
        }
    )

    private fun <T> List<T>.random(): T = this[r.nextInt(size)]
}
