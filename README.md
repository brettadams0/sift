# sift

[![CI](https://github.com/brettadams0/sift/actions/workflows/ci.yml/badge.svg)](https://github.com/brettadams0/sift/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An Android app for clearing a camera roll. You swipe through photos to keep or reject them, and the
rejects are trashed in one confirmation. Each keeper is then graded by a pipeline that measures that
frame and derives its own correction, and the original is trashed only after you approve the
result. It runs entirely on the phone, and the app ships with **no `INTERNET` permission** — a build
step reads the merged manifest and fails if one appears. The imaging pipeline is plain Kotlin, with
no OpenCV and no Android dependency, so its tests run on a bare JVM. The machine-learning upscaler
the spec describes has deliberately not been built: the spec says to A/B it against a plain Lanczos
baseline first, and that A/B has not run.

**[Download the APK](dist/)** — a signed sideload build for Android 11 or newer (API 30+);
[install guide](docs/INSTALL.md).

<!-- HERO (W3): before/after grading images go here — dimensions, weights and alt text to come
from W3. -->

## Does it grade at full resolution?

It didn't, and the green test suite couldn't tell. On an emulator with a ⟦FACT sift-12mp-heap⟧
heap, a full 12-megapixel grade ran out of memory ⟦FACT sift-12mp-oom-margin⟧ short of finishing.
The fallback in §12 of the spec — retry at half resolution — then produced a ⟦FACT
sift-fallback-px⟧ image, and the app shipped that as the master. Every large photo on hardware in
that class was silently downgraded, which is the plain reason graded output looked bad.

Peak memory was several full-resolution float copies of the frame alive at once. Four were removed:
the decoded bitmap is freed before the float frame is allocated, the linear conversion reuses the
source, quantisation happens in place, and the quality gates read the encoded bytes rather than a
rebuilt frame. The working copy stays, because each retry in §6.12's chain has to start from an
untouched frame. ([`Pipeline.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/Pipeline.kt))

The first fix appeared to do nothing, and the reason was the test. `GradeMemoryTest` built its
request without `ownsSource`, so the pipeline still copied the source frame — the largest
allocation the fix removed. With the test matching what `GradeWorker` actually does:

| 12 MP grade, on emulators in CI | API 30 | API 35 |
|---|---:|---:|
| Heap | ⟦FACT⟧ | ⟦FACT⟧ |
| Completed at full resolution (`fellBack=false`) | **⟦FACT⟧** | **⟦FACT⟧** |
| Time per grade, across runs | ⟦FACT⟧ | ⟦FACT⟧ |
| Budget in §13 of the spec | 2.5 s | 2.5 s |

It now completes at full resolution, and `GradeMemoryTest` **requires** that rather than accepting
either outcome, so a regression fails the build. It is still well over the time budget on two
emulated cores. Closing that means fixed-point, tiling or NDK SIMD, and the last of those reopens
the OpenCV decision below. The CI job prints the on-device numbers into its log on every run, pass
or fail; the full history is in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md).
([`GradeMemoryTest.kt`](core/data/src/androidTest/kotlin/dev/sift/data/media/GradeMemoryTest.kt),
[`ci.yml`](.github/workflows/ci.yml))

## How it works

### Triage

The library is scanned through MediaStore, decoding each photo at no more than 1024 px on the long
edge, which is what the perceptual hash needs. Near-duplicates are clustered by dHash, and
screenshots are detected. Swipes are recorded in Room and can be
undone; nothing is deleted until the batch is confirmed.
([`MediaStoreRepository.kt`](core/data/src/main/kotlin/dev/sift/data/media/MediaStoreRepository.kt),
[`PerceptualHash.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/PerceptualHash.kt))

### Grading

Each keeper is measured — tone and clipping, colour cast, chroma, skin, sharpness, noise,
faces — and routed to a Portrait or a Scene
grade. A photo counts as a portrait when a face is found or skin covers more than 8% of it
(`faceCount > 0 || skinFraction > 0.08`). Every parameter is derived from that frame's
measurements; constants exist only as targets and bounds, which is the spec's first rule. Values
stay unbounded float end to end, so nothing clamps between stages (§2.1). Per-pixel passes run in parallel by row, except dither, which
uses a seeded random sequence and is parallelised over fixed chunks so its output stays
reproducible.
([`FrameAnalyzer.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/FrameAnalyzer.kt),
[`FrameAnalysis.kt`](core/model/src/main/kotlin/dev/sift/model/FrameAnalysis.kt),
[`FaceDetector.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/FaceDetector.kt),
[`Parallel.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/Parallel.kt))

### Quality gates

Six gates check the result: skin range, no new clipping, no shadow crush, sharpness preserved, no
banding, chroma sanity. When one fails, the grade is retried along the remedy chain in §6.12,
and if it still fails the original ships and the report says which gate refused it. A degraded
photo is worse than an unprocessed one.
([`QualityGates.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/QualityGates.kt),
[`GateResults.kt`](core/model/src/main/kotlin/dev/sift/model/GateResults.kt))

### Approval and trash

The original is trashed only when all five invariants in §9.3 hold — among them that the export
still decodes, checked at that moment rather than at write time. `ApprovalGuard` is the single place
that decision is made. Rejected photos and approved originals are separate kinds of request with
separate confirmations, because agreeing to one is not agreeing to the other. Every lifecycle
transition is written to Room before any file is touched, so a process killed mid-transition
resumes instead of trashing twice.
([`ApprovalGuard.kt`](core/data/src/main/kotlin/dev/sift/data/media/ApprovalGuard.kt),
[`TrashCoordinator.kt`](core/data/src/main/kotlin/dev/sift/data/media/TrashCoordinator.kt),
[`LifecycleRepository.kt`](core/data/src/main/kotlin/dev/sift/data/media/LifecycleRepository.kt))

## Three places it departs from the spec

The app is built to [`SIFT_SPEC.md`](docs/SIFT_SPEC.md) v3. These are the three places it doesn't
follow it, and each is a judgement worth arguing with.

### The ML upscaler is not built

This is the spec being followed, not departed from. §6.6 ends with an instruction:

> Before writing any ONNX code: A/B it. Build a Lanczos + unsharp baseline. Compare on ten of your
> real photos at 100%. If the difference doesn't justify 30 seconds per image and a native
> dependency, ship the baseline and delete this section.

So what exists is the baseline, the gate that decides whether to upscale at all, the interface a
model would plug into, the two treatments that stop a learned upscaler looking synthetic — written
against that interface — and the harness that runs the comparison. What does not exist is a
Real-ESRGAN session, because building it first would mean doing the work the spec says to justify. Face detection depends on the same decision, and the router works
without a detector by falling back to the skin rule.
([`Upscale.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/Upscale.kt),
[`ModelPolicy.kt`](core/ml/src/main/kotlin/dev/sift/ml/ModelPolicy.kt))

### No OpenCV

The spec lists OpenCV and then names its most dangerous defect: OpenCV ships two different LAB
scalings, and porting the 8-bit form's `-128` arithmetic into a float pipeline "silently produces
garbage that still looks like a plausible image". The spec's only defence is a golden test.

Writing the conversion directly removes the trap instead of guarding it. There is no 8-bit LAB
path here to confuse with the float one. `ColorSpaces` produces true CIELAB and nothing else, with
the sRGB transfer curve as an 8192-entry interpolated table, and the exact formula used outside
[0, 1], where the pipeline's unbounded values live. `LabGoldenTest` pins it to published reference
values rather than to another implementation, so it can't drift in step with a mistaken one — a
neutral grey must come out with `a*, b*` near 0, not near 128. The cost is several hundred lines of
image maths and no SIMD, which shows up in the time budget above.
([`ColorSpaces.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/ColorSpaces.kt),
[`LabGoldenTest.kt`](core/imaging/src/test/kotlin/dev/sift/imaging/LabGoldenTest.kt))

### A hand-written JPEG encoder

§2.4 requires 4:4:4 chroma — no colour subsampling. Without OpenCV the platform option is
`Bitmap.compress`, which takes a quality number and nothing else; whether it subsamples depends on
whichever Skia build the phone has. That turns a requirement into a hope. `JpegEncoder` is a
baseline encoder with two-pass optimised Huffman tables, and `JpegEncoderTest` doesn't trust its
own flag: it parses the SOF0 marker of real output and asserts 1×1 sampling on all three
components.
([`JpegEncoder.kt`](core/imaging/src/main/kotlin/dev/sift/imaging/JpegEncoder.kt),
[`JpegEncoderTest.kt`](core/imaging/src/test/kotlin/dev/sift/imaging/JpegEncoderTest.kt))

## Running it

Install the APK from [`dist/`](dist/); [`docs/INSTALL.md`](docs/INSTALL.md) covers sideloading and
first-run permissions. Every release is signed with the same key, so each one installs over the
last as an update.

To build, JDK 17. The imaging pipeline needs nothing else; the app needs an Android SDK named in
`local.properties` (`sdk.dir=...`).

```sh
./gradlew :core:imaging:test   # the pipeline; no Android SDK needed
./gradlew build                # everything
./gradlew assembleRelease      # the sideloadable APK
./gradlew :core:imaging:test -Dsift.bench=true   # also runs PipelineBenchmark, which is opt-in
```

`settings.gradle.kts` includes the Android modules only when an SDK is present, so the first
command works on a machine that has never installed one.

## Development

```sh
./gradlew :core:model:build :core:imaging:test :core:testing:build   # no Android SDK
./gradlew assembleDebug assembleRelease
./gradlew :app:verifyNoNetworkPermissionsRelease
git status --porcelain core/data/schemas                              # must print nothing

# on emulators, API 30 and API 35
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The instrumented tests write real files into MediaStore. Run them on an emulator you can wipe.

Three properties are enforced by the build, and each was checked to fail when broken: no network
permission in the merged manifest; Room schemas committed with the entity change that produced
them; and the kotlinx-serialization serializers surviving R8 in the release APK. If an entity
changes on purpose, commit the regenerated schema under `core/data/schemas` in the same commit.

What the tests prove, what they don't, and what the device suite found on its first run are in
[`docs/TESTING.md`](docs/TESTING.md). Changes, each with what was wrong and how it was found, are
in [`CHANGELOG.md`](CHANGELOG.md).

## Layout

| Module | |
|---|---|
| `:app` | Compose UI, navigation, view models, WorkManager |
| `:core:model` | shared immutable types; no dependencies |
| `:core:imaging` | the grading pipeline; plain Kotlin — no Android, no OpenCV |
| `:core:ml` | model policy and the upscale A/B harness; no ONNX, deliberately |
| `:core:data` | Room, MediaStore, DataStore, lifecycle and approval safety |
| `:core:testing` | synthetic fixtures, and the slot for golden-image fixtures |
| `docs/` | the spec, the install guide, test evidence, performance history |
| `dist/` | the signed release APK |

## License

MIT — see [LICENSE](LICENSE).
