---
title: Security model
description: What the server can read, how accounts and sessions are protected, who sees a shared space, and what locked notes do and don't protect.
---

# Security model

## The short version

- **Regular notes are stored unencrypted on the server**, like most notes apps. Anyone who can
  read the server's disk or backups can read them. Use disk encryption if that matters to you.
- **[Locked notes](../guides/using/locked-notes.md) are end-to-end encrypted.** The server stores
  only ciphertext, and never has the key.
- **Use HTTPS.** Plain HTTP is only safe on a network you trust, and the apps refuse it to public
  addresses.

## Accounts

- Each account's notes, search index, spaces, locked-notes keyring and pictures are in their own
  database file. A request only ever opens one file: the account's own, or a
  [shared space](#shared-spaces) it's a member of. So one account can't reach another's notes,
  even through a bug in a single check.
- Accounts are added by an admin, in the web, Mac or Android app, or with `notesd add-user`. There's no public
  sign-up and no email. An admin invites someone with a **setup link** they send themselves: it
  works once, for 7 days, and only a hash of it is kept on the server. A forgotten password is
  reset the same way, or on the server with `notesd reset-password`. Whoever has a link can set
  that account's password, so it should be sent privately.
- **Admins** can change settings that apply to the whole server (email), manage accounts, and
  update the server from Settings. The first account is one. Admins can't see other accounts' notes.
- **The first account** is created by whoever opens the server first. Until then, anyone who can
  reach it could claim it. That's why the guides run notesd on the machine's loopback interface,
  and create the account before putting HTTPS in front. On a hosting platform, create it right
  after deploying. To skip that window, `NOTESD_OWNER_USERNAME` and `NOTESD_OWNER_PASSWORD` create
  it when the server starts.

## Shared spaces

[Sharing a space](../guides/using/sharing.md) is the only way one account sees another's notes.

- **Members can do almost everything.** Every member can read, edit and delete every note in the
  space, and see its pictures. Only the owner can invite or remove people, rename the space or
  delete it. Each note shows who last changed it.
- **Moving a note into a shared space shares it.** The apps ask first, naming who will see it.
- **Each shared space is its own database file.** The server opens it only for its members.
  Anyone else gets "not found", so they can't even tell the space exists.
- **Every account can see the usernames on the server,** so an owner can pick who to invite.
  They can't see anyone's spaces or notes.
- **No locked notes.** Locked notes are encrypted with a key only one account has, so a shared
  space can't hold them. Its notes are as readable on the server as any regular note.
- **Removing someone works from then on.** Their devices drop the space's notes the next time they
  sync. A copy they already have stays with them: an export, or a device that never syncs again.

## Passwords and sessions

- Passwords are hashed with Argon2id. After 10 failed sign-ins in 15 minutes from one address,
  sign-ins from it are refused for a while.
- **Browsers** get an HttpOnly session cookie, `Secure` when served over HTTPS (directly or
  as reported by the proxy's `X-Forwarded-Proto`), and must
  send a CSRF token with every change; requests from other sites are refused. A session ends after
  30 days without use.
- **Phones** get an access token that lasts an hour and a refresh token that lasts 180 days from
  its last use, kept in the iOS Keychain or the Android Keystore. The server stores only hashes of
  them.
- Changing your password signs out every other session. **Settings → Security** in the web app
  lists sessions and signs out any of them.

## Copies on your devices

- The phones keep every note in their own database, readable by the app. Signing out keeps them,
  so only the same account can sign in again; **Remove all data from this device** deletes them.
- A browser that [keeps your notes](../guides/using/offline-and-sync.md#the-web-app) stores them in
  its own storage, unencrypted except for locked notes, readable by anyone using that browser
  profile. That's why it's off by default. Signing out removes them.

## Locked notes

**What they protect against:** anyone who reads the server, its database or its backups: they see
only ciphertext.

**What they don't protect:**

- **Titles,** dates, sizes and tags written in the title stay readable.
- **An unlocked device,** and whoever holds it, sees everything.
- **Text from before locking.** The server scrubs a note's earlier text from its live database
  when you lock it, but backups made before still have it. Free disk blocks, file-system snapshots
  and SSD wear-levelling are beyond notesd's reach.
- **Pictures from before locking** stay on the server, unencrypted, for 30 days, or longer while
  another note still shows them. Backups made before locking keep them for as long as you keep
  the backups.
- **The web app** is served by the server it's protecting your notes from. Someone who controls the
  server could change the app to capture your lock password. The phone apps don't have this
  weakness.

**The forgotten password.** If you forget the lock password and have no passkey, fingerprint or
Face ID set up that still opens your notes, they can't be recovered, by you or anyone.

The cryptography: each account has one 256-bit key that encrypts locked notes and their pictures
with AES-256-GCM. The server keeps it only wrapped (encrypted) under the lock password
(PBKDF2-SHA256, 600,000 iterations) and under each passkey (WebAuthn PRF). Fingerprint and Face ID
keep a copy on the phone, sealed by the Android Keystore or iOS Keychain behind the biometric check.
Each keyring entry records how its key is derived, and a locked note records its format version, so
a later notesd can strengthen either without an older app mistaking the result for a wrong password
or for plain text: older apps ask you to update instead. The full design is in
[architecture §10.1](../contributing/architecture.md#101-locked-notes).

## What notesd connects to

Nothing, unless you turn it on: there are no analytics. The server contacts
[Resend](../guides/using/email.md) to email a note, and GitHub to look for new releases only when
[updating from Settings](../guides/self-hosting/upgrading.md#updating-from-settings) is on.
