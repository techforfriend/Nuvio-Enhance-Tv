package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.core.player.findCause
import java.net.UnknownHostException

/**
 * Why a stream (or the channel) failed; the UI words it from [kind] and shows [httpCode] if set.
 * [detail] is a ready-made reason when the ladder never ran (e.g. the addon lookup's "Source removed").
 * A [FailureKind.DRM] failure carries [drm] (Spec 74 D5) and the stream's [keySystem] (a `LiveDrm` id).
 */
data class LiveFailure(
    val kind: FailureKind,
    val httpCode: Int? = null,
    val detail: String? = null,
    val drm: DrmFailure? = null,
    val keySystem: String? = null
)

/**
 * How a licensed stream failed (Spec 74 D5): the licence server refused, the TV lacks the key
 * system, the TV rejected the licence, or the stream is encrypted and its playlist gives no licence.
 */
enum class DrmFailure { REFUSED, UNSUPPORTED, NOT_ACCEPTED, NO_LICENCE }

/** What the player must do next. */
sealed interface LadderAction {
    /** Build and prepare stream [index] of the ordered list. */
    data class Play(val index: Int) : LadderAction

    /** Prepare the same stream again, no seek. */
    data object Reload : LadderAction

    /** Behind the live window: `seekToDefaultPosition()` + `prepare()`. */
    data object SeekToDefault : LadderAction

    /** Stop trying: the channel is unavailable, last failure attached. */
    data class Unavailable(val failure: LiveFailure) : LadderAction

    data object None : LadderAction
}

/** One decision; [abandoned] is the stream index given up on (record its health), if any. */
data class LadderStep(val action: LadderAction, val abandoned: Int? = null, val failure: LiveFailure? = null)

/**
 * The per-channel recovery ladder (Spec 34 D7, L6c), pure: the player feeds it events with the
 * current time and does what it answers. Rungs, every one bounded (M3-PIPE-10):
 *
 * 1. Behind the live window -> [LadderAction.SeekToDefault], at most [MAX_BLW_FIXES] per stream.
 * 2. Any other failure the stream may survive -> [LadderAction.Reload] once. Skipped for a fatal
 *    HTTP/parse/DRM failure (never heals; a sustained play's failure still gets its reload: one
 *    bad live segment must not cost the stream a day) and for a startup timeout on a stream that
 *    never played (a reload would spend another [WATCHDOG_MS] of the budget on the same host).
 *    Exception (Spec 72 D1): a stream resolved with a cached token ([onResolved]) keeps its
 *    reload on a 401/403/404, since a stale token gives exactly that; the player's Reload
 *    resolves it again with a fresh token.
 * 3. Next stream.
 * 4. [LadderAction.Unavailable] when every stream failed or [BUDGET_MS] passed without a stream
 *    playing.
 *
 * Watchdogs: no READY within [WATCHDOG_MS] of a Play or Reload (the player's URL resolve and mime
 * sniff included), or BUFFERING for [WATCHDOG_MS] after
 * playing, is a [FailureKind.STALL]; so is ENDED (a live stream never ends). Any failure after
 * READY restarts the budget at the failure, so a stream is not judged by the tune time; only a
 * play of [MIN_SUSTAINED_PLAY_MS] also restores the stream's reload and seek allowances, so a
 * stream that reaches READY and fails again at once still runs out of rungs. The caller schedules
 * [deadlineMs] and calls [onTick] then.
 */
class LiveTuneLadder(private val streamCount: Int, tuneStartMs: Long) {

    private enum class Phase { STARTING, PLAYING, REBUFFERING, DONE }

    private var budgetStartMs = tuneStartMs
    private var phase = Phase.STARTING
    private var phaseDeadlineMs = Long.MAX_VALUE

    var index = 0
        private set
    private var reloadUsed = false
    private var blwFixes = 0

    /** The current attempt plays a URL built on a cached resolver token; cleared by the next (re)load. */
    private var cachedResolve = false

    /** When the current (re)prepare reached READY; null until it does. */
    private var playingSinceMs: Long? = null

    val isDone: Boolean get() = phase == Phase.DONE

    /** The first action: play stream 0, or unavailable when there is nothing to play. */
    fun start(nowMs: Long): LadderStep {
        if (streamCount <= 0) return giveUp(LiveFailure(FailureKind.DEAD), abandoned = null)
        return LadderStep(startStream(0, nowMs))
    }

    /** The player resolved the current attempt's URL; [fromCache] = on a reused token (Spec 72). */
    fun onResolved(fromCache: Boolean) {
        cachedResolve = fromCache
    }

    fun onReady(nowMs: Long): LadderStep {
        if (phase == Phase.DONE) return LadderStep(LadderAction.None)
        if (phase == Phase.STARTING) playingSinceMs = nowMs
        phase = Phase.PLAYING
        phaseDeadlineMs = Long.MAX_VALUE
        return LadderStep(LadderAction.None)
    }

    fun onBuffering(nowMs: Long): LadderStep {
        if (phase == Phase.PLAYING) {
            phase = Phase.REBUFFERING
            phaseDeadlineMs = nowMs + WATCHDOG_MS
        }
        return LadderStep(LadderAction.None)
    }

    fun onEnded(nowMs: Long): LadderStep {
        if (phase == Phase.DONE) return LadderStep(LadderAction.None)
        return fail(LiveFailure(FailureKind.STALL), nowMs, startupTimeout = false, sustained = afterPlay(nowMs))
    }

    fun onError(failure: LiveFailure, behindLiveWindow: Boolean, nowMs: Long): LadderStep {
        if (phase == Phase.DONE) return LadderStep(LadderAction.None)
        val sustained = afterPlay(nowMs)
        if (behindLiveWindow && blwFixes < MAX_BLW_FIXES && nowMs < budgetStartMs + BUDGET_MS) {
            blwFixes++
            arm(nowMs)
            return LadderStep(LadderAction.SeekToDefault)
        }
        return fail(failure, nowMs, startupTimeout = false, sustained = sustained)
    }

    /** The earliest time [onTick] must run, or null when nothing is pending. */
    fun deadlineMs(): Long? = when (phase) {
        Phase.DONE -> null
        Phase.PLAYING -> null
        Phase.REBUFFERING -> phaseDeadlineMs
        Phase.STARTING -> minOf(phaseDeadlineMs, budgetStartMs + BUDGET_MS)
    }

    fun onTick(nowMs: Long): LadderStep {
        val deadline = deadlineMs() ?: return LadderStep(LadderAction.None)
        if (nowMs < deadline) return LadderStep(LadderAction.None)
        val startupTimeout = phase == Phase.STARTING
        return fail(LiveFailure(FailureKind.STALL), nowMs, startupTimeout, sustained = afterPlay(nowMs))
    }

    /**
     * Bookkeeping for a failure (or behind-live-window) after READY: the budget restarts now; a
     * sustained play also restores the allowances. Returns whether the play was sustained.
     */
    private fun afterPlay(nowMs: Long): Boolean {
        val since = playingSinceMs ?: return false
        playingSinceMs = null
        budgetStartMs = nowMs
        val sustained = nowMs - since >= MIN_SUSTAINED_PLAY_MS
        if (sustained) {
            reloadUsed = false
            blwFixes = 0
        }
        return sustained
    }

    private fun fail(failure: LiveFailure, nowMs: Long, startupTimeout: Boolean, sustained: Boolean): LadderStep {
        if (nowMs >= budgetStartMs + BUDGET_MS) return giveUp(failure, abandoned = index)
        val staleToken = cachedResolve && failure.httpCode in STALE_TOKEN_CODES
        val reloadable = !reloadUsed && !startupTimeout && (sustained || !failure.kind.isPermanent() || staleToken)
        if (reloadable) {
            reloadUsed = true
            cachedResolve = false
            arm(nowMs)
            return LadderStep(LadderAction.Reload)
        }
        val next = index + 1
        if (next >= streamCount) return giveUp(failure, abandoned = index)
        val abandoned = index
        return LadderStep(startStream(next, nowMs), abandoned = abandoned, failure = failure)
    }

    private fun startStream(i: Int, nowMs: Long): LadderAction {
        index = i
        reloadUsed = false
        blwFixes = 0
        cachedResolve = false
        playingSinceMs = null
        arm(nowMs)
        return LadderAction.Play(i)
    }

    private fun arm(nowMs: Long) {
        phase = Phase.STARTING
        phaseDeadlineMs = nowMs + WATCHDOG_MS
    }

    private fun giveUp(failure: LiveFailure, abandoned: Int?): LadderStep {
        phase = Phase.DONE
        phaseDeadlineMs = Long.MAX_VALUE
        return LadderStep(LadderAction.Unavailable(failure), abandoned = abandoned, failure = failure)
    }

    companion object {
        const val WATCHDOG_MS = 10_000L
        const val BUDGET_MS = 30_000L
        const val MAX_BLW_FIXES = 2
        const val MIN_SUSTAINED_PLAY_MS = 30_000L

        /** What a stale resolver token answers (Spec 72 D1). */
        private val STALE_TOKEN_CODES = setOf(401, 403, 404)

        private fun FailureKind.isPermanent(): Boolean =
            this == FailureKind.BLOCKED || this == FailureKind.DEAD ||
                this == FailureKind.PARSE || this == FailureKind.DRM

        /**
         * A player error as the ladder sees it: its [LiveFailure], and whether it is Media3's
         * behind-live-window error (1002), which has its own rung.
         */
        @OptIn(UnstableApi::class)
        fun classify(error: PlaybackException): Pair<LiveFailure, Boolean> {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                return LiveFailure(FailureKind.STALL) to true
            }
            val code = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
            // Before the HTTP mapping: a licence server's 403 is a refused licence, not a blocked stream.
            if (error.errorCode in DRM_ERRORS) return classifyDrm(error, code) to false
            val kind = when {
                code == 401 || code == 403 || code == 451 -> FailureKind.BLOCKED
                code == 400 || code == 404 || code == 410 -> FailureKind.DEAD
                code != null -> FailureKind.NETWORK
                error.findCause<UnknownHostException>() != null -> FailureKind.NETWORK
                error.errorCode in PARSE_ERRORS -> FailureKind.PARSE
                else -> FailureKind.NETWORK
            }
            return LiveFailure(kind, code) to false
        }

        /**
         * Spec 74 D5: 6001 = this TV lacks the key system; a licence 4xx = refused; a licence 5xx,
         * 408 or 429, or a request that never got an answer = a network failure (may heal, as on a
         * clear stream); the rest (provisioning, licence rejected, device revoked, expired) = not
         * accepted by this TV.
         */
        @OptIn(UnstableApi::class)
        private fun classifyDrm(error: PlaybackException, code: Int?): LiveFailure = when {
            error.errorCode == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                LiveFailure(FailureKind.DRM, drm = DrmFailure.UNSUPPORTED)
            code != null && (code >= 500 || code == 408 || code == 429) -> LiveFailure(FailureKind.NETWORK, code)
            code != null -> LiveFailure(FailureKind.DRM, code, drm = DrmFailure.REFUSED)
            error.findCause<HttpDataSource.HttpDataSourceException>() != null ||
                error.findCause<UnknownHostException>() != null -> LiveFailure(FailureKind.NETWORK)
            else -> LiveFailure(FailureKind.DRM, drm = DrmFailure.NOT_ACCEPTED)
        }

        /** Media3 6000-6008: DRM scheme, provisioning, licence, device revoked. */
        private val DRM_ERRORS = 6000..6999

        /**
         * Container/manifest parsing (3001-3004) and decoder init/query/capability/format
         * (4001, 4002, 4004, 4005): this stream will not play here. A mid-play decoding failure
         * (4003) stays reloadable.
         */
        private val PARSE_ERRORS = (3001..3004).toSet() + setOf(4001, 4002, 4004, 4005)
    }
}
