-- Titles of notes in the space sent with every run, as a JSON array.
ALTER TABLE agent_spaces ADD COLUMN reference_notes TEXT NOT NULL DEFAULT '[]';

-- Claiming a run skips notes with one running: a handful of rows, not the whole log.
CREATE INDEX idx_agent_runs_running ON agent_runs(note_id) WHERE status = 'running';
