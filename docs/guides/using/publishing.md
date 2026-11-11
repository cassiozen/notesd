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

A site can have a domain of its own, such as `blog.example.com`. notesd can't serve a domain by
itself, so the reverse proxy in front of it does, the same one that serves the app's address. It
sends every request for the domain to notesd with `/@<handle>` put before the path.

1. In the web app, under **Website → Your own domain**, enter the domain and
   choose **Save**. The site's feed, sitemap and links now use it.
2. Set up your proxy, below. The examples use the handle `garden` and the domain
   `blog.example.com`, and notesd at `127.0.0.1:8080`: put in your own, with notesd's address as in
   your app's own block.

Only the site is reachable on its domain, never the app. The site still answers at its address on
the app's domain too; search engines are told the domain is the real one. To stop using the domain,
empty the field and save, then remove what you added to the proxy. With Tailscale alone, a site
can't have a domain of its own.

### Caddy

1. Point the domain's DNS at your server, as you did for the app's address: an `A` (and `AAAA`)
   record, or a `CNAME` to the app's domain.
2. Add a block for it to `/etc/caddy/Caddyfile`:

   ```caddyfile
   blog.example.com {
       rewrite * /@garden{uri}
       reverse_proxy 127.0.0.1:8080
   }
   ```

3. `sudo systemctl reload caddy`. Caddy gets the domain's certificate by itself.

### nginx

1. Point the domain's DNS at your server, as for Caddy.
2. Add a server for it:

   ```nginx
   server {
       listen 80;
       server_name blog.example.com;
       location / {
           proxy_pass http://127.0.0.1:8080/@garden/;
           proxy_set_header Host $host;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       }
   }
   ```

3. `sudo certbot --nginx -d blog.example.com`. It gets the certificate and adds HTTPS to that
   server.

### Cloudflare Tunnel

1. The domain must be on Cloudflare. There's no DNS record to add: the tunnel makes it.
2. In the [Zero Trust dashboard](https://one.dash.cloudflare.com), under **Networks → Tunnels**, add a
   public hostname to your tunnel, `blog.example.com`, pointing to `http://127.0.0.1:8080`.
3. In the domain's dashboard, under **Rules**, add a URL rewrite rule for requests whose hostname is
   `blog.example.com`, rewriting the path dynamically to:

   ```text
   concat("/@garden", http.request.uri.path)
   ```

### Other proxies

Point the domain's DNS at your server, then have the proxy send every request for the domain to
notesd, adding `/@garden` before the path: `blog.example.com/tags/` goes to
`127.0.0.1:8080/@garden/tags/`. Any proxy that can rewrite a path can do it. Have it pass the
visitor's address in `X-Forwarded-For` too: the site's search is limited per visitor, and without it
everyone shares one limit.

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
