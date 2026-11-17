package com.nuvio.tv.ui.components.trailerpreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.screens.detail.DetailKeyHint
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Approved sandbox spec-96-trailer-preview (v3): stage 1 `.s96pop`, grid lift (D10), stage 2 `.s96pan`.
private val ROW_RADIUS = 14.dp
private val GRID_WIDTH = 352.dp
private val GRID_HEIGHT = 198.dp
private val GRID_RADIUS = 16.dp
private val PANEL_WIDTH = 800.dp
private val PANEL_HEIGHT = 450.dp
private val PANEL_RADIUS = 20.dp
// Spec 123 F2 (D1): the panel keeps 450 dp of height up to the window width less this each side, then shrinks.
private val PANEL_SIDE_MARGIN = 48.dp
private const val PANEL_MIN_ASPECT = 4f / 3f
private const val VIDEO_ASPECT = 16f / 9f
// The player is re-measured only for a frame shape this far from its current one.
private const val PLAYER_RESHAPE_TOLERANCE = 0.01f
// Room under the panel for the hints row (the sandbox's 34 dp top on a 540 dp window).
private val PANEL_HINT_SPACE = 22.dp
private val ROW_RING = 2.dp
private val GRID_RING = 3.dp
private val PROGRESS_HEIGHT = 3.dp
private val CARD_SHADOW = 18.dp
private val GRID_SHADOW = 30.dp
private val PANEL_SHADOW = 36.dp
private val CARD_GLYPH = 26.dp
private val PANEL_GLYPH = 32.dp
private val HINTS_BOTTOM = 16.dp
// Label insets (`.s96t` 12, `.s96mu` 10/9, `.s96inf` 28/26, panel `.s96mu` 22/24).
private val CARD_LABEL_INSET = 12.dp
private val CARD_GLYPH_END = 10.dp
private val CARD_GLYPH_BOTTOM = 9.dp
private val PANEL_LABEL_START = 28.dp
private val PANEL_LABEL_BOTTOM = 26.dp
private val PANEL_GLYPH_END = 22.dp
private val PANEL_GLYPH_BOTTOM = 24.dp
private const val GROW_MS = 350
// Spec 123 C2: the video's fade-in from the card's first shown frame (TrailerPlayer's own first-frame fade, moved here:
// the first frame may come while hidden, before the dwell ends).
private const val VIDEO_FADE_IN_MS = 300
// C4: the panel eases to its decided shape.
private const val SHAPE_EASE_MS = 350
// Spec 123 F3 = A, Morph (sandbox spec-123-trailer-polish v3, `#s123-f3-morph-*`): the grid card grows from its cell
// (any shape, the card's radius and ring) to the lifted 352x198 card over 380 ms. The geometry (rect, radius, ring,
// shadow, glow) eases out; the fades run on linear time as the mockup's keyframes do: the card's art covers the
// video and fades out over 50-92% of the time, the title, glyph, gradient and progress fade in over the last 30%.
private const val MORPH_MS = 380
// The loading spinner leaves with the art (TrailerPreviewSpinner).
internal const val MORPH_POSTER_FADE_START = 0.5f
internal const val MORPH_POSTER_FADE_END = 0.92f
private const val MORPH_CHROME_START = 0.7f
// MarqueeHomeCard's CARD_BACKING.
private val MORPH_POSTER_BACKING = NuvioPrimitives.neutral900
private const val SCRIM_ALPHA = 0.62f
private const val CARD_GRADIENT_START = 0.55f
private const val CARD_GRADIENT_ALPHA = 0.72f
// Spec 123 D5 (sandbox spec-123-trailer-polish v2): the panel's text carries its own shadow, no gradient behind it
// (CSS `0 1px 2px rgba(0,0,0,.75)`; one Compose shadow, blur widened to 3 dp for the dropped 10 px halo).
private const val PANEL_TEXT_SHADOW_ALPHA = 0.75f
private val PANEL_TEXT_SHADOW_Y = 1.dp
private val PANEL_TEXT_SHADOW_BLUR = 3.dp
private val PROGRESS_TRACK = NuvioPrimitives.white.copy(alpha = 0.18f)
private val GLYPH_FILL = NuvioPrimitives.black.copy(alpha = 0.45f)

/**
 * The card rect of stage 1 (PD3), in overlay pixels: the row form draws over the widened card itself; the grid
 * form lifts a [gridSize] card centred on the cell, clamped inside the safe area [safe] (D10).
 */
internal fun trailerPreviewCardRect(anchor: Rect, grid: Boolean, window: Size, gridSize: Size, safe: Offset): Rect {
    if (!grid) return anchor
    val left = (anchor.center.x - gridSize.width / 2).coerceIn(safe.x, max(safe.x, window.width - safe.x - gridSize.width))
    val top = (anchor.center.y - gridSize.height / 2).coerceIn(safe.y, max(safe.y, window.height - safe.y - gridSize.height))
    return Rect(left, top, left + gridSize.width, top + gridSize.height)
}

/** Stage 2's panel, centred on the window with the hints row's room under it (PD4). */
internal fun trailerPreviewPanelRect(window: Size, panelSize: Size, hintSpace: Float): Rect {
    val left = (window.width - panelSize.width) / 2
    val top = ((window.height - panelSize.height - hintSpace) / 2).coerceAtLeast(0f)
    return Rect(left, top, left + panelSize.width, top + panelSize.height)
}

/**
 * Stage 2's panel size for the trailer's visible [aspect] (C4, D1): [height] tall, as wide as the aspect asks, up to
 * the [window] width less [margin] each side; past that the width caps and the height shrinks, so the whole trailer
 * stays visible. Aspects under 4:3 are drawn at 4:3.
 */
internal fun trailerPreviewPanelSize(aspect: Float, window: Size, height: Float, margin: Float): Size {
    val a = max(aspect, PANEL_MIN_ASPECT)
    val maxWidth = window.width - 2 * margin
    val width = height * a
    return if (maxWidth <= 0f || width <= maxWidth) Size(width, height) else Size(maxWidth, maxWidth / a)
}

/**
 * The player's measured size (C4): the frame's own [frameAspect] fitted inside [box] (the 800x450 dp panel), so the
 * view's ZOOM crops nothing and every crop is the overlay's cover of the animated rect.
 */
internal fun trailerPreviewPlayerSize(frameAspect: Float, box: Size): Size {
    val fitted = if (frameAspect >= box.width / box.height) Size(box.width, box.width / frameAspect)
    else Size(box.height * frameAspect, box.height)
    // Whole pixels, as the view is measured, so the cover math scales exactly what is drawn.
    return Size(fitted.width.roundToInt().toFloat(), fitted.height.roundToInt().toFloat())
}

/** 0 at [start], 1 at [end], linear and clamped: a fade's share of the Morph's time (C5). */
internal fun trailerPreviewRamp(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** [content] scaled uniformly to cover [rect], centred on it (ContentScale.Crop): the caller's clip crops the rest. */
internal fun trailerPreviewCoverRect(rect: Rect, content: Size): Rect {
    val scale = max(rect.width / content.width, rect.height / content.height)
    val width = content.width * scale
    val height = content.height * scale
    val left = rect.center.x - width / 2
    val top = rect.center.y - height / 2
    return Rect(left, top, left + width, top + height)
}

private val EMPTY_SHAPE = RoundRectAt(Rect.Zero, 0f)

/** A rounded rect at [rect] inside a full-window layer: the clip moves in the draw phase, never the player. */
private class RoundRectAt(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** The one overlay (PD4): composed only while a preview exists, for the route that registered the card. */
@Composable
internal fun TrailerPreviewOverlay(handle: TrailerPreviewHandle, currentRoute: String?) {
    val state = handle.state.value ?: return
    if (handle.registeredRoute != currentRoute) return
    key(state.candidate.key, state.source.videoUrl) {
        PreviewFrame(handle, state)
    }
}

@Composable
private fun PreviewFrame(handle: TrailerPreviewHandle, state: TrailerPreviewState) {
    val machine = handle.machine
    val density = LocalDensity.current
    val panel = state.stage == TrailerPreviewStage.PANEL
    val shown = state.stage != TrailerPreviewStage.LOADING
    val grow = remember { Animatable(0f) }
    LaunchedEffect(panel) { grow.animateTo(if (panel) 1f else 0f, tween(GROW_MS)) }
    val videoAlpha = remember { Animatable(0f) }
    LaunchedEffect(shown) { if (shown) videoAlpha.animateTo(1f, tween(VIDEO_FADE_IN_MS)) }
    // The grid Morph's linear time (C5): 0 = the card's own cell, 1 = lifted. Rows snap to 1 once their anchor exists
    // (their card is the rect already). Geometry reads eased(), the eased value; the fades read the time itself.
    val morph = remember { Animatable(0f) }
    val morphDone by remember { derivedStateOf { morph.value >= 1f } }
    val progress = remember { mutableFloatStateOf(0f) }
    // The overlay's own place in the root (anchors are root coordinates) and the window size; set by layout.
    val origin = remember { mutableStateOf(Offset.Zero) }
    val window = remember { mutableStateOf(Size.Zero) }
    val geometry = remember(density) {
        with(density) {
            PreviewGeometry(
                gridSize = Size(GRID_WIDTH.toPx(), GRID_HEIGHT.toPx()),
                playerBox = Size(PANEL_WIDTH.toPx(), PANEL_HEIGHT.toPx()),
                hintSpace = PANEL_HINT_SPACE.toPx(),
                sideMargin = PANEL_SIDE_MARGIN.toPx(),
                rowRadius = ROW_RADIUS.toPx(),
                gridRadius = GRID_RADIUS.toPx(),
                panelRadius = PANEL_RADIUS.toPx()
            )
        }
    }
    val cellRadius = with(density) { NuvioTheme.marquee.cardRadius.toPx() }
    val cellRing = with(density) { NuvioTheme.marquee.ringWidth.toPx() }
    val safe = with(density) {
        Offset(NuvioTheme.layout.tvSafeHorizontal.toPx(), NuvioTheme.layout.tvSafeVertical.toPx())
    }
    val key = state.candidate.key
    val videoUrl = state.source.videoUrl
    // C4: the trailer's shape. The frame's own aspect (0 until reported; 16:9 meanwhile) sizes the player; the panel
    // takes the decided shape (remembered from an earlier preview, or sampled once, D12), else the frame's own.
    val frameAspect = remember { mutableFloatStateOf(0f) }
    val playerAspect = remember { mutableFloatStateOf(VIDEO_ASPECT) }
    val remembered = remember { machine.rememberedShape(videoUrl) }
    val decidedShape = remember { mutableStateOf(remembered) }
    val sampler = remember {
        if (remembered != null) null
        else TrailerShapeSampler(machine, videoUrl, state.startPositionMs, { frameAspect.floatValue }) { decidedShape.value = it }
    }
    DisposableEffect(sampler) { onDispose { sampler?.release() } }
    LaunchedEffect(Unit) { remembered?.let { machine.onShapeDecided(videoUrl, TrailerShape(it, TrailerShapeReason.REMEMBERED)) } }
    val panelAspectTarget = decidedShape.value ?: frameAspect.floatValue.takeIf { it > 0f } ?: VIDEO_ASPECT
    val panelAspect = remember { Animatable(panelAspectTarget) }
    LaunchedEffect(panelAspectTarget) { panelAspect.animateTo(panelAspectTarget, tween(SHAPE_EASE_MS)) }
    fun panelSize() = trailerPreviewPanelSize(panelAspect.value, window.value, geometry.playerBox.height, geometry.sideMargin)
    val playerSize = trailerPreviewPlayerSize(playerAspect.floatValue, geometry.playerBox)
    // Read only in layout/draw lambdas: the bring-into-view scroll and the growth never recompose this.
    // A grid card's loading bounds are its cell too (C6): they place stage 1 until the playing card pushes its own,
    // so the hidden card never leaves a frame with nothing drawn. Rows wait for their widened card as before.
    fun anchor() = handle.anchor.value?.takeIf { it.key == key }
        ?: handle.loadingAnchor.value?.takeIf { it.key == key && it.grid }
    // The Morph's start (C6): the loading card as it was drawn (at its focus scale), root coordinates, taken once when
    // the Morph starts; null = the cell.
    val morphStart = remember { arrayOfNulls<Rect>(1) }
    fun grid() = anchor()?.grid == true
    fun eased() = EaseOutCubic.transform(morph.value)
    fun rect(): Rect {
        val windowSize = window.value
        val panelRect = trailerPreviewPanelRect(windowSize, panelSize(), geometry.hintSpace)
        val cardRect = anchor()?.let {
            val cell = it.boundsInRoot.translate(-origin.value)
            val lifted = trailerPreviewCardRect(cell, it.grid, windowSize, geometry.gridSize, safe)
            if (it.grid) lerp(morphStart[0]?.translate(-origin.value) ?: cell, lifted, eased()) else lifted
        } ?: panelRect
        return lerp(cardRect, panelRect, grow.value)
    }
    fun radius() = lerp(
        if (grid()) lerp(cellRadius, geometry.gridRadius, eased()) else geometry.rowRadius,
        geometry.panelRadius,
        grow.value
    )
    // The card chrome's share: the Morph's last 30% for the grid, always for rows.
    fun chromeIn() = if (grid()) trailerPreviewRamp(morph.value, MORPH_CHROME_START, 1f) else 1f
    fun posterAlpha() = 1f - trailerPreviewRamp(morph.value, MORPH_POSTER_FADE_START, MORPH_POSTER_FADE_END)
    fun accent() = anchor()?.accent ?: NuvioPrimitives.white
    // Stage 1 waits for the card's bounds: a row for its widened playing card (its first layout after the stage
    // change), a grid card has its loading bounds already (anchor() above). Until then the frame draws nothing
    // rather than a stale or fallback rect. Stage 2 needs none.
    fun drawn() = anchor() != null || grow.value > 0f

    // The Morph starts once the card's bounds exist: the grid form's from the loading card as drawn (else its cell),
    // rows skip it.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        val first = snapshotFlow { anchor() }.filterNotNull().first()
        if (first.grid) {
            morphStart[0] = handle.loadingAnchor.value?.takeIf { it.key == key }?.drawnBoundsInRoot
            morph.animateTo(1f, tween(MORPH_MS, easing = LinearEasing))
        } else {
            morph.snapTo(1f)
        }
    }
    // The spinner follows the grid Morph's corner and fades with its time (C6).
    DisposableEffect(key) {
        val probe = TrailerPreviewMorphProbe(key, { morph.value }) { if (grid()) rect().translate(origin.value) else null }
        handle.morphProbe.value = probe
        onDispose { if (handle.morphProbe.value === probe) handle.morphProbe.value = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val position = it.positionInRoot()
                if (origin.value != position) origin.value = position
                val size = Size(it.size.width.toFloat(), it.size.height.toFloat())
                if (window.value != size) window.value = size
            }
    ) {
        // Stage 2's scrim over everything, the top bar included (PD4).
        Box(Modifier.fillMaxSize().drawBehind {
            // The timing trace's "shown": the first draw with the frame placed (the machine counts the first only).
            if (shown && drawn()) machine.onShown()
            val p = grow.value
            if (p > 0f) drawRect(NuvioPrimitives.black, alpha = SCRIM_ALPHA * p)
        })
        if (shown) {
            // Shadow, then the ambient glow: elevation outlines at the rect; the first draws the black card under the video.
            PreviewShadow(rect = ::rect, radius = ::radius, drawn = ::drawn, elevation = {
                val card = if (grid()) lerp(CARD_SHADOW, GRID_SHADOW, eased()) else CARD_SHADOW
                with(density) { lerp(card, PANEL_SHADOW, grow.value).toPx() }
            }, color = { NuvioPrimitives.black })
            // The grid lift's accent glow (96/D10) grows with the Morph and fades out as the card grows: the panel's
            // shadow is black only (Spec 123 F1); rows never had one.
            PreviewShadow(rect = ::rect, radius = ::radius, drawn = ::drawn, elevation = {
                with(density) { (if (grid()) GRID_SHADOW else 0.dp).toPx() * eased() * (1f - grow.value) }
            }, color = ::accent, fill = false)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Never seen while loading: a grid card's kept loading anchor would place it in the cell, and the
                    // first frame may come while hidden (C2).
                    shape = if (shown && drawn()) RoundRectAt(rect(), radius()) else EMPTY_SHAPE
                    clip = true
                }
        ) {
            TrailerPlayer(
                trailerUrl = state.source.videoUrl,
                trailerAudioUrl = state.source.audioUrl,
                isPlaying = true,
                // C2: prepared paused and hidden until the dwell ends.
                isPaused = state.paused,
                startPaused = true,
                fadeInFirstFrame = false,
                // C7: a preview starts at 2 s buffered; hero and Detail trailers keep 5 s.
                quickStart = true,
                onEnded = machine::onEndedOrError,
                onFirstFrameRendered = machine::onFirstFrame,
                // The card is always muted, the panel always has sound (D11).
                muted = !panel,
                onProgressChanged = { position, duration ->
                    machine.onProgress(position)
                    // Deferred while the panel grows, its first frame included (before the animation starts running).
                    val growing = grow.isRunning || (machine.state.value?.stage == TrailerPreviewStage.PANEL && grow.value < 1f)
                    sampler?.onPosition(position, deferred = !shown || growing)
                    progress.floatValue = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                },
                cropToFill = true,
                startPositionMs = state.startPositionMs,
                onError = machine::onEndedOrError,
                focusable = false,
                onPlayingChanged = { playing -> if (playing) machine.onPlaying() },
                onVideoSizeChanged = { aspect ->
                    frameAspect.floatValue = aspect
                    val current = playerAspect.floatValue
                    if (abs(aspect - current) > current * PLAYER_RESHAPE_TOLERANCE) playerAspect.floatValue = aspect
                },
                onVideoSurface = { sampler?.surface = it },
                // Measured at the frame's shape inside the panel size; the card, the Morph and the growth only scale
                // and move its layer, uniformly, covering the rect about its centre (the clip above crops the rest).
                modifier = Modifier
                    .requiredSize(with(density) { playerSize.width.toDp() }, with(density) { playerSize.height.toDp() })
                    .graphicsLayer {
                        val cover = trailerPreviewCoverRect(rect(), playerSize)
                        val scale = cover.width / playerSize.width
                        transformOrigin = TransformOrigin(0f, 0f)
                        alpha = videoAlpha.value
                        translationX = cover.left
                        translationY = cover.top
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
        val poster = state.candidate.poster
        // Dropped once the Morph ends (the poster is gone by 92%): its painter and full-window layer go with it.
        if (shown && poster != null && !morphDone) {
            MorphPoster(poster, rect = ::rect, radius = ::radius, alpha = { if (grid()) posterAlpha() else 0f })
        }
        if (shown) {
            PreviewChrome(
                drawn = ::drawn,
                rect = ::rect,
                radius = ::radius,
                grow = { grow.value },
                grid = ::grid,
                eased = ::eased,
                cellRing = cellRing,
                chromeIn = ::chromeIn,
                accent = ::accent,
                progress = { progress.floatValue }
            )
            CardLabels(state.candidate.title, rect = ::rect, alpha = { if (drawn()) (1f - grow.value) * chromeIn() else 0f })
            PanelLabels(state.candidate, rect = ::rect, panelWidth = { panelSize().width }, alpha = { grow.value })
            PanelHints(alpha = { grow.value })
        }
    }
}

private class PreviewGeometry(
    val gridSize: Size,
    // The 800x450 dp box the player is fitted into (C4).
    val playerBox: Size,
    val hintSpace: Float,
    val sideMargin: Float,
    val rowRadius: Float,
    val gridRadius: Float,
    val panelRadius: Float
)

@Composable
private fun PreviewShadow(
    rect: () -> Rect,
    radius: () -> Float,
    drawn: () -> Boolean,
    elevation: () -> Float,
    color: () -> Color,
    fill: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundRectAt(rect(), radius())
                shadowElevation = if (drawn()) elevation() else 0f
                ambientShadowColor = color()
                spotShadowColor = color()
            }
            .drawBehind {
                if (!fill || !drawn()) return@drawBehind
                val r = rect()
                drawRoundRect(NuvioPrimitives.black, r.topLeft, r.size, CornerRadius(radius()))
            }
    )
}

/**
 * The card's own art over the video while the grid Morph runs (C5): the card's own image model (a memory-cache hit),
 * drawn centre-cropped into the moving rect as the card's ContentScale.Crop.
 */
@Composable
private fun MorphPoster(poster: Any, rect: () -> Rect, radius: () -> Float, alpha: () -> Float) {
    val painter = rememberAsyncImagePainter(model = poster)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundRectAt(rect(), radius())
                clip = true
            }
            .drawBehind {
                val a = alpha()
                if (a <= 0f) return@drawBehind
                // The card's backing under its art, as the card draws it (transparent logos, art still loading).
                val r = rect()
                drawRect(MORPH_POSTER_BACKING, r.topLeft, r.size, alpha = a)
                val image = painter.intrinsicSize
                if (image.isUnspecified || image.width <= 0f || image.height <= 0f) return@drawBehind
                val cover = trailerPreviewCoverRect(r, image)
                translate(cover.left, cover.top) { with(painter) { draw(cover.size, alpha = a) } }
            }
    )
}

/** Stage 1's gradient, the 3 dp progress and the ring, all in the draw phase. */
@Composable
private fun PreviewChrome(
    drawn: () -> Boolean,
    rect: () -> Rect,
    radius: () -> Float,
    grow: () -> Float,
    grid: () -> Boolean,
    eased: () -> Float,
    cellRing: Float,
    chromeIn: () -> Float,
    accent: () -> Color,
    progress: () -> Float
) {
    val density = LocalDensity.current
    val progressHeight = with(density) { PROGRESS_HEIGHT.toPx() }
    val rowRing = with(density) { ROW_RING.toPx() }
    val gridRing = with(density) { GRID_RING.toPx() }
    // Reused every frame of the widen, scroll and growth (no per-frame Path on the 32-bit Shield).
    val clip = remember { Path() }
    Box(Modifier.fillMaxSize().drawBehind {
        if (!drawn()) return@drawBehind
        val r = rect()
        val corner = CornerRadius(radius())
        val p = grow()
        val c = chromeIn()
        clip.rewind()
        clip.addRoundRect(RoundRect(r, corner))
        clipPath(clip) {
            if (p < 1f && c > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        CARD_GRADIENT_START to Color.Transparent,
                        1f to NuvioPrimitives.black.copy(alpha = CARD_GRADIENT_ALPHA),
                        startY = r.top,
                        endY = r.bottom
                    ),
                    r.topLeft, r.size, alpha = (1f - p) * c
                )
            }
            if (c > 0f) {
                drawRect(PROGRESS_TRACK, Offset(r.left, r.bottom - progressHeight), Size(r.width, progressHeight), alpha = c)
                drawRect(
                    accent(), Offset(r.left, r.bottom - progressHeight), Size(r.width * progress(), progressHeight), alpha = c
                )
            }
        }
        if (p < 1f) {
            // A ring outside the edge, as the sandbox's `box-shadow: 0 0 0 Npx #fff`; the grid's grows with the Morph.
            val ring = if (grid()) lerp(cellRing, gridRing, eased()) else rowRing
            drawRoundRect(
                NuvioPrimitives.white,
                Offset(r.left - ring / 2, r.top - ring / 2),
                Size(r.width + ring, r.height + ring),
                CornerRadius(corner.x + ring / 2),
                style = Stroke(ring),
                alpha = 1f - p
            )
        }
    })
}

/** Places its content against the rect in the placement phase only (the rect moves every frame). */
private fun Modifier.placeAgainst(rect: () -> Rect, place: (Rect, Int, Int) -> IntOffset): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(place(rect(), placeable.width, placeable.height))
        }
    }

/** Stage 1: the title bottom-left and the muted glyph bottom-right (`.s96t`, `.s96mu`). */
@Composable
private fun CardLabels(title: String, rect: () -> Rect, alpha: () -> Float) {
    val density = LocalDensity.current
    val inset = with(density) { CARD_LABEL_INSET.roundToPx() }
    val glyphEnd = with(density) { CARD_GLYPH_END.roundToPx() }
    val glyphBottom = with(density) { CARD_GLYPH_BOTTOM.roundToPx() }
    Text(
        text = title,
        style = NuvioTheme.textStyles.cardLabel,
        color = NuvioPrimitives.white,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .placeAgainst(rect) { r, _, h -> IntOffset(r.left.roundToInt() + inset, r.bottom.roundToInt() - inset - h) }
            .graphicsLayer { this.alpha = alpha() }
            // The narrowest card (row, 320 dp) less the insets and the glyph.
            .widthIn(max = 320.dp - 24.dp - CARD_GLYPH - 10.dp)
    )
    PreviewGlyph(
        muted = true,
        size = CARD_GLYPH,
        modifier = Modifier
            .placeAgainst(rect) { r, w, h -> IntOffset(r.right.roundToInt() - glyphEnd - w, r.bottom.roundToInt() - glyphBottom - h) }
            .graphicsLayer { this.alpha = alpha() }
    )
}

/** Stage 2: title and meta bottom-left, the sound glyph bottom-right (`.s96inf`, `.s96mu`). */
@Composable
private fun PanelLabels(candidate: TrailerPreviewCandidate, rect: () -> Rect, panelWidth: () -> Float, alpha: () -> Float) {
    val density = LocalDensity.current
    val start = with(density) { PANEL_LABEL_START.roundToPx() }
    val bottom = with(density) { PANEL_LABEL_BOTTOM.roundToPx() }
    val glyphEnd = with(density) { PANEL_GLYPH_END.roundToPx() }
    val glyphBottom = with(density) { PANEL_GLYPH_BOTTOM.roundToPx() }
    val textShadow = remember(density) {
        with(density) {
            Shadow(
                color = NuvioPrimitives.black.copy(alpha = PANEL_TEXT_SHADOW_ALPHA),
                offset = Offset(0f, PANEL_TEXT_SHADOW_Y.toPx()),
                blurRadius = PANEL_TEXT_SHADOW_BLUR.toPx()
            )
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .placeAgainst(rect) { r, _, h -> IntOffset(r.left.roundToInt() + start, r.bottom.roundToInt() - bottom - h) }
            .graphicsLayer { this.alpha = alpha() }
            // The panel's own width (C4: it follows the trailer's shape) less the label start, glyph and gap.
            .layout { measurable, constraints ->
                val reserved = (PANEL_LABEL_START + PANEL_GLYPH + 44.dp).toPx()
                val maxWidth = (panelWidth() - reserved).roundToInt().coerceIn(0, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
    ) {
        Text(
            text = candidate.title,
            style = NuvioTheme.textStyles.heroTitleCompact.copy(
                fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp, shadow = textShadow
            ),
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // The house meta line (year · genre · runtime · IMDb), as on the hero; it draws nothing when all are blank.
        MarqueeMetaRow(
            parts = remember(candidate) { trailerPreviewMeta(candidate) },
            imdb = formatRatingScore(candidate.imdbRating),
            shadow = textShadow
        )
    }
    PreviewGlyph(
        muted = false,
        size = PANEL_GLYPH,
        modifier = Modifier
            .placeAgainst(rect) { r, w, h -> IntOffset(r.right.roundToInt() - glyphEnd - w, r.bottom.roundToInt() - glyphBottom - h) }
            .graphicsLayer { this.alpha = alpha() }
    )
}

/** Stage 2's meta parts (`.s96meta`) before the IMDb mark: year, genre, runtime; blanks dropped. */
internal fun trailerPreviewMeta(candidate: TrailerPreviewCandidate): List<String> = listOfNotNull(
    candidate.releaseInfo?.takeIf { it.isNotBlank() },
    candidate.genre?.takeIf { it.isNotBlank() },
    candidate.runtime?.takeIf { it.isNotBlank() }
)

@Composable
private fun PreviewGlyph(muted: Boolean, size: Dp, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).background(GLYPH_FILL, CircleShape)
    ) {
        Icon(
            imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = NuvioPrimitives.white,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

/** Stage 2's keys row, centred at the window's bottom (`.s96hints`). */
@Composable
private fun PanelHints(alpha: () -> Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
            // The layer is the row's own size, not the window's.
            modifier = Modifier.graphicsLayer { this.alpha = alpha() }.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val bottom = HINTS_BOTTOM.roundToPx()
                layout(placeable.width, placeable.height + bottom) { placeable.place(0, 0) }
            }
        ) {
            DetailKeyHint(key = stringResource(R.string.trailer_preview_key_ok), label = stringResource(R.string.trailer_preview_hint_details))
            DetailKeyHint(key = stringResource(R.string.detail_key_back), label = stringResource(R.string.detail_hint_close))
            DetailKeyHint(key = stringResource(R.string.trailer_preview_key_arrows), label = stringResource(R.string.trailer_preview_hint_close_move))
        }
    }
}
