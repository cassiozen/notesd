---
title: 2. Create your account
description: Create the first account on your new server, before anyone else can.
---

# 2. Create your account

> [!IMPORTANT]
> Until the first account exists, anyone who can reach the server can create it and become its
> admin. Do this step right away.

1. Open [http://localhost:8080](http://localhost:8080) in a browser on the same computer. From
   another computer on your network, use the server's address instead, such as
   `http://192.168.1.20:8080`.
2. notesd sees that it has no account yet and shows **A quiet place for everything you write.**
   Click **Get started**.
3. Choose a **Username** and a **Password** of at least 8 characters, type the password again
   under **Confirm password**, and click **Create account**.

You're signed in, looking at an empty list of notes.

This first account is the server's **admin**. It can change settings that apply to everyone on the
server, such as [emailing notes](../guides/apps/iphone.md#email), and you can
[add accounts](../guides/self-hosting/users.md#adding-people) for other people later. Each account
has its own notes, which no other account can see.

> [!TIP]
> notesd can't email you a password reset. If you forget your password, set a new one on the
> server:
>
> ```bash
> docker exec -it notesd notesd reset-password
> ```

**Next:** [3. Write your first note](03-write-your-first-note.md)
