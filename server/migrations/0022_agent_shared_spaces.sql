-- Agents in shared spaces (architecture §7.11): a writing session is for the
-- member who last wrote in it, and the daily token cap is per space.
ALTER TABLE agent_sessions ADD COLUMN by_user INTEGER NOT NULL DEFAULT 0;

DROP TABLE agent_usage;
CREATE TABLE agent_usage (
    space_id          TEXT NOT NULL,
    day               TEXT NOT NULL,     -- YYYY-MM-DD, in the time zone of the member each run was for
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    runs              INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (space_id, day)
) WITHOUT ROWID;
