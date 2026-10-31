-- Full reconciliation after a server restore (docs/android-app-plan.md §4.5 step 3) spans
-- many pages and can be interrupted. Its progress is stored, so a restart still finishes
-- it: re-creating on the server every note the full pull didn't see.

ALTER TABLE sync_state ADD COLUMN full_sync INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notes ADD COLUMN full_sync_seen INTEGER NOT NULL DEFAULT 0;
