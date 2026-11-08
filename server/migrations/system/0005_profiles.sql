-- 0005_profiles: a name and photo each account may choose (architecture §10).
-- Both optional: without them, the apps show the username and its initial.
ALTER TABLE users ADD COLUMN display_name TEXT NOT NULL DEFAULT '';
-- The photo's version, '' without one: in its URL, so a new photo is never
-- served from a cache that holds the old.
ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT '';

CREATE TABLE avatars (
    user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    data       BLOB    NOT NULL,  -- JPEG, as the server re-encoded it
    updated_at INTEGER NOT NULL
);
