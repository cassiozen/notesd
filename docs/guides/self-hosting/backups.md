---
title: Backups
description: What to back up, and how.
---

# Backups

Everything notesd stores is in its data directory (`/var/lib/notesd` with the
[systemd setup](binary-systemd.md#keep-it-running-with-systemd), or the `notesd-data` volume with
[Docker](#docker)):

```text
system.db      accounts, sessions and server-wide settings (email)
users/1.db       the first account's notes, images, spaces and locked-notes keyring
users/2.db       the next account's, and so on: one file per account
*.db-wal         recent changes not yet folded into the file beside it (may not exist)
*.db-shm         shared memory index for the WAL (may not exist)
```

Back up the whole directory. For a consistent copy, stop the server
first. The phones' copies aren't a backup. Keep a copy off the machine.

## systemd

```bash
sudo systemctl stop notesd
sudo cp -a /var/lib/private/notesd ~/notesd-backup   # DynamicUser keeps it here
sudo systemctl start notesd
```

## Docker

To back up, stop the server and copy the volume to a file:

```bash
docker stop notesd
docker run --rm -v notesd-data:/data -v "$PWD":/backup busybox tar czf /backup/notesd-backup.tgz -C /data .
docker start notesd
```
