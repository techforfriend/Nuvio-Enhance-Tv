package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * The ExoPlayer `onPlayerError` recovery ladder as one ordered table (Spec 21 P1 / 11/S2a).
 *
 * [classify] reads the error, the controller snapshots its per-stream latches into [StreamState],
 * and [route] returns the rungs to try in order. The controller (`executeErrorRecoveryRung`,
 * PlayerRuntimeControllerErrorRecovery; the rung-to-function mapping has no harness, review guards
 * it) runs them until one takes the error, else it surfaces
 * the terminal error. Between [Route.Background] and the ladder the controller spends one unit of
 * the nt6 fix B budget and tries the P5 bitstream same-config retry; a spent budget skips the
 * whole ladder.
 *
 * Rungs gated here are decided from the snapshot. The self-gated rungs (audio ladder, denied
 * FFmpeg, PCM, dead source, parsing probe, mid-play and startup failover, auto-retry) keep their
 * own latches in their controller functions and may decline. The snapshot stays valid for the
 * whole walk: a rung that changes a snapshotted fact (mode-1 URL set, 416 latch, engine-hop URL
 * set, the Spec 86 link-refresh URL set) always takes the error, and [route] ends the list at it; a rung that can decline must leave every [StreamState]
 * fact untouched.
 *
 * Spec 21 P8 (Q2 latch table) removed the inherited timeout, loader-NPE and MediaPeriodHolder
 * same-URL retries (string matches with no evidence; their latches reset on the rebuild they
 * triggered, so they repeated until the budget ran out and never reached the auto-retry or
 * next-source failover) and the Exo-ladder engine failover (MPV -> Exo only, so it always declined
 * on an Exo error). Those errors now take the generic rungs below.
 *
 * Spec 85 G5 adds [Rung.MPV_FORMAT_HOP]: a file ExoPlayer cannot open or decode at all restarts on
 * mpv, after the rungs that could still keep it on ExoPlayer.
 *
 * Spec 86 R6 adds [Route.LiveEdge]: behind the live window, re-anchor in place first, outside the
 * budget, as the Live TV ladder does ([com.nuvio.tv.livetv.player.LiveTuneLadder]). Spec 86 R2/D2
 * adds the mid-play rungs: [Rung.MID_PLAY_LINK_REFRESH] for a refused link, then the dead-source
 * failover; [Rung.MID_PLAY_SERVER_FAILOVER] for a 5xx / 429 storm after the same-link retries.
 */
internal object PlayerErrorRecoveryPolicy {

    enum class Rung {
        DV_MODE1_AFTER_DECODER_FAILURE,
        DV_HDR10_AFTER_DECODER_FAILURE,
        DV_MODE1_AFTER_RUNTIME_CHECK,
        DV_HDR10_AFTER_RUNTIME_CHECK,
        AUDIO_LADDER_RENDERER_FAULT,
        DENIED_AUDIO_FFMPEG,
        AUDIO_TRACK_PCM,
        AUDIO_LADDER_AUDIO_TRACK,
        AUDIO_LADDER_STUCK_NO_PROGRESS,
        RANGE_416_FROM_START,
        DEAD_SOURCE_HTTP_FAILOVER,
        PARSING_PROBE,
        MID_PLAY_LINK_REFRESH,
        MID_PLAY_CORRUPT_FAILOVER,
        AUTO_RETRY,
        MID_PLAY_SERVER_FAILOVER,
        MPV_FORMAT_HOP,
        STARTUP_EXHAUSTED_FAILOVER,
    }

    /** What the error is, read once from the exception. */
    data class ErrorTraits(
        val retryable: Boolean,
        val dolbyVisionDecoderFailure: Boolean,
        /**
         * 8000 FAILED_RUNTIME_CHECK from a renderer/extractor assertion. Media3's stuck-buffering
         * watchdog shares the code but is a loading deadlock, not a decoder or audio fault (and
         * carries no renderer format, so it would read as an audio failure): it is excluded here,
         * skips the DV and audio rungs and takes the same-link auto-retry (Register A2 P2).
         */
        val runtimeCheckFault: Boolean,
        val decodingFailed: Boolean,
        /** The failing renderer's format is audio, or the error names no renderer format. */
        val audioRendererFailed: Boolean,
        val audioTrackFailure: Boolean,
        val stuckPlayingNoProgress: Boolean,
        /** HTTP 416 as the direct cause. */
        val rangeNotSatisfiable: Boolean,
        /** HTTP 404/410 anywhere in the cause chain. */
        val deadSourceHttp: Boolean,
        /** 3001 malformed container, or 2000 whose cause chain has no connection-level failure. */
        val containerCorruption: Boolean,
        /**
         * Spec 85 G5: no decoder takes the video (4001 init, 4002 query, 4004 exceeds
         * capabilities, 4005 unsupported) on a video renderer format that is not Dolby Vision.
         * 4003 DECODING_FAILED is a decoder that started, so it stays out (D1).
         */
        val videoDecoderUnsupported: Boolean,
        /** 3003 from a container sniff no extractor matched ([isUnrecognizedContainer]). */
        val unrecognizedContainer: Boolean,
        /** 1002: the playhead fell behind a live window (an HLS live or sliding playlist). */
        val behindLiveWindow: Boolean,
        /** Spec 86 R2: HTTP 400/401/403/410 in the cause chain (an expired or blocked debrid link). */
        val linkRefusedHttp: Boolean,
        /** Spec 86 R2/D2: HTTP 429 or 5xx in the cause chain (a busy or failing server). */
        val serverErrorHttp: Boolean,
    )

    /** The controller's per-stream latches at the moment of the error. */
    data class StreamState(
        val inBackground: Boolean,
        val mapDv7ToHevcActive: Boolean,
        val manualDv81Mode2Active: Boolean,
        val experimentalDv7ToDv81Active: Boolean,
        val dv7Mode1AlreadyForced: Boolean,
        /** The 416 retry-from-start already ran for this URL (reset on a switch, not by its own rebuild). */
        val rangeNotSatisfiableRetried: Boolean,
        val renderedFirstFrame: Boolean,
        /** Spec 85 G2: this ExoPlayer start may still hop to mpv (`mpvFormatHopAvailable`). */
        val mpvHopAvailable: Boolean,
        /** Spec 86 R6: fewer than [MAX_LIVE_EDGE_FIXES] live-edge re-anchors ran for this stream. */
        val liveEdgeFixAvailable: Boolean,
        /** Spec 86 R2: this URL had no mid-play link refresh yet and is not a torrent. */
        val linkRefreshAvailable: Boolean,
    )

    sealed interface Route {
        /** Free the decoder and rebuild paused on resume; no ladder, no budget. */
        data object Background : Route

        /**
         * Spec 86 R6: `seekToDefaultPosition(); prepare()` in place (Media3's documented handling of
         * ERROR_CODE_BEHIND_LIVE_WINDOW, live-streaming guide); no probe, no ladder, no budget unit.
         */
        data object LiveEdge : Route

        data class Ladder(val rungs: List<Rung>) : Route
    }

    /**
     * Spec 86 R6: live-edge re-anchors per stream before the ladder takes 1002 (the Live TV ladder's
     * MAX_BLW_FIXES, the same bound); the stable-progress reset refills them.
     */
    const val MAX_LIVE_EDGE_FIXES = 2

    fun route(error: ErrorTraits, state: StreamState): Route {
        // A codec crash while backgrounded (another app reclaimed the hardware decoder): each
        // retry would re-acquire a decoder the foreground app reclaims again, burning the budget
        // into an unrecoverable 4003 by the time the user returns.
        if (state.inBackground && error.retryable) return Route.Background
        if (error.behindLiveWindow && state.liveEdgeFixAvailable) return Route.LiveEdge
        return Route.Ladder(ladder(error, state))
    }

    private fun ladder(error: ErrorTraits, state: StreamState): List<Rung> = buildList {
        if (!state.mapDv7ToHevcActive) {
            // DV decoder failure: manual Convert-to-DV8.1 mode 2 tries libdovi mode 1 once,
            // then HDR10.
            if (error.dolbyVisionDecoderFailure) {
                add(
                    if (state.manualDv81Mode2Active && !state.dv7Mode1AlreadyForced) {
                        Rung.DV_MODE1_AFTER_DECODER_FAILURE
                    } else {
                        Rung.DV_HDR10_AFTER_DECODER_FAILURE
                    }
                )
                return@buildList
            }
            // DV conversion armed and an 8000: the converted bitstream trips a renderer/extractor
            // assertion before the codec reports a decoding failure. A video-path failure, so the
            // DV ladder, not the audio fallbacks (they rebuild with the same broken conversion).
            if (error.runtimeCheckFault &&
                (state.experimentalDv7ToDv81Active || state.manualDv81Mode2Active)
            ) {
                add(
                    if (state.manualDv81Mode2Active && !state.dv7Mode1AlreadyForced) {
                        Rung.DV_MODE1_AFTER_RUNTIME_CHECK
                    } else {
                        Rung.DV_HDR10_AFTER_RUNTIME_CHECK
                    }
                )
                return@buildList
            }
        }
        // Audio review F5: the audio ladder only when the audio renderer failed; a video decoder
        // crash must not end in silent video. No autoSwitchInternalPlayerOnError gate: no engine
        // switch takes an ExoPlayer audio decoder error, so this is its only recovery.
        if ((error.decodingFailed || error.runtimeCheckFault) && error.audioRendererFailed) {
            add(Rung.AUDIO_LADDER_RENDERER_FAULT)
        }
        // F5 root cause: 4001 on a policy-denied audio format (hybrid MIME upgraded mid-stream)
        // rebuilds with FFmpeg audio preferred. Self-gated on 4001.
        add(Rung.DENIED_AUDIO_FFMPEG)
        // Audio review F6: the gentler PCM-forcing rebuild for a 5001 before the safe-audio
        // ladder. Self-gated (5001, first attempt, extension mode on, tunneling off).
        add(Rung.AUDIO_TRACK_PCM)
        // AudioTrack init (5001) or write (5002, e.g. DEAD_OBJECT on a passthrough track).
        if (error.audioTrackFailure) add(Rung.AUDIO_LADDER_AUDIO_TRACK)
        if (error.stuckPlayingNoProgress) add(Rung.AUDIO_LADDER_STUCK_NO_PROGRESS)
        if (error.rangeNotSatisfiable && !state.rangeNotSatisfiableRetried) {
            add(Rung.RANGE_416_FROM_START)
            return@buildList
        }
        // Spec 86 R2: after the first frame a refused link (an expired or blocked debrid link) is
        // refreshed once: the source list again, the same row replayed at the position. The rung
        // spends this URL's refresh (a snapshotted fact), so it ends the list.
        val refusedMidPlay = state.renderedFirstFrame && error.linkRefusedHttp
        if (refusedMidPlay && state.linkRefreshAvailable) {
            add(Rung.MID_PLAY_LINK_REFRESH)
            return@buildList
        }
        // Dead-source HTTP arm (community 3003 report): 404/410 is permanent for this URL, so
        // advance now instead of burning same-URL retries; after the first frame also a link
        // refused again after its refresh (R2).
        if (refusedMidPlay) {
            // Refused again after its refresh's first frame: no same-URL retry (a PRDS-wrapped 403
            // reads as a retryable 2000); the next source or the box. A replay refused at its open
            // is a startup error and takes the startup ladder.
            add(Rung.DEAD_SOURCE_HTTP_FAILOVER)
            return@buildList
        }
        if (error.deadSourceHttp) add(Rung.DEAD_SOURCE_HTTP_FAILOVER)
        // 0.8.5 parsing-error probe: a mislabeled container is rescuable; its probe-null branch
        // holds the sniff dead-source arm (non-media body behind HTTP 200, .rar/.zip).
        add(Rung.PARSING_PROBE)
        // Spec 85 G5/G6: a 3003 once PARSING_PROBE is spent for this stream (by this 3003's tail,
        // which hops itself, or by an earlier parsing/IO error): mpv may open the container. With
        // no server probe here, a non-media body costs one mpv start before it ends as NO_START.
        // The hop always takes the error, so it ends the list.
        if (error.unrecognizedContainer && state.mpvHopAvailable) {
            add(Rung.MPV_FORMAT_HOP)
            return@buildList
        }
        // T2 (ysosrs cad15d29e): mid-play corruption will not recover on a same-URL re-prepare
        // (auto-retry cue-seeks back into it). PRDS chunk losses arrive as 2001/2002; a 2000 with
        // a connection-level cause is a lost connection and takes the auto-retry (Register A2 P3).
        if (state.renderedFirstFrame && error.containerCorruption) add(Rung.MID_PLAY_CORRUPT_FAILOVER)
        add(Rung.AUTO_RETRY)
        // Spec 86 D2: a mid-play 5xx or a 429/503 storm's fatal, once the two same-link retries are
        // spent, goes to the next source at the position. A dropped connection (no HTTP status)
        // and a decode error keep today's handling.
        if (state.renderedFirstFrame && error.serverErrorHttp) add(Rung.MID_PLAY_SERVER_FAILOVER)
        // Spec 85 G5: the same-link retries first, so a transient decoder-init failure stays on
        // ExoPlayer; then mpv, before the next source.
        if (error.videoDecoderUnsupported && state.mpvHopAvailable) {
            add(Rung.MPV_FORMAT_HOP)
            return@buildList
        }
        // Task 1.6: a startup failure that exhausted the ladders and both auto-retries tries the
        // next source before the error screen. Self-gated on no first frame.
        add(Rung.STARTUP_EXHAUSTED_FAILOVER)
    }

    fun classify(error: PlaybackException): ErrorTraits {
        val code = error.errorCode
        val chain = error.causeChainText()
        val rendererFormat = (error as? ExoPlaybackException)?.rendererFormat
        val rendererMime = rendererFormat?.sampleMimeType
        val dolbyVisionDecoderFailure = error.isDolbyVisionDecoderFailure(rendererMime, chain)
        val httpCode = error.findInvalidResponseCodeException()?.responseCode
        return ErrorTraits(
            retryable = isRetryablePlaybackError(error),
            dolbyVisionDecoderFailure = dolbyVisionDecoderFailure,
            runtimeCheckFault = code == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                !isStuckBufferingWatchdog(code, chain),
            decodingFailed = code == PlaybackException.ERROR_CODE_DECODING_FAILED,
            audioRendererFailed = rendererMime == null || MimeTypes.isAudio(rendererMime),
            audioTrackFailure = isAudioTrackFailure(code, chain),
            stuckPlayingNoProgress = code == PlaybackException.ERROR_CODE_TIMEOUT &&
                chain.contains("stuck playing with no progress", ignoreCase = true),
            rangeNotSatisfiable = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 416,
            deadSourceHttp = isDeadSourceHttpError(error),
            // Spec 86 D2: a PRDS chunk refused with 429/5xx arrives as a plain 2000 (an HTTP status
            // is not a network cause): a busy server, not a corrupt file.
            containerCorruption = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                (code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && !PlaybackNetworkFailure.hasNetworkCause(error) &&
                    !isServerErrorHttp(httpCode)),
            // Never Dolby Vision (D1): by the failing format, or by the DV verdict above (it also
            // reads a DV decoder named in the message under an HEVC mime, as with map-to-HEVC).
            videoDecoderUnsupported = code in VIDEO_DECODER_UNSUPPORTED_CODES &&
                MimeTypes.isVideo(rendererMime) &&
                !dolbyVisionDecoderFailure &&
                !MpvEngineHopPolicy.isDolbyVision(rendererMime, rendererFormat?.codecs),
            unrecognizedContainer = error.isUnrecognizedContainer(),
            behindLiveWindow = code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            linkRefusedHttp = httpCode in LINK_REFUSED_HTTP_CODES,
            serverErrorHttp = isServerErrorHttp(httpCode),
        )
    }

    /** Spec 86 R2: the refusals worth a fresh link (404 stays dead-source only). */
    private val LINK_REFUSED_HTTP_CODES = DEAD_LINK_HTTP_RESPONSE_CODES - 404

    private fun isServerErrorHttp(code: Int?): Boolean = code == 429 || code in 500..599

    private val VIDEO_DECODER_UNSUPPORTED_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    private fun PlaybackException.isDolbyVisionDecoderFailure(rendererMime: String?, chain: String): Boolean {
        // DV7 review F7: media3 surfaces failure-to-instantiate as ERROR_CODE_DECODER_INIT_FAILED
        // ("Decoder init failed..."), which the message match below never catches, so the
        // mode2 -> mode1 -> HDR10 ladder never fired on devices whose DV decoder exists but refuses
        // to start (the hidden/broken-licence Amlogic case DolbyVisionCodecFallback documents).
        // Match init failures by code + the failing format's DV MIME.
        if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            if (rendererMime?.contains("dolby-vision", ignoreCase = true) == true) return true
            // A Dolby audio decoder (c2.dolby.*) names "dolby" too; it takes the audio ladder.
            if (rendererMime != null && MimeTypes.isAudio(rendererMime)) return false
            // Init-failure messages name the codec; catch DV decoders that way too.
            val initDetails = (message ?: "") + ' ' + (cause?.message ?: "")
            return initDetails.contains("dvhe", ignoreCase = true) ||
                initDetails.contains("dvh1", ignoreCase = true) ||
                initDetails.contains("dolby", ignoreCase = true)
        }
        if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
        return chain.contains("dolby-vision", ignoreCase = true) && chain.contains("decoder failed", ignoreCase = true)
    }
}
