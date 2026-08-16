package dev.sift.data.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.model.JobState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §9.3's five invariants, exercised against a real `ContentResolver`.
 *
 * This is the test the README has been calling outstanding since 0.1.0. Trap #14
 * names invariant 2 as "the one genuinely unrecoverable bug in the app": if an
 * original is trashed after a quality gate failed, the only remaining master is
 * a re-encode of it, and no amount of later care gets the original back.
 *
 * Each invariant gets a case that fails *on its own*, with every other condition
 * satisfied. A test where three things are wrong at once proves only that the
 * function rejects something.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val written = mutableListOf<Uri>()

    private fun jpeg(width: Int = 64, height: Int = 48): Uri =
        ApprovalFixtures.writeJpeg(context, width, height).also { written += it }

    @After
    fun tearDown() = ApprovalFixtures.cleanUp(context, written)

    @Test
    fun allFiveSatisfied_isAllowed() = runTest {
        val output = jpeg(width = 64, height = 48)
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = true,
        )
        assertEquals(ApprovalGuard.Verdict.Allowed, verdict)
    }

    /**
     * Invariant 2, trap #14 — the one that must never regress.
     *
     * Everything else here is perfect: the job is DONE, the user approved it, the
     * output exists, decodes, is non-empty and is exactly the promised size. The
     * single difference from the passing case above is `fellBackToOriginal`.
     */
    @Test
    fun fallbackToOriginal_isRefused_evenWhenEverythingElseIsPerfect() = runTest {
        val output = jpeg(width = 64, height = 48)
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output, fellBack = true),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = true,
        )
        val refused = assertRefused(verdict)
        assertTrue(
            "the refusal must explain that the export is a re-encode, not just say no: $refused",
            refused.reason.contains("re-encode"),
        )
        // Not a transient failure — regrading would produce the same fallback.
        assertFalse(refused.requeueGrade)
    }

    /** Invariant 5 — a bulk approve-all must not satisfy this for a held-back item. */
    @Test
    fun notExplicitlyApproved_isRefused() = runTest {
        val output = jpeg()
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output, approvedAt = null),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = false,
        )
        assertRefused(verdict)
    }

    /** Invariant 1 — the job finished. */
    @Test
    fun unfinishedJob_isRefused() = runTest {
        val output = jpeg()
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output, state = JobState.RUNNING),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = true,
        )
        assertRefused(verdict)
    }

    /**
     * Invariant 3 — write-time success is not read-time success.
     *
     * The file was written and the row still points at it; the bytes are gone.
     * This is the case a "did the write return successfully?" check misses
     * entirely, and it is recoverable, so it must ask for a regrade.
     */
    @Test
    fun outputThatNoLongerDecodes_isRefusedAndRequeued() = runTest {
        val output = jpeg()
        ApprovalFixtures.truncate(context, output)

        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = true,
        )
        val refused = assertRefused(verdict)
        assertTrue("a missing output is recoverable by regrading", refused.requeueGrade)
    }

    /** Invariant 4 — the dimensions the preset promised. */
    @Test
    fun wrongDimensions_isRefusedAndRequeued() = runTest {
        val output = jpeg(width = 64, height = 48)
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = output),
            expectedWidth = 1920,
            expectedHeight = 1080,
            userApproved = true,
        )
        val refused = assertRefused(verdict)
        assertTrue(refused.requeueGrade)
        assertTrue(refused.reason.contains("64x48"))
    }

    @Test
    fun missingOutputUri_isRefusedAndRequeued() = runTest {
        val verdict = ApprovalGuard.evaluate(
            resolver = context.contentResolver,
            job = ApprovalFixtures.job(assetId = 1L, outputUri = null),
            expectedWidth = 64,
            expectedHeight = 48,
            userApproved = true,
        )
        assertTrue(assertRefused(verdict).requeueGrade)
    }

    /**
     * §9.3 also requires the *control* to be disabled, with the reason shown.
     * Refusing at evaluation time is not enough — offering a button that always
     * fails teaches the user nothing (§6.12).
     */
    @Test
    fun fallbackIsNotOfferedAtAll_andSaysWhy() {
        val job = ApprovalFixtures.job(assetId = 1L, outputUri = Uri.parse("content://x/1"), fellBack = true)
        assertFalse(ApprovalGuard.canOfferOriginalTrashing(job))

        val reason = ApprovalGuard.disabledReason(job)
        assertNotNull("a disabled control must state its reason", reason)
        assertTrue(reason!!.contains("quality gate"))
    }

    private fun assertRefused(verdict: ApprovalGuard.Verdict): ApprovalGuard.Verdict.Refused {
        assertTrue("expected a refusal, got $verdict", verdict is ApprovalGuard.Verdict.Refused)
        return verdict as ApprovalGuard.Verdict.Refused
    }
}
