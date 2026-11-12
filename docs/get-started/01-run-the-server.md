---
title: 1. Run the server
description: Start notesd on a server and make it reachable over HTTPS.
---

# 1. Run the server

<WherePicker />

<div data-where="home">

## On a home server

This guide will walk you through using Docker. [Install it](https://docs.docker.com/engine/install/) on the machine if it
isn't there yet.

1. **Start notesd** on the machine:

   ```bash
   docker run -d --name notesd --restart unless-stopped \
     -p 127.0.0.1:8080:8080 \
     -v notesd-data:/data \
     ghcr.io/cassiozen/notesd:latest
   ```

   `-p 127.0.0.1:8080:8080` makes notesd accessible in your machine only, for now. Your notes go in the `notesd-data` volume, so
   they survive upgrades. Check that it's running: `curl http://127.0.0.1:8080/healthz` should print
   `{"status":"ok"}`.

2. **Create your account.** Open [http://localhost:8080](http://localhost:8080) in your server's browser. notesd sees that it has no account yet. Click **Get started**, choose a **Username** and a **Password**, and click **Create account**.

   No browser on the machine? Create it from the command line instead. It asks for the password twice:

   ```bash
   docker exec -it notesd notesd create-owner --username YOUR_USERNAME
   ```

3. **Reach it from anywhere.** Right now, notesd is serving plain HTTP on the machine's loopback interface, so it's only accessible from that machine. To reach it securely from other devices, you'll need a domain with HTTPS pointing to it. Setting this up can be tricky on a home server: you'd typically need to open ports on your router, configure DNS, and manage TLS certificates.

   A simpler alternative is using a tunnel, like Cloudflare Tunnel: It runs a lightweight service (`cloudflared`) on your server that establishes an outbound connection to Cloudflare. You then map a domain (such as `notes.example.com`) to your local notesd server. Cloudflare handles HTTPS automatically, and you don't need to open any ports or expose your home IP address.

   You'll need a free [Cloudflare account](https://dash.cloudflare.com/) and a domain managed by Cloudflare.

   - You can either [buy a domain directly from Cloudflare](https://developers.cloudflare.com/registrar/get-started/register-domain/), or, if you already have one, [add it to Cloudflare](https://developers.cloudflare.com/fundamentals/manage-domains/add-site/).
   - Next, follow [Cloudflare's official tunnel setup guide](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/). Here's the summary of what you'll need to configure for notesd:

      1. In the Cloudflare dashboard, go to **Networking → Tunnels** and select **Create a tunnel**. Give it a name, such as `notesd`.
      2. Select your server's operating system and follow the installation commands shown by Cloudflare. Run these commands **on the same machine running notesd**. This installs `cloudflared`, which connects your server to Cloudflare.
      3. Once the tunnel is connected, go to **Routes → Add route → Published application**.
      4. Configure the route:
         - **Hostname:** Choose a subdomain, such as `notes.example.com`, using your domain.
         - **Service URL:** `http://127.0.0.1:8080`

   That's it! Open `https://notes.example.com` from another device. You should see notesd's login screen.

**Only at home instead?** Skip the tunnel and let devices on your network reach notesd over plain
HTTP. Find the machine's address with `hostname -I` (on a Mac, `ipconfig getifaddr en0`), such as
`192.168.1.10`, and recreate the container to answer there too. Your notes stay in the volume:

```bash
docker rm -f notesd
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 -p 192.168.1.10:8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

Your address is then `http://192.168.1.10:8080`. Your password and notes cross your network
unencrypted, so do this only on a network you trust. The phone apps work this way; the web app
can't keep notes offline or be installed. More in
[Network access and HTTPS](../guides/self-hosting/https.md#limiting-who-can-reach-notesd).

</div>

<div data-where="vps">

## On a VPS

These steps use Docker. [Install it](https://docs.docker.com/engine/install/) on the server if it
isn't there yet. To run the binary instead, see
[Run the binary with systemd](../guides/self-hosting/binary-systemd.md). You also need a domain
name.

1. **Start notesd** on the server:

   ```bash
   docker run -d --name notesd --restart unless-stopped \
     -p 127.0.0.1:8080:8080 \
     -v notesd-data:/data \
     ghcr.io/cassiozen/notesd:latest
   ```

   It answers on the server itself only, for now. Your notes go in the `notesd-data` volume, so
   they survive upgrades. Check that it's running: `curl http://127.0.0.1:8080/healthz` prints
   `{"status":"ok"}`.

2. **Create your account**, before anything else can reach notesd. Until it exists, anyone who can
   reach the server can create it and become its admin. It asks for a password of at least 8
   characters, twice:

   ```bash
   docker exec -it notesd notesd create-owner --username YOUR_USERNAME
   ```

3. **Set up HTTPS.**

   notesd serves plain HTTP on the machine's loopback interface. To reach it from your devices, put
   HTTPS in front of it. The phone apps refuse plain HTTP to public addresses, and the web app needs
   HTTPS to keep notes offline.

   In this example, we will use Caddy. Caddy gets and renews certificates from Let's Encrypt on its own.
   nginx and Cloudflare Tunnel work too: see
   [Network access and HTTPS](../guides/self-hosting/https.md#access-from-anywhere-with-https).

   1. Point a DNS `A` (and `AAAA`, if the machine has IPv6) record for `notes.example.com` at the
      machine's public IP.
   2. Open ports 80 and 443. With UFW:

      ```bash
      sudo ufw allow 80,443/tcp
      ```

      Also allow them in your provider's firewall or security group, if it has one.
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

   Your address is `https://notes.example.com`.

</div>

<div data-where="fly">

## On Fly.io

[Fly.io](https://fly.io) is a hosting platform that runs your apps in lightweight virtual machines, without you having to set up or maintain a server yourself. It gives your app a public address (like `my-notes.fly.dev`) with HTTPS already configured, so you don't need to buy or configure a domain (though you can use your own if you want).

To get started, you'll need a Fly.io account and [`flyctl`](https://fly.io/docs/flyctl/install/) installed on your computer. Once installed, sign in with `fly auth login`.

1. **Create the app.** Pick a name, which is also your address: `my-notes` becomes
   `https://my-notes.fly.dev`. Names are shared by everyone on Fly.io, so if yours is taken, try
   another. Make a folder for it and create the app:

   ```bash
   mkdir my-notes && cd my-notes
   fly apps create my-notes
   ```

   Keep this folder: the `fly` commands for your server, such as upgrading it, run from here.

2. **Get the config.** This downloads notesd's `fly.toml` with your app's name added at the top:

   ```bash
   { echo 'app = "my-notes"'; curl -fsSL https://raw.githubusercontent.com/cassiozen/notesd/main/deploy/fly/fly.toml; } > fly.toml
   ```

3. **Choose your account.** Pick a username and a password of at least 8 characters:

   ```bash
   fly secrets set --stage NOTESD_OWNER_USERNAME='YOUR_USERNAME' NOTESD_OWNER_PASSWORD='a long password'
   ```

   notesd creates your account from these the first time it starts, so no one else can claim the
   server.

4. **Deploy:**

   ```bash
   fly deploy
   ```

   This creates the server, the volume your notes are stored on, and HTTPS, in a minute or two.
   Check that it's running: `curl https://my-notes.fly.dev/healthz` should print
   `{"status":"ok"}`.

Your address is `https://my-notes.fly.dev`. To use your own domain, upgrade, or restore a backup,
see [Run on Fly.io](../guides/self-hosting/fly-io.md).

</div>

**Next:** [2. Account and your first note](02-account-and-first-note.md)
