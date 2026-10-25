package notes.core.markdown

internal actual fun canonicalMarkdown(markdown: String): String = Markdown.canonical(markdown)
