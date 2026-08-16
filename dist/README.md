# Prebuilt APK

`sift-0.2.0-release.apk` — signed, minSdk 30, 3.0 MB.

Direct download (works in a phone browser):
https://github.com/brettadams0/sift/raw/main/dist/sift-0.2.0-release.apk

Install steps: [../docs/INSTALL.md](../docs/INSTALL.md)

Signed with the same key as every previous build — signer certificate SHA-256
`40f9639d00c0294c5bb913a37f6c4b3540a42b353034992ed2f694ee9e741154` — so it
installs straight over 0.1.x as an update and keeps your database and your
pending queues. If Android refuses with a signature mismatch, the file did not
come from this repository; do not uninstall to force it, ask instead.

Upgrading from 0.1.x runs a Room migration (schema v1 → v2). Nothing is dropped.

## Why a binary is committed here

GitHub Releases is the right home for this, and it is where it should move. The
build that produced this APK ran in an environment whose credentials could not
create a release, so the binary lives in the tree instead. Once you publish a
release from the web UI (or CI does it), delete this directory — a 3 MB
artifact per version is not something a git history should carry indefinitely.

CI already uploads the release APK as a build artifact on every green run, so
this file is a convenience, not the source of truth.
