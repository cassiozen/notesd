---
title: Accounts
description: Create the first account, add people, and reset passwords.
---

# Accounts

## Create your account

> [!IMPORTANT]
> Until the account exists, anyone who can reach the server can create it. Create it while notesd
> listens on the machine only, before you put [HTTPS](https.md) in front.

On the server, with [`create-owner`](../../reference/cli.md#create-owner):

```bash
printf 'Password: '; IFS= read -rs pw; echo
printf '%s\n' "$pw" | docker exec -i notesd notesd create-owner --username me
# systemd, with notesd-run from the command line reference
printf '%s\n' "$pw" | notesd-run create-owner --username me
unset pw
```

With systemd, define [`notesd-run`](../../reference/cli.md#with-systemd) first. On a server that
serves plain HTTP to your LAN, you can also sign up from the web app or the Android or iPhone app
directly.

To create it when the server first starts instead, set `NOTESD_OWNER_USERNAME` and
`NOTESD_OWNER_PASSWORD` (with Docker, as `-e` flags). The password then stays in the container's
or unit's configuration.

This first account is an admin: it can set up [emailing notes](../using/email.md) for everyone.

## Adding people

notesd can hold a few accounts, such as for family or friends. Each has its own notes, spaces, locked notes and pictures, which no other account can see. Accounts are added on the server, not from the apps:

```bash
docker exec -it notesd notesd add-user --username sam
# systemd
notesd-run add-user --username sam
```

It asks for the new account's password (or reads `NOTESD_PASSWORD`). Add `--admin` to let them change server-wide settings too. They can then sign in from the web app or a phone.

notesd doesn't send password-reset emails. If someone forgets their password, set a new one for them. That signs them out everywhere:

```bash
docker exec -it notesd notesd reset-password --username sam
# systemd
notesd-run reset-password --username sam
```

`list-users` and `remove-user` are in the [command line reference](../../reference/cli.md#account-commands).

A phone that's signed out keeps its notes, so only the same account can sign in on it again. To hand a phone to someone else, remove all data from it in Settings first.
