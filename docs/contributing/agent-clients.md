---
title: Agent clients
description: "What an app does to take part in a space's agent: the socket, editor triggers, and applying a run's changes."
---

# Agent clients

What an app has to do to take part in a space's agent, and what it gets without doing anything. The
web app does all of it; Android and iOS do none of it yet. Read this before adding agent support to
an app.

The server is the authority: it decides when an agent acts, runs the model with the AI key only it
holds, and records every run. An app only tells the server what the user has open, answers its
questions about those notes, asks for the three runs that start in an editor, and shows what's
going on. The message formats are in the [API reference](../reference/api.md#agents); the server's
side is in the [architecture](architecture.md#78-agents), §7.8.

## What works with no agent support

Most of it, because the triggers that matter most fire from note writes, and every write reaches
the server, by HTTP or by sync:

- **Done writing** (*new note done*, *note edit done*): a writing session starts with a create or an
  edit and ends 2 minutes after the user's last change.
- **Note deleted**, **moved in** and **moved out**.
- **The agent's changes** are ordinary note writes on the server, so they reach every device with
  sync, like an edit from another device.

What an app must still do, even with no agent support:

- Send **`X-Notesd-Time-Zone`** (the device's IANA zone) on every request. The account takes the
  first one it hears as the "today" its agents go by. The Kotlin engine (`ClientInfo.timeZone`) and
  the iOS `ApiClient` already do.
- Send **`X-Notesd-Sync: initial`** on a device's first sync, and `import` for an import, so those
  writes start no session and fire no trigger.

What it doesn't get:

- **The agent writes under the user.** With no editor connection, a run changes the server's copy.
  A note open with unsaved typing then meets the change as any edit from another device does, through
  sync's conflict handling, instead of the change landing in the editor as one step the user can
  undo.
- **No editor triggers:** *note created*, *started editing* and the space's menu command need the
  socket.
- **No live progress:** the app sees what a run did when sync brings it, not while it runs.

## Levels of support

An app can add support in steps; each works on its own.

| Level | The app | The user gets |
| --- | --- | --- |
| 0 | Sends the headers above | Background triggers; the agent's changes arrive with sync |
| 1 | Opens the socket, says which notes are open, answers `read` and `apply` | Runs read unsaved typing and write in the editor, as one undoable step; closing a note ends its writing session at once |
| 2 | Asks for editor triggers; shows `run` updates | *Note created*, *started editing* and the menu command; the agent shown at work, and a failed run on the note |

## The socket

One WebSocket per app instance (a web tab is one), at `GET /api/v1/agent/socket`, signed in as any
request. An app authenticates with its bearer token and sends no `Origin`; a browser uses its session
cookie and must send its own origin.

### When it's open

Open it only while someone could use it:

- AI is set up on the server (`GET /settings/ai` → `configured`), **and**
- the space the app shows has its agent on (`GET /agent/spaces` → that space's `enabled`).

On the web, moving between two spaces whose agents are on keeps the same socket, and leaving for a
space without one closes it. A phone app should also close it in the background: a hidden app has no
editor to answer, and the runs that need one are skipped or fall back to the server's copy.

The note an editor shows is always in the space the app shows, which is what makes the space a
good rule: opening a search result from another space switches to that space first, and switching
space closes the open note.

### Connecting

The server's first message is `{type: "hello", connectionId, protocol: 1}`. Send nothing before it;
on it, send `editor.opened` again for every note the app has open (a reconnect starts with nothing on
the server), then any run requests held while connecting (below).

The server pings every 25 seconds, under the 60-second idle timeout of common proxies; a WebSocket
library answers pings by itself. On each ping the server checks the session, and closes the socket
with 1008 (*policy violation*) once it's signed out or expired. More than 50 sockets for one account
is `429 rate_limited` on the upgrade. A message is at most 4 MiB.

### Reconnecting

After a drop, connect again after 1 second, doubling to at most 30, each wait taken at 50–100% at
random so a server's restart isn't met by every app at once. Don't try while the device is offline;
try at once when it's back. The new `hello` brings a new `connectionId`.

### Closing

When the socket is no longer wanted (the user left for a space without an agent), close it once the
open note has sent its `editor.closed`, waiting at most 5 seconds: that close goes after the note's
last save, and without it the note's writing session ends on the 2-minute timer instead of at once.
Coming back before it closed keeps the same socket.

On sign-out, close it at once and forget the open notes and runs: an `editor.closed` arriving later,
from the editor of the account that signed out, must not go out on the next account's socket.

## Messages

Unknown types are ignored, both ways, so either side can add more. A `noteId` is the note's id, up
to 64 characters.

### From the app

| Message | When |
| --- | --- |
| `{type: "editor.opened", noteId}` | An editor shows the note, a new one not yet saved included |
| `{type: "editor.closed", noteId, revision}` | It no longer does, sent after its last save completes (see below) |
| `{type: "run.request", trigger, noteId, spaceId, note}` | It asks for an editor trigger's run (see [Editor triggers](#editor-triggers)) |
| `{type: "result", id, …}` | It answers the server's request `id` |

**Opened and closed** count per note: two editors on the same note in one app (React's strict mode
mounts one twice) send two opens, and the note is closed only at the last close. A close for a note
the app doesn't have open is never sent.

`revision` on a close is the server's revision of what the editor last showed, or `null` when it
doesn't know (unsaved changes it couldn't send, offline, a conflict). When the server has that
revision and no other editor anywhere has the note open, the note's writing session ends now, and
its *done* trigger fires, rather than 2 minutes after the last change. A dropped connection closes
nothing, so a reconnect doesn't fire *done*.

### From the server

| Message | The app |
| --- | --- |
| `{type: "read", id, noteId}` | Answers with the note as its editor shows it (see [Reading](#reading)) |
| `{type: "apply", id, noteId, runId, before, after, op}` | Makes the change in its editor (see [Applying a change](#applying-a-change)) |
| `{type: "run", runId, noteId, trigger, status, failure?, outcome?}` | Updates what it shows (see [What the user sees](#what-the-user-sees)) |

Answer every request within 10 seconds, or the editor counts as gone: a read falls back to the
server's copy (except for an editor trigger's own note, whose run is then skipped), and a write fails
the run as `editor_disconnected`. An app that gets a request for a note it doesn't have open answers
`{type: "result", id, error: "not_open"}` at once rather than letting it time out.

`run` updates go to every socket of the account, in order but best-effort: a socket that's slow or
reconnecting misses some. `status` is `running`, then `done`, `failed` or `skipped`; `queued` again
when a run will be retried. `failure` and `outcome` are as in the activity log.

## Reading

Answer `read` with what the editor would save, and where the cursor is:

```json
{"type": "result", "id": "s1", "spaceId": "work", "title": "Plan", "content": "Milk\n\nEggs\n", "cursor": 5, "revision": 12}
```

- `title` and `content` are the note as the editor would save it now, unsaved typing included, with
  the title apart from the Markdown content.
- `cursor` is the caret's position in `content`, in **Unicode code points** (not UTF-16 units, not
  bytes), or `null` when there's no caret in the text: a selection, or the caret in the title. The
  web app finds it by inserting a private-use character at the caret, writing the Markdown, and
  locating it.
- `revision` is the server's revision of exactly this text, or `null` when the editor has changes
  the server doesn't have yet.
- A **locked** note is never read: answer `{error: "locked"}`.

## Applying a change

`apply` carries the change three ways: the note the run read (`before`), the note it wants
(`after`), and the change as the agent asked for it (`op`), so the app can make it even when the user
typed since the run read the note.

1. If the note is locked, answer `{error: "locked"}`.
2. Wait for any input composition (an IME) to finish.
3. Take what the editor shows now (`now`).
4. If `now` is `before`, the target is `after`. Otherwise make `op` to `now`, by the rules below; if
   it no longer fits, answer `{error: "changed"}`, and the run gets the note as it is now and tries
   again.
5. Change the editor to the target as **one undoable step of its own**, replacing only what differs,
   so the caret stays where the user had it.
6. Answer `{before: now, revision, shows}`: what it showed just before, the server's revision of
   exactly that (or `null`), and the note as it would save it now. The editor's Markdown may write
   some of `after` differently; `shows` is what it actually holds.
7. Save it as any edit. Nothing marks the save as the agent's: the server works that out.

### The rules for `op`

They're the server's (`agent.EditorChange.MadeTo` in Go, `madeTo` in
`apps/web/src/editor/agent-edit.ts`), and every implementation must give the same text for the same
input.

| `op` | Made to `now` |
| --- | --- |
| `{kind: "title", title}` | The title becomes `title`; the content is unchanged |
| `{kind: "replace", find, replace}` | `find` must appear **exactly once** in the content (overlapping occurrences count: `aa` is in `aaa` twice); that one is replaced with `replace`, taken literally. None, or more than one: it doesn't fit |
| `{kind: "insert", at: "start", text}` | `text` as a block of its own at the start: if the content is empty or only newlines, it becomes `text` + `\n`; otherwise `text` + `\n\n` + the content without its leading newlines |
| `{kind: "insert", at: "end", text}` | Likewise at the end: the content without its trailing newlines + `\n\n` + `text` + `\n` (or `text` + `\n` for an empty one) |
| `{kind: "insert", at: "cursor", text}` | `text` exactly at the editor's caret **now**, in code points, clamped to the content. No caret: it doesn't fit |
| `{kind: "content", text}` | Never fits: replacing the whole content would wipe out what the user typed. (While the editor still shows `before`, `after` already is that content.) |

The server has already trimmed newlines from both ends of a start or end insert's text.

### How the server links the save

The run records the change as before (`before` in the answer) and after (`shows`), and waits for the
save that holds exactly `shows`; that save completes the record, so Activity can show and undo it.

- The save counts as **the agent's alone**, and fires nothing, if the server held exactly what the
  editor showed before the run's first unsaved change (kept as a hash, not text).
- Otherwise the user's unsaved typing went out with it, and the save counts as theirs too.
- A save that also holds typing **after** the change isn't linked, so undo from Activity can never
  take that typing; the run's edit then shows there as `not_saved`. Undo in the editor still works.

So an app should save the change promptly, as its normal saving would.

## Editor triggers

Three triggers start in an editor. The app decides when they happen; the server decides whether the
space's agent acts on them, and runs them.

| Trigger | When |
| --- | --- |
| `noteCreated` | A **new** note, not yet saved, opens in an editor. Once per note however often its editor is shown again; never for a note that already existed |
| `editStarted` | The user's first change to an existing note in an editor, and again on the first change after 3 minutes without one. Changes the agent made don't count. Not for a note that opened new |
| `menu` | The user picks the space's command in the note's menu. Offer it when the space's agent is on and has a `menu` prompt, labelled with its `menuLabel`, and not for a locked note |

Before asking, check what the app knows of the setup (`GET /agent/spaces`): the space's agent is on
and has a prompt for the trigger. Otherwise don't ask.

**Asking:** send `run.request` with `note` set to exactly what a `read` would answer, so the run
starts from it without asking the editor. Leave `note` out for a locked note (or don't ask at all).

- **Not yet connected,** but the socket is wanted (still connecting, or reconnecting): hold the
  request and send it after `hello`, with what the editor shows then, if the note is still open and
  it was asked for less than 10 seconds ago. Otherwise drop it; the moment it was for has passed.
- **No socket wanted:** drop it.
- Hold at most one request per note and trigger.

**On the server,** the run starts at once, beside the account's other runs, one at a time per note.
Asked again before it starts, it's the same run, starting from the newer `note`. It works on the note
**only as an editor shows it**, never the server's copy, which may not exist yet:

- it starts from the request's `note` if that's under 5 seconds old, else it reads the editor;
- with no editor showing the note, before or during the run, it's skipped as `note_closed`;
- locked there, it's skipped as `note_locked`.

A request the space's agent doesn't act on, or for a note that's gone or locked, is dropped without
an answer.

## What the user sees

The `run` updates drive these. The web app's are described here; another app may show them its own
way, but should show the same things.

- **The agent at work:** from a run's `running` on the open note until its change lands in this
  editor (or the run ends), a marker sits where the agent is likely to write: the caret, or the top
  of the body when the caret is in the title. The user can keep typing. Screen readers hear "Agent
  is writing…".
- **The change landing:** the changed text is highlighted, fading over 3 seconds, with an Undo
  button at the end of its last line until the user types or 6 seconds pass.
- **A failed run:** opening a note, the app asks for its latest run (`GET /agent/runs?note=…&limit=1`).
  If it failed, a notice on the note says why, with a way to open it in Activity and to dismiss it.
  `run` updates keep it current while the note is open.

## Edge cases

- **Locked notes** never reach the model. The editor refuses `read` and `apply` for one, and the
  server also checks its copy before every model call.
- **A note the server doesn't have yet** (a new note, or one kept on the device and not yet synced):
  editor triggers work on it, because they only use the editor. Background triggers wait for it to
  reach the server.
- **Offline:** no socket. Editor triggers are dropped (or held while reconnecting, up to 10 seconds);
  background triggers fire when the writes reach the server. A device's first sync sends
  `X-Notesd-Sync: initial`, so it fires none.
- **The same note in two editors** (two tabs, or a phone and a computer): a run talks to the editor
  that asked for it first, otherwise the one that opened the note last. The note's writing session
  ends at once only when the last of them closes it.
- **The editor goes away mid-run:** a background run reads the server's copy instead; a write in
  progress fails the run as `editor_disconnected`; an editor trigger's run is skipped as
  `note_closed`.
- **Two runs on one note** never go at once: the second waits.

## Other endpoints

| Endpoint | For |
| --- | --- |
| `GET /settings/ai` | Whether AI is set up (`configured`) |
| `GET /agent/spaces`, `GET`/`PUT`/`DELETE /agent/spaces/{id}` | Each space's agent: on or off, its prompts, the menu command |
| `GET`/`PUT /settings/account` | The account's time zone |
| `GET /agent/runs?space=&note=&limit=&cursor=`, `GET /agent/runs/{id}` | Activity: runs, their changes and diffs |
| `POST /agent/runs/{id}/undo` | Undo a run |
| `GET /agent/usage` | Today's tokens against the daily cap |

## Numbers

| What | Value |
| --- | --- |
| Socket protocol | 1 |
| Server ping | every 25 s |
| Answer to a request | within 10 s |
| Sockets per account | 50 |
| Message size | 4 MiB |
| Reconnect | 1 s, doubling to 30 s, at 50–100% |
| Closing, waiting for the last `editor.closed` | at most 5 s |
| A run request held while connecting | at most 10 s |
| A run request's `note`, used by the run | if under 5 s old |
| *Started editing* again after | 3 minutes without a change |
| A writing session ends | 2 minutes after the last change |
| One run | 20 tool calls, 150,000 tokens, 5 minutes |

## In the code

- Server: `internal/agent` (triggers, runs, tools, the `Editors` interface, `EditorChange.MadeTo`),
  `internal/editors` (the hub: connections, which editor a run talks to, requests and answers),
  `internal/api/agent_socket.go` (the socket).
- Web: `src/agent/socket.ts` (the socket's life and messages), `src/agent/triggers.ts` (asking for
  editor triggers), `src/editor/agent-edit.ts` (reading, `madeTo`, applying), `src/notes/note-pane.tsx`
  (when triggers fire, and the editor's answers), `src/editor/agent-presence.ts` (the agent at work).
