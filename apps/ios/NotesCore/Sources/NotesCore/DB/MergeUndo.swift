import Foundation

/// Undo for an automatic body merge (§6.2): the note goes back to `keepTitle`/`keepContent` and
/// `copyTitle`/`copyContent` becomes a conflicted copy, what merging less would have done.
public struct MergeUndo: Hashable, Sendable {
    public var noteId: String
    public var keepTitle: String
    public var keepContent: String
    public var copyTitle: String
    public var copyContent: String
    public var mergedTitle: String
    public var mergedContent: String
    public var mergedAt: Int64
}

/// Well under the 30 days the server keeps released images (§7.6), so an undone version's images still exist.
let mergeUndoMs: Int64 = 14 * 24 * 60 * 60 * 1000

extension Sql {
    func recordMerge(_ undo: MergeUndo) throws {
        try exec(
            """
            INSERT OR REPLACE INTO merge_undo (note_id, keep_title, keep_content, copy_title, copy_content,
                merged_title, merged_content, merged_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            undo.noteId, undo.keepTitle, undo.keepContent, undo.copyTitle, undo.copyContent, undo.mergedTitle,
            undo.mergedContent, undo.mergedAt)
    }

    func forgetMerge(_ noteId: String) throws {
        try exec("DELETE FROM merge_undo WHERE note_id = ?", noteId)
    }

    func forgetOldMerges(now: Int64) throws {
        try exec("DELETE FROM merge_undo WHERE merged_at < ?", now - mergeUndoMs)
    }

    func mergeUndo(_ noteId: String, now: Int64) throws -> MergeUndo? {
        try one(
            """
            SELECT note_id, keep_title, keep_content, copy_title, copy_content, merged_title, merged_content, merged_at
            FROM merge_undo WHERE note_id = ? AND merged_at >= ?
            """,
            noteId, now - mergeUndoMs
        ) {
            MergeUndo(
                noteId: $0.text(0), keepTitle: $0.text(1), keepContent: $0.text(2), copyTitle: $0.text(3),
                copyContent: $0.text(4), mergedTitle: $0.text(5), mergedContent: $0.text(6), mergedAt: $0.int64(7))
        }
    }
}
