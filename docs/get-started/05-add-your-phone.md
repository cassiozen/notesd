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

1. On the phone, open the
   [latest release](https://github.com/cassiozen/notesd/releases/latest) and download
   `notesd-<version>.apk`.
2. Open the download. If Android asks, allow your browser to install unknown apps, then tap
   **Install**.
3. Open notesd, enter the server address, and sign in with the username and password from
   [step 2](02-create-your-account.md).

**iPhone** (iOS 26 or later): notesd isn't on the App Store yet, so you build it with Xcode on a Mac.
Follow [Install](../guides/apps/iphone.md#install) in the iPhone guide, then in the app tap
**Connect to a server**, enter the server address and sign in.

Your notes download.

## What's next

- **Set up backups.** The phones' copies aren't a backup: [Backups](../guides/self-hosting/backups.md).
- **Install the web app.** The browser can keep your notes on the computer and open notesd in its
  own window: [Installing the web app](../guides/apps/web.md).
- **Lock private notes.** Locked notes are encrypted on your device, and the server can't read
  them: [Locked notes](../guides/using/locked-notes.md).
- **Add people.** Family or friends can have their own accounts on the same server:
  [Accounts](../guides/self-hosting/users.md#adding-people).
- **Run it as a service without Docker:** [Run the binary with systemd](../guides/self-hosting/binary-systemd.md).

Everything else is in the [documentation index](../README.md).
