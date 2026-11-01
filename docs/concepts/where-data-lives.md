---
title: Where your data lives
description: The server's data directory, why notes are in SQLite rather than files, and the copies on your devices.
---

# Where your data lives

## On the server

Everything is in the data directory: `/var/lib/notesd` with the
[systemd setup](../guides/self-hosting/binary-systemd.md#keep-it-running-with-systemd), or the
`notesd-data` volume with [Docker](../guides/self-hosting/docker.md).

```text
system.db        accounts, sessions and server-wide settings (email)
users/1.db       the first account's notes, pictures, spaces and locked-notes keyring
users/2.db       the next account's, and so on: one file per account
*.db-wal         recent changes not yet folded into the file beside it (may not exist)
*.db-shm         shared memory index for the WAL (may not exist)
```

That's all the state there is: no other database, cache or search service. Pictures are stored
inside the databases too, so one copy of the directory holds notes and pictures consistently. See
[Backups](../guides/self-hosting/backups.md).

## Why SQLite and not Markdown files

Every note is a Markdown document, but notesd keeps them in SQLite, not as files. That gives fast
full-text search (SQLite's FTS5), saves that either happen completely or not at all, and reliable
sync: every change gets its place in the change sequence in the same transaction that stores it.

You can still have files whenever you want:

- [`notesd export`](../reference/cli.md#export) writes every note as a Markdown file once.
- The [Markdown mirror](../guides/self-hosting/markdown-mirror-and-export.md#markdown-mirror)
  keeps a folder of Markdown files up to date as you write, for Syncthing, another editor or a
  backup tool.

## On your devices

- **Phones** keep a full copy of your notes in their own SQLite database, with their own search
  index, so they work offline. Pictures download the first time you open their note.
- **The web app** keeps nothing by default. If you choose
  [Keep my notes on this device](../guides/using/offline-and-sync.md#the-web-app), the browser
  keeps a full copy in its private storage, in SQLite compiled to WebAssembly, with pictures in
  its cache.

A device's copy isn't a backup. If the server's data is lost, devices can help put it back (see
[restoring](../guides/self-hosting/backups.md#restore)), but only with the notes they hold.
