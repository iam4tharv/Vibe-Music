package com.music.vibe.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.vibe.data.model.PLAYER_ART_PX
import kotlin.math.roundToInt

/**
 * Comfortably over the sleeve's drawn size on a phone, without wasting bytes.
 *
 * A rung on the app-wide ladder rather than a number of the player's own, so a
 * large home-screen widget asks for the same copy — see [PLAYER_ART_PX].
 */
internal const val ART_PX = PLAYER_ART_PX

/** How many further goes a cover that failed to load gets. */
internal const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
internal const val ART_RETRY_DELAY_MS = 1_500L

/**
 * How long a canvas lookup waits for the track's album name before giving up
 * on it.
 */
internal const val ALBUM_SETTLE_MS = 700L

/** Long enough for the post-upgrade rollback cue to be noticed without lingering. */
internal const val REVERT_CUE_MS = 2_600L

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/** How long that handle is held at the drop point regardless. */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

internal val THUMB_SIZE = 54.dp
internal val HEADER_HEIGHT = 60.dp
internal val ART_TITLE_GAP = 20.dp
internal const val QUEUE_TRAVEL_MS = 420
internal const val QUEUE_CARRY_FRACTION = 0.3f
internal const val QUEUE_FLICK_VELOCITY = 450f
internal val DISMISS_STRIP_HEIGHT = 32.dp
internal val ART_BOX_TOP_PAD = 8.dp
internal val VERSION_PILL_ART_INSET = 12.dp
internal const val HERO_FADE_FRACTION = 0.42f
internal const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f
internal const val MESH_REFRESH_MS = 3_000L

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp
internal val PLAYER_MAX_WIDTH = 560.dp
internal val TABLET_PLAYER_MIN_WIDTH = 700.dp
internal val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

internal const val ARTWORK_EXPANDED_SCALE = 1f
internal const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
internal const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f
internal val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
internal const val ARTWORK_SCALE_DURATION_MS = 500

internal val CONTROL_GAP_SPREAD_MAX = 24.dp
internal var lastControlSpread: Dp = 0.dp
internal var lastHeroSettled = false

internal const val DOCK_FADE_LEAD = 0.15f
internal const val DOCK_FADE_FLOOR = 1f / 255f

/**
 * What one frame of docking into the mini player worked out — see [PlayerDock].
 */
internal class DockFrame {
    var anchor: Offset? by mutableStateOf(null)
    var anchorFor = IntSize.Zero
    var miniInBox: Rect? = null
    var offset = 0f
    var ride = 0f
    var sleeve: Rect = Rect.Zero
    private var cornerRadius = Dp.Unspecified
    private var corner: Shape = RectangleShape
    fun cornerShape(radius: Dp): Shape {
        if (radius != cornerRadius) {
            cornerRadius = radius
            corner = RoundedCornerShape(radius)
        }
        return corner
    }
    var portalLeft = 0f
    var portalTop = 0f
    var portalScale = 1f
    var host: LayoutCoordinates? = null
    var landscapeArt: LayoutCoordinates? = null
}

/**
 * One frame of the landscape sleeve's trip to the mini player: where it is
 * drawn in the player, and how small.
 */
internal class LandscapeDockPose(val left: Float, val top: Float, val scale: Float)

/** The placeholder tile's corners — one shape, not a new one every frame it moves. */
internal val TileShape = RoundedCornerShape(8.dp)

@Composable
internal fun WhileShown(shown: () -> Boolean, content: @Composable () -> Unit) {
    if (shown()) content()
}

fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

internal fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

internal fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= 600.dp

fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    (windowWidth >= 840.dp) || (windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH)

internal const val LYRICS_CONTROLS_IDLE_MS = 5_000L
internal const val SPOTIFY_CANVAS_CONTROLS_IDLE_MS = 5_000L
internal const val SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS = 420
internal const val PLAYER_DECK_FADE_IN_MS = 700
internal const val SPOTIFY_DECK_TOP_FADE_FRACTION = 0.28f

internal fun playerDeckSlideIn() = slideInVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    initialOffsetY = { it },
)

internal fun playerDeckSlideOut() = slideOutVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    targetOffsetY = { it },
)

@Composable
internal fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    fadeIn: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (fadeIn() && reveal.value < 1f) {
                alpha.snapTo(0f)
                reveal.snapTo(1f)
                mounted = true
                alpha.animateTo(
                    1f,
                    tween(PLAYER_DECK_FADE_IN_MS, easing = LinearEasing),
                )
            } else {
                alpha.snapTo(1f)
                mounted = true
                reveal.animateTo(
                    1f,
                    tween(
                        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.animateTo(
                0f,
                tween(
                    SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha.value }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val animatedHeight = (placeable.height * reveal.value)
                        .roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(placeable.width, animatedHeight) {
                        placeable.placeRelative(0, 0)
                    }
                },
        ) {
            content()
        }
    }
}

internal suspend fun AwaitPointerEventScope.dragQueueIn(
    down: PointerInputChange,
    travel: Float,
    slide: MutableFloatState,
    onHold: (Boolean) -> Unit,
    onSettle: (Boolean) -> Unit,
) {
    if (travel < 1f) return

    var pulled = 0f
    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (overSlop < 0f) {
            pulled = -overSlop
            change.consume()
        }
    }
    if (drag == null || pulled <= 0f) return

    onHold(true)
    val velocity = VelocityTracker()
    velocity.addPointerInputChange(drag)
    slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
    verticalDrag(drag.id) { change ->
        velocity.addPointerInputChange(change)
        pulled -= change.positionChange().y
        slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
        change.consume()
    }

    val flick = -velocity.calculateVelocity().y
    val open = when {
        flick >= QUEUE_FLICK_VELOCITY -> true
        flick <= -QUEUE_FLICK_VELOCITY -> false
        else -> slide.floatValue >= QUEUE_CARRY_FRACTION
    }
    onHold(false)
    onSettle(open)
}
