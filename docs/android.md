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

To build the app yourself, see [development.md](development.md#android).

## Connect to your server

On first launch, the app asks for your server:

1. Enter the **server address**, for example `notes.example.com` or
   `http://192.168.1.20:8080`.
2. Then either:
   - **Sign in** with your username and password, or
   - on a **new server** that has no account yet, create the account
     from the phone (see [hosting.md](hosting.md#2-create-your-account)).

The app only connects over **HTTPS**, or over plain HTTP to an address on
your own network (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`,
`*.local`), so your password never crosses the internet unencrypted.

**Use without a server** skips this step: notes stay on the phone, and you
can connect later in Settings. Notes you wrote before connecting upload
when you connect.

The app stays signed in for as long as you use it at least every six
months. If the session ends (for example after you sign out that device
from the web app), your notes stay on the phone and the app asks you to
sign in again. Nothing is lost.


## Notes, tags and search

- The first line of a note is its title. Format text with the bar above the
  keyboard: bold, italic, strikethrough, headings, bullet, numbered and
  check lists, quotes and links.
- Add a photo with the picture button in the same bar. It's resized and
  stripped of location data first, and works offline: it uploads with the
  next sync. GIFs are uploaded as they are, so they keep any metadata they
  hold. Photos from other devices download the first time you open the
  note and are cached; Android may clear that cache, and then they download
  again (which needs a connection).
- Write `#tag` anywhere in a note to tag it. Use `#multi word tag#` for
  names with spaces and `#parent/child` to nest them. "‹ Tags" above the
  list (or the sidebar, on wide screens) filters by tag. While you type a
  tag, the app suggests ones you already use.
- Search covers titles and text, works offline, and finds words as you
  type them.
