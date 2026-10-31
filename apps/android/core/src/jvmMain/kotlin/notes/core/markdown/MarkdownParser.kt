package notes.core.markdown

import java.net.IDN
import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.ListItem
import notes.core.doc.Mark
import notes.core.doc.TaskItem
import notes.core.doc.normalize
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document as MdDocument
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem as MdListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Markdown → [Document], matching the web parser (markdown-it + prosemirror-markdown
 * in apps/web/src/editor/markdown.ts) so every client reads a note the same way.
 */
internal class MarkdownParser(private val source: String) {
    fun parse(): Document {
        val root = parser.parse(source) as MdDocument
        return Document(blocks(root).ifEmpty { listOf(emptyParagraph()) })
    }

    private fun blocks(parent: Node): List<Block> = children(parent).mapNotNull(::block)

    private fun block(node: Node): Block? = when (node) {
        is Paragraph -> {
            Block.Paragraph(inlines(node))
        }

        // Levels 4–6 aren't in the dialect; they read as the smallest heading.
        is Heading -> {
            Block.Heading(minOf(node.level, 3), inlines(node))
        }

        is BlockQuote -> {
            Block.Blockquote(blocks(node).ifEmpty { listOf(emptyParagraph()) })
        }

        is BulletList -> {
            taskList(node) ?: Block.BulletList(children(node).map { ListItem(itemBlocks(it)) })
        }

        is OrderedList -> {
            Block.OrderedList(
                node.markerStartNumber ?: 1,
                children(node).map { ListItem(itemBlocks(it)) }
            )
        }

        // markdown-it ends an indented code block at its last non-blank line.
        is IndentedCodeBlock -> {
            Block.RawBlock(spanText(node).trimEnd { it == '\n' || it == ' ' || it == '\t' })
        }

        is FencedCodeBlock, is ThematicBreak, is TableBlock -> {
            Block.RawBlock(spanText(node))
        }

        // Link reference definitions and anything unknown: markdown-it drops them too.
        else -> {
            null
        }
    }

    /** ProseMirror's list item content is `paragraph block*`; it fills a missing paragraph. */
    private fun itemBlocks(item: Node): List<Block> {
        val blocks = blocks(item)
        return if (blocks.firstOrNull() is Block.Paragraph) blocks else listOf(emptyParagraph()) + blocks
    }

    /** GFM task list: only when every item starts with a box; a mixed list stays a bullet list. */
    private fun taskList(list: BulletList): Block.TaskList? {
        val items = children(list)
        if (items.isEmpty()) return null
        val boxes =
            items.map { item ->
                val first = item.firstChild as? Paragraph ?: return null
                TASK_PREFIX.find(spanText(first)) ?: return null
            }
        return Block.TaskList(
            items.zip(boxes) { item, box ->
                stripLeadingText(item.firstChild as Paragraph, box.value.length)
                TaskItem(checked = box.groupValues[1] != " ", blocks = itemBlocks(item as MdListItem))
            }
        )
    }

    /**
     * Removes the box from the item's first text token, as the web does. That token
     * ends at the first line break, so text after it keeps its leading space.
     */
    private fun stripLeadingText(paragraph: Paragraph, n: Int) {
        var left = n
        var node = paragraph.firstChild
        while (left > 0 && node is Text) {
            val cut = minOf(left, node.literal.length)
            node.literal = node.literal.substring(cut)
            left -= cut
            node = node.next
        }
    }

    /**
     * The active marks while walking inline content. Closing a mark removes it even
     * when an outer one of the same type is still open (`**a **b** c**` leaves " c"
     * plain): that is how prosemirror-markdown builds the web document.
     */
    private var marks: List<Mark> = emptyList()

    private fun inlines(parent: Node): List<Inline> {
        val out = ArrayList<Inline>()
        marks = emptyList()
        inlines(parent, out)
        return out.normalize()
    }

    private fun inlines(parent: Node, out: MutableList<Inline>) {
        for (node in children(parent)) {
            when (node) {
                is Text -> out += Inline.Text(node.literal, marks)

                // prosemirror-markdown turns soft breaks into a space.
                is SoftLineBreak -> out += Inline.Text(" ", marks)

                is HardLineBreak -> out += Inline.HardBreak(marks)

                is Emphasis -> marked(Mark.Italic, node, out)

                is StrongEmphasis -> marked(Mark.Bold, node, out)

                is Strikethrough -> marked(Mark.Strike, node, out)

                is Link -> link(node, out)

                is Code -> out += Inline.RawInline(inlineSource(node), marks)

                is Image -> image(node, out)

                // The web parser runs with HTML off: tags are text.
                is HtmlInline -> out += Inline.Text(node.literal, marks)

                else -> inlines(node, out)
            }
        }
    }

    private fun marked(mark: Mark, node: Node, out: MutableList<Inline>) {
        marks = Mark.setOf(marks + mark)
        inlines(node, out)
        marks = marks.filter { it.rank != mark.rank }
    }

    private fun image(node: Image, out: MutableList<Inline>) {
        if (isAllowedLink(normalizeLink(node.destination))) {
            out += Inline.RawInline(inlineSource(node), marks)
        } else {
            // markdown-it rejects the image: "!" and the brackets read as text.
            out += Inline.Text("!", marks)
            invalidLink(node, spanText(node).drop(1), node.sourceSpans.first().inputIndex + 1, out)
        }
    }

    private fun link(node: Link, out: MutableList<Inline>) {
        val href = normalizeLink(node.destination)
        if (isAllowedLink(href)) {
            marked(Mark.Link(href, node.title?.ifEmpty { null }), node, out)
            return
        }
        invalidLink(node, spanText(node), node.sourceSpans.first().inputIndex, out)
    }

    /** markdown-it rejects the link, so its brackets and destination are plain text. */
    private fun invalidLink(node: Node, markdown: String, start: Int, out: MutableList<Inline>) {
        val textEnd =
            node.lastChild
                ?.sourceSpans
                ?.lastOrNull()
                ?.let { it.inputIndex + it.length - start }
        if (textEnd == null || !markdown.startsWith("[")) {
            out += Inline.Text(markdown, marks)
            return
        }
        out += Inline.Text("[", marks)
        inlines(node, out)
        out += Inline.Text(markdown.substring(textEnd), marks)
    }

    /**
     * An inline node's exact source. A run continued on the next line keeps that
     * line's indentation, which commonmark-java leaves out of the node's span but
     * markdown-it keeps.
     */
    private fun inlineSource(node: Node): String {
        val spans = node.sourceSpans
        if (spans.size < 2) return spanText(node)
        var block = node.parent
        while (block != null && block !is Paragraph && block !is Heading) block = block.parent
        val blockSpans = block?.sourceSpans.orEmpty()
        val contentColumn = blockSpans.firstOrNull()?.columnIndex ?: 0
        val lines = blockSpans.associateBy { it.lineIndex }
        val out = StringBuilder()
        spans.forEachIndexed { i, span ->
            var start = span.inputIndex
            val line = lines[span.lineIndex]
            if (i > 0) out.append('\n')
            if (i > 0 && line != null && line.inputIndex < start &&
                source.substring(line.inputIndex, start).isBlank()
            ) {
                // A lazy line (left of the content column) loses that much indentation, as in markdown-it.
                start = line.inputIndex + maxOf(0, minOf(contentColumn - line.columnIndex, start - line.inputIndex))
            }
            out.append(source, start, span.inputIndex + span.length)
        }
        return out.toString()
    }

    /** A node's exact source, without the container prefixes ("> ", list indent) on each line. */
    private fun spanText(node: Node): String {
        val spans = node.sourceSpans
        val out = StringBuilder()
        spans.forEachIndexed { i, span ->
            // Blank lines inside a block (a fence's empty line) have no span of their own.
            if (i > 0) repeat(span.lineIndex - spans[i - 1].lineIndex) { out.append('\n') }
            out.append(source, span.inputIndex, span.inputIndex + span.length)
        }
        return out.toString()
    }

    private fun children(parent: Node): List<Node> = buildList {
        var child = parent.firstChild
        while (child != null) {
            add(child)
            child = child.next
        }
    }

    private fun emptyParagraph() = Block.Paragraph(emptyList())

    companion object {
        private val TASK_PREFIX = Regex("^\\[([ xX])\\](?: |$)")

        private val parser: Parser =
            Parser
                .builder()
                // Tables only so they become raw blocks cleanly.
                .extensions(listOf(TablesExtension.create()))
                .customBlockParserFactory(FenceOpenerFactory())
                .customDelimiterProcessor(TildeDelimiterProcessor())
                // No HtmlBlock: the web parser has HTML off, so block HTML is paragraph text.
                .enabledBlockTypes(
                    setOf(
                        Heading::class.java,
                        ThematicBreak::class.java,
                        FencedCodeBlock::class.java,
                        IndentedCodeBlock::class.java,
                        BlockQuote::class.java,
                        ListBlock::class.java
                    )
                ).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build()

        private val BAD_PROTOCOL = Regex("^(vbscript|javascript|file|data):")
        private val GOOD_DATA = Regex("^data:image/(gif|png|jpeg|webp);")

        /** markdown-it's validateLink. */
        private fun isAllowedLink(url: String): Boolean {
            val s = url.trim().lowercase()
            return !BAD_PROTOCOL.containsMatchIn(s) || GOOD_DATA.containsMatchIn(s)
        }

        private val HOST = Regex("^((?:https?:)?//)([^/?#:@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE)

        /** markdown-it's normalizeLink: punycode for http(s) hosts, then mdurl.encode. */
        internal fun normalizeLink(url: String): String {
            val withHost =
                HOST.find(url)?.let { m ->
                    val host = m.groupValues[3]
                    if (host.all { it.code < 128 }) return@let url
                    val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return@let url
                    url.replaceRange(m.groups[3]!!.range, ascii)
                } ?: url
            return encodeUrl(withHost)
        }

        private const val URL_SAFE = ";/?:@&=+$,-_.!~*'()#"

        /** mdurl.encode with its defaults: keep safe ASCII and existing %XX, percent-encode the rest. */
        internal fun encodeUrl(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' && i + 2 <= s.lastIndex && isHex(s[i + 1]) && isHex(s[i + 2]) -> {
                        out.append(s, i, i + 3)
                        i += 3
                        continue
                    }

                    c.code < 128 && (c.isLetterOrDigit() || c in URL_SAFE) -> {
                        out.append(c)
                    }

                    else -> {
                        val cp: Int
                        val len: Int
                        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                            cp = Character.toCodePoint(c, s[i + 1])
                            len = 2
                        } else {
                            cp = if (c.isSurrogate()) 0xFFFD else c.code
                            len = 1
                        }
                        for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                            out.append('%').append("%02X".format(b.toInt() and 0xFF))
                        }
                        i += len
                        continue
                    }
                }
                i++
            }
            return out.toString()
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
