-- 0006_published_spaces: what leads the public to a published space
-- (architecture §7.10). The space's own publishing settings (custom CSS) are
-- in the file holding its notes (space_publish); this is only the address.
-- file names that file, user:<id> or space:<id>. Turning publishing off keeps
-- the row, so the handle stays the space's.
CREATE TABLE published_spaces (
    file       TEXT    NOT NULL,
    space_id   TEXT    NOT NULL,
    handle     TEXT    NOT NULL UNIQUE,  -- [a-z0-9-], the space's address: /@<handle>/
    origin     TEXT    NOT NULL,         -- scheme://host the owner published from, for absolute URLs
    published  INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (file, space_id)
) WITHOUT ROWID;
