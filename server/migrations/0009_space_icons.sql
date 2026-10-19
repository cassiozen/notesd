-- Each space shows an icon (architecture §7.7): a key from the apps' built-in
-- set. The server stores it as given; '' means the apps' default icon.
ALTER TABLE spaces ADD COLUMN icon TEXT NOT NULL DEFAULT '';
