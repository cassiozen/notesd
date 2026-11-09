-- A published space's own settings (architecture §7.10): kept with its notes,
-- so they move with them when the space is shared. Its address is in
-- system.db (published_spaces).
CREATE TABLE space_publish (
    space_id   TEXT    PRIMARY KEY REFERENCES spaces(id) ON DELETE CASCADE,
    css        TEXT    NOT NULL DEFAULT '',  -- the owner's, after the site's own
    updated_at INTEGER NOT NULL
) WITHOUT ROWID;
