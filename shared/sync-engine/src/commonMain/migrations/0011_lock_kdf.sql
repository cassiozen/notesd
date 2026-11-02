-- How each keyring entry's key is derived (architecture §10.1), as the server's JSON object. NULL: from
-- before 1.0, implied by the kind (pbkdf2-sha256 with iterations for password/recovery, hkdf-sha256 for passkey).
ALTER TABLE lock_keys ADD COLUMN kdf TEXT;
