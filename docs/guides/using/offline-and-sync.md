---
title: Offline and sync
description: How each app works without a connection, when it syncs, and what happens when two devices edit the same note.
---

# Offline and sync

## The phone apps

The Android and iPhone apps keep a full copy of your notes. Writing, search, tags and locked notes
all work with no connection; edits wait on the phone and upload when the server is reachable.

They sync:

- when the app opens or comes back to the screen;
- a couple of seconds after you stop typing;
- when you open a note;
- when the network comes back;
- every 30 seconds while the app is open;
- when you pull down on the list;
- now and then in the background, when the system allows it: on Android at most every 15 minutes,
  on the iPhone on its own schedule.

On the iPhone, the list's footer counts the notes while sync is fine, and says so when it isn't
(Offline, or how many notes are waiting to upload); tap it for **Sync now**. **Settings → Sync &
data** shows the last sync and any error, on the iPhone and in a web app that keeps your notes.

**When the server answers strangely.** If something between your device and the server answers
instead of notesd, such as a Wi-Fi login page, a company sign-in page or a redirect, the apps keep
your change and say what happened; sign in to the network or proxy, then sync again. A change that
fails this way 3 times is set aside so the rest can sync: the list says how many couldn't sync, and
**Retry** tries them again. Server errors (HTTP 5xx, such as a proxy's 502 while the server
restarts) and being offline never set a change aside: the apps just keep retrying. They stay on your device the whole time and count
as not synced, so signing out warns you before they'd be lost.

**When an app is too old.** If your server needs a newer version of an app, that app stops syncing,
keeps your changes, and asks you to update. In the web app, after the server is upgraded, an open
tab offers to reload.

You can use either app without a server: choose **Use without a server** (Android) or just start
writing (iPhone), and connect later in **Settings → Server**. Notes you wrote before connecting
upload when you connect.

## The web app

By default the web app works directly on the server, and needs a connection.

Choose **Keep my notes on this device** when you sign in, or later in **Settings → Sync & data**,
and the browser keeps a full copy too. Then the web app works offline like the phones: it syncs
when it starts, 2 seconds after an edit, when you open a note, when the tab or the network comes back,
every 30 seconds while a tab is visible, and every 5 minutes while one is open in the background. It's on by default in the [installed web app](../apps/desktop-web-app.md).

- It needs notesd served over HTTPS (or from `localhost`), and isn't offered in private windows.
- Anyone using that browser profile can read the notes it keeps, except locked notes. Only turn
  it on on a device you trust.
- Turning it off, or signing out, removes the copy from the browser.

## When two devices edit the same note

Nothing is lost unless you choose to drop it.

- **Different parts changed**, such as the title on one device and the text on another: both
  changes are kept.
- **The text changed on both, in different paragraphs, list items or headings:** the changes are
  merged. Ticking a checklist item on one device while adding items or rewording another on the
  other merges too. The note says it was merged, and **Undo** (for 14 days) splits it back into
  the other device's version and a copy of yours.
- **The same paragraph changed on both:** the note keeps the other device's version of that
  paragraph, and yours goes into a new note titled "… (Conflicted copy)", which links back to the
  original. Both notes keep everything else that merged. If nothing could be merged, the web
  app, working on the server, asks instead: **Use theirs**, or **Keep mine**, which saves your
  version as a separate note.
- **Locked notes** are never merged: when both devices changed the text, yours becomes a
  conflicted copy.
- **Edited on one device, deleted on another:** the edits come back as a new note titled
  "… (Recovered copy)". A deletion made offline doesn't delete a note that was edited elsewhere
  in the meantime.

[How sync works](../../concepts/how-sync-works.md) explains why.

## Signing out and lost devices

- A phone stays signed in as long as you use it at least every six months; a browser, every 30
  days. If the session ends, the phone keeps its notes and asks you to sign in again.
- A signed-out phone keeps its notes, so only the same account can sign in on it again. To hand a
  phone to someone else, use **Remove all data from this device** in **Settings → Sync & data**
  first. Sync before you do: it deletes notes the server doesn't have yet.
- **A lost device:** in the web app, **Settings → Security** lists the browsers and devices
  signed in to your account. Sign the lost one out there. It keeps the notes it has, but can't
  sync any more. Changing your password signs out every other session too.
