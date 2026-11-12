---
title: Run on Fly.io
description: Deploy notesd to Fly.io with HTTPS and daily snapshots, upgrade it, and run commands on it.
---

# Run on Fly.io

[Fly.io](https://fly.io) runs the notesd image on a small machine with a volume for your notes and
HTTPS at `https://<app>.fly.dev`. The machine suspends when no one is using it and wakes on the next
request, so it costs about $2–3 a month. You need a Fly account with billing set up, and
[`flyctl`](https://fly.io/docs/flyctl/install/), signed in with `fly auth login`.

## Deploy

Pick an app name, which is also the address: `my-notes` below becomes `https://my-notes.fly.dev`.
In a new, empty folder:

```bash
fly apps create my-notes
{ echo 'app = "my-notes"'; curl -fsSL https://raw.githubusercontent.com/cassiozen/notesd/main/deploy/fly/fly.toml; } > fly.toml
fly secrets set --stage NOTESD_OWNER_USERNAME=me NOTESD_OWNER_PASSWORD='a long password'
fly deploy
```

Start the `fly secrets` line with a space to keep the password out of your shell history (zsh needs
`setopt histignorespace`).

`fly deploy` creates the machine and its 1 GB volume, and notesd creates your account from the two
secrets when it first starts, so no one else can claim it. Open `https://my-notes.fly.dev` and sign
in. Keep `fly.toml`: the commands on this page run from its folder.

Once you've signed in, you can remove the secrets. The account stays; this restarts the machine:

```bash
fly secrets unset NOTESD_OWNER_USERNAME NOTESD_OWNER_PASSWORD
```

> [!WARNING]
> Never run more than one machine, with `fly scale count` or by cloning. Each machine gets its own
> volume, so a second one starts with no notes and each device sees whichever it reaches.

## Your own domain

```bash
fly certs add notes.example.com
```

It prints the DNS records to add at your domain's provider. Once they're in, Fly gets the
certificate; `fly certs show notes.example.com` says when. notesd works at both addresses.

A published space can have a domain of its own the same way: see
[Publishing a space](../using/publishing.md#fly-io).

## Upgrade

`fly deploy` pulls the newest image. Snapshot the volume first, as a newer notesd updates the
database when it starts and an older one can't open it after that:

```bash
fly volumes list                  # the volume's ID, vol_…
fly volumes snapshots create vol_…
fly deploy
```

To stay on one release line, change `image` in `fly.toml` to its tag, such as
`ghcr.io/cassiozen/notesd:0.2`. Read [Changes that need action](upgrading.md#changes-that-need-action)
before a new major version.

## Backups

Fly snapshots the volume every day and keeps 14 days of them (`snapshot_retention` in `fly.toml`,
up to 60). A snapshot copies the whole volume at one instant, which notesd's databases recover from
as they would from a power cut: whole, but without changes from the last seconds before it.

To restore one, make a volume from it, then move the machine onto that volume:

```bash
fly volumes list                          # the volume's ID, vol_…, and region
fly volumes snapshots list vol_…          # pick a snapshot, vs_…
fly volumes create notesd_data --snapshot-id vs_… --region <region> --snapshot-retention 14 --yes
fly machine list                          # the machine's ID
fly machine destroy <machine> --force
fly volumes destroy vol_… --yes           # the old volume
fly deploy --primary-region <region>
```

`fly deploy` makes a new machine on the restored volume; with no machine left, it needs the region.

Then, before anyone writes anything new, open each app that was signed in and let it sync: see
[Why sync every device first](backups.md#why-sync-every-device-first).

Snapshots stay inside Fly. A copy you can take elsewhere waits on the planned `notesd backup`
command: copying the databases while notesd runs can catch them halfway.

## Running commands

Run [notesd commands](../../reference/cli.md) on the machine as user `nonroot`, which notesd runs as:

```bash
fly ssh console -u nonroot -C "notesd check"
fly ssh console -u nonroot --pty -C "notesd reset-password --username sam"
fly logs
```

Add `--pty` for commands that ask you something. The image has no shell, so `fly ssh console`
without `-C` doesn't work. `fly ssh sftp get` copies a file off the machine.

## Good to know

- **Settings:** add any [setting](../../reference/cli.md#serve) under `[env]` in `fly.toml`, then
  `fly deploy`. Use `fly secrets set` for API keys and passwords. Leave `PORT`, `NOTESD_DATA_DIR`
  and `NOTESD_CLIENT_IP_HEADER` as they are, and don't set `NOTESD_SELF_UPDATE`: upgrade with
  `fly deploy`.
- **Sleeping:** while suspended, notesd does nothing on its own. Work it does after you leave, such
  as an agent's *When you finish a note* rule, can wait until the next request wakes it. To keep it
  always on, set `min_machines_running = 1`, for roughly the same cost.
- **Sign-in limits:** notesd tells visitors apart by the `Fly-Client-IP` header, which Fly's proxy
  sets itself, so failed sign-ins from one visitor don't lock out the others.
- **Size:** 256 MB of memory is enough for a few accounts. If notesd runs out, give it more with
  `fly scale memory 512`. To grow the volume: `fly volumes extend vol_… --size 3`.
