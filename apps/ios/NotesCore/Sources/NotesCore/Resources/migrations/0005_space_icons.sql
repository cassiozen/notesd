-- Each space shows an icon (architecture §7.7): a key into the app's set,
-- '' for the default one.
ALTER TABLE spaces ADD COLUMN icon TEXT NOT NULL DEFAULT '';
