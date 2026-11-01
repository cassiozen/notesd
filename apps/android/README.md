# notesd for Android

The native Android client: offline-first, with its own SQLite database and full-text index, a native rich-text editor, and background sync with notesd. Design and protocol are in [`docs/contributing/architecture.md`](../../docs/contributing/architecture.md).

This README is for working on the app. To install and use it, see [`docs/guides/apps/android.md`](../../docs/guides/apps/android.md); release builds and signing are in [`docs/contributing/releasing.md`](../../docs/contributing/releasing.md).

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
| `mise run test:android` | All JVM and Robolectric tests |
| `mise run lint:android` | ktlint and Android lint (warnings fail the build) |
| `mise run screenshots:android` | Renders every screen to `app/build/screenshots` (light, dark, 200% text, two-pane, foldable) |

Install on a connected device with `cd apps/android && mise exec -- ./gradlew :app:installDebug`.

## Running against a dev server

The app talks to notesd over HTTPS, or plain HTTP to an address only reachable on your network (`10.x`, `172.16–31.x`, `192.168.x`, loopback, link-local, `localhost`, `*.local`); anything else must use HTTPS, so tokens never cross the internet in the clear.

- **Phone on your Wi-Fi:** `NOTESD_LISTEN=0.0.0.0:8080 mise run dev:server`, then connect to `http://<your computer's LAN address>:8080`.
- **Emulator:** `mise run dev:server` and connect to `http://10.0.2.2:8080`, the emulator's alias for the host machine.

On first run, onboarding asks for the server, then either creates the account (a new server) or signs in with a username and password. "Use without a server" skips it; you can connect later in Settings.

## How the tests run

- The database, API client and sync live in the sync engine (`shared/sync-engine`), shared with the web app and tested there: `mise run test:sync-engine`.
- `:app` tests run on Robolectric: the editor's spans and behaviours, the screens (onboarding, list, editor, sync changes arriving in an open note), and an accessibility audit that checks every control on every screen has a label and a 48dp target.

## Layout

```text
apps/android/
└── app/    the Android app; the engine it runs on is shared/sync-engine
    ├── editor/    the EditText-based editor: spans, normaliser, formatting bar
    ├── notes/     list, editor screen, navigation (one pane, or two from 600dp)
    ├── account/   onboarding, token storage sealed with an Android Keystore key
    ├── settings/  settings and appearance
    ├── sync/      foreground scheduling and WorkManager background sync
    └── ui/        design tokens, type, icons and shared components
```

## Tags

Tags are text in the note (`#tag`, `#multi word#`, `#parent/child`; architecture §7.5). `shared/sync-engine`'s `text/Tags.kt` is the shared extractor, pinned by `shared/markdown-fixtures/tags.json`. The database keeps a `note_tags` table beside the search index, updated in the same transaction by every local save and every sync write, so tags need nothing from the sync protocol.

- **Tags:** All notes, Untagged and the tree with counts, on their own screen under the list ("‹ Tags"), or in a sidebar from 1000dp wide. Settings open from there. Choosing one filters the list and search, and the app reopens on the list last shown; a new note starts with the selected tag and is still discarded if nothing else is typed.
- **Editor:** the `Normalizer` tints tags with a `TagSpan` that never reaches the document. With the cursor in a tag, a strip above the formatting bar suggests existing tags while you type, or offers the notes with that tag.

## Email

A note can be emailed through notesd, which sends it with Resend (`server/README.md`). The Resend key and sender are set under Settings → Email while signed in; they live on the server, so web and phone share them.

- **Share sheet:** with email set up, sharing a note adds an "Email" action: a custom chooser action on Android 14+, our own target before that. It comes back to `MainActivity` (`ACTION_EMAIL_NOTE`), which opens the note and asks for addresses.
- **Sending:** `shared/sync-engine`'s `sync/NoteMailer.kt` syncs first and passes the revision the note is at here; the server sends its own copy, so the email is what's on screen. Offline, or with changes the server doesn't have yet, nothing is sent.

## Design

The app follows the web app's design system, not Material defaults:

- **Colours:** `ui/Tokens.kt` holds the web's `tokens.css` values converted from oklch to sRGB, with the oklch source beside each value.
- **Typefaces:** Instrument Sans, Instrument Serif and JetBrains Mono, bundled so the app looks the same offline (SIL Open Font License; see `licenses/`).
- **Glass surfaces:** blurred on Android 12+ with [Haze](https://github.com/chrisbanes/haze), translucent on earlier versions.
- **Motion:** stops when the system's "Remove animations" is on.

To check a design change, run `mise run screenshots:android` and look at the PNGs.
