---
title: Backups
description: Back up the data directory safely, restore it, and what to know about restoring today.
---

# Backups

Everything notesd stores is in its [data directory](../../concepts/where-data-lives.md): the
accounts, every note, and every picture. Back up that whole directory, and keep a copy off the
machine.

The copies on your phones and browsers aren't a backup. They can help after a restore, but only
for notes they had synced.

## Make a backup

Stop the server while you copy. notesd keeps recent changes in `*.db-wal` files beside each
database and folds them in over time, so a copy taken while it runs can catch the files halfway
and be inconsistent. A `notesd backup` command that copies safely while the server runs is
planned, but not built yet.

**systemd**, where `DynamicUser` keeps the directory in `/var/lib/private/notesd`:

```bash
sudo systemctl stop notesd
sudo tar czf ~/notesd-backup-$(date +%F).tgz -C /var/lib/private/notesd .
sudo systemctl start notesd
```

**Docker**, copying the `notesd-data` volume to a file in the current folder:

```bash
docker stop notesd
docker run --rm -v notesd-data:/data -v "$PWD":/backup busybox \
  tar czf /backup/notesd-backup-$(date +%F).tgz -C /data .
docker start notesd
```

The server is down for a few seconds; the apps keep working and sync once it's back. To back up
on a schedule, run the same commands from a systemd timer or cron, then hand the file to restic,
Borg or your NAS's backup job.

Before an upgrade, back up first: a newer notesd updates the database when it starts, and an older
one can't open it after that. [Updating from Settings](upgrading.md#updating-from-settings) makes
its own backup in `/var/backups/notesd`.

## Check a backup

To check that a backup is whole, unpack it somewhere and run [`notesd check`](../../reference/cli.md#check)
against it:

```bash
mkdir /tmp/notesd-restore-test
tar xzf notesd-backup-2026-11-01.tgz -C /tmp/notesd-restore-test
notesd check --data-dir /tmp/notesd-restore-test
```

## Restore

1. Stop the server.
2. Replace the data directory's contents with the backup's.
3. Start the server.
4. **Before anyone writes anything new,** open each app that was signed in, phones and web apps
   that keep notes, and let it sync.

```bash
# systemd
sudo systemctl stop notesd
sudo find /var/lib/private/notesd -mindepth 1 -delete
sudo tar xzf notesd-backup-2026-11-01.tgz -C /var/lib/private/notesd
sudo systemctl start notesd

# Docker
docker stop notesd
docker run --rm -v notesd-data:/data -v "$PWD":/backup busybox \
  sh -c 'find /data -mindepth 1 -delete && tar xzf /backup/notesd-backup-2026-11-01.tgz -C /data'
docker start notesd
```

### Why sync every device first

After a restore, the server is behind your devices: they've seen changes the backup doesn't have.
A device that notices compares all its notes with the server and uploads what the server lost.
See [how sync works](../../concepts/how-sync-works.md#restoring-a-backup).

notesd doesn't mark a restored database as restored yet, so devices notice only because their
sync position is ahead of the server's. That works as long as the restored server hasn't moved
on. If notes are written on the restored server before a device syncs, that device can miss the
new changes, and can overwrite some of them with its own copy. Syncing every device first, before
writing anything, avoids both.

Locked notes are restored with their keyring, so they open with the lock password they had when
the backup was made.
