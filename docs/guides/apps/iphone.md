---
title: notesd on iPhone
description: Build and install the iPhone app, connect it to your server, and use it.
---

# notesd on iPhone

The iPhone app keeps a full copy of your notes on the phone. Writing,
search and tags all work offline, and the app syncs with your notesd server
whenever it can. It runs on iOS 26 or later.

It does what the web and Android apps do, except that there's no iPad
layout. On an iPhone Duo it uses both displays: folded, it's the phone
layout; unfolded, the list and the open note sit side by side (split at the
fold while it's partly folded, on iOS 27.1 or later). Folding or unfolding keeps the note you're
writing open.

## Install

notesd isn't on the App Store. You build it with Xcode on a Mac and install
it on your iPhone with your Apple ID. A free Apple ID works.

1. Install Xcode from the Mac App Store, open it once and sign in with your
   Apple ID (Xcode → Settings → Accounts).
2. Get the code: `git clone https://github.com/cassiozen/notesd`.
3. In `apps/ios/Config`, copy `Local.xcconfig.example` to `Local.xcconfig`.
   Set `DEVELOPMENT_TEAM` to your team ID (Xcode → Settings → Accounts →
   your Apple ID) and `BUNDLE_ID_PREFIX` to something only you use, such as
   `com.yourname`.
4. Connect the iPhone with a cable, open `apps/ios/Notes.xcodeproj`, pick
   the iPhone at the top of the window and press Run (⌘R).
5. The first time, the iPhone asks you to trust the developer: Settings →
   General → VPN & Device Management → your Apple ID → Trust. It also asks
   you to turn on Developer Mode.

**With a free Apple ID, the app stops opening after 7 days.** Run it from
Xcode again to renew it. Your notes and sign-in are kept, as they are when
you install a newer version over it. Deleting the app deletes the notes on
the phone, so sync before you do.

## Connect to your server

The app opens straight into your notes and works offline: notes stay on the
phone until you connect. To connect, tap **Connect to a server** on the empty
list, the **On this iPhone only** line at the bottom of the list, or **Connect
to a server** in Settings (the sidebar's gear):

1. Enter the **server address**, for example `notes.example.com`, or
   `http://192.168.1.20:8080` for a server on your LAN.
2. Then either:
   - **Sign in** with your username and password, or
   - on a **new server** that has no account yet, create the account
     from the phone (see [Create your account](../self-hosting/users.md#create-your-account)).

The app only connects over **HTTPS**, or over plain HTTP to a private
address (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`,
`*.local`), so your password never crosses the internet unencrypted. For a
server on your LAN, iOS asks once whether notesd may find devices on
your local network. Allow it, or the app can't reach the server.

Notes you wrote before connecting upload when you connect.

The app stays signed in as long as you use it at least every six months. If
the session ends (for example after you sign out that device from the web
app), your notes stay on the phone and the list offers to sign in again.

## iPhone specifics

- The bar above the keyboard has headings, lists, quotes, pictures and indenting; select text for
  bold, italic, strikethrough and links. Undo and Redo sit at the top of the note. With a hardware
  keyboard, ⌘B, ⌘I, ⇧⌘X, Tab and ⇧Tab work, and ⇧Return starts a new line within a paragraph.
- The sidebar (the button at the top left of the list, or swipe in from the left edge) holds tags,
  spaces and Settings.
- Swipe a note left, or touch and hold it, to delete it.
- The list's footer shows the sync status; tap it for **Sync now**. iOS lets the app sync in the
  background only now and then, on its own schedule.
- Open locked notes with Face ID (or Touch ID): see [Locked notes](../using/locked-notes.md).
  iOS doesn't let apps block screenshots, so the app covers an open locked note in the app
  switcher instead.
- Email a note with **Email…** in its ••• menu ([setup](../using/email.md)).

## Using notesd

- [Writing and Markdown](../using/writing-and-markdown.md)
- [Tags, spaces and search](../using/organize-and-find.md)
- [Locked notes](../using/locked-notes.md)
- [Offline and sync](../using/offline-and-sync.md)
- [Settings](../using/settings.md)
