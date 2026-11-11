---
title: Command line
description: Every notesd command, flag and environment variable.
---

# Command line

```text
notesd [command] [flags]
```

Without a command, notesd runs `serve`. Every command but `version` takes `--data-dir`, and every
command but `serve` and `version` works on the data directory directly, so run it as the same user as the
server, with the same data directory. They're all safe to run while the server is up.

With Docker, the image already sets the data directory:

```bash
docker exec -it notesd notesd check
```

### With systemd

The [systemd setup](../guides/self-hosting/binary-systemd.md#keep-it-running-with-systemd) runs
the server as you, on `/var/lib/notesd`. With `NOTESD_DATA_DIR` set in your shell, as that page
does, run commands as yourself, without `sudo`:

```bash
notesd check
```

Without the variable, a command uses `./data` in the current folder: an empty server, not yours.

| Command | What it does |
| --- | --- |
| [`serve`](#serve) | Runs the server (the default) |
| [`create-owner`](#create-owner) | Creates the first account, an admin |
| [`add-user`](#add-user) | Creates another account |
| [`list-users`](#list-users) | Lists the accounts |
| [`remove-user`](#remove-user) | Deletes an account and signs it out |
| [`reset-password`](#reset-password) | Sets a new password for an account |
| [`export`](#export) | Writes an account's notes to a folder as Markdown |
| [`import`](#import) | Adds a folder or zip of Markdown files as notes |
| [`check`](#check) | Checks the databases and images for damage |
| [`reindex`](#reindex) | Rebuilds search, tags and image references |
| [`gc-images`](#gc-images) | Deletes images no note has shown for 30 days |
| [`version`](#version) | Prints the version |

## Global flags

| Flag | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` (Docker: `/data`) | The data directory: `system.db`, one database per account in `users/` and one per shared space in `spaces/` |

## serve

```text
notesd [serve] [flags]
```

Runs the server: the API, sync, search and the web app. Every flag can also be set with its
environment variable; a flag wins over the variable. With Docker, use the variables (`-e NAME=value`),
and use `-p` and `-v` rather than changing `PORT` or `NOTESD_DATA_DIR`.

| Flag | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `--listen` | `PORT` | `127.0.0.1:8080` (Docker: `:8080`) | Address to listen on. The default accepts connections from this machine only ([details](../guides/self-hosting/https.md#limiting-who-can-reach-notesd)). With `PORT` set, as hosting platforms do, notesd listens on every interface at that port |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` | `text` or `json` |
| `--mirror-dir` | `NOTESD_MIRROR_DIR` | Unset | Also keep every account's unlocked notes in this folder as Markdown files ([details](../guides/self-hosting/markdown-mirror-and-export.md#markdown-mirror)) |
| `--client-ip-header` | `NOTESD_CLIENT_IP_HEADER` | Unset: `X-Forwarded-For` | The header your proxy sets to the visitor's address, for proxies that put something else last in `X-Forwarded-For`, such as `X-Real-IP`, `CF-Connecting-IP` or `Fly-Client-IP`. Use one the proxy always replaces, never passes on from the visitor ([details](../guides/self-hosting/https.md#good-to-know)) |
| `--self-update` | `NOTESD_SELF_UPDATE` | `false` | `true` lets admins update from Settings. systemd installs only, and it also needs the `notesd-update` units ([setup](../guides/self-hosting/upgrading.md#updating-from-settings)) |
| `--resend-url` | `NOTESD_RESEND_URL` | `https://api.resend.com` | Where to send email. Only for tests, which point it at a fake |

These are read only from the environment:

| Variable | Description |
| --- | --- |
| `NOTESD_OWNER_USERNAME`, `NOTESD_OWNER_PASSWORD` | Create the first account when the server starts, if there isn't one yet ([details](../guides/self-hosting/users.md#create-your-account)). Environment only, so the password stays out of the process list |
| `GOMEMLIMIT` | Go's soft memory limit. On machines with little memory, `GOMEMLIMIT=64MiB` makes notesd use less |

## Account commands

The commands that take a password ask for it at the terminal, hidden, and twice so a typo can't
slip through. With Docker, keep `-it` in `docker exec -it` so there is a terminal to type at. For
scripts, they read `NOTESD_PASSWORD`, or else the first line of standard input:

```bash
printf '%s\n' "$PASSWORD" | notesd add-user --username sam
```

### create-owner

```text
notesd create-owner --username NAME
```

Creates the first account, an admin, if there isn't one yet. Its password comes from
`NOTESD_PASSWORD` or standard input, like the other account commands. The usual ways are to
[sign up in the browser](../guides/self-hosting/users.md#create-your-account), or to set
`NOTESD_OWNER_USERNAME` and `NOTESD_OWNER_PASSWORD` for `serve`; this command doesn't read those.

### add-user

```text
notesd add-user --username NAME [--admin]
```

Creates another account. It has its own notes, which no other account can see. `--admin` also
lets it change server-wide settings, such as email, and manage accounts. Admins can also
[invite people from the apps](../guides/self-hosting/users.md#from-the-apps).

### list-users

```text
notesd list-users
```

Lists the accounts, which are admins, when each was created, and which are invited from the web
app but haven't chosen a password yet.

### remove-user

```text
notesd remove-user --username NAME
```

Deletes an account and signs it out everywhere. Its notes stay in `users/<id>.db`, whose path it
prints, until you delete the file. `export` finds accounts by name, so run it before `remove-user`
to keep a copy. The last admin can't be removed. Each shared space the account owned goes to the
member who joined it first; one no one else is in keeps its file in `spaces/`.

### reset-password

```text
notesd reset-password [--username NAME]
```

Sets a new password and signs the account out everywhere. notesd can't email a reset link: an
admin can send one from People in the web or Android app, or recover a forgotten password with this. On an
invited account, it sets the password the invitation was waiting for. `--username` can be left out while there's only
one account. Locked notes use their own password, which this doesn't change.

## Data commands

`check`, `reindex` and `gc-images` act on every account and shared space unless you pass `--username`. `export`
and `import` need `--username` once there's more than one account. Only `gc-images` deletes
anything.

### export

```text
notesd export [--username NAME] --out DIR [--include-locked]
```

Writes every note to `DIR`, which must be new or empty, as a Markdown file named after its title.
With two or more spaces, each space gets a folder. The shared spaces the account is in are
written too, each in its own folder. The images notes show go in `DIR/images`, with
the notes' references pointed there; an image that isn't stored or can't be opened keeps its
`notesd:image/` reference.

Locked notes are skipped unless you pass `--include-locked`. Then it asks you to confirm, asks for
the lock password (typed at the terminal every time, never read from a variable), and writes them
and their images decrypted.

See [Markdown mirror and export](../guides/self-hosting/markdown-mirror-and-export.md) for keeping
an always-current copy instead.

### import

```text
notesd import [--username NAME] --from PATH [--space NAME] [--dry-run] [--no-line-breaks]
```

Adds every Markdown file (`.md`, `.markdown`, `.mdown`, `.mkd`, `.txt`) in `PATH`, a folder or `.zip`, as a note,
with the pictures the notes show. Front matter gives titles, dates and tags, and each note is
tagged with its folder. Running it again skips notes already imported.

| Flag | Description |
| --- | --- |
| `--from` | The folder or `.zip` to import |
| `--space` | The space to put the notes in, created if there's none by that name, ignoring case. Default: the default space |
| `--dry-run` | Only report what would be imported |
| `--no-line-breaks` | Join single newlines inside paragraphs, as Markdown does, instead of making them line breaks |

It prints the totals, then every file it skipped and every note imported with something left out,
with the reason. Links to other notes and web images are only counted. See [Import notes from Markdown](../guides/self-hosting/import-markdown.md) for
what it reads and how.

### check

```text
notesd check [--username NAME]
```

Runs SQLite's integrity check on the system database, each account's database and each shared space's, re-hashes every
image, and prints `ok`, with a warning for each image a note shows that isn't stored. The checks
never change anything. Like every command, though, it first applies any upgrade a newer notesd
needs to the data directory, as `serve` would. notesd never deletes or replaces a damaged database by itself: if `check`
reports problems, stop the server and [restore from a backup](../guides/self-hosting/backups.md).

### reindex

```text
notesd reindex [--username NAME]
```

Rebuilds the search index, tags and image references from the notes. Try it if search results or
tags look wrong. The server also runs it by itself when an upgrade needs it.

### gc-images

```text
notesd gc-images [--username NAME] [--dry-run]
```

Deletes images no note has shown for 30 days. The server already does this every 6 hours.
`--dry-run` only reports what it would delete.

## version

```text
notesd version
```

Prints the version, such as `notesd v0.10.0`.
