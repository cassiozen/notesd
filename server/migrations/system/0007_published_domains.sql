-- 0007_published_domains: a published space's own domain (architecture §7.10),
-- '' for none. The owner's reverse proxy maps it onto /@<handle>/; the server
-- only builds the site's absolute links (feed, sitemap, canonical) from it.
ALTER TABLE published_spaces ADD COLUMN domain TEXT NOT NULL DEFAULT '';
