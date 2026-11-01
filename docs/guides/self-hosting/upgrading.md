---
title: Upgrading
description: Check your version, upgrade the binary or the Docker image, or let admins update from Settings.
---

# Upgrading

To see which version you run, open **Settings → Server** in the web app, run
`notesd version` (`docker exec notesd notesd version` with Docker), or look
for `notesd started` in the logs.

New versions are on the [releases page](https://github.com/cassiozen/notesd/releases).
To hear about them, choose **Watch → Custom → Releases** on GitHub, or
subscribe to its feed: `https://github.com/cassiozen/notesd/releases.atom`.

To upgrade the binary ([systemd setup](binary-systemd.md#keep-it-running-with-systemd)),
back up the data directory, then replace the binary and restart:

```bash
sudo systemctl stop notesd
sudo cp -a /var/lib/private/notesd ~/notesd-backup   # DynamicUser keeps it here
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd
sudo systemctl start notesd
notesd version
```

With Docker, see [Docker](#docker). A new version updates the database
when it starts. An older binary can't open a database a newer one has
updated, so to go back, restore the backup too.

## Docker

To upgrade to the latest release, pull the image, then replace the container
with the same flags you started it with:

```bash
docker pull ghcr.io/cassiozen/notesd:latest
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

To stay on one release line, use its tag instead of `latest`, e.g.
`ghcr.io/cassiozen/notesd:0.2`.

## From a single-account version

A data directory from before there were
several accounts (a single `notes.db`) is moved to this layout the first
time a newer notesd starts: the owner becomes the first account, an admin,
and stays signed in everywhere. Stop the old server first.

## Updating from Settings

With the systemd setup, admins can update from **Settings → Server** in the
web app instead. notesd can't replace its own binary, so it only leaves an
`update-request` file in its data directory, and a root service,
[`scripts/notesd-update`](../../../scripts/notesd-update), does the rest. That
service:

- installs the newest release from GitHub, never an older one, and only if
  it matches the release's `SHA256SUMS`;
- backs up the data directory and binary to `/var/backups/notesd` first;
- puts both back if the new version doesn't start;
- keeps the newest three backups and deletes older ones.

To set it up, download the script from the same release as your binary
and check it against that release's `SHA256SUMS`, then install it:

```bash
v=$(notesd version | awk '{print $2}')
curl -fLO "https://github.com/cassiozen/notesd/releases/download/$v/notesd-update"
curl -fLO "https://github.com/cassiozen/notesd/releases/download/$v/SHA256SUMS"
grep ' notesd-update$' SHA256SUMS | sha256sum -c -
sudo install -m 755 notesd-update /usr/local/sbin/notesd-update
```

Create `/etc/systemd/system/notesd-update.service`:

```ini
[Unit]
Description=Update notesd to the newest release

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/notesd-update /var/lib/private/notesd
```

Create `/etc/systemd/system/notesd-update.path`:

```ini
[Unit]
Description=Update notesd when an admin asks in Settings

[Path]
PathExists=/var/lib/private/notesd/update-request

[Install]
WantedBy=multi-user.target
```

Add `Environment=NOTESD_SELF_UPDATE=true` under `[Service]` in
`notesd.service`, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now notesd-update.path
sudo systemctl restart notesd
```

When a newer release is out, Settings → Server offers it to admins. The
page reloads on the new version once it's running. Follow an update with
`journalctl -u notesd-update -f`. Docker doesn't support this: use
[Docker](#docker) instead.
