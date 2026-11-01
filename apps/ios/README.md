# notesd for iOS

The native iPhone client: offline-first, with its own SQLite database and
full-text index, a native rich-text editor, and sync with notesd. Design and
protocol are in [`docs/contributing/architecture.md`](../../docs/contributing/architecture.md); the
Android app (`apps/android`) is the reference this one ports from, file for
file where it can.

This README is for working on the app. To install and use it, see
[`docs/guides/apps/iphone.md`](../../docs/guides/apps/iphone.md).

## Setup

- Xcode 26 or later (`xcode-select -s /Applications/Xcode.app`), with an iPhone
  simulator (`xcodebuild -downloadPlatform iOS`).
- [mise](https://mise.jdx.dev) for Go, which the tests use to build a server.
- To run on a phone: `Config/Local.xcconfig` (copy the `.example`) with your
  team and a bundle ID prefix of your own. Simulator builds don't need it.

## Everyday tasks

Run from the repository root:

| Task | What it does |
| --- | --- |
| `mise run test:ios:core` | NotesCore tests on the Mac: shared fixtures, store, API client, and every sync scenario against a real notesd |
| `mise run test:ios` | The editor tests, then the UI flows against a real notesd, on a simulator |
| `mise run lint:ios` | swift-format (`.swift-format`), warnings fail |
| `mise run build:ios` | Debug build for a simulator |

`NOTESD_SEEDS=1,2,3` runs `RandomSyncTests` with other seeds.

## Layout

```text
apps/ios/
├── NotesCore/    Swift package, no UIKit: document model, Markdown codec, plain text and tags,
│                 database and search, API client, sync engine, images, locked-notes crypto,
│                 the mailer. Builds for macOS too.
├── Notes/        the app
│   ├── Editor/   the UITextView (TextKit 2) editor: attributes, codec, normaliser, formatting bar
│   ├── Notes/    list, tags, note screen and its model, the email sheet
│   ├── Images/   decoding and caching pictures for the editor
│   ├── Locks/    unlocking, the lock password, the privacy cover
│   ├── Account/  connecting to a server, the account in the Keychain
│   ├── Sync/     when to sync: foreground triggers, background refresh
│   ├── Settings/ including email (Resend) and locked notes
│   └── UI/       design tokens and type
├── NotesTests/   editor tests, hosted in the app
├── NotesUITests/ end-to-end flows and accessibility audits
├── Config/       xcconfig and Info.plist
└── scripts/      test helpers: a server for the UI tests, a simulator, a watchdog for xcodebuild
```

`Notes.xcodeproj` uses folder-synchronized groups: a file added to one of
those folders is in its target, with no project edit.

## How it differs from Android

- **Markdown parsing** runs the web app's own tokenizer, markdown-it 14.3.2
  (`NotesCore/Sources/NotesCore/Resources`), in JavaScriptCore, so a note
  parses exactly as it does on the web. Android matches it with
  commonmark-java plus workarounds. The serializer is a Swift port of
  Android's, itself a port of the web's. `markdown.js` is the tokenizer half
  of `apps/web/src/editor/markdown.ts`; keep the two in step.
- **SQLite** is the system library, which has FTS5 on iOS and macOS (a test
  checks). One schema file, `0001_init.sql`, starts where Android's
  migrations end.
- **The editor** keeps structure in attributes rather than spans: each
  block's characters and its "\n" carry one `BlockAttr`, and the text always
  ends with a "\n". Hard breaks are U+2028, which keeps TextKit's paragraph
  (one paragraph per block). Raw Markdown runs (code, tables, images) are
  attachments holding their source, so they're atomic and saved byte for
  byte. `BlockLayoutFragment` draws list markers, quote bars and tag pills.
- **First run**: no onboarding. The app opens on the list, working offline,
  and the empty list offers **Connect to a server** (App Review requires an
  app that works without a server). The other platforms ask for the server
  first.
- **Undo**: the platform undoes typing; formatting and other editor commands
  register a snapshot, so structure comes back with the text.

## Tests

- `NotesCore` uses Swift Testing on the Mac. Sync tests start a real notesd
  per suite (`TestServer`), built from `server/` or taken from
  `NOTESD_SERVER_BIN`, and drive clients whose network can drop requests or
  lose responses (`FaultyTransport`).
- `NotesTests` run in the app on a simulator: every shared fixture
  round-trips through the editor, plus the Enter, Backspace, formatting,
  link, tag and undo rules.
- `NotesUITests` launch the app with `--ui-testing` (a fresh database and
  Keychain item), sign in to the server `scripts/with-server.sh` starts, and
  run the accessibility audit on every screen. Screenshots stay in the test
  results.

## Images, locked notes, email

- **Pictures** (§7.6): `ImageStore` keeps added pictures in Application
  Support with a `pending_images` row until sync uploads them (before any
  note operation), and fetched ones in Caches. `PrepareImage` resizes and
  re-encodes with ImageIO, dropping metadata. In the editor a
  `notesd:image` run is still a `RawAttachment`, so it saves byte for byte;
  it draws the picture at its size in points (`ImageLoader`).
- **Locked notes** (§10.1): `LockedNotes` is CryptoKit plus CommonCrypto's
  PBKDF2, checked against `shared/crypto-fixtures`. The keyring syncs every
  cycle into `lock_keys`; `LockSession` holds the key in memory only and
  drops it when the app goes to the background, after five idle minutes or
  on Lock now, saving open notes first. Face ID unlock (`BiometricUnlock`,
  a port of Android's `FingerprintUnlock`) keeps the key in a Keychain item
  behind `.biometryCurrentSet`, on this device only, bound to the password
  entry it was made with (`KeychainBiometrics`). UI tests fake it with
  `--fake-biometrics=match|cancel`.
- **Email**: the server renders and sends; `NoteMailer` syncs first and
  passes the revision. The tests fake Resend with a small HTTP listener
  (`FakeResend`), and `scripts/with-server.sh` points the UI tests' server
  at a closed port so nothing reaches the real Resend.

## Not built yet

The recovery code (on no platform yet) and iPad layouts.
