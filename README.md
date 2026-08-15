# Sift

A swipe-based triage deck over the Android camera roll that batches deletions
into a single confirmation, then puts keepers through a per-image adaptive
grading pipeline and exports to a watched folder.

100% on-device. No accounts, no network, no cost. **The app ships with no
`INTERNET` permission at all.**

Built to [`SIFT_SPEC.md`](docs/SIFT_SPEC.md) v3.

**→ [Install and first-run guide](docs/INSTALL.md)**

---

## Status

Every module compiles. `./gradlew build` is green, and `./gradlew assembleRelease`
produces a **2.9 MB signed APK** that `apksigner` verifies as installable across
API 30–35.

| Milestone | Deliverable | State |
|---|---|---|
| **M0** | Permissions, MediaStore, thumbnail grid | Builds; **not yet run on a device** |
| **M1** | Swipe deck, Room, undo, batched trash | Builds; **not yet run on a device** |
| **M2** | dHash, clustering, screenshot detection | Logic tested; UI not yet run |
| **M3** | Float pipeline + `FrameAnalysis` + Portrait grade | **Done and tested** |
| **M4** | Scene grade + router | **Done and tested** |
| **M5** | Quality gates + fallback | **Done and tested** |
| **M6** | Export presets, encode, EXIF, settings | Encode + presets tested; EXIF path not yet run |
| **M7** | Review UI, lifecycle, deferred original-trashing | Builds; **not yet run on a device** |
| **M8** | Upscale | **Deliberately not built** — see below |

**Of 47 tests, 46 pass and 1 is deliberately skipped** (§14.1, which needs your
own fixtures — see below).

> **The honest caveat: no part of this has run on a phone yet.** It compiles,
> R8 does not strip anything it needs, the tests that can run do run, and the
> APK installs — but the UI, the MediaStore reads, the trash dialogs and the
> EXIF copy have never executed against real Android. Treat the first session as
> a shakedown, and see [what is not proven](#what-is-not-proven) before trusting
> it with the approve-and-trash step.

```sh
./gradlew :core:imaging:test   # the pipeline — needs only a JDK, no Android SDK
./gradlew build                # everything, needs local.properties → sdk.dir
./gradlew assembleRelease      # the sideloadable APK
```

---

## The three deviations from the spec, and why

Everything else follows §6.1's canonical order and §0's three rules exactly.
These three do not, and each is a judgement call worth disagreeing with.

### 1. No OpenCV. The imaging pipeline is pure Kotlin.

The spec lists OpenCV as the imaging dependency. It is not used.

§6.2 and trap #1 identify the most dangerous defect in the whole document:
OpenCV ships *two different* LAB scalings, and porting `-128` arithmetic from an
8-bit reference into a float pipeline "silently produces garbage that still looks
like a plausible image". The spec's only defence is a golden test.

Writing the conversion directly removes the trap rather than guarding it. There
is no 8-bit LAB path in this codebase to confuse with the float one; `ColorSpaces`
produces true CIELAB and nothing else, and `LabGoldenTest` pins it to *published*
reference values rather than to another implementation, so it cannot drift in
lockstep with a mistaken reference.

Three things follow from it:

- `:core:imaging` unit-tests on a plain JVM, which is what §4.1 asks for
  ("independently testable ... it needs to be exercisable in isolation") and what
  made every number in §6 checkable while building it.
- §2.1's unbounded float is guaranteed end-to-end, because there is no library
  boundary that might clamp.
- The ~15MB native dependency and its ABI restriction go away.

The cost is real: several hundred lines of image maths that OpenCV would have
provided, and no SIMD. §13's performance budgets are consequently **unmeasured**
— see below.

### 2. The JPEG encoder is hand-written.

§2.4 requires 4:4:4 chroma and optimised Huffman tables, via OpenCV's
`IMWRITE_JPEG_SAMPLING_FACTOR_444`. Without OpenCV the platform alternative is
`Bitmap.compress`, which exposes a quality number and nothing else — whether it
subsamples is an undocumented detail of whichever Skia build is on the device.
That turns a non-negotiable into a hope.

`JpegEncoder` makes it a fact, and `JpegEncoderTest` makes it an asserted one: it
parses the SOF0 marker of real output and checks the sampling factors are 1×1 on
all three components. Baseline sequential, two-pass optimised Huffman, verified
round-trip against `ImageIO`.

### 3. No ONNX code. M8 is not built.

This is following the spec, not departing from it. §6.6 ends with an instruction
and §18 repeats it as open decision 3:

> Before writing any ONNX code: A/B it. Build a Lanczos + unsharp baseline.
> Compare on ten of your real photos at 100%. If the difference doesn't justify
> 30 seconds per image and a native dependency, ship the baseline and delete this
> section.

So what exists is the baseline (`Upscale.LanczosBaseline`), the gate and
sharpness cap (`Upscale.decide`), the seam a model drops into
(`Upscale.SuperResolver`), the two treatments that keep a learned upscaler from
looking synthetic written against that seam (`detailPreservingBlend`,
`softenSmallFaces`), and the harness that answers the question
(`UpscaleComparison`). What does not exist is a Real-ESRGAN session, because
building it first would be doing the work the spec says to justify.

Face detection is downstream of the same decision — see `ModelPolicy` for the
reasoning. The router degrades rather than breaking without a detector: §6.4's
portrait rule is `(faceCount > 0 || skinFraction > 0.08)`, and the contiguity
guard still rejects terracotta.

---

## What the tests actually prove

`:core:imaging` carries 46 passing tests. The ones that matter:

- **`LabGoldenTest`** — sRGB primaries land on published CIELAB values; a neutral
  grey has `a*, b* ≈ 0` and not `≈ 128` (trap #1 stated as an assertion);
  operating in the wrong colour space throws rather than producing muddy output;
  the P3 soft-clip keeps gradient in saturated reds instead of flattening them.
- **`PortraitGradeTest`** — convergence in 3–4 passes at damping 0.7; the §14.2
  guard rail (`b* ∈ [15,22]`) across eight skin tones; **the correction is
  identical against neutral, terracotta and blue backgrounds**, which is §6.7's
  single most important constraint stated as an outcome; the delta is applied
  globally, not through the mask.
- **`BandingTest`** — a dithered gradient is clean, *and the same gradient without
  dither bands*. §14.6 explicitly demands that control, because a banding test
  that passes with dither disabled is measuring nothing.
- **`JpegEncoderTest`** — 4:4:4 verified by parsing the file, not by trusting a flag.
- **`PipelineTest`** — end to end; §14.3 no-regression; a gate failure falls back
  to the original *and says which gate*; presets hit exact dimensions; a
  screenshot needs both signals, so a photo at a screen resolution is not one.
- **`GateEvaluationTest`** — each of the six gates fails on its own threshold and
  maps to the §6.12 remedy.

### One thing the suite found

The first run of §14.3 failed: Scene was crushing 2.3% of a well-exposed frame
into shadow. The auto-levels stage was mapping a black point at L\*24 down to
L\*1, and the gamma and S-curve after it pushed the result past the crush line —
"adaptive terms overreaching", which is exactly what §14.3 says a large delta
means. The levels endpoints are now solved against the *composed* tone chain
rather than their own stage, so the curve cannot manufacture crushed shadows or
new clipping. See `SceneGrade.COMPOSED_BLACK_FLOOR_L`.

### What is not proven

- **§14.1 golden-image parity is skipped, not passing.** It needs three of your
  portraits with their committed Python outputs. Drop them into
  `core/testing/src/main/resources/golden/`, list them in `GoldenFixtures`, and
  the test turns on. It reports as *skipped* rather than passing vacuously,
  because a green parity test with no fixtures is worse than none on the one gate
  §6.2 calls the only defence against the LAB trap.
- **§13's performance budgets are unmeasured.** No device, no numbers. The pure
  Kotlin pipeline has no SIMD, and the analysis pass takes a full-resolution
  Laplacian and a cube root per pixel. §13 says a miss means stop and fix — that
  measurement has not happened, and the 12MP figures are the ones to check first.
- **Nothing has run on a device.** This is the big one. Compiling proves the
  types line up; it proves nothing about whether the swipe deck feels right,
  whether `createTrashRequest` returns what the code expects, whether the EXIF
  copy actually preserves GPS, or whether a 12MP grade finishes before the
  system kills the service. There are no instrumented tests yet — §14's
  device-dependent cases (§14.7 memory, §14.8 cancelled dialog, §14.10
  original-retention) are specified and unwritten.
- **§14.4 router accuracy, §14.5 clustering precision/recall** need hand-labelled
  real photographs; the synthetic fixtures show the mechanisms work, not that the
  thresholds are right for your library.

### What *is* mechanically enforced

Three properties that would otherwise erode silently are checked by the build,
and each has been verified to actually fail when violated rather than passing
vacuously:

- **No network permission (§3).** `verifyNoNetworkPermissionsRelease` reads the
  *merged* manifest — the one that ships — and fails on `INTERNET`,
  `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`. WorkManager pulls
  `ACCESS_NETWORK_STATE` in through manifest merging; it is removed explicitly,
  and the guard is what stops the next dependency putting it back.
- **Room schemas stay committed (§4.2).** CI fails if an entity changed without
  its schema being regenerated, so migrations never stop being reviewable.
- **R8 does not strip the serializers.** `derivedParamsJson` and
  `gateResultsJson` are not optional (§6.3, §5) — a minified build that lost
  `FrameAnalysis$$serializer` would throw at runtime on the first graded photo.
  The release APK is checked to still contain them.

---

## Architecture

```
:app             Compose UI, navigation, ViewModels, WorkManager
:core:model      Shared immutable types. No dependencies.
:core:imaging    The pipeline (§2, §6). Pure Kotlin, no Android, no OpenCV.
:core:ml         Model policy and the §6.6 A/B harness. No ONNX, deliberately.
:core:data       Room, MediaStore, DataStore, lifecycle and approval safety.
:core:testing    Synthetic fixtures and the §14.1 golden slot.
```

`settings.gradle.kts` includes the Android modules **only when an SDK is
present**, so `gradle :core:imaging:test` works on a machine that has never
installed one. That is not a convenience; §4.1 requires the module that
determines output quality to be exercisable in isolation, and a test suite that
needs a device attached does not get run.

### The safety-critical path

§9.3 says an original may be trashed only when all five invariants hold, and
trap #14 calls violating invariant 2 "the one genuinely unrecoverable bug in the
app". `ApprovalGuard` is the single place that decision is made — one code path
to audit, one to test. `TrashCoordinator` cannot express a mixed batch: triage
rejects and approved originals are separate types of request with separate
confirmation copy, because a user confirming one has not consented to the other
(trap #16).

`LifecycleRepository` writes every transition to Room *before* any filesystem
operation, and `ORIGINAL_TRASHED` is terminal in the transition table, so process
death mid-transition resumes rather than double-trashing.

---

## Still open

Carried over from §18, unchanged:

1. **Does upscale survive the §6.6 A/B?** Run `UpscaleComparison` over ten of
   your photos. It can delete M8 entirely — and if it does, ML Kit becomes the
   better face-detection dependency.
2. **Detail-blend percentage.** 15% is committed as the midpoint of the
   specified 12–18% band, not a measured value. `UpscaleComparison.sweepDetailBlend`
   exists to settle it.
3. **Pending-review backlog cap.** Still 300, still a guess.

And one added here:

4. **`OutputSharpen.TARGET_LAPLACIAN_VARIANCE`.** §6.10 asks for an amount
   "targeting a consistent apparent sharpness", which requires a target to aim
   at. The current value is defensible, not measured. Same treatment as the
   detail blend: tune once against real photographs, then commit it.
5. **Instrumented tests.** §14.7, §14.8 and §14.10 all need a device or an
   emulator. §14.10 is the one that matters — its failure mode is permanent
   photo loss — and until it exists, the §9.3 invariants are enforced by
   `ApprovalGuard` and reviewed by eye, not proven. Treat the approve-and-trash
   step with more suspicion than the rest of the app.

---

## Licence

MIT.
