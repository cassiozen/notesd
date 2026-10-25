-- Images added on this device and not yet uploaded (architecture §7.6). The
-- file is in the app's files directory, named by id; sync uploads it before
-- any note operation, then moves it to the cache.
CREATE TABLE IF NOT EXISTS pending_images (
    id           TEXT PRIMARY KEY,   -- lowercase hex SHA-256 of the file
    content_type TEXT NOT NULL,      -- image/*, or application/vnd.notesd.locked
    created_at   INTEGER NOT NULL
);
