---
title: Accounts
description: Create the first account, add people, and reset passwords.
---

# Accounts

## Create your account

Open notesd in a browser, or enter the server address in the Android or iPhone app, and sign up.

> [!IMPORTANT]
> Until the account exists, anyone who can reach the server can create it. Do this right after starting notesd.

You can also create the account when the server first starts by setting these variables (with Docker, as `-e` flags):

```text
NOTESD_OWNER_USERNAME=me
NOTESD_OWNER_PASSWORD=…
```

This first account is an admin: it can set up [emailing notes](../apps/iphone.md#email) for everyone.

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

`list-users` and `remove-user` are in the [command line reference](../../reference/cli.md#commands).

A phone that's signed out keeps its notes, so only the same account can sign in on it again. To hand a phone to someone else, remove all data from it in Settings first.
