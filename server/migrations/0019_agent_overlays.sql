-- What a run shows on a note open in an editor, beside the note's text rather than in it: a card to
-- read and dismiss, or a suggested change to accept or reject. None stays open once the note's last editor closes.
CREATE TABLE agent_overlays (
    id          TEXT PRIMARY KEY,
    run_id      TEXT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    note_id     TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('card', 'suggestion')),
    place       TEXT NOT NULL,            -- JSON: {"at": "top" | "end" | "cursor"}, or {"after": a quote}
    body        TEXT NOT NULL DEFAULT '', -- a card's Markdown; a suggestion's why
    change      TEXT,                     -- JSON, a suggestion's edit
    status      TEXT NOT NULL DEFAULT 'open'
                CHECK (status IN ('open', 'dismissed', 'accepted', 'rejected', 'stale', 'closed')),
    created_at  INTEGER NOT NULL,
    answered_at INTEGER
);
CREATE INDEX idx_agent_overlays_note ON agent_overlays(note_id) WHERE status = 'open';
CREATE INDEX idx_agent_overlays_run ON agent_overlays(run_id);
