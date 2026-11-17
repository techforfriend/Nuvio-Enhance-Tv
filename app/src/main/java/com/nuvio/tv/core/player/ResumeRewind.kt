package com.nuvio.tv.core.player

import kotlin.math.abs

/**
 * Spec 93 P5 (D1-D3), "Rewind on resume": playback that starts from saved progress, or resumes after a user pause
 * of [MIN_PAUSE_MS] or more, picks up [rewindSeconds] earlier, never before 0. A rebuild that carries the live
 * position (source switch, failover, engine hop: `pendingSeekPosition`) is no start from saved progress and never
 * comes here.
 */
internal object ResumeRewind {

    /** D1: the pause length that earns a rewind, fixed (the row's subtitle says it). */
    const val MIN_PAUSE_MS = 30_000L

    /**
     * The most the position may differ from where the pause began. More means the viewer seeked while paused, or
     * played and paused again through another path (e.g. a media key): they chose where to resume, so no rewind.
     */
    const val PAUSE_POSITION_TOLERANCE_MS = 1_000L

    /** [positionMs] moved back by [rewindSeconds], clamped at 0; unchanged when either is not positive. */
    fun rewound(positionMs: Long, rewindSeconds: Int): Long =
        if (positionMs <= 0L || rewindSeconds <= 0) {
            positionMs
        } else {
            (positionMs - rewindSeconds * 1_000L).coerceAtLeast(0L)
        }

    /**
     * Where a resume after a user pause starts, or null to play on from where it stands. [pausedAtMs] and
     * [nowMs] are on one monotonic clock (elapsedRealtime); [pausedAtMs] null = no user pause on record.
     */
    fun afterPause(
        pausedAtMs: Long?,
        pausedPositionMs: Long,
        nowMs: Long,
        positionMs: Long,
        rewindSeconds: Int,
    ): Long? {
        if (pausedAtMs == null || rewindSeconds <= 0 || positionMs <= 0L) return null
        if (nowMs - pausedAtMs < MIN_PAUSE_MS) return null
        if (abs(positionMs - pausedPositionMs) > PAUSE_POSITION_TOLERANCE_MS) return null
        return rewound(positionMs, rewindSeconds)
    }
}
