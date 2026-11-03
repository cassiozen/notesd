-- Set when the note was locked or deleted after a run changed it: the
-- run's copies of its text are cleared then, as the note's own are.
ALTER TABLE agent_run_edits ADD COLUMN text_removed INTEGER NOT NULL DEFAULT 0;
