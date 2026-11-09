package notes.app.editor

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import notes.core.doc.Container

/**
 * A change that didn't come from typing (another device's, through sync, or an agent's) made to the editor's
 * text: the blocks that are the same at either end stay as they are, with their spans, pictures and ids; only
 * the blocks between are replaced.
 */
internal object OutsideChange {
    /** Where offsets in the text before go in the text after. */
    fun interface Mapping {
        fun map(offset: Int): Int
    }

    /**
     * Changes [text] to [target] (normalised text from [SpanCodec.toSpannable]). Null when they're the same.
     * The caller normalises [text] afterwards.
     */
    fun apply(text: Editable, target: Spanned, style: EditorStyle): Mapping? {
        val a = Blocks.of(text)
        val b = Blocks.of(target)
        val sa = signatures(text, a)
        val sb = signatures(target, b)
        var p = 0
        while (p < a.size && p < b.size && sa[p] == sb[p]) p++
        if (p == a.size && p == b.size) return null
        var s = 0
        while (s < minOf(a.size, b.size) - p && sa[a.size - 1 - s] == sb[b.size - 1 - s]) s++
        // Changed at the end: the last block before the change ends with a "\n" on one side only.
        if (s == 0 && p > 0 && (p == a.size || p == b.size)) p--

        val startA = a.getOrNull(p)?.start ?: text.length
        val endA = if (s > 0) a[a.size - s].start else text.length
        val startB = b.getOrNull(p)?.start ?: target.length
        val endB = if (s > 0) b[b.size - s].start else target.length

        // The new blocks join the lists and quotes of the blocks kept around them.
        val ids = HashMap<Long, Long>()
        val kept = (0 until p).map { it to it } + (1..s).map { a.size - it to b.size - it }
        for ((i, j) in kept) {
            for ((ca, cb) in a[i].span.path.zip(b[j].span.path)) {
                ids[cb.id] = ca.id
                if (ca is Container.Item && cb is Container.Item) ids[cb.listId] = ca.listId
            }
        }
        // Only the spans wholly inside: a ranged copy would bring the neighbours' in too, as empty spans.
        val middle = SpannableStringBuilder(target.subSequence(startB, endB).toString())
        for (span in target.getSpans(startB, endB, Any::class.java)) {
            val from = target.getSpanStart(span)
            val to = target.getSpanEnd(span)
            if (from < startB || to > endB) continue
            val copy = if (span is BlockSpan) {
                val path = span.path.map { c ->
                    when (c) {
                        is Container.Quote -> c.copy(id = ids[c.id] ?: c.id)
                        is Container.Item -> c.copy(id = ids[c.id] ?: c.id, listId = ids[c.listId] ?: c.listId)
                    }
                }
                if (path == span.path) span else BlockSpan(path, span.leaf, null, style)
            } else {
                span
            }
            middle.setSpan(copy, from - startB, to - startB, target.getSpanFlags(span))
        }

        val oldMiddle = text.substring(startA, endA)
        val newMiddle = middle.toString()
        // The spans of the blocks going go with them. Those touching the change from outside are put back where
        // they were: an inclusive end or start would take the new text in.
        val touching = ArrayList<Triple<Any, Int, Int>>()
        for (span in text.getSpans(startA, endA, Any::class.java)) {
            if (span === Selection.SELECTION_START || span === Selection.SELECTION_END) continue
            val from = text.getSpanStart(span)
            val to = text.getSpanEnd(span)
            when {
                from >= startA && to <= endA && !(from == to && (from == startA || from == endA)) -> text.removeSpan(
                    span
                )

                to <= startA || from >= endA -> touching += Triple(span, from, to)
            }
        }
        text.replace(startA, endA, middle)
        val delta = newMiddle.length - oldMiddle.length
        for ((span, from, to) in touching) {
            val f = if (from >= endA) from + delta else from
            val t = if (to >= endA) to + delta else to
            text.setSpan(span, f, t, text.getSpanFlags(span))
        }

        val same = oldMiddle.commonPrefixWith(newMiddle).length
        val sameEnd = oldMiddle.drop(same).commonSuffixWith(newMiddle.drop(same)).length
        return Mapping { x ->
            when {
                x <= startA -> x

                x >= endA -> x + delta

                // Inside the change: where it's the same text, it stays with it; else after what came in.
                x - startA <= same -> x

                endA - x <= sameEnd -> x + delta

                else -> startA + newMiddle.length - sameEnd
            }
        }
    }

    /**
     * What makes two blocks the same, ids aside: their text, kind, the containers they're in (and whether each
     * goes on from the line before), and the runs of marks, breaks and raw text in them.
     */
    private fun signatures(text: Spanned, blocks: List<BlockRange>): List<Any> = blocks.mapIndexed { k, block ->
        val before = blocks.getOrNull(k - 1)?.span?.path.orEmpty()
        val path = block.span.path.mapIndexed { depth, c ->
            val goesOn = before.getOrNull(depth)?.id == c.id
            when (c) {
                is Container.Quote -> listOf("quote", goesOn)

                is Container.Item -> listOf(
                    c.kind,
                    c.checked,
                    c.start,
                    goesOn,
                    before.getOrNull(depth).let {
                        it is Container.Item && it.listId == c.listId
                    }
                )
            }
        }
        val runs = SpanCodec.inlines(text, block.start, block.contentEnd)
        listOf(text.substring(block.start, block.contentEnd), block.span.leaf, path, runs)
    }
}
