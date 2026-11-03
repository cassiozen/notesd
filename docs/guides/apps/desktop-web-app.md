---
title: Desktop web app
description: Install notesd on your computer as an app that keeps your notes and works offline.
---

# Desktop web app

The notesd web app can be installed on your computer as an app. Installed, it opens in its own window from
your dock or taskbar, keeps a full copy of your notes on the computer, and works offline, just like
the phone apps: writing, search and tags work with no connection, and it syncs with your server
whenever it can.

<img width="1000" height="592" alt="notesd installed as a desktop app" src="https://github.com/user-attachments/assets/cd45c640-262b-4ca5-88c6-bd4cf086622f" />

## Install it

Open your server's address in a browser and sign in. Installing needs notesd served over HTTPS (or
from `localhost`), as set up in [Get started](../../get-started/03-set-up-https.md). On a plain-HTTP LAN address like
`http://192.168.1.20:8080`, Chrome won't show its install offer, and no browser keeps notes on the
computer.

### Safari (Recommended on Mac)

On macOS 14 or later, choose File → **Add to Dock**.

<img width="494" height="452" alt="Install on Safari" src="https://github.com/user-attachments/assets/a1eb7c63-3296-4666-906c-39b9e4aad390" />

#### Safari additional recommendations

After the application is added to the dock, open it and click on notesd → Settings.
<img width="551" height="375" alt="Settings" src="https://github.com/user-attachments/assets/7d7c4136-901a-4b31-9e25-b2d2e44195ad" />

Then:

1. Uncheck "Show Navigation Controls"
  <img width="886" height="460" alt="Uncheck Show Navigation controls" src="https://github.com/user-attachments/assets/13d526c5-a68c-4259-990a-8ab385e5fe83" />

2. Disable extensions for this app (unless there are some specific extensions you need in notesd)

### Chrome

Click the install icon at the right of the address bar. Or, In notesd, go to Settings → Sync & data and click **Install notesd**.

<img width="471" height="163" alt="Install on Chrome" src="https://github.com/user-attachments/assets/fc8ba906-b7ec-4062-8aa9-d6ae1bd56def" />

Firefox can't install it; use notesd in a tab there.

## Using notesd

- [Writing and Markdown](../using/writing-and-markdown.md)
- [Tags, spaces and search](../using/organize-and-find.md)
- [Locked notes](../using/locked-notes.md)
- [Offline and sync](../using/offline-and-sync.md)
- [Settings](../using/settings.md)
