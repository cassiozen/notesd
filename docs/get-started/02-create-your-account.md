---
title: 2. Create your account
description: Create the first account on your new server, before anyone else can reach it.
---

# 2. Create your account

> [!IMPORTANT]
> Until the first account exists, anyone who can reach the server can create it and become its
> admin. Create it now, while notesd only listens on the machine itself.

On the server:

```bash
read -rsp 'Password: ' pw; echo
printf '%s\n' "$pw" | docker exec -i notesd notesd create-owner --username YOUR_USERNAME
unset pw
```

The password needs at least 8 characters. Reading it this way keeps it off the screen, out of your
shell history and out of `docker inspect`.

```text
owner account created
```

Prefer the browser? Forward the port and sign up at `http://localhost:8080` instead:

```bash
ssh -L 8080:127.0.0.1:8080 you@server
```

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
