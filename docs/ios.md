# notesd on iPhone

The iPhone app keeps a full copy of your notes on the phone. Writing,
search and tags all work offline, and the app syncs with your notesd server
whenever it can. It runs on iOS 26 or later.

It does what the web and Android apps do, except that there's no iPad
layout.

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

1. Enter the **server address**, for example `notes.example.com` or
   `http://192.168.1.20:8080`.
2. Then either:
   - **Sign in** with your username and password, or
   - on a **new server** that has no account yet, create the account
     from the phone (see [hosting.md](hosting.md#2-create-your-account)).

The app only connects over **HTTPS**, or over plain HTTP to an address on
your own network (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`,
`*.local`), so your password never crosses the internet unencrypted. For a
server on your network, iOS asks once whether notesd may find devices on
your local network. Allow it, or the app can't reach the server.

Notes you wrote before connecting upload when you connect.

The app stays signed in as long as you use it at least every six months. If
the session ends (for example after you sign out that device from the web
app), your notes stay on the phone and the list offers to sign in again.

## Notes, tags and search

- The first line of a note is its title. Format text with the bar above the
  keyboard: headings, bullet, numbered and check lists, quotes, pictures and
  indentation; select text for bold, italic, strikethrough and links. Undo
  and Redo sit at the top of the note. With a hardware keyboard, ⌘B,
  ⌘I, ⇧⌘X, Tab and ⇧Tab work too, and ⇧Return starts a new line within a
  paragraph.
- Write `#tag` anywhere in a note to tag it. Use `#multi word tag#` for
  names with spaces and `#parent/child` to nest them. The sidebar (the
  button at the top left of the list, or swipe in from the left edge)
  filters by tag, and holds Settings. While you type a tag, the bar
  suggests ones you already use.
- Search covers titles and text, works offline, and finds words as you type
  them.
- Swipe a note left, or touch and hold it, to delete it. Undo appears for a
  few seconds.

## Sync

The app syncs when it opens, every couple of minutes while it's open, a few
seconds after you stop typing, when the network comes back, and when you
pull down on the list. iOS also lets it
sync now and then in the background, but on its own schedule. While sync
is fine, the list's footer just counts the notes; when it isn't, it says so
(Offline, or how many notes are waiting to upload), and tapping it offers
Sync now.

If the same note was changed on two devices before they synced, nothing is
lost. When both changed different parts (one the title, the other the
text), the changes are combined. When both changed the text, this phone's
version becomes a copy titled "(Conflicted copy)" that links back to the
original.

## Pictures

Add a picture with the photo button on the bar above the keyboard, or paste
one. It goes on a line of its own, below the line you're on. Pictures are
resized (at most 2048 pixels on the long side) and their location and other
metadata removed before they're stored. GIFs are the exception: they're kept
exactly as they are, so they stay animated, keep their size and keep any
metadata they carry. They upload with the next sync, so
you can add them offline too. Pictures from your other devices download the
first time you open their note, and stay on the phone for when you're
offline. Backspace after a picture removes it.

## Locked notes

A locked note is encrypted on the phone; the server can't read it. Its title
stays readable, so it shows in the list and in search by title.

- **Lock a note** from its ••• menu. The first time, on any of your devices,
  you create the lock password; every locked note uses it.
- **Open a locked note** by typing the lock password, or with Face ID
  (Touch ID on an iPhone that has it) once that's on. Locked notes stay open
  until you leave the app, choose **Lock now**, or don't use the app for
  five minutes.
- **Face ID**: the first time you type the lock password on the iPhone, it
  asks whether to use Face ID for locked notes from then on. After that,
  opening a locked note asks for your face straight away; **Use Password**
  falls back to the lock password. **Not Now** is remembered.
  **Settings → Locked notes → Unlock with Face ID** turns it on or off.
  It turns itself off, and asks for the password again, when the lock
  password is changed on another device or reset, or when you set up
  Face ID again. Changing the lock password on the iPhone keeps it on.
- **Remove the lock** from the ••• menu of an open locked note.
- **Settings → Locked notes** changes the lock password, or resets it if you
  forgot it. Resetting needs your account password, and notes locked with
  the old password can never be opened again.

While a locked note is open, the app covers it in the app switcher. iOS
doesn't let apps block screenshots, so the iPhone app can't do what Android
does there.

## Email

notesd can email a note through [Resend](https://resend.com). In
**Settings → Email**, paste a Resend API key with sending access and the
address emails come from (on a domain you verified with Resend). Then a
note's ••• menu has **Email…**. The server sends the note as it is after a
sync, as formatted HTML with the Markdown as plain text. Locked notes can't
be emailed.
