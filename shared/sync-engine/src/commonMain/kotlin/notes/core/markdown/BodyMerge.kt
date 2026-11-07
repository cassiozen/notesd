package notes.core.markdown

/** A three-way merge of note bodies (spec §6.2), as apps/web/src/editor/merge.ts defines it. */
sealed interface BodyMerge {
    data class Clean(val markdown: String) : BodyMerge

    /** Each side's version of the blocks both changed; both hold every change that merged. */
    data class Partial(val theirs: String, val mine: String) : BodyMerge

    data object Conflict : BodyMerge
}

/** Plain bodies only: callers keep locked envelopes away. Each platform's own codec decides, as for [canonicalMarkdown]. */
internal expect fun mergeMarkdown(base: String, mine: String, theirs: String): BodyMerge
