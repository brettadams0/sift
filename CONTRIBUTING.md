# Contributing

Sift is built to [`docs/SIFT_SPEC.md`](docs/SIFT_SPEC.md). The `§` numbers in code, tests, CI and
this file refer to it. A change that departs from the spec says so and says why — the README's
three deviations are the model.

Every bug since 0.1.0 was found on a real device, not by the test suite. A report from a phone,
with the device and Android version, is the most useful thing you can send.

## Setup

JDK 17. The imaging pipeline needs nothing else:

```sh
./gradlew :core:imaging:test   # the pipeline; no Android SDK
```

`settings.gradle.kts` includes the Android modules only when an SDK is present, so that command
works on a machine that has never installed one. For everything else, point `local.properties`
at an SDK (`sdk.dir=...`).

## What CI runs

```sh
./gradlew :core:model:build :core:imaging:test :core:testing:build   # no Android SDK
./gradlew assembleDebug assembleRelease
./gradlew :app:verifyNoNetworkPermissionsRelease
git status --porcelain core/data/schemas                              # must print nothing

# on an emulator or device, API 30 and API 35
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The instrumented tests write real files into MediaStore. Run them on an emulator you can wipe, not
on a phone with photos you care about.

## Rules that are easy to break

- **`:core:imaging` does not depend on Android.** CI tests it in a job with no SDK installed; an
  Android import there breaks that job, on purpose (§4.1).
- **The app has no network permission.** `verifyNoNetworkPermissionsRelease` reads the merged
  manifest and fails on `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`. A new
  dependency that pulls one in gets it removed in the manifest, not the check relaxed (§3).
- **Room schemas are committed.** An entity change ships with its regenerated schema under
  `core/data/schemas` in the same commit (§4.2).
- **Trashing an original goes through `ApprovalGuard`.** It is the one place that decision is made,
  and its failure mode is permanent photo loss (§9.3). A second code path that trashes, however
  convenient, will not be merged.
- **A test that cannot fail is not a test.** A new check is shown failing against the defect it
  pins before it is shown passing — `BandingTest` runs its control without dither for that reason
  (§14.6).
- **Unmeasured constants are labelled as such.** If a threshold is a guess, the code and the
  README say so; see "Still open" in the README.

## Commits and changelog

A release commit names the version and the defect it fixes: `0.2.8 — the export date was written
to a column no app can write`. User-visible changes get a `CHANGELOG.md` entry that states what
was wrong, how it was found, and what was measured afterwards.
