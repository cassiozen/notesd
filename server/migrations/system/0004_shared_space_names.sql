-- 0004_shared_space_names: a shared space's name and icon are in its own
-- file, in its spaces row, as every space's are (architecture §7.8): settings
-- that belong to a space live with its notes, and this database keeps only
-- what finds them (owner, members, invitations). They were copied there
-- before this runs (sharing.NamesToFiles).
ALTER TABLE shared_spaces DROP COLUMN name;
ALTER TABLE shared_spaces DROP COLUMN icon;
