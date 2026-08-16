package dev.sift.data.media

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.imaging.ColorSpaceTag
import dev.sift.imaging.FloatImage
import dev.sift.imaging.Pipeline
import dev.sift.model.ExportPreset
import dev.sift.testing.SyntheticFrames
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

    @Test
    fun twelveMegapixelGradeCompletesAtFullOrHalfResolution() {
        logHeap()

        val full = tryGrade(4000, 3000)
        if (full != null) {
            assertTrue("the encoder produced no output", full.jpeg.isNotEmpty())
            return
        }

        Log.w(
            TAG,
            "12MP OOMed at full resolution — this device needs §12's retry on every " +
                "large frame, which is a performance finding, not a crash.",
        )

        val reduced = requireNotNull(tryGrade(HALF_RESOLUTION_LONG_EDGE, 1536)) {
            "§12's half-resolution retry also ran out of memory. Grading cannot " +
                "complete on this device at any resolution the app tries."
        }
        assertTrue(reduced.jpeg.isNotEmpty())
    }

    /**
     * §4.3 caps concurrent frames at 2. This holds two decoded sources alive at
     * once — the shape the grading worker produces — at the resolution the
     * device actually manages, to confirm the cap is survivable rather than
     * merely documented.
     */
    @Test
    fun twoFramesHeldAtOnceDoNotExhaustTheHeap() {
        val edge = if (canAllocateTwelveMegapixels()) 4000 to 3000 else 2048 to 1536

        val first = SyntheticFrames.portrait(width = edge.first, height = edge.second)
        val second = SyntheticFrames.portrait(width = edge.first, height = edge.second)

        val a = Pipeline.process(Pipeline.Request(Pipeline.SourceFrame(first), ditherSeed = 1L))
        val b = Pipeline.process(Pipeline.Request(Pipeline.SourceFrame(second), ditherSeed = 2L))

        Log.i(TAG, "two ${edge.first}x${edge.second} frames held at once: ok")
        assertTrue(a.jpeg.isNotEmpty() && b.jpeg.isNotEmpty())
    }

    private fun tryGrade(width: Int, height: Int): Pipeline.Result? = try {
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
        Log.i(
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

    /** Probe without running a whole grade, so the check itself is cheap. */
    private fun canAllocateTwelveMegapixels(): Boolean = try {
        FloatImage.alloc(4000, 3000, ColorSpaceTag.GAMMA_SRGB)
        true
    } catch (_: OutOfMemoryError) {
        false
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
    }
}
