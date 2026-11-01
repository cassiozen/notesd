---
title: Run with Docker
description: Run notesd in a container, change its settings, and run commands inside it.
---

# Run with Docker

## Start the container

The image, `ghcr.io/cassiozen/notesd`, runs on 64-bit Linux (x86-64 or ARM64):

```bash
docker run -d --name notesd --restart unless-stopped \
  -p 8080:8080 \
  -v notesd-data:/data \
  ghcr.io/cassiozen/notesd:latest
```

Open `http://localhost:8080` (from your other devices, use the machine's address), then [create your account](users.md#create-your-account). Your notes are stored in the `notesd-data` Docker volume. To change a setting later, see [Changing Docker settings](#changing-docker-settings).

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

Keep your `docker run` command somewhere; you'll need it for [upgrades](upgrading.md#docker).

- Pass any [setting](../../reference/cli.md#configuration) as `-e NAME=value`. Don't change `NOTESD_LISTEN` or `NOTESD_DATA_DIR`; use `-p` and `-v` instead.
- To store notes in a folder instead of a volume (`-v "$PWD/data:/data"`), create it first with `mkdir -p data && sudo chown 65532:65532 data`. notesd runs as user 65532 and can't write to a folder owned by root.

## Running commands

The data directory is the `notesd-data` volume. Run
[notesd commands](../../reference/cli.md#commands) inside the container:

```bash
docker exec notesd notesd check
docker logs -f notesd
```

To export an account's notes to a folder on the host (`--username` can be
left out while there's only one account):

```bash
docker exec notesd notesd export --username sam --out /tmp/export
docker cp notesd:/tmp/export ./notes-export
```
