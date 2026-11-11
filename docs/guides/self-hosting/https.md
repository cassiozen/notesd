---
title: Network access and HTTPS
description: Put HTTPS in front of notesd, and limit who can reach it.
---

# Network access and HTTPS

notesd serves plain HTTP. Run it on the machine's loopback interface and put HTTPS in front with
a reverse proxy or Tailscale on the same machine. That's the setup the
[Docker](docker.md) and [systemd](binary-systemd.md) guides use.

## Access from anywhere with HTTPS

1. Make sure notesd listens on [this machine only](#limiting-who-can-reach-notesd).
2. [Create your account](users.md#create-your-account) first. Behind a proxy on the same machine,
   notesd can't tell you from anyone else who reaches the HTTPS address.
3. Set up one of the options below. notesd learns that it's served over HTTPS from the proxy's
   `X-Forwarded-Proto` header, and then makes session cookies `Secure`.

For Caddy and nginx, point a DNS `A` (and `AAAA`) record at the machine's public IP and open
ports 80 and 443: `sudo ufw allow 80,443/tcp`, plus your provider's firewall or security group if
it has one, or port forwarding on a home router.

### Caddy

Caddy gets and renews certificates automatically. [Install it](https://caddyserver.com/docs/install), then add to `/etc/caddy/Caddyfile`:

```caddyfile
notes.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Then `sudo systemctl reload caddy`.

### Tailscale

If your devices use Tailscale, this needs no domain and no open ports,
and notesd stays private to your tailnet. Turn on MagicDNS and HTTPS certificates in the tailnet's
DNS settings, then:

```bash
sudo tailscale serve --bg 8080
```

The address is `https://<machine>.<tailnet>.ts.net`.

### nginx

With a certificate from certbot:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    server_name notes.example.com;

    ssl_certificate     /etc/letsencrypt/live/notes.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/notes.example.com/privkey.pem;

    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # Agents' connection to the editor is a WebSocket.
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
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
- If saving fails with HTTP 403, your proxy rewrites the `Host` header: forward it unchanged, or send the original as `X-Forwarded-Host`.
- After 10 failed sign-ins in 15 minutes from one IP, notesd blocks sign-ins from that IP for a while. People who are already signed in aren't affected. Behind a proxy, notesd learns the visitor's IP from the proxy's `X-Forwarded-For` header, believed only from a proxy on this machine or a private network (LAN, Docker, VPN). Behind a proxy on a public address, every visitor shares one limit. On a network you expose notesd to directly, a device there could fake the header to get around the limit.
- Pictures can be up to 10 MiB. If your proxy limits request size, allow at least that.

## Limiting who can reach notesd

| Reachable from | Docker | Binary |
| --- | --- | --- |
| This machine only (the default in these guides; use with HTTPS above) | `-p 127.0.0.1:8080:8080` | `--listen 127.0.0.1:8080` (the binary's default) |
| One network, such as your LAN or VPN | `-p 192.168.1.10:8080:8080` | `--listen 192.168.1.10:8080` |
| Every network | `-p 8080:8080` | `--listen 0.0.0.0:8080` |

Replace `192.168.1.10` with the machine's address on that network (`hostname -I` lists them). To
change Docker flags, see [Changing Docker settings](docker.md#changing-docker-settings).

> [!WARNING]
> Docker's published ports bypass host firewalls such as UFW and firewalld. On a machine with a
> public IP, `-p 8080:8080` exposes notesd to the internet over plain HTTP.

Without HTTPS, your password and notes are sent unencrypted, so serve plain HTTP only to networks
you trust. The apps allow plain HTTP only to local
addresses (private, link-local or loopback IPs such as `192.168.x` or `10.x`, `localhost`,
`*.local`), and the web app can't keep
notes offline or be installed over a plain-HTTP LAN address.
