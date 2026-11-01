---
title: 3. Set up HTTPS
description: Put HTTPS in front of notesd with Caddy or Tailscale, so your browser and phone can reach it.
---

# 3. Set up HTTPS

notesd serves plain HTTP on the machine's loopback interface. To reach it from your devices, put
HTTPS in front of it. The phone apps refuse plain HTTP to public addresses, and the web app needs
HTTPS to keep notes offline.

Pick one. The address you end up with must match `NOTESD_PUBLIC_URL` from
[step 1](01-run-the-server.md).

## With a domain: A proxy server 

In this example, we will use Caddy. Caddy gets and renews certificates from Let's Encrypt on its own.
nginx and Cloudflare Tunnel work too: see
[Network access and HTTPS](../guides/self-hosting/https.md#access-from-anywhere-with-https).

### Setting Up Caddy

1. Point a DNS `A` (and `AAAA`, if the machine has IPv6) record for `notes.example.com` at the
   machine's public IP.
2. Open ports 80 and 443. With UFW:

   ```bash
   sudo ufw allow 80,443/tcp
   ```

   Also allow them in your provider's firewall or security group, if it has one. Behind a home
   router, forward both ports to the machine.
3. [Install Caddy](https://caddyserver.com/docs/install), then replace `/etc/caddy/Caddyfile`
   with:

   ```caddyfile
   notes.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```

4. Reload it:

   ```bash
   sudo systemctl reload caddy
   ```

## Without a domain: Tailscale

If the machine and your devices are on the same tailnet, notesd stays private to it and needs no
open ports. Turn on MagicDNS and HTTPS certificates in the tailnet's DNS settings, then on the
server:

```bash
sudo tailscale serve --bg 8080
```

The address is `https://<machine>.<tailnet>.ts.net`.

## Check it

Open your HTTPS address in a browser and sign in with the account from
[step 2](02-create-your-account.md).

If saving fails with HTTP 403, `NOTESD_PUBLIC_URL` doesn't match the address you opened. Fix it
and restart notesd: with Docker, [recreate the container](../guides/self-hosting/docker.md#changing-docker-settings);
with systemd, edit the unit, then `sudo systemctl daemon-reload && sudo systemctl restart notesd`.

> [!NOTE]
> **Only on a network you trust**, you can skip HTTPS and serve plain HTTP to your LAN instead.
> Recreate the container with `-p <lan-ip>:8080:8080` (binary: `--listen <lan-ip>:8080`) and
> without `NOTESD_PUBLIC_URL`, and use `http://<lan-ip>:8080` (`hostname -I` lists the machine's
> addresses). Never use `-p 8080:8080` on a machine with a public interface: it publishes on every
> interface. The apps accept plain HTTP only to local addresses (private, link-local or loopback
> IPs, `localhost`, `*.local`), and the web app can't keep notes offline or be installed.

**Next:** [4. Write your first note](04-write-your-first-note.md)
