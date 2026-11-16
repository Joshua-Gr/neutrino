# Neutrino

An Android [document provider](https://developer.android.com/guide/topics/providers/document-provider)
that exposes files stored in Proton Drive through the system file picker, so apps that only
work with files picked that way, such as [KeePassDX](https://www.keepassdx.com/), can open and
save them in place.

> **Third-party application, not affiliated with or supported by Proton.**

## Why

Some apps deliberately support only the system file picker and no cloud connectors. Proton Drive
for Android does not offer a writable document provider, so those apps cannot save back to a
file kept in Drive. Neutrino fills that gap until an official option exists — a temporary
answer to a temporary problem.

## Design

- Sign-in will happen in the browser on Proton's own page; Neutrino never sees the account
  password.
- Files will be read and written through the official
  [Proton Drive SDK](https://github.com/ProtonDriveApps/sdk), with Proton's end-to-end encryption.
- A save will land locally at once and be uploaded in the background as a new revision of the
  same file. Revision history is kept, and a save made against an outdated version is stored as
  a conflict copy instead of overwriting newer work.

## Status

Early development; nothing is released yet.

## License

[GPL-3.0-only](LICENSE).
