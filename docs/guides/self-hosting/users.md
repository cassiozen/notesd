---
title: Accounts
description: Create the first account, add people, and reset passwords.
---

# Accounts

## Create your account

> [!IMPORTANT]
> Until the account exists, anyone who can reach the server can create it. Create it before you
> put [HTTPS](https.md) in front, or right after deploying to a hosting platform.

On the server, with [`create-owner`](../../reference/cli.md#create-owner):

```bash
docker exec -it notesd notesd create-owner --username me
# systemd
notesd create-owner --username me
```

With systemd, set [`NOTESD_DATA_DIR`](../../reference/cli.md#with-systemd) first. You can also sign
up from the web app or the Android app directly: the first account created is the
owner.

To create it when the server first starts instead, set `NOTESD_OWNER_USERNAME` and
`NOTESD_OWNER_PASSWORD` (with Docker, as `-e` flags). The password then stays in the container's
or unit's configuration.

This first account is an admin: it can set up [emailing notes](../using/email.md) for everyone.

## Adding people

notesd can hold a few accounts, such as for family or friends. Each has its own notes, spaces, locked notes and pictures, which no other account can see.

### From the apps

Admins see **People** in **Settings → Server**, in the web app, the Mac app and the Android app:

1. Choose **Invite someone…** and enter the username they'll sign in with.
2. Send them the link you get, however you like: a message, an email. On Android, **Share…** opens
   the share sheet with a message ready. It works once, for 7 days.
3. They open it, choose a password, and they're signed in. From then on they can sign in on a phone too.

The link uses the address you opened notesd at (on Android, the server address the app is connected to). Open it at the address the other person will use
(your domain, say, or the machine's name on your network), not `localhost`, or the link won't work
for them. An invitation shows in the list until it's used; **New invite link** replaces a lost or
expired one.

For someone who has set their password, the **…** menu (on Android, tap their row) has:

- **Reset password…** for someone who forgot theirs: a link like the invitation's, where they
  choose a new one. Their old password works until then, and using the link signs them out everywhere.
- **Make admin** / **Remove admin**: admins change server-wide settings such as email, and manage
  people. The server always keeps one admin who can sign in.
- **Remove from server…** signs them out everywhere and deletes the account. Their notes stay in
  `users/<id>.db` on the server, as with `remove-user` below. A shared space they owned goes to
  the member who joined it first.

For an invitation still waiting, it has **New invite link** and **Cancel invitation…**, which
ends the link and frees the username.

You can't change or remove your own account there; another admin can, or the command line.

### From the command line

```bash
docker exec -it notesd notesd add-user --username sam
# systemd
notesd add-user --username sam
```

It asks for the new account's password (or reads `NOTESD_PASSWORD`). Add `--admin` to let them change server-wide settings too. They can then sign in from the web app or a phone.

notesd doesn't send password-reset emails. If someone forgets their password, an admin can send them a reset link from People, or set a new one on the server. That signs them out everywhere:

```bash
docker exec -it notesd notesd reset-password --username sam
# systemd
notesd reset-password --username sam
```

`list-users` and `remove-user` are in the [command line reference](../../reference/cli.md#account-commands).

A phone that's signed out keeps its notes, so only the same account can sign in on it again. To hand a phone to someone else, remove all data from it in Settings first.
