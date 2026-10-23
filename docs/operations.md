# Running notesd

Everything notesd stores is in its data directory (`/var/lib/notesd` in
[hosting.md](hosting.md), or the `notesd-data` volume with
[Docker](#docker)):

```text
system.db      accounts, sessions and server-wide settings (email)
users/1.db       the first account's notes, images, spaces and locked-notes keyring
users/2.db       the next account's, and so on: one file per account
*.db-wal         recent changes not yet folded into the file beside it (may not exist)
*.db-shm         shared memory index for the WAL (may not exist)
```

Back up the whole directory. A data directory from before there were
several accounts (a single `notes.db`) is moved to this layout the first
time a newer notesd starts: the owner becomes the first account, an admin,
and stays signed in everywhere. Stop the old server first.

## Maintenance commands

Run them as the service user with the same data directory. They're safe to
run while the server is up. Commands with `--username` act on every account
when it's left out; `reset-password` and `export` need it once there's more
than one.

```bash
sudo -u notesd notesd check --data-dir /var/lib/notesd
```

| Command | What it does |
| --- | --- |
| `check [--username NAME]` | Runs SQLite's integrity check, re-hashes every image and prints `ok`, with a warning for each image a note shows that isn't stored. Never changes the database. |
| `export [--username NAME] --out DIR` | Writes every note to DIR (new or empty) as a Markdown file named after its title (with two or more spaces, in a folder per space), and the images they show to DIR/images, with the notes' references pointed there. Locked notes are skipped unless `--include-locked` is given: it asks you to confirm, then for the lock password, typed at the terminal every time, and writes them, and their images, decrypted. An image that isn't stored or can't be opened keeps its `notesd:image/` reference. Never changes the database. |
| `gc-images [--username NAME] [--dry-run]` | Deletes images no note has shown for 30 days. The server already does this every 6 hours; `--dry-run` only reports what it would delete. |
| `reindex [--username NAME]` | Rebuilds the search index, tags and image references from the notes. Try it if search results look wrong. |
| `reset-password [--username NAME]` | Sets a new password (from `NOTESD_PASSWORD` or stdin) and signs out that account's sessions. notesd can't email a reset link, so this is how a forgotten password is recovered. |
| `create-owner --username NAME` | Creates the first account, an admin, if there isn't one yet. |
| `add-user --username NAME [--admin]` | Creates another account (password from `NOTESD_PASSWORD` or stdin). It has its own notes, which no other account can see. Admins can also change server-wide settings, such as email. |
| `list-users` | Lists the accounts, and which are admins. |
| `remove-user --username NAME` | Deletes an account and signs it out. Its notes stay in `users/<id>.db`, whose path it prints, until you delete the file. `export` finds accounts by name, so run it before `remove-user` to keep a copy. The last admin can't be removed. |
| `version` | Prints the version. |

notesd never deletes or replaces a damaged database by itself. If `check`
reports problems, stop the server and restore from a backup.

## Monitoring

- `GET /healthz` answers 200 while the process runs.
- `GET /readyz` answers 200 when the system database is reachable.

Neither needs a login or exposes any data, so they work with Uptime Kuma,
Gatus, or a systemd watchdog script.

## Logs

notesd logs to stderr, so systemd captures it: `journalctl -u notesd`.
Every API request is logged in one line (query strings are left out, so
search terms aren't). `--log-format json` produces JSON for log collectors,
and `--log-level debug` adds detail.

## Docker

With the [Docker setup](hosting.md#running-with-docker), the data directory is the
`notesd-data` volume, and the commands above run inside the container:

```bash
docker exec notesd notesd check
docker logs -f notesd
```

To export an account's notes to a folder on the host (`--username` can be
left out while there's only one account):

```bash
docker exec notesd notesd export --username sam --out /tmp/export
docker cp notesd:/tmp/export ./notes-export
```

To upgrade to the latest release, pull the image, then replace the container
with the same flags you started it with:

```bash
docker pull ghcr.io/cassiozen/notesd:latest
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

To stay on one release line, use its tag instead of `latest`, e.g.
`ghcr.io/cassiozen/notesd:0.2`.

To back up, stop the server and copy the volume to a file:

```bash
docker stop notesd
docker run --rm -v notesd-data:/data -v "$PWD":/backup busybox tar czf /backup/notesd-backup.tgz -C /data .
docker start notesd
```
