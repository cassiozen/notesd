-- Shared spaces (architecture §7.9). Each one is a feed of its own, with its
-- cursor, generation and full reconciliation here; the account's own feed
-- stays in sync_state.
CREATE TABLE feeds (
    space_id   TEXT PRIMARY KEY,
    cursor     INTEGER NOT NULL DEFAULT 0,
    generation TEXT,
    full_sync  INTEGER NOT NULL DEFAULT 0
);

-- The server's GET /invites as last downloaded.
CREATE TABLE invites (
    id            TEXT PRIMARY KEY,
    space_id      TEXT NOT NULL,
    space_name    TEXT NOT NULL,
    space_icon    TEXT NOT NULL DEFAULT '',
    from_id       INTEGER NOT NULL,
    from_username TEXT NOT NULL,
    created_at    INTEGER NOT NULL
);

-- shared: the server's `shared` JSON, NULL for the account's own spaces; a
-- shared space is a feed. visited_at: when this device last showed it, for
-- what others changed since.
ALTER TABLE spaces ADD COLUMN shared TEXT;
ALTER TABLE spaces ADD COLUMN visited_at INTEGER;

-- The account that last changed a note: the server's updatedBy, or this
-- account for an edit made here.
ALTER TABLE notes ADD COLUMN updated_by INTEGER;


-- The signed-in account's id, to tell its edits from other members'.
ALTER TABLE sync_state ADD COLUMN user_id INTEGER;
