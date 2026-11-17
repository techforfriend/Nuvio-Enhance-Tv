package com.nuvio.tv.core.player

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 * Detection lives in [AfrMediaProbe] / [AfrHttpProbe]; the fps cache in [AfrFpsCache].
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"
    private const val SWITCH_TIMEOUT_MS = 4000L
    private const val REFRESH_MATCH_MIN_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f
    internal const val TIMESTAMP_REORDER_MARGIN = 16

    // Spec 36/S5 (B7-5, upstream 62b31e948 + e2c0a47f0): samples come in decode order. With B-frames that is
    // not presentation order, and the newest samples of any read can be ahead of frames that were never read,
    // so sort and leave those out. skipFirst drops the lowest timestamps after sorting, not the first decoded.
    internal fun averageSampleDurationUs(
        timestampsUs: List<Long>,
        skipFirst: Int,
        skipLast: Int
    ): Float? {
        val sorted = timestampsUs.sorted()
        val last = sorted.size - 1 - skipLast
        val intervals = last - skipFirst
        if (intervals < 30) return null
        val average = (sorted[last] - sorted[skipFirst]).toFloat() / intervals
        return average.takeIf { it > 0f }
    }
    private const val SWITCH_POLL_INTERVAL_MS = 60L
    private const val SWITCH_REQUIRED_STABLE_POLLS = 2
    /** Below this the reported rate is not real content; never switch the panel for it. */
    private const val MIN_AFR_SWITCH_FPS = 20f

    data class DisplayModeSwitchResult(
        val appliedMode: Display.Mode
    )

    /**
     * Spec 93 P1 (Plezy FrameRateManager HDR_EXIT_SETTLE_MS, #2172): the exit restore waits this
     * long after the player released its surface, so the sink does the HDR exit and the refresh
     * change as two steps; folded into one renegotiation it took 8-30 s on some AVR chains.
     */
    private const val HDR_EXIT_SETTLE_MS = 400L

    // Main thread only: recorded and restored inside withContext(Main), Compose effects and the
    // main-looper handler below.
    private val originalMode = OriginalDisplayMode()
    private val restoreHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingRestoreRunnable: Runnable? = null

    data class FrameRateDetection(
        val raw: Float,
        val snapped: Float,
        val videoWidth: Int? = null,
        val videoHeight: Int? = null
    )

    private fun matchesTargetRefresh(refreshRate: Float, target: Float): Boolean {
        val tolerance = max(REFRESH_MATCH_MIN_TOLERANCE_HZ, target * 0.003f)
        return abs(refreshRate - target) <= tolerance
    }

    private fun pickBestForTarget(modes: List<Display.Mode>, target: Float): Display.Mode? {
        if (target <= 0f) return null
        val closest = modes.minByOrNull { abs(it.refreshRate - target) } ?: return null
        return if (matchesTargetRefresh(closest.refreshRate, target)) closest else null
    }

    private fun refreshWeight(refresh: Float, fps: Float): Float {
        if (fps <= 0f || refresh <= 0f) return Float.MAX_VALUE
        val div = refresh / fps
        if (div < 0.5f) return (fps - refresh) / fps

        val candidateRatios = floatArrayOf(1.0f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.0f)
        var minCadenceError = Float.MAX_VALUE
        for (m in candidateRatios) {
            val err = abs(div / m - 1f)
            if (err < minCadenceError) {
                minCadenceError = err
            }
        }

        var weight = minCadenceError

        val isCinemaOrNtscContent = fps in 23.5f..24.5f || fps in 29.5f..30.5f || fps in 59.5f..60.5f
        val isPalRefresh = abs(refresh - 25.0f) <= 0.1f || abs(refresh - 50.0f) <= 0.1f
        if (isCinemaOrNtscContent && isPalRefresh) {
            weight += 0.5f
        }

        if (refresh > 60f && div > 1f) {
            weight += div / 10000f
        }
        return weight
    }

    private fun recordOriginalMode(display: Display) {
        if (originalMode.record(DisplayModeValues.of(display.mode))) {
            // A new player switches inside the previous one's exit window: no hand-back to the UI
            // mode in between, and the UI mode stays the original this session restores to.
            cancelPendingRestore()
            Log.d(TAG, "Pending exit restore cancelled by a new display-mode switch")
        }
    }

    /**
     * Refine ambiguous cinema rates for the current display capabilities.
     * Useful when probe reports ~24.x but panel supports both 23.976 and 24.000.
     */
    fun refineFrameRateForDisplay(
        activity: Activity,
        detectedFps: Float,
        prefer23976Near24: Boolean = false
    ): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return detectedFps
        if (detectedFps !in 23.5f..24.5f) return detectedFps

        return try {
            val window = activity.window ?: return detectedFps
            val display = window.decorView.display ?: return detectedFps
            val activeMode = display.mode
            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.isEmpty()) return detectedFps

            val has23976 = pickBestForTarget(sameSizeModes, NTSC_FILM_FPS) != null
            val has24 = pickBestForTarget(sameSizeModes, CINEMA_24_FPS) != null

            when {
                has23976 && has24 -> {
                    if (prefer23976Near24) {
                        NTSC_FILM_FPS
                    } else if (abs(detectedFps - NTSC_FILM_FPS) <= abs(detectedFps - CINEMA_24_FPS)) {
                        NTSC_FILM_FPS
                    } else {
                        CINEMA_24_FPS
                    }
                }
                has23976 -> NTSC_FILM_FPS
                has24 -> CINEMA_24_FPS
                else -> detectedFps
            }
        } catch (_: Exception) {
            detectedFps
        }
    }

    /**
     * How well a chosen mode actually serves the content's frame rate.
     * EXACT and DOUBLE are clean cadences; PULLDOWN is 2:3 judder that merely
     * happens to divide evenly; FALLBACK is the least-bad of a bad set.
     * Previously this distinction existed only as the order of the elvis chain
     * and was discarded on return, so nothing above could tell a clean match
     * from a judder-inducing one.
     */
    internal enum class ModeMatchQuality { EXACT, DOUBLE, PULLDOWN, FALLBACK }

    internal data class ModeChoice(val mode: Display.Mode, val quality: ModeMatchQuality)

    /** True for cadences worth giving up a resolution match to obtain. */
    private fun ModeMatchQuality.isCleanCadence() =
        this == ModeMatchQuality.EXACT || this == ModeMatchQuality.DOUBLE

    private fun chooseBestModeForFrameRate(
        activeMode: Display.Mode,
        modes: List<Display.Mode>,
        frameRate: Float
    ): ModeChoice {
        pickBestForTarget(modes, frameRate)?.let {
            return ModeChoice(it, ModeMatchQuality.EXACT)
        }
        pickBestForTarget(modes, frameRate * 2f)?.let {
            return ModeChoice(it, ModeMatchQuality.DOUBLE)
        }
        pickBestForTarget(modes, frameRate * 2.5f)?.let {
            return ModeChoice(it, ModeMatchQuality.PULLDOWN)
        }
        val modeFallback = modes.minByOrNull { refreshWeight(it.refreshRate, frameRate) }
        return ModeChoice(modeFallback ?: activeMode, ModeMatchQuality.FALLBACK)
    }

    private fun hasValidVideoSize(videoWidth: Int?, videoHeight: Int?): Boolean {
        return (videoWidth ?: 0) > 0 && (videoHeight ?: 0) > 0
    }

    private fun normalizedSize(width: Int, height: Int): Pair<Int, Int> {
        return if (width >= height) width to height else height to width
    }

    private fun resolutionDistanceSquared(mode: Display.Mode, targetWidth: Int, targetHeight: Int): Long {
        val (modeWidth, modeHeight) = normalizedSize(mode.physicalWidth, mode.physicalHeight)
        val dw = modeWidth - targetWidth
        val dh = modeHeight - targetHeight
        return dw.toLong() * dw.toLong() + dh.toLong() * dh.toLong()
    }

    private fun selectModesForVideoResolution(
        modes: List<Display.Mode>,
        videoWidth: Int,
        videoHeight: Int
    ): List<Display.Mode> {
        if (modes.isEmpty()) return modes
        // AFR review F8/R6: floor at 720p. "Nearest resolution" with no lower
        // bound meant an SD file could drop the whole HDMI output - UI included -
        // to a 480p/576p mode (Amlogic boxes commonly expose them). The intended
        // 4K<->1080p use case is unaffected.
        val flooredModes = modes.filter { it.physicalHeight >= 720 && it.physicalWidth >= 1280 }
            .ifEmpty { modes }
        val (targetWidth, targetHeight) = normalizedSize(videoWidth, videoHeight)
        val minDistance = flooredModes.minOfOrNull { resolutionDistanceSquared(it, targetWidth, targetHeight) } ?: return flooredModes
        return flooredModes.filter { resolutionDistanceSquared(it, targetWidth, targetHeight) == minDistance }
    }

    suspend fun matchFrameRateAndWait(
        activity: Activity,
        frameRate: Float,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        resolutionMatchingEnabled: Boolean = false
    ): DisplayModeSwitchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (frameRate <= 0f) return null
        // Sanity floor. A broken source can report an absurd rate -- 24 Jul 2026 a
        // StremThru entry served a 30 s 1280x720 error stub at frameRate=1.0 and the
        // panel was driven 59.94Hz -> 50.0Hz for it, costing a switch, a settle and
        // an exit blank for content that was never the episode. snapToStandardRate
        // recognises nothing below 23.90 fps, so anything under this floor cannot be
        // matched meaningfully anyway; leave the display alone.
        if (frameRate < MIN_AFR_SWITCH_FPS) {
            Log.w(TAG, "Refusing display-mode switch for implausible frame rate ${frameRate}fps")
            return null
        }

        val switchPlan = withContext(Dispatchers.Main) {
            val window = activity.window ?: return@withContext null
            val display = window.decorView.display ?: return@withContext null
            val activeMode = display.mode

            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            val candidateModes = if (resolutionMatchingEnabled && hasValidVideoSize(videoWidth, videoHeight)) {
                selectModesForVideoResolution(
                    modes = display.supportedModes.toList(),
                    videoWidth = videoWidth ?: activeMode.physicalWidth,
                    videoHeight = videoHeight ?: activeMode.physicalHeight
                )
            } else {
                sameSizeModes
            }
            if (candidateModes.isEmpty()) {
                Log.d(
                    TAG,
                    "No candidate display modes; leaving the display at " +
                        "${activeMode.refreshRate}Hz for ${frameRate}fps"
                )
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }
            if (!resolutionMatchingEnabled && candidateModes.size <= 1) {
                // Previously a silent return: the app did nothing and said nothing, which
                // is why a display that can never frame-rate match took a user report to
                // find.
                Log.d(
                    TAG,
                    "Display offers a single mode at " +
                        "${activeMode.physicalWidth}x${activeMode.physicalHeight} " +
                        "(${activeMode.refreshRate}Hz): no app-side frame rate matching is " +
                        "possible for ${frameRate}fps"
                )
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }

            var choice = chooseBestModeForFrameRate(
                activeMode = activeMode,
                modes = candidateModes,
                frameRate = frameRate
            )

            // Resolution matching narrows the candidates to the mode family
            // nearest the video's own size. When that family cannot serve the
            // content's cadence, honouring it costs judder for a benefit
            // nobody asked for.
            //
            // Observed 21 Jul 2026 on an LG C9 (S905X5M): a 1920x1080 23.976
            // title selected the 1080p family, whose only rates are
            // 59.94 / 60 / 50. No exact or double match exists there, so the
            // 2.5x branch matched 59.94 and the display sat in 2:3 pulldown -
            // while 3840x2160 @ 23.976 (modeId 9) went unused one resolution
            // away. Every rung behaved as written; nothing weighed the two
            // kinds of match against each other.
            //
            // The rule: an exact or double frame-rate match beats a resolution
            // match. A pulldown or fallback match does not. So resolution
            // matching keeps working wherever it costs nothing - which is
            // every 4K case - and stands down only where it would cost cadence.
            if (resolutionMatchingEnabled && !choice.quality.isCleanCadence()) {
                val wider = chooseBestModeForFrameRate(
                    activeMode = activeMode,
                    modes = display.supportedModes.toList(),
                    frameRate = frameRate
                )
                if (wider.quality.isCleanCadence()) {
                    Log.d(
                        TAG,
                        "Resolution-matched modes offer only a ${choice.quality} match for " +
                            "${frameRate}fps; widening to all display modes for a " +
                            "${wider.quality} match at ${wider.mode.refreshRate}Hz " +
                            "(${wider.mode.physicalWidth}x${wider.mode.physicalHeight})"
                    )
                    choice = wider
                }
            }
            val modeBest = choice.mode
            recordOriginalMode(display)
            if (modeBest.modeId == activeMode.modeId) {
                // Not necessarily optimal: very often it is simply the only mode on offer,
                // and calling 60Hz "optimal" for 25fps content is a plain untruth. Say
                // which of the two it is.
                val alternatives = candidateModes.size - 1
                Log.d(
                    TAG,
                    if (alternatives <= 0) {
                        "No alternative display mode at " +
                            "${activeMode.physicalWidth}x${activeMode.physicalHeight}: staying at " +
                            "${activeMode.refreshRate}Hz for ${frameRate}fps. The panel may still be " +
                            "matching the content on its own — Android cannot report that."
                    } else {
                        "Keeping ${activeMode.refreshRate}Hz for ${frameRate}fps: best of " +
                            "${candidateModes.size} candidate modes"
                    }
                )
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }

            Log.d(
                TAG,
                "Switching display mode: ${activeMode.refreshRate}Hz -> ${modeBest.refreshRate}Hz " +
                    "(video ${frameRate}fps)"
            )

            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = modeBest.modeId
            window.attributes = layoutParams
            Pair(modeBest, null)
        } ?: return null

        val immediateResult = switchPlan.second
        if (immediateResult != null) return immediateResult

        val expectedMode = switchPlan.first ?: return null
        var stablePolls = 0
        var lastMode: Display.Mode? = null
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < SWITCH_TIMEOUT_MS) {
            val mode = withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode
            } ?: break

            lastMode = mode
            val modeStable =
                mode.modeId == expectedMode.modeId ||
                    matchesTargetRefresh(mode.refreshRate, expectedMode.refreshRate)
            if (modeStable) {
                stablePolls += 1
                if (stablePolls >= SWITCH_REQUIRED_STABLE_POLLS) {
                    Log.d(
                        TAG,
                        "Display mode switch stabilized at ${mode.refreshRate}Hz (modeId=${mode.modeId})"
                    )
                    return DisplayModeSwitchResult(mode)
                }
            } else {
                stablePolls = 0
            }
            delay(SWITCH_POLL_INTERVAL_MS)
        }

        val fallbackMode = lastMode ?: expectedMode
        Log.w(
            TAG,
            "Display mode polling timed out after ${SWITCH_TIMEOUT_MS}ms, using ${fallbackMode.refreshRate}Hz"
        )
        return DisplayModeSwitchResult(fallbackMode)
    }

    fun clearOriginalDisplayMode() {
        cancelPendingRestore()
        originalMode.clear()
    }

    private fun cancelPendingRestore() {
        pendingRestoreRunnable?.let { restoreHandler.removeCallbacks(it) }
        pendingRestoreRunnable = null
    }

    /** Restores the mode the session started in now (the player is still showing; no HDR exit). */
    fun restoreOriginalDisplayMode(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val target = originalMode.take() ?: return false
        return applyDisplayMode(activity, target)
    }

    /**
     * Spec 93 P1: restores the session's original mode [HDR_EXIT_SETTLE_MS] after the player was
     * released, on a main-looper handler that outlives the player screen.
     */
    fun restoreOriginalDisplayModeAfterExit(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (originalMode.deferForExit() == null) return
        cancelPendingRestore()
        val runnable = Runnable {
            pendingRestoreRunnable = null
            val target = originalMode.takePending() ?: return@Runnable
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            applyDisplayMode(activity, target)
        }
        pendingRestoreRunnable = runnable
        restoreHandler.postDelayed(runnable, HDR_EXIT_SETTLE_MS)
    }

    private fun applyDisplayMode(activity: Activity, target: DisplayModeValues): Boolean {
        return try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            // F1A-3: mode ids can be renumbered (standby, HDR) while the values stay, so the
            // original is found again by size and refresh, never by the id it had. Always written,
            // even when the panel already shows that mode: no tolerance-based "already there"
            // (59.94 and 60 Hz are within it), and the window must stop asking for the film's mode.
            // 0 (the original is no longer offered) hands the choice back to the system default.
            val modeId = restoreModeId(display.supportedModes.toList(), display.mode, target)
            if (modeId == 0) Log.w(TAG, "Original display mode $target is no longer offered; using the system default")
            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = modeId
            window.attributes = layoutParams
            Log.d(TAG, "Restoring display mode $target (modeId=$modeId)")
            modeId != 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore display mode", e)
            false
        }
    }

    /**
     * The mode id the restore writes: the active mode when its values are exactly [target]'s (no
     * switch between twin modes), else [pickModeByValues], else 0 (the system default).
     */
    internal fun restoreModeId(modes: List<Display.Mode>, active: Display.Mode, target: DisplayModeValues): Int {
        if (DisplayModeValues.of(active) == target) return active.modeId
        return pickModeByValues(modes, target)?.modeId ?: 0
    }

    /** The supported mode of [target]'s size whose refresh is nearest [target]'s, within the match tolerance. */
    internal fun pickModeByValues(modes: List<Display.Mode>, target: DisplayModeValues): Display.Mode? =
        pickBestForTarget(
            modes.filter { it.physicalWidth == target.width && it.physicalHeight == target.height },
            target.refreshRate
        )

    /** A display mode by its values; its id is not stable across standby or an HDR switch. */
    internal data class DisplayModeValues(val width: Int, val height: Int, val refreshRate: Float) {
        companion object {
            fun of(mode: Display.Mode) = DisplayModeValues(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        }
    }

    /** The mode a playback session started in, and an exit restore waiting for the HDR exit. */
    internal class OriginalDisplayMode {
        private var recorded: DisplayModeValues? = null
        private var pending: DisplayModeValues? = null

        /**
         * Before an AFR switch. The session keeps its first original; a pending exit restore
         * becomes the original again. Returns true when it cancelled a pending restore.
         */
        fun record(current: DisplayModeValues): Boolean {
            val adopted = pending
            if (adopted == null) {
                if (recorded == null) recorded = current
                return false
            }
            pending = null
            recorded = adopted
            return true
        }

        fun take(): DisplayModeValues? = recorded.also { recorded = null }

        /** Moves the original to a pending exit restore; null when there is nothing to restore. */
        fun deferForExit(): DisplayModeValues? {
            val target = recorded ?: return null
            recorded = null
            pending = target
            return target
        }

        fun takePending(): DisplayModeValues? = pending.also { pending = null }

        fun clear() {
            recorded = null
            pending = null
        }
    }

    // C-2: an explicit proximity gate for the pre-seed. snapToStandardRate is
    // NOT usable for this test -- an input already equal to a ladder value
    // returns unchanged, so "snap changed the value" cannot distinguish an
    // on-ladder rate from an off-ladder one. Only a rate this close to a known
    // standard is trusted enough to switch the panel before prepare; anything
    // else (a torn/misparsed head, an audio DefaultDuration mistaken for video)
    // is left to the post-prepare track-format path.
    private val STANDARD_RATES = floatArrayOf(
        NTSC_FILM_FPS, CINEMA_24_FPS, 25f, 30000f / 1001f, 30f, 50f, 60000f / 1001f, 60f
    )
    private const val STANDARD_RATE_TOLERANCE_FPS = 0.05f

    internal fun isNearStandardRate(fps: Float): Boolean {
        if (!fps.isFinite() || fps < MIN_AFR_SWITCH_FPS) return false
        for (r in STANDARD_RATES) {
            if (abs(fps - r) <= STANDARD_RATE_TOLERANCE_FPS) return true
        }
        return false
    }

    fun snapToStandardRate(formatFrameRate: Float): Float {
        if (formatFrameRate <= 0f) return formatFrameRate
        return when {
            formatFrameRate in 23.90f..23.988f -> NTSC_FILM_FPS
            formatFrameRate in 23.988f..24.1f -> CINEMA_24_FPS
            formatFrameRate in 24.9f..25.1f -> 25f
            formatFrameRate in 29.90f..29.985f -> 30000f / 1001f
            formatFrameRate in 29.985f..30.1f -> 30f
            formatFrameRate in 49.9f..50.1f -> 50f
            formatFrameRate in 59.9f..59.97f -> 60000f / 1001f
            formatFrameRate in 59.97f..60.1f -> 60f
            else -> formatFrameRate
        }
    }

    internal fun snapProbeRateByFrameDuration(measuredFps: Float, averageFrameDurationUs: Float): Float {
        if (measuredFps in 23.5f..24.5f) {
            val frameUs23976 = 1_000_000f / NTSC_FILM_FPS
            val frameUs24 = 1_000_000f / CINEMA_24_FPS
            val diff23976 = abs(averageFrameDurationUs - frameUs23976)
            val diff24 = abs(averageFrameDurationUs - frameUs24)
            val nearestCinema = if (diff23976 <= diff24) NTSC_FILM_FPS else CINEMA_24_FPS
            val nearestDiff = min(diff23976, diff24)

            // If probe timing is reasonably close to cinema cadence, trust frame-duration matching.
            if (nearestDiff <= 120f) {
                return nearestCinema
            }
        }
        return snapToStandardRate(measuredFps)
    }

}
