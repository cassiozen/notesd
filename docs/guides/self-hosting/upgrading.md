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
back up the data directory, then replace the binary and restart. If your unit still has
`DynamicUser=yes`, [switch it first](#systemd-the-service-runs-as-you).

```bash
sudo systemctl stop notesd
cp -a /var/lib/notesd ~/notesd-backup-$(date +%Y%m%d-%H%M%S)
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd   # notesd-linux-arm64 on ARM64
sudo systemctl start notesd
notesd version
```

With Docker, see [Docker](#docker). A new version updates the database
when it starts. An older binary can't open a database a newer one has
updated, so to go back, restore the backup too.

## Docker

To upgrade to the latest release, pull the image, then replace the container
with the same flags you started it with. The command below uses the ones from
[Run with Docker](docker.md#start-the-container); keep your own port binding and
settings:

```bash
docker pull ghcr.io/cassiozen/notesd:latest
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

To stay on one release line, use its tag instead of `latest`, e.g.
`ghcr.io/cassiozen/notesd:0.2`.

## Changes that need action

### systemd: the service runs as you

The [systemd setup](binary-systemd.md#keep-it-running-with-systemd) used to have
`DynamicUser=yes`, which kept the data in `/var/lib/private/notesd` and meant every `notesd`
command had to go through a `notesd-run` shell function. It now runs as your own user, so you can
run commands directly. The rest of these docs (backups, upgrading) assume the new setup, so switch
before following them:

1. Stop the server: `sudo systemctl stop notesd`
2. In `/etc/systemd/system/notesd.service`, replace `DynamicUser=yes` with
   `User=YOUR_USERNAME`.
3. If you [update from Settings](#updating-from-settings), change `/var/lib/private/notesd` to
   `/var/lib/notesd` in `notesd-update.service` and `notesd-update.path`.
4. Move the data out of `/var/lib/private` and make it yours, then start again:

   ```bash
   sudo rm /var/lib/notesd   # only a link to the private directory
   sudo mv /var/lib/private/notesd /var/lib/notesd
   sudo chown -R YOUR_USERNAME: /var/lib/notesd
   sudo systemctl daemon-reload
   sudo systemctl start notesd
   sudo systemctl restart notesd-update.path   # only if you update from Settings
   ```

5. Set `NOTESD_DATA_DIR` in your shell, as in the
   [setup](binary-systemd.md#keep-it-running-with-systemd), and delete `notesd-run` if you
   defined it.

### 1.0: update the apps too

1.0 changes how the apps handle sync errors, versions and locked notes, so update every phone and
browser along with the server. 0.x apps keep working with a 1.0 server, but only 1.0 apps:

- keep a change that a proxy or Wi-Fi portal answered instead of notesd, rather than dropping it;
- recover changes that older apps dropped this way, the first time they open;
- understand later versions of locked notes and lock passwords, instead of showing them as text or
  saying "wrong password".

If you update from Settings, install `notesd-update` again from 1.0: see
[Updating from Settings](#updating-from-settings).

### 1.0: `NOTESD_PUBLIC_URL` and `NOTESD_LISTEN` removed

notesd now works out where it's served from on its own. After upgrading:

- **systemd:** remove `--public-url` from `ExecStart` if it's there, or notesd won't start. You can
  delete `Environment=NOTESD_PUBLIC_URL=…` too; it's ignored.
- **If you set `NOTESD_LISTEN`:** use `--listen ADDR` instead. Without it, notesd falls back to
  `127.0.0.1:8080`, or to every interface at `$PORT` when `PORT` is set.
- **nginx:** add `proxy_set_header X-Forwarded-Proto $scheme;` to the `location` block, as in
  [the example](https.md#nginx). Without it, sign-in still works but session cookies aren't
  marked `Secure`.
- **Docker without `-v`:** the image no longer declares a volume. If you started the container
  without `-v`, your notes are in an anonymous volume that a new container won't see. **Before**
  you remove the old container, find the volume's name, then start the new container with
  `-v <that name>:/data`:

  ```bash
  docker inspect -f '{{range .Mounts}}{{.Name}} {{.Destination}}{{end}}' notesd
  ```

  Already removed it? `docker rm -f` keeps anonymous volumes, so your notes are still there. List
  the unused ones, and look for the one holding `system.db`:

  ```bash
  docker volume ls -q -f dangling=true
  docker run --rm -v <name>:/data busybox ls /data
  ```
- **Docker, Caddy, Tailscale and Cloudflare Tunnel:** nothing to change. You can drop `-e NOTESD_PUBLIC_URL=…` the
  next time you recreate the container.

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

- installs the newest release from GitHub with the same major version as the
  one running (0.x stays on 0.x, 1.x on 1.x), never an older one, and only if
  it matches the release's `SHA256SUMS`;
- backs up the data directory and binary to `/var/backups/notesd` first;
- puts both back if the new version doesn't start;
- keeps the newest three backups and deletes older ones.

A new major version can need steps of its own, so it's never installed from
Settings: Settings says when one is out, and you upgrade by hand after reading
its notes on this page.

> [!IMPORTANT]
> A copy of `notesd-update` from before 1.0 installs whatever release is
> newest, including a new major version. Install it again from 1.0 or later,
> as below.

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
ExecStart=/usr/local/sbin/notesd-update /var/lib/notesd
```

Create `/etc/systemd/system/notesd-update.path`:

```ini
[Unit]
Description=Update notesd when an admin asks in Settings

[Path]
PathExists=/var/lib/notesd/update-request

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
