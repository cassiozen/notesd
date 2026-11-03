-- Titles of notes in the space sent with every run, as a JSON array.
ALTER TABLE agent_spaces ADD COLUMN reference_notes TEXT NOT NULL DEFAULT '[]';
