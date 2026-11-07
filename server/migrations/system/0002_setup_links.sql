-- 0002_setup_links: one-time links to choose a password, for an invited
-- account (password_hash '' until used) or a forgotten password.
CREATE TABLE setup_links (
    user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT    NOT NULL UNIQUE,  -- SHA-256, as sessions do
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
