-- How each keyring entry's key is derived (§10.1), as JSON: {"name":"pbkdf2-sha256","iterations":N}
-- or {"name":"hkdf-sha256"}. NULL for entries synced before 1.0: implied by kind.
ALTER TABLE lock_keys ADD COLUMN kdf TEXT;
