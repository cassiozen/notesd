package notes.core.doc

import java.util.concurrent.atomic.AtomicLong

/**
 * A [Document] flattened into lines, the shape a native text view edits: one
 * line per textblock (title, paragraph, heading, raw block), each carrying the
 * path of containers (quotes, list items) it sits in.
 *
 * Containers carry identities rather than depths so the flat form is lossless:
 * two adjacent bullet lists, a list item with two paragraphs and a list inside
 * a quote all survive [toLines] → [linesToDocument].
 */
data class Line(val path: List<Container>, val leaf: Leaf, val content: List<Inline> = emptyList()) {
    val items: List<Container.Item> get() = path.filterIsInstance<Container.Item>()
    val item: Container.Item? get() = path.lastOrNull() as? Container.Item
}

sealed interface Container {
    val id: Long

    data class Quote(override val id: Long) : Container

    /**
     * A list item. [listId] groups items into one list; [id] groups the lines of
     * one item. [start] is the list's first number (ordered lists only), [checked]
     * the item's box (task lists only).
     */
    data class Item(
        val kind: ListKind,
        val listId: Long,
        override val id: Long,
        val checked: Boolean = false,
        val start: Int = 1
    ) : Container
}

enum class ListKind { Bullet, Ordered, Task }

sealed interface Leaf {
    data object Title : Leaf

    data object Paragraph : Leaf

    data class Heading(val level: Int) : Leaf

    data class Raw(val source: String) : Leaf
}

/** Identities for containers; unique within the process, which is all lines need. */
object Ids {
    private val next = AtomicLong(1)

    fun next(): Long = next.getAndIncrement()
}

fun Document.toLines(): List<Line> {
    val out = ArrayList<Line>()
    flatten(blocks, emptyList(), out)
    return out
}

private fun flatten(blocks: List<Block>, path: List<Container>, out: MutableList<Line>) {
    for (block in blocks) {
        when (block) {
            is Block.Title -> out += Line(path, Leaf.Title, listOf(Inline.Text(block.text)).normalize())

            is Block.Paragraph -> out += Line(path, Leaf.Paragraph, block.content)

            is Block.Heading -> out += Line(path, Leaf.Heading(block.level), block.content)

            is Block.RawBlock -> out += Line(path, Leaf.Raw(block.source))

            is Block.Blockquote -> flatten(block.blocks, path + Container.Quote(Ids.next()), out)

            is Block.BulletList -> {
                val list = Ids.next()
                for (item in block.items) {
                    flatten(item.blocks, path + Container.Item(ListKind.Bullet, list, Ids.next()), out)
                }
            }

            is Block.OrderedList -> {
                val list = Ids.next()
                for (item in block.items) {
                    flatten(
                        item.blocks,
                        path + Container.Item(ListKind.Ordered, list, Ids.next(), start = block.start),
                        out
                    )
                }
            }

            is Block.TaskList -> {
                val list = Ids.next()
                for (item in block.items) {
                    flatten(item.blocks, path + Container.Item(ListKind.Task, list, Ids.next(), item.checked), out)
                }
            }
        }
    }
}

/**
 * Rebuilds the tree: consecutive lines sharing a container at the same depth
 * share that node. A list item whose first line isn't a paragraph gets an
 * empty one, as ProseMirror's schema would add.
 */
fun linesToDocument(lines: List<Line>): Document =
    Document(build(lines, 0).ifEmpty { listOf(Block.Paragraph(emptyList())) })

private fun build(lines: List<Line>, depth: Int): List<Block> {
    val out = ArrayList<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val container = line.path.getOrNull(depth)
        if (container == null) {
            out += leafBlock(line)
            i++
            continue
        }
        var j = i + 1
        when (container) {
            is Container.Quote -> {
                while (j < lines.size && lines[j].path.getOrNull(depth) == container) j++
                out += Block.Blockquote(build(lines.subList(i, j), depth + 1))
            }

            is Container.Item -> {
                while (j < lines.size &&
                    (lines[j].path.getOrNull(depth) as? Container.Item)?.listId == container.listId
                ) {
                    j++
                }
                out += list(container, lines.subList(i, j), depth)
            }
        }
        i = j
    }
    return out
}

private fun list(first: Container.Item, lines: List<Line>, depth: Int): Block {
    val items = ArrayList<Pair<Container.Item, List<Block>>>()
    var i = 0
    while (i < lines.size) {
        val item = lines[i].path[depth] as Container.Item
        var j = i + 1
        while (j < lines.size && lines[j].path[depth].id == item.id) j++
        val blocks = build(lines.subList(i, j), depth + 1)
        items +=
            item to
            if (blocks.firstOrNull() is Block.Paragraph) blocks else listOf(Block.Paragraph(emptyList())) + blocks
        i = j
    }
    return when (first.kind) {
        ListKind.Bullet -> Block.BulletList(items.map { ListItem(it.second) })
        ListKind.Ordered -> Block.OrderedList(first.start, items.map { ListItem(it.second) })
        ListKind.Task -> Block.TaskList(items.map { TaskItem(it.first.checked, it.second) })
    }
}

private fun leafBlock(line: Line): Block = when (val leaf = line.leaf) {
    Leaf.Title -> Block.Title(line.content.filterIsInstance<Inline.Text>().joinToString("") { it.text })
    Leaf.Paragraph -> Block.Paragraph(line.content)
    is Leaf.Heading -> Block.Heading(leaf.level, line.content)
    is Leaf.Raw -> Block.RawBlock(leaf.source)
}
