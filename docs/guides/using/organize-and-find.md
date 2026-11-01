---
title: Tags, spaces and search
description: Organize notes with tags and spaces, and find them with search.
---

# Tags, spaces and search

## Tags

A tag is text in the note: write `#` and a name anywhere, in the title or the body. There's no tag
field, so tags stay in the note when you export it, and sync with its text.

| Write | Tag |
| --- | --- |
| `#recipes` | `recipes` |
| `#home/garden` | `garden`, nested under `home` |
| `#to read#` | `to read`: a tag with spaces is closed with a second `#` |
| `#school/biology 101#` | Nesting and spaces together |

The sidebar lists every tag you use, nested ones under their parent. Choose one to see only its
notes. A parent tag also shows the notes of every tag nested under it. **Untagged** shows the notes
without tags. While you type a tag, the apps suggest the ones you already use.

Tags work like Bear's, and nest like Obsidian's. The details:

- A tag starts at the beginning of a line or after a space or `(`, `[` or `{`. So `# Heading` is a
  heading, and `C#` or a URL's `#fragment` isn't a tag.
- A one-word tag ends at a space or at punctuation other than `-`, `_` and `/`: `#done,` is `done`.
- A tag made only of numbers isn't one (`#1 priority`), unless it's closed: `#2024#`.
- Tags ignore case: `#Work` and `#work` are the same tag. The sidebar shows the spelling of the most
  recently edited note.
- Links, code and pictures never hold tags.
- A tag disappears when no note uses it any more. Tags can't be renamed in one step yet.

## Spaces

Spaces keep separate contexts apart, such as Work and Personal. Each note is in one space, and the
list, tags and search show the current space only. Spaces aren't folders: they don't nest, and you
never have to file a note. Use tags to organize within a space.

- **Switch spaces** from the space's name at the top of the sidebar.
- **Create one** with **New space** in that menu, or in **Settings → Spaces**. Give it a name and
  an icon. You can rename it and change its icon later.
- **Move a note** with **Move to space** in its menu. It only shows once there's more than one
  space.
- **Delete a space** in **Settings → Spaces**, once it's empty. The first space, "Notes", can be
  renamed but not deleted.

New notes go into the current space. Each device remembers its own current space, and goes back to
the first one when you open the app fresh, or come back to it after more than 30 minutes away.

## Search

Type in the search box above the list. Search covers titles and text in the current space, and
finds words as you type them: `panc` finds "Pancakes". Title matches rank higher. Under the
results, a last row counts the matches in your other spaces; choose it to see them.

- Each result shows the text around the match.
- Locked notes are found by their title only.
- On the phones, search works offline and finds edits right away, before they sync. The web app
  searches the server, or its own copy when it [keeps your notes](offline-and-sync.md#the-web-app).
- In the web app, **Enter** opens the first result and **Escape** clears the search.

If search results look wrong, [`notesd reindex`](../../reference/cli.md#reindex) rebuilds the
index on the server.
