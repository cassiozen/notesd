---
title: 5. Add your phone
description: Install the Android or iPhone app, connect it to your server, and watch a note sync.
---

# 5. Add your phone

The phone apps keep a full copy of your notes on the phone, so writing and search work without a
connection. They sync with the server whenever they can reach it.

## Install the app and connect

The server address is your HTTPS address from [step 3](03-set-up-https.md), such as
`notes.example.com`. With Tailscale, the phone must be on the tailnet too.

**Android** (Android 9 or later):

1. Install notesd from
   [Google Play](https://play.google.com/store/apps/details?id=app.notesd). It's in early access:
   if the store doesn't show it, [join the test](https://play.google.com/apps/testing/app.notesd)
   first. Without Google Play, install the APK instead:
   [Without Google Play](../guides/apps/android.md#without-google-play).
2. Open notesd, enter the server address, and sign in with the username and password from
   [step 2](02-create-your-account.md).

**iPhone** (iOS 26 or later): notesd is coming to the App Store. Until then, you build it with Xcode on a Mac.
Follow [Install](../guides/apps/iphone.md#install) in the iPhone guide, then in the app tap
**Connect to a server**, enter the server address and sign in.

Your notes download.

## What's next

- **Set up backups.** The phones' copies aren't a backup: [Backups](../guides/self-hosting/backups.md).
- **Add your computer.** Install the [Mac app](../guides/apps/mac.md), or the
  [desktop web app](../guides/apps/desktop-web-app.md) on any other computer. Both keep your notes
  on the computer and open notesd in its own window.
- **Lock private notes.** Locked notes are encrypted on your device, and the server can't read
  them: [Locked notes](../guides/using/locked-notes.md).
- **Add people.** Family or friends can have their own accounts on the same server:
  [Accounts](../guides/self-hosting/users.md#adding-people).
- **Run it as a service without Docker:** [Run the binary with systemd](../guides/self-hosting/binary-systemd.md).

Everything else is in the [documentation index](../README.md).
