package notes.core.db

/**
 * Undo for an automatic body merge (§6.2): the note goes back to [keepTitle]/[keepContent] and
 * [copyTitle]/[copyContent] becomes a conflicted copy, what merging less would have done.
 */
data class MergeUndo(
    val noteId: String,
    val keepTitle: String,
    val keepContent: String,
    val copyTitle: String,
    val copyContent: String,
    val mergedTitle: String,
    val mergedContent: String,
    val mergedAt: Long
)

/** Well under the 30 days the server keeps released images (§7.6), so an undone version's images still exist. */
internal const val MERGE_UNDO_MS = 14L * 24 * 60 * 60 * 1000

internal suspend fun Sql.recordMerge(undo: MergeUndo) = exec(
    """
    INSERT OR REPLACE INTO merge_undo (note_id, keep_title, keep_content, copy_title, copy_content, merged_title,
        merged_content, merged_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """,
    undo.noteId,
    undo.keepTitle,
    undo.keepContent,
    undo.copyTitle,
    undo.copyContent,
    undo.mergedTitle,
    undo.mergedContent,
    undo.mergedAt
)

internal suspend fun Sql.forgetMerge(noteId: String) = exec("DELETE FROM merge_undo WHERE note_id = ?", noteId)

internal suspend fun Sql.forgetOldMerges(now: Long) = exec(
    "DELETE FROM merge_undo WHERE merged_at < ?",
    now - MERGE_UNDO_MS
)

internal suspend fun Sql.mergeUndo(noteId: String, now: Long): MergeUndo? = one(
    """
    SELECT note_id, keep_title, keep_content, copy_title, copy_content, merged_title, merged_content, merged_at
    FROM merge_undo WHERE note_id = ? AND merged_at >= ?
    """,
    noteId,
    now - MERGE_UNDO_MS
) {
    MergeUndo(
        it.getText(0),
        it.getText(1),
        it.getText(2),
        it.getText(3),
        it.getText(4),
        it.getText(5),
        it.getText(6),
        it.getLong(7)
    )
}
