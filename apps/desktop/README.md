# notesd for macOS

A Mac app that is a window on your notesd server. The web app runs in it as it
does in Safari, offline included: its service worker keeps the app, and the
notes are always kept in the window's storage (OPFS), with no "Keep my notes
on this device" to turn off; signing out removes them. This side adds what a Mac app has and a browser tab doesn't:
its own window and Dock icon, menus, links that open in your browser, and
downloads to the Downloads folder.

The window has no title bar: its buttons sit in the web app's top bars, as in
most Mac apps. The page is marked `data-desktop="macos"` before it loads; the
web app then lays its bars out around the buttons, marks where the window
drags from, offers no passkeys (this WebKit refuses them), and names its
session "notesd for macOS". Full screen, which hides the buttons, adds
`data-fullscreen`.

Closing the window leaves the app running, as Mac apps do: the window only
hides (out of full screen first), and the Dock icon brings it back with the
page as it was. Quit (⌘Q) quits.

Touch ID stands in for passkeys: it unlocks locked notes, as Face ID does on
the iPhone, with the same Keychain code (`shared/keychain-biometrics`). It
needs a signed app (the Keychain item lives in the data protection keychain,
which wants an application identifier), so unsigned builds don't offer it.

Built with [Tauri 2](https://tauri.app) on the system's WKWebView. macOS only
for now.

## How it's put together

- `setup/`: the bundled page the window opens on. It asks for the server
  the first time (checked to be a notesd: `GET /api/v1/auth/status` answers
  with `X-Notesd-Server`), then goes on to it. When the server can't be reached
  and nothing was kept, the load fails and this page stays, with Try Again
  and Change Server.
- `src-tauri/src/main.rs`: the window, menus, which addresses stay in the
  window (the server's and the setup page's), downloads. Every load of the
  server is one the app asks for (`navigate`): a load the page starts itself
  is first offered to whatever app claims the address, and a Safari web app
  made with Add to Dock claims its site, so the server would open there.
- `src-tauri/src/close.rs`: closing hides the window, the Dock icon shows it.
- `src-tauri/src/touch_id.rs` and `src-tauri/swift/`: Touch ID. The Swift
  package wraps the shared Keychain code in C functions; swift-rs builds and
  links it from `build.rs`. One Keychain item per server and account. The web
  app (`apps/web/src/locks/touch-id.ts`) decides when to use it.
- `src-tauri/src/server.rs`: turns what was typed into an origin (https://
  unless it's this Mac) and checks it.
- `src-tauri/capabilities/`: which pages may call the app's commands. Only
  the setup page. The server's pages may only move and zoom the window and
  use Touch ID, a capability added while the app runs, as their address is
  the user's.

The chosen server is kept in `~/Library/Application Support/app.notesd.desktop/settings.json`.
Notes kept for offline use live in the window's WebKit storage for that
server's origin, as a browser keeps them.

## Everyday tasks

```bash
mise run dev:desktop     # the app, starting at its setup page
mise run test:desktop    # Rust tests
mise run lint:desktop    # rustfmt, clippy, swift-format
mise run build:desktop   # unsigned .app in src-tauri/target/release/bundle/macos
mise run build:desktop:signed  # the same, signed, with Touch ID
mise run release:macos   # signed, notarized DMG in server/bin (docs/contributing/releasing.md)
```

The signed build needs the team's Developer ID Application certificate in the
login keychain. `src-tauri/tauri.signed.conf.json` adds the signing identity,
hardened runtime, the entitlements (`src-tauri/signing/notesd.entitlements`)
and the Developer ID provisioning profile they need
(`src-tauri/signing/notesd.provisionprofile`, for the App ID
`app.notesd.desktop`). The profile is what lets the app hold the
application-identifier entitlement the data protection keychain needs: macOS
won't launch an app claiming that entitlement without one. So the plain build,
bundle identifier and all, claims no entitlements, and reports Touch ID
unavailable.

The icon comes from `scripts/icons.py … macos`.
