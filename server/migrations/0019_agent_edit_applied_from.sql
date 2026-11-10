-- For a change a run made in an editor and the editor hasn't saved yet: a
-- hash of the note as the editor showed it just before. A save holding the
-- change whose note was exactly that adds nothing of the user's.
ALTER TABLE agent_run_edits ADD COLUMN applied_from TEXT;
