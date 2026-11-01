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
read -rsp 'Password: ' pw; echo
printf '%s\n' "$pw" | docker exec -i notesd notesd create-owner --username me
# systemd
printf '%s\n' "$pw" | sudo -u notesd notesd create-owner --username me --data-dir /var/lib/notesd
unset pw
```

Or sign up in a browser through an SSH tunnel (`ssh -L 8080:127.0.0.1:8080 you@server`, then open
`http://localhost:8080`). On a LAN-only server, you can also sign up from the web app or the
Android or iPhone app directly.

To create it when the server first starts instead, set `NOTESD_OWNER_USERNAME` and
`NOTESD_OWNER_PASSWORD` (with Docker, as `-e` flags). The password then stays in the container's
or unit's configuration.

This first account is an admin: it can set up [emailing notes](../using/email.md) for everyone.

## Adding people

notesd can hold a few accounts, such as for family or friends. Each has its own notes, spaces, locked notes and pictures, which no other account can see. Accounts are added on the server, not from the apps:

```bash
sudo -u notesd notesd add-user --username sam --data-dir /var/lib/notesd
# with Docker
docker exec -it notesd notesd add-user --username sam
```

It asks for the new account's password (or reads `NOTESD_PASSWORD`). Add `--admin` to let them change server-wide settings too. They can then sign in from the web app or a phone.

notesd doesn't send password-reset emails. If someone forgets their password, set a new one for them. That signs them out everywhere:

```bash
sudo -u notesd notesd reset-password --username sam --data-dir /var/lib/notesd
# with Docker
docker exec -it notesd notesd reset-password --username sam
```

`list-users` and `remove-user` are in the [command line reference](../../reference/cli.md#account-commands).

A phone that's signed out keeps its notes, so only the same account can sign in on it again. To hand a phone to someone else, remove all data from it in Settings first.
