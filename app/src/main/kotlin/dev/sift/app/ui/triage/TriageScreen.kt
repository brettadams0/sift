package dev.sift.app.ui.triage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.sift.app.ui.theme.SiftSpacing
import kotlinx.coroutines.launch
import dev.sift.app.MainActivity
import dev.sift.model.ContentClass
import dev.sift.model.Verdict
import kotlin.math.roundToInt

/**
 * How far a card must travel before the gesture counts as a decision.
 *
 * Unchanged from the value it has always had — named rather than inlined so the
 * badge alpha and the threshold cannot drift apart.
 */
private val SWIPE_THRESHOLD = 120.dp

/** Thumb-sized, so the two most-repeated actions are not 24dp glyphs. */
private val DECK_ACTION_SIZE = 64.dp

/** Spring back, rather than snap back, when a drag ends short of a decision. */
private val SETTLE = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The swipe deck (§8).
 *
 * Right = keep, left = toss, up = skip. Long-press gives 1:1 zoom, because
 * sharpness uncertainty is the most common reason to hesitate and it should be
 * resolvable in one gesture rather than a trip to another screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriageScreen(
    onOpenReview: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPending: () -> Unit,
    viewModel: TriageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // The trash launcher lives in SiftApp, above the NavHost: the bin commits
    // the same batch this screen does, and a second launcher here would fire
    // the same IntentSender twice during the navigation transition.

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // §8 — volume-key bindings. Registered while this screen is on top only.
    val activity = context as? MainActivity
    DisposableEffect(activity) {
        val listener: (Boolean) -> Boolean = { keep ->
            viewModel.decide(if (keep) Verdict.KEEP else Verdict.TOSS)
            true
        }
        activity?.addVolumeKeyListener(listener)
        onDispose { activity?.removeVolumeKeyListener(listener) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.progressLabel) },
                actions = {
                    if (state.canUndo) {
                        IconButton(onClick = viewModel::undo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last decision")
                        }
                    }
                    // Review used to be reachable only from the empty state,
                    // which meant graded photos could pile up unreviewed with no
                    // visible way in — and §9 only works if you actually look at
                    // them before the originals go.
                    if (state.pendingReview > 0) {
                        BadgedBox(
                            badge = { Badge { Text("${state.pendingReview}") } },
                        ) {
                            IconButton(onClick = onOpenReview) {
                                Icon(
                                    Icons.Default.RateReview,
                                    contentDescription = "${state.pendingReview} photos to review",
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isEmpty) {
                EmptyState(
                    libraryTotal = state.total,
                    reviewed = state.reviewed,
                    pendingToss = state.pendingToss,
                    onCommit = onOpenPending,
                    onReview = onOpenReview,
                    onRescan = viewModel::rescanLibrary,
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // The next card sits underneath, so a swipe reveals rather
                    // than flashing an empty frame.
                    state.deck.getOrNull(1)?.let { next ->
                        PhotoCard(uri = next.uri, modifier = Modifier.fillMaxSize())
                    }
                    state.current?.let { current ->
                        SwipeableCard(
                            uri = current.uri,
                            onKeep = { viewModel.decide(Verdict.KEEP) },
                            onToss = { viewModel.decide(Verdict.TOSS) },
                            onSkip = { viewModel.decide(Verdict.SKIP) },
                        )
                    }
                }

                if (state.cluster.size > 1) {
                    ClusterFilmstrip(
                        members = state.cluster,
                        suggestedKeeperId = state.suggestedKeeperId,
                        onPromote = viewModel::promoteInCluster,
                    )
                }

                if (state.current?.contentClass == ContentClass.NON_PHOTOGRAPHIC) {
                    OutlinedButton(
                        onClick = viewModel::tossAllNonPhotographic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Bin all screenshots and documents")
                    }
                }

                // Toss and keep are the two actions taken hundreds of times in
                // a session, so they get real targets rather than bare 24dp
                // glyphs — 64dp tonal circles, thumb-reachable at the screen
                // edges, colour-coded to match the swipe badges. The bin sits
                // between them at lower visual weight: it is pressed once.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckAction(
                        icon = Icons.Default.Close,
                        label = "Toss",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = { viewModel.decide(Verdict.TOSS) },
                    )
                    // Goes to the bin rather than straight to the trash dialog:
                    // the last chance to pull one photo back out is worth more
                    // than one saved tap.
                    OutlinedButton(
                        onClick = { if (state.pendingToss > 0) onOpenPending() else viewModel.commit() },
                    ) {
                        Text(
                            if (state.pendingToss > 0) "Bin ${state.pendingToss}" else "Commit",
                            maxLines = 1,
                        )
                    }
                    DeckAction(
                        icon = Icons.Default.Check,
                        label = "Keep",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { viewModel.decide(Verdict.KEEP) },
                    )
                }
            }
        }
    }
}

/**
 * One of the two decision buttons under the deck.
 *
 * Sized to [DECK_ACTION_SIZE] so the tap target matches the visual target —
 * a bare `IconButton` draws a 24dp glyph inside a 48dp ripple, which reads as
 * a small, tentative control for the action this app exists to perform.
 */
@Composable
private fun DeckAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(DECK_ACTION_SIZE),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

/**
 * The swipe card.
 *
 * Two things here are about frame rate rather than looks, and both were costing
 * a recomposition per drag frame:
 *
 * - **The offset is never read during composition.** It lives in an
 *   [Animatable] and every consumer reads it inside a `graphicsLayer` lambda,
 *   which runs at draw time. Reading a drag offset in the composable body
 *   recomposes the whole card — and the `AsyncImage` inside it — on every
 *   pointer event.
 * - **Both verdict labels are always emitted**, with alpha driven in the same
 *   deferred way. Emitting one conditionally on `abs(offsetX) > 24f` made the
 *   card's content structurally change mid-drag, which is the most expensive
 *   thing a drag can do.
 *
 * Release below the threshold now springs back instead of snapping. Above it,
 * the callback fires immediately as before — the decision is not delayed by an
 * animation, because the deck advancing is what the user is waiting on.
 */
@Composable
private fun SwipeableCard(
    uri: String,
    onKeep: () -> Unit,
    onToss: () -> Unit,
    onSkip: () -> Unit,
) {
    val offsetX = remember(uri) { Animatable(0f) }
    val offsetY = remember(uri) { Animatable(0f) }
    var zoomed by remember(uri) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val scope = rememberCoroutineScope()

    // The new card settles in rather than appearing fully formed where the last
    // one was thrown from.
    val entrance = remember(uri) { Animatable(0.94f) }
    LaunchedEffect(uri) { entrance.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offsetX.value
                translationY = offsetY.value
                rotationZ = (offsetX.value / 40f).coerceIn(-12f, 12f)
                scaleX = entrance.value
                scaleY = entrance.value
            }
            .pointerInput(uri) {
                detectDragGestures(
                    onDragEnd = {
                        val x = offsetX.value
                        val y = offsetY.value
                        when {
                            x > threshold -> onKeep()
                            x < -threshold -> onToss()
                            y < -threshold -> onSkip()
                        }
                        scope.launch { offsetX.animateTo(0f, SETTLE) }
                        scope.launch { offsetY.animateTo(0f, SETTLE) }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, SETTLE) }
                        scope.launch { offsetY.animateTo(0f, SETTLE) }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x) }
                    scope.launch { offsetY.snapTo(offsetY.value + dragAmount.y) }
                }
            }
            .pointerInput(uri) {
                // §8 — long-press for 1:1 zoom. One gesture, no navigation.
                detectTapGestures(
                    onLongPress = { zoomed = true },
                    onPress = {
                        tryAwaitRelease()
                        zoomed = false
                    },
                )
            },
    ) {
        PhotoCard(
            uri = uri,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (zoomed) ContentScale.None else ContentScale.Fit,
        )

        VerdictBadge(
            label = "KEEP",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopStart),
        ) { (offsetX.value / threshold).coerceIn(0f, 1f) }

        VerdictBadge(
            label = "TOSS",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.TopEnd),
        ) { (-offsetX.value / threshold).coerceIn(0f, 1f) }
    }
}

/**
 * A verdict label that reads over any photograph.
 *
 * Bare coloured text was legible over a dark frame and invisible over a bright
 * one. The outlined pill carries its own ground, so the label does not depend on
 * what is behind it.
 *
 * [alpha] is a lambda so the drag offset is sampled at draw time — see
 * [SwipeableCard].
 */
@Composable
private fun VerdictBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: () -> Float,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = modifier
            .padding(SiftSpacing.large)
            .graphicsLayer { this.alpha = alpha() }
            .border(2.dp, color, MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), MaterialTheme.shapes.small)
            .padding(horizontal = SiftSpacing.medium, vertical = SiftSpacing.tight),
    )
}

@Composable
private fun PhotoCard(
    uri: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = modifier) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/**
 * §8 — a cluster shows its suggested keeper large with the rest as a filmstrip;
 * tapping promotes.
 */
@Composable
private fun ClusterFilmstrip(
    members: List<dev.sift.data.db.MediaAsset>,
    suggestedKeeperId: Long?,
    onPromote: (Long) -> Unit,
) {
    Column {
        Text(
            "${members.size} in this burst",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(72.dp),
        ) {
            items(members, key = { it.id }) { member ->
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .pointerInput(member.id) {
                            detectTapGestures { onPromote(member.id) }
                        },
                ) {
                    AsyncImage(
                        model = member.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().aspectRatio(1f),
                    )
                    if (member.id == suggestedKeeperId) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Suggested keeper",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * §8 — the empty state offers the next thing to do rather than a blank screen.
 *
 * It also has to tell the truth about *why* it is empty. "Deck clear" in front of
 * someone who has just installed the app and whose library never scanned is
 * actively misleading: it reads as "nothing to do" when the real state is
 * "nothing was found", and it leaves no next action. The three cases are
 * genuinely different and each gets its own wording and its own button.
 */
@Composable
private fun EmptyState(
    libraryTotal: Int,
    reviewed: Int,
    pendingToss: Int,
    onCommit: () -> Unit,
    onReview: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (libraryTotal == 0) {
            // Nothing in the database at all: the scan has not finished, or it
            // failed, or the permission was granted to a subset that excludes
            // everything.
            Text("No photos yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sift has not found anything in your library. The first scan runs in " +
                    "the background and can take a minute or two on a large roll.\n\n" +
                    "If it stays empty, check that Sift has access to all your photos " +
                    "rather than a selected few, then rescan.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRescan) { Text("Rescan library") }
        } else {
            Text("Deck clear", style = MaterialTheme.typography.headlineSmall)
            Text(
                "You have been through all $libraryTotal photos" +
                    if (reviewed > 0) " ($reviewed reviewed)." else ".",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pendingToss > 0) {
                Button(onClick = onCommit) { Text("Commit $pendingToss deletions") }
            }
            OutlinedButton(onClick = onRescan) { Text("Rescan library") }
        }
        OutlinedButton(onClick = onReview) { Text("Review graded photos") }
    }
}
