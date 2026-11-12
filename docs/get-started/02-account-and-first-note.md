---
title: 2. Account and your first note
description: Sign in to your new server, write a note, tag it, and find it again with search.
---

# 2. Account and your first note

<WherePicker />

<div data-where="home vps">

## Sign in

Open your address from [step 1](01-run-the-server.md), such as `https://notes.example.com`, and
sign in with the account you created there.

If saving a note fails with HTTP 403, your proxy changes the `Host` header without sending
`X-Forwarded-Host`. Caddy and Cloudflare Tunnel keep it as it is; see
[Network access and HTTPS](../guides/self-hosting/https.md#good-to-know) for others.

notesd can't email you a password reset. If you forget your password, set a new one on the server:

```bash
docker exec -it notesd notesd reset-password --username YOUR_USERNAME
```

</div>

<div data-where="fly">

## Sign in

Open `https://my-notes.fly.dev` and sign in with the username and password from the secrets in
[step 1](01-run-the-server.md). Once you have, you can remove the secrets; the account stays, and
the server restarts:

```bash
fly secrets unset NOTESD_OWNER_USERNAME NOTESD_OWNER_PASSWORD
```

notesd can't email you a password reset. If you forget your password, set a new one from the
folder with `fly.toml`:

```bash
fly ssh console -u nonroot --pty -C "notesd reset-password --username YOUR_USERNAME"
```

</div>

## Your account

This first account is the server's **admin**. It can change settings that apply to everyone on the
server, such as [emailing notes](../guides/using/email.md), and you can
[add accounts](../guides/self-hosting/users.md#adding-people) for other people later. Each account
has its own notes, which no other account can see.

## Write a note

The first line of a note is always its title. There's no Save button: notes save as you type.

## Tag

Add a tag anywhere in the title or text by typing `#` and a name (tags aren't picked up inside
links or code):

```text
#recipes #breakfast
```

Some tag forms:

| Write | For |
| --- | --- |
| `#recipes` | A one-word tag |
| `#home/garden` | A tag nested under `#home` |
| `#to read#` | A tag with spaces, closed with a second `#` |

The sidebar lists every tag you use (if it's hidden, click **Show sidebar** at the bottom of the list; in the Mac app, beside the window buttons, except in full screen).
Click one to show only its notes. A parent tag such as `#home` also shows the notes tagged
`#home/garden`.

## Search

Type in **Search all notes** above the list. Search covers titles and text, matches the
beginning of words (`panc` finds a note titled "Pancakes"). When the match is in a note's text, the result
shows the text around it.

- **Enter** opens the first result.
- **Escape** clears the search.

**Next:** [3. Install the apps](03-install-the-apps.md)
