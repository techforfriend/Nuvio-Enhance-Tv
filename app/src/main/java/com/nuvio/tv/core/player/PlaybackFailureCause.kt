package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.core.stream.FailureCause

/**
 * Spec 75 S2: the plain cause of a failure (D1), from the facts each failure path has at hand.
 * Pure, so every mapping is pinned by a test; the shown wording lives with the error box.
 */
internal object PlaybackFailureCauses {

    /** The cause an HTTP answer names: 401/410 expired, 400/403 refused, 404 gone, 429 busy, 5xx down. */
    fun ofHttp(code: Int): FailureCause = when (code) {
        401, 410 -> FailureCause.LINK_EXPIRED
        400, 403 -> FailureCause.BLOCKED
        404 -> FailureCause.REMOVED
        429 -> FailureCause.RATE_LIMITED
        in 500..599 -> FailureCause.SERVER_DOWN
        else -> FailureCause.OTHER_HTTP
    }

    /** Which renderer a decoder/renderer error came from, when Media3 says. */
    enum class Renderer { VIDEO, AUDIO, OTHER }

    /**
     * The cause of a Media3 error. [httpCode] wins (the server's answer names it); a container the
     * extractors do not recognise ([unrecognizedFormat]) or a parse error is UNREADABLE before the
     * first frame (the probe may sharpen it) and CORRUPT after it (the file played, then broke).
     */
    fun ofPlaybackError(
        errorCode: Int,
        httpCode: Int?,
        unrecognizedFormat: Boolean,
        renderer: Renderer,
        renderedFirstFrame: Boolean
    ): FailureCause {
        if (httpCode != null) return ofHttp(httpCode)
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureCause.NO_CONNECTION

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FailureCause.REMOVED

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                if (renderedFirstFrame) FailureCause.CORRUPT else FailureCause.UNREADABLE

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FailureCause.AUDIO_FAILED

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                if (renderer == Renderer.AUDIO) FailureCause.AUDIO_FAILED else FailureCause.VIDEO_UNSUPPORTED

            PlaybackException.ERROR_CODE_DECODING_FAILED -> when {
                renderer == Renderer.AUDIO -> FailureCause.AUDIO_FAILED
                renderedFirstFrame -> FailureCause.CORRUPT
                else -> FailureCause.VIDEO_UNSUPPORTED
            }

            else -> when {
                unrecognizedFormat -> if (renderedFirstFrame) FailureCause.CORRUPT else FailureCause.UNREADABLE
                else -> FailureCause.UNKNOWN
            }
        }
    }

    /**
     * The startup watchdog's fire: no tracks / no video track / data still arriving (Spec 86 R9) is a
     * source that sent nothing usable in time, TOO_SLOW when a speed was measured under what the
     * file needs, else NO_DATA.
     */
    fun ofStartupWatchdog(
        reason: PlayerStartupWatchdogPolicy.FireReason,
        measuredMbps: Double?,
        neededMbps: Double?
    ): FailureCause = when (reason) {
        PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported -> FailureCause.VIDEO_UNSUPPORTED
        PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive -> FailureCause.DECODER_STUCK
        PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
        PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
        PlayerStartupWatchdogPolicy.FireReason.DataStillArriving -> slowOrNoData(measuredMbps, neededMbps)
    }

    /** TOO_SLOW needs both speeds and a positive measurement below the need; anything else is NO_DATA. */
    fun slowOrNoData(measuredMbps: Double?, neededMbps: Double?): FailureCause =
        if (measuredMbps != null && neededMbps != null && measuredMbps > 0.0 && measuredMbps < neededMbps) {
            FailureCause.TOO_SLOW
        } else {
            FailureCause.NO_DATA
        }

    /** The probe's finding sharpens an UNREADABLE (D1.1); a finding that learned nothing keeps it. */
    fun ofProbe(finding: ServerResponseFinding?, fallback: FailureCause): FailureCause = when (finding?.kind) {
        null -> fallback
        ServerResponseFinding.Kind.HTTP_ERROR -> ofHttp(finding.status)
        ServerResponseFinding.Kind.LOGIN_PAGE -> FailureCause.LOGIN
        ServerResponseFinding.Kind.WEB_PAGE -> FailureCause.WEB_PAGE
        ServerResponseFinding.Kind.TEXT -> FailureCause.TEXT
        ServerResponseFinding.Kind.TINY_FILE -> FailureCause.TINY_FILE
    }

    /** Megabits per second of [bytes] over [elapsedMs], null without a span. */
    fun mbps(bytes: Long, elapsedMs: Long): Double? =
        if (elapsedMs <= 0L || bytes < 0L) null else bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
}

/** [PlaybackFailureCauses.ofPlaybackError] with this error's own facts. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.failureCause(renderedFirstFrame: Boolean): FailureCause {
    val rendererMime = (this as? ExoPlaybackException)
        ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
        ?.rendererFormat?.sampleMimeType
    val renderer = when {
        rendererMime == null -> PlaybackFailureCauses.Renderer.OTHER
        MimeTypes.isAudio(rendererMime) -> PlaybackFailureCauses.Renderer.AUDIO
        MimeTypes.isVideo(rendererMime) -> PlaybackFailureCauses.Renderer.VIDEO
        else -> PlaybackFailureCauses.Renderer.OTHER
    }
    return PlaybackFailureCauses.ofPlaybackError(
        errorCode = errorCode,
        httpCode = findInvalidResponseCodeException()?.responseCode,
        unrecognizedFormat = findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null,
        renderer = renderer,
        renderedFirstFrame = renderedFirstFrame
    )
}
