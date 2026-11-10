-- What a run did, for the activity log, and when a retry may start.
ALTER TABLE agent_runs ADD COLUMN not_before INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN model TEXT NOT NULL DEFAULT '';
ALTER TABLE agent_runs ADD COLUMN tool_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN outcome TEXT NOT NULL DEFAULT '';  -- edited, created, no_change
ALTER TABLE agent_runs ADD COLUMN message TEXT NOT NULL DEFAULT '';  -- the agent's last word, or what went wrong
CREATE INDEX idx_agent_runs_finished ON agent_runs(finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX idx_agent_runs_space ON agent_runs(space_id, created_at DESC);

-- Each note a run changed: as it was before the run's first change to it,
-- and after its last. The activity log's diff, and what undo puts back.
CREATE TABLE agent_run_edits (
    run_id          TEXT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    note_id         TEXT NOT NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('edit', 'create')),
    before_revision INTEGER,                -- NULL for a note the run created
    before_title    TEXT NOT NULL DEFAULT '',
    before_content  TEXT NOT NULL DEFAULT '',
    after_revision  INTEGER,
    after_title     TEXT NOT NULL,
    after_content   TEXT NOT NULL,
    written_at      INTEGER NOT NULL,
    undone_at       INTEGER,
    PRIMARY KEY (run_id, note_id)
) WITHOUT ROWID;
CREATE INDEX idx_agent_run_edits_note ON agent_run_edits(note_id, written_at DESC);

-- Tokens used per day, in the account's time zone, against the daily cap.
CREATE TABLE agent_usage (
    day               TEXT PRIMARY KEY,     -- YYYY-MM-DD
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    runs              INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
