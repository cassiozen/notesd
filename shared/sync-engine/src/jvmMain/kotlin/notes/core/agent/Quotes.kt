package notes.core.agent

import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.markdown.Markdown

/**
 * Which block an overlay placed `{after: quote}` goes under (docs/contributing/agent-clients.md: Overlays), as the
 * web app finds it. A quote is from the note's Markdown, but an editor shows text, so the search widens in steps.
 * Checked against shared/agent-fixtures/quote.json.
 */
object Quotes {
    /**
     * The body's text blocks in order, those inside lists and quotes included: what a quote can be in. Not raw
     * blocks (code), which the web's editor shows as their source, not as text it searches.
     */
    fun blocks(doc: Document): List<Block> = buildList { for (b in doc.body) collect(b) }

    private fun MutableList<Block>.collect(b: Block) {
        when (b) {
            is Block.Title, is Block.RawBlock -> {}
            is Block.Paragraph, is Block.Heading -> add(b)
            is Block.BulletList -> b.items.forEach { i -> i.blocks.forEach { collect(it) } }
            is Block.OrderedList -> b.items.forEach { i -> i.blocks.forEach { collect(it) } }
            is Block.TaskList -> b.items.forEach { i -> i.blocks.forEach { collect(it) } }
            is Block.Blockquote -> b.blocks.forEach { collect(it) }
        }
    }

    /**
     * The index in [blocks] of the first block whose Markdown holds [quote]; else of the one block whose text holds
     * it with the Markdown taken out. Null when none does, or more than one does and which was meant isn't known:
     * the top of the body.
     */
    fun find(doc: Document, quote: String): Int? {
        val list = blocks(doc)
        val written = list.indexOfFirst { holdsMarkdown(it, quote) }
        if (written >= 0) return written
        val plain = plainOf(quote)
        if (plain.isEmpty()) return null
        val shown = list.indices.filter { unmarked(textOf(list[it])).contains(plain) }
        return shown.singleOrNull()
    }

    /** Whether [block] still holds [quote]: one placed by it can still be what was meant. */
    fun holds(block: Block, quote: String): Boolean {
        if (holdsMarkdown(block, quote)) return true
        val plain = plainOf(quote)
        return plain.isNotEmpty() && unmarked(textOf(block)).contains(plain)
    }

    private fun holdsMarkdown(block: Block, quote: String): Boolean {
        // The block's own Markdown, less what a list or quote around it adds.
        val md = Markdown.serialize(Document(listOf(block)))
        if (md.contains(quote)) return true
        val line = longestLine(quote)
        return line.isNotEmpty() && md.contains(line)
    }

    private val blockSyntax = Regex("""^\s*(#{1,6}\s+|>\s*|[-*+]\s+(\[[ xX]]\s+)?|\d+[.)]\s+)""")
    private val inlineSyntax = Regex("""[*_~`\[\]\\]""")
    private val linkTarget = Regex("""]\([^)]*\)""")

    /** The quote's longest line (the first of equals), without the block syntax a single block's Markdown leaves out. */
    private fun longestLine(quote: String): String = quote.split('\n')
        .map { blockSyntax.replace(it, "").trim() }
        .fold("") { a, b -> if (b.length > a.length) b else a }

    /** Text without the characters inline Markdown is made of; done to both sides, so ones the text has don't stop a match. */
    private fun unmarked(text: String) = inlineSyntax.replace(text, "")

    private fun plainOf(quote: String) = unmarked(linkTarget.replace(longestLine(quote), ""))

    private fun textOf(block: Block): String = when (block) {
        is Block.Paragraph -> textOf(block.content)
        is Block.Heading -> textOf(block.content)
        else -> ""
    }

    private fun textOf(inlines: List<Inline>) = inlines.joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.RawInline -> it.source
            is Inline.HardBreak -> ""
        }
    }
}
