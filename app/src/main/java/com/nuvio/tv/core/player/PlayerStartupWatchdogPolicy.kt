package com.nuvio.tv.core.player

import com.nuvio.tv.R

/**
 * Startup watchdog decisions (T3, ysosrs 01b6b4bae + 859ecf718): whether a check
 * should extend the watchdog instead of firing, what a fire means given track state,
 * whether a fire tries the next source first (Spec 86 R4) and whether to stop the player
 * after the post-fire grace (Spec 56 R1). Pure logic so the ceiling arithmetic and fire
 * classification stay pinnable.
 */
object PlayerStartupWatchdogPolicy {

    /**
     * Extend instead of firing while buffered-ahead keeps growing and this check
     * landed before the hard ceiling; with a 20 s interval and a 60 s ceiling that
     * grants extends at ~20/~40 s and fires at ~60 s. Growth must be strict: a flat
     * buffer is a stall.
     *
     * The boundary deliberately does not require drift-free timing: `delay()` never
     * undershoots, so a check's elapsed is always a few ms past its nominal mark, and
     * an `elapsed + interval <= ceiling` comparison would deny the ~40 s re-arm and
     * cap the grace window at ~40 s. A pathological check delay (main thread stalled
     * for seconds) can land the last extendable check just under the ceiling, so the
     * latest possible fire is the ceiling plus one interval.
     *
     * [dataArriving] ([waitsForData]) extends on the same terms when the buffer is flat. Both
     * extensions share the ceiling.
     */
    fun shouldExtend(
        elapsedMs: Long,
        bufferedAheadMs: Long,
        lastBufferedAheadMs: Long,
        dataArriving: Boolean = false,
    ): Boolean {
        return (bufferedAheadMs > lastBufferedAheadMs || dataArriving) &&
            elapsedMs < PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS
    }

    /**
     * Spec 86 D3: a start whose tracks are read and whose video is selected keeps waiting while
     * network bytes still arrive, even with nothing buffered ahead yet: a link slightly too slow
     * for the file played 4 s after a 20 s fire (Shield window 2026-11-12, S73 b). The same fact
     * names the fire when the ceiling is reached ([FireReason.DataStillArriving]). A link with no
     * data, unread tracks, a refused or missing video track fire as before.
     */
    fun waitsForData(input: FireInput): Boolean = fireReason(input) == FireReason.DataStillArriving

    /**
     * Spec 86 R4/D3: the source-facing fires try the next source before the error box. A refused
     * video format and a decoder that took data and showed nothing stay on the box here; a start
     * READY with sound and no picture is failed over earlier by the first-frame watchdog (R1/D1,
     * [PlayerFirstFrameCodecRecoveryPolicy]).
     */
    fun failsOver(reason: FireReason): Boolean = when (reason) {
        FireReason.TracksNotRead, FireReason.NoVideoTrack, FireReason.DataStillArriving -> true
        FireReason.VideoTrackUnsupported, FireReason.DecoderUnresponsive -> false
    }

    /**
     * Spec 56 R1: one interval after a surfaced fire, stop the player only if nothing
     * changed the case: still no first frame (a late frame retracts the error instead,
     * T3), the same player instance (a reinit or release replaced it), and the watchdog's
     * own failure still on screen (any other error owns its own player handling).
     */
    fun <T : Any> shouldStopAfterGrace(
        hasRenderedFirstFrame: Boolean,
        samePlayer: Boolean,
        currentError: T?,
        fire: T,
    ): Boolean = !hasRenderedFirstFrame && samePlayer && currentError == fire

    enum class FireReason(val logToken: String) {
        /** onTracksChanged never ran: the container headers never arrived. Source problem. */
        TracksNotRead("tracks_not_read"),

        /** Video group exists but nothing selected: the device rejected the format. */
        VideoTrackUnsupported("video_track_unsupported"),

        /** Tracks read, no video group at all: trackless stream. Source problem. */
        NoVideoTrack("no_video_track"),

        /**
         * Spec 86 R9/D3: tracks read, video selected, network bytes still arriving, no frame: the
         * link is too slow for the file (the Shield window's start had nothing buffered at 36 of
         * 38 Mbit/s). Source problem.
         */
        DataStillArriving("data_still_arriving"),

        /**
         * Tracks read, video selected, no frame and no bytes arriving (or unknown, mpv): the
         * original decoder wedge (the Codec2 wedge blocks the playback thread, so loading stops).
         */
        DecoderUnresponsive("decoder_unresponsive"),
    }

    /**
     * [networkBytesGrew]: network bytes arrived since the last check; null = unknown (mpv fetches
     * outside the byte counter).
     */
    data class FireInput(
        val hasScannedTracksOnce: Boolean,
        val streamHasVideoTrack: Boolean,
        val videoTrackSelected: Boolean,
        val networkBytesGrew: Boolean? = null,
    )

    /**
     * Order matters: checked first, so stale track flags from a previous stream can
     * never masquerade as decoder blame when the new stream's headers never arrived.
     */
    fun fireReason(input: FireInput): FireReason = when {
        !input.hasScannedTracksOnce -> FireReason.TracksNotRead
        input.streamHasVideoTrack && !input.videoTrackSelected -> FireReason.VideoTrackUnsupported
        !input.streamHasVideoTrack -> FireReason.NoVideoTrack
        input.networkBytesGrew == true -> FireReason.DataStillArriving
        else -> FireReason.DecoderUnresponsive
    }

    /** The source-facing reasons (no tracks, no video track, slow data) share one string. */
    fun messageRes(reason: FireReason): Int = when (reason) {
        FireReason.VideoTrackUnsupported -> R.string.player_error_startup_video_track_unsupported
        FireReason.TracksNotRead, FireReason.NoVideoTrack, FireReason.DataStillArriving ->
            R.string.player_error_startup_no_stream_data
        FireReason.DecoderUnresponsive -> R.string.player_error_startup_timeout
    }
}
