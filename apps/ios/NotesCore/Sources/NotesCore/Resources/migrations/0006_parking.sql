-- Queued changes the server keeps answering in a way notesd never would (a proxy's page,
-- a redirect): after 3 such answers one is parked, skipped by sync so the ones behind it
-- still go, and kept until the user retries or a later sync gets through.
ALTER TABLE sync_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_outbox ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE spaces ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE spaces ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_images ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pending_images ADD COLUMN parked INTEGER NOT NULL DEFAULT 0;
