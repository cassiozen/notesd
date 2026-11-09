-- Who sent an invitation, as people are shown everywhere: the name and photo (its version) they chose,
-- '' for none. Filled in by the next sync, which replaces every invitation.
ALTER TABLE invites ADD COLUMN from_display_name TEXT NOT NULL DEFAULT '';
ALTER TABLE invites ADD COLUMN from_avatar TEXT NOT NULL DEFAULT '';
