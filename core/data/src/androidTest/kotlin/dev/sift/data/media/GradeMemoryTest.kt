package dev.sift.data.media

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.imaging.Pipeline
import dev.sift.model.ExportPreset
import dev.sift.testing.SyntheticFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14.7 and §12, on hardware rather than on a desktop JVM.
 *
 * A 12MP frame is ~144MB as unbounded float (§2.1) and the pipeline holds a
 * source, a working copy and a resize reference, so the full-resolution path
 * does not fit everywhere. **That is expected, and §12 is the answer to it:**
 * catch the OOM and retry at half resolution.
 *
 * The first version of this test asserted that 12MP simply succeeds, which is a
 * claim about the device rather than about the code — it OOMed on the CI
 * emulator and told us nothing we could act on. What ships is
 * `GradeWorker`'s retry, so what is asserted is the retry: a grade completes at
 * full resolution *or* at [HALF_RESOLUTION_LONG_EDGE], and the log says which.
 * A device where even the reduced path fails is a real failure and this fails
 * with it.
 *
 * No time threshold. A cold or throttled emulator would fail one for reasons
 * that have nothing to do with the code; §13's budget is a number to read out
 * of the log, not a gate.
 */
@RunWith(AndroidJUnit4::class)
class GradeMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A 12MP grade must complete at **full resolution**.
     *
     * This used to accept either outcome, because it did not fit: peak
     * footprint was the source frame, a linear copy, a working copy, a
     * quantisation copy and a re-materialised output frame, and on a 512MB
     * heap that OOMed 48MB from the end. §12's retry then produced a 2048px
     * image and the app shipped it as the master — a silent resolution
     * downgrade, and the actual answer to "the grading gives bad outputs".
     *
     * Four of those allocations are gone, so the reduced path is now a genuine
     * emergency rather than the normal case, and this asserts it. If it fails,
     * exports on this class of device are quarter-resolution again and that is
     * worth failing a build over.
     */
    @Test
    fun twelveMegapixelGradeCompletesAtFullResolution() {
        logHeap()

        val full = tryGrade(4000, 3000)

        assertTrue(
            "a 12MP grade ran out of memory at full resolution. Exports on this " +
                "device would be 2048px masters — see the README's performance " +
                "section before relaxing this.",
            full != null,
        )
        assertTrue("the encoder produced no output", full!!.jpeg.isNotEmpty())
        assertEquals("the master must keep the source's geometry", 4000, full.width)
        assertEquals(3000, full.height)
    }

    /** §12's retry still has to work, for the devices where it is reached. */
    @Test
    fun theHalfResolutionRetryStillProducesAnImage() {
        val reduced = requireNotNull(tryGrade(HALF_RESOLUTION_LONG_EDGE, 1536)) {
            "§12's half-resolution retry ran out of memory. Grading cannot " +
                "complete on this device at any resolution the app tries."
        }
        assertTrue(reduced.jpeg.isNotEmpty())
    }

    /**
     * A batch does not accumulate. This is the memory question the app actually
     * asks.
     *
     * The first version of this test held two 12MP frames alive at once, on the
     * grounds that §4.3 caps concurrent frames at 2 — and it OOMed so hard the
     * runtime could not allocate the exception ("OutOfMemoryError thrown while
     * trying to throw an exception"). Two things were wrong with it, and neither
     * was a defect in the app:
     *
     * - `GradeWorker` grades **sequentially**, one asset at a time. Nothing in
     *   the shipping code ever holds two decoded frames simultaneously, so the
     *   test was asserting against a scenario the app does not produce. §4.3's
     *   cap is a ceiling on a design that has not been built, not a description
     *   of the current worker.
     * - It probed capacity by allocating a 144MB frame and discarding it, then
     *   allocated two more before the first could be collected. The test was
     *   creating its own memory pressure.
     *
     * What a batch really does is grade frames back to back, and what can go
     * wrong is retention: a frame held past its iteration by a cache, a
     * reference, or a thread-local. That fails after N frames rather than
     * immediately, which is exactly the failure a single-frame test misses.
     */
    @Test
    fun gradingFramesBackToBackDoesNotAccumulate() {
        var completed = 0
        repeat(BATCH_FRAMES) { i ->
            // Nothing from the previous iteration is referenced here, so a
            // failure means something else is holding it.
            val result = tryGrade(BATCH_EDGE, BATCH_EDGE * 3 / 4, log = false)
                ?: error(
                    "frame ${i + 1} of $BATCH_FRAMES ran out of memory at " +
                        "${BATCH_EDGE}px. Frame 1 fitting and frame ${i + 1} not " +
                        "means the pipeline is retaining something across grades.",
                )
            assertTrue(result.jpeg.isNotEmpty())
            completed++
        }
        Log.i(TAG, "batch: $completed frames at ${BATCH_EDGE}px back to back, no accumulation")
        assertEquals(BATCH_FRAMES, completed)
    }

    private fun tryGrade(width: Int, height: Int, log: Boolean = true): Pipeline.Result? = try {
        val frame = SyntheticFrames.portrait(width = width, height = height)
        val started = System.nanoTime()
        val result = Pipeline.process(
            Pipeline.Request(
                source = Pipeline.SourceFrame(frame),
                preset = ExportPreset.MASTER,
                ditherSeed = 7L,
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (log) Log.i(
            TAG,
            "12MP grade: ${width}x$height in ${elapsedMs}ms on " +
                "${Runtime.getRuntime().availableProcessors()} cores " +
                "(§13 budget 2500ms), profile=${result.profile}, " +
                "fellBack=${result.fellBackToOriginal}, jpeg=${result.jpeg.size} bytes",
        )
        result
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM grading ${width}x$height: ${e.message}")
        null
    }

    private fun logHeap() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.i(
            TAG,
            "heap: standard ${am.memoryClass}MB, large ${am.largeMemoryClass}MB, " +
                "max ${Runtime.getRuntime().maxMemory() / 1_048_576}MB",
        )
    }

    private companion object {
        const val TAG = "SiftGradeMemoryTest"

        /** Mirrors `GradeWorker.HALF_RESOLUTION_LONG_EDGE` (§12). */
        const val HALF_RESOLUTION_LONG_EDGE = 2048

        /**
         * The batch runs at a size every device manages, because the question
         * is whether memory grows across frames, not whether one frame fits —
         * that is the other test.
         */
        const val BATCH_EDGE = 1600
        const val BATCH_FRAMES = 6
    }
}
