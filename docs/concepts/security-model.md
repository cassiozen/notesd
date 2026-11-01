---
title: Security model
description: What the server can read, how accounts and sessions are protected, and what locked notes do and don't protect.
---

# Security model

## The short version

- **Regular notes are stored unencrypted on the server**, like most notes apps. Anyone who can
  read the server's disk or backups can read them. Use disk encryption if that matters to you.
- **[Locked notes](../guides/using/locked-notes.md) are end-to-end encrypted.** The server stores
  only ciphertext, and never has the key.
- **Use HTTPS** for anything reachable from outside your home network. The apps refuse plain HTTP
  to public addresses.

## Accounts

- Each account's notes, search index, spaces, locked-notes keyring and pictures are in their own
  database file. A request only ever opens the file of the account it's signed in as, so one
  account can't reach another's notes, even through a bug in a single check.
- Accounts are added on the server with `notesd add-user`. There's no public sign-up and no email,
  so a forgotten password is reset on the server, with `notesd reset-password`.
- **Admins** can change settings that apply to the whole server (email) and update the server
  from Settings. The first account is one. Admins can't see other accounts' notes.
- **The first account** is created by whoever opens the server first. Until then, anyone who can
  reach it could claim it. That's why the binary listens only on the machine itself by default,
  and the guides create the account before exposing the server. To skip that window,
  `NOTESD_OWNER_USERNAME` and `NOTESD_OWNER_PASSWORD` create it when the server starts.

## Passwords and sessions

- Passwords are hashed with Argon2id. After 10 failed sign-ins in 15 minutes from one address,
  sign-ins from it are refused for a while.
- **Browsers** get an HttpOnly session cookie, `Secure` when `--public-url` is HTTPS, and must
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
- **Pictures from before locking** stay on the server, unencrypted, for 30 days.
- **The web app** is served by the server it's protecting your notes from. Someone who controls the
  server could change the app to capture your lock password. The phone apps don't have this
  weakness.

**The forgotten password.** If you forget the lock password and have no passkey, fingerprint or
Face ID set up that still opens your notes, they can't be recovered, by you or anyone.

The cryptography: each account has one 256-bit key that encrypts locked notes and their pictures
with AES-256-GCM. The server keeps it only wrapped (encrypted) under the lock password
(PBKDF2-SHA256, 600,000 iterations) and under each passkey (WebAuthn PRF). Fingerprint and Face ID
keep a copy on the phone, sealed by the Android Keystore or iOS Keychain behind the biometric check. The full design is in
[architecture §10.1](../contributing/architecture.md#101-locked-notes).

## What notesd connects to

Nothing, unless you turn it on: there are no analytics. The server contacts
[Resend](../guides/using/email.md) to email a note, and GitHub to look for new releases only when
[updating from Settings](../guides/self-hosting/upgrading.md#updating-from-settings) is on.
