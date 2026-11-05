---
title: Import notes from Markdown
description: Bring notes from Obsidian, Joplin, Bear and other apps in from a folder or zip of Markdown files.
---

# Import notes from Markdown

`notesd import` adds a folder or `.zip` of Markdown files to an account as notes. Most note apps
can export that:

| From | Export |
| --- | --- |
| Obsidian | The vault folder itself |
| Joplin | File → Export all → **MD - Markdown + Front Matter** (plain **MD** loses tags and dates) |
| Bear, Apple Notes, Notion, Logseq, Typora and others | Their Markdown export |
| notesd | [`notesd export`](../../reference/cli.md#export) |

## Run it

On the server, as for [every command](../../reference/cli.md), with the same data directory:

```bash
notesd import --from ~/vault.zip --space Imported --dry-run
notesd import --from ~/vault.zip --space Imported
```

`--dry-run` prints what would happen without changing anything. `--space` puts the notes in that
space, created if there isn't one by that name, so you can look them over apart from your other
notes; without it they go to your default space. On a server with several accounts, add
`--username NAME`.

With Docker, run it in a second container on the same volume, with the export mounted read-only.
The container runs as user 65532, so the files must be readable by anyone (`chmod -R a+rX`):

```bash
docker run --rm -v notesd-data:/data -v "$PWD/vault.zip":/import/vault.zip:ro \
  ghcr.io/cassiozen/notesd:latest import --from /import/vault.zip --space Imported
```

It's safe to run while the server is up. Phones and browsers get the new notes when they next
sync. The [Markdown mirror](markdown-mirror-and-export.md#markdown-mirror) catches up the next
time the account changes a note, or when the server restarts.

## What it reads

Files ending in `.md`, `.markdown` or `.txt` become notes; hidden folders such as `.obsidian` and
`.trash` are skipped. A zip of one folder is read as that folder's contents.

- **Title:** the front matter's `title`, else a `# Heading` on the first line (which is then
  removed from the text), else the file name.
- **Dates:** `created` (or `date`) and `updated` (or `modified`, `lastmod`) from the front matter,
  else the file's modification time.
- **Tags:** the front matter's `tags` are added at the end of the note, as are its folders: a
  note in `Work/Projects/` gets `#Work/Projects`. Tags already in the text work as they are.
  Names with spaces are written `#like this#`.
- **Line breaks:** a single newline inside a paragraph becomes a line break, as Obsidian, Joplin
  and Bear show it. To join those lines instead, as plain Markdown does, add `--no-line-breaks`.
- **Pictures:** JPEG, PNG, WebP and GIF files the notes show, by path, as `![[name]]`, as
  `<img>` or inline as `data:` URLs, up to 10 MiB each, are stored as they are, like pictures
  added in the apps. Pictures on the web stay links to their sites and are never downloaded.
  Other formats, such as SVG and HEIC, are left as written and listed.
- **Links:** notesd has no links between notes, so links to other notes, and wiki-links
  (`[[Note]]`, `[[Note|text]]`), become their text. Links to other files, such as PDFs, become
  their text too, and are listed: only notes and pictures are imported.

The rest of the front matter, such as `aliases`, is dropped. Everything else in the text, tables
and HTML included, is kept as written; see [Writing and Markdown](../using/writing-and-markdown.md)
for how the apps show it.

## Importing again

Each note's identity comes from its text, so importing the same files again skips the notes
already there and adds only new or changed ones. A changed file comes in as a new note beside the
old one. A note you deleted after importing it stays deleted.

## The report

After the totals, `import` lists every file it skipped (not UTF-8 text, longer than a note can
be, a symbolic link) and every note it imported with something left as written or out (a picture
it couldn't find or store, a link to a file, a tag it couldn't write).
