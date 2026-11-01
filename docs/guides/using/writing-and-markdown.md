---
title: Writing and Markdown
description: Titles, formatting, pictures and deleting notes, and the Markdown notesd stores them as.
---

# Writing and Markdown

## Writing a note

The first line of a note is its title. Everything after it is the body. Notes save as you type;
there's no Save button.

To start a note, use **New note** at the top of the list. In the web app, pasting Markdown into a
note turns headings, lists, quotes, bold and italic into formatting, and pasting into the title
line makes the pasted text's first line the title. That's also the way to bring in notes from
another app: there's no bulk importer yet.

## Formatting

Every app has bold, italic, strikethrough, three heading levels, bulleted, numbered and check
lists, quotes, links and pictures.

| App | Where |
| --- | --- |
| Web | The formatting bar: bold, italic, strikethrough, check list, indent, link, picture. **Text styles** (**Aa**) has headings, quotes and lists |
| Android | The bar above the keyboard |
| iPhone | The bar above the keyboard has headings, lists, quotes, pictures and indenting. Select text for bold, italic, strikethrough and links. Undo and Redo are at the top of the note |

In the web app, ⌘B / Ctrl+B is bold and ⌘I / Ctrl+I italic. On an iPhone with a hardware
keyboard, ⌘B, ⌘I, ⇧⌘X (strikethrough), Tab and ⇧Tab (indent) work too, and ⇧Return starts a new
line in the same paragraph.

## Pictures

Add a picture with the picture button. In the web app you can also paste or drop one, and on the
iPhone paste one. Before it's stored, it's shrunk to at most
2048 pixels on the long side, and its location and other metadata are removed. GIFs are the
exception: they're kept exactly as they are, so they stay animated, and keep any metadata they
carry. A picture can be up to 10 MiB.

On the phones, pictures added offline upload with the next sync. Pictures from your other devices
download the first time you open their note, and stay on the phone for when you're offline
(Android may clear them from its cache, and then downloads them again).

## Deleting a note

| App | How |
| --- | --- |
| Web | **Delete note** in the note's menu (on a phone, its own button) |
| Android | **Delete note** |
| iPhone | Swipe the note left in the list, or touch and hold it |

**Undo** appears for a few seconds. After that, the note is gone on every device: there's no
trash yet.

## Markdown

Every note is stored as Markdown, and that's what you get when you
[export](../self-hosting/markdown-mirror-and-export.md) it. You don't see the syntax while you
write: the editor shows formatting, the way Apple Notes does.

| You write | Stored as |
| --- | --- |
| Title | The note's title, its own field (not a `#` heading) |
| **Bold**, *italic*, ~~strikethrough~~ | `**bold**`, `*italic*`, `~~strikethrough~~` |
| Title, heading, subheading styles in the body | `# Title`, `## Heading`, `### Subheading` |
| Lists | `- item`, `1. item`, `- [ ] task`, `- [x] done` |
| Quote | `> quote` |
| Link | `[text](https://example.com)` |
| Picture | `![](notesd:image/<sha256>?w=1600&h=1200)` |

Markdown written elsewhere is safe to open. Anything the editor can't edit (tables, horizontal
rules, HTML, images from elsewhere) shows read-only and is saved back exactly as written.
**Code blocks** show highlighted on every app. The web app can also create one (type ```` ``` ````,
optionally with a language, and press Return) and edit it; the phone apps show them read-only.

Wiki-links (`[[…]]`), backlinks and plugins aren't supported.
