# Prebuilt APK

`sift-0.1.1-release.apk` — signed, minSdk 30, 2.9 MB.

Direct download (works in a phone browser):
https://github.com/brettadams0/sift/raw/main/dist/sift-0.1.1-release.apk

Install steps: [../docs/INSTALL.md](../docs/INSTALL.md)

## Why a binary is committed here

GitHub Releases is the right home for this, and it is where it should move. The
build that produced this APK ran in an environment whose credentials could not
create a release, so the binary lives in the tree instead. Once you publish a
release from the web UI (or CI does it), delete this directory — a 2.9 MB
artifact per version is not something a git history should carry indefinitely.

CI already uploads the release APK as a build artifact on every green run, so
this file is a convenience, not the source of truth.
