---
title: notesd for Mac
description: Install the Mac app, connect it to your server, and use it.
---

# notesd for Mac

The Mac app is notesd in its own window, with its own Dock icon and menus. It keeps a full copy of
your notes on the Mac, so writing, search and tags work offline, and it syncs with your server
whenever it can. Locked notes open with Touch ID. It runs on macOS 14 or later, on Macs with Apple
silicon.

## Install

1. Open the [latest release](https://github.com/cassiozen/notesd/releases/latest) and download
   `notesd-<version>.dmg`.
2. Open it and drag **notesd** to **Applications**.

The app is signed and notarized by Apple, so it opens without warnings.

**Updates:** download the newer DMG and replace the app in Applications. Your notes and sign-in are
kept. Update the app when you update your server: each release of the app goes with the server
release of the same version.

## Connect to your server

On first launch, the app asks for your server: the address you open notesd at in a browser, such as
`notes.example.com`. It needs **HTTPS**, as set up in
[Get started](../../get-started/03-set-up-https.md); plain HTTP works only for a server on the same
Mac (`http://localhost:8080`). Then sign in, or, on a new server, create its first account.

To switch servers later, choose **notesd → Change Server…**.

Your notes are always kept on the Mac: they open and save with no connection, and sync when it's
back. Signing out removes them from the Mac; they stay on the server. If the server can't be reached
the first time you open the app, there's nothing on the Mac yet, and the app offers **Try Again**
and **Change Server…**.

## Mac specifics

- Open locked notes with **Touch ID**. It's offered the first time you type the lock password, or
  turn it on in **Settings → Security → Touch ID**. See [Locked notes](../using/locked-notes.md).
  Passkeys aren't available in the Mac app: Touch ID takes their place.
- Links open in your browser. Downloads go to your Downloads folder.
- **View → Reload** (⌘R) loads the app again, for instance after your server was updated.

## Using notesd

- [Writing and Markdown](../using/writing-and-markdown.md)
- [Tags, spaces and search](../using/organize-and-find.md)
- [Locked notes](../using/locked-notes.md)
- [Offline and sync](../using/offline-and-sync.md)
- [Settings](../using/settings.md)
