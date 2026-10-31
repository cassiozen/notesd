package notes.core.markdown

/**
 * The canonical form of stored Markdown: what any editor would save for it.
 * Each platform's own editor codec decides (commonmark on the JVM, the web
 * app's markdown-it); shared/markdown-fixtures keeps them in step.
 */
internal expect fun canonicalMarkdown(markdown: String): String
