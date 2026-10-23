-- 0001_init: the system database (architecture §10). Each user's notes are
-- in their own database, users/<id>.db, with the notes schema; this one
-- holds who can sign in and the settings every user shares.

-- AUTOINCREMENT so an id is never reused: a new user must never adopt the
-- users/<id>.db a removed user left behind. username_key is the username
-- lowercased (in Go, like spaces.Key), so usernames are unique ignoring case.
CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL,
    username_key  TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,          -- PHC-encoded Argon2id
    is_admin      INTEGER NOT NULL DEFAULT 0 CHECK (is_admin IN (0, 1)), -- may change server-wide settings
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- One row per signed-in browser or device. Only SHA-256 hashes of bearer
-- secrets are stored.
CREATE TABLE sessions (
    id                 TEXT PRIMARY KEY,     -- random public identifier
    user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind               TEXT    NOT NULL CHECK (kind IN ('web', 'device')),
    name               TEXT    NOT NULL DEFAULT '',
    access_hash        TEXT    NOT NULL UNIQUE,
    access_expires_at  INTEGER NOT NULL,
    refresh_hash       TEXT UNIQUE,          -- devices only
    refresh_expires_at INTEGER,
    csrf_token         TEXT,                 -- web only
    created_at         INTEGER NOT NULL,
    last_used_at       INTEGER NOT NULL
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

-- Server-wide settings, such as the Resend key and sender used to email
-- notes. Only admins change them.
CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;
