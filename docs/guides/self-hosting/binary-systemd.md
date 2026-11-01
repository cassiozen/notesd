---
title: Run the binary with systemd
description: Download the notesd binary, run it, and keep it running with systemd.
---

# Run the binary with systemd

notesd runs on 64-bit Linux (x86-64 or ARM64, including Raspberry Pi 4/5) and Apple silicon Macs. This page runs it as a single binary. To use a container instead, see [Run with Docker](docker.md).

## Download and run

Download the binary for your machine from the [latest release](https://github.com/cassiozen/notesd/releases/latest):

| Platform | Binary |
| --- | --- |
| Linux x86-64 | `notesd-linux-amd64` |
| Linux ARM64 (including Raspberry Pi 4/5) | `notesd-linux-arm64` |
| macOS Apple silicon | `notesd-darwin-arm64` |

To try it, make it executable and start it (use the file name you downloaded):

```bash
chmod +x notesd-linux-amd64
./notesd-linux-amd64 serve
```

It listens on `127.0.0.1:8080`, so only this machine can reach it, and stores notes in `./data`.
To run it for real, [install it as a service](#keep-it-running-with-systemd), then
[create your account](users.md#create-your-account) and put [HTTPS](https.md) in front.

To build from source, see [Development](../../contributing/development.md).

## Keep it running with systemd

To start notesd on boot and restart it if it crashes, install the binary you downloaded:

```bash
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd   # notesd-linux-arm64 on ARM64
```

Then create `/etc/systemd/system/notesd.service`:

```ini
[Unit]
Description=notesd

[Service]
ExecStart=/usr/local/bin/notesd serve --data-dir /var/lib/notesd
Environment=NOTESD_PUBLIC_URL=https://notes.example.com
DynamicUser=yes
StateDirectory=notesd
StateDirectoryMode=0700
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then enable it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now notesd
```

Check on it with `sudo systemctl status notesd`, or follow the logs with `sudo journalctl -u notesd -f`.

notesd listens on `127.0.0.1:8080`, for a reverse proxy or `tailscale serve` on the same machine.
Set `NOTESD_PUBLIC_URL` to the HTTPS address it serves. To serve plain HTTP to a network you trust
instead, see [Limiting who can reach notesd](https.md#limiting-who-can-reach-notesd).
