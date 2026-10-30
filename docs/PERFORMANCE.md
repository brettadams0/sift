<!-- Moved from the README unchanged when it was cut to a front page. The figures here are as
the README stated them; they were not re-measured in the move. -->

# Performance

§13 budgets a 12MP grade at **2.5s**. It is not met.

Measured on a 4-core x86 JVM (`./gradlew :core:imaging:test -Dsift.bench=true`,
`PipelineBenchmark`, 4000×3000 portrait, MASTER preset):

| | 12MP grade |
|---|---|
| cold (first frame, JIT warming) | 8.7s |
| warm median | **5.4s** |
| §13 budget | 2.5s |

The same benchmark on the 0.1.4 pipeline measured a 12.1s warm median, so this
is roughly a 2× improvement that still lands at a bit over twice the budget.
Where it came from, in rough order of contribution:

- **Row-parallel per-pixel passes** (`Parallel`). Every colour conversion, tone
  curve, blur and DCT is independent per row and was running on one thread.
  §4.3's limit of 2 concurrent frames is a cap on how many photos are in flight
  — a 12MP frame is ~144MB as float and three at once will OOM — not on how
  many cores may work on one of them. Dither is deliberately excluded: it draws
  from a seeded RNG in a fixed sequence (§2.3), and reproducibility is what
  lets `BandingTest` assert anything, so it parallelises over fixed chunks with
  per-chunk seeds rather than over rows.
- **sRGB transfer functions became table lookups.** A `pow(x, 1/2.4)` per
  channel per pixel is 36M calls on a 12MP frame; an 8192-entry LUT with
  interpolation is within float precision of the exact curve, and the exact
  form is still used outside [0,1] where §2.1's unbounded values live.
- **Local contrast blurs at quarter scale** and expands bilinearly. A
  large-radius low-frequency blur has nothing above the Nyquist limit of the
  downscaled grid to lose.
- **The sharpness gate samples** rather than taking a full-resolution Laplacian,
  and reuses the source's variance when the geometry did not change.
- **Ingest stopped decoding at full resolution.** The dHash needs 1024px on the
  long edge, and it was decoding 12MP frames to compute a 64-bit number.

## On a device, the problem is memory before it is time

The instrumented suite measures this on an emulator, and the result was worse
than the JVM figure suggested:

```
heap: standard 192MB, large 512MB, max 512MB
OOM grading 4000x3000: failed to allocate 48MB with 4.4MB until OOM,
  target footprint 536870912, growth limit 536870912
```

**A full 12MP grade did not complete in a 512MB heap.** Not "was slow" — it ran
out of memory 48MB from the end. §12's half-resolution retry then produced a
2048px image, and the app shipped that as the master. So on hardware in that
class every large photo was silently downgraded to a quarter of its resolution,
which is the honest answer to why graded output looked bad.

Peak footprint at 12MP was five full frames alive at once:

| | before | after |
|---|---|---|
| decoded bitmap held across the float allocation | 48MB | freed first |
| source frame | 144MB | 144MB |
| linear copy of the source | 144MB | *reuses the source* |
| working copy (needed — retries re-copy from it) | 144MB | 144MB |
| quantisation copy | 144MB | *quantises in place* |
| output frame rebuilt from bytes for the gates | 144MB | *gates read the bytes* |

Four of those are gone. The working copy stays because §6.12's retry chain needs
an untouched frame to start each attempt from, and the source stays because it
is that frame. `GradeMemoryTest` now **requires** a full-resolution 12MP grade
rather than accepting either outcome, so a regression here fails the build.

Two related changes: `Pipeline.Request.ownsSource` lets a caller that will not
reuse its frame have it converted in place — a promise about the caller, not
about the pipeline, so it defaults to false. And a grade that only fitted at
reduced resolution is no longer written as a master at all; it is treated as a
fallback, leaving the original untouched and reporting the count. A 2048px stand
-in for a 12MP photograph could not pass §9.3's invariant 4 anyway, so those
grades were being refused and requeued to OOM again in a loop.

Timing is unchanged within measurement noise — 5.0s warm median against 5.4s
before, on a container running at load 3.4. These changes bought resolution, not
speed.

**It worked, and the device tests now say so rather than the bench.** The first
run with a correctly-configured `GradeMemoryTest` reports a 4000x3000 grade
completing with `fellBack=false` on both API 30 (512MB heap) and API 35 (576MB).
The budget is a different matter: 13-21s across runs, on two emulated cores,
against §13's 2.5s. Closing that is a different problem — fixed-point,
tiling, or NDK/SIMD, which reopens the OpenCV decision above.
