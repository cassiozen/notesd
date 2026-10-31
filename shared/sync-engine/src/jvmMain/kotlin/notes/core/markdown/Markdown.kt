package notes.core.markdown

import notes.core.doc.Document

/** Markdown ⇄ [Document] for the restricted dialect (spec §7.1, §7.4). */
object Markdown {
    /** Parses stored Markdown into a document. Never throws on odd input. */
    fun parse(markdown: String): Document = MarkdownParser(markdown.replace(CRLF, "\n")).parse()

    /** Deterministic output: no trailing whitespace, one final newline, "" when empty. Skips the title line. */
    fun serialize(doc: Document): String = MarkdownSerializer().serialize(doc)

    /** The canonical form of stored Markdown: what any editor would save for it. */
    fun canonical(markdown: String): String = serialize(parse(markdown))

    private val CRLF = Regex("\r\n?")
}
