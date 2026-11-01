---
title: notesd documentation
description: Guides and reference for running and using notesd.
---

# notesd documentation

New to notesd? **[Get started](get-started/01-run-the-server.md)**: run the server, create your
account, put HTTPS in front, write a note and sync your phone, in about fifteen minutes.

## I want to…

- **Run notesd:** [with Docker](guides/self-hosting/docker.md) or [as a binary with systemd](guides/self-hosting/binary-systemd.md)
- **Reach it from my devices:** [Network access and HTTPS](guides/self-hosting/https.md)
- **Install an app:** [web](guides/apps/web.md), [Android](guides/apps/android.md), [iPhone](guides/apps/iphone.md)
- **Keep a note private, even from the server:** [Locked notes](guides/using/locked-notes.md)
- **Back up, or restore:** [Backups](guides/self-hosting/backups.md)
- **Add someone, or reset a password:** [Accounts](guides/self-hosting/users.md)
- **Get my notes out as Markdown:** [Markdown mirror and export](guides/self-hosting/markdown-mirror-and-export.md)
- **Look up a command or setting:** [Command line](reference/cli.md)
- **Look up an endpoint:** [HTTP API](reference/api.md)
- **Find a quick answer:** [FAQ](faq.md)

## Get started

1. [Run the server](get-started/01-run-the-server.md)
2. [Create your account](get-started/02-create-your-account.md)
3. [Set up HTTPS](get-started/03-set-up-https.md)
4. [Write your first note](get-started/04-write-your-first-note.md)
5. [Add your phone](get-started/05-add-your-phone.md)

## Using notesd

- [Writing and Markdown](guides/using/writing-and-markdown.md): formatting, pictures, deleting, and how notes are stored as Markdown
- [Tags, spaces and search](guides/using/organize-and-find.md)
- [Locked notes](guides/using/locked-notes.md): end-to-end encrypted notes
- [Emailing notes](guides/using/email.md)
- [Offline and sync](guides/using/offline-and-sync.md): what works offline, conflicts, lost devices
- [Settings](guides/using/settings.md)

## Apps

- [Web app](guides/apps/web.md): keeping notes offline, installing it
- [Android](guides/apps/android.md): installing, connecting
- [iPhone](guides/apps/iphone.md): building, installing, connecting

## Self-hosting

- [Run with Docker](guides/self-hosting/docker.md)
- [Run the binary with systemd](guides/self-hosting/binary-systemd.md)
- [Network access and HTTPS](guides/self-hosting/https.md)
- [Accounts](guides/self-hosting/users.md)
- [Backups](guides/self-hosting/backups.md)
- [Upgrading](guides/self-hosting/upgrading.md)
- [Markdown mirror and export](guides/self-hosting/markdown-mirror-and-export.md)
- [Monitoring and logs](guides/self-hosting/monitoring-and-logs.md)

## Concepts

- [How sync works](concepts/how-sync-works.md): revisions, conflicts, deletions, restores
- [Security model](concepts/security-model.md): what the server can read, and what locked notes protect
- [Where your data lives](concepts/where-data-lives.md): the data directory, and why SQLite

## Reference

- [Command line](reference/cli.md): commands, flags and environment variables
- [HTTP API](reference/api.md): endpoints, authentication, errors
- [FAQ](faq.md)

## Contributing

- [Development](contributing/development.md): building from source and working on notesd
- [Architecture](contributing/architecture.md): the specification (data model, sync protocol, conflicts, security)
- [Settings guidelines](contributing/settings-guidelines.md): how Settings is organized, and where a new setting goes
- [Releasing](contributing/releasing.md)
