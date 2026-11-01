---
title: 4. Add your phone
description: Install the Android or iPhone app, connect it to your server, and watch a note sync.
---

# 4. Add your phone

The phone apps keep a full copy of your notes on the phone, so writing and search work without a
connection. They sync with the server whenever they can reach it.

## Find the server's address

Your phone can't use `localhost`: that's the phone itself. It needs the address of the computer
running notesd on your network. On that computer:

| System | Command |
| --- | --- |
| Linux | `hostname -I` (the first address) |
| macOS | `ipconfig getifaddr en0` |

With an address like `192.168.1.20`, the server is at `http://192.168.1.20:8080`. Connect the
phone to the same Wi-Fi and open that address in its browser: you should see notesd's sign-in
page. If it doesn't load, the computer's firewall may be blocking port 8080.

## Install the app and connect

**Android** (Android 9 or later):

1. On the phone, open the
   [latest release](https://github.com/cassiozen/notesd/releases/latest) and download
   `notesd-<version>.apk`.
2. Open the download. If Android asks, allow your browser to install unknown apps, then tap
   **Install**.
3. Open notesd, enter the server address (`http://192.168.1.20:8080`), and sign in with the
   username and password from [step 2](02-create-your-account.md).

**iPhone** (iOS 26 or later): notesd isn't on the App Store, so you build it with Xcode on a Mac.
Follow [Install](../guides/apps/iphone.md#install) in the iPhone guide, then in the app tap
**Connect to a server**, enter the server address and sign in. When iOS asks whether notesd may
find devices on your local network, allow it, or the app can't reach the server.

Your notes download, and the pancake recipe from [step 3](03-write-your-first-note.md) appears in
the list.

## Watch it sync

1. On the phone, open the note and add a line.
2. Back on the computer, click into the browser window. Within a few seconds the note shows the
   new line (reload the page if it doesn't).
3. Now turn on airplane mode and edit the note on the phone. It saves on the phone as usual.
4. Turn airplane mode off. The edit uploads on its own.

If the same note changes on two devices before they sync, nothing is lost: changes to different
parts are combined, and otherwise one version is kept as a "Conflicted copy".

## What's next

You have a working notesd on your home network. From here:

- **Use notesd away from home.** Plain HTTP is only safe on a network you trust, and the apps
  refuse it for public addresses. Put HTTPS in front of notesd:
  [Network access and HTTPS](../guides/self-hosting/https.md). Tailscale is the quickest option.
- **Install the web app.** With HTTPS, the browser can keep your notes on the computer and open
  notesd in its own window: [Installing the web app](../guides/apps/web.md).
- **Set up backups.** The phones' copies aren't a backup: [Backups](../guides/self-hosting/backups.md).
- **Lock private notes.** Locked notes are encrypted on your device, and the server can't read
  them: [Locked notes](../guides/using/locked-notes.md).
- **Add people.** Family or friends can have their own accounts on the same server:
  [Accounts](../guides/self-hosting/users.md#adding-people).
- **Run it as a service without Docker:** [Run the binary with systemd](../guides/self-hosting/binary-systemd.md).

Everything else is in the [documentation index](../README.md).
