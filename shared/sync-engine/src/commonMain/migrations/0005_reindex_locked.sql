-- Locked notes (docs/architecture.md §10.1) index their title only. A device that synced locked notes before
-- its indexer knew that holds their ciphertext as body text: rebuild the index once.
INSERT OR IGNORE INTO rebuild_index (id) VALUES (1);
