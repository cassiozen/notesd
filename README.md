<p align="center"><img src="apps/web/public/icon-512.png" alt="" width="144" height="144"></p>

<h2 align="center">notesd</h2>
<p align="center">Self-hosted notes, with a web app and native Android and iPhone apps that work offline.</p>
<p align="center"><a href="https://notesd.app">notesd.app</a></p>

<img width="1448" height="1086" alt="notesd in a browser and on a phone" src="https://github.com/user-attachments/assets/9ceabc92-18c3-443d-9e3f-5a8e058d0c4f" />

notesd is a small server you run yourself, on a Raspberry Pi, a home server or a cheap VPS: one
binary with SQLite inside, and nothing else to set up.

## Quick start

```bash
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

Open http://localhost:8080 and create your account right away: until it exists, anyone who can
reach the server can claim it. The [Get started guide](docs/get-started/01-run-the-server.md)
walks through the rest: writing a note, connecting your phone, and what to do next. To run it without Docker, see
[Run the binary with systemd](docs/guides/self-hosting/binary-systemd.md).

## Features

- **Offline-first apps.** The Android and iPhone apps, and the web app if you choose, keep a full
  copy of your notes. Edits made on two devices at once are merged, or kept as a separate copy.
- **Full-text search** across titles and text.
- **Tags** like `#work` anywhere in a note, nested with `/` (`#home/garden`).
- **Spaces** to keep separate contexts, such as work and personal, apart.
- **Locked notes**, encrypted on your device (AES-256-GCM), so the server stores only ciphertext.
  Unlock them with a password, a passkey on the web, or your fingerprint or Face ID.
- **Markdown.** Notes are stored as Markdown. Export them, or keep a folder of `.md` files up to
  date as you write.
- **A few accounts per server,** for family or friends, each with its own notes.

The server runs on 64-bit Linux (x86-64 or ARM64) and Apple silicon Macs. The Android app needs
Android 9 or later and installs from an APK; the iPhone app needs iOS 26 or later and is built
with Xcode.

## Your data and security

- Everything is in one data directory on the server: SQLite databases holding notes and pictures.
  See [Where your data lives](docs/concepts/where-data-lives.md) and
  [Backups](docs/guides/self-hosting/backups.md).
- Regular notes are stored unencrypted on the server. Use disk encryption if that matters to you,
  and HTTPS for anything reachable from the internet. Locked notes are end-to-end encrypted, with
  limits described in the [security model](docs/concepts/security-model.md).
- notesd has no analytics and contacts no other service unless you turn on a feature that needs
  one (emailing notes, or updating from Settings).

## Documentation

Start at the [documentation index](docs/README.md). Most-used pages:

- [Get started](docs/get-started/01-run-the-server.md)
- [Network access and HTTPS](docs/guides/self-hosting/https.md)
- [Command line](docs/reference/cli.md)
- [FAQ](docs/faq.md)

## Contributing

The server is Go and SQLite, with the React web app embedded in the binary. The Android app is
Kotlin and Jetpack Compose, the iPhone app Swift and SwiftUI. See
[Developing notesd](docs/contributing/development.md) to build and test it, and the
[architecture spec](docs/contributing/architecture.md) for how it works.

## License

[MIT](LICENSE)
