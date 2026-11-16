# Neutrino

An Android [document provider](https://developer.android.com/guide/topics/providers/document-provider)
that exposes files stored in Proton Drive through the system file picker, so apps that only work
with files picked that way, such as [KeePassDX](https://www.keepassdx.com/), can open and save them
in place.

> **Third-party application, not affiliated with or supported by Proton.**

## Why

Some apps deliberately support only the system file picker and no cloud connectors. Proton Drive
for Android does not offer a writable document provider, so those apps cannot save back to a file
kept in Drive. Neutrino fills that gap until an official option exists: a temporary answer to a
temporary problem.

## How it works

- **Sign-in** happens in the browser on Proton's own page; Neutrino never sees the account
  password. The session is stored encrypted with an Android Keystore key.
- **Files** are read and written through the official
  [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk), with Proton's end-to-end encryption.
- **Saves** land on the device at once and are uploaded in the background as a new revision of
  the same file. Revision history is kept. A save made against a version that has since changed
  in Drive is uploaded beside it as a conflict copy, so nothing is overwritten, and you are
  notified.
- **Offline**: the last version of each file read through Neutrino is kept encrypted on the
  device and served when Drive is unreachable; saves made offline upload when the network returns.
- **The app** shows the account, how many saves are waiting, and the files other apps have opened
  through it, with when each was last accessed and updated.

## Install

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.joshuagr.neutrino%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FJoshua-Gr%2Fneutrino%22%2C%22author%22%3A%22Joshua-Gr%22%2C%22name%22%3A%22Neutrino%22%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="56"></a>

With [Obtainium](https://github.com/ImranR98/Obtainium) installed, the badge adds Neutrino and keeps it
updated. Releases are also published on this repository's
[Releases](https://github.com/Joshua-Gr/neutrino/releases) page.

Requires Android 16 (API 36) on an arm64 device.

## Build

```
./gradlew assembleDebug
```

Release builds are signed when `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`
and `RELEASE_KEY_PASSWORD` are set in the environment or in `local.properties`; otherwise the
release APK is left unsigned. `./gradlew assembleR8check` builds the minified release signed with
the debug key under its own application ID, to exercise R8 on a device.

Releases are cut by pushing a signed `v*` tag; CI builds, signs and publishes the APK.

## License

[GPL-3.0-only](LICENSE). The bundled Inter typeface is licensed under the
[SIL Open Font License 1.1](licenses/Inter-OFL.txt).
