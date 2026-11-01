---
title: 1. Run the server
description: Start notesd with Docker and check that it's running.
---

# 1. Run the server

This walkthrough takes you from nothing to notes syncing between your browser and your phone. It
takes about ten minutes, and all of it happens on your home network. You'll make notesd reachable
from the internet later, if you want to.

**You need** a computer with [Docker](https://docs.docker.com/get-docker/) that stays on while
you use notesd: a home server, a Raspberry Pi 4 or 5, a NAS, or your own computer to try it out.
The image runs on 64-bit Linux (x86-64 or ARM64), and on Macs through Docker Desktop.

No Docker? [Run the binary instead](../guides/self-hosting/binary-systemd.md), then come back for
[step 2](02-create-your-account.md).

## Start notesd

```bash
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

This runs notesd in the background on port 8080, and starts it again when the computer
restarts. Your notes go in a Docker volume called `notesd-data`, so they survive when you
upgrade or recreate the container.

## Check that it's running

```bash
curl http://localhost:8080/healthz
```

```json
{"status":"ok"}
```

The logs show that it started, and remind you that there's no account yet:

```bash
docker logs notesd
```

```text
time=… level=WARN msg="no owner account yet: open url and create it before anyone else does, …" url=http://localhost:8080/
time=… level=INFO msg="notesd started" version=… listen=[::]:8080 …
```

> [!NOTE]
> `-p 8080:8080` makes notesd reachable from every device on your network, which you'll need
> for your phone in step 4. On a machine with a public IP address, it's reachable from the
> internet too. In that case, read
> [Limiting who can reach notesd](../guides/self-hosting/https.md#limiting-who-can-reach-notesd)
> first.

**Next:** [2. Create your account](02-create-your-account.md)
