package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.FailedPlayback
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import com.nuvio.tv.core.stream.FailureReason
import com.nuvio.tv.core.stream.StreamTextSizeParser
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_AUTO_RETRIES = 2
private const val MAX_DEAD_SOURCE_FAILOVERS = 3

/** How long the empty-list dead-source arm waits for the (cached) source list. */
private const val SOURCE_LIST_WAIT_MS = 15_000L

/**
 * Waits for the in-player source list to become available for a dead-source
 * failover. Replaces the old single-predicate wait, which read the PRE-load
 * state (not loading yet, list empty) as "completed with nothing" and gave up
 * in the same millisecond it started the load — while the scrape it never
 * waited for landed seconds later with dozens of failover candidates
 * (2026-09-03, Arshad's 404 report: the failover surrendered instantly on
 * every attempt). Two phases: the load must START (or a cached list appear),
 * then FINISH (list non-empty, or loading ended). Null on timeout.
 */
internal suspend fun awaitSourceListForFailover(
    states: StateFlow<PlayerUiState>,
    timeoutMs: Long = SOURCE_LIST_WAIT_MS
): PlayerUiState? = withTimeoutOrNull(timeoutMs) {
    val loadingStarted = states.first {
        it.isLoadingSourceStreams || it.sourceAllStreams.isNotEmpty()
    }
    if (loadingStarted.sourceAllStreams.isNotEmpty()) {
        loadingStarted
    } else {
        states.first { it.sourceAllStreams.isNotEmpty() || !it.isLoadingSourceStreams }
    }
}

// nt6 fix B: ceiling on TOTAL automatic recoveries for one stream URL (between
// stable-progress windows, which zero it) across the rungs of the ExoPlayer
// onPlayerError path (e.g. DV modes, bitstream retry, safe audio, PCM, 416, parsing
// probe, auto-retry; order: PlayerErrorRecoveryPolicy). A failover to another URL (dead-source,
// mid-play, startup-exhausted) is itself a budgeted rung there, but the next URL
// starts a fresh budget because the counter keys on the URL. Outside it, each
// bounded by its own latch: the background-crash branch (runs before the gate),
// the first-frame watchdog's codec rungs, the MPV path, and the startup failover
// taken when initializePlayer itself throws. Each ladder is individually bounded, but chained they observably looped a
// dead decoder pipeline for minutes — ~40 s per cycle on a large moov-at-tail
// MP4. Five covers the deepest legitimate chain while bounding the pathological case.
private const val MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM = 5
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCause<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code in DEAD_LINK_HTTP_RESPONSE_CODES)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        // Media3's stuck-buffering watchdog: a loading deadlock, re-prepared on the same link.
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> error.isStuckBufferingWatchdog()

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

/**
 * The subset of [isAudioTrackFailure] that is a refused AudioTrack open (5001, or the init
 * message under a generic code): the only failure F3 may learn from. A 5002 write failure is a
 * track that opened and later died (DEAD_OBJECT on an HDMI renegotiation), so it says nothing
 * about whether the receiver takes the format (Register A2 P1).
 */
internal fun isAudioTrackInitFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return false
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true)
}

/**
 * Media3's stuck-buffering watchdog ("Playback stuck buffering and not loading", an
 * IllegalStateException surfaced as 8000 FAILED_RUNTIME_CHECK): the player sat buffering with a
 * near-empty buffer while the LoadControl had stopped loading (a deadlock, e.g. a byte target
 * met by one track; see the dvmkv MatroskaExtractor note). Not a decoder or audio fault: it takes
 * the same-link auto-retry (a fresh player on the second attempt), never the DV-conversion or
 * audio fallbacks, which would drop the stream to the HDR10 base layer or turn passthrough off
 * for nothing (upstream-sync Register A2 P2; upstream `0954ef772` guards the DV arm only).
 */
internal fun isStuckBufferingWatchdog(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
    return combinedMessage.contains("stuck buffering and not loading", ignoreCase = true)
}

internal fun PlaybackException.isStuckBufferingWatchdog(): Boolean =
    isStuckBufferingWatchdog(errorCode, causeChainText())

/** message, cause.message and cause.cause.message joined by spaces, for message matching. */
internal fun PlaybackException.causeChainText(): String = buildString {
    append(message ?: "")
    append(' ')
    append(cause?.message ?: "")
    append(' ')
    append(cause?.cause?.message ?: "")
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            400 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            401 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            403 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
            410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
            500, 502, 503, 504 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    // Check for codec/renderer errors
    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(com.nuvio.tv.R.string.player_error_decoder)
        val unsupported = context.getString(com.nuvio.tv.R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails. A track
 * that needs a warm decoder (Spec 57 D2) rebuilds on both: the error released its codec.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    // Dead URLs (non-media body, 404/410) never benefit from same-URL retries; the dead-source
    // HTTP rung and the parsing probe's tail (mime-clear retry, mpv hop, DEAD_LINK) handle them.
    if (isDeadSourcePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0
    // Spec 57 D2: an in-place re-prepare after the error released the codec enters it cold at the
    // position; on a track that needs a warm decoder only the full rebuild (which primes) recovers.
    val inPlace = isFirstAttempt && !videoNeedsWarmDecoder

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (inPlace) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (inPlace) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused. Spec 62 H1: not inside an AFR
                // hold either; the gate's own release starts it.
                player.playWhenReady = !paused && !afrStartGateHeld
            } else {
                rebuildCurrentStream("auto-retry", savedPosition, paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            rebuildCurrentStream("auto-retry", savedPosition, paused)
        }
    }
    return true
}

/**
 * Resets the per-stream retry state. Called after stable progress
 * (scheduleStableProgressReset), on a manual engine switch, and when a new
 * stream or episode starts. Automatic paths pass
 * [resetDeadSourceFailovers] = false: a dead-source failover (or a recovery on
 * the source it landed on) must not refill the MAX_DEAD_SOURCE_FAILOVERS cap it
 * is drawing on, or the cap never trips.
 */
internal fun PlayerRuntimeController.resetErrorRetryState(resetDeadSourceFailovers: Boolean = true) {
    errorRetryCount = 0
    if (resetDeadSourceFailovers) {
        deadSourceFailoverCount = 0
        failedOverSources = emptyList()
        lastRecordedFailure = null
        _uiState.update { it.copy(failoverNotice = null, exhaustedFailover = null) }
    }
    hasRetriedAfterMimeOverrideClear = false
    parsingErrorProbeAttempted = false
    liveEdgeFixCount = 0
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

/**
 * Spec 86 R6 ([PlayerErrorRecoveryPolicy.Route.LiveEdge]): the playhead fell behind a live window
 * (1002), so re-anchor at the window's default position and prepare the same player in place, as
 * Media3's live-streaming guide does; [PlayerErrorRecoveryPolicy.MAX_LIVE_EDGE_FIXES] per stream.
 */
internal fun PlayerRuntimeController.recoverBehindLiveWindow(player: Player) {
    liveEdgeFixCount++
    Log.w(
        PlayerRuntimeController.TAG,
        "BEHIND_LIVE_WINDOW: re-anchoring at the live edge " +
            "($liveEdgeFixCount/${PlayerErrorRecoveryPolicy.MAX_LIVE_EDGE_FIXES}) host=${currentStreamUrl.safeHost()}"
    )
    player.seekToDefaultPosition()
    player.prepare()
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        // A frozen picture is READY and playing too: without the moving-position check every frozen
        // source would refill the failover cap and the freeze failover could walk the whole list.
        if (player.playbackState == Player.STATE_READY && player.isPlaying &&
            playbackAnalyticsDiagnostics.isPositionMoving()
        ) {
            resetErrorRetryState()
            // Healthy playback ends a recovery chain: a later, independent error
            // gets a fresh nt6 fix B budget instead of inheriting this one.
            autoRecoveryCountForCurrentStream = 0
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/** The safe-audio ladder's rungs, in order (see [tryAudioRecoveryLadder]). */
internal enum class AudioRecoveryRung { SAFE_AUDIO, PCM, AUDIO_OFF }

/**
 * The next rung, or null when the ladder is spent. Audio off is terminal: [pcmTried] is per player
 * build (initializePlayer clears it unless a PCM rebuild is pending), so after the audio-off
 * rebuild it reads false again and must not buy a pointless muted PCM rebuild.
 */
internal fun nextAudioRecoveryRung(
    safeAudioActive: Boolean,
    pcmTried: Boolean,
    audioDisabled: Boolean
): AudioRecoveryRung? = when {
    audioDisabled -> null
    !safeAudioActive -> AudioRecoveryRung.SAFE_AUDIO
    !pcmTried -> AudioRecoveryRung.PCM
    else -> AudioRecoveryRung.AUDIO_OFF
}

/**
 * The audio-failure ladder (decode failure, AudioTrack failure, stuck playing): safe audio, then
 * safe audio with the FFmpeg software (PCM) decoder, then audio off so video keeps playing.
 * Schedules the rebuild and returns true, or false once every rung was tried. The PCM rung arms
 * [PlayerRuntimeController.pendingAudioPcmFallbackRebuild] as [tryAudioTrackPcmFallback] does:
 * without it `initializePlayer` cleared the flag, the rung replayed plain safe audio and the
 * recovery budget ran out before audio-off (DRDYK B1). The arming is not unit-pinned (no controller
 * harness); this KDoc and tryAudioTrackPcmFallback are its guard.
 */
internal fun PlayerRuntimeController.tryAudioRecoveryLadder(fromPositionMs: Long): Boolean {
    val rung = nextAudioRecoveryRung(
        safeAudioActive = isSafeAudioModeActiveForCurrentPlayback,
        pcmTried = hasTriedAudioPcmFallback,
        audioDisabled = isAudioDisabledForCurrentPlayback
    ) ?: return false
    when (rung) {
        AudioRecoveryRung.SAFE_AUDIO -> {
            safeAudioForcedStreamUrls.add(currentStreamUrl)
            scheduleDeferredPlayerReinitialize("safe audio", fromPositionMs)
        }
        AudioRecoveryRung.PCM -> {
            hasTriedAudioPcmFallback = true
            pendingAudioPcmFallbackRebuild = true
            scheduleDeferredPlayerReinitialize("safe audio PCM", fromPositionMs)
        }
        AudioRecoveryRung.AUDIO_OFF -> {
            audioDisabledForcedStreamUrls.add(currentStreamUrl)
            scheduleDeferredPlayerReinitialize("audio off", fromPositionMs)
        }
    }
    return true
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Rebuilds the player at the current position with PCM output forced
 * (pendingAudioPcmFallbackRebuild, read by the next initializePlayer) before the safe-audio ladder.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = exoCarryPositionMs(player).takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        rebuildCurrentStream("audio PCM fallback", savedPosition, paused)
    }

    return true
}

/**
 * Same-configuration AudioTrack-open retries for one stream (upstream-sync Register A2 P5):
 * [MAX_ATTEMPTS] per stream URL, refilled once [REFILL_AFTER_MS] pass after the last retry's
 * rebuild (checked lazily at the next refusal). Upstream `PlaybackRecoveryGate` (`0954ef772`).
 */
internal class BitstreamAudioRetryBudget {
    private var streamUrl: String? = null
    private var used = 0
    private var lastRebuildAtMs = 0L

    /** The 1-based attempt number when a retry is available for [url], else null. */
    fun take(url: String, nowMs: Long): Int? {
        if (url != streamUrl) {
            streamUrl = url
            used = 0
        } else if (used > 0 && nowMs - lastRebuildAtMs >= REFILL_AFTER_MS) {
            used = 0
        }
        if (used >= MAX_ATTEMPTS) return null
        used++
        lastRebuildAtMs = nowMs
        return used
    }

    /** Restarts the refill clock when the retry's rebuild starts (after any output wait). */
    fun noteRebuild(nowMs: Long) {
        lastRebuildAtMs = nowMs
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val REFILL_AFTER_MS = 60_000L
        const val POLL_MS = 500L
        const val MAX_WAIT_MS = 30_000L

        fun delayMs(attempt: Int): Long = 400L * attempt
    }
}

/**
 * Whether a failure is a refused open of a bitstream the policy lets through: the only case a
 * same-configuration retry can fix (the receiver or HDMI link was briefly not ready). A PCM sink
 * input or a denied format already decodes; a 5002 is a track that opened.
 */
internal fun isBitstreamSameConfigRetryCandidate(
    errorCode: Int,
    combinedMessage: String,
    sinkInputMime: String?,
    policyDeniesMime: Boolean
): Boolean = isAudioTrackInitFailure(errorCode, combinedMessage) &&
    AudioTrackRejectionLog.labelForMime(sinkInputMime) != null &&
    !policyDeniesMime

/**
 * Whether the wait before a bitstream retry is over: the output claims the format again, media
 * moved to a non-HDMI output (that receiver is not coming back for this play), or the cap passed.
 */
internal fun bitstreamRetryWaitOver(outputClaimsFormat: Boolean, routeKey: String?, waitedMs: Long): Boolean =
    outputClaimsFormat ||
        (routeKey != null && !com.nuvio.tv.core.player.AudioOutputRouteDetector.isHdmiRouteKey(routeKey)) ||
        waitedMs >= BitstreamAudioRetryBudget.MAX_WAIT_MS

/**
 * P5: a 5001 on a bitstream the policy allows rebuilds the player with the SAME configuration
 * (up to two times, see [BitstreamAudioRetryBudget]) before the PCM-forced rebuild, after
 * waiting for the output to come back: a rebuild while HDMI is gone pins PCM-only capabilities
 * for the rest of the title. Keeps lossless audio through receiver/HDMI-CEC blips.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryBitstreamAudioTrackSameConfigRetry(error: PlaybackException): Boolean {
    val format = failedAudioTrackInputFormat(error) ?: return false
    val mime = format.sampleMimeType
    val eligible = isBitstreamSameConfigRetryCandidate(
        errorCode = error.errorCode,
        combinedMessage = error.causeChainText(),
        sinkInputMime = mime,
        policyDeniesMime = currentAudioPassthroughPolicy?.deniesPassthrough(mime) == true
    )
    if (!eligible) return false
    val player = _exoPlayer ?: return false
    val url = currentStreamUrl
    val attempt = bitstreamAudioRetryBudget.take(url, android.os.SystemClock.elapsedRealtime()) ?: return false
    val savedPosition = exoCarryPositionMs(player).takeIf { it > 0L } ?: 0L
    val paused = userPausedManually
    val delayMs = BitstreamAudioRetryBudget.delayMs(attempt)
    Log.w(
        PlayerRuntimeController.TAG,
        "AUDIO_BITSTREAM_RETRY attempt=$attempt/${BitstreamAudioRetryBudget.MAX_ATTEMPTS} mime=$mime " +
            "delayMs=$delayMs position=${savedPosition}ms"
    )
    showRecoveryOverlay()
    seedTrackPreferenceCarryForPlayerRebuild()
    // Release synchronously (what the inline Main.immediate launch elsewhere amounts to), THEN
    // start the wait: releasePlayer cancels errorRetryJob, and this job must stay in it so exit,
    // a new stream, an engine switch or another release cancel the wait (review 193037 #1).
    errorRetryJob?.cancel()
    releasePlayer(flushPlaybackState = false)
    if (savedPosition > 0L) {
        _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
    }
    errorRetryJob = scope.launch {
        delay(delayMs)
        awaitAudioOutputForBitstreamRetry(format)
        if (currentStreamUrl != url) return@launch
        if (isInBackground) {
            // Same deferral as onPlayerError's background branch: no decoder grab while away;
            // resumeForLifecycle rebuilds paused at the saved position.
            backgroundCrashSavedPositionMs = savedPosition
            pendingBackgroundCrashRecovery = true
            return@launch
        }
        bitstreamAudioRetryBudget.noteRebuild(android.os.SystemClock.elapsedRealtime())
        initializePlayer(
            url,
            currentHeaders,
            overrideInternalPlayerEngine = currentStreamEngineForRestart(),
            startPaused = paused || userPausedManually
        )
    }
    return true
}

private suspend fun PlayerRuntimeController.awaitAudioOutputForBitstreamRetry(format: androidx.media3.common.Format) {
    val startedMs = android.os.SystemClock.elapsedRealtime()
    var waiting = false
    while (true) {
        val waitedMs = android.os.SystemClock.elapsedRealtime() - startedMs
        val routeKey = detectAudioRouteKey()
        if (bitstreamRetryWaitOver(liveOutputClaimsPassthrough(format), routeKey, waitedMs)) {
            if (waiting) {
                // The output was gone and the route callback was unregistered with the player:
                // stand in for the device events it missed, so a refusal right after the
                // output returns reads ROUTE_CHANGING in the F3 outage guard (review 193037 #2).
                lastAudioDeviceChangeAtMs = android.os.SystemClock.elapsedRealtime()
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY wait over after ${waitedMs}ms route=$routeKey")
            }
            return
        }
        if (!waiting) {
            Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY waiting for the audio output route=$routeKey")
            waiting = true
        }
        delay(BitstreamAudioRetryBudget.POLL_MS)
    }
}

/**
 * FFmpeg-preferred rebuild for ERROR_CODE_DECODER_INIT_FAILED (4001) on an audio
 * renderer whose failing format belongs to a policy-denied group.
 *
 * Root cause (F5 investigation, 5 Aug 2026): a hybrid track (e.g. DTS-HD MA in
 * Matroska) is exposed at selection time under its base MIME (audio/vnd.dts),
 * which the user may not have denied, so the MediaCodec audio renderer wins the
 * mapping tie. When the sample pipeline reads the extension substream it upgrades
 * the format mid-stream to the denied MIME (audio/vnd.dts.hd); the policy
 * abdication then leaves the already-selected renderer with no decoder (-49999),
 * and media3 never remaps a track mid-stream, so the generic retry rebuilds into
 * the identical trap. Retrying with FFmpeg audio preferred (the same audio-local
 * reorder Force AC-3 uses) makes FFmpeg win the tie for the whole family; it
 * decodes both the base and upgraded formats.
 *
 * Deliberate trade-off: while active (this stream only), FFmpeg wins ties for
 * every audio format it fully supports, so a second audio track that could have
 * passed through decodes to PCM instead. Degraded-but-playing beats the error
 * screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDeniedAudioFfmpegFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (currentStreamUrl in preferFfmpegAudioStreamUrls) return false
    if (cachedDecoderPriority == 0) return false // No FFmpeg renderer without extensions.
    val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
        ?.rendererFormat?.sampleMimeType
    if (failingMime == null || !androidx.media3.common.MimeTypes.isAudio(failingMime)) return false
    val policy = currentAudioPassthroughPolicy ?: return false
    if (!policy.deniesPassthrough(failingMime)) return false

    preferFfmpegAudioStreamUrls.add(currentStreamUrl)

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4001) on policy-denied audio $failingMime - retrying with FFmpeg audio preferred, position=${savedPosition}ms"
    )

    resetErrorRetryState(resetDeadSourceFailovers = false)

    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        rebuildCurrentStream("denied audio FFmpeg", savedPosition, paused)
    }
    return true
}

internal fun PlayerRuntimeController.tryParsingErrorProbeFallback(
    error: PlaybackException,
    failure: PlaybackFailure,
    savedPosition: Long = 0L,
    paused: Boolean = userPausedManually
): Boolean {
    val isSourceOrParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null ||
        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    if (!isSourceOrParsingError) return false
    // NuvioTV fork (T2, ysosrs cad15d29e): mid-play, the container already played
    // for a while under a known non-HLS mimeType - re-probing the format cannot
    // help (the data is corrupt, not the container label) and costs a network
    // round trip. Skip the probe; the mid-play malformed failover in
    // onPlayerError and the auto-retry ladder handle it. HLS (M3U8) still probes
    // for live-window recovery.
    if (hasRenderedFirstFrame &&
        currentStreamMimeType != null &&
        currentStreamMimeType != androidx.media3.common.MimeTypes.APPLICATION_M3U8
    ) {
        return false
    }
    if (parsingErrorProbeAttempted) return false
    parsingErrorProbeAttempted = true

    val previousMimeType = currentStreamMimeType
    Log.w(
        PlayerRuntimeController.TAG,
        "Source/parsing error [${error.errorCode}] detected (previous mimeType=$previousMimeType). " +
            "Probing stream format..."
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val probedMime = PlayerMediaSourceFactory.probeNetworkMimeType(
            url = currentStreamUrl,
            headers = currentHeaders
        )

        if (probedMime != null && probedMime != previousMimeType) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Stream probe resolved mimeType=$probedMime (was $previousMimeType). Retrying playback..."
            )
            currentStreamMimeType = probedMime
            currentStreamResponseHeaders = emptyMap()
            rebuildCurrentStream("parsing probe", savedPosition, paused)
        } else if (previousMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            rebuildCurrentStream("parsing probe HLS", savedPosition, paused)
        } else {
            // Fork dead-source rung (community 3003 report): the probe failed to
            // name a better container, so a sniff-failure body (HTML page behind
            // HTTP 200, .rar/.zip payload) or HTTP 404/410 is permanent for this
            // URL. Advance to the next source instead of burning both same-URL
            // retries on a doomed link.
            // Spec 75 D1.1: one bounded look at what the server sent before a failover records
            // the mark or the box shows the reason ("a web page", not "could not be read"); a
            // same-URL retry does not wait for it. At most once per tail.
            var probed: PlaybackFailure? = null
            suspend fun sharpened(): PlaybackFailure = probed ?: sharpenedByProbe(failure).also { probed = it }
            if (isDeadSourcePlaybackError(error)) {
                val sharp = sharpened()
                // A reply the probe named (web page, text, sign-in, tiny file, HTTP error) is not a
                // mislabelled container: skip the mime-clear re-init, whose second failure would lose
                // the finding. Spec 85 G6 (E4): any other unrecognized container may be one only
                // mpv opens (ASF, RealMedia: the probe cannot name those as media), so it goes there
                // after the mime-clear re-init, before the failover records it.
                val handled = if (sharp.probeNamedNonMedia) {
                    advanceToNextLiveSource(sharp, FailureReason.DEAD_LINK)
                } else {
                    retryWithMimeOverrideCleared(error, sharp) ||
                        (error.isUnrecognizedContainer() && maybeHopToMpv("UNRECOGNIZED_CONTAINER")) ||
                        advanceToNextLiveSource(sharp, FailureReason.DEAD_LINK)
                }
                if (handled) return@launch
            }
            if (attemptAutoRetry(error, failure.message)) {
                return@launch
            }
            // The rungs after PARSING_PROBE never run (it took the error), so its tail mirrors them.
            val shown = sharpened()
            if (attemptStartupExhaustedSourceFailover(shown)) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = shown,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
    return true
}

/**
 * Spec 75 D1.1, D3 (4): an UNREADABLE failure sharpened by [ServerResponseProbe] (64 KB, 3 s);
 * any other cause, or a probe that learned nothing, keeps [failure]. The step line is redrawn only
 * when the new cause fails at another step (an HTTP error the probe met fails at Connecting).
 */
private suspend fun PlayerRuntimeController.sharpenedByProbe(failure: PlaybackFailure): PlaybackFailure {
    if (failure.cause != FailureCause.UNREADABLE) return failure
    val finding = ServerResponseProbe.probe(currentStreamUrl, currentHeaders) ?: return failure
    val sharpened = failure.sharpenedBy(finding) { stepLineFor(it) }
    Log.w(
        PlayerRuntimeController.TAG,
        "PARSING_PROBE_FINDING kind=${finding.kind} status=${finding.status} cause=${sharpened.cause} " +
            "host=${currentStreamUrl.safeHost()}"
    )
    return sharpened
}

/** @return true if a mimeType override was present and has been cleared. */
private fun PlayerRuntimeController.clearMimeOverrideForParsingError(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    ) {
        if (currentStreamMimeType != null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "Parsing error [${error.errorCode}] detected with mimeType=$currentStreamMimeType. " +
                        "Clearing mimeType override for fallback."
            )
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            return true
        }
    }
    return false
}

/**
 * Dead-source classification (community 3003 report).
 *
 * A container-sniff failure where the content is NOT malformed media - media3's
 * [androidx.media3.exoplayer.source.UnrecognizedInputFormatException], surfaced as
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003, [isUnrecognizedContainer]) - means the
 * body is not media ExoPlayer reads: an HTML error page behind HTTP 200, a .rar/.zip
 * payload from a torrent, or a container only mpv opens (Spec 85 E4: the parsing probe's
 * tail and [PlayerErrorRecoveryPolicy.Rung.MPV_FORMAT_HOP] try mpv first).
 * HTTP 404/410 are equally permanent for the URL. Same-URL retries only make the
 * user sit through doomed rebuild cycles.
 *
 * Deliberately NOT classified dead: HTTP 429 and timeouts - auto-advancing on a
 * debrid rate limit (TorBox parallel-connection 429s are transient) would wrongly
 * burn perfectly good sources. After the first frame a 429/5xx still fails over once the
 * two same-link retries are spent (Spec 86 D2, [PlayerErrorRecoveryPolicy.Rung.MID_PLAY_SERVER_FAILOVER]).
 */
internal fun PlayerRuntimeController.isDeadSourcePlaybackError(error: PlaybackException): Boolean =
    isDeadSourceHttpError(error) || error.isUnrecognizedContainer()

/** 3003 from a container sniff no extractor matched (media3's UnrecognizedInputFormatException). */
internal fun PlaybackException.isUnrecognizedContainer(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
        findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null

/**
 * The HTTP arm of the dead-source classification. 404/410 is certain-dead with
 * no probe value, so callers can advance without spending the 0.8.5
 * parsing-error probe on it.
 */
internal fun isDeadSourceHttpError(error: PlaybackException): Boolean {
    val http = error.findInvalidResponseCodeException()
    return http != null && (http.responseCode == 404 || http.responseCode == 410)
}

/**
 * The dead-source HTTP rung (404/410): one narrowly-scoped same-URL retry if a mimeType
 * override was steering the extractor (the only genuinely recoverable sub-case),
 * otherwise mark the URL dead for the session and auto-advance to the next source
 * in the user's existing sort order. Returns false when out of options (failover
 * cap reached or no live sources left) so the caller surfaces the error screen.
 * The parsing probe's tail composes the same two steps with the Spec 85 mpv hop between them.
 */
internal fun PlayerRuntimeController.attemptDeadSourceFailover(
    error: PlaybackException,
    failure: PlaybackFailure
): Boolean = retryWithMimeOverrideCleared(error, failure) ||
    advanceToNextLiveSource(failure, FailureReason.DEAD_LINK)

/**
 * The dead-source check's one same-URL retry: true when a mimeType override was steering the
 * extractor and one full re-init without it is scheduled.
 */
private fun PlayerRuntimeController.retryWithMimeOverrideCleared(
    error: PlaybackException,
    failure: PlaybackFailure
): Boolean {
    // The override is baked into the MediaItem, so only a FULL re-init applies the
    // clear - a bare prepare() retry cannot (which is why the old auto-retry's first
    // attempt never fixed this case). One full retry, then the URL is treated as dead.
    if (clearMimeOverrideForParsingError(error) && !hasRetriedAfterMimeOverrideClear) {
        hasRetriedAfterMimeOverrideClear = true
        val paused = userPausedManually
        val savedPosition = _exoPlayer?.let(::exoCarryPositionMs)?.takeIf { it > 0L } ?: 0L
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source check: mimeType override cleared; one full re-init for: ${failure.message}"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(error = null, showLoadingOverlay = it.loadingOverlayEnabled, showPauseOverlay = false)
            }
            delay(RETRY_DELAY_MS)
            rebuildCurrentStream("mime override cleared", savedPosition, paused)
        }
        return true
    }
    return false
}

/**
 * Spec 65 P5: remembers the playing stream as failed for [reason] (FailedReleases, D8): its URLs,
 * and for a wrong file the release, from its list entry ([listEntry], when the list is loaded) and
 * the player's own facts; [mark] is the plain cause the Sources marking shows (Spec 75 S1).
 */
internal fun PlayerRuntimeController.recordPlaybackFailure(
    reason: FailureReason,
    listEntry: Stream?,
    mark: FailureMark? = null
) {
    val state = _uiState.value
    failedReleases.record(
        reason,
        FailedPlayback(
            urls = listOf(currentStreamUrl, state.currentStreamUrl),
            listEntry = listEntry,
            infoHash = state.currentStreamInfoHash ?: currentInfoHash,
            fileIdx = state.currentStreamFileIdx ?: currentFileIdx,
            filename = currentListedFilename,
            size = currentListedSize
        ),
        mark
    )
}

/**
 * Spec 75 D6(5): leaving the player from the error box records the failure it shows, as NO_START,
 * when the source caused it ([PlaybackFailure.recordedWhenBoxEnds]) and no failover recorded it
 * already. At the exit, not when the box shows: a late first frame can still retract the startup
 * watchdog's box (a slow source that then played is never ranked down).
 */
internal fun PlayerRuntimeController.recordBoxEndingFailure() {
    val failure = boxEndingFailureToRecord(_uiState.value.error, lastRecordedFailure) ?: return
    lastRecordedFailure = failure
    recordPlaybackFailure(FailureReason.NO_START, playingListEntry(), failure.mark)
}

/** The playing stream's entry in the loaded source list, or null when it is not there. */
internal fun PlayerRuntimeController.playingListEntry(): Stream? =
    sourceStreamsForPlayingItem().let { streams -> streams.getOrNull(playingRowIndex(streams, _uiState.value)) }

/**
 * Spec 86 R10: the playing row in [streams] ([playingStreamIndex]), else its listed release
 * ([listedReleaseIndex]). A list loaded after the start (the failover's own load, the Sources
 * panel's) can carry new playback URLs for the same rows: AIOStreams signs them per scrape and its
 * debrid rows carry no info hash, so the URL match missed and the failover walked from the top
 * (Shield 2026-11-12, run 2: a fresh 48-row scrape, failover to row 0). -1 when neither finds it.
 */
internal fun PlayerRuntimeController.playingRowIndex(streams: List<Stream>, state: PlayerUiState): Int =
    playingStreamIndex(streams, state).takeIf { it >= 0 }
        ?: listedReleaseIndex(streams, state.currentStreamAddonName, currentListedFilename, currentListedSize)

/**
 * The one row of [addonName] listing [filename] at [size] (the facts the playing stream was started
 * with, [StreamTextSizeParser.effectiveSizeBytes] as the Stream screen passes it), or -1: any fact
 * missing, no row, or several (a file cached on two services: the walk then starts at the top, as
 * before). Known gap (review 8ac7e68d): a reload that drops the playing service's copy leaves its
 * twin the single match, so the twin is marked dead; the route carries no service id to tell them
 * apart.
 */
internal fun listedReleaseIndex(streams: List<Stream>, addonName: String?, filename: String?, size: Long?): Int {
    if (addonName.isNullOrBlank() || filename.isNullOrBlank() || size == null || size <= 0L) return -1
    return streams.indices.singleOrNull { i ->
        val stream = streams[i]
        stream.addonName == addonName &&
            stream.behaviorHints?.filename == filename &&
            StreamTextSizeParser.effectiveSizeBytes(stream) == size
    } ?: -1
}

/**
 * Marks the current stream URL dead for this session (greying it in the source
 * panel), then auto-advances to the next live source in the user's existing
 * sort order. Shared by the dead-source, mid-play, startup-exhausted and
 * placeholder paths; all draw on the same MAX_DEAD_SOURCE_FAILOVERS cap, and each records its
 * [reason] in the failure store (Spec 65 P5) so autoplay ranks the stream last for a day, with
 * [failure]'s mark (Spec 75); [failure] is also what the error box shows when this gives up. Returns
 * false when out of options (cap reached or no live sources left) so the caller
 * surfaces the error screen. After a placeholder reject the placeholder's playhead
 * is not carried ([failoverPendingSeek]); the next source resumes from saved progress.
 */
internal fun PlayerRuntimeController.advanceToNextLiveSource(
    failure: PlaybackFailure,
    reason: FailureReason
): Boolean {
    val state = _uiState.value
    val streams = sourceStreamsForPlayingItem()
    val currentIdx = playingRowIndex(streams, state)
    val playingEntry = streams.getOrNull(currentIdx)
    // Record first: the Sources panels re-read the store when the published dead set grows.
    recordPlaybackFailure(reason, playingEntry, failure.mark)
    lastRecordedFailure = failure
    markPlayingSourceDead(playingEntry)

    if (deadSourceFailoverCount >= MAX_DEAD_SOURCE_FAILOVERS) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source failover cap ($MAX_DEAD_SOURCE_FAILOVERS) reached; surfacing error"
        )
        markFailoverExhausted(failure)
        return false
    }
    if (streams.isEmpty()) {
        // Direct-launch case (2026-08-30, Arshad's expired-link report): the in-player
        // source list only loads when the sources/episodes panel opens, so a dead link
        // on a freshly launched playback arrived with no candidates in hand and the
        // failover gave up straight to the error screen. Load the (repository-cached)
        // list and continue the failover when it lands; only surface the error when
        // nothing is loadable. Also taken after an episode switch.
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead source with no source list for the playing item; loading cached list to fail over"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(
                    error = null,
                    showPauseOverlay = false,
                    showLoadingOverlay = it.loadingOverlayEnabled
                )
            }
            loadSourceStreams(
                // A cached ERROR is not a list to fail over from: when the last
                // load for this item ended in an error, force a fresh scrape
                // instead of letting loadSourceStreams early-return on it. An
                // error left by the previous episode does not force one.
                forceRefresh = sourceListIsForPlayingItem() && _uiState.value.sourceStreamsError != null
            )
            val loaded = awaitSourceListForFailover(_uiState)
            // The same scoped list the re-entered call reads, so a mismatch cannot
            // re-enter this branch.
            if (loaded != null && sourceStreamsForPlayingItem().isNotEmpty() &&
                advanceToNextLiveSource(failure, reason)
            ) {
                return@launch
            }
            Log.w(PlayerRuntimeController.TAG, "No sources loadable for dead-source failover; surfacing error")
            markFailoverExhausted(failure)
            _uiState.update {
                it.copy(
                    error = failure,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
        return true
    }

    val next = failoverCandidate(streams, currentIdx, state) ?: run {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead source and no live sources besides index $currentIdx; surfacing error"
        )
        markFailoverExhausted(failure)
        return false
    }

    deadSourceFailoverCount++
    val attemptNo = deadSourceFailoverCount
    failedOverSources = failedOverSources + failure.asTriedSource
    Log.w(
        PlayerRuntimeController.TAG,
        "Dead source (${failure.message}) - failing over to next source ($attemptNo/$MAX_DEAD_SOURCE_FAILOVERS): " +
                "host=${next.getStreamUrl()?.safeHost()}"
    )
    val livePosition = _exoPlayer?.let(::exoCarryPositionMs) ?: 0L
    val notice = FailoverNotice(failure.mark, attemptNo, MAX_DEAD_SOURCE_FAILOVERS, failure.steps)
    errorRetryJob?.cancel()
    scope.launch {
        // The notice also drives the failover toast (PlayerScreen, Spec 75 S5).
        _uiState.update {
            it.copy(
                error = null,
                showPauseOverlay = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                // The notice names the failover (whatever Loading status says); the status line
                // starts over for the next source.
                loadingMessage = null,
                failoverNotice = notice,
                exhaustedFailover = null,
                pendingSeekPosition = failoverPendingSeek(
                    placeholderRejected = placeholderRejectedForStream,
                    livePositionMs = livePosition,
                    pendingSeekMs = it.pendingSeekPosition
                )
            )
        }
        switchToSourceStream(next, resetFailoverBudget = false)
    }
    return true
}

/**
 * The failover's next source after the playing row ([currentIdx], -1 when not in [streams]), with
 * the session's dead URLs and rows. Spec 51 (R4-1): the limit's hidden set computed now, over the
 * list walked, with the playing key and binge exemption as of now; never a stored set, so a stream
 * the user switched away from does not stay eligible. All remaining hidden = the error screen (R5-8).
 */
private fun PlayerRuntimeController.failoverCandidate(
    streams: List<Stream>,
    currentIdx: Int,
    state: PlayerUiState,
    deadUrls: Set<String> = deadSourceStreamUrls,
    deadKeys: Set<String> = deadSourceRowKeys
): Stream? {
    val startIdx = if (currentIdx >= 0) currentIdx + 1 else 0
    return nextLiveSource(streams, startIdx, deadUrls, sourceHiddenKeysNow(state), p2pEnabled, deadKeys)
}

/**
 * Spec 86 R4: whether [advanceToNextLiveSource] would switch (or load the list to try) for the
 * playing stream, without its dead marks and failure record; a caller whose box may still be
 * retracted asks first (the startup watchdog).
 */
internal fun PlayerRuntimeController.canAdvanceToNextLiveSource(): Boolean {
    if (deadSourceFailoverCount >= MAX_DEAD_SOURCE_FAILOVERS) return false
    if (sourceStreamsForPlayingItem().isEmpty()) return true
    return nextSourceAfterPlaying() != null
}

/**
 * The source the failover would pick next with the playing one counted dead (its URL and row), from
 * the loaded list; null when none is left or no list is loaded. No cap: the error box's Next source
 * (Spec 86 R3) and [canAdvanceToNextLiveSource] apply their own.
 */
internal fun PlayerRuntimeController.nextSourceAfterPlaying(): Stream? {
    val streams = sourceStreamsForPlayingItem()
    if (streams.isEmpty()) return null
    val state = _uiState.value
    val currentIdx = playingRowIndex(streams, state)
    val playingEntry = streams.getOrNull(currentIdx)
    return failoverCandidate(
        streams,
        currentIdx,
        state,
        deadUrls = deadSourceStreamUrls + playingSourceUrls(playingEntry),
        deadKeys = deadSourceRowKeys + listOfNotNull(playingEntry?.limitKey())
    )
}

/** The playing stream's URLs a dead mark covers: the resolved playback URL and its list entry's. */
private fun PlayerRuntimeController.playingSourceUrls(playingEntry: Stream?): Set<String> =
    listOfNotNull(currentStreamUrl, playingEntry?.getStreamUrl()).toSet()

/**
 * Marks the playing stream dead for this session: the resolved playback URL and, where identifiable,
 * its list entry's URL and row (debrid resolution can make these differ), so the source panel greys
 * it, a manual re-pick is visibly discouraged and no failover picks it again.
 */
private fun PlayerRuntimeController.markPlayingSourceDead(playingEntry: Stream?) {
    deadSourceStreamUrls.addAll(playingSourceUrls(playingEntry))
    playingEntry?.let { deadSourceRowKeys.add(it.limitKey()) }
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }
}

/**
 * Spec 86 R3: the box's Retry or Play with tries the playing source again, so a failover that gave
 * up on it no longer counts it dead (its Sources row greyed, later failovers skipping it).
 */
private fun PlayerRuntimeController.unmarkPlayingSourceDead() {
    val playingEntry = playingListEntry()
    deadSourceStreamUrls.removeAll(playingSourceUrls(playingEntry))
    playingEntry?.let { deadSourceRowKeys.remove(it.limitKey()) }
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }
}

/**
 * Spec 86 R3 (D4): the error box's Next source, one press, one source, past the automatic cap. The
 * box's failure is recorded as leaving the player would ([recordBoxEndingFailure]: only one the
 * source caused), the playing source is marked dead and counted as tried, and the next source plays
 * as a Sources-panel pick does (live position carried, no failover notice), keeping the failover
 * budget: a source that then fails goes on to the box again once the cap is spent. Nothing when the
 * box is gone or no source is left (the button is not drawn then).
 */
internal fun PlayerRuntimeController.playNextSourceFromFailureBox() {
    val failure = _uiState.value.error ?: return
    val next = nextSourceAfterPlaying() ?: return
    recordBoxEndingFailure()
    markPlayingSourceDead(playingListEntry())
    failedOverSources = failedOverSources + failure.asTriedSource
    Log.i(PlayerRuntimeController.TAG, "Failure box: next source host=${next.getStreamUrl()?.safeHost()}")
    errorRetryJob?.cancel()
    errorRetryJob = null
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            loadingMessage = null,
            failoverNotice = null,
            exhaustedFailover = null
        )
    }
    switchToSourceStream(next, resetFailoverBudget = false)
}

/**
 * Spec 86 R3: the error box's Retry, for a passing cause: this link again where it stopped (the
 * playhead, else the pending resume), with fresh retries and a fresh failover budget, as the
 * user's own new start (the per-URL recovery budget included, and no longer marked dead).
 */
internal fun PlayerRuntimeController.retryFromFailureBox() {
    if (_uiState.value.error == null || currentStreamUrl.isBlank()) return
    val positionMs = currentPlaybackPositionMs()?.takeIf { it > 0L }
        ?: _uiState.value.pendingSeekPosition
        ?: 0L
    resetErrorRetryState()
    // The nt6 per-URL budget too: Retry is the user's own new start of this URL.
    autoRecoveryCountForCurrentStream = 0
    unmarkPlayingSourceDead()
    Log.i(PlayerRuntimeController.TAG, "Failure box: retry host=${currentStreamUrl.safeHost()} positionMs=$positionMs")
    scheduleDeferredPlayerReinitialize("failure box retry", fromPositionMs = positionMs)
}

/** Spec 86 R3: the error box's Play with: the manual engine switch, this source no longer dead. */
internal fun PlayerRuntimeController.switchEngineFromFailureBox() {
    if (_uiState.value.error == null || currentStreamUrl.isBlank()) return
    unmarkPlayingSourceDead()
    switchInternalPlayerEngineManually()
}

/**
 * Spec 86 R3: the engine the error box offers ([manualEngineSwitchTarget], as the manual switch), or
 * null when this link already took its one switch ([engineHopStreamUrls], Spec 85 G1): the other
 * engine has failed it, or the user chose this one.
 */
internal fun PlayerRuntimeController.failureBoxEngineTarget(): InternalPlayerEngine? {
    if (currentStreamUrl.isBlank() || currentStreamUrl in engineHopStreamUrls) return null
    return manualEngineSwitchTarget()
}

/**
 * Spec 86 R3: the error box needs the playing item's list for Next source; a start that failed
 * before the first frame (where the list is loaded after it, T2) may have none yet. A cache hit,
 * as T2's; a cached error is scraped again, once per box (also straight after the failover's own
 * failed load: one more try, accepted, review f84b0035).
 */
internal fun PlayerRuntimeController.loadSourceListForFailureBox() {
    if (sourceStreamsForPlayingItem().isNotEmpty()) return
    // A cached ERROR is no list: refresh it, as the failover's empty-list arm does.
    loadSourceStreams(forceRefresh = sourceListIsForPlayingItem() && _uiState.value.sourceStreamsError != null)
}

/**
 * Spec 75 S5: the failover gives up at [failure], which the caller shows next; the box counts the
 * sources this chain left (none = a plain box).
 */
private fun PlayerRuntimeController.markFailoverExhausted(failure: PlaybackFailure) {
    val exhausted = exhaustedFailoverOf(failure, failedOverSources)
    // The failover is over: no notice left to toast or draw under the box.
    _uiState.update { it.copy(exhaustedFailover = exhausted, failoverNotice = null) }
}

/**
 * Spec 75 S5: the next source drew its first frame, so the failover notice is done (a later
 * rebuffer's loading screen must not name it); the tried sources stay until the budget resets.
 */
internal fun PlayerRuntimeController.clearFailoverNoticeAfterFirstFrame() {
    if (_uiState.value.failoverNotice == null) return
    _uiState.update { it.copy(failoverNotice = null) }
}

/**
 * The failover's next source: the rows from [startIdx] to the end, then (Spec 86 R5) the rows from
 * the top, so a pick low in the list still reaches better-ranked untried rows. [startIdx] is the
 * playing row + 1, or 0 when the playing row is not in the list (the whole list, once); the playing
 * row (startIdx - 1) is never a candidate. A candidate is not proven dead (its URL in [deadUrls], or
 * its row in [deadKeys], by limitKey: a torrent row has no http URL), not hidden by the stream list
 * limit ([hiddenKeys], by limitKey: auto never picks a hidden stream, Spec 51 D1), and one the
 * failover may switch to on its own ([isAutoFailoverCandidate]); a row reported uncached
 * ([isReportedUncached], Spec 86 R7) only when no other candidate is left. Null when none is.
 */
internal fun nextLiveSource(
    streams: List<Stream>,
    startIdx: Int,
    deadUrls: Set<String>,
    hiddenKeys: Set<String>,
    p2pEnabled: Boolean,
    deadKeys: Set<String> = emptySet()
): Stream? {
    val wrapEnd = minOf(startIdx - 1, streams.size)
    val candidates = ((startIdx..<streams.size) + (0..<wrapEnd))
        .map { streams[it] }
        .filter { candidate ->
            val url = candidate.getStreamUrl()
            val key = candidate.limitKey()
            (url == null || url !in deadUrls) &&
                key !in deadKeys &&
                key !in hiddenKeys &&
                isAutoFailoverCandidate(candidate, p2pEnabled)
        }
    return candidates.firstOrNull { !it.isReportedUncached() } ?: candidates.firstOrNull()
}

/**
 * The next source's pending seek on a failover: the live playhead when there is one, else
 * the pending seek as it stands. After a placeholder reject (Spec 21 P3) the playhead is the
 * placeholder's, not the title's, so only the existing pending seek (a saved resume) carries.
 */
internal fun failoverPendingSeek(placeholderRejected: Boolean, livePositionMs: Long, pendingSeekMs: Long?): Long? =
    if (!placeholderRejected && livePositionMs > 0L) livePositionMs else pendingSeekMs

/**
 * Task 1.6 (Option A): a startup-phase failure that has exhausted the fallback
 * ladders and both same-URL auto-retries advances to the next source instead
 * of surfacing the error screen (previously: one-shot engine failover to MPV
 * when that toggle was enabled, else the error screen). A URL that could not
 * produce a first frame through every recovery path is treated like a dead
 * source for this session. Also the source-facing startup watchdog fire (Spec 86 R4) and mpv's
 * failed start (Spec 85 D3), which run no ladder.
 */
internal fun PlayerRuntimeController.attemptStartupExhaustedSourceFailover(failure: PlaybackFailure): Boolean {
    if (hasRenderedFirstFrame) return false
    Log.w(
        PlayerRuntimeController.TAG,
        "Startup failed; attempting next-source failover for: ${failure.message}"
    )
    return advanceToNextLiveSource(failure, FailureReason.NO_START)
}

/**
 * Spec 86 R2 ([PlayerErrorRecoveryPolicy.Rung.MID_PLAY_LINK_REFRESH]): a link refused after the
 * first frame (an expired or blocked debrid link) is fetched again: the source list is reloaded
 * from the addons, the playing row found in it ([playingRowIndex]: by URL, else by its listed
 * release, as AIOStreams signs new URLs per scrape) and replayed at [positionMs]. The PRDS session
 * retained for a URL (45 s, with the redirected CDN URL pinned) is released first, so a replay of
 * the same URL asks the addon again (Spec 86 F1; nuviomedia PlayerRuntimeControllerExpiredLinkRefresh,
 * NM e72921b68). Once per URL: both URLs are latched, so a second refusal takes the dead-source
 * failover. No row found (or the list did not load): [advanceToNextLiveSource] straight away, the
 * error box when it cannot. Always takes the error.
 */
internal fun PlayerRuntimeController.refreshLinkAndReplay(failure: PlaybackFailure, positionMs: Long): Boolean {
    val refusedUrl = currentStreamUrl
    Log.w(
        PlayerRuntimeController.TAG,
        "MID_PLAY_LINK_REFRESH: link refused (${failure.message}); reloading the source list " +
            "host=${refusedUrl.safeHost()}"
    )
    errorRetryJob?.cancel()
    // Lazy, so errorRetryJob holds this job before its body runs: under Main.immediate a body that
    // never suspends would otherwise overwrite the failover's own errorRetryJob on return.
    val job = scope.launch(start = CoroutineStart.LAZY) {
        _uiState.update {
            it.copy(error = null, showPauseOverlay = false, showLoadingOverlay = it.loadingOverlayEnabled)
        }
        loadSourceStreams(forceRefresh = true)
        awaitSourceFetchCompleted()
        // A switch, an episode change or an exit releases the player, which cancels this job; the
        // check is the backstop.
        if (currentStreamUrl != refusedUrl) return@launch
        val streams = sourceStreamsForPlayingItem()
        val row = streams.getOrNull(playingRowIndex(streams, _uiState.value))
        val rowUrl = row?.getStreamUrl()
        if (row == null || rowUrl.isNullOrBlank() || row.isTorrent()) {
            Log.w(PlayerRuntimeController.TAG, "MID_PLAY_LINK_REFRESH: playing row not found; failing over")
            if (!advanceToNextLiveSource(failure, FailureReason.DEAD_LINK)) {
                _uiState.update { it.copy(error = failure, showLoadingOverlay = false, showPauseOverlay = false) }
            }
            return@launch
        }
        // Latched only when the replay happens: a wait cut short (an exit, a background release)
        // leaves this URL its refresh.
        linkRefreshedStreamUrls.add(refusedUrl)
        linkRefreshedStreamUrls.add(rowUrl)
        Log.i(
            PlayerRuntimeController.TAG,
            "MID_PLAY_LINK_REFRESH: replaying the row at ${positionMs}ms sameUrl=${rowUrl == refusedUrl}"
        )
        ParallelRangeDataSource.releaseRetainedSession()
        if (positionMs > 0L) _uiState.update { it.copy(pendingSeekPosition = positionMs) }
        switchToSourceStream(row, resetFailoverBudget = false)
        // The switch normalizes the URL ([PlayerMediaSourceFactory.normalizePlaybackRequest]); the
        // gate reads that one.
        linkRefreshedStreamUrls.add(currentStreamUrl)
    }
    errorRetryJob = job
    job.start()
    return true
}

/**
 * Waits (up to [SOURCE_LIST_WAIT_MS]) for the source fetch to complete with every addon, not for
 * one job handle: the Sources panel's dismiss or reload cancels the running load meanwhile, and a
 * cancelled job's join returns as if done. A cancelled, incomplete load is started again once:
 * forced again when no row landed yet (the cancelled forced load may not have dropped the cached
 * session, whose URLs are the refused ones), else resuming its rows. A second cancel, or a load
 * that cannot be restarted, ends the wait with what is there.
 */
private suspend fun PlayerRuntimeController.awaitSourceFetchCompleted() {
    var restarted = false
    withTimeoutOrNull(SOURCE_LIST_WAIT_MS) {
        while (!sourceStreamsFetchCompleted) {
            val job = sourceStreamsJob
            if (job == null || job.isCancelled) {
                if (restarted) break
                restarted = true
                loadSourceStreams(forceRefresh = _uiState.value.sourceAllStreams.isEmpty())
                if (sourceStreamsJob === job) break
            }
            (sourceStreamsJob ?: break).join()
        }
    }
}

/** Spec 21 P1: the latches [PlayerErrorRecoveryPolicy.route] reads, at the moment of the error. */
internal fun PlayerRuntimeController.errorRecoveryStreamState(): PlayerErrorRecoveryPolicy.StreamState =
    PlayerErrorRecoveryPolicy.StreamState(
        inBackground = isInBackground,
        mapDv7ToHevcActive = isMapDv7ToHevcActiveForCurrentPlayback,
        manualDv81Mode2Active = isManualDv81Mode2ActiveForCurrentPlayback,
        experimentalDv7ToDv81Active = isExperimentalDv7ToDv81ActiveForCurrentPlayback,
        dv7Mode1AlreadyForced = dv7Mode1ForcedStreamUrls.contains(currentStreamUrl),
        rangeNotSatisfiableRetried = hasRetriedCurrentStreamAfter416,
        renderedFirstFrame = hasRenderedFirstFrame,
        mpvHopAvailable = mpvFormatHopAvailable(),
        liveEdgeFixAvailable = liveEdgeFixCount < PlayerErrorRecoveryPolicy.MAX_LIVE_EDGE_FIXES,
        linkRefreshAvailable = currentStreamUrl !in linkRefreshedStreamUrls &&
            !isTorrentStream && !currentPlayingStream.isTorrent(),
    )

/**
 * Runs one `onPlayerError` rung; true when it took the error (a rebuild, retry or failover is
 * scheduled), false when a self-gated rung declined.
 */
internal fun PlayerRuntimeController.executeErrorRecoveryRung(
    rung: PlayerErrorRecoveryPolicy.Rung,
    error: PlaybackException,
    failure: PlaybackFailure,
    positionMs: Long
): Boolean = when (rung) {
    PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_DECODER_FAILURE,
    PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_RUNTIME_CHECK -> {
        dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
        val prefix = if (rung == PlayerErrorRecoveryPolicy.Rung.DV_MODE1_AFTER_DECODER_FAILURE) {
            "DV7_MODE2_PLAYBACK_FALLBACK: mode 2 decode failed; "
        } else {
            "DV7_MODE2_RUNTIME_CHECK_FALLBACK: mode 2 hit FAILED_RUNTIME_CHECK; "
        }
        Log.i(PlayerRuntimeController.TAG, prefix + "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}")
        scheduleDeferredPlayerReinitialize("DV7 mode 1", positionMs, clearResumeProgress = true)
        true
    }
    PlayerErrorRecoveryPolicy.Rung.DV_HDR10_AFTER_DECODER_FAILURE,
    PlayerErrorRecoveryPolicy.Rung.DV_HDR10_AFTER_RUNTIME_CHECK -> {
        // Unknown profile (none recorded, no failing or selected video format): base layer.
        val sourceProfile = currentDvSourceProfile((error as? ExoPlaybackException)?.rendererFormat?.codecs)
        if (sourceProfile == 5) {
            dvConversionDisarmedStreamUrls.add(currentStreamUrl)
        } else {
            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "DV_HDR10_FALLBACK: rung=$rung sourceProfile=$sourceProfile " +
                "baseLayer=${sourceProfile != 5} host=${currentStreamUrl.safeHost()}"
        )
        scheduleDeferredPlayerReinitialize("DV HDR10 base layer", positionMs, clearResumeProgress = true)
        true
    }
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_RENDERER_FAULT,
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_AUDIO_TRACK,
    PlayerErrorRecoveryPolicy.Rung.AUDIO_LADDER_STUCK_NO_PROGRESS -> tryAudioRecoveryLadder(positionMs)
    PlayerErrorRecoveryPolicy.Rung.DENIED_AUDIO_FFMPEG -> tryDeniedAudioFfmpegFallback(error)
    PlayerErrorRecoveryPolicy.Rung.AUDIO_TRACK_PCM -> tryAudioTrackPcmFallback(error)
    PlayerErrorRecoveryPolicy.Rung.RANGE_416_FROM_START -> {
        retryCurrentStreamFromStartAfter416()
        true
    }
    PlayerErrorRecoveryPolicy.Rung.DEAD_SOURCE_HTTP_FAILOVER -> attemptDeadSourceFailover(error, failure)
    PlayerErrorRecoveryPolicy.Rung.PARSING_PROBE -> tryParsingErrorProbeFallback(
        error = error,
        failure = failure,
        savedPosition = positionMs,
        paused = userPausedManually
    )
    PlayerErrorRecoveryPolicy.Rung.MID_PLAY_LINK_REFRESH -> refreshLinkAndReplay(failure, positionMs)
    // Bounded by MAX_DEAD_SOURCE_FAILOVERS; false (=> auto-retry, then the error screen) once
    // the cap is reached or no live source remains.
    PlayerErrorRecoveryPolicy.Rung.MID_PLAY_CORRUPT_FAILOVER ->
        advanceToNextLiveSource(failure, FailureReason.CORRUPT)
    PlayerErrorRecoveryPolicy.Rung.AUTO_RETRY -> attemptAutoRetry(error, failure.message)
    PlayerErrorRecoveryPolicy.Rung.MID_PLAY_SERVER_FAILOVER -> advanceToNextLiveSource(failure, FailureReason.DEAD_LINK)
    // Routed only when the snapshot's gate was open, which no earlier (declining) rung changes.
    PlayerErrorRecoveryPolicy.Rung.MPV_FORMAT_HOP -> {
        hopToMpvForFormat(error.errorCodeName)
        true
    }
    PlayerErrorRecoveryPolicy.Rung.STARTUP_EXHAUSTED_FAILOVER -> attemptStartupExhaustedSourceFailover(failure)
}

/**
 * nt6 fix B: consume one unit of the per-stream auto-recovery budget.
 *
 * Self-keys on the current stream URL — a genuine source switch resets the
 * counter without any external reset call; stable progress
 * (scheduleStableProgressReset) also zeroes it. Returns false once the budget is
 * spent, at which point onPlayerError skips every fallback ladder and surfaces
 * the error to the user instead of silently re-preparing again.
 */
internal fun PlayerRuntimeController.consumeAutoRecoveryBudget(detailedError: String): Boolean {
    val url = currentStreamUrl
    if (url != autoRecoveryBudgetUrl) {
        autoRecoveryBudgetUrl = url
        autoRecoveryCountForCurrentStream = 0
    }
    autoRecoveryCountForCurrentStream += 1
    if (autoRecoveryCountForCurrentStream > MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Auto-recovery budget exhausted ($MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM recoveries) " +
                "for host=${url.safeHost()}; surfacing error instead of retrying: $detailedError"
        )
        return false
    }
    return true
}
