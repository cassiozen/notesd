-- 0003_shared_spaces (architecture §7.9): a shared space's notes are in
-- spaces/<id>.db; its name, icon, owner, members and invitations are here.
CREATE TABLE shared_spaces (
    id         TEXT    PRIMARY KEY,           -- the space's id from before it was shared
    name       TEXT    NOT NULL,
    icon       TEXT    NOT NULL DEFAULT '',
    owner_id   INTEGER NOT NULL REFERENCES users(id),
    created_at INTEGER NOT NULL
);

-- The owner is a member too.
CREATE TABLE space_members (
    space_id  TEXT    NOT NULL REFERENCES shared_spaces(id) ON DELETE CASCADE,
    user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX idx_space_members_user ON space_members(user_id);

CREATE TABLE space_invites (
    id         TEXT    PRIMARY KEY,
    space_id   TEXT    NOT NULL REFERENCES shared_spaces(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    UNIQUE (space_id, user_id)
);
-- Read for each account every sync cycle.
CREATE INDEX idx_space_invites_user ON space_invites(user_id);

-- A move of notes between files, from before the destination is written
-- until the source's tombstones commit; startup finishes or undoes any left.
-- source and dest name files: "user:<id>" or "space:<id>".
CREATE TABLE space_moves (
    id         TEXT    PRIMARY KEY,
    kind       TEXT    NOT NULL CHECK (kind IN ('space', 'note')),
    space_id   TEXT    NOT NULL,              -- the space shared, or the note's destination
    note_id    TEXT,                          -- a note move's note
    source     TEXT    NOT NULL,
    dest       TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    -- Recover tells the two apart by it.
    CHECK ((kind = 'note') = (note_id IS NOT NULL))
);
