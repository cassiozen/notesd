---
title: Markdown mirror and export
description: Get your notes out as Markdown files, once or kept up to date.
---

# Markdown mirror and export

## Markdown mirror

To keep plain Markdown files of your notes, for another editor, a sync
tool or your own backups, start the server with `--mirror-dir DIR` (or
`NOTESD_MIRROR_DIR`):

```bash
notesd serve --data-dir /var/lib/notesd --mirror-dir /srv/notes-markdown
```

Each account gets a folder named after its username (made safe for file
names, and numbered, such as `sam (2)`, if two usernames would make the same
name). Inside it there's always a folder per space, even with only one (unlike
[`export`](../../reference/cli.md#commands), which stays flat then), with a
file per note named after its title, as `export` names them, and the images
notes show in `images/`.
The mirror then follows the notes:

- New notes get new files, edited notes overwrite their files, renamed or
  moved notes move their files, and deleted notes delete their files.
- Writing the files is low priority and never slows down saving. notesd
  writes an account's changes once its notes have been quiet for about 10
  seconds, and at least every 2 minutes while someone keeps typing.
- It only goes one way. Edits made to the files are overwritten the next
  time the note changes, and new files are never imported.
- Locked notes aren't written. Locking a note deletes its file from the
  mirror folder, so its plain text doesn't stay there; copies that backups
  or sync tools made are up to them.
- notesd only replaces or deletes files it wrote. It keeps track of them in a
  hidden `.notesd-mirror.json` in each account's folder. Renaming a space
  moves only its notes' files, leaving your own files in the old folder. If one of your own files
  already has a note's name, the note gets a numbered name, such as
  `Groceries (2).md`.
- After a restart, notesd picks up where it stopped. After a restore from a
  backup, it rewrites the account's folder to match.

The server refuses to start if an account's folder has files (other than
hidden ones) but no `.notesd-mirror.json`, if that file is damaged, if the
folder is a symbolic link or another entry has its name in another case
(`Sam` beside `sam`), or if the mirror folder is or contains the data
directory. The mirror never writes through a symbolic link inside an
account's folder either.

With Docker, mount a host folder for the mirror and pass its path in the
container:

```bash
mkdir -p notes-markdown && sudo chown 65532:65532 notes-markdown
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  -v "$PWD/notes-markdown:/markdown" \
  -e NOTESD_MIRROR_DIR=/markdown \
  ghcr.io/cassiozen/notesd:latest
```

notesd creates the files readable only by the user it runs as.
