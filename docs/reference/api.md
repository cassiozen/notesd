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
  [locked notes](#locked-notes), [email](#email), [agents](#agents), [AI](#ai), [updates](#updates), [health](#health)
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
- **`X-Notesd-Sync`**, an optional header, says a write isn't someone editing: `initial` for what a
  device wrote before its first sync and for notes it puts back on a server restored from a backup,
  `import` for an import. Any other value also means that, so a later
  client can send a new reason. Agents don't react to these. It isn't part of the request hash.

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
  "displayName": "Sam Smith",
  "avatar": "3f9a1c0e7b2d4a65",
  "accessToken": "…",
  "accessExpiresAt": 1767229200000,
  "refreshToken": "…",
  "refreshExpiresAt": 1782777600000,
  "serverVersion": "v1.2.0"
}
```

Browsers get `csrfToken` instead of the four token fields, and the cookie. `userId` is the
account's id, as notes' `updatedBy` and shared spaces' members name it. `displayName` and
`avatar` are the account's [profile](#profile).

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
`spaceId`, `updatedBy` (when recorded), and `preview`: up to 160 characters of plain text, one line per block (paragraph, list item, heading), empty for
locked notes.

**Spaces.** `spaceId` on create puts the note in that space; left out, it goes in `default`. On
update, `spaceId` moves the note, and leaving it out keeps it where it is. If the space doesn't
exist, the note goes in `default`. The result's `spaceId` says where it ended up. Moving into or
out of a [shared space](#shared-spaces) is the same update: the server moves the note between
files, with the update's text, and answers with its revision there.

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
| `GET` | `/spaces` | → `{spaces: [{id, name, icon, createdAt, noteCount, traits}]}`: `default` first, then by name. `traits` is `[{key, label, summary, exposure?, audience?, problem?}]` in a fixed order ([space traits](../contributing/architecture.md#78-space-traits)) |
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
their own, but you reach them at your own address: writes, `GET /notes/{id}` and email find the
note wherever it is, a create goes to its space, and `?space=` on the list, filters and search
reads that space. The one exception is the change feed: each shared space has its own,
`/spaces/{id}/sync`, with its own cursor and generation, for members only (`404 not_found` for
anyone else).

| Method | Path | Body → response |
| --- | --- | --- |
| `POST` | `/spaces/{id}/invites` | `{userId}` → `201 {id, userId, username, displayName, avatar}`. Owner only. The first invitation shares the space, moving its notes to its own file |
| `DELETE` | `/spaces/{id}/invites/{invite}` | → `204`. Owner only |
| `DELETE` | `/spaces/{id}/members/{userId}` | → `204`. The owner removes a member, or a member leaves |
| `PUT` | `/spaces/{id}/owner` | `{userId}` → `204`. The owner hands the space to another member |
| `GET` | `/invites` | → `{invites: [{id, spaceId, spaceName, spaceIcon, from: {id, username, displayName, avatar}, createdAt}]}`: waiting for you |
| `POST` | `/invites/{invite}/accept` | → the space. Joins it |
| `POST` | `/invites/{invite}/decline` | → `204` |
| `GET` | `/people` | → `{people: [{id, username, displayName, avatar}]}`: every account, to invite |

- `GET /spaces` lists shared spaces you're in with your own. A shared one adds
  `shared: {role, members: [{id, username, displayName, avatar}], invites?}`: `role` is `owner` or
  `member`, members start with the owner, and `invites` (`[{id, userId, username, displayName,
  avatar}]`) is there for the owner only. `displayName` and `avatar` are each account's
  [profile](#profile). Its `shared` trait names everyone else from your side, by their chosen name.
- Renaming, changing the icon and deleting a shared space (`PUT`/`DELETE /spaces/{id}`) are for
  its owner (`403 not_space_owner`). Names may match one of a member's own spaces; ids may not:
  a space of your own with a shared space's id is refused (`409 space_exists`).
- Sharing refuses `default` (`409 space_default`). Any invitation, the first one too, refuses a
  space holding locked notes (`409 space_has_locked`, with `count`). While anyone else is in it or
  invited, a shared space refuses locked notes (`422 locked_in_exposed_space`).
- The owner can't leave (`409 owner_cannot_leave`): hand the space on, or remove every member.
- A note another member moved into a space of their own is deleted as far as you can tell: `GET`
  answers `410`, an update `409 note_deleted` (its tombstone without `movedTo`), a delete
  `already_deleted`.
- A write that keeps meeting a share or move running under it answers `503 busy`: retry.

### Published spaces

A space can be published as a small public website at `/@{handle}/`
([architecture §7.10](../contributing/architecture.md#710-published-spaces)): a page per note with a
title and a body, a list of posts, tags, a feed and a search.

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/spaces/{id}/publish` | → `{handle, url, origin, domain, published, css}`; `404 not_published` if it never was. `url` is on `domain` when it's set |
| `PUT` | `/spaces/{id}/publish` | `{handle?, origin?, domain?, css?}` → the same. Publishes the space, or changes how; a field left out stays as it is. `domain`: the site's own (`blog.example.com`), `""` to drop it. Requests for it are the site's. Setting it is an admin's (`403 admin_only`); it can't be another site's (`409 domain_taken`) or the app's own address (`409 domain_is_app`) |
| `DELETE` | `/spaces/{id}/publish` | → `204`. Takes the site down; the handle stays the space's. `404 not_published` if it never was |

- Your own spaces, and shared ones you own (`403 not_space_owner` for members). Never `default`
  (`409 space_default`).
- `origin` is the app's own address, such as `https://notes.example.com`: required the first time
  (`400`, `field: origin`). Without a `domain`, the site's absolute links (the feed's, the
  sitemap's) are made from it; with one, from `https://<domain>/`.
- `handle` is 1–64 characters of `[a-z0-9-]`, not starting or ending with `-`. Left out the first
  time, it's made from the space's name. One another space has: `409 handle_taken`.
- `css` is the owner's own stylesheet, up to 64 KB, after the site's.
- Refused while the space holds locked notes (`409 space_has_locked`, with `count`); while it's
  published, it refuses locked notes (`422 locked_in_exposed_space`).
- The space's `published` trait says where: `summary` "At notes.example/@garden".

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
`GET` at the account's address also finds an image in the [shared spaces](#shared-spaces) you're
in, so a note's pictures load from one address whichever file the note is in. Upload to your own
address too: a note written into a shared space takes the pictures it shows there with it.
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

### Profile

An account's name and photo, both optional. Without them, the apps show the username and its
initial.

| Method | Path | Body → response |
| --- | --- | --- |
| `PATCH` | `/profile` | `{displayName}` → the account. `""` goes back to the username |
| `PUT` | `/profile/avatar` | A square JPEG of at most 512 px a side, at most 1 MiB, as the body (`Content-Type: image/jpeg`) → the account |
| `DELETE` | `/profile/avatar` | → the account, without a photo |
| `GET` | `/users/{id}/avatar?v={avatar}` | → the JPEG, to anyone signed in, as accounts see each other's usernames to share spaces; `404 not_found` for an account without one |

A name is at most 64 characters, trimmed, without control characters, and needn't be unique; it
never replaces the username for signing in. `avatar` on an account is the photo's version, `""`
without one. The server encodes the photo again, so nothing but its pixels is kept (no EXIF).
With `v` the current version, the photo is cached for good; a new photo has a new version.
A photo that isn't `image/jpeg` is `415 unsupported_image`; over 1 MiB, `413 image_too_large`;
not square, over 512 px or not a readable JPEG, `400 invalid_request` with `field: avatar`.

### People

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/users` | → `{users: [{id, username, displayName, avatar, isAdmin, createdAt, pending, linkExpiresAt?}]}`, oldest first. Admins only |
| `POST` | `/users` | `{username, displayName?}` → `201 {user, link: {token, expiresAt}}`. Invites someone; `displayName` is a suggestion they can change when they use the link. Admins only |
| `POST` | `/users/{id}/link` | → `{user, link: {token, expiresAt}}`, a new setup link replacing the account's last, and the account as it is now. Admins only |
| `PATCH` | `/users/{id}` | `{isAdmin}` → the account. Admins only |
| `DELETE` | `/users/{id}` | → `204`. Removes the account and signs it out. Admins only |
| `POST` | `/auth/setup-link` | `{token}` → `{username, pending, displayName?}`: whose the link is, and for an invitation the suggested name. No session needed |
| `POST` | `/auth/setup-link/use` | `{token, password, displayName?}` → `204`. Sets the password, and an invitation's name when given (a reset ignores it), and signs the account out everywhere; sign in after. No session needed |

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
### Agents

What an app does to take part (the socket, editor triggers, applying a run's changes) is in
[agent clients](../contributing/agent-clients.md).

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/agent/spaces` | → `{spaces: [config, …]}`, every space whose agent has been set up |
| `GET` | `/agent/spaces/{id}` | → the space's config; off and empty if none was set up |
| `PUT` | `/agent/spaces/{id}` | `{enabled, prompt, menuLabel, triggers, referenceNotes?}` → the config as stored |
| `DELETE` | `/agent/spaces/{id}` | → `204`; forgets the space's agent |
| `GET` | `/settings/account` | → `{timeZone, timeZoneSource}` |
| `PUT` | `/settings/account` | `{timeZone}` → the same as `GET` |
| `GET` | `/agent/runs?space=&note=&limit=&cursor=` | → `{runs: [run, …], nextCursor, hasMore}`, newest first |
| `GET` | `/agent/usage?space=` | → `{day, tokens, runs, dailyTokenCap}`, the space's today |
| `GET` | `/agent/runs/{id}` | → the run, with `diffs` |
| `POST` | `/agent/runs/{id}/undo` | → `{undone: [noteId, …], skipped: [{noteId, reason}, …]}` |
| `GET` | `/agent/socket` | WebSocket; see below |

A **config** is `{spaceId, enabled, prompt, menuLabel, triggers, referenceNotes, updatedAt}`. `prompt` describes the
space and how its agent behaves. `triggers` maps each trigger the agent acts on to its prompt:
`noteCreated`, `editStarted`, `newNoteDone`, `editDone`, `noteDeleted`, `movedIn`, `movedOut` and
`menu`. A trigger with an empty prompt is dropped, and so is one the server doesn't know.
`menuLabel` names the `menu` command in a note's menu, up to 40 characters, and is required with a
`menu` prompt. Prompts can be up to 16 KiB. `referenceNotes` are up to 5 titles of notes in the
space, each up to 200 characters, sent with every run: the newest unlocked note with that title, if
any. `PUT` replaces the whole config, except that leaving out `referenceNotes` keeps them. A space that doesn't
exist is `404 not_found`. Deleting a space deletes its agent. A shared space has one like any other:
every member reads and changes it, and sharing a space keeps its agent, finished runs and token use.

A space whose agent is on with at least one prompt has the `agent` trait. Its `summary` names
when the agent acts ("While you write · when you’re done"); its `exposure` is the AI provider
("Sent to the AI at api.example.com", `audience` `ai:<host>`), or, with no AI set up, its
`problem` is "AI isn’t set up".

The account's **time zone** decides "today" for agents. It's `UTC` (`timeZoneSource: "default"`)
until a device says otherwise: any signed-in request may carry `X-Notesd-Time-Zone` with an IANA
name such as `Europe/Lisbon`, and the first valid one is kept (`"auto"`). `PUT` sets it (`"user"`);
a name that isn't a time zone is `400 invalid_request` on `timeZone`.

The **activity log** lists every run: queued, running and finished, kept 30 days after it
finishes. A **run** is `{id, trigger, status, spaceId, noteId, noteTitle, noteDeleted, outcome,
failure, message, model, toolCalls, promptTokens, completionTokens, createdAt, startedAt,
finishedAt, edits, overlays?, forUserId}`. `forUserId` is the account the run was for: whose editor
asked, whose write fired it, or who last wrote in the note (`0` when not recorded). `status` is `queued`, `running`, `done`, `failed` or `skipped`; `failure` says
why a run failed or was skipped, and `outcome` what a finished one did (`edited`, `created`,
`no_change`, or after undo `undone` or `partly_undone`; a suggestion accepted later makes a
`no_change` run `edited`). `message` is the model's last words, or what went wrong. `overlays`, when
the run showed any, counts them: `{cards, suggestions, accepted}`. `edits` lists the notes it
changed: `{noteId, kind, title, writtenAt, undoneAt, undoable, blocked?}`, where `kind` is `edit` or
`create` and `blocked` says why one can't be undone: `undone`, `running`, `deleted`, `locked`,
`changed` (the note changed since the run wrote it) or `not_saved` (the run changed it in an open
editor, which hasn't saved exactly that). `limit` is up to 100, 50 by default. The log covers the
account's spaces and the shared ones it's in; `space` narrows it to one. `GET /agent/usage` needs
`space` (`400 invalid_request` without), and is that space's today, in the time zone of the account
asking.

`GET /agent/runs/{id}` adds `diffs`, one per note changed: `{noteId, kind, titleBefore, titleAfter,
lines, textRemoved}`. `lines` compares the content before the run's first change with after its last: each is
`{op, text}` with `op` `=` (kept), `-` (removed) or `+` (added), `text` left out for a blank line;
or `{op: "…", count}` for unchanged lines left out, three kept around each change. Each change lists
its removed lines, then its added ones. `textRemoved` says the note was locked (its content) or
deleted (title and content) since: notesd then clears the run's copies too, and `lines` is empty.
An unknown run is `404 not_found`.

**Undo** puts back each note a finished run changed, if nobody has changed it since: an edit's
title and content are restored as a new revision, and a note the run created is deleted. Each
note is undone on its own, so one changed note doesn't stop the rest; `skipped` gives each
`reason`, as `blocked` above. Undoing again retries only what was skipped. When nothing can be
undone it's `409 nothing_to_undo`, with `skipped`. The run's `outcome` becomes `undone`, or
`partly_undone` while some of its changes stand. Undo fires no triggers.

**Asking for a run.** An editor asks for `noteCreated`, `editStarted` or `menu` runs on the socket
below (`run.request`); the others come from writes. The run works on the note only as an editor
shows it, the asking one's first, never the server's copy: with no editor showing it, before or
during the run, it's skipped as `note_closed`; an editor that disconnects while the run is changing
the note fails it as `editor_disconnected`, since the change may or may not have landed. It starts at once, beside other runs, one at a time
per note; asked again before it starts, it's the same run. A request the space's agent doesn't act
on, for a note that's gone or locked, or one the server hasn't got with no `spaceId`, is dropped.

**Done ahead.** A `noteCreated` run needs nothing of its note, which is empty, so while an editor
shows a space it asks for one in advance (`prepare`), and a new note opens with its result
(`prepared`), at once. It runs as any `noteCreated` run would, on an empty note with no cursor,
except that `done` doesn't end it: nobody waits for the call it saves, and a model that gives it too
soon still does the rest. Its
tokens count as they're spent, used or not, and it shows in the activity log only once used, as
the new note's run. It's held in memory, one per space and account, for 30 minutes and never past
midnight in the account's time zone. It's thrown away (`prepared.void`) when what it went by
changes: the parts of the space's agent setup it goes by (whether it's on, the space's prompt, the
rule, its reference notes), a note changing the space's tags, one of its reference
notes, a note it read, or, if it searched or listed notes, any note in the space; and when the AI
provider changes. One thrown away or expired is still taken for 10 seconds, for a new note that
opened with it as it went; an editor never sends one it knows is gone. A rule that changes another
note, or makes one, can't wait in memory: it isn't done ahead, and runs live, until what of the
setup it goes by changes (whether it's on, the space's prompt, the rule, its reference notes).

**The socket** is a WebSocket at `/agent/socket` for note editors, signed in as any request. A
browser must send its own `Origin`; anything else is `403 csrf_failed`. More than 50 open for one
account is `429 rate_limited`. Messages are JSON text with a `type`; a type a side doesn't know is
ignored, so either may add some, except that an editor answers a request it can't do (one with an
`id`) with `{type: "result", id, error: "not_open"}`, so the server doesn't wait out its timeout.

| From | Message | Meaning |
| --- | --- | --- |
| Server | `{type: "hello", connectionId, protocol: 1}` | Sent first |
| Editor | `{type: "editor.opened", noteId, spaceId?}` | The editor shows the note. Again after reconnecting. `spaceId` finds a new note the server hasn't got yet |
| Editor | `{type: "run.request", trigger, noteId, spaceId, note?, prepared?}` | Asks for a run on a note it has open. `spaceId` is the note's space as it shows it, which the server may not have yet; `note`, what it shows (`{spaceId, title, content, cursor, revision}`, as it answers a `read`), so the run starts without asking. For `noteCreated`, `prepared` is the id of the result done ahead the new note opened with: it becomes the note's run, finished, and nothing runs |
| Editor | `{type: "prepare", spaceId}` | The editor shows the space: its `noteCreated` run is done ahead, for the next new note, unless one is going or ready. A ready one is sent again |
| Server | `{type: "prepared", id, spaceId, title, content, overlays, expiresAt}` | A `noteCreated` run done ahead, to every editor of the account it's for: the title and content a new note opens with, and the overlays it shows (their `noteId` empty). Not to be used from `expiresAt` (Unix milliseconds) |
| Server | `{type: "prepared.void", spaceId, id}` | Don't use result `id`, or wait for it if it's still going: something it went by changed, or it was used. Ask again to have the next one |
| Server | `{type: "prepared.missed", noteId, id}` | A new note opened with result `id`, which the server no longer has: if nobody typed in it yet, the editor empties it and asks for its run live |
| Editor | `{type: "editor.closed", noteId, revision, spaceId?}` | It closed it. `revision` is the server's revision of what it last showed, or `null`; when the server has it and no other editor has the note open, the note's writing session ends now instead of after 2 idle minutes |
| Editor | `{type: "overlay.answer", noteId, id, answer, before?, revision?, shows?}` | The user answered overlay `id`: `dismiss` for a card; `accept`, `reject` or `stale` (it no longer fits) for a suggestion. An accepted one's change was made in the editor as for an `apply`, and `before`, `revision` and `shows` are as its result's |
| Server | `{type: "overlays", noteId, overlays}` | What runs show on the note now, all of it: on `editor.opened` when there's any, and to every editor of the account (in a shared space, of every member) when a run shows more, one is answered, or the note's last editor closes it |
| Server | `{type: "run", runId, noteId, trigger, status, failure?, outcome?}` | A run started (`running`) or ended; to every editor of the account |
| Server | `{type: "read", id, noteId}` | A run reads the note. The editor answers `{type: "result", id, spaceId, title, content, cursor, revision}`: `content` as it would save it, `cursor` in Unicode code points into it, `revision` the server's of exactly this or `null` |
| Server | `{type: "apply", id, noteId, runId, before: {title, content}, after: {title, content}, op}` | A run changes the note. If the editor shows `before`, it changes it to `after`; otherwise, the user having typed meanwhile, it makes `op` to what it shows. As one undoable step, saved as any edit. It answers `{type: "result", id, before, revision, shows}`: what it showed just before (`revision`: the server's revision of exactly that, or `null`), and the note as it then saves it |

`op` is the change as the agent asked for it: `{kind: "title", title}`, `{kind: "replace", find,
replace}` (find must appear exactly once), or `{kind: "insert", at, text}` with `at` `start` or
`end` (as a block of its own) or `cursor` (at the editor's cursor), or `{kind: "content", text}`
(the whole content, which never fits once the user has typed). An editor that can't do a request
answers `{type: "result", id, error}`: `changed` (`op` no longer fits), `not_open` or `locked`. Ten seconds without an answer counts as gone. The server
pings every 25 seconds; a proxy in front must pass WebSocket upgrades.

An **overlay** is something a run shows beside a note an editor has open, never in its text:
`{id, runId, noteId, kind, place, body, change?}`. `kind` is `card`, with `body` Markdown to read,
or `suggestion`, with `change` an `op` the user may accept and `body` why (possibly empty). `place`
is `{at}` with `at` `top`, `end` or `cursor` (where the cursor was when it showed), or `{after}`, a
quote from the note's content whose block it goes under; an editor that can't find the quote shows
it at the top. Overlays close when the note's last editor closes it, when a later run of the same
rule shows its own, or when the note is locked or deleted. Locking or deleting sends no `overlays`: an editor stops
showing a note's overlays once it's locked, and closes a deleted one. An editor forgets them when its
connection drops, and hears them again on `editor.opened`.

### AI

| Method | Path | Body → response |
| --- | --- | --- |
| `GET` | `/settings/ai` | → `{configured, baseUrl, model, keyHint, dailyTokenCap, editable}` |
| `PUT` | `/settings/ai` | `{baseUrl, apiKey?, model, dailyTokenCap?}` → the same as `GET`. Admins only |
| `DELETE` | `/settings/ai` | → `204`. Admins only |
| `POST` | `/settings/ai/test` | `{baseUrl, apiKey?, model}`, or no body → `{ok, model, latencyMs}`. Admins only |
| `POST` | `/settings/ai/models` | `{baseUrl, apiKey?}` → `{models: [{id}]}`. Admins only |

**Settings** are server-wide: one OpenAI-compatible provider for every account's
[agents](../guides/using/agents.md). `baseUrl` is where requests go, without `/chat/completions`
(a trailing one is removed); plain `http` is accepted only for a loopback, private or link-local
address, a `.local` name or a name with no dot. `apiKey` can be left out to keep the saved one; it's never returned,
and `keyHint` is its last 4 characters, or empty for a key shorter than 8. `dailyTokenCap` is tokens per space per day, 1,000,000
when left out or `0`. `editable` says whether this account is an admin; other accounts get
`403 admin_only` when they change it.

**Testing** sends one small request that asks the model to call a tool, with the settings in the
body before they're saved (`apiKey` left out uses the saved key), or the saved settings when there
is no body. `model` in the answer is the model as the provider names it. The test fails with
`502 ai_test_failed` and a `message` for the admin when the provider can't be reached, refuses the
request, or the model answers without calling the tool. With no body and nothing saved, it's
`409 ai_not_configured`.

**Listing models** asks the provider's `GET /models` with the address and key in the body (`apiKey`
left out uses the saved key), so a model can be picked before anything is saved. `baseUrl` is
checked as when saving. `models` is sorted by `id`; where the provider says which parameters a
model supports (OpenRouter), models without tool calls are left out. The provider refusing, not
answering within 30 seconds, or answering with something other than a model list is
`502 ai_failed` with a `message` for the admin.

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
| `not_found` | 404 | No such note, session, lock key, image, account, profile photo, space or agent run |
| `no_such_endpoint` | 404 | No such path under `/api/`: usually an app newer than its server |
| `not_published` | 404 | The space has never been published |
| `note_deleted` | 409, 410 | The note was deleted; `current` has the tombstone |
| `revision_conflict` | 409 | The note changed since `baseRevision`; `current` has it |
| `note_exists` | 409 | A note with this ID exists or existed |
| `space_has_locked` | 409 | A space holding locked notes can't be shared or published; see `count` |
| `owner_cannot_leave` | 409 | The owner must hand the space on, or remove every member |
| `already_member` | 409 | That account is already in the space |
| `space_exists` | 409 | A shared space has this id |
| `space_busy` | 409 | The shared space is in use; try deleting it again |
| `already_set_up` | 409 | The server already has an account |
| `username_taken` | 409 | Another account has this username |
| `cannot_change_self` | 409 | An admin can't change, remove or make a setup link for their own account from People |
| `last_admin` | 409 | That would leave no admin who can sign in |
| `space_not_empty` | 409 | The space still holds notes; see `count` |
| `space_default` | 409 | The default space can't be deleted, shared or published |
| `handle_taken` | 409 | Another published space has that address |
| `domain_taken` | 409 | Another published space has that domain |
| `domain_is_app` | 409 | That domain is the app's own address |
| `no_lock_password` | 409 | The keyring's first entry must be the password |
| `password_key_required` | 409 | The password entry can't be deleted |
| `stale_revision` | 409 | The note changed since the revision you sent; sync first |
| `note_locked` | 409 | Locked notes can't be emailed |
| `email_not_configured` | 409 | Email isn't set up on this server |
| `ai_not_configured` | 409 | AI isn't set up on this server |
| `nothing_to_undo` | 409 | None of the run's changes can be undone; see `skipped` |
| `generation_mismatch` | 409 | The server was restored; reconcile in full |
| `cursor_ahead` | 409 | The sync cursor is ahead of the server; reconcile in full |
| `updates_disabled` | 409 | The server wasn't started with `--self-update` |
| `link_invalid` | 410 | The setup link was used, replaced or has expired |
| `image_too_large` | 413 | Images can be up to 10 MiB; a profile photo up to 1 MiB |
| `unsupported_image` | 415 | Not JPEG, PNG, WebP, GIF or an encrypted image, or an image's bytes don't match its type |
| `operation_id_reused` | 422 | This `operationId` was used for a different write |
| `locked_in_exposed_space` | 422 | Locked notes can't be in a space others can read (a trait exposes it) |
| `upgrade_required` | 426 | This app is older than the server accepts; see `minVersion` and [Compatibility](#compatibility) |
| `rate_limited` | 429 | Too many failed attempts; see `Retry-After` |
| `internal_error` | 500 | Something went wrong on the server; it's in the logs |
| `email_failed` | 502 | Resend refused the email; see `message` |
| `busy` | 503 | A note kept moving between files during the write; retry |
| `ai_test_failed` | 502 | The AI provider failed the test; see `message` |
| `ai_failed` | 502 | The AI provider refused or failed a request; see `message` |
