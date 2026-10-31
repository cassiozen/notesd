# notesd web app

The browser client for [notesd](../../server/README.md): React 19,
TypeScript, Vite 8 and Tiptap 3. It builds into the server's
`internal/web/dist` and is embedded in the Go binary, so a deployment is just
that one binary; there is no separate frontend process. The design is the
prototype in [design-system/](../../design-system/). For using the web app,
see [docs/web.md](../../docs/web.md).

## Dev loop

From the repo root, `mise run dev` starts the API server (rebuilt on Go
changes), Vite at http://localhost:5173, and Gradle rebuilding the sync
engine (below) when its Kotlin changes. See
[docs/development.md](../../docs/development.md).

Vite proxies `/api`, `/healthz` and `/readyz` to `http://127.0.0.1:8080`.
Set `NOTESD_SERVER` to point it elsewhere. The proxy keeps the browser's
`Host` (`changeOrigin: false`). If you change that, every save fails with
403: the server rejects a mutation whose `Origin` host doesn't match `Host`.

A fresh dev database gets the owner `dev` / `devpassword`. To test the real
first-run onboarding, run the server by hand on an empty data dir
(`go run ./cmd/notesd --data-dir /tmp/empty` in `server/`) and open the
address it logs.

## Checks

`mise run check` at the root runs these together with the Go checks.

```bash
pnpm lint         # oxlint, warnings are errors
pnpm typecheck    # app, and config/test files (tsconfig.node.json)
pnpm test         # Vitest
pnpm e2e          # Playwright: Chromium, Firefox and WebKit (--project=chromium for one)
```

`mise run check` runs `e2e` in Chromium only.

`typecheck`, `test`, `e2e`, `dev` and `build` first build the sync engine
with Gradle (`pnpm core`), so they need the JDK from `mise.toml`.

Most tests are integration tests: they render the whole `<App/>` in jsdom
against a real notesd. The suite builds the server once per run
(`go build`, so Go must be on `PATH`), and each test starts its own instance
with an empty data directory. Conflicts are tested the real way, by editing
the same note through the API as a second client. A few things jsdom can't
do (moving focus inside ProseMirror, Backspace at a block boundary, layout)
are checked by hand in a browser.

`test/engine/` runs the sync engine in Node (the `engine` Vitest project), on
an in-memory sqlite-wasm database, against a real notesd. `e2e/` runs it in
real browsers, where it needs OPFS, workers and Web Locks: one engine for
several tabs, a reload, the tab running it closing. Those tests use
persistent profiles, because WebKit has no OPFS in an ephemeral one (nor
Safari in a private window). Playwright's Firefox didn't start on macOS 27
when this was written; run that project in the Linux image
(`mcr.microsoft.com/playwright`).

## Build and embedding

```bash
pnpm build        # typecheck, then vite build into ../../server/internal/web/dist
```

The server embeds that directory with `//go:embed` and serves it, sending
every unknown path to `index.html` so client routes (`/notes/:id`,
`/settings`) survive a reload. `dist/` is gitignored except for a `.gitkeep`
that the embed needs; the build puts it back after emptying the directory.
A server built without the web app still runs and answers web pages with a
503 "web app not built" placeholder.

`mise run build` at the root runs this, then builds the server binary.

Fonts are self-hosted through `@fontsource`; the server's Content Security
Policy doesn't allow font CDNs. The bundle is one chunk of about 790 KB
(265 KB gzipped), mostly the editor. It could be split if load time ever
matters.

## Source layout

```text
src/
  api/        fetch wrapper (CSRF token, typed errors, offline detection), endpoints
  auth/       auth gate state, onboarding, sign in
  notes/      split view: tags panel, list, editor pane, store, saver, search, tag extractor;
              source.ts is where they read and write (online-source.ts: the server;
              local-source.ts: this device's engine)
  editor/     Tiptap setup, Markdown ⇄ document, toolbar and text styles, tag styling and suggestions
  settings/   appearance, password, signed-in devices, sign out
  ui/         design-system primitives
  styles/     tokens (light and dark), base styles, fonts
  offline/    the sync engine's worker and the tabs' side of it (below)
test/         integration tests and the test server harness
e2e/          Playwright tests
```

## Editing

The editor follows the Apple Notes pattern: one document for the whole note,
with the first line as the title. The document is `title block+`. The title
line is plain text with no formatting and is saved to the note's `title`
field; the rest is serialized to Markdown as `content`. The stored note, the
sync protocol and the per-field merge are therefore the same as on every
other client.

Formatting is limited to the spec's dialect (§7.1): bold, italic,
strikethrough, three heading levels, bulleted, numbered and task lists,
quotes and links (http, https and mailto), plus images added by the apps
(§7.6). Markdown outside it, such as code, tables and other images, is shown
read-only and written back byte for byte, so opening and saving a note never
changes text written on another device.

Images come from the toolbar's picture button, a paste or a drop
(`src/images/`). Each is downscaled on a canvas (2048 px, JPEG or PNG),
encrypted when the note is locked, uploaded, and only then inserted as an
`image` node, an atom that keeps its Markdown source like a raw run. A
plain image loads straight from `/api/v1/images/<id>`; one that won't (it's
encrypted) is fetched, decrypted and shown from a `blob:` URL. Locking or
unlocking a note first re-uploads its images the other way round
(`resealImages`) and points the nodes at them; if that fails, the lock stays
as it was.
Output is canonical (`**bold**`, `-` bullets, one task syntax), and the
round trip is pinned by `shared/markdown-fixtures/roundtrip.json`.

## Tags

Tags are text in the note (`#tag`, `#multi word#`, `#parent/child`; spec
§7.5), so they need nothing from the editor but styling. `src/editor/tags.ts`
finds them in each line with the shared extractor (`src/notes/tags.ts`,
pinned by `shared/markdown-fixtures/tags.json`), skipping link text and code,
and marks them with a decoration: they stay plain, editable text.
⌘-click (Ctrl+click elsewhere) shows the notes with that tag. Typing `#`
and a letter suggests existing tags; a name with spaces is inserted closed.

The sidebar (All notes, Untagged, Locked when there are locked notes, and the
tag tree with counts) comes from `GET /filters` and is refetched after saves.
From 1100px wide it sits beside the list and can be hidden; narrower it's a
screen of its own at `/tags`, reached from the list's "‹", in the list's
place. Settings open from it. The list last shown is remembered per browser.
Choosing a tag, Untagged or Locked reloads the list and scopes search
through the server's `tag`, `untagged` and `locked` filters. A new note made while a
tag is selected starts with that tag, and is still dropped if nobody types.
Renaming and deleting tags aren't built yet.

## Saving and conflicts

Each open note has a saver (`src/notes/saver.ts`) that works independently
of React, so saves outlive the editor.

- **Autosave.** 1.5 s after the last change, the note is serialized and
  sent. Saves for a note are serialized: one request in flight, and edits
  made meanwhile go out afterwards against the revision it returned.
  Saving is meant to go unnoticed: the status pill (`src/notes/save-status.ts`)
  appears only for a save still pending after 2 s, a conflict, or a failure.
  A single unreachable-server failure counts as pending, because the first
  retry usually fixes it.
- **Retries.** A network failure or 5xx retries with backoff (2 s doubling
  to 30 s), and again on focus or when the browser comes back online. A
  retry reuses the request's `operationId`, so a save whose response was
  lost is replayed by the server instead of being applied twice. A 4xx that
  won't fix itself (for example a note that's too large) is reported, not
  retried.
- **Session ended.** A 401 keeps every edit in memory and shows a sign-in
  dialog over the open note; the waiting saves go out after sign-in.
- **Closing the tab.** Pending saves are sent with `keepalive`, and the
  browser asks before leaving while anything is unsaved.
- **Edited on another device** (`409 revision_conflict`). The title and the
  content are merged separately, against the last version this browser
  saved (§6.1). Content is compared in canonical Markdown, so another
  client's formatting style doesn't count as a change. If only one side
  changed a field, that side wins, and the merge is saved without asking.
- **Both sides changed the same field.** Nothing is saved until the user
  picks: "Use theirs" drops the local edits; "Keep mine" saves the local
  version as a new note, "<Title> (Conflicted copy)", linked to the original
  by `conflictOf`, and shows the server's version in the original.
- **Deleted on another device** (`409 note_deleted`). "Discard" drops the
  edits; "Keep as new note" saves them as "<Title> (Recovered copy)".
- **Deleting.** The note disappears at once and "Note deleted · Undo" shows
  for 5 s. The `DELETE` is sent when that passes, or with `keepalive` if the
  page unloads first. If the note was edited elsewhere in the meantime, the
  server refuses the stale delete and the note comes back.

New notes exist only in the browser until their first save with content, and
go into the URL only once the server has them. Leaving a new note nobody
typed into drops it without a request.

## Sync engine (in progress)

The web app is becoming offline-first like the phone apps, on the same sync
engine: `apps/android/core`, Kotlin compiled to JavaScript, imported as
`@notes/core`. Nothing chooses it yet: `localSource` (the screens on the
engine) is only used by tests.

- **One worker per origin** (`src/offline/worker.ts`) owns the database:
  sqlite-wasm on OPFS through its SAH-pool VFS, one file per user, in WAL
  mode. OPFS's synchronous handles exist only in dedicated workers and are
  exclusive, so a single worker can hold them.
- **Tabs** (`src/offline/tab.ts`): the one holding the `notesd-engine` Web
  Lock starts the worker; the others send their calls over a
  BroadcastChannel. When it closes, the next tab to get the lock starts a
  new worker, and unanswered calls are sent to it again. That's safe
  because every call is idempotent (ids for new notes and spaces come from the
  caller), and the new owner runs a call it already has only once.
- **Calls** (`src/offline/protocol.ts`) are coarse (list, search, save,
  sync…), so no SQL statement crosses a thread.
- **Reads** (`src/notes/local-source.ts`): the list, tags, search and spaces
  come from the engine, and refetch whenever a write commits there (sync or
  any tab). A list is read whole and paged from memory, so pages don't shift
  while scrolling.
- **Writes** (`src/notes/local-saver.ts`): saves, moves and deletes go to the
  engine, each save based on the text it last stored, so a change sync
  brought in meanwhile is merged as on the phones (§6.1). Sync keeps both
  sides of a clash itself and says so in a toast; nothing waits for the user
  but an unlock. A note open and idle follows what sync brings in, and closes
  if it was deleted elsewhere; deleted while typed into, the typing opens as a
  "(Recovered copy)". The list says how many notes are stuck while sync can't
  get through, with Sync now.
- **Locked notes** unlock offline: the keyring comes from the server, or from
  the engine's copy (sync refreshes it every cycle) when the server can't be
  reached. A keyring change made here goes into that copy straight away.
- **Pictures** (`src/offline/images.ts`) live in Cache Storage, one cache per
  user. One added here is stored there with a `pending_images` row, and sync
  uploads it before any note that shows it; others download on first view
  and stay. Shown from blob: URLs, so locking a note offline can encrypt its
  pictures whenever they're on the device.
- **Scheduling** (`src/offline/scheduler.ts`), in the worker: on start, 2 s
  after an edit, when a tab comes back or goes online, and every minute.
  Failures back off from 2 s to 5 min. Signed out, it waits for the next
  sign-in's token.

## Not built yet

- Pinning: it needs a field on the server that syncs like the others.
- Naming the other device in the conflict banner: the server doesn't record
  which session made a change.
