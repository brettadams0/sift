package dev.sift.data.media

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.imaging.Pipeline
import dev.sift.model.ExportPreset
import dev.sift.testing.SyntheticFrames
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14.7 and §13, on the hardware that actually decides them.
 *
 * A 12MP frame is roughly 144MB as unbounded `CV_32FC3`-equivalent float (§2.1),
 * and the pipeline holds a source, a working copy and a resize reference at
 * once. `largeHeap` buys headroom but does not remove the risk, which is why §12
 * requires the OOM to be caught and retried at half resolution — this test is
 * how we find out whether that path is a formality or the common case.
 *
 * The timing here is the only honest answer to §13's 2.5s budget. Every number
 * quoted so far has come from a JVM benchmark on a desktop core, which says how
 * the code scales and nothing about a phone's memory bandwidth, core mix or
 * thermal behaviour.
 *
 * Deliberately asserts only "it finished without OOM". A hard time threshold
 * would fail on a cold or throttled device for reasons unrelated to the code;
 * the measurement is logged and read, not gated on.
 */
@RunWith(AndroidJUnit4::class)
class GradeMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun twelveMegapixelGradeCompletesWithinHeap() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.i(TAG, "heap: standard ${am.memoryClass}MB, large ${am.largeMemoryClass}MB")

        val frame = SyntheticFrames.portrait(width = 4000, height = 3000)
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
            "12MP grade: ${elapsedMs}ms on ${Runtime.getRuntime().availableProcessors()} cores " +
                "(§13 budget 2500ms), profile=${result.profile}, " +
                "fellBack=${result.fellBackToOriginal}, jpeg=${result.jpeg.size} bytes",
        )

        assertTrue("the encoder produced no output", result.jpeg.isNotEmpty())
        assertTrue("output geometry is wrong", result.width > 0 && result.height > 0)
    }

    /**
     * §4.3 caps concurrent frames at 2 because three at once will not fit. This
     * runs the two that are supposed to fit, sequentially but without letting the
     * first be collected, which is the shape the grading worker produces.
     */
    @Test
    fun twoFramesHeldAtOnceDoNotExhaustTheHeap() {
        val first = SyntheticFrames.portrait(width = 4000, height = 3000)
        val second = SyntheticFrames.portrait(width = 4000, height = 3000)

        val a = Pipeline.process(Pipeline.Request(Pipeline.SourceFrame(first), ditherSeed = 1L))
        val b = Pipeline.process(Pipeline.Request(Pipeline.SourceFrame(second), ditherSeed = 2L))

        assertTrue(a.jpeg.isNotEmpty() && b.jpeg.isNotEmpty())
    }

    private companion object {
        const val TAG = "SiftGradeMemoryTest"
    }
}
