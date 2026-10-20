# Hosting notesd

Setting up notesd takes three steps:

1. **[Run the server](#1-run-the-server)**, either [as a binary with systemd](#with-the-binary-and-systemd) or [with Docker](#with-docker).
2. **[Create your account](#2-create-your-account)**.
3. **[Set up HTTPS](#3-https)** with a reverse proxy or tunnel, or [skip it on a private network](#without-a-domain).

You'll need a 64-bit Linux machine (x86-64 or ARM64) (or a Mac on Apple silicon), and either a domain name for HTTPS access or a private LAN/VPN.

## 1. Run the server

### With the binary and systemd

#### Install the binary

Download the binary from the [latest release](https://github.com/cassiozen/notesd/releases/latest):

| Platform | Binary |
| --- | --- |
| Linux x86-64 | `notesd-linux-amd64` |
| Linux ARM64 (including Raspberry Pi 4/5) | `notesd-linux-arm64` |
| macOS Apple silicon | `notesd-darwin-arm64` |

For example, on Linux x86-64:

```bash
VERSION=v0.1.0
curl -fLO https://github.com/cassiozen/notesd/releases/download/$VERSION/notesd-linux-amd64
curl -fLO https://github.com/cassiozen/notesd/releases/download/$VERSION/SHA256SUMS
sha256sum --check --ignore-missing SHA256SUMS
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd
notesd version
```

The binary includes the web app. No additional dependencies are required.

To build from source, see [development.md](development.md).

#### Set up systemd

You can run notesd directly with `notesd serve`, but for a permanent installation, you'll probably want to run it as a systemd service. This starts notesd on boot and automatically restarts it if it crashes.

Create `/etc/systemd/system/notesd.service`:

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

Set `NOTESD_PUBLIC_URL` to the address you'll open notesd at (see [Configuration](#configuration)).

**Notes**:

- `StateDirectory` creates `/var/lib/notesd` with the appropriate permissions and preserves its contents across restarts.
- `DynamicUser` runs notesd as an unprivileged user without requiring a separate system account. 

Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now notesd
```

To check its status or follow the logs:

```bash
sudo systemctl status notesd
sudo journalctl -u notesd -f
```

The default listen address is `127.0.0.1:8080`. For other options, see [Configuration](#configuration).


### With Docker

The image is `ghcr.io/cassiozen/notesd`, for x86-64 and ARM64 (including Raspberry Pi 4/5). Pull it and start a container, with `NOTESD_PUBLIC_URL` set to the address you'll open notesd at (see [Configuration](#configuration)):

```bash
docker pull ghcr.io/cassiozen/notesd:latest
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v notesd-data:/data \
  -e NOTESD_PUBLIC_URL=https://notes.example.com \
  ghcr.io/cassiozen/notesd:latest
docker logs notesd
```

Your notes are kept in the `notesd-data` volume, which Docker creates on first run.

Keep the `docker run` command around: it's how you'll [upgrade](operations.md#docker) or change a setting. The container's flags can't be edited in place, so to change one, remove the container and run it again (`docker rm -f notesd`, then the command with the new flags). The volume, and your notes, are unaffected.

**Notes**:

- The port is published on `127.0.0.1` only, for the [reverse proxy or tunnel](#3-https) on the same machine. To reach notesd directly on a LAN or VPN, publish it as `-p 8080:8080` once your account exists (see [Without a domain](#without-a-domain)).
- Other settings from [Configuration](#configuration) are passed as `-e NAME=value`. Inside the container, `NOTESD_LISTEN` and `NOTESD_DATA_DIR` are already set; leave them alone.
- notesd runs as an unprivileged user (uid 65532). If you'd rather keep the data in a folder (`-v "$PWD/data:/data"`) than a volume, create it with that owner first: `mkdir -p data && sudo chown 65532:65532 data`. Otherwise Docker creates it owned by root and notesd can't write to it.


## 2. Create your account

Open notesd in a browser, or point the Android/iPhone app to the new server and create your account.


> [!IMPORTANT]
> Until the owner account exists, **anyone who can reach the server can create
> it**.
> Create the account now

You can also use environment variables to create the account as the server starts the first time (with Docker, as `-e` flags on `docker run`):

```text
NOTESD_OWNER_USERNAME=me
NOTESD_OWNER_PASSWORD=…
```


## 3. HTTPS

notesd serves plain HTTP, whether it runs in Docker or as a binary. For public access, put it behind a reverse proxy or tunnel and configure `NOTESD_PUBLIC_URL` with the external HTTPS address.

### Option A: Reverse proxy

**Caddy** handles certificates automatically. Add to `/etc/caddy/Caddyfile`:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Or use **nginx** with a certificate provisioned through certbot:

```nginx
server {
    listen 443 ssl;
    server_name notes.example.com;

    ssl_certificate     /etc/letsencrypt/live/notes.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/notes.example.com/privkey.pem;

    client_max_body_size 5m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

The host must be reachable on port 443, with DNS pointing to it. notesd accepts request bodies up to 4 MiB.

### Option B: Tunnel

Tunnels avoid inbound ports and work behind CGNAT. Options include [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/), [ngrok](https://ngrok.com), and [Tailscale Funnel](https://tailscale.com/kb/1223/funnel).

For Cloudflare Tunnel:

1. Install `cloudflared` from [Cloudflare's packages](https://pkg.cloudflare.com/).
2. In the [Zero Trust dashboard](https://one.dash.cloudflare.com), create a Cloudflared tunnel under **Networks → Tunnels**.
3. Install the tunnel service using the provided token:

   ```bash
   sudo cloudflared service install <token>
   ```

4. Add a public hostname pointing to `http://127.0.0.1:8080`.

Your domain's DNS must be managed by Cloudflare. Set `NOTESD_PUBLIC_URL` to the resulting HTTPS address.

Unlike a locally terminated reverse proxy, a provider-terminated tunnel allows the provider to inspect decrypted traffic.

### Proxy considerations

- **Public URL:** Set `NOTESD_PUBLIC_URL` to the external HTTPS URL. This enables `Secure` cookies and makes the address in the first-run log line correct.
- **Root path:** Use a dedicated hostname (`notes.example.com`), not a subpath (`example.com/notes`).
- **Origin validation:** Preserve the `Host` header or configure `NOTESD_PUBLIC_URL`. Otherwise, writes may fail with HTTP 403.
- **Rate limiting:** Authentication is limited to 10 failed attempts per 15 minutes per client IP. Behind a proxy or tunnel, clients may share the same limit, potentially blocking new logins temporarily. Existing sessions are unaffected.

### Without a domain

For LAN or VPN access, you can serve HTTP directly. With the binary, change the listen address in your systemd unit to:

```text
--listen 0.0.0.0:8080
```

With Docker, publish the port on every address instead: `-p 8080:8080` (remove and re-run the container, see [With Docker](#with-docker)).

Docker's published ports bypass host firewalls such as UFW and firewalld, so on a machine with a public IP this exposes notesd to the internet. There, publish on the LAN or VPN address only (`-p 192.168.1.10:8080:8080`), or block the port at your provider's firewall.

Set `NOTESD_PUBLIC_URL` to `http://<server-ip>:8080` and access notesd at that address.

Plain HTTP sends credentials without transport encryption and uses cookies without the `Secure` flag. Use it only on networks you trust.

The Android app permits HTTP connections to private IPv4 ranges (`192.168.x`, `10.x`, `172.16–31.x`), `localhost`, and `*.local`. Other addresses require HTTPS.

#### Tailscale

If you use Tailscale, you can expose notesd over HTTPS within your tailnet without a separate reverse proxy:

```bash
tailscale serve --bg 8080
```

Set `NOTESD_PUBLIC_URL` to `https://<machine>.<tailnet>.ts.net`.

## Configuration

Configuration is available through CLI flags or environment variables. No configuration file is required. With Docker, use the environment variables.

| Flag | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` (Docker: `/data`) | Database directory |
| `--listen` | `NOTESD_LISTEN` | `127.0.0.1:8080` (Docker: `0.0.0.0:8080`) | Listen address |
| `--public-url` | `NOTESD_PUBLIC_URL` | Unset | External URL; HTTPS enables `Secure` cookies |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` | `text` or `json` |
| | `NOTESD_OWNER_USERNAME`, `NOTESD_OWNER_PASSWORD` | Unset | Create the owner account at startup if there is none ([details](#2-create-your-account)) |

For memory-constrained hosts, `GOMEMLIMIT` can reduce Go's memory usage by triggering garbage collection earlier:

```ini
Environment=GOMEMLIMIT=64MiB
```

With Docker, add `-e GOMEMLIMIT=64MiB` to `docker run`.

## Next steps

- [Install the Android app](android.md)
- [Upgrades and maintenance](operations.md)
