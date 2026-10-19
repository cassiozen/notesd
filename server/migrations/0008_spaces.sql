-- Spaces (architecture §7.7): separate contexts, such as Work and Personal,
-- that each hold their own notes. Every install has the default space, which
-- can be renamed but never deleted. name_key is the name lowercased (in Go,
-- for every script, which SQLite's lower() isn't), so names are unique
-- ignoring case.
CREATE TABLE spaces (
    id         TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    name_key   TEXT    NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
) WITHOUT ROWID;
INSERT INTO spaces (id, name, name_key, created_at)
VALUES ('default', 'Notes', 'notes', CAST(strftime('%s', 'now') AS INTEGER) * 1000);

-- Each note is in one space; tombstones keep theirs.
ALTER TABLE notes ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX idx_notes_space_updated ON notes(space_id, updated_at DESC, id DESC) WHERE deleted_at IS NULL;
