package com.nuvio.tv.ui.components.trailerpreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioPrimitives
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Spec 123 F5 = corner spinner (D8, sandbox spec-123-trailer-polish v3 `#s123-f5-corner-loader`): a 26 dp disc
// (black 55%) with a 16 dp accent arc on a faint track, turning every 0.9 s, at the card's bottom-right (the muted
// glyph's spot, 10/9 dp in). It shows once the wait ends and the trailer is fetched and buffered, 300 ms late so a
// quick start never shows it; at the first frame it hands over to the muted glyph.
private val SPINNER_DISC = 26.dp
private val SPINNER_ARC = 16.dp
private val SPINNER_END = 10.dp
private val SPINNER_BOTTOM = 9.dp
private val DISC_FILL = NuvioPrimitives.black.copy(alpha = 0.55f)
private val ARC_TRACK = NuvioPrimitives.white.copy(alpha = 0.22f)
private const val ARC_SWEEP = 90f
private const val TURN_MS = 900
private const val SHOW_DELAY_MS = 300L
private const val FADE_IN_MS = 150
private const val FADE_OUT_MS = 200
// The row card widens over this at the first frame (MarqueeHomeCard's PREVIEW_WIDEN_MS): the spinner fades in place.
private const val ROW_HANDOVER_MS = 250
// A grid handover with no Morph to follow fades this long after the first frame.
private const val GRID_HANDOVER_BOUND_MS = 400L

/**
 * The loading spinner (C6), outside the overlay's preview frame: it exists before there is a preview to draw (the
 * lookup) and outlives one that never comes (no trailer, left, cancelled), fading out. One session per key, so a
 * card left while loading fades while the next one's starts.
 */
@Composable
internal fun TrailerPreviewSpinner(handle: TrailerPreviewHandle, currentRoute: String?) {
    if (handle.registeredRoute != currentRoute) return
    val sessions = remember { mutableStateListOf<String>() }
    val liveKey = trailerPreviewLoadingKey(handle.loadingKey.value, handle.state.value)
    LaunchedEffect(liveKey) { if (liveKey != null && liveKey !in sessions) sessions += liveKey }
    // The spinner's own place in the root (anchors are root coordinates).
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val position = it.positionInRoot()
            if (origin.value != position) origin.value = position
        }
    ) {
        for (sessionKey in sessions) {
            key(sessionKey) { SpinnerSession(handle, sessionKey, origin = { origin.value }) { sessions -= sessionKey } }
        }
    }
}

/**
 * The key the spinner shows for: loading (dwell end to first frame), or a preview still invisible past its dwell
 * (LOADING, not paused); a preview buffering hidden during the dwell (Spec 123 C2) shows none.
 */
internal fun trailerPreviewLoadingKey(loadingKey: String?, state: TrailerPreviewState?): String? =
    loadingKey ?: state?.takeIf { it.stage == TrailerPreviewStage.LOADING && !it.paused }?.candidate?.key

@Composable
private fun SpinnerSession(handle: TrailerPreviewHandle, key: String, origin: () -> Offset, onDone: () -> Unit) {
    val state = handle.state.value
    val active = trailerPreviewLoadingKey(handle.loadingKey.value, state) == key
    val handover = state != null && state.candidate.key == key && state.stage != TrailerPreviewStage.LOADING
    val alpha = remember { Animatable(0f) }
    // Read in draw only: the grid Morph to follow, once it exists.
    val probe = { handle.morphProbe.value?.takeIf { it.key == key } }
    fun loading() = handle.loadingAnchor.value?.takeIf { it.key == key }
    val followMorph = remember { mutableStateOf(false) }
    // Composed (and turning) only once the disc can show: most previews start before the 300 ms delay.
    val showing = remember { mutableStateOf(false) }

    LaunchedEffect(active, handover) {
        when {
            active -> {
                if (alpha.value == 0f) delay(SHOW_DELAY_MS)
                showing.value = true
                alpha.animateTo(1f, tween(FADE_IN_MS))
            }
            // The preview closed mid-Morph: the art it was fading with is gone, so it goes too (no 200 ms replay).
            followMorph.value -> onDone()
            alpha.value == 0f -> onDone() // never shown: a quick start, or gone before 300 ms
            handover && loading()?.grid == true -> {
                // Follow the Morph's corner and fade with its time (alpha stays; draw applies the fade); bounded if
                // no Morph comes, or the panel takes over.
                val morph = withTimeoutOrNull(GRID_HANDOVER_BOUND_MS) {
                    snapshotFlow { probe()?.takeIf { it.rectInRoot() != null } }.filterNotNull().first()
                }
                if (morph != null) {
                    followMorph.value = true
                    snapshotFlow {
                        morph.progress() >= MORPH_POSTER_FADE_END ||
                            handle.state.value?.stage == TrailerPreviewStage.PANEL
                    }.first { it }
                } else {
                    alpha.animateTo(0f, tween(FADE_OUT_MS))
                }
                onDone()
            }
            handover -> {
                // Rows: the card widens under it with no corner to follow; it fades where it was.
                alpha.animateTo(0f, tween(ROW_HANDOVER_MS))
                onDone()
            }
            else -> {
                alpha.animateTo(0f, tween(FADE_OUT_MS))
                onDone()
            }
        }
    }

    if (showing.value) SpinnerDisc(handle, key, origin, alpha = { alpha.value }, followMorph = { followMorph.value })
}

@Composable
private fun SpinnerDisc(
    handle: TrailerPreviewHandle,
    key: String,
    origin: () -> Offset,
    alpha: () -> Float,
    followMorph: () -> Boolean
) {
    val turn = rememberInfiniteTransition(label = "trailerPreviewSpinner")
    val rotation = turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "trailerPreviewSpinnerTurn"
    )
    // Its own layer: the per-frame turn re-records only this node, not the host's content.
    Box(Modifier.fillMaxSize().graphicsLayer().drawBehind {
        val anchor = handle.loadingAnchor.value?.takeIf { it.key == key } ?: return@drawBehind
        val morph = handle.morphProbe.value?.takeIf { it.key == key && followMorph() }
        val card: Rect = morph?.rectInRoot() ?: anchor.drawnBoundsInRoot
        val fade = morph?.let { 1f - trailerPreviewRamp(it.progress(), MORPH_POSTER_FADE_START, MORPH_POSTER_FADE_END) } ?: 1f
        val a = alpha() * fade
        if (a <= 0f) return@drawBehind
        val disc = SPINNER_DISC.toPx()
        val center = Offset(
            card.right - SPINNER_END.toPx() - disc / 2,
            card.bottom - SPINNER_BOTTOM.toPx() - disc / 2
        ) - origin()
        drawCircle(DISC_FILL, radius = disc / 2, center = center, alpha = a)
        // The sandbox's 24-unit icon at 16 dp: radius 9, stroke 3.
        val unit = SPINNER_ARC.toPx() / 24f
        val r = 9f * unit
        val stroke = Stroke(width = 3f * unit, cap = StrokeCap.Round)
        drawCircle(ARC_TRACK, radius = r, center = center, alpha = a, style = Stroke(width = 3f * unit))
        drawArc(
            color = anchor.accent,
            startAngle = rotation.value - 90f,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = center - Offset(r, r),
            size = Size(2 * r, 2 * r),
            alpha = a,
            style = stroke
        )
    })
}
