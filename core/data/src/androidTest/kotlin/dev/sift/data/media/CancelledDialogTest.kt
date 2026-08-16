package dev.sift.data.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.data.db.SiftDatabase
import dev.sift.model.LifecycleState
import dev.sift.model.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14.8 — cancelling the system trash dialog must lose nothing.
 *
 * The dialog is not ours: the user can dismiss it, the system can dismiss it,
 * and a `RESULT_CANCELED` is indistinguishable from "the phone rang". Any state
 * committed *before* the result comes back is state that gets lost or, worse,
 * state that claims a photo was deleted when it was not.
 *
 * The rule (§8) is that a cancelled dialog leaves the user exactly where they
 * were: every decision still there, still uncommitted, ready to retry without
 * re-triaging a single photo.
 */
@RunWith(AndroidJUnit4::class)
class CancelledDialogTest {

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

    private fun jpeg(): Uri =
        ApprovalFixtures.writeJpeg(context, 64, 48).also { written += it }

    @Test
    fun cancelledTriageBatchLeavesEveryDecisionIntact() = runTest {
        val uris = List(3) { jpeg() }
        db.mediaAssets().insertAll(
            uris.mapIndexed { i, uri -> ApprovalFixtures.asset(i + 1L, uri, 64, 48) },
        )
        for (id in 1L..3L) repo.recordDecision(id, Verdict.TOSS)

        val request = requireNotNull(repo.buildTriageTrashRequest())
        repo.onTriageTrashResult(request, granted = false)

        assertEquals(
            "all three decisions survive a cancel",
            3,
            db.triageDecisions().uncommittedWith(Verdict.TOSS).size,
        )
        for (id in 1L..3L) {
            assertEquals(
                "nothing moves state until the dialog succeeds",
                LifecycleState.UNTRIAGED,
                db.mediaAssets().byId(id)!!.lifecycleState,
            )
            assertFalse(db.triageDecisions().byAsset(id)!!.committed)
        }
    }

    /** And the retry works — the point of keeping them is being able to try again. */
    @Test
    fun retryAfterCancelCommitsTheSameBatch() = runTest {
        val uris = List(2) { jpeg() }
        db.mediaAssets().insertAll(
            uris.mapIndexed { i, uri -> ApprovalFixtures.asset(i + 1L, uri, 64, 48) },
        )
        for (id in 1L..2L) repo.recordDecision(id, Verdict.TOSS)

        repo.onTriageTrashResult(requireNotNull(repo.buildTriageTrashRequest()), granted = false)

        val retry = requireNotNull(repo.buildTriageTrashRequest())
        assertEquals(listOf(1L, 2L), retry.assetIds.sorted())

        repo.onTriageTrashResult(retry, granted = true)
        assertTrue(db.triageDecisions().uncommittedWith(Verdict.TOSS).isEmpty())
        assertEquals(
            LifecycleState.TRASHED_AT_TRIAGE,
            db.mediaAssets().byId(1L)!!.lifecycleState,
        )
    }

    /**
     * The same rule on batch 2, where getting it wrong is unrecoverable: a
     * cancelled dialog must not mark an original trashed.
     */
    @Test
    fun cancelledOriginalsBatchLeavesAssetsApproved() = runTest {
        val original = jpeg()
        val output = jpeg()
        db.mediaAssets().insertAll(
            listOf(ApprovalFixtures.asset(1L, original, 64, 48, LifecycleState.APPROVED)),
        )
        db.editJobs().upsert(ApprovalFixtures.job(1L, output))

        val request = requireNotNull(repo.buildApprovedOriginalsRequest().request)
        repo.onApprovedOriginalsResult(request, granted = false)

        assertEquals(
            "a cancelled batch 2 leaves the asset approved and the original in place",
            LifecycleState.APPROVED,
            db.mediaAssets().byId(1L)!!.lifecycleState,
        )
        assertEquals(listOf(1L), repo.strandedApprovals())
        assertTrue(
            "no job may be marked as having had its original trashed",
            db.editJobs().latestForAsset(1L)!!.originalTrashedAt == null,
        )
    }

    /**
     * Rescuing one photo out of the bin removes exactly that one.
     *
     * Undo-the-last-decision could not express this: the mistake is noticed
     * several swipes later, and reversing it in order would reverse everything
     * after it too.
     */
    @Test
    fun rescuingOnePhotoLeavesTheOtherDecisionsAlone() = runTest {
        val uris = List(3) { jpeg() }
        db.mediaAssets().insertAll(
            uris.mapIndexed { i, uri -> ApprovalFixtures.asset(i + 1L, uri, 64, 48) },
        )
        for (id in 1L..3L) repo.recordDecision(id, Verdict.TOSS)

        assertTrue(repo.undoDecision(2L))

        val remaining = db.triageDecisions().uncommittedWith(Verdict.TOSS).map { it.assetId }
        assertEquals(listOf(1L, 3L), remaining.sorted())
        assertEquals(
            "a rescued photo goes back on the deck",
            null,
            db.mediaAssets().byId(2L)!!.seenAt,
        )
    }

    /** Once a decision is committed the bin is no longer the way back. */
    @Test
    fun committedDecisionsCannotBeRescued() = runTest {
        val uri = jpeg()
        db.mediaAssets().insertAll(listOf(ApprovalFixtures.asset(1L, uri, 64, 48)))
        repo.recordDecision(1L, Verdict.TOSS)
        repo.onTriageTrashResult(requireNotNull(repo.buildTriageTrashRequest()), granted = true)

        assertFalse(
            "after the trash succeeds, recovery is the system trash, not this",
            repo.undoDecision(1L),
        )
    }
}
