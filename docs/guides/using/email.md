---
title: Emailing notes
description: Set up Resend once on the server, then email any note from its menu.
---

# Emailing notes

notesd can email a note, formatted, through [Resend](https://resend.com). An admin sets it up
once, for everyone on the server.

## Set it up (admins)

1. In Resend, [verify a domain](https://resend.com/docs/dashboard/domains/introduction) you'll
   send from, and create an API key with sending access.
2. In notesd, open **Settings → Integrations**, paste the API key, and enter the address emails
   come from, on the domain you verified, such as `notes@example.com`.

The key is stored on the server and never shown again; Settings shows its last four characters.
Every account on the server sends with it, but only admins can change it. The first account is an
admin; [`notesd add-user --admin`](../../reference/cli.md#add-user) makes more.

notesd doesn't test the key when you save it. If it's wrong, the first email fails with Resend's
explanation.

## Send a note

| App | How |
| --- | --- |
| Web | **Email…** in the note's menu |
| Android | Share the note and choose **Email** |
| iPhone | **Email…** in the note's ••• menu |

Enter up to 10 recipients. The server sends the note as it has it after the last sync: formatted
as HTML, with the Markdown as plain text, and the title as the subject. Pictures are included, as
many as fit in the email's size limit. The app has to reach the server to send, so emailing
doesn't work offline.

[Locked notes](locked-notes.md) can't be emailed: the server can't read them.
