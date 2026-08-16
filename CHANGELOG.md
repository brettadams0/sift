# Changelog

All versions are sideload-only builds signed with the same key, so every one
installs over the last as an update (see [dist/](dist/)).

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
