---
title: Space agents on Android and iOS
description: "What the web app does for space agents, what the phone apps must replicate, and the plan to build it."
---

# Space agents on Android and iOS

The space agent (PRs #94–#100, #124, #136, GitHub stack #101) is complete on the server and the web
app. The Mac app wraps the web app and has all of it. Android and iOS have none of it, beyond
sending `X-Notesd-Time-Zone`. This is the inventory of what to replicate and the plan to build it.
The protocol is in [Agent clients](agent-clients.md); this doesn't repeat it.

## Findings that change the plan

1. **No client sends `X-Notesd-Sync`.** The server reads it on note create, update and delete
   (`server/internal/api/notes_handlers.go:153`), and the sync engines push through those, so a
   device's first sync and imports can start writing sessions and fire triggers. Web (its engine
   included), the Kotlin engine and the iOS engine all miss it. `agent-clients.md` says apps must send
   it. Fix it for every client first.
2. **Both phone editors are native and custom**, not web views:
   - Android: `NoteEditText` (an `EditText` over one `Spannable`).
   - iOS: `NoteTextView` (a TextKit 2 `UITextView` over one `NSAttributedString`).

   Neither can make an outside change as one undoable step today. Their only outside-change path
   (`setDocument`) replaces the whole text, moves the caret by raw offset, records no undo step (a
   later undo silently reverts it), and ignores IME composition. Neither can put views between
   blocks. That's where the hard work is: reading, applying and overlays.
3. **The rules for `op` exist only in Go and TypeScript, with no shared fixtures.** Kotlin and Swift
   ports would be a third and fourth copy, so the fixtures come first.
4. **Neither app knows "the server's revision of exactly what the editor shows."** Saves go to the
   local database, and sync pushes them about 2–3 s later. The revision has to be derived (below),
   and `editor.closed` must wait for a push.
5. **The settings guidelines' "What goes where" table has no rows for AI or the time zone**, not even
   for the web.
6. **Web gaps worth fixing before copying:**
   - an empty rule is dropped without making the page dirty;
   - the menu command does nothing, silently, when there's no socket;
   - Activity isn't live;
   - the agent page body is blank while loading or offline;
   - the AI settings store doesn't retry when the device comes back online.

## What each app has to do

Status: ✓ done, – not needed, ☐ to do.

### Data and plumbing

| # | Feature | Web | Android | iOS |
| --- | --- | --- | --- | --- |
| D1 | `X-Notesd-Time-Zone` (IANA) on every request | ✓ | ✓ | ✓ |
| D2 | `X-Notesd-Sync` on writes nobody typed (architecture §7.12) | ✓ | ✓ | ☐ |
| D3 | API: `GET/PUT/DELETE /settings/ai`, `POST /settings/ai/test` | ✓ | ☐ | ☐ |
| D4 | API: `GET /agent/spaces`, `PUT /agent/spaces/{id}` | ✓ | ☐ | ☐ |
| D5 | API: `GET /agent/runs`, `GET /agent/runs/{id}`, `POST …/undo`, `GET /agent/usage` | ✓ | ☐ | ☐ |
| D6 | API: `GET/PUT /settings/account` (time zone) | ✓ | ☐ | ☐ |
| D7 | Per-account caches for AI settings and agent configs: forgotten at sign-in and sign-out, an answer for an old account ignored, refetched when back online | ✓ | ☐ | ☐ |
| D8 | `madeTo` (the `op` rules), identical to Go and TS | ✓ | ☐ | ☐ |

### Settings and pages

| # | Feature | Web | Android | iOS |
| --- | --- | --- | --- | --- |
| S1 | Integrations → AI row: "{model} at {host}"; Manage/Connect for admins, "On" / "An admin can connect it." for others; category value "AI" or a count | ✓ | ☐ | ☐ |
| S2 | AI dialog: address, key (saved hint; empty keeps it), model, daily tokens (default 1,000,000), in-app checks, field errors, **Test** without saving, **Disconnect…** confirmation | ✓ | ☐ | ☐ |
| S3 | Time zone under Server: IANA picker, saves on change, "Set from the first device that connected." | ✓ | ☐ | ☐ |
| S4 | Agent page: AI-not-set-up and paused states; on/off (switch on phones) naming the provider host | ✓ | ☐ | ☐ |
| S5 | Agent page: purpose; rules (add from 8 moments in 3 groups, change a rule's moment, remove, menu command name); ideas while a rule is empty | ✓ | ☐ | ☐ |
| S6 | Agent page: examples (Journal, Recipes, Tidy tags) shown in place, each part taken or left, Use all; never turns it on or saves | ✓ | ☐ | ☐ |
| S7 | Agent page: notes it always reads (5; title lookup in the space; "not here yet"; offers for notes a rule needs; no-room message) | ✓ | ☐ | ☐ |
| S8 | Agent page: Save keeps typing done meanwhile; field errors; "hasn't reached the server yet"; discard confirmation | ✓ | ☐ | ☐ |
| S9 | Ways in: the `agent` trait's row in Space info (architecture §7.8): Manage while on, Turn on once AI is set up (or to resume a paused agent), never for a shared space; Activity… from the agent page. The glyph, summary, problem and move check already show on all three apps, from main's traits | ✓ | ☐ | ☐ |
| S10 | Activity: space filter, today's usage, runs (paginated), status and failure text, overlay counts | ✓ | ☐ | ☐ |
| S11 | Activity → run: summary, model's message, per-note line diffs, Undo with result, "Can't undo: …" | ✓ | ☐ | ☐ |

### In a note

| # | Feature | Web | Android | iOS |
| --- | --- | --- | --- | --- |
| N1 | Failed-run notice: latest run failed and not dismissed; Dismiss remembered; Activity opens the run | ✓ | ☐ | ☐ |
| N2 | The space's menu command in the note's menu (agent on, menu prompt, not locked) | ✓ | ☐ | ☐ |
| N3 | Socket: open while AI is set up and the shown space's agent is on, and the app is in the foreground; same socket across agent spaces; backoff only while online; closes 5 s after the last `editor.closed`; closes at once on sign-out | ✓ | ☐ | ☐ |
| N4 | `editor.opened` / `editor.closed` per note, counted; close after the last save and push, with its revision | ✓ | ☐ | ☐ |
| N5 | Answer `read`: title, Markdown content, cursor in code points, revision; `locked` | ✓ | ☐ | ☐ |
| N6 | Answer `apply`: wait for IME, `madeTo` when the text moved, change only what differs as one undoable step, keep the caret; `{before, revision, shows}` | ✓ | ☐ | ☐ |
| N7 | Editor triggers: `noteCreated` once per new note; `editStarted` on the first user change and after 3 min idle (agent changes don't count); held for up to 10 s while connecting | ✓ | ☐ | ☐ |
| N8 | Agent at work: "{Space} agent" marker at the caret or top of body; screen reader "Agent is writing…" | ✓ | ☐ | ☐ |
| N9 | Change landing: highlight fading over 3 s (static with reduced motion); Undo beside it until typing or 6 s | ✓ | ☐ | ☐ |
| N10 | Overlays: cards between blocks, placed by top, end, cursor or quote; follow typing; card Markdown with safe links; suggestion old → new | ✓ | ☐ | ☐ |
| N11 | Overlay answers: × / ✓ / ✗; accept checks the quote, makes the `op`, lands as the agent's change; stale when it can't; answers queued while disconnected | ✓ | ☐ | ☐ |
| N12 | Stale check for suggested replaces when typing pauses and when the list changes | ✓ | ☐ | ☐ |
| N13 | No overlays on a locked note; forgotten on close or drop | ✓ | ☐ | ☐ |

## How it maps onto each app

### Shared ground

- **Android** uses the Kotlin sync engine (`shared/sync-engine`, jvmMain `ApiClient` on OkHttp).
- **iOS** has its own Swift port in `NotesCore` (`ApiClient` on `URLSession`).

So data and plumbing are written twice: in Kotlin (where the web's engine could share types, but needn't) and in Swift.

| Need | Android | iOS |
| --- | --- | --- |
| API types and calls | `Api.kt` (commonMain) + `ApiClient.kt` (jvmMain) | `NotesCore/API/Api.swift` + `ApiClient.swift` |
| Admin-only pattern to copy | `EmailSettings.editable`, `EmailSetup.kt` | `EmailSettings.editable`, `Settings/EmailSetup.swift` |
| WebSocket | OkHttp `newWebSocket` (already a dependency; sends no `Origin`; answers pings) | `URLSessionWebSocketTask`: set `maximumMessageSize` to 4 MiB; ping answers are automatic |
| Bearer token for the upgrade | `ApiClient` keeps refresh private: add a way to open an authed socket | `authed()` is private: add `withFreshAccess` or a socket factory |
| Foreground and background | `ProcessLifecycleOwner` (as `SyncScheduler`) | `scenePhase` (`NotesApp.swift`) |
| Online state | only `onAvailable` today: add `onLost` and publish it | `NWPathMonitor` is private to `SyncScheduler`: share it |
| Sign-out, including silent revocation | follow `accounts.account` (as `email.forget()`) | follow `accounts.account.stream()` (as `SyncScheduler`) |
| First-sync signal for D2 | sync engine's first cycle (no cursor yet) | `SyncState.cursor == 0` / `fullSync` |
| Settings rows | `Rows.kt`; on/off is a `Switch` row (`Security.kt`) | `Form` rows; on/off is a `Toggle` (`LockedNotesSection.swift`) |
| Sheets and pages | `Sheet`, `NotesDialog`, Navigation 3 keys | `.sheet` + `NavigationStack`, `Route` cases |
| Note menu | `EditorScreen.kt` ⋯ `Sheet` | `NoteScreen.swift` ⋯ `Menu` |
| Banner for the failed run | `Banner(..., action)` in `EditorScreen.kt` | `problemBanner` in `NoteScreen.swift` |

### The editor work (the same shape on both)

1. **An open-editor registry.**
   - noteId → the live editor (view and model). Opened and closed are counted per note in the view
     model (Android `EditorViewModel`, iOS `EditorModel`), so rotation, screen pushes and rebuilds
     don't close the note.
   - The view can be missing while the note stays open (a locked note shows its placeholder; a
     rotation is in progress). Then `read` and `apply` answer `locked` when locked, and otherwise
     wait briefly for the view, then answer `not_open`.
   - Every way out sends `editor.closed`:
     - Android: Back; the two-pane swap; switching space; the launcher's "new note"; delete; move.
     - iOS: leaving the note; the narrow layout's delayed clear; Settings replacing the path; a
       conflict push.
2. **Reading.** `(doc.title, Markdown.serialize(doc))`, as saving already does. The cursor uses the
   web's method:
   - copy the text with its content spans/attributes;
   - put U+E000 at the caret;
   - serialize and find the first difference;
   - count code points.

   The cursor is `null` for a selection, the title, or next to a raw block.
3. **Revision of what's shown.** It's the note's `serverRevision` when all of these hold, otherwise
   `null`:
   - no unsaved edits and no save in flight;
   - the stored note isn't dirty;
   - the stored title and content equal the editor's, compared canonically (`Merge.sameMarkdown` /
     its Swift twin), so a note written in another client's Markdown style still counts.
4. **Applying as one step.**
   - Build the target text from `after`, or from `madeTo(op, now)`.
   - Diff it against the editor's text: common prefix and suffix over characters and block
     structure (iOS compares `BlockAttr` by value, not identity).
   - Replace only the middle inside the existing command path, so undo gets one step and the save
     happens: Android `command { }` (as `replaceAll`); iOS `commandSelecting` (as `replaceFound`).
   - Break typing coalescing before and after the step.
   - Map the caret through the change.
   - The same diff-replace should become the sync path's `setDocument(keepSelection)`, fixing its
     caret drift and stale undo too.
5. **User vs agent changes.** The single `onChange` gets a flag: agent changes save but don't count
   as the user's, for `editStarted` and the revision bookkeeping.
6. **IME.** Before applying, wait up to 3 s for composition to end:
   - Android: the composing span is gone (hook `finishComposingText` / `commitText` in the
     `InputConnection` wrapper);
   - iOS: `markedTextRange == nil` (override `unmarkText`).

   No outside change ever lands mid-composition, and that includes sync.
7. **Saving the agent's change promptly.** After an apply, save at once and ask for a sync, rather
   than waiting for the 700 ms autosave and then the 2 s sync debounce, so the server links the save
   to the run instead of marking it `not_saved`.
8. **Cards between blocks.**
   - Make room under the anchor block without touching the text:
     - Android: a `LineHeightSpan` that isn't an `EditorSpan`, so the codec and undo ignore it;
     - iOS: extra height reported by `BlockLayoutFragment`, or paragraph spacing that `EditorStyle`
       knows about.
   - Place a native card view in that gap: Android in a `FrameLayout` beside the `EditText` in the
     scroller; iOS as a hosted SwiftUI subview of the text view. Re-place cards on every layout pass.
   - Placement follows the shared quote rules. The anchor follows typing (a span on Android; on iOS,
     re-found after the normalizer replaces `BlockAttr`s).
   - Card buttons never take focus or move the caret. Each card is an accessibility landmark with
     the web's labels.
   - Never an attachment character: it would be saved as Markdown.
9. **The agent at work and the change landing.** Drawn, never stored:
   - Android: `onDraw`, as the tag pills are;
   - iOS: TextKit 2 rendering attributes, or a positioned view.

   Neither may get into undo snapshots or the codec.

## Plan

Each phase ends with something a user can use, and each phase has a PR per app.

- **Order:** Phase 0 goes first. After it, each phase is built on Android and iOS side by side.
- **Each phase ends** when lint, typecheck and tests pass on both apps and the docs say what it does.

### Phase 0: shared ground (server, web, fixtures, docs)

- **D2 everywhere.** Send `X-Notesd-Sync`:
  - web engine and `FetchApi`;
  - Kotlin engine;
  - iOS engine.
  - Each with a test that a first sync's pushes carry `initial`.
- **`shared/agent-fixtures/`**, JSON cases consumed by tests in Go, TS, Kotlin and Swift:
  - `made-to.json`: each `op` kind, overlapping `find`, newline trimming, code-point cursors,
    clamping, `content` never fitting;
  - `cursor.json`: a Markdown document with a marked caret → expected code point, including emoji,
    escapes, lists and a caret in the title;
  - `quote.json`: content + quote → which body block, or the top (as written, longest line without
    syntax, ambiguous).

  Go and TS read them now, so drift shows up before the phone apps exist.
- **Fix the web gaps** (findings, item 6) so the phones copy the fixed behaviour:
  - an empty rule makes the page dirty and can't be saved silently;
  - the menu command says why it can't run;
  - loading and offline states on the agent page;
  - Activity refreshes when `run` updates arrive;
  - the AI store retries when back online.
- **Docs:**
  - add AI (Integrations, server) and time zone (Server, account) to the settings table, and the
    agent row under Spaces;
  - in `agent-clients.md`, the client status per app.

### Phase 1: settings and pages (REST only; agent level 0)

No editor work, so it can't break writing. It gives phone users everything except what happens in
the editor.

| App | Work |
| --- | --- |
| Both | D3–D7 (types, calls, caches), with tests against a real notesd (both already test that way) |
| Both | S1–S3: AI under Integrations (admin controls, Test, Disconnect), time zone under Server |
| Both | S4–S8: the agent page as a pushed page. Rules as rows. A rule's moment is a picker. Ideas and examples are chip rows, and an example's parts are dashed rows where they'd go. Notes it always reads uses local search |
| Both | S9: the agent trait's Settings and Turn on in each app's trait registry, opening the agent page from Space info; refresh the space list after a save so the trait shows; explain `409 space_has_agent` where sharing starts |
| Both | S10–S11: Activity list and run, line diffs, Undo |
| Both | N1: the failed-run notice, fetched when a note opens (live in phase 3) |

### Phase 2: editor foundations (no socket yet)

The riskiest work, kept apart from networking and tested on its own.

| App | Work |
| --- | --- |
| Both | D8 `madeTo`, passing `made-to.json`. Kotlin goes in the sync engine (commonMain). Swift goes in NotesCore, comparing by scalars |
| Both | The cursor in code points, passing `cursor.json` through the real codec |
| Both | Diff-replace apply as one undo step, caret kept; the sync path moves onto it. Tests: undo takes back only the change; typing before and after survives; the caret holds; raw blocks, list markers and the end marker hold |
| Both | User-vs-agent change flag; waiting for IME, with composition tests |
| Both | Revision of what's shown; an immediate save and sync after an agent change |
| Both | The open-editor registry and every way out of a note (one test per path) |

### Phase 3: the socket, reading and writing (agent level 1, and live updates)

| App | Work |
| --- | --- |
| Both | N3: the socket client. A seam for tests (Android: OkHttp or mockwebserver; iOS: a `WebSocketTransport` protocol), plus an end-to-end test against the real hub in the engine test suites |
| Both | N4–N6: opened, closed with revision; `read`, `apply`, `not_open`, `locked`, the 10 s answers |
| Both | `run` updates feed the failed-run notice live, and Activity refreshes |

**User check:** with the agent on, a done-writing run lands in the open note as one undoable step
while you type.

### Phase 4: editor triggers and the agent at work (agent level 2)

| App | Work |
| --- | --- |
| Both | N7: `noteCreated`, `editStarted` and held requests |
| Both | N2: the menu command in the note's menu |
| Both | N8–N9: the marker, the landing highlight with Undo, accessibility announcements, reduced motion |

### Phase 5: cards beside the note (agent level 3)

| App | Work |
| --- | --- |
| Both | N10: room under blocks, card views, placement passing `quote.json`, following typing |
| Both | N11–N13: answers, accept as the agent's change, stale check, queued answers, locked |

**User check:** the Journal's follow-ups show above a new entry, and a "Fix typos" suggestion is
accepted, then undone.

### Phase 6: parity pass

- Accessibility audit (TalkBack and VoiceOver order of cards inside the text, labels).
- Tablet and foldable layouts: Android's two panes; iPad's split view.
- Update `docs/guides/apps/*` and the agents guide's "web only" notes.
- Screenshots.

## Risks

| Risk | Mitigation |
| --- | --- |
| Diff-replace leaves spans or block attributes inconsistent after normalizing | Do it in phase 2 alone, with property-style tests over the Markdown fixtures (apply, then serialize, equals the target) |
| Snapshot undo also takes back typing after the agent's step | Break coalescing on both sides; a test types right after an apply and undoes |
| IME corruption | Never change the text while composing; tests with a composing span / marked text |
| `revision` often `null` on phones (saves are local first) | An immediate save and sync after an agent change; Activity shows `not_saved` honestly when it loses the race |
| Cards drift out of place in a scrolling `EditText` / `UITextView` | Re-place them on every layout pass; snapshot tests (Roborazzi; iOS snapshot of the text view) |
| A fourth copy of the rules drifts | `shared/agent-fixtures` run by every implementation |
| The socket and background limits | Close in the background (the server falls back to its copy); reopen on foreground |

## To decide

1. **Phase order:** settings first (phase 1, safe and useful at once) or the editor first
   (phases 2–3, the agent writing into an open note)? Recommended: settings first.
2. **Both apps at once or one after the other?** Recommended: Android first in each phase. It shares
   Kotlin with the engine, and its editor gaps are the better known. iOS follows within the same
   phase.
3. **The web gaps** (findings, item 6): fix them in phase 0 as proposed, or keep the web as it is?
4. **Admin AI setup on phones:** the guidelines put server settings on every app (as Resend). Keep
   that, or leave AI setup on the web?
5. **Where the PRs go:** stacked on #136 (GitHub stack #101), or a new stack once #94–#136 merge?
