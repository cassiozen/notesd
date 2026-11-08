-- Agents' runs: the queue (queued, running) and, once finished, the
-- activity log. One table, so a run keeps its id from trigger to undo.
CREATE TABLE agent_runs (
    id          TEXT PRIMARY KEY,           -- run_<time><random>
    status      TEXT NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed', 'skipped')),
    trigger     TEXT NOT NULL,
    space_id    TEXT NOT NULL,              -- the space whose agent runs
    note_id     TEXT NOT NULL,
    priority    INTEGER NOT NULL DEFAULT 0, -- 1 for a trigger from an open editor, which goes first
    event_json  TEXT NOT NULL,              -- what the agent is told happened
    created_at  INTEGER NOT NULL,
    started_at  INTEGER,
    finished_at INTEGER,
    failure     TEXT NOT NULL DEFAULT ''    -- why it failed or was skipped
);
CREATE INDEX idx_agent_runs_queue ON agent_runs(priority DESC, created_at) WHERE status = 'queued';
CREATE INDEX idx_agent_runs_note ON agent_runs(note_id, created_at DESC);

-- Writing sessions in progress: someone is changing a note, and its "done"
-- trigger fires when they stop. Kept with the note's write, so a restart
-- doesn't lose one.
CREATE TABLE agent_sessions (
    note_id           TEXT PRIMARY KEY,
    kind              TEXT NOT NULL CHECK (kind IN ('new', 'edit')),
    start_revision    INTEGER NOT NULL,     -- before the first change: 0 for a new note
    last_revision     INTEGER NOT NULL,
    last_user_save_at INTEGER NOT NULL      -- server clock
) WITHOUT ROWID;
