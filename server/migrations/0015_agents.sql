-- Settings for the account as a whole, such as its time zone (agents need
-- "today"). A different name from the settings table 0010 moved to system.db.
CREATE TABLE account_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;

-- A space's agent. A row exists once someone has set one up; it stays when
-- the agent is turned off, so turning it back on keeps what was written.
CREATE TABLE agent_spaces (
    space_id   TEXT PRIMARY KEY REFERENCES spaces(id) ON DELETE CASCADE,
    enabled    INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    prompt     TEXT NOT NULL DEFAULT '',
    menu_label TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
) WITHOUT ROWID;

-- One prompt per trigger the space's agent acts on; no row, no action.
CREATE TABLE agent_triggers (
    space_id TEXT NOT NULL REFERENCES agent_spaces(space_id) ON DELETE CASCADE,
    trigger  TEXT NOT NULL,
    prompt   TEXT NOT NULL,
    PRIMARY KEY (space_id, trigger)
) WITHOUT ROWID;
