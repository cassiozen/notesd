# Sync engine

The client side of notesd's offline-first model, in Kotlin Multiplatform: the local database and search index, the API client, and sync. Design and protocol are in [`docs/architecture.md`](../../docs/architecture.md).

Two apps use it:

- **Android** (`apps/android`), on the JVM target, as `notes:sync-engine`. Its Gradle build includes this one (`includeBuild`), so the app always builds against these sources.
- **Web** (`apps/web`), on the JS target, as the pnpm workspace package `@notes/sync-engine`: an ES module with TypeScript definitions, run in a worker on sqlite-wasm.

iOS has its own Swift port (`apps/ios/NotesCore`), held to the same fixtures.

## Tasks

Run from the repository root, or with `pnpm <script>` here:

| Task | Script | What it does |
| --- | --- | --- |
| `mise run build:sync-engine` | `build` | The JS library in `build/dist/js/productionLibrary`, what `@notes/sync-engine` exports |
| `mise run dev:sync-engine` | `dev` | The same, rebuilt on change (part of `mise run dev`) |
| `mise run test:sync-engine` | `test` | Tests on the JVM and on JS under Node |
| `mise run lint:sync-engine` | `lint` | ktlint |

Needs the JDK, Go (the sync tests build the server) and Node, all from `mise.toml`; not the Android SDK.

## How the tests run

- On the JVM: the Markdown codec against `shared/markdown-fixtures`, the store, the API client against a mock server, and sync. The ones in `commonTest` (store, spaces, search, migrations, a sync cycle that fails, plain text, tags, SHA-256) also run on JS under Node, on sqlite-wasm in memory: `./gradlew allTests` runs both.
- Sync tests use the real server. Gradle builds `server/` with Go once (`buildNotesd`); each test starts it on a free port with a temporary data directory, reads the address from its log, creates the owner account, and drives two or more clients through every scenario in the architecture doc's §12.1. `RandomSyncTest` replays random edit, delete, offline and retry sequences; run more seeds with `./gradlew jvmTest -Pnotes.seeds=1,2,3`.

## Layout

```text
shared/sync-engine/src/
├── commonMain/  database and search, sync engine, API types, plain text and tags,
│                migrations (SQL, compiled into the code by generateMigrations)
├── jvmMain/     what Android uses besides: document model, Markdown codec, API client,
│                locked-notes crypto, images, the bundled SQLite driver
└── jsMain/      the web app's side: SQLite through sqlite-wasm, called directly, the API
                 over fetch, and the engine exported to TypeScript (web/Engine.kt)
```

Versions shared with `apps/android` (Kotlin, coroutines, serialization, sqlite, okhttp, ktlint) are repeated in both builds' `gradle/libs.versions.toml`; keep them in step.
