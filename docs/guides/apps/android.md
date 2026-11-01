---
title: notesd on Android
description: Install the Android app, connect it to your server, and use it.
---

# notesd on Android

The Android app keeps a full copy of your notes on the phone. Writing,
search and tags all work offline, and the app syncs with your notesd server
whenever it can. It runs on Android 9 or later, on phones, tablets and
foldables.

## Install

notesd isn't on the Play Store. Install the APK from GitHub:

1. On the phone, open the
   [latest release](https://github.com/cassiozen/notesd/releases/latest) and
   download `notesd-<version>.apk`.
2. Open the download. If Android asks, allow your browser (or file manager)
   to install unknown apps, then tap **Install**.

**Updates:** download the newer APK and install it over the existing app;
your notes and sign-in are kept. Every release is signed with the same key,
so Android accepts it as an update. An APK you built yourself is signed
with a different key, so to switch between the two you have to uninstall
first, which deletes the notes on the phone. Sync before you uninstall.

To build the app yourself, see [development.md](../../contributing/development.md#android).

## Connect to your server

On first launch, the app asks for your server:

1. Enter the **server address**, for example `notes.example.com`, or
   `http://192.168.1.20:8080` for a server on your LAN.
2. Then either:
   - **Sign in** with your username and password, or
   - on a **new server** that has no account yet, create the account
     from the phone (see [Create your account](../self-hosting/users.md#create-your-account)).

The app only connects over **HTTPS**, or over plain HTTP to a local
address (private, link-local or loopback IPs such as `192.168.x` or `10.x`,
`localhost`, `*.local`), so your password never crosses the internet unencrypted.

**Use without a server** skips this step: notes stay on the phone, and you
can connect later in Settings. Notes you wrote before connecting upload
when you connect.

The app stays signed in for as long as you use it at least every six
months. If the session ends (for example after you sign out that device
from the web app), your notes stay on the phone and the app asks you to
sign in again. Nothing is lost.

## Android specifics

- Format text with the bar above the keyboard. Add a photo with its picture button.
- On a wide window, such as a large tablet in landscape, the tags and spaces sidebar sits beside
  the list. Otherwise, **‹ Tags** above the list opens it.
- Open locked notes with your fingerprint: see [Locked notes](../using/locked-notes.md). While a
  locked note is open, it's kept out of screenshots and the recent-apps view.
- To email a note, share it and choose **Email** ([setup](../using/email.md)).

## Using notesd

- [Writing and Markdown](../using/writing-and-markdown.md)
- [Tags, spaces and search](../using/organize-and-find.md)
- [Locked notes](../using/locked-notes.md)
- [Offline and sync](../using/offline-and-sync.md)
- [Settings](../using/settings.md)
