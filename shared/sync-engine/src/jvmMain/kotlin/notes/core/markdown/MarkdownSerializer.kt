package notes.core.markdown

import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.Mark
import notes.core.text.JS_SPACE_CLASS
import notes.core.text.isJsSpace

/**
 * [Document] → canonical Markdown (spec §7.4). A line-for-line port of
 * prosemirror-markdown's MarkdownSerializerState plus the node rules in
 * apps/web/src/editor/markdown.ts, because the output has to match the web
 * byte for byte: a client whose canonical form differs would make every
 * note it opens look edited to the others.
 */
internal class MarkdownSerializer {
    private val out = StringBuilder()
    private var delim = ""
    private var closed: Block? = null
    private var inTightList = false
    private var atBlockStart = false
    private var inAutolink: Boolean? = null

    fun serialize(doc: Document): String {
        renderBlocks(doc.blocks)
        val text =
            out
                .toString()
                .split('\n')
                .joinToString("\n") { it.trimEnd(' ', '\t') }
                // Not trim(): an indented code block at the very start keeps its indent.
                .trimStart('\n')
                .trimEnd(::isJsSpace)
        return if (text.isEmpty()) "" else "$text\n"
    }

    // --- blocks --------------------------------------------------------------

    private fun renderBlocks(blocks: List<Block>) {
        blocks.forEachIndexed { i, block -> render(block, blocks, i) }
    }

    private fun render(node: Block, siblings: List<Block>, index: Int) {
        when (node) {
            // The editor's first line is the note's title, stored in its own field.
            is Block.Title -> {}

            is Block.Paragraph -> {
                renderInline(node.content)
                closeBlock(node)
            }

            is Block.Heading -> {
                write("#".repeat(node.level) + " ")
                renderInline(node.content, fromBlockStart = false)
                closeBlock(node)
            }

            is Block.Blockquote -> {
                wrapBlock("> ", null, node) { renderBlocks(node.blocks) }
            }

            is Block.BulletList -> {
                val bullet = if (alternate(siblings, index, node)) "* " else "- "
                renderList(node, siblings, index, node.items.map { it.blocks }, { 2 }) { bullet }
            }

            is Block.OrderedList -> {
                val delim = if (alternate(siblings, index, node)) ") " else ". "
                // Continuation lines indent to the item's own content column ("9. " → 3, "10. " → 4):
                // the widest number's column would add a space to a code block under "9." per save.
                val marker = { i: Int -> "${node.start + i}$delim" }
                renderList(node, siblings, index, node.items.map { it.blocks }, { marker(it).length }, marker)
            }

            is Block.TaskList -> {
                val bullet = if (alternate(siblings, index, node)) "*" else "-"
                // Nested content aligns after the bullet: "[ ]" is part of the item's text.
                renderList(node, siblings, index, node.items.map { it.blocks }, { 2 }) {
                    if (node.items[it].checked) "$bullet [x] " else "$bullet [ ] "
                }
            }

            is Block.RawBlock -> {
                text(node.source, escape = false)
                closeBlock(node)
            }
        }
    }

    /**
     * Markdown merges adjacent lists that use the same marker, so a list right
     * after a sibling list of its kind switches marker ("-" ↔ "*", "." ↔ ")").
     * Bullet and task lists share the "-" marker, so they count as one kind.
     */
    private fun alternate(siblings: List<Block>, index: Int, node: Block): Boolean {
        var flip = false
        var i = index - 1
        while (i >= 0 && listKind(siblings[i]) == listKind(node)) {
            flip = !flip
            i--
        }
        return flip
    }

    private fun listKind(block: Block): String? = when (block) {
        is Block.BulletList, is Block.TaskList -> "bullet"
        is Block.OrderedList -> "ordered"
        else -> null
    }

    private fun renderList(
        node: Block,
        siblings: List<Block>,
        index: Int,
        items: List<List<Block>>,
        indent: (Int) -> Int,
        firstDelim: (Int) -> String
    ) {
        // prosemirror-markdown separates a list from a same-type predecessor with
        // an extra blank line; alternate() already keeps them apart.
        if (index > 0 && siblings[index - 1]::class == node::class) flushClose(2)
        if (closed != null && closed!!::class == node::class) {
            flushClose(3)
        } else if (inTightList) {
            flushClose(1)
        }
        val prevTight = inTightList
        inTightList = true
        items.forEachIndexed { i, blocks ->
            if (i > 0) flushClose(1)
            wrapBlock(" ".repeat(indent(i)), firstDelim(i), node) { renderBlocks(blocks) }
        }
        inTightList = prevTight
    }

    private fun wrapBlock(delim: String, firstDelim: String?, node: Block, f: () -> Unit) {
        val old = this.delim
        write(firstDelim ?: delim)
        this.delim += delim
        f()
        this.delim = old
        closeBlock(node)
    }

    private fun closeBlock(node: Block) {
        closed = node
    }

    private fun flushClose(size: Int = 2) {
        if (closed == null) return
        if (!atBlank()) out.append('\n')
        if (size > 1) {
            val delimMin = delim.trimEnd(::isJsSpace)
            repeat(size - 1) { out.append(delimMin).append('\n') }
        }
        closed = null
    }

    private fun atBlank() = out.isEmpty() || out.last() == '\n'

    private fun write(content: String? = null) {
        flushClose()
        if (delim.isNotEmpty() && atBlank()) out.append(delim)
        if (!content.isNullOrEmpty()) out.append(content)
    }

    private fun text(text: String, escape: Boolean = true) {
        val lines = text.split('\n')
        lines.forEachIndexed { i, line ->
            write()
            // Escape exclamation marks in front of links.
            if (!escape && line.startsWith("[") && endsWithUnescapedBang()) {
                out.setLength(out.length - 1)
                out.append("\\!")
            }
            out.append(if (escape) esc(line, atBlockStart) else line)
            if (i != lines.lastIndex) out.append('\n')
        }
    }

    private fun endsWithUnescapedBang() = out.endsWith("!") && (out.length == 1 || out[out.length - 2] != '\\')

    // --- inline --------------------------------------------------------------

    private fun renderInline(content: List<Inline>, fromBlockStart: Boolean = true) {
        atBlockStart = fromBlockStart
        val active = ArrayList<Mark>()
        var trailing = ""

        fun progress(original: Inline?, index: Int) {
            var node = original
            var marks = node?.marks ?: emptyList()
            // Remove marks from a hard break that is the last node inside them, to
            // avoid parser edge cases with a newline just before closing marks.
            if (node is Inline.HardBreak) {
                marks =
                    marks.filter { m ->
                        if (index + 1 == content.size) return@filter false
                        val next = content[index + 1]
                        m in next.marks && (next !is Inline.Text || next.text.any { !isJsSpace(it) })
                    }
            }
            val len = marks.size
            // Reorder mixable marks (all of ours are), which Markdown may open and
            // close in any order, so the order for this node matches `active`.
            outer@ for (i in 0 until len) {
                val mark = marks[i]
                for (j in active.indices) {
                    if (mark == active[j]) {
                        if (i > j) {
                            marks = marks.jsSlice(0, j) + mark + marks.jsSlice(j, i) + marks.jsSlice(i + 1, len)
                        } else if (j > i) {
                            marks = marks.jsSlice(0, i) + marks.jsSlice(i + 1, j) + mark + marks.jsSlice(j, len)
                        }
                        continue@outer
                    }
                }
            }
            // Find the prefix of the mark set that didn't change.
            var keep = 0
            while (keep < minOf(active.size, len) && marks[keep] == active[keep]) keep++

            var leading = trailing
            trailing = ""
            // Expel whitespace at the edges of bold/italic/strike outside the delimiters.
            if (node is Inline.Text &&
                marks.any { expels(it) && active.withIndex().none { (i, m) -> i < keep && m == it } }
            ) {
                val lead = node.text.takeWhile(::isJsSpace)
                if (lead.isNotEmpty()) {
                    leading += lead
                    val rest = node.text.substring(lead.length)
                    node = if (rest.isNotEmpty()) node.copy(text = rest) else null
                    if (node == null) marks = active.toList()
                }
            }
            if (node is Inline.Text &&
                marks.withIndex().any { (i, m) ->
                    expels(m) && !isMarkAhead(content, index + 1, marks.subList(0, i + 1))
                }
            ) {
                val trail = node.text.takeLastWhile(::isJsSpace)
                if (trail.isNotEmpty()) {
                    trailing = trail
                    val rest = node.text.substring(0, node.text.length - trail.length)
                    node = if (rest.isNotEmpty()) node.copy(text = rest) else null
                    if (node == null) marks = active.toList()
                }
            }

            // Close the marks that need to be closed.
            if (node != null || index == content.size) {
                while (keep <
                    active.size
                ) {
                    text(markString(active.removeAt(active.lastIndex), false, content, index), false)
                }
            }
            // Output any previously expelled trailing whitespace outside the marks.
            if (leading.isNotEmpty()) text(leading)
            if (node != null) {
                while (active.size < len) {
                    val add = marks[active.size]
                    active += add
                    text(markString(add, true, content, index), false)
                    atBlockStart = false
                }
                renderInlineNode(node, content, index)
                atBlockStart = false
            }
        }

        content.forEachIndexed { i, node -> progress(node, i) }
        progress(null, content.size)
        atBlockStart = false
    }

    private fun renderInlineNode(node: Inline, content: List<Inline>, index: Int) {
        when (node) {
            is Inline.Text -> {
                text(node.text, escape = inAutolink != true)
            }

            is Inline.RawInline -> {
                text(node.source, escape = false)
            }

            // Written only when something other than hard breaks follows: a trailing break is dropped.
            is Inline.HardBreak -> {
                if (content.subList(index + 1, content.size).any { it !is Inline.HardBreak }) write("\\\n")
            }
        }
    }

    private fun expels(mark: Mark) = mark !is Mark.Link

    private fun isMarkAhead(content: List<Inline>, start: Int, marks: List<Mark>): Boolean {
        var index = start
        while (true) {
            if (index >= content.size) return false
            val next = content[index]
            if (next !is Inline.HardBreak) {
                return next.marks.size >= marks.size && next.marks.subList(0, marks.size) == marks
            }
            // Skips the node after a hard break too, as prosemirror-markdown does.
            index += 2
        }
    }

    private fun markString(mark: Mark, open: Boolean, content: List<Inline>, index: Int): String = when (mark) {
        Mark.Bold -> {
            "**"
        }

        Mark.Italic -> {
            "*"
        }

        Mark.Strike -> {
            "~~"
        }

        is Mark.Link -> {
            if (open) {
                inAutolink = isPlainUrl(mark, content, index)
                if (inAutolink == true) "<" else "["
            } else {
                val wasAutolink = inAutolink
                inAutolink = null
                if (wasAutolink == true) {
                    ">"
                } else {
                    val href = mark.href.replace(LINK_HREF_ESCAPES, "\\\\$0")
                    val title = mark.title?.let { " \"${it.replace("\"", "\\\"")}\"" } ?: ""
                    "]($href$title)"
                }
            }
        }
    }

    private fun isPlainUrl(link: Mark.Link, content: List<Inline>, index: Int): Boolean {
        if (link.title != null || !SCHEME.containsMatchIn(link.href)) return false
        val node = content[index]
        if (node !is Inline.Text || node.text != link.href || node.marks.lastOrNull() != link) return false
        return index == content.lastIndex || link !in content[index + 1].marks
    }

    /** prosemirror-markdown's esc(): escape so text reads back as the same text. */
    private fun esc(str: String, startOfLine: Boolean): String {
        val sb = StringBuilder(str.length + 8)
        for (i in str.indices) {
            val c = str[i]
            if (c in ESCAPED) {
                val intraword =
                    c == '_' && i > 0 && i + 1 < str.length && isWordChar(str[i - 1]) && isWordChar(str[i + 1])
                if (!intraword) sb.append('\\')
            }
            sb.append(c)
        }
        var s = sb.toString()
        if (startOfLine) {
            s = s.replaceFirst(LINE_START_MARKER, "\\\\$0")
            s = s.replaceFirst(LINE_START_HEADING, "$1\\\\$2$3")
            s = s.replaceFirst(LINE_START_ORDERED, "$1\\\\. ")
        }
        return s
    }

    companion object {
        private const val ESCAPED = "`*\\~[]_"
        private val LINK_HREF_ESCAPES = Regex("[()\"]")
        private val SCHEME = Regex("^\\w+:")
        private val LINE_START_MARKER = Regex("^(\\+ |[-*>])")
        private val LINE_START_HEADING = Regex("^($JS_SPACE_CLASS*)(#{1,6})($JS_SPACE_CLASS|$)")
        private val LINE_START_ORDERED = Regex("^($JS_SPACE_CLASS*[0-9]+)\\.$JS_SPACE_CLASS")

        private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    }
}

/** JavaScript's Array.slice: indices clamp instead of throwing. */
private fun <T> List<T>.jsSlice(from: Int, to: Int): List<T> {
    val end = minOf(to, size)
    return if (from >= end) emptyList() else subList(from, end)
}
