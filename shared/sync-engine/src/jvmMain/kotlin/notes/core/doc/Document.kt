package notes.core.doc

/**
 * The editor document, mirroring the web editor's ProseMirror schema node for
 * node so every client parses and serializes the same tree (spec §7.1).
 *
 * Invariants (what ProseMirror's schema enforces on the web): [blocks] is never
 * empty; list items and task items start with a [Block.Paragraph]; a blockquote
 * is never empty; inline content is [normalize]d.
 */
data class Document(val blocks: List<Block>) {
    /** The title line, when this is an editor document (`title block+`). */
    val title: String? get() = (blocks.firstOrNull() as? Block.Title)?.text

    /** The body without the title line: what is serialized to `content`. */
    val body: List<Block> get() = if (blocks.firstOrNull() is Block.Title) blocks.drop(1) else blocks

    companion object {
        val EMPTY = Document(listOf(Block.Paragraph(emptyList())))

        /** The editor document for a note: its title as the first line, then the body. */
        fun forNote(title: String, body: Document): Document =
            Document(listOf(Block.Title(foldTitle(title))) + body.body)

        /** Titles from other clients can hold line breaks; the title line can't. */
        fun foldTitle(title: String): String = title.replace(Regex("\\s*\\n\\s*"), " ")
    }
}

sealed interface Block {
    /** The note's first line, stored as the plain-text `title` field, never in the Markdown. */
    data class Title(val text: String) : Block

    data class Paragraph(val content: List<Inline>) : Block

    data class Heading(val level: Int, val content: List<Inline>) : Block {
        init {
            require(level in 1..3) { "heading level $level" }
        }
    }

    data class BulletList(val items: List<ListItem>) : Block

    data class OrderedList(val start: Int, val items: List<ListItem>) : Block

    data class TaskList(val items: List<TaskItem>) : Block

    data class Blockquote(val blocks: List<Block>) : Block

    /** Markdown outside the dialect (code, tables, rules…), kept as its exact source. */
    data class RawBlock(val source: String) : Block
}

data class ListItem(val blocks: List<Block>)

data class TaskItem(val checked: Boolean, val blocks: List<Block>)

sealed interface Inline {
    val marks: List<Mark>

    data class Text(val text: String, override val marks: List<Mark> = emptyList()) : Inline

    data class HardBreak(override val marks: List<Mark> = emptyList()) : Inline

    /** An inline construct outside the dialect (code span, image), kept as its exact source. */
    data class RawInline(val source: String, override val marks: List<Mark> = emptyList()) : Inline
}

/** Marks, ordered by [rank] the way the web schema orders them: link outermost. */
sealed class Mark(val rank: Int) {
    data class Link(val href: String, val title: String? = null) : Mark(0)

    data object Bold : Mark(1)

    data object Italic : Mark(2)

    data object Strike : Mark(3)

    companion object {
        /** A mark set in canonical order, one mark per type (a new link replaces the old). */
        fun setOf(marks: Iterable<Mark>): List<Mark> = marks
            .groupBy { it.rank }
            .map { (_, same) -> same.last() }
            .sortedBy { it.rank }
    }
}

/** Merges adjacent text runs with the same marks and drops empty ones, as ProseMirror does. */
fun List<Inline>.normalize(): List<Inline> {
    val out = ArrayList<Inline>(size)
    for (node in this) {
        if (node is Inline.Text && node.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (node is Inline.Text && last is Inline.Text && last.marks == node.marks) {
            out[out.size - 1] = Inline.Text(last.text + node.text, node.marks)
        } else {
            out += node
        }
    }
    return out
}
