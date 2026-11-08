-- Shared spaces (architecture §7.9). updated_by: the account that last changed
-- a note, so members see who did; NULL for changes from before it was kept.
-- moved_to: set on the tombstone a move to another file leaves (the space the
-- note went to), so a device that follows it there doesn't take it for a
-- deletion.
ALTER TABLE notes ADD COLUMN updated_by INTEGER;
ALTER TABLE notes ADD COLUMN moved_to TEXT;
