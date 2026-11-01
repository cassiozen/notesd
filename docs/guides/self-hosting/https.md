---
title: Network access and HTTPS
description: Limit who can reach notesd, and put HTTPS in front of it to use it from anywhere.
---

# Network access and HTTPS

## Limiting who can reach notesd

As set up in [Run with Docker](docker.md) and [Run the binary](binary-systemd.md), notesd accepts connections on every network the machine is connected to. That's fine at home, but not on a server with a public IP.

| Reachable from | Docker | Binary |
| --- | --- | --- |
| Every network (as set up in those guides) | `-p 8080:8080` | `--listen 0.0.0.0:8080` |
| One network, such as your LAN or VPN | `-p 192.168.1.10:8080:8080` | `--listen 192.168.1.10:8080` |
| This machine only (use with HTTPS below) | `-p 127.0.0.1:8080:8080` | `--listen 127.0.0.1:8080` |

Replace `192.168.1.10` with the machine's address on that network. To change Docker flags, see [Changing Docker settings](docker.md#changing-docker-settings).

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

    client_max_body_size 10m;

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
- Pictures can be up to 10 MiB. If your proxy limits request size, allow at least that.
