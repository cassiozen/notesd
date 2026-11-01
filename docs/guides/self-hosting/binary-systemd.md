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

Make it executable and start it (use the file name you downloaded):

```bash
chmod +x notesd-linux-amd64
./notesd-linux-amd64 serve --listen 0.0.0.0:8080
```

Open `http://localhost:8080` and [create your account](users.md#create-your-account).

By default the binary only accepts connections from this machine. `--listen 0.0.0.0:8080` lets your other devices reach it. Notes are stored in `./data`.

That's all you need on a home network. The rest of this page covers [keeping notesd running](#keep-it-running-with-systemd). To limit who can reach it or use it outside your network, see [Network access and HTTPS](https.md).

To build from source, see [Development](../../contributing/development.md).

## Keep it running with systemd

To start notesd on boot and restart it if it crashes, install the binary:

```bash
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd
```

Then create `/etc/systemd/system/notesd.service`:

```ini
[Unit]
Description=notesd

[Service]
ExecStart=/usr/local/bin/notesd serve --listen 0.0.0.0:8080 --data-dir /var/lib/notesd
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
