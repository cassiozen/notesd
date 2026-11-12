---
title: 3. Install the apps
description: Use notesd outside the browser, on your computer and your phone, with your notes kept on the device.
---

# 3. Install the apps

The browser works anywhere, but the apps keep a full copy of your notes on the device: writing,
search and tags work with no connection, and they sync with your server whenever they can reach it.

To connect one, enter your server address from [step 1](01-run-the-server.md), such as
`notes.example.com`, and sign in. The apps need HTTPS, except the phone apps, which also accept a
server on your home network (`http://192.168.1.10:8080`) while the phone is on it.

## On your computer

- **Mac** (macOS 14 or later, Apple silicon): download
  [`notesd-mac.dmg`](https://github.com/cassiozen/notesd/releases/latest/download/notesd-mac.dmg),
  open it and drag **notesd** to **Applications**. On a Mac with Touch ID, locked notes open with
  your fingerprint. More in [notesd for Mac](../guides/apps/mac.md).
- **Windows, Linux and ChromeOS:** install the web app from Chrome. Open your server's address, sign
  in, and click the install icon at the right of the address bar. It opens in its own window and
  keeps your notes on the computer. More in [Desktop web app](../guides/apps/desktop-web-app.md).

## On your phone

- **Android** (9 or later): get notesd on
  [Google Play](https://play.google.com/store/apps/details?id=app.notesd), or install the APK from
  GitHub: [Without Google Play](../guides/apps/android.md#without-google-play). More in
  [notesd on Android](../guides/apps/android.md).
- **iPhone** (iOS 26 or later): notesd is coming to the App Store. Until then, you build it with
  Xcode on a Mac: follow [Install](../guides/apps/iphone.md#install). In the app, tap
  **Connect to a server**.

Your notes download, and from then on a note you write on one device shows up on the others.

## What's next

- **Lock private notes.** Locked notes are encrypted on your device, and the server can't read
  them: [Locked notes](../guides/using/locked-notes.md).
- **Add people.** Family or friends can have their own accounts on the same server:
  [Accounts](../guides/self-hosting/users.md#adding-people).
- **Set up backups.** The apps' copies aren't a backup: [Backups](../guides/self-hosting/backups.md),
  or on Fly.io, [daily snapshots](../guides/self-hosting/fly-io.md#backups).

Everything else is in the [documentation index](../README.md).
