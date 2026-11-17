package com.nuvio.tv.ui.components.trailerpreview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A focused card that may preview its trailer (Spec 96 PD3). [key] is the card's focus key; [genre], [runtime]
 * and [imdbRating] only fill stage 2's meta row; [poster] is the card's own art, which the grid Morph grows from.
 */
data class TrailerPreviewCandidate(
    val key: String,
    val itemId: String,
    val title: String,
    val releaseInfo: String?,
    val apiType: String,
    val fallbackYtId: String?,
    val genre: String? = null,
    val runtime: String? = null,
    val imdbRating: Float? = null,
    /**
     * The grid card's own image model (its Coil request, Spec 123 C5), opaque here: the overlay redraws the card's
     * art from the same request, so it is a memory-cache hit. Null for rows and cards without art.
     */
    val poster: Any? = null
)

data class TrailerPreviewSource(val videoUrl: String, val audioUrl: String?)

/** LOADING: the player is composed invisibly until its first frame; CARD = stage 1; PANEL = stage 2. */
enum class TrailerPreviewStage { LOADING, CARD, PANEL }

/**
 * What the overlay draws; null = nothing (waiting, resolving, idle). [paused]: a LOADING preview buffering hidden
 * before its dwell ends (Spec 123 C2); the player holds its first frame until then.
 */
data class TrailerPreviewState(
    val candidate: TrailerPreviewCandidate,
    val source: TrailerPreviewSource,
    val startPositionMs: Long,
    val stage: TrailerPreviewStage,
    val paused: Boolean = false
)

/** The phase a preview left before its first frame (Spec 123 C1). */
enum class TrailerPreviewAbortPhase { RESOLVING, LOADING }

/**
 * Spec 123 C1 timing trace (F4 step 1): each event carries ms since the arm, a focus or a [TrailerPreviewMachine.resume]
 * re-arm. Leaving before the settle (C2) traces nothing after [Armed].
 */
sealed interface TrailerPreviewEvent {
    val sinceArmMs: Long

    data class Armed(val itemId: String, val rearm: Boolean) : TrailerPreviewEvent {
        override val sinceArmMs: Long get() = 0L
    }
    /** The lookup starts at the settle, before the dwell ends (C2). */
    data class ResolveStarted(override val sinceArmMs: Long) : TrailerPreviewEvent
    data class Resolved(override val sinceArmMs: Long, val found: Boolean, val durationMs: Long) : TrailerPreviewEvent
    /** The source is set: the player prepares (LOADING). */
    data class Prepared(override val sinceArmMs: Long) : TrailerPreviewEvent
    /** The dwell ended (C2): the preview may show from here. */
    data class DwellEnded(override val sinceArmMs: Long) : TrailerPreviewEvent
    /**
     * The first frame: stage 1 (CARD) at once, or [hidden] before the dwell ended (C2), when CARD follows at the
     * dwell's end, marked by [Shown].
     */
    data class FirstFrame(override val sinceArmMs: Long, val hidden: Boolean = false) : TrailerPreviewEvent
    /** The player reports playing (each time, rebuffers included). */
    data class Playing(override val sinceArmMs: Long) : TrailerPreviewEvent
    /** The overlay drew its first frame of this preview. */
    data class Shown(override val sinceArmMs: Long) : TrailerPreviewEvent
    data class Panel(override val sinceArmMs: Long) : TrailerPreviewEvent
    /** Left, cancelled, failed or timed out before shown; [hidden] = before the dwell ended (C2). */
    data class Aborted(override val sinceArmMs: Long, val phase: TrailerPreviewAbortPhase, val hidden: Boolean = false) :
        TrailerPreviewEvent
    /** C4: one bar-detection sample: its frame copy and copy + scan in ms, its visible aspect (null = invalid). */
    data class ShapeSample(override val sinceArmMs: Long, val copyMs: Float, val totalMs: Float, val aspect: Float?) :
        TrailerPreviewEvent
    data class ShapeDecided(override val sinceArmMs: Long, val shape: TrailerShape) : TrailerPreviewEvent
}

/** What stops the preview while focus stays (PD6); each [TrailerPreviewMachine.cancel] ends with its own resume. */
enum class TrailerPreviewCancelCause { LIFECYCLE, SCREENSAVER, WINDOW_FOCUS }

/** The keys the preview treats differently (PD6); everything else is [OTHER]. VOLUME = up, down, mute. */
enum class TrailerPreviewKey { BACK, OK, DPAD, VOLUME, OTHER }

enum class TrailerPreviewKeyDecision {
    /** Let the key through untouched. */
    PASS,
    /** The preview closed; let the key through (it moves focus / does its usual job). */
    CLOSE_AND_PASS,
    /** The preview closed; the key is spent. */
    CLOSE_AND_CONSUME,
    /** Stage 2 OK: spent now; Details opens on its KeyUp ([TrailerPreviewMachine.openDetailsOnKeyUp]). */
    CONSUME_OPEN_ON_UP
}

/**
 * Spec 96 stages, timers and resume memory (PD5, PD6), with no Android types so it runs under virtual time.
 * All calls come from the main thread. Only what the overlay draws is observable ([state]); waiting and
 * resolving are plain fields, so D-pad travel writes no state.
 *
 * "Done until focus leaves" (D9) needs no mark: a card is armed only by a new key's [focus], or by [resume]
 * after a [cancel] of its wait, and every done cause ends both. Between [cancel] and [resume] nothing arms: a
 * card focused meanwhile waits for [resume].
 *
 * [delayMs] is the preview delay setting (D12), read each time it starts: focus -> shown (the dwell), stage 1 ->
 * stage 2. Spec 123 C2: the lookup starts at the settle ([trailerPreviewSettleMs]) and the player prepares paused and
 * hidden until the dwell ends. [trace] receives the Spec 123 timing events; it must not call back into the machine.
 */
class TrailerPreviewMachine(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val delayMs: () -> Long,
    private val trace: (TrailerPreviewEvent) -> Unit = {},
    private val resolve: suspend (TrailerPreviewCandidate) -> TrailerPreviewSource?
) {
    private class Resume(val itemId: String, val videoUrl: String, val positionMs: Long, val atMs: Long)

    // Trace only (C1): where the current preview is, for the abort and shown events.
    private enum class TracePhase { IDLE, DWELL, RESOLVING, LOADING, HIDDEN_FRAME, FIRST_FRAME, SHOWN }
    private var tracePhase = TracePhase.IDLE
    private var armedAtMs = 0L
    // C2: the current arm's dwell has ended; a first frame arrived while it had not. Reset on every arm and stop.
    private var dwellElapsed = false
    private var frameSeen = false

    private val _state = MutableStateFlow<TrailerPreviewState?>(null)
    val state: StateFlow<TrailerPreviewState?> = _state.asStateFlow()

    // Spec 123 C6 (D8): the key whose trailer is being fetched and buffered, from the dwell's end to the first frame
    // (the corner spinner). One write each way per preview; D-pad travel writes nothing.
    private val _loadingKey = MutableStateFlow<String?>(null)
    val loadingKey: StateFlow<String?> = _loadingKey.asStateFlow()

    private var focused: TrailerPreviewCandidate? = null
    // The token of the focused card's latest registration; 0 = none.
    private var focusedToken: Long = 0L
    private var lastToken: Long = 0L
    // A cancel stopped the focused card's wait: [resume] starts a fresh one.
    private var rearmOnResume = false
    // The causes between their [cancel] and [resume] (paused, screensaver, a dialog's window): while any is
    // active nothing is fetched or timed.
    private val causes = HashSet<TrailerPreviewCancelCause>()
    // The last left title (D1: only one is kept).
    private var resume: Resume? = null
    // The resume record captured when the focused card was entered within the window.
    private var resumeForFocused: Resume? = null
    private var positionMs: Long = 0L
    // Stage 2's OK is held: its KeyUp opens this card's Details, even if the trailer ended meanwhile.
    private var pendingOpen: TrailerPreviewCandidate? = null
    private var job: Job? = null
    // C4 (D12): the shape each recent video decided by sampling, so a return opens at it with no sampling.
    private val shapes = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > SHAPE_MEMORY
    }

    /**
     * A card took focus. Returns the token [leave] takes. A repeat registration of the focused card's key (a
     * recomposition, or the same card in an incoming screen) changes nothing but the token: the latest wins,
     * so the earlier registration's [leave] becomes a no-op.
     */
    fun focus(candidate: TrailerPreviewCandidate): Long {
        val token = ++lastToken
        if (candidate.key != focused?.key) {
            leaveFocused()
            focused = candidate
            resumeForFocused = resume?.takeIf { it.itemId == candidate.itemId && now() - it.atMs <= RESUME_WINDOW_MS }
            if (causes.isNotEmpty()) rearmOnResume = true else arm(candidate, rearm = false)
        }
        focusedToken = token
        return token
    }

    /** The registration with [token] lost focus or left composition; a stale token is a no-op. */
    fun leave(token: Long) {
        if (token != focusedToken) return
        leaveFocused()
    }

    /** A route change (PD6): the focused card leaves at once, a stop point like any leave (PD5). */
    fun leaveCurrent() = leaveFocused()

    /** The host left composition: forget the card and the record (PD2). Tokens keep counting, so old ones stay stale. */
    fun reset() {
        stop()
        focused = null
        focusedToken = 0L
        rearmOnResume = false
        // causes stay: they mirror live sources (the screensaver's state outlives the host and will not re-emit).
        resume = null
        resumeForFocused = null
        positionMs = 0L
        pendingOpen = null
        shapes.clear()
    }

    /**
     * Lifecycle pause, screensaver, another window (a dialog) taking focus: the trailer must stop while focus stays.
     * A shown card or panel closes and is done; a card still waiting (dwell, resolve, or the invisible LOADING, hidden
     * buffering included) stops, and the last active cause's [resume] starts a fresh dwell (PD5), so nothing is
     * fetched or timed while the app is in the background.
     */
    fun cancel(cause: TrailerPreviewCancelCause) {
        causes += cause
        val candidate = focused ?: return
        pendingOpen = null
        when (_state.value?.stage) {
            TrailerPreviewStage.CARD, TrailerPreviewStage.PANEL -> closeDone(candidate)
            TrailerPreviewStage.LOADING -> stopWaiting()
            null -> if (job?.isActive == true) stopWaiting()
        }
    }

    /** [cause] ended (lifecycle resume, screensaver gone, window focus back); once none is left, a stopped wait starts afresh. */
    fun resume(cause: TrailerPreviewCancelCause) {
        causes -= cause
        if (causes.isNotEmpty() || !rearmOnResume) return
        rearmOnResume = false
        focused?.let { arm(it, rearm = true) }
    }

    /**
     * The player's first frame. Before the dwell ends (C2) it is only noted: the arm job shows the card at the
     * dwell's end. After, the card shows now, from outside the arm job (which waits in its timeout).
     */
    fun onFirstFrame() {
        val current = _state.value ?: return
        if (current.stage != TrailerPreviewStage.LOADING) return
        if (!dwellElapsed) {
            if (frameSeen) return
            frameSeen = true
            tracePhase = TracePhase.HIDDEN_FRAME
            trace(TrailerPreviewEvent.FirstFrame(sinceArm(), hidden = true))
            return
        }
        enterCard(current)
        trace(TrailerPreviewEvent.FirstFrame(sinceArm()))
        job?.cancel()
        job = scope.launch { panelAfterDelay() }
    }

    /** The player started playing (trace only). */
    fun onPlaying() {
        if (_state.value != null) trace(TrailerPreviewEvent.Playing(sinceArm()))
    }

    /** The shape [videoUrl] decided on an earlier preview, if remembered (C4). */
    fun rememberedShape(videoUrl: String): Float? = shapes[videoUrl]

    /** A bar-detection sample (trace only). */
    fun onShapeSample(copyMs: Float, totalMs: Float, aspect: Float?) {
        trace(TrailerPreviewEvent.ShapeSample(sinceArm(), copyMs, totalMs, aspect))
    }

    /** [videoUrl]'s panel took [shape]: remembered only when sampling agreed (never the fallback). */
    fun onShapeDecided(videoUrl: String, shape: TrailerShape) {
        if (shape.reason == TrailerShapeReason.AGREED) shapes[videoUrl] = shape.aspect
        trace(TrailerPreviewEvent.ShapeDecided(sinceArm(), shape))
    }

    /** The overlay drew a shown preview (trace only; the first call per preview counts). */
    fun onShown() {
        if (tracePhase != TracePhase.FIRST_FRAME) return
        tracePhase = TracePhase.SHOWN
        trace(TrailerPreviewEvent.Shown(sinceArm()))
    }

    fun onProgress(positionMs: Long) {
        val stage = _state.value?.stage ?: return
        if (stage != TrailerPreviewStage.LOADING) this.positionMs = positionMs
    }

    /** The trailer ended or failed: done, and nothing to resume. */
    fun onEndedOrError() {
        val candidate = focused ?: return
        if (_state.value == null) return
        closeDone(candidate)
    }

    /**
     * Every KeyDown the gate did not consume: a fresh press, or a repeat of a key the gate does not hold, decided
     * like a fresh press (e.g. a key held down from before the preview showed).
     */
    fun onKeyDown(key: TrailerPreviewKey): TrailerPreviewKeyDecision {
        if (key != TrailerPreviewKey.VOLUME) pendingOpen = null
        val stage = _state.value?.stage
        if (stage == null || stage == TrailerPreviewStage.LOADING) return TrailerPreviewKeyDecision.PASS
        val candidate = focused ?: return TrailerPreviewKeyDecision.PASS
        return when {
            key == TrailerPreviewKey.VOLUME -> TrailerPreviewKeyDecision.PASS
            key == TrailerPreviewKey.BACK -> {
                closeDone(candidate)
                TrailerPreviewKeyDecision.CLOSE_AND_CONSUME
            }
            key == TrailerPreviewKey.OK && stage == TrailerPreviewStage.PANEL -> {
                pendingOpen = candidate
                TrailerPreviewKeyDecision.CONSUME_OPEN_ON_UP
            }
            else -> {
                // Left/Right hides the trailer and a quick return resumes it (D1). A key that did not move focus
                // leaves the card done (nothing re-arms it) and keeps this record (PD5).
                closeKeepingRecord()
                TrailerPreviewKeyDecision.CLOSE_AND_PASS
            }
        }
    }

    /** KeyUp of stage 2's OK: the caller opens Details for the returned card; null = nothing to open. */
    fun openDetailsOnKeyUp(): TrailerPreviewCandidate? {
        val candidate = pendingOpen ?: return null
        pendingOpen = null
        closeKeepingRecord()
        return candidate
    }

    private fun leaveFocused() {
        if (focused == null) return
        closeKeepingRecord()
        focused = null
        focusedToken = 0L
        rearmOnResume = false
        resumeForFocused = null
        pendingOpen = null
    }

    /**
     * C2: wait the settle, then look up while the dwell runs out and prepare the player paused and hidden. The dwell's
     * end (a child of this job) unpauses it and, with no frame yet, starts the spinner; the card shows at the later of
     * the dwell's end and the first frame. The first-frame timeout counts from the later of the dwell's end and the
     * lookup's end.
     */
    private fun arm(candidate: TrailerPreviewCandidate, rearm: Boolean) {
        job?.cancel()
        armedAtMs = now()
        tracePhase = TracePhase.DWELL
        dwellElapsed = false
        frameSeen = false
        trace(TrailerPreviewEvent.Armed(candidate.itemId, rearm))
        job = scope.launch {
            val dwell = delayMs()
            val settle = trailerPreviewSettleMs(dwell)
            delay(settle)
            val dwellEnd = launch {
                delay(dwell - settle)
                dwellElapsed = true
                trace(TrailerPreviewEvent.DwellEnded(sinceArm()))
                // A frame already seen: the job shows it as the card next (unpaused there); else play on, still hidden.
                if (!frameSeen) {
                    _state.value?.let { if (it.stage == TrailerPreviewStage.LOADING && it.paused) _state.value = it.copy(paused = false) }
                    _loadingKey.value = candidate.key
                }
            }
            tracePhase = TracePhase.RESOLVING
            val resolveStart = sinceArm()
            trace(TrailerPreviewEvent.ResolveStarted(resolveStart))
            // A focus change or cancel cancels this job and the resolve throws on return, so the card is still
            // the focused one here. No source: nothing shows (the dwell's end must not start the spinner), and
            // nothing re-arms the card.
            val source = resolve(candidate)
            val resolveEnd = sinceArm()
            trace(TrailerPreviewEvent.Resolved(resolveEnd, found = source != null, durationMs = resolveEnd - resolveStart))
            if (source == null) {
                dwellEnd.cancel()
                tracePhase = TracePhase.IDLE
                _loadingKey.value = null
                return@launch
            }
            val start = resumeForFocused?.takeIf { it.videoUrl == source.videoUrl }
                ?.let { (it.positionMs - RESUME_REWIND_MS).coerceAtLeast(0L) } ?: 0L
            positionMs = start
            _state.value = TrailerPreviewState(candidate, source, start, TrailerPreviewStage.LOADING, paused = !dwellElapsed)
            tracePhase = TracePhase.LOADING
            trace(TrailerPreviewEvent.Prepared(sinceArm()))
            dwellEnd.join()
            val loading = _state.value?.takeIf { it.stage == TrailerPreviewStage.LOADING }
            if (loading != null && frameSeen) {
                // The frame came while hidden: show it now, from inside this job (no self-cancel).
                enterCard(loading)
                panelAfterDelay()
                return@launch
            }
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (_state.value?.stage == TrailerPreviewStage.LOADING) closeDone(candidate)
        }
    }

    private fun enterCard(loading: TrailerPreviewState) {
        _state.value = loading.copy(stage = TrailerPreviewStage.CARD, paused = false)
        _loadingKey.value = null
        tracePhase = TracePhase.FIRST_FRAME
    }

    /** Stage 1 -> stage 2 after the delay setting, if still at stage 1. */
    private suspend fun panelAfterDelay() {
        delay(delayMs())
        _state.value?.let {
            if (it.stage == TrailerPreviewStage.CARD) {
                _state.value = it.copy(stage = TrailerPreviewStage.PANEL)
                trace(TrailerPreviewEvent.Panel(sinceArm()))
            }
        }
    }

    private fun stopWaiting() {
        stop()
        rearmOnResume = true
    }

    /** Stop at a stop point (PD5): a playing card's last progress becomes the resume record. */
    private fun closeKeepingRecord() {
        val current = _state.value
        if (current != null && current.stage != TrailerPreviewStage.LOADING) {
            resume = Resume(current.candidate.itemId, current.source.videoUrl, positionMs, now())
        }
        stop()
    }

    /** A done cause: stop, and forget this title's record (another title's record stays). */
    private fun closeDone(candidate: TrailerPreviewCandidate) {
        stop()
        rearmOnResume = false
        if (resume?.itemId == candidate.itemId) resume = null
        resumeForFocused = null
    }

    private fun stop() {
        job?.cancel()
        job = null
        _state.value = null
        _loadingKey.value = null
        val aborted = when (tracePhase) {
            TracePhase.RESOLVING -> TrailerPreviewAbortPhase.RESOLVING
            TracePhase.LOADING, TracePhase.HIDDEN_FRAME -> TrailerPreviewAbortPhase.LOADING
            TracePhase.IDLE, TracePhase.DWELL, TracePhase.FIRST_FRAME, TracePhase.SHOWN -> null
        }
        aborted?.let { trace(TrailerPreviewEvent.Aborted(sinceArm(), it, hidden = !dwellElapsed)) }
        tracePhase = TracePhase.IDLE
        dwellElapsed = false
        frameSeen = false
    }

    private fun sinceArm(): Long = now() - armedAtMs

    companion object {
        const val RESUME_WINDOW_MS = 10_000L
        const val RESUME_REWIND_MS = 5_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val SHAPE_MEMORY = 32
        /** C2: how far before the dwell's end the lookup may start (0 = at the dwell's end, the pre-C2 timing). */
        const val SETTLE_LEAD_MS = 2_000L
    }
}

/**
 * C2: when the lookup starts after a focus, for a dwell of [dwellMs]: [leadMs] before the dwell ends, but never
 * before a third of it (a 1 s dwell waits 333 ms, a 3 s one 1 s, a 10 s one 8 s).
 */
internal fun trailerPreviewSettleMs(dwellMs: Long, leadMs: Long = TrailerPreviewMachine.SETTLE_LEAD_MS): Long =
    maxOf(dwellMs / 3, dwellMs - leadMs)
