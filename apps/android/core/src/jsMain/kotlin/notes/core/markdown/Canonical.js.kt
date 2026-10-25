package notes.core.markdown

/** The web app's canonical(), from src/editor/markdown.ts; set before the first sync. */
internal var webCanonical: ((String) -> String)? = null

internal actual fun canonicalMarkdown(markdown: String): String =
    (webCanonical ?: error("No Markdown codec: the web app sets webCanonical before opening the engine"))(markdown)
