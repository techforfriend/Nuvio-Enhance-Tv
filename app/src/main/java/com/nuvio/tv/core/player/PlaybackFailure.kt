package com.nuvio.tv.core.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Spec 75 S4: one failure as the error box shows it (D1): the plain [cause], the [steps] line as
 * it stood when it failed, and the [facts] Details lists. No shown wording lives here (the player
 * UI builds it from res/values: PlaybackFailureCopy); [message] is the technical one-liner kept
 * for logs and diagnostics, and the Details player line when no better fact exists.
 * [fromStartupWatchdog] marks the watchdog's own box, the one a late first frame retracts.
 */
data class PlaybackFailure(
    val cause: FailureCause,
    val message: String,
    val steps: StepLine? = null,
    val facts: FailureFacts = FailureFacts(),
    val fromStartupWatchdog: Boolean = false
) {
    /**
     * The probe named what the server sent instead of video (a web page, text, a sign-in page, a
     * tiny file, or an HTTP error), so the failure is not the container label and a mime-clear
     * re-init of the same link cannot help.
     */
    val probeNamedNonMedia: Boolean get() = facts.probe != null && cause != FailureCause.UNREADABLE

    /** What the failure store keeps with the entry (Spec 75 S1). */
    val mark: FailureMark get() = FailureMark(cause, facts.measuredMbps, facts.neededMbps)

    /**
     * Spec 75 D6(5): a failure that ends on the error box (no failover) is recorded only when the
     * source caused it; a decoder, format, sound or engine fault is the TV's or the receiver's and
     * never ranks a good source down.
     */
    val recordedWhenBoxEnds: Boolean
        get() = when (cause) {
            FailureCause.TOO_SLOW, FailureCause.NO_DATA, FailureCause.TORRENT, FailureCause.NO_URL -> true
            FailureCause.LINK_EXPIRED, FailureCause.BLOCKED, FailureCause.REMOVED, FailureCause.RATE_LIMITED,
            FailureCause.SERVER_DOWN, FailureCause.OTHER_HTTP, FailureCause.NO_CONNECTION, FailureCause.WEB_PAGE,
            FailureCause.TINY_FILE, FailureCause.TEXT, FailureCause.LOGIN, FailureCause.UNREADABLE,
            FailureCause.PLACEHOLDER, FailureCause.VIDEO_UNSUPPORTED, FailureCause.DECODER_STUCK,
            FailureCause.CORRUPT, FailureCause.FROZEN, FailureCause.AUDIO_FAILED, FailureCause.ENGINE,
            FailureCause.WRONG_FILE, FailureCause.UNKNOWN -> false
        }
}

/**
 * Spec 75 D6(5): what leaving the player from the error box records: the [shown] failure when it is
 * one the source caused and not the one the store already has ([lastRecorded], a failover's);
 * null = nothing (no box, a TV fault, or already recorded).
 */
fun boxEndingFailureToRecord(shown: PlaybackFailure?, lastRecorded: PlaybackFailure?): PlaybackFailure? =
    shown?.takeIf { it.recordedWhenBoxEnds && it != lastRecorded }

/**
 * Spec 75 S5: a source the failover left, as the every-source-tried box names it (its mark in the
 * count line, a Details line with its addon and file).
 */
data class TriedSource(val mark: FailureMark, val addon: String?, val fileName: String?)

/**
 * Spec 75 S5: the failover gave up at [endedBy] after leaving [tried]. The error box counts them only
 * while it shows [endedBy] itself ([triedFor]), never for a later or unrelated failure.
 */
data class ExhaustedFailover(val endedBy: PlaybackFailure, val tried: List<TriedSource>)

/** The failover gave up at [failure] after leaving [tried]; null (a plain box) when it left none. */
fun exhaustedFailoverOf(failure: PlaybackFailure, tried: List<TriedSource>): ExhaustedFailover? =
    if (tried.isEmpty()) null else ExhaustedFailover(failure, tried)

/** The sources tried before [failure] when it is the one the failover gave up at, else none. */
fun ExhaustedFailover?.triedFor(failure: PlaybackFailure): List<TriedSource> =
    this?.takeIf { it.endedBy == failure }?.tried.orEmpty()

/** [this] failure as a source the failover left. */
internal val PlaybackFailure.asTriedSource: TriedSource
    get() = TriedSource(mark, facts.addon, facts.fileName)

/**
 * Spec 75 S5 (mockup stage 8): the failover in progress, which the loading screen shows whatever the
 * Loading status setting says: the failed source's [mark], this failover's [attempt] of [max], and
 * the failed source's step line ([previousSteps], null when none was drawn).
 */
data class FailoverNotice(val mark: FailureMark, val attempt: Int, val max: Int, val previousSteps: StepLine?)

/**
 * What Details can say about a failure (D4: the server/player line, host + addon, file + size,
 * then the probe or the measured speed when there is one); null = not known. [fileSizeBytes] is
 * what the player opened (else the listing's); [listedSizeBytes] the listing's, which a tiny-file
 * reply is compared with.
 * [streamName]/[streamDescription] name the debrid service the body speaks of. The player line
 * is the first of: [httpCode] (+ [httpStatus], [playerError]), [playerError] (Media3's error name
 * and code), [watchdogFire] after [waitedMs], [frozenForMs], [engineMessage], else the failure's
 * message. [measuredMbps] arrived over [measuredOverMs]; [neededMbps] is the file's size over
 * [runtimeMinutes]. [engineMessage] is untrusted text from an engine or exception (plain text only).
 */
data class FailureFacts(
    val streamName: String? = null,
    val streamDescription: String? = null,
    val addon: String? = null,
    val host: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val listedSizeBytes: Long? = null,
    val httpCode: Int? = null,
    val httpStatus: String? = null,
    val playerError: String? = null,
    val watchdogFire: PlayerStartupWatchdogPolicy.FireReason? = null,
    val waitedMs: Long? = null,
    val frozenForMs: Long? = null,
    val engineMessage: String? = null,
    val probe: ServerResponseFinding? = null,
    val measuredMbps: Double? = null,
    val measuredOverMs: Long? = null,
    val neededMbps: Double? = null,
    val runtimeMinutes: Int? = null
)

/**
 * [this] failure with the probe's [finding] (Spec 75 D1.1): its cause sharpened (S2), the finding
 * in Details, and the step line kept unless the new cause fails at another step ([redraw], e.g. an
 * HTTP error the probe met fails at Connecting).
 */
internal fun PlaybackFailure.sharpenedBy(
    finding: ServerResponseFinding,
    redraw: (FailureCause) -> StepLine?
): PlaybackFailure {
    val sharpCause = PlaybackFailureCauses.ofProbe(finding, cause)
    val line = if (PlaybackSteps.stepOf(sharpCause) == steps?.failed) steps else redraw(sharpCause)
    return copy(cause = sharpCause, steps = line, facts = facts.copy(probe = finding))
}

/** Media3's own facts about [this] error: the HTTP answer, and its error name and code. */
internal fun PlaybackException.failureFacts(): FailureFacts {
    val http = findInvalidResponseCodeException()
    return FailureFacts(
        httpCode = http?.responseCode,
        httpStatus = http?.responseMessage?.takeIf { it.isNotBlank() },
        playerError = "$errorCodeName ($errorCode)"
    )
}

/** The host of [url], null when it has none (an empty or non-HTTP link). */
internal fun hostOf(url: String?): String? = url?.toHttpUrlOrNull()?.host

/**
 * The failure of [cause] now: its step line (S3) and the playing stream's facts (name, addon,
 * host, file + size) over the site's own [facts].
 */
internal fun PlayerRuntimeController.playbackFailure(
    cause: FailureCause,
    message: String,
    facts: FailureFacts = FailureFacts(),
    fromStartupWatchdog: Boolean = false
): PlaybackFailure {
    val state = _uiState.value
    val size = currentMediaFileSize()
    return PlaybackFailure(
        cause = cause,
        message = message,
        steps = stepLineFor(cause),
        facts = facts.copy(
            streamName = state.currentStreamName,
            streamDescription = currentStreamDescription,
            addon = state.currentStreamAddonName,
            host = hostOf(currentStreamUrl),
            fileName = currentFilename,
            fileSizeBytes = size?.takeIf { it > 0L },
            listedSizeBytes = currentListedSize?.takeIf { it > 0L }
        ),
        fromStartupWatchdog = fromStartupWatchdog
    )
}

/** The playing file's size: what the player opened (a manifest has none), else the known size. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.currentMediaFileSize(): Long? =
    PlaybackByteCounter.mediaFileSizeFor(
        url = currentStreamUrl,
        isAdaptiveStream = _exoPlayer?.currentManifest != null
    ) ?: currentVideoSize

/** An engine fault (an init, rebuild or MPV exception): its [message] is also the Details line. */
internal fun PlayerRuntimeController.engineFailure(message: String): PlaybackFailure =
    playbackFailure(FailureCause.ENGINE, message, FailureFacts(engineMessage = message))

/** The failure for a Media3 [error]: its cause (S2), technical message and facts. */
internal fun PlayerRuntimeController.playbackFailureOf(error: PlaybackException): PlaybackFailure =
    playbackFailure(
        cause = error.failureCause(hasRenderedFirstFrame),
        message = error.toDisplayMessage(context),
        facts = error.failureFacts()
    )
