# Settings

Settings is a list of categories in two groups. Each category opens its own page. Web, iOS and
Android use the same categories, names and order. A platform leaves out a category that has
nothing in it there, and leaves out rows it doesn't support.

When you add a setting, put it in one of these categories. Add a new category only when a feature
has several settings that don't fit any existing one. Update this file in the same change.

## Layout

The list works like the system's own settings:

1. **The account row** at the top: an initial, the username and the server. It opens Server. When
   not connected, it says "Not connected" and opens Server, where you can connect.
2. **The groups.** Each has a one-line header that says where its settings are kept. The header
   is the group's only label: there's no separate description.
   - **Synced to your account**: stored on the server. Every device signed in to the account sees
     the same values. On a native app that isn't connected yet, "Synced once you connect".
   - **This device only** ("This browser only" on the web, "This iPhone only" on iOS, "This phone
     only" on Android): stored only here.
3. **The category rows.** Each row has an icon, the title, and its current state as a short value
   on the right ("Off", "3", "System"), like the system's own settings. On a phone, each row also
   ends in a chevron.

A row inside a synced category that applies to this device only says so in its footnote ("Only on
this iPhone", "Only on this phone"). Biometric unlock (Face ID or Touch ID, as the iPhone has it;
fingerprint on Android) is one example.

## Categories

A value is the category's state in a word or two. It's never a description of the category.

iOS writes titles in title case, as its own settings do ("Sync & Data").

| # | Group | Category | Route key | Icon | Value in the list |
|---|-------|----------|-----------|------|-------------------|
| 1 | (account row) | Server | `server` | initial | username and server; "Signed out"; "Not connected" |
| 2 | Synced | Security | `security` | lock | "Off" (no lock password), "Locked", "Unlocked" |
| 3 | Synced | Spaces | `spaces` | four squares | the number of spaces |
| 4 | Synced | Integrations | `integrations` | envelope | "Email" when set up, else "Off" |
| 5 | This device | Appearance | `appearance` | half-filled circle | "System", "Light", "Dark" ("Automatic" on iOS, as the system says) |
| 6 | This device | Sync & data | `device` | cloud | "Up to date", "N waiting", "Offline"; Android, with no sync status yet, shows only "N waiting" |
| 7 | This device | About | `about` | info (i in a circle) | the version |

On a wide web window, the category list sits beside the selected category's page, and Server is
selected when Settings opens. A category has its own address (`/settings/security`). On a phone,
the list is its own screen and a category opens on top of it with a back button.

## What goes where

| Setting | Category | Synced? | Web | iOS | Android |
|---------|----------|---------|-----|-----|---------|
| Server address (the web uses the address it's served from) | Server | device | ✓ | ✓ | ✓ |
| Signed-in username | Server | account | ✓ | ✓ | ✓ |
| Server version, with a link to releases | Server | – | ✓ | – | – |
| Update to the newest release (admins; needs `NOTESD_SELF_UPDATE`) | Server | – | ✓ | – | – |
| Connect to a server / Sign in again | Server | – | – | ✓ | ✓ |
| Sign out | Server | – | ✓ | ✓ | ✓ |
| Change password (+ `notesd reset-password` hint) | Server | account | ✓ | – | – |
| Lock password: create hint, lock now, change, forgot/reset | Security | account | ✓ | ✓ | ✓ |
| Passkeys for locked notes | Security | account | ✓ | – | – |
| Unlock with Face ID / Touch ID / fingerprint | Security | **device** | – | ✓ | ✓ |
| Signed-in devices (list, sign one out) | Security | account | ✓ | – | – |
| Spaces: list, new, edit, delete | Spaces | account | ✓ | ✓ | ✓ |
| Email via Resend (API key, From) | Integrations | **server** | ✓ | ✓ | ✓ |
| Theme | Appearance | device | ✓ | ✓ | ✓ |
| Note font (System, Sans, Serif, Book, Legible, Mono; the note's title and text only) | Appearance | device | ✓ | – | – |
| Sync status, last synced, last error, Sync now | Sync & data | device | – | ✓ | – |
| Remove all data from this device | Sync & data | device | – | ✓ | ✓ |
| App version | About | device | – | ✓ | ✓ |

**server** means one value for every account on the server. Everyone signed in sees it and uses
it, but only an admin can change it: the server refuses anyone else (403 `admin_only`), and
`GET /api/v1/settings/email` says whether this account may (`editable`). The apps don't hide the
edit controls for other users yet; they show the server's refusal.

The rules for when a category or row is visible don't change from before the split:

- Integrations appears only when signed in.
- Security appears when signed in or when a lock password already exists.
- Sync & data's status rows appear only when there's an account.

Web has no rows in Sync & data or About, so it leaves those categories out. Because of the rules
above, a native app that isn't connected shows only Server, Spaces, Appearance, Sync & data and
About.
