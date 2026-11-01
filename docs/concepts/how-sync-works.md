---
title: How sync works
description: Revisions, the change feed, conflicts and deletions, in plain terms.
---

# How sync works

notesd syncs whole notes. A device never sends keystrokes or edit operations, and never keeps a
connection open: it uploads the notes it changed and downloads the ones that changed elsewhere.
There are no CRDTs and no WebSockets, which keeps sync simple to reason about and to debug.

## Revisions and the change feed

Every note has a **revision**, a number the server raises each time the note changes. The server
also numbers every change across all notes in order, the **change sequence**.

**Uploading.** A device sends an edited note along with the revision it started from. If that's
still the note's latest revision, the server stores the edit as the next one. If someone else's
edit got there first, the server refuses and sends back its latest version, and the device
[resolves the conflict](#conflicts).

**Downloading.** A device remembers how far into the change sequence it has seen, its cursor, and
asks for everything after it. It gets each changed note's latest state (not every revision in
between) and moves its cursor forward. A new device starts from zero and downloads everything.

**Retries are safe.** Every upload carries an operation ID. If the connection drops after the
server stored an edit but before the device heard back, the device sends it again, and the server
answers as before instead of applying it twice.

Devices write to their own database first, so edits are safe on the phone before they ever leave
it, and survive the app being closed. The phones queue edits and upload them in order, one note
at a time.

## Conflicts

Two devices edit the same note before syncing. The second to upload is refused, gets the server's
version, and merges field by field, comparing each with the version both started from:

| | Started from | This device | Server | Result |
| --- | --- | --- | --- | --- |
| Title | Shopping | Groceries | Shopping | **Groceries** |
| Text | Buy milk | Buy milk | Buy oat milk | **Buy oat milk** |

A field only one side changed takes that side's value. The note's space works the same way; if
both moved it, the server's move wins.

When **both changed the text differently**, notesd doesn't try to merge the words. The phones keep
the server's version as the note and save their own as a new note, "… (Conflicted copy)", linked
to the original, so both versions exist. The web app, working on the server, asks you instead:
**Keep mine** saves your version as a separate note, and **Use theirs** drops your edit. Nothing
is dropped without you choosing it. (If both made the same change, there's nothing to resolve.)

## Deletions

Deleting a note leaves a **tombstone**: the note's ID and revision stay on the server, without its
title or text. Tombstones sync like any other change and are kept forever, so a device that was
offline for months still learns what was deleted, and can't bring a deleted note back by
uploading its old copy.

| What happened | Result |
| --- | --- |
| Deleted elsewhere, unchanged here | Deleted here too |
| Deleted elsewhere, edited here while offline | The deletion stands, and the edits come back as "… (Recovered copy)" |
| Deleted here while offline, edited elsewhere | The server refuses the old deletion, and the edited note stays |

## Restoring a backup

A server restored from a backup is behind its devices: they've seen changes the backup doesn't
have. A device that notices compares every note with the server instead of syncing from its
cursor, re-uploads what the server lost, and never treats a note missing on the server as deleted.

Each database has a **sync generation** for this, but notesd doesn't change it on a restore yet.
Today a device notices only because its cursor is ahead of the server's. If new notes are written
on the restored server before a device syncs, the server can move past that cursor, and the device
can miss changes or overwrite some with its own copy. So after a restore, let every device sync
before anyone writes anything new: [Backups](../guides/self-hosting/backups.md#restore) has the
steps.

## More

- The full protocol, with cursor rules and crash safety, is in
  [architecture §5–6](../contributing/architecture.md#5-synchronization-protocol).
- The endpoints are in the [API reference](../reference/api.md#sync).
