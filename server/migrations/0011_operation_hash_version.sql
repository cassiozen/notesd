-- How request_hash was computed. 1: over the decoded request, which a newer
-- server can't reproduce, so those rows replay on their operation id alone.
-- 2: over the request body as received, in canonical JSON.
ALTER TABLE applied_operations ADD COLUMN hash_version INTEGER NOT NULL DEFAULT 1;
