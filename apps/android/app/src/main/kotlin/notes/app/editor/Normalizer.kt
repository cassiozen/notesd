package notes.app.editor

import android.text.Editable
import android.text.Selection
import android.text.Spanned
import notes.core.doc.Container
import notes.core.doc.Ids
import notes.core.doc.Leaf
import notes.core.doc.ListKind
import notes.core.text.OBJECT
import notes.core.text.scanTags

/**
 * Re-derives the structure spans from the text after every change, so editing
 * can never leave the spans inconsistent:
 *
 * - Blocks are the text between separators: every "\n" that isn't a hard break
 *   and isn't inside a raw block.
 * - Each block gets exactly one [BlockSpan]. A block that kept its span's start
 *   keeps its type; a block split off by Enter takes the type the line above
 *   continues into (next list item, same quote, paragraph after a heading or
 *   the title); blocks joined by deleting a separator take the first one's type.
 * - The first block is the title; nothing else is.
 * - List numbers, bullets and boxes are assigned here, not at draw time.
 * - Marks never cross a block, and the title and raw blocks have none.
 *
 * Only span work happens while an IME composition is active, with one
 * exception that can't wait: a raw run that lost part of its text is removed
 * whole (raw text is never edited in place).
 */
class Normalizer(private val style: EditorStyle, private val images: ImageSpans? = null) {
    private data class Info(val path: List<Container>, val leaf: Leaf)

    private class Range(val start: Int, val contentEnd: Int, val end: Int)

    fun normalize(text: Editable, composingStart: Int = -1, composingEnd: Int = -1) {
        removeDamagedRaw(text, composingStart, composingEnd)
        splitRawBlocks(text)
        cleanHardBreaks(text)
        cleanEndMarker(text)
        var ranges = ranges(text)
        var infos = assign(text, ranges)
        if (fixEndMarker(text, ranges, infos)) {
            ranges = ranges(text)
            infos = assign(text, ranges)
        }
        val markers = markers(infos)
        val edges = quoteEdges(infos)
        val gaps = gaps(infos, edges)
        applyBlockSpans(text, ranges, infos, markers, gaps, edges)
        applyLeafStyles(text, ranges, infos, gaps)
        clipMarks(text, ranges, infos)
        applyTags(text)
        images?.apply(text)
    }

    // --- raw runs ------------------------------------------------------------

    private fun removeDamagedRaw(text: Editable, composingStart: Int, composingEnd: Int) {
        val damaged = text.getSpans(0, text.length, EditorSpan::class.java)
            .filter { it is RawInlineSpan || it is RawBlockSpan }
            .filter { span ->
                val s = text.getSpanStart(span)
                val e = text.getSpanEnd(span)
                text.substring(s, e) != sourceOf(span) && !(composingStart < e && composingEnd > s)
            }
            .sortedByDescending { text.getSpanStart(it) }
        for (span in damaged) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            text.removeSpan(span)
            if (e > s) text.delete(s, e)
        }
    }

    /** Keeps a raw block alone on its line: text that ended up beside it gets its own block. */
    private fun splitRawBlocks(text: Editable) {
        val raws = text.getSpans(0, text.length, RawBlockSpan::class.java).sortedByDescending { text.getSpanStart(it) }
        for (raw in raws) {
            val s = text.getSpanStart(raw)
            val e = text.getSpanEnd(raw)
            if (e < text.length && text[e] != '\n') text.insert(e, "\n")
            if (s > 0 && text[s - 1] != '\n') text.insert(s, "\n")
        }
    }

    private fun sourceOf(span: EditorSpan) = when (span) {
        is RawInlineSpan -> span.source
        is RawBlockSpan -> span.shown
        else -> ""
    }

    private fun cleanHardBreaks(text: Editable) {
        for (span in text.getSpans(0, text.length, HardBreakSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (e - s != 1 || text[s] != '\n') text.removeSpan(span)
        }
    }

    // --- end marker (see EndMarkerSpan) --------------------------------------

    /** Drops a marker that is no longer a lone marker character at the end (text was typed or pasted around it). */
    private fun cleanEndMarker(text: Editable) {
        val valid = endMarkerStart(text)
        for (span in text.getSpans(0, text.length, EndMarkerSpan::class.java)) {
            val s = text.getSpanStart(span)
            if (s == valid) continue
            text.removeSpan(span)
            if (s in 0 until text.length && text[s] == END_MARKER) text.delete(s, s + 1)
        }
    }

    /** Adds or removes the marker as the last block needs it. True when the text changed. */
    private fun fixEndMarker(text: Editable, ranges: List<Range>, infos: List<Info>): Boolean {
        val last = ranges.last()
        val info = infos.last()
        val needed = ranges.size > 1 && last.start == last.contentEnd &&
            (info.path.isNotEmpty() || info.leaf is Leaf.Heading)
        val marker = endMarkerStart(text)
        when {
            needed && marker < 0 -> {
                val selStart = Selection.getSelectionStart(text)
                val selEnd = Selection.getSelectionEnd(text)
                text.append(END_MARKER)
                text.setSpan(EndMarkerSpan(), text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // A cursor at the end would have moved past the marker.
                val at = text.length - 1
                if (selStart >= 0) Selection.setSelection(text, minOf(selStart, at), minOf(selEnd, at))
            }

            !needed && marker >= 0 -> {
                text.getSpans(0, text.length, EndMarkerSpan::class.java).forEach(text::removeSpan)
                text.delete(marker, marker + 1)
            }

            else -> return false
        }
        return true
    }

    // --- blocks --------------------------------------------------------------

    private fun ranges(text: Spanned): List<Range> {
        val breaks = text.getSpans(0, text.length, HardBreakSpan::class.java).mapTo(HashSet()) { text.getSpanStart(it) }
        val raws = text.getSpans(0, text.length, RawBlockSpan::class.java).map {
            text.getSpanStart(it) until
                text.getSpanEnd(it)
        }
        val out = ArrayList<Range>()
        var start = 0
        for (i in 0 until text.length) {
            if (text[i] != '\n' || i in breaks || raws.any { i in it }) continue
            out += Range(start, i, i + 1)
            start = i + 1
        }
        val marker = endMarkerStart(text)
        out += Range(start, if (marker >= start) marker else text.length, text.length)
        return out
    }

    private fun assign(text: Spanned, ranges: List<Range>): List<Info> {
        val spans = text.getSpans(0, text.length, BlockSpan::class.java).sortedBy { text.getSpanStart(it) }
        val owners = arrayOfNulls<BlockSpan>(ranges.size)
        val infos = ArrayList<Info>(ranges.size)
        ranges.forEachIndexed { k, r ->
            val owner = spans.firstOrNull { text.getSpanStart(it) in r.start..r.contentEnd }
            owners[k] = owner
            // A block without a span of its own was split off the one above (Enter, a paste).
            infos += when {
                owner != null -> Info(owner.path, owner.leaf)
                k > 0 -> derive(infos[k - 1])
                else -> Info(emptyList(), Leaf.Paragraph)
            }
        }

        // Enter at the start of a non-empty block: the new empty line goes above,
        // and the text keeps its type (a heading stays a heading, an item its box).
        for (k in 0 until ranges.lastIndex) {
            val owner = owners[k] ?: continue
            if (owner.leaf == Leaf.Title || owners[k + 1] != null) continue
            if (ranges[k].start == ranges[k].contentEnd && ranges[k + 1].start < ranges[k + 1].contentEnd) {
                infos[k + 1] = infos[k]
                infos[k] = derive(infos[k])
            }
        }

        for (k in infos.indices) {
            val info = infos[k]
            infos[k] = when {
                k == 0 -> Info(emptyList(), Leaf.Title)
                info.leaf == Leaf.Title -> Info(emptyList(), Leaf.Paragraph)
                info.leaf is Leaf.Raw && !hasRaw(text, ranges[k]) -> Info(info.path, Leaf.Paragraph)
                else -> info
            }
        }
        return infos
    }

    private fun hasRaw(text: Spanned, r: Range) =
        text.getSpans(r.start, r.contentEnd, RawBlockSpan::class.java).any { text.getSpanStart(it) == r.start }

    /** The type a new line continues into from the line it was split off. */
    private fun derive(info: Info): Info = when (info.leaf) {
        Leaf.Title -> Info(emptyList(), Leaf.Paragraph)

        is Leaf.Heading, is Leaf.Raw -> Info(info.path, Leaf.Paragraph)

        Leaf.Paragraph -> {
            val item = info.path.lastOrNull() as? Container.Item
            if (item ==
                null
            ) {
                info
            } else {
                Info(info.path.dropLast(1) + item.copy(id = Ids.next(), checked = false), Leaf.Paragraph)
            }
        }
    }

    private fun markers(infos: List<Info>): List<Marker?> {
        val next = HashMap<Long, Int>()
        return infos.mapIndexed { k, info ->
            val item = info.path.lastOrNull() as? Container.Item ?: return@mapIndexed null
            val previous = infos.getOrNull(k - 1)?.path.orEmpty()
            if (previous.any { it is Container.Item && it.id == item.id }) return@mapIndexed null
            when (item.kind) {
                ListKind.Bullet -> Marker.Bullet(info.path.count { it is Container.Item } - 1)

                ListKind.Task -> Marker.Box(item.checked)

                ListKind.Ordered -> {
                    val n = next[item.listId] ?: item.start
                    next[item.listId] = n + 1
                    Marker.Number(n)
                }
            }
        }
    }

    /** How many of each block's innermost quotes start on it, and how many end on it: those its neighbour isn't in. */
    private fun quoteEdges(infos: List<Info>): List<Pair<Int, Int>> = infos.indices.map { k ->
        val quotes = infos[k].path.filterIsInstance<Container.Quote>()
        val above = infos.getOrNull(k - 1)?.path.orEmpty()
        val below = infos.getOrNull(k + 1)?.path.orEmpty()
        quotes.count { it !in above } to quotes.count { it !in below }
    }

    /** The space after each block: wide around quotes and raw blocks, narrow inside a list. */
    private fun gaps(infos: List<Info>, edges: List<Pair<Int, Int>>): List<Gap> = infos.indices.map { k ->
        val next = infos.getOrNull(k + 1) ?: return@map Gap.Paragraph
        when {
            infos[k].leaf is Leaf.Raw || next.leaf is Leaf.Raw || edges[k].second > 0 || edges[k + 1].first > 0 ->
                Gap.Block

            sameList(infos[k].path, next.path) -> Gap.Item

            else -> Gap.Paragraph
        }
    }

    /** Both blocks are in one list, at whatever depth: they share its outermost item's list. */
    private fun sameList(a: List<Container>, b: List<Container>): Boolean {
        val d = a.indexOfFirst { it is Container.Item }
        if (d < 0 || d >= b.size || a.subList(0, d) != b.subList(0, d)) return false
        val x = a[d] as Container.Item
        val y = b[d] as? Container.Item ?: return false
        return x.listId == y.listId
    }

    private fun applyBlockSpans(
        text: Editable,
        ranges: List<Range>,
        infos: List<Info>,
        markers: List<Marker?>,
        gaps: List<Gap>,
        edges: List<Pair<Int, Int>>
    ) {
        val existing = text.getSpans(0, text.length, BlockSpan::class.java)
        val byStart = existing.groupBy { text.getSpanStart(it) }
        val keep = HashSet<BlockSpan>()
        ranges.forEachIndexed { k, r ->
            val info = infos[k]
            val ends = edges[k].second
            val reuse = byStart[r.start]?.firstOrNull {
                it !in keep &&
                    it.sameAs(info.path, info.leaf, markers[k], gaps[k], ends) &&
                    text.getSpanEnd(it) == r.end
            }
            if (reuse != null) {
                keep += reuse
            } else {
                val span = BlockSpan(info.path, info.leaf, markers[k], style, gaps[k], ends)
                text.setSpan(span, r.start, r.end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                keep += span
            }
        }
        for (span in existing) if (span !in keep) text.removeSpan(span)
    }

    private fun applyLeafStyles(text: Editable, ranges: List<Range>, infos: List<Info>, gaps: List<Gap>) {
        val existing = text.getSpans(0, text.length, LeafStyleSpan::class.java)
        val byStart = existing.groupBy { text.getSpanStart(it) }
        val keep = HashSet<LeafStyleSpan>()
        ranges.forEachIndexed { k, r ->
            val info = infos[k]
            val dim = info.path.any { it is Container.Item && it.kind == ListKind.Task && it.checked }
            val quoted = info.path.any { it is Container.Quote }
            val reuse = byStart[r.start]?.firstOrNull {
                it !in keep && it.sameAs(info.leaf, dim, quoted, gaps[k]) && text.getSpanEnd(it) == r.end
            }
            if (reuse != null) {
                keep += reuse
            } else {
                val span = LeafStyleSpan(info.leaf, dim, quoted, style, gaps[k])
                text.setSpan(span, r.start, r.end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                keep += span
            }
        }
        for (span in existing) if (span !in keep) text.removeSpan(span)
    }

    // --- tags ----------------------------------------------------------------

    /**
     * Finds tags as the shared extractor finds them in Markdown: link text,
     * raw runs and the end marker can't hold them (they read as [OBJECT]),
     * and every "\n" ends a line. Unchanged tags keep their spans.
     */
    private fun applyTags(text: Editable) {
        val visible = StringBuilder(text)
        for (span in text.getSpans(0, text.length, EditorSpan::class.java)) {
            if (span is LinkSpan || span is RawInlineSpan || span is RawBlockSpan || span is EndMarkerSpan) {
                for (i in text.getSpanStart(span) until text.getSpanEnd(span)) {
                    if (visible[i] != '\n') visible.setCharAt(i, OBJECT)
                }
            }
        }
        val found = HashMap<Pair<Int, Int>, String>()
        var lineStart = 0
        for (line in visible.split('\n')) {
            for (m in scanTags(line)) found[lineStart + m.start to lineStart + m.end] = m.name
            lineStart += line.length + 1
        }
        for (span in text.getSpans(0, text.length, TagSpan::class.java)) {
            val at = text.getSpanStart(span) to text.getSpanEnd(span)
            if (found[at] == span.name) found.remove(at) else text.removeSpan(span)
        }
        for ((at, name) in found) {
            text.setSpan(TagSpan(name), at.first, at.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // --- marks ---------------------------------------------------------------

    private fun clipMarks(text: Editable, ranges: List<Range>, infos: List<Info>) {
        val markable = ranges.filterIndexed { k, _ -> infos[k].leaf != Leaf.Title && infos[k].leaf !is Leaf.Raw }
        val spans = text.getSpans(0, text.length, EditorSpan::class.java).filter { SpanCodec.markOf(it) != null }
        for (span in spans) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            val pieces = markable.mapNotNull { r ->
                val a = maxOf(s, r.start)
                val b = minOf(e, r.contentEnd)
                if (a < b) a to b else null
            }
            if (pieces.size == 1 && pieces[0] == (s to e)) continue
            val mark = SpanCodec.markOf(span)!!
            text.removeSpan(span)
            for ((a, b) in pieces) text.setSpan(SpanCodec.markSpan(mark, style), a, b, SpanCodec.flagsFor(mark))
        }
    }
}
