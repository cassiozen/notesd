-- The images in a note's recent locked bodies (architecture §7.6): ones
-- this device encrypted, and ones sync brought with the server's record.
-- The server can't read a locked body, so its saves list the images; sync
-- sends bodies it can't decrypt (merges, copies), and finds their images
-- here by the body's hash. A few per note are kept, newest by rowid: the
-- body being saved, the one stored, and one a conflict may copy.
CREATE TABLE IF NOT EXISTS locked_images (
    note_id     TEXT NOT NULL,
    content_sha TEXT NOT NULL,   -- lowercase hex SHA-256 of the envelope
    image_ids   TEXT NOT NULL,   -- JSON array of image ids
    PRIMARY KEY (note_id, content_sha)
);
CREATE INDEX IF NOT EXISTS idx_locked_images_sha ON locked_images(content_sha);
