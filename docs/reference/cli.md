---
title: Command line
description: Every notesd command, flag and environment variable.
---

# Command line

## Configuration

Set options with flags or environment variables. With Docker, use environment variables.

| Flag | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` (Docker: `/data`) | Database directory |
| `--listen` | `NOTESD_LISTEN` | `127.0.0.1:8080` (Docker: `0.0.0.0:8080`) | Listen address |
| `--public-url` | `NOTESD_PUBLIC_URL` | Unset | External URL; HTTPS enables `Secure` cookies |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` | `text` or `json` |
| `--mirror-dir` | `NOTESD_MIRROR_DIR` | Unset | Also keep every account's unlocked notes in this folder as Markdown files ([details](../guides/self-hosting/markdown-mirror-and-export.md#markdown-mirror)) |
| `--self-update` | `NOTESD_SELF_UPDATE` | `false` | `true` lets admins update from Settings. systemd installs only, and it also needs the `notesd-update` units ([setup](../guides/self-hosting/upgrading.md#updating-from-settings)); not for Docker |
| | `NOTESD_OWNER_USERNAME`, `NOTESD_OWNER_PASSWORD` | Unset | Create the account at startup ([details](../guides/self-hosting/users.md#create-your-account)) |

On machines with little memory, `GOMEMLIMIT=64MiB` makes notesd use less memory.

## Commands

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
