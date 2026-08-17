# Changelog

All versions are sideload-only builds signed with the same key, so every one
installs over the last as an update (see [dist/](dist/)).

## 0.2.8

The export date is fixed, and the instrumentation added in 0.2.7 is what fixed
it. That same run also showed the 12MP grade now completes at full resolution.

### Fixed

- **Graded exports landed at the top of the gallery dated today.** Three
  releases tried to write `DATE_TAKEN`; the instrumented run measured the
  result:

  ```
  export date: wanted=1552555613000 updateRows=0 err=null
               readback=datetaken=null date_added=… date_modified=…
  ```

  Zero rows changed, no exception, on both API 30 and 35. **A non-system app
  cannot write those columns at all** — MediaProvider drops `DATE_TAKEN` and
  `DATE_MODIFIED` from an update by an ordinary caller, is left with nothing to
  apply, and returns 0. Every previous attempt was writing to a column the
  provider silently ignores, which is exactly why each one looked reasonable and
  changed nothing.

  Both columns are derived from the file, so the fix is to hand the scanner a
  file that already says the right thing:

  - `DATE_TAKEN` comes from EXIF `DateTimeOriginal` — but that is a wall clock
    with no zone, so it does not name an instant, and MediaProvider will not
    guess. It trusts the tag outright only when an offset tag says which zone
    the wall clock is in; without one it compares against the file's
    modification time and **discards the value when the two disagree by more
    than a day**. An export is always a file written just now, so every photo
    older than yesterday hit that: correct EXIF, valid file, `DATE_TAKEN` null.
    Exports now carry `OffsetTimeOriginal`.
  - `DATE_MODIFIED` is read off the filesystem, so the exported file's
    modification time is set to the capture time before `IS_PENDING` clears and
    the scan reads it.

  For a source that was already dated but zoneless — most cameras — the offset
  is recovered rather than invented: the wall clock read as UTC, minus the
  instant MediaStore holds for the same photograph, is the offset it was
  written in.

- **A shared `SimpleDateFormat` was formatting EXIF stamps from parallel
  grading threads.** It is not thread-safe. Never observed, latent since the
  pipeline was parallelised.

### Confirmed

- **A 12MP frame now grades at full resolution.** 0.2.7's corrected memory test
  reports `4000x3000 … fellBack=false` on both API 30 (512MB heap) and API 35
  (576MB), where it previously fell back to a reduced-resolution grade. §13's
  2.5s budget is still missed by a wide margin on a 2-core emulator (12.6s and
  21.0s), which remains open and is not what these releases were about.

## 0.2.7

The first honest CI run showed the 0.2.5 memory work had no measurable effect,
and the reason was the test, not the code.

### Fixed

- **The memory test measured a configuration the app does not use.**
  `GradeMemoryTest` built its `Pipeline.Request` without `ownsSource`, so it
  defaulted to false and the pipeline still copied the source frame — the single
  largest allocation 0.2.5 removed. The OOM headroom came back within 7KB of the
  pre-optimisation figure, which is what a no-op looks like. The test now matches
  `GradeWorker`.
- **A UI test raced an asynchronous Room `Flow`.** The bin's queue arrives on a
  background dispatcher, so the first composition always shows the empty state
  and Compose is idle for the gap before the real content lands. Asserting
  straight after `setContent` passed on API 35 and failed on API 30 — a timing
  difference, not a behavioural one. It waits for the screen to settle now.

### Changed

- **The export date is instrumented rather than guessed at again.** Three
  attempts have each been a plausible theory that turned out to be wrong, and
  the device tests still report `DATE_TAKEN` null. This release folds the write
  into the same update that clears `IS_PENDING` — the operation that finalises
  the row — and logs the update's row count, any exception, and a read-back of
  all three date columns. CI dumps that log, so the next attempt starts from
  evidence.

### Known failing

`ExportMetadataTest`'s two date assertions and
`GradeMemoryTest.twelveMegapixelGradeCompletesAtFullResolution` fail on API 30
and 35. They are left failing on purpose: the first is unproven and the second
is the honest state of §13. The EXIF capture-time test passes, so the file
itself carries the right date even where MediaStore does not.

## 0.2.6

CI had been reporting green while tests failed. Fixing that surfaced three real
failures, including that 0.2.4's export-date fix did not work.

### Fixed

- **The instrumented job passed with failing tests in it.** The emulator runner
  executes a multi-line `script:` as a separate `sh -c` per line, so the
  `status=$?` I added in 0.2.3 was gone by the time `exit $status` ran on the
  next line — it exited 0 and the job reported success. Two runs were announced
  as green with failures in them, including one reported to the user as such.
  The script is now a single chained invocation, so the exit code is real.
- **Exports were still dated today.** 0.2.4 wrote `DATE_TAKEN` at insert *and*
  again after `IS_PENDING` cleared, and patched EXIF through a file descriptor
  on the pending item. The device test came back with `DATE_TAKEN` null and
  `DATE_MODIFIED` set to now, so none of it survived. The scanner that runs when
  `IS_PENDING` clears reads the finished file and decides those columns itself —
  the only reliable way to influence it is to hand it a file that already
  carries the right EXIF. The JPEG is now staged to a scratch file, decorated
  there, and streamed in complete. The two `DATE_TAKEN` writes remain as
  belt-and-braces rather than as the mechanism.
- **A test teardown crashed the instrumentation process.** `PendingScreenTest`
  closed its in-memory database while `PendingViewModel`'s `stateIn` collector
  was still reading it, which threw from a Room worker thread and took the other
  four tests in the class down with it — they were reported as never having run.
  An in-memory database dies with the process; there was nothing to close.

### Confirmed on device

- **The rotation fix is correct.** `aRotatedSourceDecodesUprightExactlyOnce`
  passed on API 30 and 35: `ImageDecoder` does apply EXIF orientation, so
  removing the second bake was right.

## 0.2.5

**The grading fix.** Graded photos were coming out worse than the originals, and
the cause was not the grade — it was that most of them were never graded at full
resolution.

A 12MP frame did not fit. Peak footprint was five full frames alive at once, and
on the 512MB heap the emulator reports, the pipeline OOMed 48MB from the end.
§12's retry then re-ran at 2048px and the app shipped *that* as the master. Every
large photo was silently downgraded to a quarter of its resolution, and nothing
said so.

### Fixed

- **Four of the five full-frame allocations are gone.**
  - The decoded bitmap is freed before the float buffer is allocated, instead of
    after (−48MB).
  - `Pipeline.Request.ownsSource` lets a caller that will not reuse its frame
    have it converted in place rather than copied (−144MB). `GradeWorker` opts
    in; it decodes, grades and drops. Defaults to false, because it is a promise
    about the caller.
  - Quantisation writes in place — `resized` was dead after the sharpness
    reading, so the defensive copy was pure waste (−144MB).
  - The quality gates read the encoded bytes directly instead of rebuilding a
    float frame from them (−144MB). Nothing is approximated: channel clipping is
    exact and simpler at 8-bit, and chroma still measures the same strided proxy.
- **A reduced-resolution grade is no longer shipped as a master.** It is treated
  as a fallback: the original is kept untouched and the count is surfaced. Those
  exports could never pass §9.3's invariant 4 anyway — it compares the export's
  dimensions against the source's — so each one was refused and requeued, to OOM
  and be refused again in a loop.

### Tested

- `GradeMemoryTest` now **requires** a full-resolution 12MP grade rather than
  accepting either outcome, so a regression puts exports back to quarter
  resolution and fails the build. §12's retry keeps its own test.

Timing is unchanged within noise (5.0s warm median against 5.4s, on a loaded
container). This bought resolution, not speed; §13's 2.5s budget is still missed.

## 0.2.4

Two bugs from real use, both in the export path, both invisible to every test
that existed.

### Fixed

- **Photos came out rotated.** `ImageDecoder` applies EXIF orientation itself —
  unlike `BitmapFactory`, which does not — and the pipeline was baking the
  orientation in again on top of it. Every photo whose camera wrote a non-normal
  tag was rotated twice, and since the export records `TAG_ORIENTATION = NORMAL`
  nothing downstream could undo it. Shots tagged NORMAL were untouched, which is
  why it looked intermittent rather than total. §6.1 step 2 is still satisfied —
  the rotation *is* in the pixels before anything measures or crops (trap #2),
  it is just the decoder doing it.
- **Exports landed at the top of the gallery dated today.** `DATE_TAKEN` was
  being set when the row was inserted, and then silently overwritten: clearing
  `IS_PENDING` makes MediaStore rescan the finished file and re-derive its
  columns from disk. The value is now written again after the row settles, which
  is the one ordering the scanner cannot undo. 0.1.2's fix set the right value
  at the wrong moment.
- **And dated today in the cloud, too.** Google Photos does not read MediaStore;
  it dates a photo from EXIF `DateTimeOriginal`. Sources that have none —
  screenshots, messaging-app saves, anything already stripped — produced exports
  that were correct on the device and wrong in the backup. Sift now writes the
  capture time it already knows when the source did not supply one, and never
  overwrites one that did.

### Changed

- **Decode frees the source bitmap before allocating the float buffer.** At 12MP
  that is a ~48MB bitmap held across a ~144MB allocation, and the CI emulator
  measured a 512MB `largeHeap` ceiling where a full-resolution grade OOMs — so
  every large frame was falling back to §12's half-resolution retry and quietly
  shipping a 2048px master. This takes ~48MB off peak decode. It is a step, not
  a fix: see the README's performance section.

### Added

- `ExportMetadataTest` — device tests for both bugs. Capture date survives to
  MediaStore and to EXIF, `DATE_MODIFIED` is in seconds, and a source tagged
  `ROTATE_90` decodes upright exactly once. The orientation test asserts the
  contract rather than the mechanism, so it still means something if a future
  Android changes who applies the rotation.

### Also in this release

- **The memory test was asserting against a scenario the app never creates.**
  It held two 12MP frames alive at once on the strength of §4.3's cap of two
  concurrent frames, and OOMed hard enough that the runtime could not allocate
  the exception. But `GradeWorker` grades sequentially — nothing in the shipping
  code holds two decoded frames at the same time — and the test's own capacity
  probe allocated a third 144MB frame and discarded it just before the other
  two. Neither problem was a defect in the app. It now grades a batch back to
  back and asserts that memory does not accumulate across frames, which is the
  failure mode a sequential worker can actually have and one a single-frame test
  cannot see.
- CI dumps the instrumented tests' logcat into the job output. The §13 timings
  were being measured on device and then discarded, because `Log.i` does not
  reach the Gradle console.

## 0.2.3

A presentation pass. **No behaviour changed** — every decision, threshold and
state transition is byte-for-byte what 0.2.2 did. What changed is how it feels
and how fast it draws.

### Performance

- **The swipe deck no longer recomposes on every drag frame.** The card's offset
  was read during composition, so each pointer event recomposed the card *and*
  the full-size `AsyncImage` inside it. It now lives in an `Animatable` read only
  inside `graphicsLayer` lambdas, which run at draw time. The verdict labels are
  always emitted rather than appearing at a threshold, so the card's content no
  longer changes structurally mid-drag.
- **Images are cached properly.** A configured Coil loader: a memory cache at a
  quarter of the heap, a 96MB disk cache, and crossfade. Swiping back a photo,
  or toggling before/after in review, previously meant a fresh decode every time.

### Look and feel

- Releasing a drag short of the threshold springs back instead of snapping, and
  a new card settles in rather than appearing where the last one was thrown from.
- Verdict labels are outlined pills. As bare coloured text they were legible over
  a dark frame and invisible over a bright one.
- Toss and keep are 64dp tonal circles colour-matched to the swipe badges. They
  were 24dp glyphs — the two actions taken hundreds of times a session had the
  smallest targets on screen.
- The review screen's three identical button rows now have a hierarchy: the
  verdict for the current photo stays filled and full width, recovery and bulk
  actions drop to text weight behind a divider.
- The bin's thumbnails are rounded, spaced, and dimmed so the rescue button is
  the bright thing in each cell; removals animate out.
- Real typography and shape scales, and one shared spacing scale instead of each
  screen inventing its own.
- **The launch flash is gone.** The splash theme hardcoded the dark background,
  so a light-themed phone showed a black frame before the first Compose frame.
  Now day/night with matching system bars.
- Back and undo icons are auto-mirrored, so they point the right way in RTL.

### Tested

- `PendingScreenTest` — the first UI-level tests in the project, driving the real
  screen over the real view model and an in-memory database. They pin the two
  0.2.1 bugs directly: pressing Delete reaches the commit callback, and the label
  fits on one line. Both were wrong at once while every unit test stayed green,
  because the defect was in the wiring between a screen and a view model.
- The app's UI tests run alongside the `:core:data` suite on API 30 and 35 in CI.

## 0.2.2

The first CI run of the new instrumented suite found two real defects in the
approve-and-trash path. Both were invisible to every existing test.

### Fixed

- **§12's recovery path was dead code.** When an approved asset's export failed
  verification — it stopped decoding, or came out the wrong size —
  `buildApprovedOriginalsRequest` calls `requeueForGrade` to put it back in the
  grading queue. But the lifecycle table listed `ORIGINAL_TRASHED` as the *only*
  exit from `APPROVED`, so that call returned false and did nothing. The asset
  sat approved with a broken export forever: never regraded, never trashed,
  re-refused on every subsequent batch. `APPROVED → QUEUED_FOR_GRADE` is now
  legal, which is also the safe direction — `ORIGINAL_TRASHED` is reachable only
  from `APPROVED`, so leaving that state removes the asset from batch 2 until it
  has been graded and approved again.
- **"The latest job for this asset" meant the slowest one.** `latestForAsset`
  ordered by `processingMs`, which is how *long* a grade took, not when it ran.
  `ApprovalGuard` reads that job's `approvedAt` as §9.3 invariant 5, so once an
  asset carried more than one job — which the fix above makes routine — a slow,
  approved, superseded grade could authorise trashing an original whose current
  grade the user had never seen. `EditJob` gains `createdAt` (schema v3,
  migration included) and the query orders on it.

### Changed

- **The 12MP memory test asserts what ships.** It previously required a
  full-resolution grade to succeed, which is a claim about the device rather
  than the code, and it OOMed on the CI emulator. §12's answer to a large frame
  is to catch the OOM and retry at half resolution, so that is what is now
  asserted: the grade completes at full or at 2048px, and the log says which. A
  device where even the reduced path fails is a real failure and still fails.
- The instrumented test APK declares `largeHeap`, matching the app. Without it
  the tests ran on a 192MB heap — a configuration nothing ships — and a single
  144MB frame could not fit.

## 0.2.1

### Fixed

- **The Commit button in the bin did nothing.** It was wired to "go back" and
  nothing else, so with anything queued for deletion the trash dialog was
  unreachable — which is every time it mattered. The deck and the bin each had
  their own `TriageViewModel` instance and their own `trashRequest`; the bin's
  Commit had no way to reach the one the deck was watching. Both screens now
  share one view model, hoisted above the `NavHost`, and the trash launcher
  lives there too — one launcher, because `NavHost` composes both screens during
  a transition and two would fire the same `IntentSender` twice.
- **That button's label wrapped onto two lines.** It was an `IconButton`, a
  fixed 48dp circle sized for a 24dp glyph, with text inside it. Now a
  `TextButton` reading `Delete N`, so the label matches what pressing it does.

### Added

- **Instrumented tests for approve-and-trash** — §14.7, §14.8 and §14.10, the
  gap the README has been flagging since 0.1.0. §14.10 is the one whose failure
  mode is permanent photo loss.
  - Each of §9.3's five invariants is failed *on its own*, with everything else
    satisfied, against a real `ContentResolver` and real files in MediaStore.
    Invariant 3 ("the output still decodes") cannot be checked any other way —
    a fake resolver that always returns a valid bitmap asserts nothing.
  - A fallback original is never in deletion batch 2 (trap #14), an
    unverifiable output is requeued rather than dropped, `ORIGINAL_TRASHED` is
    terminal against every other state, and a replayed result after process
    death trashes nothing twice.
  - The two batches never share an asset (trap #16).
  - A cancelled dialog leaves every decision intact and retryable, on both
    batches.
  - A 12MP grade completes within the heap, and logs its time on the device —
    the only honest answer to §13's budget.
  - They run on API 30 and 35 emulators in CI on every push.

## 0.2.0

The first release driven entirely by using the app rather than by reading the
spec. Everything here is a bug that only a phone could have found.

### Fixed

- **Gate-failed frames no longer go to Review.** When the pipeline could not
  improve a photo it shipped the original unchanged — and then queued it for a
  decision anyway, so a run turned into scrolling through untouched photos
  rejecting each one. These are now closed out at the point of grading, and the
  count is reported in Settings with the gate that failed.
- **Most frames were failing a gate in the first place.** Two independent
  faults: the portrait grade applied its lightness correction as a uniform
  translation, which pushed highlights past white and tripped the clipping
  gate on any bright frame; and the §6.12 retry that is supposed to lower the
  tone strength never actually reached the grade, so all three attempts were
  identical. The correction now rolls off over a band as wide as the shift
  itself — highlights compress instead of clipping — and the retry's reduced
  strength is threaded through.
- **No reason dialog after every reject.** Rejecting is one tap and the frame
  is gone. The confirmation snackbar offers **Why?** if you have an opinion.
- **Regrade actions actually regrade.** "Other profile" and "half strength"
  recorded an intent that the worker then ignored, so the photo came back
  identical. The override is now persisted (schema v2) and folded into the
  settings the next grade runs with.
- **Duplicate exports cleaned up.** A fallback-to-original used to write a copy
  of your photo into `Pictures/Sift` alongside the untouched original. It no
  longer writes anything, and Settings has a housekeeping action that removes
  the ones earlier builds left behind.

### Performance

12MP grade is roughly **2× faster** — 12.1s → 5.4s warm median on a 4-core JVM.
§13 budgets 2.5s, so it is still missed; see the README's performance section
for where the time goes and why this is not a device measurement.

Row-parallel per-pixel passes, table-driven sRGB transfer functions,
quarter-scale local-contrast blur, sampled sharpness measurement, and an ingest
path that stops decoding 12MP frames to compute a 64-bit hash.

### Added

- `PipelineBenchmark`, opt-in via `-Dsift.bench=true`, so the performance claim
  above can be re-run rather than trusted.
- Settings → Housekeeping: stale-export cleanup with a count.

### Upgrading

Room migrates v1 → v2 in place. Nothing is dropped; pending deletions and
photos awaiting review survive.

## 0.1.4

- **Pending deletions screen.** Queued rejects are shown as a grid with a
  per-photo ↩. Previously a mis-swipe could only be undone if it was one of the
  last ten decisions, in order.
- Volume keys: up keeps, down tosses (was the other way round).
- Review is reachable from the home screen with a pending-count badge.

## 0.1.2

- **Fixed an empty library.** MediaStore paging used `LIMIT`/`OFFSET` in the
  sort-order string, which Android has ignored since API 30 — the query
  returned nothing and the grid was blank. Now uses `QUERY_ARG_LIMIT`.
- **Fixed exports losing their capture date.** Graded photos appeared in the
  gallery dated today. `DATE_TAKEN` and `DATE_MODIFIED` are now carried over
  from the source.
- `READ_MEDIA_IMAGES` is API 33+; added `READ_EXTERNAL_STORAGE` capped at 32 so
  Android 11 and 12 can read the library at all.

## 0.1.0

First build. Pipeline, triage deck, review, export — all of it green in tests
and none of it yet run on a phone.
