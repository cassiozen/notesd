package notes.core.markdown

internal actual fun mergeMarkdown(base: String, mine: String, theirs: String): BodyMerge =
    BlockMerge.merge(base, mine, theirs)
