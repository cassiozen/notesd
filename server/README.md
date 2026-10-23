# notesd server

The backend from [docs/architecture.md](../docs/architecture.md): one Go binary
serving the JSON API, the sync feed, full-text search and the embedded web
app ([apps/web](../apps/web/README.md)), backed by SQLite: a system database
for accounts, sessions and server-wide settings, and one database per account
for its notes.

This README is the developer and API reference. To install and run notesd,
see [docs/hosting.md](../docs/hosting.md) and
[docs/operations.md](../docs/operations.md).

## Build and test

Requires the toolchain in the root `mise.toml` (see
[docs/development.md](../docs/development.md)). No cgo: the SQLite driver is
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
in [docs/hosting.md](../docs/hosting.md#configuration) and
[docs/operations.md](../docs/operations.md#maintenance-commands). Notes for
working on them:

- The data directory holds `system.db` (accounts, sessions, server-wide
  settings) and one notes database per account, `users/<id>.db`
  (`internal/userdata`). Every command first moves a single-user `notes.db`
  into that layout (`userdata.Upgrade`).
- `reindex` also runs automatically when a user's database is first opened,
  if a migration asks for it.
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

All endpoints are under `/api/v1` and speak JSON. Errors look like
`{"error": "code", "message": "…", "field": "…", "current": {…}}`.

**Authentication.** Browsers log in with `"client": "web"` and get an
HttpOnly session cookie (30-day sliding expiry) plus a `csrfToken`, which must
be sent as `X-CSRF-Token` on every non-GET request; cross-origin `Origin`
headers are rejected. Native apps log in with `"client": "device"` and get an
`accessToken` (1 hour) and `refreshToken` (180 days, sliding, not rotated, so
a lost refresh response is safe to retry), sent as `Authorization: Bearer`.
Failed logins are limited to 10 per 15 minutes per client address.

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/auth/status` | `{"setupRequired": bool}` |
| POST | `/auth/setup` | `{username, password}`; only while no account exists. Later accounts come from `notesd add-user` |
| POST | `/auth/login` | `{username, password, client, deviceName}` |
| POST | `/auth/refresh` | `{refreshToken}` → new `accessToken` |
| POST | `/auth/logout` | |
| GET | `/auth/session` | current session, `username`, `isAdmin`, and `csrfToken` for browsers |
| POST | `/auth/password` | `{currentPassword, newPassword}`; signs out other sessions |
| GET | `/auth/sessions` | this account's signed-in browsers and devices |
| DELETE | `/auth/sessions/{id}` | revoke one |
| GET | `/notes?limit=&cursor=&tag=&untagged=&locked=&space=` | live notes, newest first, with `preview` (plain text, one line per block, ≤160 chars), `locked` and `spaceId`; `nextCursor` is opaque; `total` counts the matching live notes. `tag` (with or without `#`) keeps notes with that tag or one nested under it; `untagged=true` keeps notes without tags; `locked=true` keeps locked notes; at most one of the three. `space` (a space id) keeps the notes in that space, and combines with any of them |
| POST | `/notes` | `{operationId, id, title, content, createdAt?, updatedAt?, conflictOf?, spaceId?, imageIds?}` → 201. `imageIds` lists a locked note's images (up to 1000); a plain note's are read from its content. `spaceId` absent is the default space |
| GET | `/notes/{id}` | 410 with the tombstone in `current` if deleted |
| PUT | `/notes/{id}` | `{operationId, baseRevision, title, content, updatedAt?, spaceId?, imageIds?}`; both fields required. `spaceId` moves the note; absent keeps its space. For a locked note, absent `imageIds` keeps the images recorded |
| DELETE | `/notes/{id}` | `{operationId, baseRevision}` |
| POST | `/notes/{id}/email` | `{to: [address, …], revision}` → `{id}` (Resend's). Sends the stored note: HTML rendered from the Markdown plus the Markdown as plain text, subject = title. `revision` must be the server's current one (`409 stale_revision` otherwise: sync first). Up to 10 recipients. `409 email_not_configured` without settings; `502 email_failed` with Resend's `message` when Resend refuses (unverified domain, bad key); `409 note_locked` for a locked note |
| GET | `/sync?since=&limit=&generation=` | `{changes, nextCursor, hasMore, generation}` |
| GET | `/search?q=&limit=&offset=&tag=&untagged=&locked=&space=&exceptSpace=` | `{results: [{id, title, excerpt, updatedAt, locked, spaceId}], otherSpaces?}`; filters as for `/notes`, or `exceptSpace` for every space but one. With `space`, the first page (`offset=0`) also has `otherSpaces`: the matches in every other space |
| GET | `/settings/email` | `{configured, from, keyHint, editable}`; server-wide, so every account sends with it. The key itself is never returned, `keyHint` is its last 4 characters; `editable` is whether this account is an admin |
| PUT | `/settings/email` | admins only (else 403 `admin_only`). `{apiKey?, from}`; `apiKey` (starts with `re_`) may be omitted to keep the saved one. Not checked against Resend: send-only keys can't call anything but sending, so a bad key shows on the first send |
| DELETE | `/settings/email` | admins only; forget the key and sender |
| GET | `/filters?space=` | `{tags: [{key, name, count}], locked}`: `tags` sorted by key, parents of nested tags included, `count` is distinct notes including nested tags; `locked` counts live locked notes. With `space`, only that space's notes count |
| GET | `/spaces` | `{spaces: [{id, name, icon, createdAt, noteCount}]}`: the default space (`default`) first, then by name. `icon` is a key into the apps' icon set, `''` for the app's own icon |
| PUT | `/spaces/{id}` | `{name, icon?}` → the space as stored. Creates the space or renames it; `id` is 1–64 characters `[A-Za-z0-9_-]`. The name is trimmed with inner whitespace collapsed, 1–64 characters; a name another space already has (ignoring case) gets ` 2`, ` 3`… appended, shortened to fit if need be. `icon` is 0–64 characters `[a-z0-9-]`; without it the icon stays as it is (`''` for a new space) |
| DELETE | `/spaces/{id}` | → 204, also when already gone. `409 space_not_empty` with `count` while it holds live notes; `409 space_default` for the default |
| GET | `/locks` | `{keys: [{id, kind, credentialId?, salt, iterations, wrapped, createdAt}]}`: the locked-notes keyring, password entry first |
| PUT | `/locks/keys/{id}` | `{kind, credentialId?, salt, iterations, wrapped, verifier}`. `id` is `password` or `recovery` for those kinds; a `passkey` takes any other id of 1–64 characters `[A-Za-z0-9_-]` (`400` otherwise). `salt`, `wrapped`, `credentialId` and `verifier` are unpadded base64url. The first entry must be the password, and its `verifier` is stored (hashed); every later write must send the same verifier (`403 bad_verifier`). `409 no_lock_password` for a recovery or passkey entry first |
| DELETE | `/locks/keys/{id}` | `{verifier}`; the password entry can't be removed (`409 password_key_required`) |
| DELETE | `/locks` | `{accountPassword}`: forgets the whole keyring (a forgotten lock password); rate-limited like a password change |
| PUT | `/images/{sha256}` | raw body, up to 10 MiB, `Content-Type` one of `image/jpeg`, `image/png`, `image/webp`, `image/gif` or `application/vnd.notesd.locked` (an encrypted image). `{sha256}` is lowercase hex of the body (`400 hash_mismatch` otherwise); image types must match the body's magic bytes (`415 unsupported_image`). → `201 {id, contentType, size}`, or `200` if it already exists, so retries are safe |
| GET | `/images/{sha256}` | the bytes with their stored type, `Cache-Control: private, max-age=31536000, immutable`, an `ETag` and a sandboxing CSP |

`/healthz` (process up) and `/readyz` (database reachable) sit outside
`/api/v1`. Every other path serves the web app, falling back to `index.html`.

**Mutations** answer `{id, status, revision, changeSeq, updatedAt, deletedAt?, spaceId?}`
where `status` is `created`, `updated`, `unchanged` (identical content and space, no new
revision), `deleted` or `already_deleted`. Creates and updates carry the note's
`spaceId`: the one sent, `default` if that space no longer exists, or, for an update that
sent none, the space the note was already in. Conflicts are `409` with the
server's latest note in `current`:

- `revision_conflict`: `baseRevision` is stale (also for stale deletions, so a
  deletion never discards a newer edit)
- `note_deleted`: the note is a tombstone; the edit should become a
  "Recovered copy"
- `note_exists`: create with an ID already in use, including deleted IDs

**Idempotency.** `operationId` is required on every mutation. A retry with the
same ID and payload returns the original response with
`Idempotent-Replayed: true`, even if the note has changed since; the same ID
with a different payload is `422 operation_id_reused`. Rejected requests
record nothing, and since revisions only grow, a stale request stays
rejected.

**Sync.** Pass the last `nextCursor` as `since`, and the stored `generation`.
`409 generation_mismatch` or `409 cursor_ahead` means the server was
restored from a backup and the client must run a full reconciliation (§11.4).

**Locked notes** (architecture §10.1). A note whose `content` starts with
`notesd:locked:v1:` has an encrypted body the server can't read. It must be
one line of unpadded base64url holding at least a nonce and tag (`400` with
`field: content` otherwise), so an edit made on the ciphertext as text is
refused instead of stored. Only its title is indexed and tagged; its preview
and excerpt are empty and `locked` is true. The keyring holds the key
wrapped under the lock password, a recovery code and passkeys, never in the
clear. `verifier` is HMAC-SHA256 of the key with `notesd:verifier:v1`:
proof that a client holds the key, so a stolen session alone can't replace
or delete entries.

**Images** (architecture §7.6). Notes reference them as
`![alt](notesd:image/<sha256>?w=<px>&h=<px>)`. The server tracks which notes show
which images (`note_images`): plain notes' from their content, locked notes'
from the `imageIds` their clients send, which notes and sync changes return
for locked notes. An image is deleted 30 days after the last note showing it
stops (or 30 days after upload, if no note ever does), every 6 hours or by
`notesd gc-images`. On a database upgraded from before references were
tracked, encrypted images are kept until a locked note's save lists them and
a later one drops them: `gc-images` doesn't remove those by itself.

**Search.** Input is split into words, each quoted as a prefix term, so FTS5
syntax in user input is inert and never errors. `excerpt` is HTML-escaped
note text with matches wrapped in `<mark>`.

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
