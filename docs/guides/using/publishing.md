---
title: Publishing a space
description: Turn a space into a small public website, with a page per note, tags, a feed and a search.
---

# Publishing a space

Publish a space and anyone can read it on the web, at an address on your server such as
`https://notes.example.com/@garden/`. There's nothing to set up: every note in the space with a
title and some text is a post.

The site has:

- a home page listing posts, newest first, 30 to a page;
- a page for each post, with its tags and the posts before and after it;
- a page for each tag, and a list of every tag;
- on a wide enough screen, the tags in the left margin of every page, with the one you're on marked;
- a search, and a feed for feed readers.

It looks like notesd: titles in the same serif as the apps, and the colour of the space's icon
along the top, in each tab's icon and in small details. It follows the reader's light or dark
setting.

## Publish a space

In any of the apps:

1. Choose the space's name at the top of the sidebar, then **Info**.
2. Under **Published** (in the web and Android apps, choose **Website**), choose **Publish**.

Publish with notesd open at the address people should use, not `localhost`: the feed's links are
made from it.

The address comes from the space's name. To change it, edit it where it's shown under
**Published** (**Website** in the web and Android apps), save it (Enter on the web), then confirm with
**Change address**. Links to the old address stop working.

To take the site down, choose **Stop publishing**. The address stays the space's, for when you
publish it again.

## Use your own domain

A site can have a domain of its own, such as `blog.example.com`. notesd serves the site there: every
request for the domain gets the site, never the app. Only an admin of the server can set a site's
domain.

1. In the web or Mac app, under **Website → Your own domain**, enter the domain and choose
   **Save**. The site's feed, sitemap and links now use it.
2. Point the domain at your server and give it HTTPS, the same way as the app's address. The steps
   for each setup are below. The examples use the domain `blog.example.com`, and notesd at
   `127.0.0.1:8080`: put in your own.

The site still answers at its address on the app's domain too; search engines are told the domain is
the real one. To stop using the domain, empty the field and save, then remove what you added for it.
With Tailscale alone, a site can't have a domain of its own.

### Fly.io

1. From the folder with `fly.toml`, add a certificate for the domain:

   ```bash
   fly certs add blog.example.com
   ```

2. It prints the DNS records to add at your domain's provider. Once they're in, Fly gets the
   certificate; `fly certs show blog.example.com` says when.

### Caddy

1. Point the domain's DNS at your server, as you did for the app's address: an `A` (and `AAAA`)
   record, or a `CNAME` to the app's domain.
2. Add a block for it to `/etc/caddy/Caddyfile`:

   ```caddyfile
   blog.example.com {
       reverse_proxy 127.0.0.1:8080
   }
   ```

3. Reload Caddy, which gets the domain's certificate by itself:

   ```bash
   sudo systemctl reload caddy
   ```

### nginx

1. Point the domain's DNS at your server, as for Caddy.
2. Add a server for it:

   ```nginx
   server {
       listen 80;
       server_name blog.example.com;
       location / {
           proxy_pass http://127.0.0.1:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       }
   }
   ```

3. Get its certificate, which also adds HTTPS to that server:

   ```bash
   sudo certbot --nginx -d blog.example.com
   ```

### Cloudflare Tunnel

1. The domain must be on Cloudflare. There's no DNS record to add: the tunnel makes it.
2. In the Cloudflare dashboard, go to **Networking → Tunnels**, open your tunnel, then
   **Routes → Add route → Published application**.
3. Set **Hostname** to `blog.example.com` and **Service URL** to `http://127.0.0.1:8080`.

### Other proxies

Point the domain's DNS at your server, then have the proxy send its requests to notesd with the
`Host` header unchanged, or the domain in `X-Forwarded-Host`. Have it pass the visitor's address in
`X-Forwarded-For` too: the site's search is limited per visitor, and without it everyone shares one
limit.

## Good to know

- **It's live.** A note you save shows on the site about 10 seconds after you stop editing, or
  within 2 minutes if you keep going. Write drafts in another space and move them in when they're
  ready.
- **Locked notes can't be in a published space.** Unlock them, or move them out, to publish it.
  Your first space, "Notes", can't be published.
- **Sharing and publishing are separate.** A shared space can be published too, by its owner, and
  then every member's notes in it are public.
- **Custom CSS.** Under **Website → Custom CSS** in the web app, add CSS of
  your own. It's added after the site's styles, so it wins without `!important`, and most changes are a
  variable, such as `:root { --accent: teal }`. The variables are `--bg`, `--surface`, `--text`, `--muted`,
  `--border`, `--accent`, `--link`, `--font-body`, `--font-heading`, `--font-mono`,
  `--measure` and `--rail` (the width of the tags in the margin).
- **Custom CSS and the domain are set in the web or Mac app.** The Android and iPhone apps
  publish, change the address and stop publishing.

For who can see what, see the [security model](../../concepts/security-model.md).
