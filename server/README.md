# notesd server

The backend from [docs/contributing/architecture.md](../docs/contributing/architecture.md): one Go binary
serving the JSON API, the sync feed, full-text search and the embedded web
app ([apps/web](../apps/web/README.md)), backed by SQLite: a system database
for accounts, sessions and server-wide settings, and one database per account
for its notes.

This README is for working on the server. To install and run notesd,
see the [documentation](../docs/README.md).

## Build and test

Requires the toolchain in the root `mise.toml` (see
[docs/contributing/development.md](../docs/contributing/development.md)). No cgo: the SQLite driver is
`modernc.org/sqlite` (pure Go, FTS5 included), so cross-compiling for a
Raspberry Pi is a plain `GOARCH` switch.

| Task (`mise run …`) | What it does |
| --- | --- |
| `build:web` | `pnpm build` in `apps/web`, writing `internal/web/dist` |
| `build` | `build:web`, then `./bin/notesd` |
| `release` | `build:web`, then linux/amd64, linux/arm64 and darwin/arm64 binaries in `./bin` |
| `test:go` | the Go tests with the race detector |
| `lint:go` | golangci-lint (`.golangci.yml`) |
| `clean` | removes `./bin` |

The web app is embedded at compile time, so a plain `go build` embeds
whatever is in `internal/web/dist`. That directory is build output and is
gitignored except for a `.gitkeep` (`//go:embed` needs the directory to
exist). A binary built without the web app still works; web pages show a
"web app not built" placeholder with status 503.

## Commands and configuration

The commands (`serve`, the default, plus `create-owner`, `add-user`,
`list-users`, `remove-user`, `reset-password`, `reindex`, `check`,
`gc-images`, `export` and `version`) and every user-facing flag are documented
in the [command line reference](../docs/reference/cli.md). Notes for
working on them:

- The data directory holds `system.db` (accounts, sessions, server-wide
  settings) and one notes database per account, `users/<id>.db`
  (`internal/userdata`). Every command first moves a single-user `notes.db`
  into that layout (`userdata.Upgrade`).
- `reindex` also runs automatically when a user's database is first opened,
  if a migration asks for it.
- `serve --mirror-dir` (`internal/mirror`) follows the sync feed to keep
  Markdown files up to date. The notes, spaces and images stores report each
  commit through `OnCommit`, which `userdata.Registry.OnChange` wires to the
  mirror. `export` and the mirror share `internal/mdexport`.
- Passwords for CLI commands come from `NOTESD_PASSWORD` or the first line
  of stdin.
- `--resend-url` / `NOTESD_RESEND_URL` (default `https://api.resend.com`)
  exists so tests can point email at a fake.
- First-run setup has no token: `POST /api/v1/auth/setup` creates the first
  account, an admin, for whoever calls it first, then answers `already_set_up` (architecture
  §10). While there's no owner, `serve` logs a warning with the app's `url`
  (`--public-url` when set, otherwise the listen address).
  `NOTESD_OWNER_USERNAME` and `NOTESD_OWNER_PASSWORD` create the owner at
  startup instead. They're env-only, so the password stays out of process arguments.

## API

The API is documented for its users in
[docs/reference/api.md](../docs/reference/api.md). When you change an endpoint, a
parameter, a response or an error code, update that page in the same change. Error
codes come from `writeErr` in `internal/api/respond.go` and from `writeError` calls in
the handlers and middleware (`grep -rn 'writeError\|Error:' internal/api`).

## Layout

```text
cmd/notesd   CLI, configuration, graceful shutdown
internal/database  connection pools, pragmas, migration runner, integrity check
internal/notes     create/update/delete, revisions, tombstones, idempotency, listing
internal/sync      change feed
internal/search    Markdown → plain text and tags, FTS5 query building, search, tag listing, reindex
internal/locks     locked-note envelopes, keyring
internal/spaces    the list of spaces
internal/images    image blobs: validation, storage, references, collection, integrity check
internal/mail      email settings, note Markdown → HTML email, Resend client
internal/auth      Argon2id, accounts, sessions, login rate limiting
internal/userdata  each account's notes database and stores; the upgrade from one notes.db
internal/api       routing, auth/CSRF middleware, handlers, gzip, logging
internal/web       embedded web app (apps/web builds into internal/web/dist)
migrations         numbered SQL files, tracked with PRAGMA user_version (system/ for system.db)
```

## Implementation notes and deviations from the spec

- **`notes` has an `INTEGER PRIMARY KEY` (`seq`) and a `UNIQUE` text `id`.**
  The spec keys the FTS index on the notes rowid; an implicit rowid can be
  renumbered by `VACUUM`, which would silently desynchronize search.
- **`sync_state.generation`** is created now (random at first migration) and
  returned on every sync page, so clients store it from day one. Changing it
  on restore belongs to the backup/restore work in step 6.
- **Tombstones clear title and content.** If the 30-day Trash (§6.5) is
  adopted, deletion will need to keep content until the purge.
- **Writes are serialized in Go** through a one-connection pool using
  `BEGIN IMMEDIATE`, so concurrent requests queue instead of failing with
  `SQLITE_BUSY`. Reads use a separate `query_only` pool and never block on
  writes (WAL).
- **Client timestamps** (`createdAt`, `updatedAt`) are accepted so offline
  edits show when they happened, but they are informational only; values
  more than a day in the future are replaced with the server clock.
- **Endpoints added beyond §9:** `auth/status`, `auth/setup`, `auth/session`,
  `auth/password`, `auth/sessions`. §10 requires first-run protection,
  password changes and revocable sessions, and the web app needs a way to
  get its CSRF token back after a reload.
- **Plain-text extraction rules** are pinned by
  `shared/markdown-fixtures/plaintext.json`, which the iOS and Android
  extractors should also be tested against.
- **Login rate limiting keys on the TCP peer address** (`clientKey` in
  `internal/api`). `X-Forwarded-For` is ignored because there's no trusted
  proxy setting yet, so behind a reverse proxy all clients share one limit.
- **Not yet built:** `backup` (step 6), and the OpenAPI document in
  `shared/api/`. Configuration is flags and environment variables only.
