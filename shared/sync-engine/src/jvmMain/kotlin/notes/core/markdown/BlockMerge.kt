package notes.core.markdown

import notes.core.doc.Block
import notes.core.doc.Document
import notes.core.doc.ListItem
import notes.core.doc.TaskItem

/**
 * A port of apps/web/src/editor/merge.ts, step for step: shared/markdown-fixtures/merge.json and
 * merge-generated.json pin both to the same output. Change the reference first, then follow here.
 */
internal object BlockMerge {
    /** Above this many LCS cells (after the common prefix) a merge gives up: a copy is cheaper than the wait. */
    const val MAX_CELLS = 4_000_000L

    private class TooLarge : Exception()

    /** Both sides of a merged sequence; equal where nothing conflicted. */
    private data class Merged<T>(val theirs: List<T>, val mine: List<T>, val conflicts: Int)

    private class Builder<T> {
        val theirs = ArrayList<T>()
        val mine = ArrayList<T>()
        var conflicts = 0

        fun add(part: Merged<T>) {
            theirs += part.theirs
            mine += part.mine
            conflicts += part.conflicts
        }

        fun build() = Merged(theirs, mine, conflicts)
    }

    private fun <T> settled(nodes: List<T>) = Merged(nodes, nodes, 0)

    private fun <T> conflict(mine: List<T>, theirs: List<T>) = Merged(theirs, mine, 1)

    fun merge(base: String, mine: String, theirs: String): BodyMerge {
        val merged =
            try {
                mergeSeq(blocks(base), blocks(mine), blocks(theirs), ::mergeBlock)
            } catch (_: TooLarge) {
                return BodyMerge.Conflict
            }
        val theirsOut = render(merged.theirs)
        val mineOut = render(merged.mine)
        val outputs = if (merged.conflicts == 0) listOf(theirsOut) else listOf(theirsOut, mineOut)
        // Not surviving its own round trip, or repeating a block more than any input does, means a bug
        // or a diff3 anomaly: a copy loses nothing.
        if (outputs.any { Markdown.canonical(it) != it }) return BodyMerge.Conflict
        val most = maxCounts(listOf(base, mine, theirs))
        if (outputs.any { exceeds(leafCounts(it), most) }) return BodyMerge.Conflict
        if (merged.conflicts == 0) return BodyMerge.Clean(theirsOut)
        if (theirsOut == Markdown.canonical(theirs) && mineOut == Markdown.canonical(mine)) return BodyMerge.Conflict
        return BodyMerge.Partial(theirsOut, mineOut)
    }

    /** The body's blocks; the empty paragraph an empty note parses to counts as one, as on the web. */
    private fun blocks(markdown: String): List<Block> = Markdown.parse(markdown).body

    private fun render(blocks: List<Block>): String = if (blocks.isEmpty()) "" else Markdown.serialize(Document(blocks))

    // --- sequences -------------------------------------------------------------

    /** Each element of [a]'s match in [b], or -1. Ties go to deleting from [a]: ports must agree, or merges differ. */
    private fun <T> lcs(a: List<T>, b: List<T>): IntArray {
        val match = IntArray(a.size) { -1 }
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) {
            match[p] = p
            p++
        }
        val n = a.size - p
        val m = b.size - p
        if (n.toLong() * m > MAX_CELLS) throw TooLarge()
        val w = m + 1
        val table = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i * w + j] =
                    if (a[p + i] == b[p + j]) {
                        table[(i + 1) * w + j + 1] + 1
                    } else {
                        maxOf(table[(i + 1) * w + j], table[i * w + j + 1])
                    }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[p + i] == b[p + j]) {
                match[p + i] = p + j
                i++
                j++
            } else if (table[(i + 1) * w + j] >= table[i * w + j + 1]) {
                i++
            } else {
                j++
            }
        }
        return match
    }

    /** diff3 (Khanna, Kunal & Pierce): stable runs where all three match, [mergeChunk] for what lies between. */
    private fun <T : Any> mergeSeq(
        base: List<T>,
        mine: List<T>,
        theirs: List<T>,
        mergeOne: (T, T, T) -> Merged<T>
    ): Merged<T> {
        val toMine = lcs(base, mine)
        val toTheirs = lcs(base, theirs)
        val out = Builder<T>()
        var i = 0
        var a = 0
        var b = 0
        while (true) {
            while (i < base.size && toMine[i] == a && toTheirs[i] == b) {
                out.add(settled(listOf(base[i])))
                i++
                a++
                b++
            }
            if (i == base.size && a == mine.size && b == theirs.size) return out.build()
            var k = i
            while (k < base.size && (toMine[k] == -1 || toTheirs[k] == -1)) k++
            val nextA = if (k < base.size) toMine[k] else mine.size
            val nextB = if (k < base.size) toTheirs[k] else theirs.size
            out.add(
                mergeChunk(
                    base.subList(i, k),
                    mine.subList(a, nextA),
                    theirs.subList(b, nextB),
                    (i until k).map { if (toMine[it] == -1) -1 else toMine[it] - a },
                    (i until k).map { if (toTheirs[it] == -1) -1 else toTheirs[it] - b },
                    mergeOne
                )
            )
            i = k
            a = nextA
            b = nextB
        }
    }

    /** [toMine]/[toTheirs]: each base element's match in that side's chunk, or -1. */
    private fun <T : Any> mergeChunk(
        base: List<T>,
        mine: List<T>,
        theirs: List<T>,
        toMine: List<Int>,
        toTheirs: List<Int>,
        mergeOne: (T, T, T) -> Merged<T>
    ): Merged<T> {
        if (mine == base) return settled(theirs)
        if (theirs == base || mine == theirs) return settled(mine)
        val mineInPlace = inPlace(base, mine)
        val theirsInPlace = inPlace(base, theirs)
        if (mineInPlace && theirsInPlace) {
            // Paired block by block: only blocks both sides changed can conflict.
            val out = Builder<T>()
            for (k in base.indices) out.add(mergeOne(base[k], mine[k], theirs[k]))
            return out.build()
        }
        if (theirsInPlace) return applyAround(base, mine, toMine, theirs) ?: conflict(mine, theirs)
        if (mineInPlace) return applyAround(base, theirs, toTheirs, mine) ?: conflict(mine, theirs)
        return conflict(mine, theirs)
    }

    /** Edited block by block, without adding, removing or changing the kind of any. */
    private fun <T : Any> inPlace(base: List<T>, side: List<T>): Boolean =
        side.size == base.size && side.indices.all { side[it]::class == base[it]::class }

    /** [moved]'s sequence with [edited]'s version of each block it kept; null if it removed one [edited] changed. */
    private fun <T> applyAround(base: List<T>, moved: List<T>, toMoved: List<Int>, edited: List<T>): Merged<T>? {
        val out = ArrayList<T>()
        var j = 0
        for (k in base.indices) {
            val at = toMoved[k]
            if (at == -1) {
                if (edited[k] != base[k]) return null
                continue
            }
            while (j < at) out += moved[j++]
            out += edited[k]
            j = at + 1
        }
        while (j < moved.size) out += moved[j++]
        return settled(out)
    }

    // --- blocks ----------------------------------------------------------------

    /** §6.1's rule for one value: the side that changed it, or null if both changed it differently. */
    private class Field<T>(val value: T)

    private fun <T> field(base: T, mine: T, theirs: T): Field<T>? = when {
        mine == base -> Field(theirs)
        theirs == base || mine == theirs -> Field(mine)
        else -> null
    }

    /** Same-kind blocks at one position, changed on at least one side. */
    private fun mergeBlock(base: Block, mine: Block, theirs: Block): Merged<Block> {
        if (mine == base) return settled(listOf(theirs))
        if (theirs == base || mine == theirs) return settled(listOf(mine))
        val whole = conflict(listOf(mine), listOf(theirs))
        return when (base) {
            is Block.Heading -> {
                mine as Block.Heading
                theirs as Block.Heading
                val level = field(base.level, mine.level, theirs.level)
                val content = field(base.content, mine.content, theirs.content)
                if (level == null ||
                    content == null
                ) {
                    whole
                } else {
                    settled(listOf(Block.Heading(level.value, content.value)))
                }
            }

            is Block.OrderedList -> {
                mine as Block.OrderedList
                theirs as Block.OrderedList
                val start = field(base.start, mine.start, theirs.start) ?: return whole
                container(mine, theirs, mergeSeq(base.items, mine.items, theirs.items, ::mergeItem)) {
                    Block.OrderedList(start.value, it)
                }
            }

            is Block.BulletList -> {
                mine as Block.BulletList
                theirs as Block.BulletList
                container(mine, theirs, mergeSeq(base.items, mine.items, theirs.items, ::mergeItem)) {
                    Block.BulletList(it)
                }
            }

            is Block.TaskList -> {
                mine as Block.TaskList
                theirs as Block.TaskList
                container(mine, theirs, mergeSeq(base.items, mine.items, theirs.items, ::mergeTask)) {
                    Block.TaskList(it)
                }
            }

            is Block.Blockquote -> {
                mine as Block.Blockquote
                theirs as Block.Blockquote
                container(mine, theirs, mergeSeq(base.blocks, mine.blocks, theirs.blocks, ::mergeBlock)) {
                    Block.Blockquote(it)
                }
            }

            // Paragraphs and raw blocks are atomic: both changed is a conflict.
            else -> whole
        }
    }

    private fun mergeItem(base: ListItem, mine: ListItem, theirs: ListItem): Merged<ListItem> {
        if (mine == base) return settled(listOf(theirs))
        if (theirs == base || mine == theirs) return settled(listOf(mine))
        return item(mine, theirs, mergeSeq(base.blocks, mine.blocks, theirs.blocks, ::mergeBlock)) { ListItem(it) }
    }

    private fun mergeTask(base: TaskItem, mine: TaskItem, theirs: TaskItem): Merged<TaskItem> {
        if (mine == base) return settled(listOf(theirs))
        if (theirs == base || mine == theirs) return settled(listOf(mine))
        val checked = field(base.checked, mine.checked, theirs.checked) ?: return conflict(listOf(mine), listOf(theirs))
        return item(mine, theirs, mergeSeq(base.blocks, mine.blocks, theirs.blocks, ::mergeBlock)) {
            TaskItem(checked.value, it)
        }
    }

    /** A container whose children merged as a sequence; each side rebuilt around its own. */
    private fun <C, T> container(mine: T, theirs: T, inner: Merged<C>, build: (List<C>) -> T): Merged<T> {
        // Emptied (both sides removed different children): the container goes too.
        val t = if (inner.theirs.isEmpty()) emptyList() else listOf(build(inner.theirs))
        val m = if (inner.mine.isEmpty()) emptyList() else listOf(build(inner.mine))
        return Merged(t, m, inner.conflicts)
    }

    /** [container] for a list item, which must start with a paragraph; anything else conflicts whole. */
    private fun <T> item(mine: T, theirs: T, inner: Merged<Block>, build: (List<Block>) -> T): Merged<T> {
        val startsRight = { blocks: List<Block> -> blocks.isEmpty() || blocks[0] is Block.Paragraph }
        if (!startsRight(inner.theirs) || !startsRight(inner.mine)) return conflict(listOf(mine), listOf(theirs))
        return container(mine, theirs, inner, build)
    }

    // --- duplication check -------------------------------------------------------

    /** How often each leaf block (paragraph, heading, raw block) occurs. */
    private fun leafCounts(markdown: String): Map<Block, Int> {
        val counts = HashMap<Block, Int>()
        fun visit(blocks: List<Block>) {
            for (b in blocks) {
                when (b) {
                    is Block.Paragraph, is Block.Heading, is Block.RawBlock, is Block.Title -> counts[b] =
                        (counts[b] ?: 0) + 1

                    is Block.BulletList -> b.items.forEach { visit(it.blocks) }

                    is Block.OrderedList -> b.items.forEach { visit(it.blocks) }

                    is Block.TaskList -> b.items.forEach { visit(it.blocks) }

                    is Block.Blockquote -> visit(b.blocks)
                }
            }
        }
        visit(Markdown.parse(markdown).body)
        return counts
    }

    private fun maxCounts(inputs: List<String>): Map<Block, Int> {
        val most = HashMap<Block, Int>()
        for (input in inputs) for ((key, n) in leafCounts(input)) most[key] = maxOf(most[key] ?: 0, n)
        return most
    }

    // A block no input has is new (a heading with a merged level), not a duplicate.
    private fun exceeds(counts: Map<Block, Int>, most: Map<Block, Int>): Boolean =
        counts.any { (key, n) -> most[key]?.let { n > it } ?: false }
}
