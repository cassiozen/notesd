package notes.core.markdown

/** The web app's mergeBody(), from src/editor/merge.ts; set with [webCanonical]. */
internal var webMerge: ((String, String, String) -> dynamic)? = null

internal actual fun mergeMarkdown(base: String, mine: String, theirs: String): BodyMerge {
    val merge = webMerge ?: error("No Markdown merge: the web app sets webMerge before opening the engine")
    val r = merge(base, mine, theirs)
    return when (r.kind as String) {
        "clean" -> BodyMerge.Clean(r.content as String)
        "partial" -> BodyMerge.Partial(r.theirs as String, r.mine as String)
        else -> BodyMerge.Conflict
    }
}
