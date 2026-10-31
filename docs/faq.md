# Frequently asked questions

- [About notesd](#about-notesd)
- [Notes and Markdown](#notes-and-markdown)
- [Your data](#your-data)
- [Offline and sync](#offline-and-sync)
- [Apps](#apps)
- [Privacy and accounts](#privacy-and-accounts)
- [Running the server](#running-the-server)
- [Under the hood](#under-the-hood)

## About notesd

### What is notesd?

A notes app you run yourself. One small program (the server) keeps your
notes; a web app, an Android app and an iPhone app read and write them.
The phone apps keep a full copy of your notes and work offline, then sync
when they can reach the server.

Think Apple Notes or Bear, except the notes live on a computer you control
instead of someone else's cloud.

### Who is it for?

- People who want their notes on their own hardware: a Raspberry Pi, a home
  server, a cheap VPS.
- People who want a plain, fast notes app without a subscription or an
  account with a company.
- One server can hold a few accounts, each with its own private
  notes.

### Does notesd phone home or track me?

No. There are no analytics in the server or the apps. The server contacts
other services only for features you turn on: Resend, to email a note, and
GitHub, to check for new releases, only when
[updating from Settings](operations.md#updating-from-settings) is enabled.

## Notes and Markdown

### Are my notes Markdown?

Yes. Every note is stored as a Markdown document. You never see Markdown
syntax while writing (the editor shows formatting, like Apple Notes), but
it's what's saved, and what you get out.

### What Markdown is supported?

The editor handles bold, italic, strikethrough, three heading levels,
bulleted, numbered and check lists, quotes, links and pictures.

Code blocks show highlighted, and the web app can create and edit them.
Anything else (tables, other images, horizontal rules, HTML) is shown
read-only and kept exactly as written. Opening and saving a note never
mangles Markdown written elsewhere.

### Do `#tags` work like in Obsidian or Bear?

Mostly. `#tag` anywhere in a note tags it, and `#parent/child` nests, as in
both. `#multi word tag#` (closed with a `#`) is Bear's syntax for tags with
spaces. Tags are plain text in the note, so they survive export as they
are.

### What about wiki-links, backlinks, graphs, plugins?

Not supported. notesd is deliberately a simple notes app: no `[[links]]`,
no backlinks, no plugins.

### Can I email a note?

Yes, if the server's admin sets up [Resend](https://resend.com) in
**Settings → Integrations**. Then a note's menu has **Email…**, and the
note is sent formatted. Locked notes can't be emailed.

## Your data

### Where are my notes stored?

In SQLite database files in the server's data directory: one per account,
plus one for accounts and settings. Pictures are in the same files.

### Why a database and not plain Markdown files?

Fast search, reliable saves and safe sync. The notes inside are still Markdown,
and you can have them as files whenever you like (below).

### How do I get my notes out?

```bash
notesd export --out ~/notes-export
```

Writes every note as a Markdown file named after its title, and pictures
into `images/` with the notes pointing at them. On a server with several
accounts, add `--username NAME`. Locked notes are left out
unless you add `--include-locked` and type the lock password. It's safe to
run while the server is up.

To keep an always-current copy instead, use the
[Markdown mirror](hosting.md#markdown-mirror).

### Can I keep my notes as `.md` files, like an Obsidian vault?

Yes, two ways:

- **Mirror folder.** Start the server with `--mirror-dir` and it keeps a
  folder of Markdown files up to date as you write: a folder per account,
  a folder per space, a file per note named after its title, and pictures
  in `images/`. Point Syncthing, a backup tool or another editor at it. See
  [Markdown mirror](hosting.md#markdown-mirror).
- **One-time export.** `notesd export --out DIR` writes the files once,
  named the same way, with pictures in `images/`. With only one space it
  skips the space folder. On a server with several accounts, add
  `--username NAME`.

### Can I import my existing notes?

There's no bulk importer yet. For a few notes: paste Markdown into the web
app's editor. Headings, lists, quotes, bold and italic become formatting,
and pasting into the title line makes the first line the title.

### How do I back up?

Back up the whole data directory. For a consistent copy, stop the server
first (the [operations guide](operations.md) shows how, with and without
Docker). The phones' copies aren't a backup. Keep a copy off the machine.

## Offline and sync

### Does it work offline?

- **Android and iPhone:** fully. The phone keeps every note; writing,
  search and tags work with no connection.
- **Web:** if you choose **Keep my notes on this device** (at sign-in or in
  **Settings → Sync & data**), the browser keeps a full copy and works
  offline too. It's on by default in the
  [installed web app](installing-web-app.md). It needs HTTPS, and isn't
  offered in private windows.

### When does it sync?

Automatically: when the app opens, a few seconds after you stop typing,
when the network comes back, and every minute or two while it's open. On
phones you can also pull down on the list. The OS may let the phone apps
sync in the background now and then, on its own schedule.

### What if I edit the same note on two devices?

Nothing is lost.

- If one device changed the title and the other the text, both changes are
  kept.
- If both changed the text, one version stays as the note and the other
  becomes a new note titled "… (Conflicted copy)", linked to the original.
  The web app, when it's working directly on the server, asks you which to
  keep instead.
- If a note was deleted on one device and edited on another, the edits come
  back as "… (Recovered copy)".

## Apps

### Do the apps look and work the same?

Yes. They share a design, the same Settings layout, and the same rules for
Markdown, tags and locked notes, checked by shared tests. Small differences
are listed in each platform's guide.

### Why aren't the apps in the Play Store or App Store?

They aren't published there yet. On Android, install the APK; newer APKs
install over it and keep your notes.

### Why do I need Xcode for the iPhone app?

Without the App Store, building it yourself is the only way to install it.
With a free Apple ID, the app stops opening after 7 days until you run it
from Xcode again; your notes are kept.

## Privacy and accounts

### Can the server read my notes?

Yes, regular notes are stored unencrypted on the server, like most notes
apps. Use disk encryption on the server if that matters to you.

**Locked notes** are the exception: they're encrypted on your device, and
the server never has the key. Only `notesd export --include-locked`,
run on the server's machine with the lock password typed in, decrypts
them, to export them.

### What if I forget my lock password?

If you have no passkey, fingerprint or Face ID set up, notes locked with it
can't be opened again, by you or anyone else. You can reset the lock
password (it needs your account password) to lock new notes, but the old
ones stay unreadable.

### What if I forget my account password?

The person running the server sets a new one with
`notesd reset-password --username NAME`. There are no reset emails. That
doesn't affect locked notes, which use their own password.

### Can I have multiple users on the same server?

Yes. The server can hold different accounts, each with its own notes, spaces,
locked notes and pictures, in its own database file. No account can see
another's.

### How do I add someone?

On the server: `notesd add-user --username sam`. There's no public sign-up.
Add `--admin` to let them change server-wide settings such as email.

### How do I sign out a lost device?

In the web app, **Settings → Security** lists signed-in browsers and
devices; sign one out there. Its notes stay on the device, but it can't
sync any more.

## Running the server

### How do I monitor the server?

`/healthz` and `/readyz` answer without a login and expose no data, so they
work with Uptime Kuma, Gatus and the like. Logs go to stderr, optionally as
JSON. Search terms are never logged.

### How do I check the database is healthy?

`notesd check` runs SQLite's integrity check and verifies every picture.
`notesd reindex` rebuilds search and tags if results look wrong. notesd
never deletes or replaces a damaged database by itself.

### Saving fails with 403 behind my proxy. Why?

Check that `NOTESD_PUBLIC_URL` matches the address you open, and that your
proxy forwards the `Host` header.

## Under the hood

### How does sync work?

Each note has a revision; the server keeps a global change counter. A device
uploads an edit with the revision it started from, and downloads everything
changed since its last cursor. No CRDTs, no WebSockets. Every upload carries
an operation ID, so a retry after a lost response is never applied twice.
Details in [architecture §5–6](architecture.md#5-synchronization-protocol).

### How does the web app work offline?

The same sync engine as Android (Kotlin Multiplatform, compiled to
JavaScript) runs in a worker on SQLite compiled to WebAssembly, stored in
the browser's private file system. One worker serves every open tab. A
service worker serves the app itself.

### Is there an API?

Yes, a JSON API under `/api/v1`. See [server/README.md](../server/README.md).

### Where's the spec?

[docs/architecture.md](architecture.md): data model, sync protocol,
conflict rules, the Markdown dialect, search ranking and the locked-notes
design. Changes to any of those start there.
