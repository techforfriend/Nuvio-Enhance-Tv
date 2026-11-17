package com.nuvio.tv.livetv.player

import androidx.media3.common.C

/**
 * Live TV's frame-rate rule (Spec 94 T4): a 25 or 50 fps channel ([Family.PAL]) wants the display
 * at 50 Hz, any other standard rate ([Family.OTHER]) the display the viewer had. Pure, so the
 * measuring is testable without a player.
 */
object LiveFrameRate {

    enum class Family { PAL, OTHER }

    /** [fps]'s family; null for a rate that is no broadcast standard (unknown, or a bad measurement). */
    fun family(fps: Float): Family? = when {
        !fps.isFinite() -> null
        fps >= 24.5f && fps <= 25.5f || fps >= 49f && fps <= 51f -> Family.PAL
        fps >= 23.5f && fps < 24.5f || fps >= 29.5f && fps <= 30.5f || fps >= 59f && fps <= 61f -> Family.OTHER
        else -> null
    }

    /**
     * Measures a stream's family from a cumulative frame count (rendered + dropped + skipped, so a
     * struggling decoder still counts its content rate) against media time (the position in the stream's period, which a sliding live window does not move back), sampled during
     * playback: each pair of samples gives a rate, and [agree] readable windows of one family
     * with no other family between them settle it (a dropped pair, a new baseline, does not break the streak). Media time, not wall time: Media3 plays live streams at 0.97-1.03x to hold the
     * live offset, which on the wall clock would read 24 fps as 24.7 (PAL). A pair whose media time
     * did not move with wall time (a stall, a seek to the live edge) is a new baseline.
     * TS streams carry no frame rate in their format, and tunneled playback gives no per-frame
     * timestamps, so counting is the one measure both paths share.
     */
    class Meter(private val agree: Int = 2) {
        private var lastFrames = -1L
        private var lastMediaMs = 0L
        private var lastWallMs = 0L
        private var candidate: Family? = null
        private var streak = 0

        /** Feeds one sample; returns the family once settled, else null. */
        fun sample(frames: Long, mediaMs: Long, wallMs: Long): Family? {
            val prev = lastFrames
            val media = mediaMs - lastMediaMs
            val wall = wallMs - lastWallMs
            lastFrames = frames
            lastMediaMs = mediaMs
            lastWallMs = wallMs
            // First sample, counters restarted (a new renderer enable), or media time off pace.
            if (prev < 0 || frames < prev || media <= 0 || wall <= 0 || media * 4 < wall * 3 || media * 3 > wall * 4) {
                return null
            }
            val f = family((frames - prev) * 1000f / media)
            streak = if (f != null && f == candidate) streak + 1 else if (f != null) 1 else 0
            candidate = f
            return if (streak >= agree) f else null
        }

        /** Playback paused or stalled: the next sample is a new baseline, and the streak starts again. */
        fun reset() {
            lastFrames = -1L
            candidate = null
            streak = 0
        }
    }

    /**
     * Media3's video frame-rate strategy for [matching] (Frame rate matching on): on, the screen switches
     * the display (`LiveDisplayRate`), so Media3's own Surface.setFrameRate stays off (the VOD player's
     * rule, AFR review F6/R4); off, seamless-only matching is free, as before Spec 94.
     */
    fun strategy(matching: Boolean): Int =
        if (matching) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS

    /** What a [Probe] settled: the family and whether the stream declared its rate (for the log). */
    data class Settled(val family: Family, val declared: Boolean)

    /**
     * One stream's measurement (LivePlayer feeds it each sample): the format's declared rate wins
     * when it classifies (HLS `FRAME-RATE`); otherwise the [Meter] counts, restarting whenever
     * there are no counters or playback is not running.
     */
    class Probe(private val meter: Meter = Meter()) {
        fun step(declaredFps: Float?, frames: Long?, playing: Boolean, mediaMs: Long, wallMs: Long): Settled? {
            declaredFps?.takeIf { it > 0f }?.let(::family)?.let { return Settled(it, declared = true) }
            if (frames == null || !playing) {
                meter.reset()
                return null
            }
            return meter.sample(frames, mediaMs, wallMs)?.let { Settled(it, declared = false) }
        }
    }
}
