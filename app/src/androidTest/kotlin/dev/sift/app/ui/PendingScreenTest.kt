package dev.sift.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.app.ui.pending.PendingScreen
import dev.sift.app.ui.pending.PendingViewModel
import dev.sift.app.ui.theme.SiftTheme
import dev.sift.data.db.MediaAsset
import dev.sift.data.db.SiftDatabase
import dev.sift.data.media.LifecycleRepository
import dev.sift.data.media.TrashCoordinator
import dev.sift.model.ContentClass
import dev.sift.model.LifecycleState
import dev.sift.model.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bin, driven through its real view model.
 *
 * This suite exists because of a specific failure. `PendingScreen`'s Commit
 * button was wired to `popBackStack()` and nothing else for two releases, which
 * made triage deletion unreachable whenever anything was queued — and every one
 * of the 48 unit tests stayed green throughout, because the defect lived in the
 * wiring between a screen and a view model and nothing looked there.
 *
 * So the assertions here are deliberately about wiring and rendering rather than
 * about logic: that the button exists, that pressing it calls what it claims to
 * call, and that its label fits on one line. All three were wrong at once, and
 * none of them are the kind of thing a view model test can see.
 *
 * The view model and repository are the real ones over an in-memory database. A
 * fake would not have caught the original bug — the fake would have been wired
 * correctly.
 */
@RunWith(AndroidJUnit4::class)
class PendingScreenTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var db: SiftDatabase
    private lateinit var lifecycle: LifecycleRepository
    private lateinit var viewModel: PendingViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, SiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lifecycle = LifecycleRepository(context, db, TrashCoordinator(context))
        viewModel = PendingViewModel(lifecycle)
    }

    @After
    fun tearDown() = db.close()

    private fun queueForDeletion(count: Int) = runBlocking {
        db.mediaAssets().insertAll((1..count).map { asset(it.toLong()) })
        for (id in 1..count) lifecycle.recordDecision(id.toLong(), Verdict.TOSS)
    }

    /**
     * The regression test for the dead button.
     *
     * Pressing Commit must reach the callback. It previously reached only the
     * navigation controller, so the trash dialog was never built.
     */
    @Test
    fun pressingDeleteInvokesCommit() {
        queueForDeletion(3)
        var commits = 0
        setContent(onCommit = { commits++ })

        compose.onNodeWithText("Delete 3").assertIsDisplayed().performClick()

        assertEquals("Commit must be reachable from the bin", 1, commits)
    }

    /**
     * The label was inside an `IconButton` — a fixed 48dp circle sized for a
     * 24dp glyph — so "Commit" wrapped to two lines and the tap target did not
     * match what was drawn. A wrapped label carries the same semantics text as
     * an unwrapped one, so the assertion has to reach the layout.
     *
     * It asks the text node for its `TextLayoutResult` and counts lines, rather
     * than comparing a measured height against a constant. The first version did
     * the latter and was wrong in a way worth recording: semantics merge, so
     * `onNodeWithText` resolved to the *button*, not the text inside it, and a
     * Material 3 `TextButton` has a 40dp minimum height. The test was asserting
     * `40dp <= 32dp` and could never have passed however the label rendered.
     * `lineCount` has no such ambiguity — one line is one line at any density,
     * font scale or button spec.
     */
    @Test
    fun theDeleteLabelFitsOnOneLine() {
        queueForDeletion(12)
        setContent()

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Delete 12", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)

        assertTrue("the text node reported no layout at all", layouts.isNotEmpty())
        assertEquals(
            "the label wrapped onto ${layouts.first().lineCount} lines",
            1,
            layouts.first().lineCount,
        )
    }

    /** The count in the label tracks the queue, so it cannot say "Delete 0". */
    @Test
    fun theLabelCountsWhatIsActuallyQueued() {
        queueForDeletion(2)
        setContent()

        compose.onNodeWithText("Delete 2").assertIsDisplayed()
        compose.onNodeWithText("2 to delete").assertIsDisplayed()
    }

    /**
     * Rescuing removes one photo and leaves the rest, which is the whole reason
     * this screen exists — undo-in-order could not express it.
     */
    @Test
    fun rescuingOneLeavesTheOthersQueued() {
        queueForDeletion(3)
        setContent()

        assertEquals(
            "every queued photo needs its own rescue control",
            3,
            compose.countWithContentDescription("Keep this one after all"),
        )
        compose.onNodeWithText("3 to delete").assertIsDisplayed()

        runBlocking { lifecycle.undoDecision(2L) }
        compose.waitForIdle()

        val remaining = runBlocking { db.triageDecisions().uncommittedWith(Verdict.TOSS) }
        assertEquals(listOf(1L, 3L), remaining.map { it.assetId }.sorted())
    }

    /** Nothing queued means no destructive control on screen at all. */
    @Test
    fun anEmptyBinOffersNothingToPress() {
        setContent()

        compose.onNodeWithText("Nothing queued").assertIsDisplayed()
        assertTrue(
            "an empty bin must not offer a delete button",
            compose.countWithText("Delete 0") == 0,
        )
    }

    private fun setContent(onBack: () -> Unit = {}, onCommit: () -> Unit = {}) {
        compose.setContent {
            SiftTheme {
                PendingScreen(onBack = onBack, onCommit = onCommit, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    private fun asset(id: Long) = MediaAsset(
        id = id,
        uri = "content://media/external/images/media/$id",
        dateTaken = 1_600_000_000_000L + id,
        width = 64,
        height = 48,
        sizeBytes = 1_000L,
        mimeType = "image/jpeg",
        dHash = id,
        clusterId = null,
        analysisJson = null,
        contentClass = ContentClass.SCENE,
        lifecycleState = LifecycleState.UNTRIAGED,
        seenAt = null,
    )

}

/** Counting helpers, kept out of the test bodies so the assertions read cleanly. */
private fun ComposeContentTestRule.countWithText(text: String): Int =
    onAllNodes(hasText(text)).fetchSemanticsNodes().size

private fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
