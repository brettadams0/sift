<!-- Moved from the README unchanged when it was cut to a front page. The figures here are as
the README stated them; they were not re-measured in the move. -->

# What the tests prove

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

## One thing the suite found

The first run of §14.3 failed: Scene was crushing 2.3% of a well-exposed frame
into shadow. The auto-levels stage was mapping a black point at L\*24 down to
L\*1, and the gamma and S-curve after it pushed the result past the crush line —
"adaptive terms overreaching", which is exactly what §14.3 says a large delta
means. The levels endpoints are now solved against the *composed* tone chain
rather than their own stage, so the curve cannot manufacture crushed shadows or
new clipping. See `SceneGrade.COMPOSED_BLACK_FLOOR_L`.

## The device tests (§14.7, §14.8, §14.10)

`:core:data` carries an instrumented suite that runs on API 30 and 35 emulators
in CI. It exists because §14.10's failure mode is permanent photo loss and it is
the only part of this app where a bug is not recoverable.

- **`ApprovalGuardTest`** — each of §9.3's five invariants fails *on its own*,
  with every other condition satisfied. The trap #14 case differs from the
  passing case by a single boolean. It runs against a real `ContentResolver`
  with real files in MediaStore, because invariant 3 is "the output still
  decodes, checked now rather than at write time" and a fake resolver that
  always returns a valid bitmap asserts nothing.
- **`OriginalRetentionTest`** — a fallback original is never in deletion batch
  2; an output that stopped decoding is requeued rather than dropped *or*
  trashed; `ORIGINAL_TRASHED` is terminal against every other state and
  `APPROVED` is the only way in; a result replayed after process death logs no
  second transition. Plus trap #16: the two batches are disjoint.
- **`CancelledDialogTest`** — §14.8 on both batches. Cancelling leaves every
  decision present and uncommitted, the retry commits the same set, and a
  cancelled batch 2 leaves the asset `APPROVED` with `originalTrashedAt` null.
- **`GradeMemoryTest`** — a 12MP grade completes at full resolution or via
  §12's half-resolution retry, and a batch graded back to back does not
  accumulate. It logs the on-device time, which is the only honest answer to
  §13; CI dumps that logcat into the job output.

They stop at the system dialog: `createTrashRequest` returns a `PendingIntent`
only the system's confirmation UI can resolve. So the assertions are about which
asset ids Sift *puts into* a request — the part Sift controls, and the part that
can be wrong.

**What the first run found.** Two defects, both in the safety path, both
invisible to 48 green unit tests:

- `APPROVED` listed `ORIGINAL_TRASHED` as its only exit, so §12's
  `requeueForGrade` — the recovery for an export that stopped decoding — returned
  false and did nothing. Affected assets sat approved with a broken export
  forever, re-refused on every batch.
- `latestForAsset` ordered by `processingMs`, the grade's *duration*. "Latest"
  therefore meant "slowest". Since `ApprovalGuard` reads that job's `approvedAt`
  as invariant 5, an asset with more than one job could have a stale approval
  stand in for consent to a grade the user had never seen — and fixing the first
  defect is what makes a second job routine. `EditJob` gained `createdAt`
  (schema v3).

Neither could have been caught without enumerating the state table on a real
device, which is the argument for this suite existing.

## What is not proven

- **§14.1 golden-image parity is skipped, not passing.** It needs three of your
  portraits with their committed Python outputs. Drop them into
  `core/testing/src/main/resources/golden/`, list them in `GoldenFixtures`, and
  the test turns on. It reports as *skipped* rather than passing vacuously,
  because a green parity test with no fixtures is worse than none on the one gate
  §6.2 calls the only defence against the LAB trap.
- **§13's 12MP budget is missed.** A full-resolution 12MP grade does now run in
  a 512MB heap — CI reports `4000x3000 … fellBack=false` on API 30 and 35 — but
  it takes 13s or more there against a 2.5s budget. See
  [performance](PERFORMANCE.md).
- **UI tests cover one screen, not five.** `PendingScreenTest` drives the bin
  through its real view model and pins the two 0.2.1 defects, but the deck,
  review, grid and settings screens have no UI coverage. The bin got tests
  because that is where a wiring bug shipped; the others are equally exposed to
  the same class of defect.
- **§14.4 router accuracy, §14.5 clustering precision/recall** need hand-labelled
  real photographs; the synthetic fixtures show the mechanisms work, not that the
  thresholds are right for your library.

## What *is* mechanically enforced

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

## Milestones

Current build: **0.2.8** (`versionCode` 14). `./gradlew build` is green, and
`./gradlew assembleRelease` produces a **3.0 MiB signed APK** that `apksigner`
verifies as installable across API 30–35.
Download it from [`dist/`](../dist/).

| Milestone | Deliverable | State |
|---|---|---|
| **M0** | Permissions, MediaStore, thumbnail grid | **Run on a device**; empty-library bug fixed in 0.1.2 |
| **M1** | Swipe deck, Room, undo, batched trash | **Run on a device**; per-photo rescue added in 0.1.4 |
| **M2** | dHash, clustering, screenshot detection | Logic tested; run on a device, thresholds unvalidated |
| **M3** | Float pipeline + `FrameAnalysis` + Portrait grade | **Done and tested** |
| **M4** | Scene grade + router | **Done and tested** |
| **M5** | Quality gates + fallback | **Done and tested** |
| **M6** | Export presets, encode, EXIF, settings | **Run on a device**; capture date actually preserved since 0.2.8 |
| **M7** | Review UI, lifecycle, deferred original-trashing | **Run on a device**; approve-and-trash covered by §14.10 device tests |
| **M8** | Upscale | **Deliberately not built** — see below |

**Of 48 tests, 46 pass and 2 are skipped by design** — §14.1 needs your own
fixtures (see below), and `PipelineBenchmark` is opt-in because it takes a
minute.

> **It now runs on real hardware, and that is where every bug since 0.1.0 came
> from.** An empty library (MediaStore paging), exports landing in the gallery
> with today's date, undo that never returned a photo, and a run where most
> frames failed a quality gate and were shipped as originals — none of those
> were visible from a green test suite. Approve-and-trash — the one path whose
> failure mode is permanent photo loss — now has device tests, and **their first
> CI run found two more**: §12's requeue was dead code because the lifecycle
> table would not let an approved asset leave that state, and "the latest job
> for this asset" was resolved by ordering on grade *duration*, so a stale
> approval could authorise trashing an original whose current grade had never
> been seen. See [what is not proven](#what-is-not-proven) for what remains.

```sh
./gradlew :core:imaging:test   # the pipeline — needs only a JDK, no Android SDK
./gradlew build                # everything, needs local.properties → sdk.dir
./gradlew assembleRelease      # the sideloadable APK
```

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
5. **UI tests for the remaining screens.** The bin now has them, and they pin
   the exact defect that shipped. The deck, review, grid and settings screens do
   not, and they are exposed to the same class of bug: a callback wired to the
   wrong thing, which no view-model test can see.
