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
- a few seconds after you stop typing;
- when the network comes back;
- on the iPhone, every couple of minutes while the app is open;
- when you pull down on the list;
- now and then in the background, when the system allows it: on Android at most every 15 minutes,
  on the iPhone on its own schedule.

On the iPhone, the list's footer counts the notes while sync is fine, and says so when it isn't
(Offline, or how many notes are waiting to upload); tap it for **Sync now**. **Settings → Sync &
data** shows the last sync and any error, on the iPhone and in a web app that keeps your notes.

You can use either app without a server: choose **Use without a server** (Android) or just start
writing (iPhone), and connect later in **Settings → Server**. Notes you wrote before connecting
upload when you connect.

## The web app

By default the web app works directly on the server, and needs a connection.

Choose **Keep my notes on this device** when you sign in, or later in **Settings → Sync & data**,
and the browser keeps a full copy too. Then the web app works offline like the phones: it syncs
when it starts, 2 seconds after an edit, when the tab or the network comes back, and every minute
while a tab is open. It's on by default in the [installed web app](../apps/web.md).

- It needs notesd served over HTTPS (or from `localhost`), and isn't offered in private windows.
- Anyone using that browser profile can read the notes it keeps, except locked notes. Only turn
  it on on a device you trust.
- Turning it off, or signing out, removes the copy from the browser.

## When two devices edit the same note

Nothing is lost unless you choose to drop it.

- **Different parts changed**, such as the title on one device and the text on another: both
  changes are kept.
- **The text changed on both:** the phone apps keep the server's version as the note, and save
  theirs as a new note titled "… (Conflicted copy)", which links back to the original. The web
  app, working on the server, asks instead: **Use theirs**, or **Keep mine**, which saves your
  version as a separate note.
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
