---
title: Locked notes
description: Encrypt chosen notes on your devices so the server can't read them, and unlock them with a password, passkey, fingerprint or Face ID.
---

# Locked notes

A locked note is encrypted on your device before it's saved, with a key the server never has. The
server stores only ciphertext, and so do its backups. Like Apple Notes' locked notes, the title
stays readable, so the note still shows in the list and in search by its title.

## Lock a note

Choose **Lock note** from the note's menu (the ••• menu on iPhone). The first time, on any of your
devices, you create the **lock password**. Every locked note uses that one password, on every
device.

To unlock a note for good, open it and choose **Remove lock** from the same menu.

## Open a locked note

Type the lock password, or use one of these, once it's set up:

| App | Instead of the password | Set up |
| --- | --- | --- |
| Web | A **passkey**: your fingerprint, face or device PIN, through the browser | **Settings → Security → Passkeys**. Adding one asks for the lock password |
| Android | Your **fingerprint** | Offered the first time you type the lock password on the phone, or **Settings → Security → Unlock with fingerprint** |
| iPhone | **Face ID** (or Touch ID) | Offered the first time you type the lock password on the iPhone, or **Settings → Security → Unlock with Face ID** |

Fingerprint and Face ID unlock stay on that one phone. They turn themselves off, and ask for the
password again, when the lock password is changed on another device or reset, or when you change
the fingerprints or faces the phone knows. A passkey belongs to the server's address: if you move
the server to a new address, add your passkeys again.

Once unlocked, locked notes stay open until:

- you choose **Lock now**;
- you don't use the app for five minutes;
- on the web, the tab has been in the background for five minutes;
- on the phones, you leave the app.

While a locked note is open, Android keeps it out of screenshots and the recent-apps view. iOS
doesn't let apps block screenshots, so the iPhone app covers the note in the app switcher instead.

## Change or reset the lock password

In **Settings → Security**:

- **Change** it with the current lock password. No note is re-encrypted; your other devices use
  the new password once they sync.
- **Reset** it if you forgot it. That needs your account password, and **notes locked with the
  old password can never be opened again**, by you or anyone else. Use reset only when there's no
  passkey, fingerprint or Face ID left that can still open them.

## What the server can still see

- Titles, the dates, the size of each note, and tags written in the title. Tags in the body aren't
  visible, so locked notes are only found by their title, and their body tags aren't in the
  sidebar.
- **Text from before locking.** Locking scrubs a note's earlier text from the server's database,
  but [backups](../self-hosting/backups.md) made before still hold it.
- **Pictures.** A locked note's pictures are encrypted too. Their plain copies from before you
  locked it stay on the server for 30 days, then they're deleted, unless another note still shows
  them.

Locked notes can't be [emailed](email.md). They're left out of
[exports](../self-hosting/markdown-mirror-and-export.md) and the Markdown mirror, except
`notesd export --include-locked`, which asks for the lock password and decrypts them on the
server's machine.

**On the web,** the app comes from the server it's protecting your notes from. Someone in control
of the server could change the app to capture your lock password. The phone apps don't have that
weakness. See the [security model](../../concepts/security-model.md) for the whole picture.
