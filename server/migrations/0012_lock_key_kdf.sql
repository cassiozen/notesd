-- How each keyring entry's wrapping key is derived, as JSON: named, so a
-- later function can be added without old apps mistaking it for PBKDF2.
-- iterations stays for apps from before this column.
ALTER TABLE lock_keys ADD COLUMN kdf TEXT NOT NULL DEFAULT '';
UPDATE lock_keys SET kdf = CASE kind
    WHEN 'passkey' THEN json_object('name', 'hkdf-sha256')
    ELSE json_object('name', 'pbkdf2-sha256', 'iterations', iterations)
END;
