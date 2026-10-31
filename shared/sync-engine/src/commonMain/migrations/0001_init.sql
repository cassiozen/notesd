-- Local notes, sync bookkeeping and the search index (docs/android-app-plan.md §4.2).

CREATE TABLE notes (
    seq               INTEGER PRIMARY KEY, -- stable rowid, keys notes_fts (as on the server)
    id                TEXT NOT NULL UNIQUE,
    title             TEXT NOT NULL DEFAULT '',
    content           TEXT NOT NULL DEFAULT '',
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    deleted_at        INTEGER,
    conflict_of       TEXT,
    server_revision   INTEGER,             -- NULL until the server has the note
    server_change_seq INTEGER,
    dirty             INTEGER NOT NULL DEFAULT 0,
    base_title        TEXT,                -- last synced version, kept while dirty
    base_content      TEXT,
    base_deleted_at   INTEGER
);
CREATE INDEX idx_notes_list ON notes(updated_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_dirty ON notes(dirty) WHERE dirty = 1;

CREATE TABLE sync_outbox (
    operation_id   TEXT PRIMARY KEY,
    note_id        TEXT NOT NULL,
    operation_type TEXT NOT NULL,          -- create | update | delete
    base_revision  INTEGER,
    payload_json   TEXT NOT NULL,
    created_at     INTEGER NOT NULL,
    sent           INTEGER NOT NULL DEFAULT 0 -- frozen once sent: a retry must resend the same payload
);
CREATE INDEX idx_outbox_note ON sync_outbox(note_id, created_at);

CREATE TABLE sync_state (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    cursor       INTEGER NOT NULL DEFAULT 0,
    generation   TEXT,                     -- NULL before the first sync
    last_sync_at INTEGER,
    last_error   TEXT
);
INSERT INTO sync_state (id) VALUES (1);

-- rowid = notes.seq. Holds the plain text, so the list reads previews from here too.
CREATE VIRTUAL TABLE notes_fts USING fts5(
    title, body_text,
    tokenize = 'unicode61 remove_diacritics 2'
);
