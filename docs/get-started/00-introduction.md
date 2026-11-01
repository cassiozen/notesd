---
title: Introduction
description: What notesd is, and what you need to set it up.
---

# Introduction

notesd is a notes app you host yourself. The server is a single binary with SQLite inside, and
nothing else to set up. You use it from the web app it serves, or from the Android and iPhone
apps, which keep a full copy of your notes, work offline and sync when they can reach the server.

The server runs on a machine you control, with HTTPS in front of it. Your browser and phones
sync with it over that HTTPS address.

## What you need

- **A server:** a 64-bit Linux machine (x86-64 or ARM64) that stays on and that you can SSH into,
  such as a VPS, a home server or a Raspberry Pi 4 or 5.
- **[Docker](https://docs.docker.com/get-docker/)** on it, or the
  [notesd binary](../guides/self-hosting/binary-systemd.md) instead.
- **For HTTPS:** a domain you can point at the server, or [Tailscale](https://tailscale.com) on
  the server and your devices.
- **For your phone** (optional): Android 9 or later, or an iPhone on iOS 26 or later plus a Mac
  with Xcode to build the app.

## What you'll do

In about fifteen minutes:

1. [Run the server](01-run-the-server.md)
2. [Create your account](02-create-your-account.md)
3. [Set up HTTPS](03-set-up-https.md)
4. [Write your first note](04-write-your-first-note.md)
5. [Add your phone](05-add-your-phone.md)

**Next:** [1. Run the server](01-run-the-server.md)
