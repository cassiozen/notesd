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
- `src-tauri/src/server.rs`: turns what was typed into an origin (https://
  unless it's this Mac) and checks it.
- `src-tauri/capabilities/`: which pages may call the app's commands. Only
  the setup page. The server's pages may only move and zoom the window, a
  capability added while the app runs, as their address is the user's.

The chosen server is kept in `~/Library/Application Support/app.notesd.desktop/settings.json`.
Notes kept for offline use live in the window's WebKit storage for that
server's origin, as a browser keeps them.

## Everyday tasks

```bash
mise run dev:desktop     # the app, starting at its setup page
mise run test:desktop    # Rust tests
mise run lint:desktop    # rustfmt and clippy
mise run build:desktop   # unsigned .app in src-tauri/target/release/bundle/macos
```

The icon comes from `scripts/icons.py … macos`.
