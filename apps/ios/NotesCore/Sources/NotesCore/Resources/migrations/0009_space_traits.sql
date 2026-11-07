-- Space traits (architecture §7.8): the server's JSON as last downloaded.
-- Server state, so every list replaces it, even on a row with a rename waiting.
ALTER TABLE spaces ADD COLUMN traits TEXT NOT NULL DEFAULT '[]';
