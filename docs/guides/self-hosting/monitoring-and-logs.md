---
title: Monitoring and logs
description: Health check endpoints and where notesd logs go.
---

# Monitoring and logs

## Monitoring

- `GET /healthz` answers 200 while the process runs.
- `GET /readyz` answers 200 when the system database is reachable.

Neither needs a login or exposes any data, so they work with Uptime Kuma,
Gatus, or a systemd watchdog script.

## Logs

notesd logs to stderr, so systemd captures it: `journalctl -u notesd`.
Every API request is logged in one line (query strings are left out, so
search terms aren't). `--log-format json` produces JSON for log collectors,
and `--log-level debug` adds detail.

Warnings worth acting on at startup:

- `no owner account yet`: nobody has created the first account. Until someone does, anyone who
  can reach the server can, so [create it](users.md#create-your-account) now.
- `the data directory is not on a volume`: notesd runs in a container whose data directory
  is part of the container itself, so your notes go when it's replaced. Mount a persistent
  volume at the data directory (`/data` in the image).
