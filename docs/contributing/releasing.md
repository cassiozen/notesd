---
title: Releasing notesd
description: Cutting a release, versions, Android and Mac signing, and local release builds.
---

# Releasing notesd

A release is a `v*` tag. Pushing one runs
[`.github/workflows/release.yml`](../../.github/workflows/release.yml), which
checks, builds and publishes everything:

| Artifact | Where |
| --- | --- |
| Server binaries: `notesd-linux-amd64`, `notesd-linux-arm64`, `notesd-darwin-arm64` | GitHub release |
| Signed Android APK: `notesd-<version>.apk` | GitHub release |
| Signed, notarized Mac app: `notesd-mac.dmg`, unversioned so `releases/latest/download/notesd-mac.dmg` always works | GitHub release |
| `notesd-update`, for [updating from Settings](../guides/self-hosting/upgrading.md#updating-from-settings) | GitHub release |
| `SHA256SUMS` for the GitHub release files above | GitHub release |
| Docker image, `linux/amd64` and `linux/arm64` | `ghcr.io/cassiozen/notesd` |
| Signed Android app bundle: `notesd-<version>.aab`, for Google Play | `android-app-bundle` artifact of the workflow run |

## Cutting a release

```bash
git tag v0.3.0
git push origin v0.3.0
```

The workflow:

1. On a macOS runner, builds the Mac app (`mise run release:macos`): signed,
   notarized by Apple, in a DMG that's signed and notarized too. It refuses to
   build one that isn't. The rest waits for it, so a failure here publishes
   nothing.
2. Runs `mise run check` and `mise run test:android`. A failure stops the
   release before anything is published.
3. Builds the server binaries (`mise run release`), with the web app
   embedded and the version from `git describe`.
4. Builds the signed APK and app bundle (`mise run release:android`). It
   refuses to publish an unsigned one. The bundle is uploaded as a workflow
   artifact, not to the GitHub release: it only installs through Play.
5. Pushes the Docker image, tagged `X.Y.Z` and `X.Y`, plus `latest` for
   non-prereleases.
6. Creates the GitHub release: a download table (Play, Mac, iPhone, server)
   followed by generated notes. The same links are on
   [notesd.app/download](https://notesd.app/download/)
   (`website/download/index.html`); keep both in step when an app changes.

The image goes out before the GitHub release because a release can't be
created twice, while a failed image push can be re-run.

**Release the Mac app with its server.** The Mac app lays itself out with the
web app its server serves (the window's buttons sit in the web app's bars), so
each app release goes out with the server release of the same version, and its
user guide says to update both.

To try the Mac build without a release, run the workflow by hand (Actions →
Release → Run workflow): it builds, signs and notarizes the DMG and keeps it as
the run's artifact, and publishes nothing.

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
in Settings → Server. The web app is built with the same version, without the
`v`, and compares it with the server's to offer a reload after an upgrade.
The iPhone app's `MARKETING_VERSION` comes from `git describe` at build time
too (see `apps/ios/Config`), so an app built from a tag reports that version.
Every app sends its version as `X-Notesd-Client`; see
[Compatibility](../reference/api.md#compatibility) for what a release may change.

The APK's `versionName` is the tag without the `v`. Its `versionCode` is
computed from the tag (`scripts/release-version`) so that every release installs over the one before;
the Mac app gets the same number as its build number (`CFBundleVersion`), and `X.Y.Z` as its version:

| Tag | `versionCode` |
| --- | --- |
| `vX.Y.Z` | `XXYYZZ99` |
| `vX.Y.Z-rcN` | `XXYYZZNN` |

So `v0.3.0-rc1` (`00030001`) sorts before `v0.3.0` (`00030099`). Minor
and patch numbers must stay below 100, and rc numbers between 1 and 98:
`scripts/release-version` refuses any other tag, which would share a build number with another release.

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

### Google Play

Play re-signs the app with its own key (Play App Signing), so the keystore
above is only Play's **upload key**: it signs the bundle you upload. A lost
upload key can be reset through Play Console support; the APK key can't.

Because Play's key differs from the APK's, Android won't install a GitHub
APK over the app from Play, or the other way round: switching means
uninstalling, which deletes the notes on the phone. Sync first.

To publish to Play, open the release's workflow run, download the
`android-app-bundle` artifact, and upload the `.aab` in Play Console. Play
refuses a `versionCode` it has seen before, so upload bundles built from
tags; to try a build on the closed testing track before a release, tag an
rc.

## Mac signing and notarization

The Mac app is signed with the team's **Developer ID Application** certificate
and notarized with an **App Store Connect API key**. The workflow reads five
repository secrets:

| Secret | Contents |
| --- | --- |
| `APPLE_CERTIFICATE` | The Developer ID Application certificate and its private key, as a `.p12`, base64-encoded |
| `APPLE_CERTIFICATE_PASSWORD` | The `.p12`'s password |
| `APPLE_API_KEY_P8` | The API key's `.p8` file |
| `APPLE_API_KEY_ID` | The API key's ID |
| `APPLE_API_ISSUER` | The API key's issuer ID |

To make them (once):

1. **The certificate:** in Xcode, Settings → Accounts → Manage Certificates,
   add a Developer ID Application certificate (account holder only). Then in
   Keychain Access, My Certificates, export it as a `.p12` with a password.
2. **The API key:** App Store Connect → Users and Access → Integrations →
   Team Keys → +, with Developer access. Note its key ID and the issuer ID,
   and download the `.p8`; Apple offers it once.
3. **The secrets:**

   ```bash
   base64 < notesd-developer-id.p12 | gh secret set APPLE_CERTIFICATE
   gh secret set APPLE_CERTIFICATE_PASSWORD   # asks for it
   gh secret set APPLE_API_KEY_P8 < AuthKey_<key ID>.p8
   gh secret set APPLE_API_KEY_ID --body <key ID>
   gh secret set APPLE_API_ISSUER --body <issuer ID>
   ```

Keep the `.p12`, its password and the `.p8` backed up somewhere safe. A lost
API key can be revoked and replaced; a Developer ID certificate can be
reissued, but only a few at a time.

The provisioning profile (`apps/desktop/src-tauri/signing/`) is committed: it
holds no secret. Regenerate it in the Apple Developer portal (Profiles →
Developer ID) when the certificate is reissued, as it names the certificate.

## Building a release locally

```bash
mise run release            # server binaries in server/bin
mise run docker             # notesd:dev image for this machine, from those binaries
mise run release:android    # APK in server/bin, Play bundle in apps/android/app/build/release
mise run release:macos      # notarized DMG in server/bin
```

`release:android` signs the APK and bundle when `NOTESD_KEYSTORE` (the
keystore's path), `NOTESD_KEYSTORE_PASSWORD` and `NOTESD_KEY_ALIAS` are set,
and builds them unsigned otherwise. An APK signed with any other key can't be installed over
a published release.

`release:macos` needs the Developer ID certificate in your login keychain and
the API key: `APPLE_API_KEY` (its ID) and `APPLE_API_ISSUER` set, and the
`.p8` at `~/.appstoreconnect/private_keys/AuthKey_<key ID>.p8` (or
`APPLE_API_KEY_PATH`). It sends the app and the DMG to Apple to notarize,
which takes a few minutes.
