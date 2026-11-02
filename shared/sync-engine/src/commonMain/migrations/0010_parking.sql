-- A queued change the server keeps answering with something notesd doesn't send (a portal page, a redirect)
-- is set aside after a few cycles, so the changes behind it still go. Parked changes stay queued, and are
-- tried again on the next start, after a cycle that sends something, or when asked.
ALTER TABLE sync_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_outbox ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE spaces ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE spaces ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_images ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_images ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
