# Running notesd

Everything notesd stores is in its data directory (`/var/lib/notesd` in
[hosting.md](hosting.md), or the `notesd-data` volume with
[Docker](#docker)):

```text
notes.db       notes, account, sessions and settings
notes.db-wal   recent changes not yet folded into notes.db (may not exist)
notes.db-shm   shared memory index for the WAL (may not exist)
```

## Maintenance commands

Run them as the service user with the same data directory. They're safe to
run while the server is up.

```bash
sudo -u notesd notesd check --data-dir /var/lib/notesd
```

| Command | What it does |
| --- | --- |
| `check` | Runs SQLite's integrity check, re-hashes every image and prints `ok`, with a warning for each image a note shows that isn't stored. Never changes the database. |
| `export --out DIR` | Writes every note to DIR (new or empty) as a Markdown file named after its title (with two or more spaces, in a folder per space), and the images they show to DIR/images, with the notes' references pointed there. Locked notes are skipped unless `--include-locked` is given: it asks you to confirm, then for the lock password, typed at the terminal every time, and writes them, and their images, decrypted. An image that isn't stored or can't be opened keeps its `notesd:image/` reference. Never changes the database. |
| `gc-images [--dry-run]` | Deletes images no note has shown for 30 days. The server already does this every 6 hours; `--dry-run` only reports what it would delete. |
| `reindex` | Rebuilds the search index, tags and image references from the notes. Try it if search results look wrong. |
| `reset-password` | Sets a new password (from `NOTESD_PASSWORD` or stdin) and signs out every session. For when you've forgotten it. |
| `create-owner --username NAME` | Creates the account, if there isn't one yet. |
| `version` | Prints the version. |

notesd never deletes or replaces a damaged database by itself. If `check`
reports problems, stop the server and restore from a backup.

## Monitoring

- `GET /healthz` answers 200 while the process runs.
- `GET /readyz` answers 200 when the database is reachable.

Neither needs a login or exposes any data, so they work with Uptime Kuma,
Gatus, or a systemd watchdog script.

## Logs

notesd logs to stderr, so systemd captures it: `journalctl -u notesd`.
Every API request is logged in one line (query strings are left out, so
search terms aren't). `--log-format json` produces JSON for log collectors,
and `--log-level debug` adds detail.

## Docker

With the [Docker setup](hosting.md#with-docker), the data directory is the
`notesd-data` volume, and the commands above run inside the container:

```bash
docker exec notesd notesd check
docker logs -f notesd
```

To export your notes to a folder on the host:

```bash
docker exec notesd notesd export --out /tmp/export
docker cp notesd:/tmp/export ./notes-export
```

To upgrade to the latest release, pull the image, then replace the container
with the same `docker run` command you started it with:

```bash
docker pull ghcr.io/cassiozen/notesd:latest
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v notesd-data:/data \
  -e NOTESD_PUBLIC_URL=https://notes.example.com \
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
