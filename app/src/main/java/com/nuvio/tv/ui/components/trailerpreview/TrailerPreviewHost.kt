package com.nuvio.tv.ui.components.trailerpreview

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The app-level preview a card registers with (Spec 96 PD2); null = no host (nothing previews). */
val LocalTrailerPreview = compositionLocalOf<TrailerPreviewHandle?> { null }

/** A screen opts its cards in by providing true around its content (Spec 96 PD2). */
val LocalTrailerPreviewEnabled = compositionLocalOf { false }

/**
 * Where the playing card is drawn, in root coordinates (PD3): the row form's widened 320x180 card, or the grid
 * form's own cell (the overlay lifts it, D10). [accent] is the card's ambient accent (ring glow, progress). The
 * bounds are the card's layout bounds; it draws at [scale] about their centre (Spec 123 C6: the focused card's
 * 1.06 while it loads, 1 while fast-scrolling or playing).
 */
data class TrailerPreviewAnchor(
    val key: String,
    val boundsInRoot: Rect,
    val grid: Boolean,
    val accent: Color,
    val scale: Float = 1f
) {
    /** The bounds as the card draws them, [scale] applied about the centre. */
    val drawnBoundsInRoot: Rect
        get() {
            if (scale == 1f) return boundsInRoot
            val c = boundsInRoot.center
            val halfW = boundsInRoot.width * scale / 2
            val halfH = boundsInRoot.height * scale / 2
            return Rect(c.x - halfW, c.y - halfH, c.x + halfW, c.y + halfH)
        }
}

/** The grid Morph as the spinner follows it (C6): its linear time and its current rect in root coordinates. */
internal class TrailerPreviewMorphProbe(val key: String, val progress: () -> Float, val rectInRoot: () -> Rect?)

/**
 * What cards and the overlay share. Registration is a plain call (no snapshot write); only the playing card
 * writes [anchor] and only the loading card [loadingAnchor] (C6); only cards that are focused read [state] and
 * [loadingKey] (PD3). The overlay's preview frame publishes [morphProbe] for the spinner.
 */
@Stable
class TrailerPreviewHandle internal constructor(
    internal val machine: TrailerPreviewMachine,
    /** In the card is the chosen mode (D7); a focused card reads it, so a late or changed setting re-registers. */
    val cardMode: State<Boolean>,
    private val currentRoute: () -> String?,
    val state: State<TrailerPreviewState?>,
    /** The key being fetched and buffered (C6); only a focused card reads it. */
    val loadingKey: State<String?>
) {
    internal val anchor = mutableStateOf<TrailerPreviewAnchor?>(null)
    // C6: the loading card's bounds (the spinner's corner, the grid Morph's start). Replaced by each loading push and
    // kept past the first frame, when the Morph starts from it; readers match the key. Cleared with the host.
    internal val loadingAnchor = mutableStateOf<TrailerPreviewAnchor?>(null)
    internal val morphProbe = mutableStateOf<TrailerPreviewMorphProbe?>(null)

    // The route that registered the focused card: the overlay composes only there (PD6).
    internal var registeredRoute: String? = null
        private set
    private var openKey: String? = null
    private var open: () -> Unit = {}

    /** The card with [candidate] took focus; [onOpen] is its click (Details), reused by stage 2's OK. 0 = ignored. */
    fun register(candidate: TrailerPreviewCandidate, onOpen: () -> Unit): Long {
        if (!cardMode.value) return 0L
        registeredRoute = currentRoute()
        openKey = candidate.key
        open = onOpen
        return machine.focus(candidate)
    }

    /** The registration with [token] lost focus or left composition. */
    fun unregister(token: Long) {
        if (token != 0L) machine.leave(token)
    }

    fun pushAnchor(anchor: TrailerPreviewAnchor) {
        if (this.anchor.value != anchor) this.anchor.value = anchor
    }

    fun pushLoadingAnchor(anchor: TrailerPreviewAnchor) {
        if (loadingAnchor.value != anchor) loadingAnchor.value = anchor
    }

    internal fun openDetails(candidate: TrailerPreviewCandidate) {
        if (candidate.key == openKey) open()
    }
}

/**
 * Spec 96's host around the app chrome (PD2): owns the handle, decides keys before the content sees them (PD6),
 * leaves the card on a route change, cancels on lifecycle pause and resumes after, resets on dispose, and draws
 * the overlay after [content], so it covers the top bar.
 */
@Composable
internal fun TrailerPreviewHost(currentRoute: String?, content: @Composable () -> Unit) {
    val viewModel: TrailerPreviewViewModel = hiltViewModel()
    val machine = viewModel.machine
    val state = machine.state.collectAsState()
    val cardMode = viewModel.cardModeEnabled.collectAsState()
    val loadingKey = machine.loadingKey.collectAsState()
    val latestRoute by rememberUpdatedState(currentRoute)
    val handle = remember(machine) { TrailerPreviewHandle(machine, cardMode, { latestRoute }, state, loadingKey) }
    val gate = remember { TrailerPreviewKeyGate() }

    val previousRoute = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(currentRoute) {
        if (isTrailerPreviewRouteLeave(previousRoute[0], currentRoute, handle.registeredRoute)) machine.leaveCurrent()
        previousRoute[0] = currentRoute
    }
    // A finished preview's bounds never place the next one (a replay of the same card starts from its own).
    LaunchedEffect(machine) {
        snapshotFlow { state.value == null }.collect { idle -> if (idle) handle.anchor.value = null }
    }
    // A dialog is its own window: its keys never reach this host, so the preview stops while it has focus.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, machine) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused) machine.resume(TrailerPreviewCancelCause.WINDOW_FOCUS)
            else machine.cancel(TrailerPreviewCancelCause.WINDOW_FOCUS)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, machine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> machine.cancel(TrailerPreviewCancelCause.LIFECYCLE)
                Lifecycle.Event.ON_RESUME -> machine.resume(TrailerPreviewCancelCause.LIFECYCLE)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(machine) {
        onDispose {
            machine.reset()
            handle.anchor.value = null
            handle.loadingAnchor.value = null
        }
    }

    CompositionLocalProvider(LocalTrailerPreview provides handle) {
        Box(
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                val result = onTrailerPreviewKey(native.action, native.keyCode, native.repeatCount, handle.machine, gate)
                result.open?.let(handle::openDetails)
                result.consumed
            }
        ) {
            content()
            TrailerPreviewOverlay(handle = handle, currentRoute = currentRoute)
            // After the overlay: the spinner hands over on top of the growing card (C6).
            TrailerPreviewSpinner(handle = handle, currentRoute = currentRoute)
        }
    }
}

/** One key's outcome: [consumed] stops it here; [open] is the card whose Details stage 2's OK opens. */
internal data class TrailerPreviewKeyResult(val consumed: Boolean, val open: TrailerPreviewCandidate? = null)

private val PASSED = TrailerPreviewKeyResult(consumed = false)
private val CONSUMED = TrailerPreviewKeyResult(consumed = true)

/** PD6: the gate pairs every press the preview acted on with its repeats and KeyUp. */
internal fun onTrailerPreviewKey(
    action: Int,
    code: Int,
    repeatCount: Int,
    machine: TrailerPreviewMachine,
    gate: TrailerPreviewKeyGate
): TrailerPreviewKeyResult = when (action) {
    AndroidKeyEvent.ACTION_DOWN -> when {
        gate.onKeyDown(code, repeatCount) -> CONSUMED
        else -> when (machine.onKeyDown(trailerPreviewKey(code))) {
            TrailerPreviewKeyDecision.PASS, TrailerPreviewKeyDecision.CLOSE_AND_PASS -> PASSED
            TrailerPreviewKeyDecision.CLOSE_AND_CONSUME -> {
                gate.hold(code, TrailerPreviewKeyGate.Held.CONSUMED)
                CONSUMED
            }
            TrailerPreviewKeyDecision.CONSUME_OPEN_ON_UP -> {
                gate.hold(code, TrailerPreviewKeyGate.Held.OPEN_ON_UP)
                CONSUMED
            }
        }
    }
    AndroidKeyEvent.ACTION_UP -> when (gate.onKeyUp(code)) {
        TrailerPreviewKeyGate.Held.CONSUMED -> CONSUMED
        TrailerPreviewKeyGate.Held.OPEN_ON_UP -> TrailerPreviewKeyResult(consumed = true, open = machine.openDetailsOnKeyUp())
        null -> PASSED
    }
    else -> PASSED
}

/**
 * A route change leaves the focused card (PD6). The first null -> route at startup is not one (plan round 4), and a
 * card the incoming screen registered before this ran ([registeredRoute] == [current]) stays.
 */
internal fun isTrailerPreviewRouteLeave(previous: String?, current: String?, registeredRoute: String?): Boolean =
    previous != null && previous != current && registeredRoute != current

/** Android key codes to the keys the preview tells apart (PD6). */
internal fun trailerPreviewKey(keyCode: Int): TrailerPreviewKey = when (keyCode) {
    AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> TrailerPreviewKey.BACK
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ->
        TrailerPreviewKey.OK
    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TrailerPreviewKey.DPAD
    AndroidKeyEvent.KEYCODE_VOLUME_UP, AndroidKeyEvent.KEYCODE_VOLUME_DOWN, AndroidKeyEvent.KEYCODE_VOLUME_MUTE ->
        TrailerPreviewKey.VOLUME
    else -> TrailerPreviewKey.OTHER
}
