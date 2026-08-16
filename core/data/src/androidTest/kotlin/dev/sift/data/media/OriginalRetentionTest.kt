package dev.sift.data.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.data.db.SiftDatabase
import dev.sift.model.LifecycleState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14.10 — original-retention.
 *
 * The failure mode this guards is permanent photo loss, so the assertions are
 * about what is *absent* from the deletion batch rather than what is present.
 * `buildApprovedOriginalsRequest` is the only path to batch 2, so covering it
 * covers every way an original can be trashed.
 *
 * Nothing here completes a trash request: `createTrashRequest` returns a
 * `PendingIntent` that only the system's confirmation UI can resolve. That is
 * the right boundary — the tests assert which asset ids Sift *puts into* the
 * request, which is the part Sift controls and the part that can be wrong.
 */
@RunWith(AndroidJUnit4::class)
class OriginalRetentionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SiftDatabase
    private lateinit var repo: LifecycleRepository
    private val written = mutableListOf<Uri>()

    @Before
    fun setUp() {
        db = ApprovalFixtures.inMemoryDb(context)
        repo = ApprovalFixtures.repository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        ApprovalFixtures.cleanUp(context, written)
    }

    private fun jpeg(width: Int = 64, height: Int = 48): Uri =
        ApprovalFixtures.writeJpeg(context, width, height).also { written += it }

    /**
     * Trap #14, end to end.
     *
     * Two approved assets, identical in every respect except that one's grade
     * fell back to the original. Only the other may be in the batch.
     */
    @Test
    fun fallbackOriginalIsNeverInTheBatch() = runTest {
        val goodOriginal = jpeg()
        val fallbackOriginal = jpeg()
        val goodOutput = jpeg()
        val fallbackOutput = jpeg()

        db.mediaAssets().insertAll(
            listOf(
                ApprovalFixtures.asset(1L, goodOriginal, 64, 48, LifecycleState.APPROVED),
                ApprovalFixtures.asset(2L, fallbackOriginal, 64, 48, LifecycleState.APPROVED),
            ),
        )
        db.editJobs().upsert(ApprovalFixtures.job(1L, goodOutput, fellBack = false))
        db.editJobs().upsert(ApprovalFixtures.job(2L, fallbackOutput, fellBack = true))

        val batch = repo.buildApprovedOriginalsRequest()

        val ids = batch.request?.assetIds.orEmpty()
        assertTrue("the healthy grade should be eligible", 1L in ids)
        assertFalse("a fallback original must never be offered for trashing", 2L in ids)
        assertTrue(
            "the refusal must be surfaced, not silent (§6.12)",
            batch.refusals.any { it.assetId == 2L },
        )
    }

    /**
     * A refusal that is nobody's fault must not become a silent data loss *or* a
     * silently dropped photo: §12 says put it back in the queue and say so.
     */
    @Test
    fun unverifiableOutputIsRequeuedRatherThanTrashedOrDropped() = runTest {
        val original = jpeg()
        val output = jpeg()
        ApprovalFixtures.truncate(context, output)

        db.mediaAssets().insertAll(
            listOf(ApprovalFixtures.asset(1L, original, 64, 48, LifecycleState.APPROVED)),
        )
        db.editJobs().upsert(ApprovalFixtures.job(1L, output))

        val batch = repo.buildApprovedOriginalsRequest()

        assertNull("nothing is eligible, so there should be no request at all", batch.request)
        assertEquals(1, batch.refusals.size)
        assertEquals(
            "an unverifiable output goes back in the grading queue",
            LifecycleState.QUEUED_FOR_GRADE,
            db.mediaAssets().byId(1L)!!.lifecycleState,
        )
    }

    /**
     * `ORIGINAL_TRASHED` is terminal, which is what makes a resumed batch safe:
     * an asset that already went through cannot go through again.
     */
    @Test
    fun originalTrashedIsTerminal() = runTest {
        assertFalse(
            repo.canTransition(LifecycleState.ORIGINAL_TRASHED, LifecycleState.APPROVED),
        )
        assertFalse(
            repo.canTransition(LifecycleState.ORIGINAL_TRASHED, LifecycleState.PENDING_REVIEW),
        )
        for (state in LifecycleState.entries) {
            assertFalse(
                "ORIGINAL_TRASHED must not lead anywhere, found -> $state",
                repo.canTransition(LifecycleState.ORIGINAL_TRASHED, state) &&
                    state != LifecycleState.ORIGINAL_TRASHED,
            )
        }
    }

    /** The only route into ORIGINAL_TRASHED is from APPROVED. */
    @Test
    fun onlyApprovedAssetsCanBeTrashedAsOriginals() = runTest {
        val reachable = LifecycleState.entries.filter {
            repo.canTransition(it, LifecycleState.ORIGINAL_TRASHED)
        }
        assertEquals(listOf(LifecycleState.APPROVED), reachable)
    }

    /**
     * §14.10's resume case: process death between `APPROVED` and
     * `ORIGINAL_TRASHED`.
     *
     * The asset is simply still approved. It reappears in the next batch, gets
     * re-checked against all five invariants, and — crucially — applying the
     * result twice trashes nothing twice, because the second transition is
     * refused by the terminal state.
     */
    @Test
    fun strandedApprovalResumesWithoutDoubleTrashing() = runTest {
        val original = jpeg()
        val output = jpeg()
        db.mediaAssets().insertAll(
            listOf(ApprovalFixtures.asset(1L, original, 64, 48, LifecycleState.APPROVED)),
        )
        db.editJobs().upsert(ApprovalFixtures.job(1L, output))

        assertEquals(listOf(1L), repo.strandedApprovals())

        val request = requireNotNull(repo.buildApprovedOriginalsRequest().request)
        repo.onApprovedOriginalsResult(request, granted = true)
        assertEquals(
            LifecycleState.ORIGINAL_TRASHED,
            db.mediaAssets().byId(1L)!!.lifecycleState,
        )

        val eventsAfterFirst = db.lifecycleEvents().forAsset(1L).size

        // Replay: the same result arriving again after a restart.
        repo.onApprovedOriginalsResult(request, granted = true)

        assertEquals(
            "a replayed result must not log a second transition",
            eventsAfterFirst,
            db.lifecycleEvents().forAsset(1L).size,
        )
        assertTrue("nothing is stranded once it is trashed", repo.strandedApprovals().isEmpty())
    }

    /**
     * Trap #16 — the two batches are never merged.
     *
     * A library holding both triage rejects and approved keepers must produce
     * two disjoint requests. "Throw away 34 photos you rejected" and "destroy
     * the originals of 34 photos you approved" are different consents.
     */
    @Test
    fun triageRejectsAndApprovedOriginalsNeverShareABatch() = runTest {
        val rejectUri = jpeg()
        val approvedOriginal = jpeg()
        val approvedOutput = jpeg()

        db.mediaAssets().insertAll(
            listOf(
                ApprovalFixtures.asset(1L, rejectUri, 64, 48, LifecycleState.UNTRIAGED),
                ApprovalFixtures.asset(2L, approvedOriginal, 64, 48, LifecycleState.APPROVED),
            ),
        )
        db.editJobs().upsert(ApprovalFixtures.job(2L, approvedOutput))
        repo.recordDecision(1L, dev.sift.model.Verdict.TOSS)

        val triage = requireNotNull(repo.buildTriageTrashRequest())
        val originals = requireNotNull(repo.buildApprovedOriginalsRequest().request)

        assertEquals(TrashCoordinator.Batch.TRIAGE_REJECTS, triage.batch)
        assertEquals(TrashCoordinator.Batch.APPROVED_ORIGINALS, originals.batch)
        assertEquals(listOf(1L), triage.assetIds)
        assertEquals(listOf(2L), originals.assetIds)
        assertTrue(
            "an asset must not appear in both batches",
            triage.assetIds.intersect(originals.assetIds.toSet()).isEmpty(),
        )
        assertNotNull(triage.intent)
        assertNotNull(originals.intent)
    }

    /**
     * A regrade must not inherit the previous grade's approval.
     *
     * Once §12's requeue path works, an asset routinely carries more than one
     * job. `latestForAsset` resolved "latest" by ordering on `processingMs` —
     * the grade's *duration* — so the slowest job won, and `ApprovalGuard` read
     * its `approvedAt` as invariant 5. A slow, approved, superseded grade could
     * therefore authorise trashing an original whose current grade the user has
     * never seen.
     *
     * The old job here is deliberately given the larger `processingMs` and the
     * earlier `createdAt`, which is exactly the shape that used to invert the
     * answer.
     */
    @Test
    fun aRegradeDoesNotInheritThePreviousApproval() = runTest {
        val original = jpeg()
        val staleOutput = jpeg()
        val freshOutput = jpeg()

        db.mediaAssets().insertAll(
            listOf(ApprovalFixtures.asset(1L, original, 64, 48, LifecycleState.APPROVED)),
        )
        // Approved, and slow.
        db.editJobs().upsert(
            ApprovalFixtures.job(
                assetId = 1L,
                outputUri = staleOutput,
                approvedAt = 1_000L,
                createdAt = 1_000L,
                processingMs = 90_000L,
            ),
        )
        // The regrade: newer, quicker, and never approved.
        db.editJobs().upsert(
            ApprovalFixtures.job(
                assetId = 1L,
                outputUri = freshOutput,
                approvedAt = null,
                createdAt = 2_000L,
                processingMs = 500L,
            ),
        )

        assertEquals(
            "latest must mean newest, not slowest",
            freshOutput.toString(),
            db.editJobs().latestForAsset(1L)!!.outputUri,
        )

        val batch = repo.buildApprovedOriginalsRequest()
        assertNull(
            "an unapproved regrade must not be trashable on the strength of an older approval",
            batch.request,
        )
        assertTrue(batch.refusals.any { it.assetId == 1L })
    }

    /**
     * §12's recovery is only recovery if the state machine allows it.
     *
     * `APPROVED` used to lead only to `ORIGINAL_TRASHED`, so `requeueForGrade`
     * returned false and did nothing: an asset with a broken export stayed
     * approved forever, never regraded, re-refused on every batch.
     */
    @Test
    fun approvedCanReturnToTheGradingQueue() = runTest {
        assertTrue(
            repo.canTransition(LifecycleState.APPROVED, LifecycleState.QUEUED_FOR_GRADE),
        )
    }

    /**
     * Approval on its own trashes nothing. It only moves the asset into the one
     * state batch 2 can be built from — the guard runs later, at build time.
     */
    @Test
    fun approvingDoesNotTrashAnything() = runTest {
        val original = jpeg()
        val output = jpeg()
        db.mediaAssets().insertAll(
            listOf(ApprovalFixtures.asset(1L, original, 64, 48, LifecycleState.PENDING_REVIEW)),
        )
        val job = ApprovalFixtures.job(1L, output, approvedAt = null)
        db.editJobs().upsert(job)

        repo.approve(job)

        assertEquals(LifecycleState.APPROVED, db.mediaAssets().byId(1L)!!.lifecycleState)
        assertNotNull(
            "the original file is still there",
            context.contentResolver.openInputStream(original)?.also { it.close() },
        )
    }
}
