---
title: Publishing a space
description: Turn a space into a small public website, with a page per note, tags, a feed and a search.
---

# Publishing a space

Publish a space and anyone can read it on the web, at an address on your server such as
`https://notes.example.com/@garden/`. There's nothing to set up: every note in the space with a
title and some text is a post.

The site has:

- a home page listing posts, newest first, 30 to a page;
- a page for each post, with its tags and the posts before and after it;
- a page for each tag, and a list of every tag;
- a search, and a feed for feed readers.

It looks like notesd: titles in the same serif as the apps, and the colour of the space's icon
along the top, in each tab's icon and in small details. It follows the reader's light or dark
setting.

## Publish a space

In the web or Mac app:

1. Choose the space's name at the top of the sidebar, then **Info**.
2. Under **Published**, choose **Publish**.

Publish with notesd open at the address people should use, not `localhost`: the feed's links are
made from it.

The address comes from the space's name. To change it, edit it where it's shown under
**Published** and press Enter. Links to the old address stop working.

To take the site down, choose **Stop publishing**. The address stays the space's, for when you
publish it again.

## Good to know

- **It's live.** A note you save shows on the site about 10 seconds after you stop editing, or
  within 2 minutes if you keep going. Write drafts in another space and move them in when they're
  ready.
- **Locked notes can't be in a published space.** Unlock them, or move them out, to publish it.
  Your first space, "Notes", can't be published.
- **Sharing and publishing are separate.** A shared space can be published too, by its owner, and
  then every member's notes in it are public.
- **Custom CSS.** Under **Published → Custom CSS**, add CSS of your own. It's added after the
  site's styles, so it wins without `!important`, and most changes are a variable, such as
  `:root { --accent: teal }`. The variables are `--bg`, `--surface`, `--text`, `--muted`,
  `--border`, `--accent`, `--link`, `--font-body`, `--font-heading`, `--font-mono` and
  `--measure`.
- **For now, publishing is set up in the web or Mac app.** The Android and iPhone apps show that
  a space is published, but don't publish one yet.

For who can see what, see the [security model](../../concepts/security-model.md).
