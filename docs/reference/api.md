---
title: HTTP API
description: The JSON API the notesd apps use to sign in, sync, search and store notes.
---

# HTTP API

> [!NOTE]
> This is the API of notesd's own web, Android and iPhone apps. It isn't a supported interface for
> other programs yet. Within a major version it only changes in the ways listed under
> [Compatibility](#compatibility), so that apps and servers of different 1.x releases work together.

- [Basics](#basics): requests, compatibility, authentication, writing notes, errors
- [Endpoints](#endpoints): [auth](#auth), [notes](#notes), [sync](#sync),
  [search and filters](#search-and-filters), [spaces](#spaces), [shared spaces](#shared-spaces), [images](#images),
  [locked notes](#locked-notes), [email](#email), [updates](#updates), [health](#health)
- [Error codes](#error-codes)

## Basics

### Requests

- Every endpoint is under `/api/v1`, except [health](#health). An unknown path under `/api/` is
  `404 no_such_endpoint`; any other path serves the web app.
- Request and response bodies are JSON, except [images](#images), which are uploaded and
  downloaded as raw bytes. Unknown fields in a request are ignored, so newer clients
  keep working with older servers. Bodies can be up to 4 MiB; images have their own
  [limit](#images).
- Timestamps are milliseconds since the Unix epoch.
- `GET /notes`, `/sync`, `/search` and `/filters` are gzipped for clients that send
  `Accept-Encoding: gzip`.
- Every request is logged on one line, without its query string, so search terms aren't logged.

### Compatibility

Apps and servers of different releases meet all the time: a phone updates before its server, or the
other way round. These rules keep them working together.

- **Versions.** Every response carries `X-Notesd-Server: <version>`, such as `v1.2.0`.
  [`GET /auth/status`](#auth) also returns `serverVersion` and `apiVersion`, without signing in.
  `apiVersion` is `1` for every 1.x server and only changes with an incompatible API, alongside a
  new path prefix.
- **Clients say what they are** with `X-Notesd-Client: <platform>/<version>` on every request, such
  as `android/1.2.0`, `ios/1.2.0` or `web/1.2.0`. The Android and iPhone apps also send
  `User-Agent: notesd-<platform>/<version>`.
- **Too old.** A server may answer `426 upgrade_required`, with the oldest version it accepts in
  `minVersion`, to a client whose `X-Notesd-Client` is older than that. It never does so to a client
  that doesn't send the header, and never on `/auth/status`. Apps pause syncing, keep every change,
  and ask to update. 1.0 servers accept every version.
- **Additive only within `v1`.** New releases add endpoints, optional request fields, response
  fields and error codes; they don't remove or rename them, or change what an existing field means.
  Clients ignore response fields they don't know. Servers ignore unknown request fields, except
  where a value decides how data is read: an unknown [lock version](#locked-notes) is stored only as
  an opaque locked body, and an unknown [key derivation](#locked-notes) is refused.
- **Unknown errors are temporary.** Apps drop a queued change only for an error code that means the
  change itself can never be accepted (`invalid_request`, `hash_mismatch`, `image_too_large`,
  `unsupported_image`, `space_default`). Any other code, or an answer that isn't notesd's own JSON
  (a proxy's login page, a captive portal, a redirect), keeps the change for later. A change that
  fails this way 3 times is set aside, still on the device, so the ones behind it can sync.
- **Versioned formats.** A locked body starts with `notesd:locked:` and its version (`v1`); a
  locked image's type is `application/vnd.notesd.locked`, with later versions as a subtype
  (`application/vnd.notesd.locked.v2`). Apps treat every version as locked and open the ones they
  don't know read-only, asking to update.

### Authentication

There are two kinds of session. Get either from [`POST /auth/login`](#auth); every endpoint
except `/auth/status`, `/auth/setup`, `/auth/login`, `/auth/refresh` and the two
[`/auth/setup-link`](#people) ones needs one.

**Browsers** (`"client": "web"`) get an HttpOnly session cookie that expires after 30 days
without use, and a `csrfToken`. Every request other than `GET`, `HEAD` and `OPTIONS` must send it
as `X-CSRF-Token`, and is rejected if its `Origin` header names another host than the request's
`Host` or `X-Forwarded-Host`.

**Apps** (`"client": "device"`) get an `accessToken` that lasts 1 hour and a `refreshToken`
that lasts 180 days from its last use. Send the access token as `Authorization: Bearer <token>`,
and trade the refresh token for a new one at [`POST /auth/refresh`](#auth). The refresh token
isn't rotated, so retrying a refresh whose response was lost is safe.

**Rate limit.** After 10 failed sign-ins (or password changes, or unknown setup links) in 15 minutes from one address,
those requests get `429 rate_limited` with `Retry-After`. Behind a reverse proxy every client has
the proxy's address, so they share the limit.

### Writing notes

Creating, updating and deleting a note are idempotent and checked against the note's revision.

- **`operationId`** is required on every write: 1–128 characters of `[A-Za-z0-9_.:-]`, unique
  per write. Sending the same ID and body again returns the original response with
  `Idempotent-Replayed: true`, even if the note has changed since. The same ID with another body
  is `422 operation_id_reused`; key order and whitespace don't make a body different.
- **`baseRevision`** is the revision the edit started from. If the note has a newer one, the write
  is refused with `409 revision_conflict`, so an edit never overwrites one it hasn't seen. The
  exception: deleting a note that's already deleted succeeds as `already_deleted`, whatever
  `baseRevision` says.
- **Note IDs** are chosen by the client: 1–64 characters of `[A-Za-z0-9_-]`. A title can be up to
  1 KiB and content up to 1 MiB, as UTF-8 without NUL characters. For a
  [locked note](#locked-notes), the 1 MiB applies to the body before encryption, so its
  encrypted `content` can be somewhat longer.
- **`createdAt` and `updatedAt`** may be sent, so edits made offline show when they happened. They
  are informational only, and a time more than a day in the future is replaced with the server's.

Every write answers with the result:

```json
{
  "id": "n_8f2c",
  "status": "updated",
  "revision": 4,
  "changeSeq": 1187,
  "updatedAt": 1767225600000,
  "spaceId": "default"
}
```

| `status` | HTTP | Meaning |
| --- | --- | --- |
| `created` | 201 | The note was created |
| `updated` | 200 | A new revision was stored |
| `unchanged` | 200 | Same title, content and space as stored; no new revision |
| `deleted` | 200 | The note is now a tombstone; `deletedAt` is set |
| `already_deleted` | 200 | It was already deleted |

A conflict is a `409` with the server's latest version of the note in `current`, so the client
can merge without another request:

| `error` | When | What clients do |
| --- | --- | --- |
| `revision_conflict` | `baseRevision` is older than the note's revision (also for deletes) | Merge, or save the local version as a "Conflicted copy" |
| `note_deleted` | The note was deleted | Save the edit as a "Recovered copy" |
| `note_exists` | A create used an ID that exists, or existed | Pick another ID |

### Errors

Every error has the same shape. Only `error` is always there:

```json
{
  "error": "invalid_request",
  "message": "title exceeds 1024 bytes",
  "field": "title"
}
```

| Field | Present |
| --- | --- |
| `error` | Always: a code from the [list below](#error-codes). Branch on this |
| `message` | Usually: a human-readable explanation |
| `field` | On `invalid_request`: which field or parameter is wrong |
| `current` | On note conflicts and `GET /notes/{id}` of a deleted note: the server's [note](#notes) |
| `generation` | On `generation_mismatch`: the server's sync generation |
| `count` | On `space_not_empty`: how many notes the space still holds |

## Endpoints

Paths are relative to `/api/v1`.

### Auth

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/auth/status` | → `{setupRequired, serverVersion, apiVersion}`. `setupRequired` is `true` while the server has no account |
| `POST` | `/auth/setup` | `{username, password}` → `201`. Creates the first account, an admin, for whoever calls it first. Later: `409 already_set_up` |
| `POST` | `/auth/login` | `{username, password, client, deviceName?}` → a [session](#session-response) with `serverVersion`. `client` is `web` or `device`; `deviceName` defaults to the `User-Agent` |
| `POST` | `/auth/refresh` | `{refreshToken}` → a [session](#session-response) with a new `accessToken` and `serverVersion` |
| `POST` | `/auth/logout` | → `204`. Ends this session |
| `GET` | `/auth/session` | → this [session](#session-response), with `csrfToken` for browsers and `serverVersion` |
| `POST` | `/auth/password` | `{currentPassword, newPassword}` → `204`. Signs out every other session |
| `GET` | `/auth/sessions` | → `{sessions: [{id, kind, name, createdAt, lastUsedAt}], currentId}`: the account's signed-in browsers and devices |
| `DELETE` | `/auth/sessions/{id}` | → `204`. Signs that session out |

Usernames are 1–64 characters without control characters; passwords at least 8 characters.
Accounts after the first are added by an admin, from [People](#people) or with
[`notesd add-user`](cli.md#add-user).

#### Session response

```json
{
  "session": { "id": "…", "kind": "device", "name": "Pixel 9", "createdAt": 1767225600000, "lastUsedAt": 1767225600000 },
  "userId": 2,
  "username": "sam",
  "isAdmin": false,
  "accessToken": "…",
  "accessExpiresAt": 1767229200000,
  "refreshToken": "…",
  "refreshExpiresAt": 1782777600000,
  "serverVersion": "v1.2.0"
}
```

Browsers get `csrfToken` instead of the four token fields, and the cookie. `userId` is the
account's id, as notes' `updatedBy` and shared spaces' members name it.

### Notes

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/notes` | → `{notes: [summary], nextCursor, hasMore, total}`. Live notes, newest first |
| `POST` | `/notes` | `{operationId, id, title, content, spaceId?, conflictOf?, imageIds?, createdAt?, updatedAt?}` → `201` [result](#writing-notes) |
| `GET` | `/notes/{id}` | → the note. A deleted note is `410 note_deleted`, with the tombstone in `current` |
| `PUT` | `/notes/{id}` | `{operationId, baseRevision, title, content, spaceId?, imageIds?, updatedAt?}` → [result](#writing-notes) |
| `DELETE` | `/notes/{id}` | `{operationId, baseRevision}` → [result](#writing-notes) |
| `POST` | `/notes/{id}/move` | `{operationId, baseRevision, spaceId}` → [result](#writing-notes), with the note's revision where it went. Moves it to a space in another file: into or out of a [shared space](#shared-spaces) |

A note:

```json
{
  "id": "n_8f2c",
  "title": "Pancakes",
  "content": "2 eggs, 1 cup flour, 1 cup milk. #recipes",
  "revision": 4,
  "changeSeq": 1187,
  "createdAt": 1767139200000,
  "updatedAt": 1767225600000,
  "deletedAt": null,
  "spaceId": "default"
}
```

`content` is the Markdown body, without the title. A deleted note (a tombstone) has `deletedAt`
set and an empty title and content. `conflictOf` is set on a conflicted or recovered copy, with
the ID of the note it came from. `imageIds` is only on [locked notes](#locked-notes).
`updatedBy` is the id of the account that last changed it, absent for changes from before it was
recorded. `movedTo` is on a tombstone a [move](#shared-spaces) left: the space the note went to.

**`GET /notes` parameters:**

| Parameter | Description |
| --- | --- |
| `limit` | Notes per page: default 50, at most 200 |
| `cursor` | `nextCursor` from the previous page. Opaque |
| `tag` | Only notes with this tag or one nested under it. With or without `#` |
| `untagged=true` | Only notes without tags |
| `locked=true` | Only locked notes |
| `space` | Only notes in this space. Combines with any of the above |

`tag`, `untagged` and `locked` exclude each other. `total` counts every note that matches, not
just this page. A summary has `id`, `title`, `revision`, `createdAt`, `updatedAt`, `locked`,
`spaceId`, and `preview`: up to 160 characters of plain text, one line per block (paragraph, list item, heading), empty for
locked notes.

**Spaces.** `spaceId` on create puts the note in that space; left out, it goes in `default`. On
update, `spaceId` moves the note, and leaving it out keeps it where it is. If the space doesn't
exist, the note goes in `default`. The result's `spaceId` says where it ended up. A space whose
notes are in another file (a [shared space](#shared-spaces), or your own space when writing in a
shared one) is refused with `409 space_elsewhere` and its `spaceId`: send the note there, or move
it with `/notes/{id}/move`.

### Sync

```text
GET /sync?since=<cursor>&limit=<n>&generation=<generation>
```

```json
{ "changes": [note, …], "nextCursor": 1187, "hasMore": false, "generation": "…" }
```

Returns every note changed after `since`, oldest change first, each as its latest full state,
deleted notes included as tombstones. Start with `since=0` and no `generation`, then store
`nextCursor` and `generation` and send them back next time; repeat while `hasMore` is true.
`limit` defaults to 100, at most 500.

`409 generation_mismatch` (with the server's `generation`) or `409 cursor_ahead` means the
server's data was replaced, usually restored from a backup. The client must then reconcile in
full: download everything, keep its unsynced edits, and never treat a note missing from the
server as deleted. See [architecture §5](../contributing/architecture.md#5-synchronization-protocol)
for the whole protocol.

### Search and filters

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/search?q=` | `{results: [{id, title, excerpt, updatedAt, locked, spaceId}], otherSpaces?}` |
| `GET` | `/filters` | `{tags: [{key, name, count}], locked}`: what the sidebar shows |

**`/search`** splits `q` (up to 500 bytes) into words and finds notes containing words that start
with each, ranked by relevance. Search syntax in `q` is treated as plain text, so it never fails.
`excerpt` is HTML-escaped text around the matches, with each match in `<mark>`. Locked notes match
on their title only, with an empty excerpt. Parameters: `limit` (default 30, at most 50),
`offset` (at most 10,000), the same `tag`, `untagged`, `locked` and `space` filters as
[`GET /notes`](#notes), or `exceptSpace` to search every space but one. With `space` and
`offset=0`, `otherSpaces` counts the matches in every other space.

**`/filters`** lists every tag in live notes, sorted by `key` (the lowercase form), including the
parents of nested tags. `count` is the number of notes with the tag or one nested under it.
`locked` counts locked notes. With `space`, only that space's notes count.

### Spaces

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/spaces` | → `{spaces: [{id, name, icon, createdAt, noteCount, traits}]}`: `default` first, then by name. `traits` is `[{key, label, summary, exposure?, problem?}]` in a fixed order ([space traits](../contributing/architecture.md#78-space-traits)) |
| `PUT` | `/spaces/{id}` | `{name, icon?}` → the space. Creates it, or renames it |
| `DELETE` | `/spaces/{id}` | → `204`, also if it's already gone |

- `id` is 1–64 characters of `[A-Za-z0-9_-]`, chosen by the client.
- `name` is trimmed, with runs of spaces collapsed, to 1–64 characters. If another space has the
  same name, ignoring case, ` 2`, ` 3`… is added.
- `icon` is a key into the apps' icon set, 0–64 characters of `[a-z0-9-]`. `''` is the app's own
  icon. Left out, the icon stays as it is.
- A space with notes in it can't be deleted (`409 space_not_empty`, with `count`), and neither can
  `default` (`409 space_default`).

### Shared spaces

A space's owner shares it, with every note in it, with other accounts on the server
([architecture §7.9](../contributing/architecture.md#79-shared-spaces)). Its notes are in a file of
their own, reached under `/spaces/{id}/`: `notes`, `notes/{note}`, `notes/{note}/move`,
`notes/{note}/email`, `images/{image}`, `sync`, `search` and `filters` work exactly as the
account's endpoints, for members only (`404 not_found` for anyone else). Sync each shared space's
feed with its own cursor and generation.

| Method | Path | Body → response |
| --- | --- | --- |
| `POST` | `/spaces/{id}/invites` | `{userId}` → `201 {id, userId, username}`. Owner only. The first invitation shares the space, moving its notes to its own file |
| `DELETE` | `/spaces/{id}/invites/{invite}` | → `204`. Owner only |
| `DELETE` | `/spaces/{id}/members/{userId}` | → `204`. The owner removes a member, or a member leaves |
| `PUT` | `/spaces/{id}/owner` | `{userId}` → `204`. The owner hands the space to another member |
| `GET` | `/invites` | → `{invites: [{id, spaceId, spaceName, spaceIcon, from: {id, username}, createdAt}]}`: waiting for you |
| `POST` | `/invites/{invite}/accept` | → the space. Joins it |
| `POST` | `/invites/{invite}/decline` | → `204` |
| `GET` | `/people` | → `{people: [{id, username}]}`: every account, to invite |

- `GET /spaces` lists shared spaces you're in with your own. A shared one adds
  `shared: {role, members: [{id, username}], invites?}`: `role` is `owner` or `member`, members
  start with the owner, and `invites` (`[{id, userId, username}]`) is there for the owner only.
  Its `shared` trait names everyone else from your side.
- Renaming, changing the icon and deleting a shared space (`PUT`/`DELETE /spaces/{id}`) are for
  its owner (`403 not_space_owner`). Names may match one of a member's own spaces; ids may not:
  a space of your own with a shared space's id is refused (`409 space_exists`).
- Sharing refuses `default` (`409 space_default`) and a space holding locked notes
  (`409 space_has_locked`, with `count`). A shared space refuses locked notes
  (`422 locked_in_shared_space`).
- The owner can't leave (`409 owner_cannot_leave`): hand the space on, or remove every member.
- A note that moved answers `409 note_moved`, with its tombstone (and `movedTo`) in `current`:
  send the change to where it went.

### Images

| Method | Path | Body → response |
| --- | --- | --- |
| `PUT` | `/images/{sha256}` | The raw image → `201 {id, contentType, size, createdAt}`, or `200` if it's already stored |
| `GET` | `/images/{sha256}` | → the image bytes |

`{sha256}` is the lowercase hex SHA-256 of the bytes, so uploading the same image again is safe.
An ID that isn't 64 lowercase hex characters is `400 invalid_request`; one that doesn't match
the bytes is `400 hash_mismatch`. `Content-Type` must be `image/jpeg`, `image/png`,
`image/webp` or `image/gif`, matching the bytes (`415 unsupported_image` otherwise), or
`application/vnd.notesd.locked` for an encrypted image. A later version of the encryption uses a
subtype, such as `application/vnd.notesd.locked.v2`, which the server stores without reading it.
Parameters on a locked type (`; v=2`) are refused with `415 unsupported_image`. Images can be up to 10 MiB
(`413 image_too_large`). `GET` answers with `ETag`,
`Cache-Control: private, max-age=31536000, immutable` and a restrictive
`Content-Security-Policy`.

Notes show images as `![alt](notesd:image/<sha256>?w=<px>&h=<px>)`. The server reads which images
a note shows from its content, or from `imageIds` for a locked note. An image no note has shown
for 30 days is deleted.

### Locked notes

A locked note's `content` is `notesd:locked:v1:` followed by one line of unpadded base64url: the
encrypted body. The server can't read it and refuses anything else after that prefix
(`400 invalid_request`, `field: content`). Any content starting with `notesd:locked:` is locked.
A later version (`notesd:locked:v2:` and so on) is stored as an opaque locked body if the rest is
one line of base64url, and refused otherwise; apps that don't know the version open it read-only. Its title stays readable: it's indexed, searchable and
tagged, and the note's `locked` is `true`. Because the server can't see a locked note's images,
creates and updates send them as `imageIds` (up to 1,000 SHA-256 IDs); leaving `imageIds` out of
an update keeps the ones stored.

The keyring holds the account's note key, wrapped (encrypted) under each way of unlocking it:

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/locks` | → `{keys: [{id, kind, credentialId?, salt, iterations, kdf, wrapped, createdAt}]}`, the password entry first |
| `PUT` | `/locks/keys/{id}` | `{kind, credentialId?, salt, iterations, kdf?, wrapped, verifier}` → the entry |
| `DELETE` | `/locks/keys/{id}` | `{verifier}` → `204` |
| `DELETE` | `/locks` | `{accountPassword}` → `204`. Forgets the whole keyring, for a forgotten lock password |

- `kind` is `password`, `recovery` or `passkey`. The first two use their kind as `id`; a passkey
  takes any other 1–64 characters of `[A-Za-z0-9_-]`.
- `salt`, `wrapped`, `credentialId` and `verifier` are unpadded base64url.
- `kdf` says how the key that wraps the note key is derived:
  `{"name": "pbkdf2-sha256", "iterations": 600000}` for `password` and `recovery` (10,000 to
  10,000,000 iterations), `{"name": "hkdf-sha256"}` for `passkey`. Without it, a `PUT` gets the
  one its kind implies, with `iterations`. An unknown name, or one that doesn't fit the kind, is
  `400 invalid_request` with `field: kdf`. `iterations` stays alongside it for apps from before 1.0.
- The first entry must be the password (`409 no_lock_password` otherwise), and it can't be
  deleted (`409 password_key_required`).
- `verifier` is HMAC-SHA256 of the note key with the message `notesd:verifier:v1`. The server
  stores it hashed with the first entry, and every later change must send the same one
  (`403 bad_verifier`), so a stolen session alone can't replace the keyring.
- `DELETE /locks` is rate-limited like a password change. Notes locked with the old key can't be
  opened again.

The design and threat model are in
[architecture §10.1](../contributing/architecture.md#101-locked-notes).

### Email

| Method | Path | Body → response |
| --- | --- | --- |
| `POST` | `/notes/{id}/email` | `{to: [address, …], revision}` → `{id}`, Resend's message ID |
| `GET` | `/settings/email` | → `{configured, from, keyHint, editable}` |
| `PUT` | `/settings/email` | `{apiKey?, from}` → the same as `GET`. Admins only |
| `DELETE` | `/settings/email` | → `204`. Admins only |

**Sending** emails the note as the server has it: HTML rendered from the Markdown, the Markdown as
plain text, and the title as the subject. Up to 10 recipients. `revision` must be the note's
current revision (`409 stale_revision` otherwise: sync, then retry). A locked note can't be sent
(`409 note_locked`). Without settings it's `409 email_not_configured`; if Resend refuses, such as
for an unverified domain, `502 email_failed` with Resend's message.

**Settings** are server-wide: one [Resend](https://resend.com) API key and sender for every
account. `apiKey` starts with `re_`, and can be left out to keep the saved one. It's never
returned: `keyHint` is its last 4 characters. `editable` says whether this account is an admin;
other accounts get `403 admin_only` when they change it.

### People

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/users` | → `{users: [{id, username, isAdmin, createdAt, pending, linkExpiresAt?}]}`, oldest first. Admins only |
| `POST` | `/users` | `{username}` → `201 {user, link: {token, expiresAt}}`. Invites someone. Admins only |
| `POST` | `/users/{id}/link` | → `{user, link: {token, expiresAt}}`, a new setup link replacing the account's last, and the account as it is now. Admins only |
| `PATCH` | `/users/{id}` | `{isAdmin}` → the account. Admins only |
| `DELETE` | `/users/{id}` | → `204`. Removes the account and signs it out. Admins only |
| `POST` | `/auth/setup-link` | `{token}` → `{username, pending}`: whose the link is. No session needed |
| `POST` | `/auth/setup-link/use` | `{token, password}` → `204`. Sets the password and signs the account out everywhere; sign in after. No session needed |

**Invited** accounts (`pending`) have no password until their setup link is used, so they can't
sign in. A **setup link** works once, for 7 days, and an account has at most one: a new one, or
the account's password changing, ends the last. Making one for an account that has a password
changes nothing until it's used, so it doubles as a password reset; the `user` that comes back
with it says which (`pending`), even if the app's list is older. Only a hash of the token is
stored, so it's returned once; the app builds the URL to send (the web app's is
`<its address>/setup#<token>`). A used, replaced or expired link is `410 link_invalid`.

An admin can't change, remove or make a link for their own account here (`409
cannot_change_self`), and the server always keeps an admin who can sign in (`409 last_admin`).
Removing an account leaves its notes database on disk, as
[`remove-user`](cli.md#remove-user) does.

### Updates

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/update` | `{current, enabled, editable, latest?, newer?, majorAvailable?, requested?}` |
| `POST` | `/update` | `204`. Admins only |

`current` is the running version. `enabled` is whether the server was started with
[`--self-update`](cli.md#serve); only then are `latest` (the newest release on GitHub with the
same major version, checked at most once an hour), `newer`, `majorAvailable` (the newest release of
a later major version, which is installed by hand) and `requested` (an update is pending) there.
`POST` asks the `notesd-update` service to install `latest`
([setup](../guides/self-hosting/upgrading.md#updating-from-settings)); without `--self-update`
it's `409 updates_disabled`.

### Health

Outside `/api/v1`, need no session, and expose no data:

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/healthz` | `200 {"status":"ok"}` while the process runs |
| `GET` | `/readyz` | `200 {"status":"ready"}` when the system database answers within 2 seconds, else `503 {"status":"unavailable"}` |

## Error codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `invalid_request` | 400 | A field or parameter is invalid; see `field` and `message` |
| `hash_mismatch` | 400 | An image's bytes don't match its SHA-256 |
| `unauthenticated` | 401 | No session, or it expired or was signed out |
| `invalid_credentials` | 401 | Wrong username or password |
| `csrf_failed` | 403 | A browser request without the right `X-CSRF-Token`, or from another origin |
| `admin_only` | 403 | Only an admin can do this |
| `not_space_owner` | 403 | Only the shared space's owner can do this |
| `bad_verifier` | 403 | The keyring changed elsewhere; unlock again first |
| `not_found` | 404 | No such note, session, lock key, image or account |
| `no_such_endpoint` | 404 | No such path under `/api/`: usually an app newer than its server |
| `note_deleted` | 409, 410 | The note was deleted; `current` has the tombstone |
| `revision_conflict` | 409 | The note changed since `baseRevision`; `current` has it |
| `note_exists` | 409 | A note with this ID exists or existed |
| `note_moved` | 409 | The note moved to another file; `current` has its tombstone, with `movedTo` |
| `space_elsewhere` | 409 | That space's notes are in another file; see `spaceId` |
| `space_has_locked` | 409 | A space holding locked notes can't be shared; see `count` |
| `owner_cannot_leave` | 409 | The owner must hand the space on, or remove every member |
| `already_member` | 409 | That account is already in the space |
| `space_exists` | 409 | A shared space has this id |
| `already_set_up` | 409 | The server already has an account |
| `username_taken` | 409 | Another account has this username |
| `cannot_change_self` | 409 | An admin can't change, remove or make a setup link for their own account from People |
| `last_admin` | 409 | That would leave no admin who can sign in |
| `space_not_empty` | 409 | The space still holds notes; see `count` |
| `space_default` | 409 | The default space can't be deleted or shared |
| `no_lock_password` | 409 | The keyring's first entry must be the password |
| `password_key_required` | 409 | The password entry can't be deleted |
| `stale_revision` | 409 | The note changed since the revision you sent; sync first |
| `note_locked` | 409 | Locked notes can't be emailed |
| `email_not_configured` | 409 | Email isn't set up on this server |
| `generation_mismatch` | 409 | The server was restored; reconcile in full |
| `cursor_ahead` | 409 | The sync cursor is ahead of the server; reconcile in full |
| `updates_disabled` | 409 | The server wasn't started with `--self-update` |
| `link_invalid` | 410 | The setup link was used, replaced or has expired |
| `image_too_large` | 413 | Images can be up to 10 MiB |
| `unsupported_image` | 415 | Not JPEG, PNG, WebP, GIF or an encrypted image, or an image's bytes don't match its type |
| `operation_id_reused` | 422 | This `operationId` was used for a different write |
| `locked_in_shared_space` | 422 | Locked notes can't be in a shared space |
| `upgrade_required` | 426 | This app is older than the server accepts; see `minVersion` and [Compatibility](#compatibility) |
| `rate_limited` | 429 | Too many failed attempts; see `Retry-After` |
| `internal_error` | 500 | Something went wrong on the server; it's in the logs |
| `email_failed` | 502 | Resend refused the email; see `message` |
