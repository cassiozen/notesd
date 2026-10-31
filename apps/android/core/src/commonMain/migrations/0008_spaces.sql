-- Spaces (architecture §7.7). Each note is in one; base_space_id is the
-- synced space, kept while edits are pending, for the merge.
ALTER TABLE notes ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE notes ADD COLUMN base_space_id TEXT;
-- Notes with edits pending were synced in the only space there was.
UPDATE notes SET base_space_id = 'default' WHERE base_title IS NOT NULL;

-- The server's list of spaces, with this device's changes waiting to go:
-- dirty for a create or rename, pending_delete for a delete.
CREATE TABLE IF NOT EXISTS spaces (
    id             TEXT    PRIMARY KEY,
    name           TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    dirty          INTEGER NOT NULL DEFAULT 0,
    pending_delete INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO spaces (id, name, created_at) VALUES ('default', 'Notes', 0);
