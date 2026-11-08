---
title: Sharing with others
description: Add family or friends to your server, and share spaces with them so you can write notes together.
---

# Sharing with others

One notesd server can hold a few people, such as a family's. Two things decide who sees what:

- **Accounts** decide who is on the server. Each account has its own notes, spaces, locked notes
  and pictures, and no other account can see them.
- **Shared spaces** decide what people see of each other's notes. A space's owner invites others
  into it, and they can then read and edit every note in it.

Everything is private until you share a space. You share whole spaces, never single notes: a
shared space works like a folder you keep together, and every note filed in it is shared.

## 1. Add someone to the server

An admin does this. The first account on the server is an admin.

1. In **Settings → Server → People**, choose **Invite someone…** and enter the username they'll
   sign in with. People is in the web app, the Mac app and the Android app.
2. Send them the link you get, in a message or an email. It works once, for 7 days.
3. They open it, choose a password, and they're signed in. After that they can sign in on any
   device.

> [!TIP]
> Make the invitation with notesd open at the address the other person will use, such as your
> domain or the machine's name on your network. A link made from `localhost` won't work for them.

Resetting passwords, making someone an admin, removing an account and adding people from the
command line are covered in [Accounts](../self-hosting/users.md#adding-people).

## 2. Share a space

1. Open the space's **Space info**. Choose the space's name at the top of the sidebar, then
   **Info**. You can also find it in **Settings → Spaces**.
2. Under **People**, everyone else on the server is listed. Choose a name to invite that person.
   The first invitation shares the space.
3. The invitation shows up at the top of the other person's space switcher, with **Join** and
   **Decline**. When they join, the space and its notes appear on all their devices.

notesd sends no emails or notifications about invitations, so tell the person you've invited
them.

## Owners and members

The person who shares a space is its **owner**. Everyone who joins is a **member**.

| | Owner | Member |
| --- | :---: | :---: |
| Read, edit, add and delete notes | ✓ | ✓ |
| Invite people, cancel invitations, remove people | ✓ | |
| Rename the space, change its icon, delete it | ✓ | |
| Hand the space to someone else (**Make owner**) | ✓ | |
| Leave the space | | ✓ |

An owner can't leave their own space. To get out of it, they make someone else the owner first,
or stop sharing.

## Working in a shared space

- **Who changed what.** A note someone else edited last shows their name in the list, such as
  "Ana · 2h", and "Edited by Ana" in the editor.
- **What's new.** In the space switcher, a dot and a line such as "Ben edited 3 notes · 2h" show
  what others changed since you last opened the space.
- **Where you are.** A shared space has a Shared mark beside its name wherever it's listed.
  Choose the mark to open Space info.
- **Moving notes.** Moving a note into a shared space, or out of one, changes who can see it, so
  the app asks first and says who.
- **Editing at the same time.** Edits from different people merge the same way as edits from two
  of your own devices. See [Offline and sync](offline-and-sync.md).

## What can't be shared

- **Locked notes.** A locked note is for your eyes only, so a shared space can't have any. To
  share a space that has locked notes, remove their locks first.
- **Your first space**, "Notes". It is never shared, so everyone always has a private space.
- **People on another server.** You can only share with accounts on your own server.

## Leaving, removing and stopping

| What happens | The result |
| --- | --- |
| A member **leaves**, or the owner **removes** them | The space and its notes are removed from that person's devices. What they changed stays in the space. |
| The owner **stops sharing** | Everyone else loses the space, and pending invitations are cancelled. The space and its notes stay with the owner. |
| The owner **deletes** the space | It has to be empty first, as for any space. |
| An admin **removes the owner's account** | Each space that person owned goes to the member who joined it first. |

Edits are never lost this way. If a device has changes that haven't synced when it loses a
space, it keeps them as copies in its first space.

## Good to know

- On iPhone you can use shared spaces, but you can't add people to the server. Use the web, Mac
  or Android app for that.
- [Export and the Markdown mirror](../self-hosting/markdown-mirror-and-export.md) include the
  shared spaces each account is in, as folders next to its own.
- Each shared space is stored on the server as its own database file. See
  [Where your data lives](../../concepts/where-data-lives.md).
- Who can see what, and what removing someone does and doesn't take back: see the
  [security model](../../concepts/security-model.md#shared-spaces).
