<p align="center"><img src="apps/web/public/icon-512.png" alt="" width="144" height="144"></p>

<h2 align="center">notesd</h1>
<h3 align="center">Self-hosted notes</h1>

<p align="center">Single-binary, lightweight server (you can drop onto a Raspberry Pi or a cheap VPS).</p>
<p align="center">Native mobile apps that works fully
offline and syncs your notes whenever a connection is available.</p>

<img width="1448" height="1086" alt="NotesD on browser and mobile" src="https://github.com/user-attachments/assets/9ceabc92-18c3-443d-9e3f-5a8e058d0c4f" />

## Features

- **Full-text Search** across titles and bodies.

- **Organize with tags.** Add tags like `#work` and `#reading` anywhere in your note. The sidebar lists all your tags, so you can browse by topic. Tags can be nested with `/` (`#home/garden`).

- **Spaces.** Create new spaces to keep separate contexts apart.

- **Locked notes.** You can secure notes with end-to-end encryption (AES-256-GCM). The server only stores ciphertext. Unlock with a password, a passkey (web) or biometrics (Android and iOS).  
  <img width="368" height="259" alt="Image" src="https://github.com/user-attachments/assets/607ed533-451f-42ed-90d6-e1cfe162deae" />  
  See [Locked notes](docs/guides/using/locked-notes.md) for more details.

- **Installable, offline-first web app.** Install it from your browser and it keeps your notes on the device, so it opens, edits and searches them without a connection.  
  See [Web app](docs/guides/apps/web.md) for more details.

- **Markdown under the hood.** Notes are stored as Markdown, and you can take them out at any time.  
  See [Markdown mirror and export](docs/guides/self-hosting/markdown-mirror-and-export.md) for more details.

## Get started

The [Get started guide](docs/get-started/01-run-the-server.md) takes about ten minutes:

1. [Run the server](docs/get-started/01-run-the-server.md) with Docker (`ghcr.io/cassiozen/notesd`), or [as a single binary](docs/guides/self-hosting/binary-systemd.md).
2. [Create your account](docs/get-started/02-create-your-account.md), before anyone else can.
3. [Write your first note](docs/get-started/03-write-your-first-note.md) in the web app.
4. [Add your phone](docs/get-started/04-add-your-phone.md) and watch it sync.

## Your data and security

- **Your data** is in one directory on the server: SQLite databases, one per account, holding notes
  and pictures. Notes are Markdown inside, and [`notesd export`](docs/reference/cli.md#export) or
  the [Markdown mirror](docs/guides/self-hosting/markdown-mirror-and-export.md) give you the
  unlocked ones as files (`export --include-locked` decrypts locked notes too). See [Where your data lives](docs/concepts/where-data-lives.md) and
  [Backups](docs/guides/self-hosting/backups.md).
- **Regular notes are stored unencrypted** on the server. Use disk encryption if that matters to
  you, and HTTPS for anything reachable from the internet. **Locked notes** are end-to-end
  encrypted, with limits worth knowing. See the [security model](docs/concepts/security-model.md).

## How it's built

| Part | Stack |
| --- | --- |
| Server | Go, `net/http`, SQLite with WAL and FTS5 |
| Web | React, TypeScript, Tiptap/ProseMirror, Vite; embedded in the server binary |
| Android | Kotlin, Jetpack Compose, a native `EditText` editor, bundled SQLite with FTS5, WorkManager |
| iOS | Swift, SwiftUI, a TextKit 2 `UITextView` editor, SQLite with FTS5, CryptoKit for locked notes, background refresh, Face ID for locked notes (iPhone only) |

A few things that may be interesting if you're reading the code:

- **One spec.** [docs/contributing/architecture.md](docs/contributing/architecture.md) is the
  specification: data model, sync protocol, conflict rules, Markdown dialect,
  search ranking and the locked-notes design. Changes to any of those start
  there.
- **Shared rules.** The platforms implement the Markdown round trip,
  plain-text extraction, tag parsing and the locked-notes crypto separately.
  Fixtures in [`shared/`](shared) pin all four, and every platform's tests run
  against them, so the server, the web app and the phone agree.
- **Simple sync.** Each note has a revision, and the server keeps a global
  change sequence. Clients push edits against the revision they started
  from, and pull everything changed since a cursor. There are no CRDTs and
  no WebSockets.
- **No other services.** No Postgres, Redis, queue or search engine: search
  is SQLite's FTS5, and the whole state is one database file.

## Documentation

Start at the [documentation index](docs/README.md).

| Section | For |
| --- | --- |
| [Get started](docs/get-started/01-run-the-server.md) | From `docker run` to notes syncing on your phone |
| [Using notesd](docs/README.md#using-notesd) | Writing, tags, spaces, search, locked notes, email, offline and sync, settings |
| [Apps](docs/README.md#apps) | Installing and connecting the web, Android and iPhone apps |
| [Self-hosting](docs/README.md#self-hosting) | Docker or a binary with systemd, HTTPS, accounts, backups, upgrades, monitoring |
| [Concepts](docs/README.md#concepts) | How sync works, the security model, where your data lives |
| [Reference](docs/README.md#reference) | The command line and the HTTP API |
| [FAQ](docs/faq.md) | Quick answers |
| [Contributing](docs/README.md#contributing) | Building from source, the architecture spec, releasing |
