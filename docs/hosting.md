# Hosting notesd

notesd runs on 64-bit Linux (x86-64 or ARM64, including Raspberry Pi 4/5) and Apple silicon Macs. Run it as a single binary or [with Docker](#running-with-docker).

## Quick start

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

Open `http://localhost:8080` and [create your account](#create-your-account).

By default the binary only accepts connections from this machine. `--listen 0.0.0.0:8080` lets your other devices reach it. Notes are stored in `./data`.

That's all you need on a home network. The rest of this page covers [keeping notesd running](#keep-it-running-with-systemd), [limiting access](#limiting-who-can-reach-notesd) and [using notesd outside your network](#access-from-anywhere-with-https).

To build from source, see [development.md](development.md).

### Keep it running with systemd

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

## Running with Docker

Alternatively, you can use Docker:

```bash
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

Open the same addresses as in the [quick start](#quick-start), then [create your account](#create-your-account). Your notes are stored in the `notesd-data` Docker volume. To change a setting later, see [Changing Docker settings](#changing-docker-settings).

## Create your account

Open notesd in a browser, or enter the server address in the Android or iPhone app, and sign up.

> [!IMPORTANT]
> Until the account exists, anyone who can reach the server can create it. Do this right after starting notesd.

You can also create the account when the server first starts by setting these variables (with Docker, as `-e` flags):

```text
NOTESD_OWNER_USERNAME=me
NOTESD_OWNER_PASSWORD=…
```

This first account is an admin: it can set up [emailing notes](settings.md) for everyone.

## Adding people

notesd can hold a few accounts, such as for family or friends. Each has its own notes, spaces, locked notes and pictures, which no other account can see. Accounts are added on the server, not from the apps:

```bash
sudo -u notesd notesd add-user --username sam --data-dir /var/lib/notesd
# with Docker
docker exec -it notesd notesd add-user --username sam
```

It asks for the new account's password (or reads `NOTESD_PASSWORD`). Add `--admin` to let them change server-wide settings too. They can then sign in from the web app or a phone.

notesd doesn't send password-reset emails. If someone forgets their password, set a new one for them. That signs them out everywhere:

```bash
sudo -u notesd notesd reset-password --username sam --data-dir /var/lib/notesd
# with Docker
docker exec -it notesd notesd reset-password --username sam
```

`list-users` and `remove-user` are in [operations.md](operations.md#maintenance-commands).

A phone that's signed out keeps its notes, so only the same account can sign in on it again. To hand a phone to someone else, remove all data from it in Settings first.

## Limiting who can reach notesd

The quick start accepts connections on every network the machine is connected to. That's fine at home, but not on a server with a public IP.

| Reachable from | Docker | Binary |
| --- | --- | --- |
| Every network (quick start) | `-p 8080:8080` | `--listen 0.0.0.0:8080` |
| One network, such as your LAN or VPN | `-p 192.168.1.10:8080:8080` | `--listen 192.168.1.10:8080` |
| This machine only (use with HTTPS below) | `-p 127.0.0.1:8080:8080` | `--listen 127.0.0.1:8080` |

Replace `192.168.1.10` with the machine's address on that network. To change Docker flags, see [Changing Docker settings](#changing-docker-settings).

> [!WARNING]
> Docker's published ports bypass host firewalls such as UFW and firewalld. On a machine with a public IP, `-p 8080:8080` exposes notesd to the internet.

Without HTTPS, your password and notes are sent unencrypted, so use plain HTTP only on networks you trust. The apps allow plain HTTP only to private addresses (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`, `*.local`). Anything else needs HTTPS.

## Access from anywhere with HTTPS

notesd serves plain HTTP. To use it outside your network, put HTTPS in front of it:

1. Make notesd reachable from [this machine only](#limiting-who-can-reach-notesd).
2. Set `NOTESD_PUBLIC_URL` to the HTTPS address, such as `https://notes.example.com`. With Docker, add `-e NOTESD_PUBLIC_URL=…`. With systemd, add `Environment=NOTESD_PUBLIC_URL=…` under `[Service]`.
3. Set up one of the options below.

### Tailscale

If your devices use Tailscale, this is the simplest option. It needs no domain, and notesd stays private to your tailnet:

```bash
tailscale serve --bg 8080
```

The address is `https://<machine>.<tailnet>.ts.net`.

### Caddy

Caddy gets certificates automatically. Add to `/etc/caddy/Caddyfile`:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

The machine must be reachable on port 443, with your domain's DNS pointing to it.

### nginx

With a certificate from certbot:

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

### Cloudflare Tunnel

A tunnel needs no open ports and works behind CGNAT. Your domain's DNS must be on Cloudflare. Note that Cloudflare can see your traffic unencrypted.

1. Install `cloudflared` from [Cloudflare's packages](https://pkg.cloudflare.com/).
2. In the [Zero Trust dashboard](https://one.dash.cloudflare.com), create a tunnel under **Networks → Tunnels**.
3. Install it with the token it gives you: `sudo cloudflared service install <token>`.
4. Add a public hostname pointing to `http://127.0.0.1:8080`.

ngrok and Tailscale Funnel work too.

### Good to know

- Use a dedicated hostname (`notes.example.com`), not a path (`example.com/notes`).
- If saving fails with HTTP 403, check that `NOTESD_PUBLIC_URL` matches the address you open, or that your proxy forwards the `Host` header.
- After 10 failed sign-ins in 15 minutes from one IP, notesd blocks sign-ins from that IP for a while. Behind a proxy, all clients may share one IP. People who are already signed in aren't affected.
- Uploads are limited to 4 MiB.

## Changing Docker settings

You can't change the flags of an existing container. Remove it and run it again with the new flags. Your notes stay in the volume. For example, to switch to HTTPS behind a proxy:

```bash
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v notesd-data:/data \
  -e NOTESD_PUBLIC_URL=https://notes.example.com \
  ghcr.io/cassiozen/notesd:latest
```

Keep your `docker run` command somewhere; you'll need it for [upgrades](operations.md#docker).

- Pass any [setting](#configuration) as `-e NAME=value`. Don't change `NOTESD_LISTEN` or `NOTESD_DATA_DIR`; use `-p` and `-v` instead.
- To store notes in a folder instead of a volume (`-v "$PWD/data:/data"`), create it first with `mkdir -p data && sudo chown 65532:65532 data`. notesd runs as user 65532 and can't write to a folder owned by root.

## Configuration

Set options with flags or environment variables. With Docker, use environment variables.

| Flag | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` (Docker: `/data`) | Database directory |
| `--listen` | `NOTESD_LISTEN` | `127.0.0.1:8080` (Docker: `0.0.0.0:8080`) | Listen address |
| `--public-url` | `NOTESD_PUBLIC_URL` | Unset | External URL; HTTPS enables `Secure` cookies |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` | `text` or `json` |
| | `NOTESD_OWNER_USERNAME`, `NOTESD_OWNER_PASSWORD` | Unset | Create the account at startup ([details](#create-your-account)) |

On machines with little memory, `GOMEMLIMIT=64MiB` makes notesd use less memory.

## Next steps

- [Install the Android app](android.md)
- [Upgrades and maintenance](operations.md)
