---
title: HTTP API
description: The JSON API the notesd apps use to sign in, sync, search and store notes.
---

# HTTP API

> [!NOTE]
> This is the internal API of notesd's own web, Android and iPhone apps. It isn't a supported
> interface for other programs yet, and it can change in any release. If you build on it anyway,
> pin your notesd version.

- [Basics](#basics): requests, authentication, writing notes, errors
- [Endpoints](#endpoints): [auth](#auth), [notes](#notes), [sync](#sync),
  [search and filters](#search-and-filters), [spaces](#spaces), [images](#images),
  [locked notes](#locked-notes), [email](#email), [updates](#updates), [health](#health)
- [Error codes](#error-codes)

## Basics

### Requests

- Every endpoint is under `/api/v1`, except [health](#health). An unknown path under `/api/` is
  `404 not_found`; any other path serves the web app.
- Request and response bodies are JSON, except [images](#images), which are uploaded and
  downloaded as raw bytes. Unknown fields in a request are ignored, so newer clients
  keep working with older servers. Bodies can be up to 4 MiB; images have their own
  [limit](#images).
- Timestamps are milliseconds since the Unix epoch.
- `GET /notes`, `/sync`, `/search` and `/filters` are gzipped for clients that send
  `Accept-Encoding: gzip`.
- Every request is logged on one line, without its query string, so search terms aren't logged.

### Authentication

There are two kinds of session. Get either from [`POST /auth/login`](#auth); every endpoint
except `/auth/status`, `/auth/setup`, `/auth/login` and `/auth/refresh` needs one.

**Browsers** (`"client": "web"`) get an HttpOnly session cookie that expires after 30 days
without use, and a `csrfToken`. Every request other than `GET`, `HEAD` and `OPTIONS` must send it
as `X-CSRF-Token`, and is rejected if its `Origin` header names another host than the request's
`Host` or `--public-url`.

**Apps** (`"client": "device"`) get an `accessToken` that lasts 1 hour and a `refreshToken`
that lasts 180 days from its last use. Send the access token as `Authorization: Bearer <token>`,
and trade the refresh token for a new one at [`POST /auth/refresh`](#auth). The refresh token
isn't rotated, so retrying a refresh whose response was lost is safe.

**Rate limit.** After 10 failed sign-ins (or password changes) in 15 minutes from one address,
those requests get `429 rate_limited` with `Retry-After`. Behind a reverse proxy every client has
the proxy's address, so they share the limit.

### Writing notes

Creating, updating and deleting a note are idempotent and checked against the note's revision.

- **`operationId`** is required on every write: 1–128 characters of `[A-Za-z0-9_.:-]`, unique
  per write. Sending the same ID and body again returns the original response with
  `Idempotent-Replayed: true`, even if the note has changed since. The same ID with another body
  is `422 operation_id_reused`.
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
| `GET` | `/auth/status` | → `{setupRequired}`: `true` while the server has no account |
| `POST` | `/auth/setup` | `{username, password}` → `201`. Creates the first account, an admin, for whoever calls it first. Later: `409 already_set_up` |
| `POST` | `/auth/login` | `{username, password, client, deviceName?}` → a [session](#session-response). `client` is `web` or `device`; `deviceName` defaults to the `User-Agent` |
| `POST` | `/auth/refresh` | `{refreshToken}` → a [session](#session-response) with a new `accessToken` |
| `POST` | `/auth/logout` | → `204`. Ends this session |
| `GET` | `/auth/session` | → this [session](#session-response), with `csrfToken` for browsers and `serverVersion` |
| `POST` | `/auth/password` | `{currentPassword, newPassword}` → `204`. Signs out every other session |
| `GET` | `/auth/sessions` | → `{sessions: [{id, kind, name, createdAt, lastUsedAt}], currentId}`: the account's signed-in browsers and devices |
| `DELETE` | `/auth/sessions/{id}` | → `204`. Signs that session out |

Usernames are 1–64 characters without control characters; passwords at least 8 characters.
Accounts after the first are added with [`notesd add-user`](cli.md#add-user).

#### Session response

```json
{
  "session": { "id": "…", "kind": "device", "name": "Pixel 9", "createdAt": 1767225600000, "lastUsedAt": 1767225600000 },
  "username": "sam",
  "isAdmin": false,
  "accessToken": "…",
  "accessExpiresAt": 1767229200000,
  "refreshToken": "…",
  "refreshExpiresAt": 1782777600000
}
```

Browsers get `csrfToken` instead of the four token fields, and the cookie.

### Notes

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/notes` | → `{notes: [summary], nextCursor, hasMore, total}`. Live notes, newest first |
| `POST` | `/notes` | `{operationId, id, title, content, spaceId?, conflictOf?, imageIds?, createdAt?, updatedAt?}` → `201` [result](#writing-notes) |
| `GET` | `/notes/{id}` | → the note. A deleted note is `410 note_deleted`, with the tombstone in `current` |
| `PUT` | `/notes/{id}` | `{operationId, baseRevision, title, content, spaceId?, imageIds?, updatedAt?}` → [result](#writing-notes) |
| `DELETE` | `/notes/{id}` | `{operationId, baseRevision}` → [result](#writing-notes) |

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
exist, the note goes in `default`. The result's `spaceId` says where it ended up.

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
| `GET` | `/spaces` | → `{spaces: [{id, name, icon, createdAt, noteCount}]}`: `default` first, then by name |
| `PUT` | `/spaces/{id}` | `{name, icon?}` → the space. Creates it, or renames it |
| `DELETE` | `/spaces/{id}` | → `204`, also if it's already gone |

- `id` is 1–64 characters of `[A-Za-z0-9_-]`, chosen by the client.
- `name` is trimmed, with runs of spaces collapsed, to 1–64 characters. If another space has the
  same name, ignoring case, ` 2`, ` 3`… is added.
- `icon` is a key into the apps' icon set, 0–64 characters of `[a-z0-9-]`. `''` is the app's own
  icon. Left out, the icon stays as it is.
- A space with notes in it can't be deleted (`409 space_not_empty`, with `count`), and neither can
  `default` (`409 space_default`).

### Images

| Method | Path | Body → response |
| --- | --- | --- |
| `PUT` | `/images/{sha256}` | The raw image → `201 {id, contentType, size, createdAt}`, or `200` if it's already stored |
| `GET` | `/images/{sha256}` | → the image bytes |

`{sha256}` is the lowercase hex SHA-256 of the bytes, so uploading the same image again is safe.
An ID that isn't 64 lowercase hex characters is `400 invalid_request`; one that doesn't match
the bytes is `400 hash_mismatch`. `Content-Type` must be `image/jpeg`, `image/png`,
`image/webp` or `image/gif`, matching the bytes (`415 unsupported_image` otherwise), or
`application/vnd.notesd.locked` for an encrypted image. Images can be up to 10 MiB
(`413 image_too_large`). `GET` answers with `ETag`,
`Cache-Control: private, max-age=31536000, immutable` and a restrictive
`Content-Security-Policy`.

Notes show images as `![alt](notesd:image/<sha256>?w=<px>&h=<px>)`. The server reads which images
a note shows from its content, or from `imageIds` for a locked note. An image no note has shown
for 30 days is deleted.

### Locked notes

A locked note's `content` is `notesd:locked:v1:` followed by one line of unpadded base64url: the
encrypted body. The server can't read it and refuses anything else after that prefix
(`400 invalid_request`, `field: content`). Its title stays readable: it's indexed, searchable and
tagged, and the note's `locked` is `true`. Because the server can't see a locked note's images,
creates and updates send them as `imageIds` (up to 1,000 SHA-256 IDs); leaving `imageIds` out of
an update keeps the ones stored.

The keyring holds the account's note key, wrapped (encrypted) under each way of unlocking it:

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/locks` | → `{keys: [{id, kind, credentialId?, salt, iterations, wrapped, createdAt}]}`, the password entry first |
| `PUT` | `/locks/keys/{id}` | `{kind, credentialId?, salt, iterations, wrapped, verifier}` → the entry |
| `DELETE` | `/locks/keys/{id}` | `{verifier}` → `204` |
| `DELETE` | `/locks` | `{accountPassword}` → `204`. Forgets the whole keyring, for a forgotten lock password |

- `kind` is `password`, `recovery` or `passkey`. The first two use their kind as `id`; a passkey
  takes any other 1–64 characters of `[A-Za-z0-9_-]`.
- `salt`, `wrapped`, `credentialId` and `verifier` are unpadded base64url.
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

### Updates

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/update` | `{current, enabled, editable, latest?, newer?, requested?}` |
| `POST` | `/update` | `204`. Admins only |

`current` is the running version. `enabled` is whether the server was started with
[`--self-update`](cli.md#serve); only then are `latest` (the newest release on GitHub, checked at
most once an hour), `newer` and `requested` (an update is pending) there. `POST` asks the
`notesd-update` service to install the newest release
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
| `bad_verifier` | 403 | The keyring changed elsewhere; unlock again first |
| `not_found` | 404 | No such note, session, lock key, image or endpoint |
| `note_deleted` | 409, 410 | The note was deleted; `current` has the tombstone |
| `revision_conflict` | 409 | The note changed since `baseRevision`; `current` has it |
| `note_exists` | 409 | A note with this ID exists or existed |
| `already_set_up` | 409 | The server already has an account |
| `username_taken` | 409 | Another account has this username |
| `space_not_empty` | 409 | The space still holds notes; see `count` |
| `space_default` | 409 | The default space can't be deleted |
| `no_lock_password` | 409 | The keyring's first entry must be the password |
| `password_key_required` | 409 | The password entry can't be deleted |
| `stale_revision` | 409 | The note changed since the revision you sent; sync first |
| `note_locked` | 409 | Locked notes can't be emailed |
| `email_not_configured` | 409 | Email isn't set up on this server |
| `generation_mismatch` | 409 | The server was restored; reconcile in full |
| `cursor_ahead` | 409 | The sync cursor is ahead of the server; reconcile in full |
| `updates_disabled` | 409 | The server wasn't started with `--self-update` |
| `image_too_large` | 413 | Images can be up to 10 MiB |
| `unsupported_image` | 415 | Not JPEG, PNG, WebP, GIF or an encrypted image, or an image's bytes don't match its type |
| `operation_id_reused` | 422 | This `operationId` was used for a different write |
| `rate_limited` | 429 | Too many failed attempts; see `Retry-After` |
| `internal_error` | 500 | Something went wrong on the server; it's in the logs |
| `email_failed` | 502 | Resend refused the email; see `message` |
