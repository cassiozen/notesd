---
title: Releasing notesd
description: Cutting a release, versions, Android signing and local release builds.
---

# Releasing notesd

A release is a `v*` tag. Pushing one runs
[`.github/workflows/release.yml`](../../.github/workflows/release.yml), which
checks, builds and publishes everything:

| Artifact | Where |
| --- | --- |
| Server binaries: `notesd-linux-amd64`, `notesd-linux-arm64`, `notesd-darwin-arm64` | GitHub release |
| Signed Android APK: `notesd-<version>.apk` | GitHub release |
| `notesd-update`, for [updating from Settings](../guides/self-hosting/upgrading.md#updating-from-settings) | GitHub release |
| `SHA256SUMS` for all of the above | GitHub release |
| Docker image, `linux/amd64` and `linux/arm64` | `ghcr.io/cassiozen/notesd` |

## Cutting a release

```bash
git tag v0.3.0
git push origin v0.3.0
```

The workflow:

1. Runs `mise run check` and `mise run test:android`. A failure stops the
   release before anything is published.
2. Builds the server binaries (`mise run release`), with the web app
   embedded and the version from `git describe`.
3. Builds the signed APK (`mise run release:android`). It refuses to publish
   an unsigned one.
4. Pushes the Docker image, tagged `X.Y.Z` and `X.Y`, plus `latest` for
   non-prereleases.
5. Creates the GitHub release with generated notes.

The image goes out before the GitHub release because a release can't be
created twice, while a failed image push can be re-run.

### Release candidates

Name prereleases `vX.Y.Z-rcN`, such as `v0.3.0-rc1`. The APK's
`versionCode` only understands `-rcN`: any other suffix (`v0.3.0-beta1`)
gets the same `versionCode` as `v0.3.0` itself (see [Versions](#versions)),
so the prerelease no longer sorts before the release.

A tag with a hyphen is published as a prerelease:
the Docker image gets only its exact version tag (`0.3.0-rc1`, not
`0.3` or `latest`), and [updating from Settings](../guides/self-hosting/upgrading.md#updating-from-settings)
never installs it.

## Versions

The server's version is `git describe --tags`, shown by `notesd version` and
in Settings → Server.

The APK's `versionName` is the tag without the `v`. Its `versionCode` is
computed from the tag so that every release installs over the one before:

| Tag | `versionCode` |
| --- | --- |
| `vX.Y.Z` | `XXYYZZ99` |
| `vX.Y.Z-rcN` | `XXYYZZNN` |

So `v0.3.0-rc1` (`00030001`) sorts before `v0.3.0` (`00030099`). Minor
and patch numbers must stay below 100, and rc numbers below 99.

## Android signing

Every release APK must be signed with the same key, or Android refuses it as
an update and users have to uninstall, losing the notes on the phone. Keep
the keystore and its password backed up somewhere safe; a lost key can't be
replaced.

The workflow reads three repository secrets:

| Secret | Contents |
| --- | --- |
| `NOTESD_KEYSTORE_BASE64` | The PKCS12 keystore, base64-encoded |
| `NOTESD_KEYSTORE_PASSWORD` | Its password, used for both the store and the key |
| `NOTESD_KEY_ALIAS` | The key's alias |

To create a keystore (once):

```bash
keytool -genkeypair -keystore release.jks -alias notesd \
  -keyalg RSA -keysize 4096 -validity 36500
base64 < release.jks   # paste into NOTESD_KEYSTORE_BASE64
```

`*.jks` and `*.keystore` are gitignored; never commit one.

## Building a release locally

```bash
mise run release            # server binaries in server/bin
mise run docker             # notesd:dev image for this machine, from those binaries
mise run release:android    # APK in server/bin
```

`release:android` signs the APK when `NOTESD_KEYSTORE` (the keystore's path),
`NOTESD_KEYSTORE_PASSWORD` and `NOTESD_KEY_ALIAS` are set, and builds it
unsigned otherwise. An APK signed with any other key can't be installed over
a published release.
