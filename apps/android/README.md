# notesd for Android

The native Android client: offline-first, with its own SQLite database and full-text index, a native rich-text editor, and background sync with notesd. Design and protocol are in [`docs/architecture.md`](../../docs/architecture.md).

This README is for working on the app. To install and use it, see [`docs/android.md`](../../docs/android.md); release builds and signing are in [`docs/releasing.md`](../../docs/releasing.md).

## Setup

- [mise](https://mise.jdx.dev) installs the pinned JDK (21) and Go from the repo's `mise.toml`.
- The Android SDK with platform 37 and build tools. Point Gradle at it with `ANDROID_HOME` or `apps/android/local.properties` (`sdk.dir=…`).
- No emulator is needed for the tests; the app runs on any device or emulator with Android 9 (API 28) or later.

## Everyday tasks

Run from the repository root:

| Task | What it does |
| --- | --- |
| `mise run build:android` | Debug APK in `app/build/outputs/apk/debug` |
| `mise run release:android` | Release APK in `server/bin`, signed when `NOTESD_KEYSTORE` is set |
| `mise run test:android` | All JVM and Robolectric tests, including sync against a real server, and core's shared tests on JS |
| `mise run lint:android` | ktlint and Android lint (warnings fail the build) |
| `mise run screenshots:android` | Renders every screen to `app/build/screenshots` (light, dark, 200% text, two-pane, foldable) |

Install on a connected device with `cd apps/android && mise exec -- ./gradlew :app:installDebug`.

## Running against a dev server

The app talks to notesd over HTTPS, or plain HTTP to an address only reachable on your network (`10.x`, `172.16–31.x`, `192.168.x`, loopback, link-local, `localhost`, `*.local`); anything else must use HTTPS, so tokens never cross the internet in the clear.

- **Phone on your Wi-Fi:** `NOTESD_LISTEN=0.0.0.0:8080 mise run dev:server`, then connect to `http://<your computer's LAN address>:8080`.
- **Emulator:** `mise run dev:server` and connect to `http://10.0.2.2:8080`, the emulator's alias for the host machine.

On first run, onboarding asks for the server, then either creates the account (a new server) or signs in with a username and password. "Use without a server" skips it; you can connect later in Settings.

## How the tests run

- `:core` tests run on the JVM: the Markdown codec against `shared/markdown-fixtures`, the store, the API client against a mock server, and sync. The ones in `commonTest` (store, search, migrations, plain text, tags) also run on JS under Node, on sqlite-wasm in memory: `./gradlew :core:allTests` runs both.
- Sync tests use the real server. Gradle builds `server/` with Go once (`buildNotesd`); each test starts it on a free port with a temporary data directory, reads the address from its log, creates the owner account, and drives two or more clients through every scenario in the architecture doc's §12.1. `RandomSyncTest` replays random edit, delete, offline and retry sequences; run more seeds with `./gradlew :core:jvmTest -Pnotes.seeds=1,2,3`.
- `:app` tests run on Robolectric: the editor's spans and behaviours, the screens (onboarding, list, editor, sync changes arriving in an open note), and an accessibility audit that checks every control on every screen has a label and a 48dp target.

## Layout

```text
apps/android/
├── core/   Kotlin Multiplatform, no Android
│   ├── commonMain/  database and search, sync engine, API types, plain text and tags,
│   │                migrations (SQL, compiled into the code by generateMigrations)
│   ├── jvmMain/     what the app uses besides: document model, Markdown codec, API client,
│   │                locked-notes crypto, images, the bundled SQLite driver
│   └── jsMain/      the web app's side: SQLite through sqlite-wasm, called directly, the API
│                    over fetch, and the engine exported to TypeScript (web/Engine.kt)
└── app/    the Android app
    ├── editor/    the EditText-based editor: spans, normaliser, formatting bar
    ├── notes/     list, editor screen, navigation (one pane, or two from 600dp)
    ├── account/   onboarding, token storage sealed with an Android Keystore key
    ├── settings/  settings and appearance
    ├── sync/      foreground scheduling and WorkManager background sync
    └── ui/        design tokens, type, icons and shared components
```

## Tags

Tags are text in the note (`#tag`, `#multi word#`, `#parent/child`; architecture §7.5). `core/text/Tags.kt` is the shared extractor, pinned by `shared/markdown-fixtures/tags.json`. The database keeps a `note_tags` table beside the search index, updated in the same transaction by every local save and every sync write, so tags need nothing from the sync protocol.

- **Tags:** All notes, Untagged and the tree with counts, on their own screen under the list ("‹ Tags"), or in a sidebar from 1000dp wide. Settings open from there. Choosing one filters the list and search, and the app reopens on the list last shown; a new note starts with the selected tag and is still discarded if nothing else is typed.
- **Editor:** the `Normalizer` tints tags with a `TagSpan` that never reaches the document. With the cursor in a tag, a strip above the formatting bar suggests existing tags while you type, or offers the notes with that tag.

## Email

A note can be emailed through notesd, which sends it with Resend (`server/README.md`). The Resend key and sender are set under Settings → Email while signed in; they live on the server, so web and phone share them.

- **Share sheet:** with email set up, sharing a note adds an "Email" action: a custom chooser action on Android 14+, our own target before that. It comes back to `MainActivity` (`ACTION_EMAIL_NOTE`), which opens the note and asks for addresses.
- **Sending:** `core/sync/NoteMailer.kt` syncs first and passes the revision the note is at here; the server sends its own copy, so the email is what's on screen. Offline, or with changes the server doesn't have yet, nothing is sent.

## Design

The app follows the web app's design system, not Material defaults:

- **Colours:** `ui/Tokens.kt` holds the web's `tokens.css` values converted from oklch to sRGB, with the oklch source beside each value.
- **Typefaces:** Instrument Sans, Instrument Serif and JetBrains Mono, bundled so the app looks the same offline (SIL Open Font License; see `licenses/`).
- **Glass surfaces:** blurred on Android 12+ with [Haze](https://github.com/chrisbanes/haze), translucent on earlier versions.
- **Motion:** stops when the system's "Remove animations" is on.

To check a design change, run `mise run screenshots:android` and look at the PNGs.
