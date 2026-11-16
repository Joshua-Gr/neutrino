# Security policy

## Reporting a vulnerability

Please report security issues privately through GitHub:
[Report a vulnerability](https://github.com/Joshua-Gr/neutrino/security/advisories/new)
(the **Security** tab of this repository). Do not open a public issue.

A useful report says what an attacker can do, what they need first (another app installed on
the device, a network position, physical access), and how to reproduce it.

## Supported versions

Only the latest release receives fixes.

## Scope

In scope:

- Sign-in, session handling and how the session is stored on the device
- The offline encrypted copy of served files
- Saving back to Drive: the upload queue and conflict handling
- The document provider: what other apps on the device can read or write through it
- The release pipeline and the signing of published APKs

Out of scope:

- Proton's servers, apps and the [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk):
  report those to Proton through its [bug bounty](https://proton.me/security/bug-bounty)
- Apps that open files through Neutrino, such as password managers
- Attacks that require a rooted device or an already unlocked device in the attacker's hands

## Verifying a release

Every release APK is signed with the same key. Its certificate SHA-256 digest is:

```
a8f65fc82b7399b116d6c687b37308aae2b9b94970d04920ba434dbb294dacb2
```

Check a downloaded APK with `apksigner` from the Android SDK build tools:

```sh
apksigner verify --print-certs neutrino-<version>.apk
```

The `certificate SHA-256 digest` line must match the value above. If it does not, do not install
the APK, and please report it.

## What to expect

Neutrino is maintained by one person in their spare time. Reports are handled on a best-effort
basis, with no guaranteed response time.
