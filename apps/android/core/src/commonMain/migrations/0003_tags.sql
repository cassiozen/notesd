-- Tags found in live notes' text (docs/architecture.md §7.5), kept with the search index.
-- Only tags as written; parents of nested tags are implied.
CREATE TABLE note_tags (
    note_seq INTEGER NOT NULL REFERENCES notes(seq),
    key      TEXT NOT NULL, -- lowercased name
    name     TEXT NOT NULL, -- as first written in the note
    PRIMARY KEY (note_seq, key)
) WITHOUT ROWID;
CREATE INDEX idx_note_tags_key ON note_tags(key, note_seq);

-- Present while derived data (search index, tags) must be rebuilt from notes; the
-- database rebuilds when it opens and deletes the row.
CREATE TABLE rebuild_index (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO rebuild_index (id) VALUES (1);
