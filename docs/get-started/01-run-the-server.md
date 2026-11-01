---
title: 1. Run the server
description: Start notesd with Docker and check that it's running.
---

# 1. Run the server

Check [what you need](00-introduction.md#what-you-need) first. No Docker?
[Run the binary instead](../guides/self-hosting/binary-systemd.md), then come back for
[step 2](02-create-your-account.md).

## Start notesd

On the server:

```bash
docker run -d --name notesd --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v notesd-data:/data \
  -e NOTESD_PUBLIC_URL=https://notes.example.com \
  ghcr.io/cassiozen/notesd:latest
```

- `-p 127.0.0.1:8080:8080` publishes notesd on the machine's loopback interface only. Nothing
  outside the machine can reach it until you put HTTPS in front in step 3.
- `NOTESD_PUBLIC_URL` is the HTTPS address you'll open notesd at: your domain, or
  `https://<machine>.<tailnet>.ts.net` with Tailscale.
- Your notes go in the `notesd-data` volume, so they survive upgrading or recreating the
  container.

> [!WARNING]
> Don't publish with `-p 8080:8080` on a machine with a public IP. It serves plain HTTP on every
> interface, and Docker's published ports bypass host firewalls such as UFW.

## Check that it's running

```bash
curl http://127.0.0.1:8080/healthz
```

```json
{"status":"ok"}
```

The logs show that it started, and remind you that there's no account yet:

```bash
docker logs notesd
```

```text
time=… level=WARN msg="no owner account yet: open url and create it before anyone else does, …" url=https://notes.example.com/
time=… level=INFO msg="notesd started" version=… listen=[::]:8080 …
```

**Next:** [2. Create your account](02-create-your-account.md)
