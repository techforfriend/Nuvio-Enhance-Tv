package com.nuvio.tv.core.player

/**
 * Spec 70 R2 (D5): what to do about a frozen picture, the position stuck while ExoPlayer is READY and
 * playing ([PlayerPlaybackAnalyticsDiagnostics.currentPositionStallMs]). Rung 1 seeks just before the playhead;
 * rung 2, when the seek did not cure it, is the dead-source failover. Pure, so tests mirror production.
 */
internal object PlayerFreezeRecoveryPolicy {

    /** Above the one Shield record, a 7.5 s freeze that cleared by itself (2026-09-17). */
    const val ACT_AFTER_MS = 10_000L

    /** Rung 1's rollback: past the detector's 250 ms tolerance, so the seek restarts its count. */
    const val SEEK_BACK_MS = 500L

    /** A new freeze this soon after our seek means the seek did not cure it. */
    const val SEEK_CURE_WINDOW_MS = 60_000L

    data class Input(
        val stalledForMs: Long,
        val nowMs: Long,
        /** elapsedRealtime of this stream's last recovery seek; null when none. */
        val lastSeekAtMs: Long?,
        /** Rung 2 already ran (or found no source) for this stream. */
        val failoverDone: Boolean,
        val errorShown: Boolean,
    )

    enum class Decision { NONE, SEEK, FAILOVER }

    fun evaluate(input: Input): Decision {
        if (input.failoverDone || input.errorShown || input.stalledForMs < ACT_AFTER_MS) return Decision.NONE
        val sinceSeekMs = input.lastSeekAtMs?.let { input.nowMs - it }
        return when {
            sinceSeekMs == null || sinceSeekMs > SEEK_CURE_WINDOW_MS -> Decision.SEEK
            // Give the seek its own full window: a freeze that carried on through it is only
            // judged ACT_AFTER_MS after the seek, not on the next tick.
            sinceSeekMs >= ACT_AFTER_MS -> Decision.FAILOVER
            else -> Decision.NONE
        }
    }
}
