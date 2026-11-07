-- The last automatic body merge per note (architecture §6.2), so Undo can split it back into the
-- note as it was (keep_*) and a conflicted copy (copy_*). Kept 14 days; never for locked notes.
CREATE TABLE merge_undo (
    note_id TEXT PRIMARY KEY NOT NULL,
    keep_title TEXT NOT NULL,
    keep_content TEXT NOT NULL,
    copy_title TEXT NOT NULL,
    copy_content TEXT NOT NULL,
    merged_title TEXT NOT NULL,
    merged_content TEXT NOT NULL,
    merged_at INTEGER NOT NULL
);
