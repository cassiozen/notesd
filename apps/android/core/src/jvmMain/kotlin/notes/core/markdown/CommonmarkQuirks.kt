package notes.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.internal.FencedCodeBlockParser
import org.commonmark.node.Nodes
import org.commonmark.node.SourceSpans
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

/**
 * Fence openers per CommonMark. commonmark-java 0.30 counts backticks and tildes
 * together, so "~~~`" or "```~" open nothing there; both are fences in markdown-it
 * (and the spec). Runs before the built-in factory and reuses its block parser.
 */
internal class FenceOpenerFactory : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return BlockStart.none()
        val line = state.line.content
        val start = state.nextNonSpaceIndex
        val c = line.getOrNull(start)
        if (c != '`' && c != '~') return BlockStart.none()
        var end = start
        while (end < line.length && line[end] == c) end++
        val length = end - start
        if (length < 3) return BlockStart.none()
        // The info string after a backtick fence can't contain backticks.
        if (c == '`' && line.indexOf('`', end) >= 0) return BlockStart.none()
        return BlockStart.of(FencedCodeBlockParser(c, length, state.indent)).atIndex(end)
    }
}

/**
 * Strikethrough the way markdown-it pairs it: every "~~" in a run is its own
 * delimiter, so "~~a~~~~b~~" is two struck runs (GFM would leave it literal).
 */
internal class TildeDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '~'

    override fun getClosingCharacter() = '~'

    override fun getMinLength() = 2

    override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
        if (openingRun.length() < 2 || closingRun.length() < 2) return 0
        val opener = openingRun.opener
        val strike = Strikethrough("~~")
        val spans = SourceSpans()
        spans.addAllFrom(openingRun.getOpeners(2))
        for (node in Nodes.between(opener, closingRun.closer)) {
            strike.appendChild(node)
            spans.addAll(node.sourceSpans)
        }
        spans.addAllFrom(closingRun.getClosers(2))
        strike.sourceSpans = spans.sourceSpans
        opener.insertAfter(strike)
        return 2
    }
}
