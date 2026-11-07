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

When **both changed the text**, it merges block by block: paragraphs, headings, list items and
quotes, again against the version both started from.

| | Started from | This device | Server | Result |
| --- | --- | --- | --- | --- |
| Paragraphs | One / Two | One / Two, edited | One, edited / Two | **One, edited / Two, edited** |
| Checklist | ☐ milk | ☑ milk | ☐ oat milk | **☑ oat milk** |

A heading's level, a checkbox and a numbered list's start merge apart from the words next to them,
so ticking an item on one device and rewording it on another both stick. notesd never merges the
words of one paragraph: two edits to the same paragraph (or two items added at the same spot, or
a block moved on one device and edited on the other) are a conflict for that block. Then the note
keeps the server's version of it, and this device's goes into a new note, "… (Conflicted copy)",
linked to the original. Both notes keep every change that did merge, so they differ only where the
edits clash. When nothing merged at all, the web app working on the server asks instead: **Keep
mine** saves your version as a separate note, and **Use theirs** drops your edit. Nothing is
dropped without you choosing it. (If both made the same change, there's nothing to resolve.)

A merged note says so, and for 14 days **Undo** splits it back into what it would otherwise have
been: one version in the note and the other as a conflicted copy. If the note was edited after the
merge, Undo keeps those edits and only makes the copy. Locked notes are never merged
this way, since sync can't read them: changed on both sides, yours becomes the copy.

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
