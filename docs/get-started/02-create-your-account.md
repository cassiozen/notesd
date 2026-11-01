---
title: 2. Create your account
description: Create the first account on your new server, before anyone else can reach it.
---

# 2. Create your account

> [!IMPORTANT]
> Until the first account exists, anyone who can reach the server can create it and become its
> admin. Create it now, while notesd only listens on the machine itself.

### If you're using a home server and it has a browser:

1. Open [http://localhost:8080](http://localhost:8080).
2. notesd sees that it has no account yet. Click **Get started**.
3. Choose a **Username** and a **Password**

You're signed in, looking at an empty list of notes.

### Otherwise, create the account via command line:

Use `notesd create-owner`:

**If you're using Docker:**

```bash
printf 'Password: '; IFS= read -rs pw; echo
printf '%s\n' "$pw" | docker exec -i notesd notesd create-owner --username YOUR_USERNAME
unset pw
```

** Otherwise**:

```bash
notesd create-owner --username YOUR_USERNAME
```

It will ask for your password next.

**Either way:**

The command should return:

```text
owner account created
```

The password needs at least 8 characters. Reading it this way keeps it off the screen, out of your
shell history and out of `docker inspect`. With the systemd setup, pipe it to
[`notesd-run create-owner`](../reference/cli.md#with-systemd) instead.

This first account is the server's **admin**. It can change settings that apply to everyone on the
server, such as [emailing notes](../guides/using/email.md), and you can
[add accounts](../guides/self-hosting/users.md#adding-people) for other people later. Each account
has its own notes, which no other account can see.

> [!TIP]
> notesd can't email you a password reset. If you forget your password, set a new one on the
> server:
>
> ```bash
> docker exec -it notesd notesd reset-password --username YOUR_USERNAME
> ```

**Next:** [3. Set up HTTPS](03-set-up-https.md)
