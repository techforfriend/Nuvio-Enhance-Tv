package com.nuvio.tv.core.player

import android.util.Log

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

private const val POSITION_STALL_THRESHOLD_MS = 5_000L
private const val POSITION_PROGRESS_EPSILON_MS = 250L
private const val POSITION_MOVING_MAX_STALL_MS = 1_500L
/** The progress tick runs every 500 ms; a longer gap means it was stopped (error, retry, IDLE). */
private const val SNAPSHOT_GAP_REARM_MS = 2_000L

/** First-frame figures for the PLAYBACK_STARTUP log line (the only startup reader). */
internal data class PlaybackStartupCounters(
    val videoFormat: Format?,
    val bandwidthEstimateBps: Long?,
    val loadStartedCount: Int,
    val loadCompletedCount: Int,
    val totalBytesLoaded: Long,
    val droppedFrames: Int,
    val audioUnderrunCount: Int
)

/** Per-load figures kept for the stats HUD and the load_* event details. */
private data class PlaybackLoad(
    val host: String?,
    val dataType: String?,
    val trackType: String?,
    val httpMethod: String?,
    val position: Long?,
    val length: Long?,
    val durationMs: Long?,
    val bytesLoaded: Long?
)

internal class PlayerPlaybackAnalyticsDiagnostics {
    private var sessionStartedAtElapsedMs: Long = SystemClock.elapsedRealtime()

    private var playbackStateName: String? = null
    private var playWhenReady: Boolean? = null
    private var isPlaying: Boolean? = null
    private var isLoading: Boolean? = null
    private var playbackSpeed: Float? = null
    private var playbackPitch: Float? = null
    private var positionMs: Long? = null
    private var bufferedPositionMs: Long? = null

    private var lastPositionForStallMs: Long = -1L
    private var lastSnapshotAtMs: Long = -1L
    private var positionLastAdvancedAtMs: Long = sessionStartedAtElapsedMs
    private var positionStallActive: Boolean = false
    private var positionStallCount: Int = 0
    private var longestPositionStallMs: Long = 0L

    /**
     * Spec 70 R2: how long the position has been frozen as of the last progress snapshot (0 while
     * advancing or not watched: paused, buffering, before the first frame). Read by the freeze recovery.
     */
    var currentPositionStallMs: Long = 0L
        private set

    /** No stall longer than a couple of 500 ms ticks as of the last snapshot. */
    fun isPositionMoving(): Boolean = currentPositionStallMs < POSITION_MOVING_MAX_STALL_MS

    private var droppedFrames: Int = 0
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var videoFrameProcessingOffsetTotalUs: Long = 0L
    private var videoFrameProcessingOffsetCount: Int = 0
    private var videoFormat: Format? = null

    private var audioUnderrunCount: Int = 0

    private var bandwidthEstimateBps: Long? = null
    private var bandwidthBytesTotal: Long = 0L

    private var loadStartedCount: Int = 0
    private var loadCompletedCount: Int = 0
    private var loadErrorCount: Int = 0
    private var totalBytesLoaded: Long = 0L
    // nt6: last COMPLETED load of dataType MEDIA only. The HUD's Request row
    // reads this rather than the latest load of any outcome: on progressive
    // streams the single long read only ever ends by CANCELLATION
    // (seek/stop/track change), and its multi-minute running duration surfaced
    // as a bogus "Request" figure (field report: 606,014 ms = ten minutes since
    // the last seek).
    private var lastCompletedMediaLoad: PlaybackLoad? = null
    private var traceHost: String = "unknown"
    private var traceEngine: String = "unknown"

    fun setTraceContext(host: String?, engine: String?) {
        traceHost = host?.takeIf { it.isNotBlank() } ?: "unknown"
        traceEngine = engine?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    /** Point-in-time snapshot of the fields the live playback stats HUD needs (task 2.2 / nt26). */
    data class HudSample(
        val videoDecoderName: String?,
        val audioDecoderName: String?,
        val droppedFrames: Int,
        val bandwidthEstimateBps: Long?,
        val audioUnderrunCount: Int,
        val totalBytesLoaded: Long,
        val bandwidthBytesTotal: Long,
        val loadErrorCount: Int,
        val lastCompletedMediaLoadDurationMs: Long?,
        val positionStallCount: Int,
        val longestPositionStallMs: Long,
        val frameProcessingOffsetAvgUs: Long?,
        val transferredBytesTotal: Long,
        val transferredNetworkBytes: Long
    )

    fun hudSample(): HudSample = HudSample(
        videoDecoderName = videoDecoderName,
        audioDecoderName = audioDecoderName,
        droppedFrames = droppedFrames,
        bandwidthEstimateBps = bandwidthEstimateBps,
        audioUnderrunCount = audioUnderrunCount,
        totalBytesLoaded = totalBytesLoaded,
        bandwidthBytesTotal = bandwidthBytesTotal,
        loadErrorCount = loadErrorCount,
        lastCompletedMediaLoadDurationMs = lastCompletedMediaLoad?.durationMs,
        positionStallCount = positionStallCount,
        longestPositionStallMs = longestPositionStallMs,
        frameProcessingOffsetAvgUs = if (videoFrameProcessingOffsetCount > 0) {
            videoFrameProcessingOffsetTotalUs / videoFrameProcessingOffsetCount
        } else {
            null
        },
        transferredBytesTotal = PlaybackByteCounter.totalBytes,
        transferredNetworkBytes = PlaybackByteCounter.networkBytes
    )

    fun reset() {
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        playbackStateName = null
        playWhenReady = null
        isPlaying = null
        isLoading = null
        playbackSpeed = null
        playbackPitch = null
        positionMs = null
        bufferedPositionMs = null
        lastPositionForStallMs = -1L
        lastSnapshotAtMs = -1L
        positionLastAdvancedAtMs = sessionStartedAtElapsedMs
        positionStallActive = false
        positionStallCount = 0
        longestPositionStallMs = 0L
        currentPositionStallMs = 0L
        droppedFrames = 0
        videoDecoderName = null
        audioDecoderName = null
        videoFrameProcessingOffsetTotalUs = 0L
        videoFrameProcessingOffsetCount = 0
        videoFormat = null
        audioUnderrunCount = 0
        bandwidthEstimateBps = null
        bandwidthBytesTotal = 0L
        loadStartedCount = 0
        loadCompletedCount = 0
        loadErrorCount = 0
        totalBytesLoaded = 0L
        lastCompletedMediaLoad = null
        PlaybackByteCounter.reset()
        PlaybackConnectionEvents.clear()
    }

    fun recordProgressSnapshot(
        player: Player,
        hasRenderedFirstFrame: Boolean,
        now: Long = SystemClock.elapsedRealtime()
    ) {
        val position = player.currentPosition.coerceAtLeast(0L)
        playbackStateName = player.playbackState.playbackStateName()
        playWhenReady = player.playWhenReady
        isPlaying = player.isPlaying
        isLoading = player.isLoading
        playbackSpeed = player.playbackParameters.speed
        playbackPitch = player.playbackParameters.pitch
        positionMs = position
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(position)

        // Spec 70 R2 (red team round 3 finding): a gap between snapshots is time nobody watched (the tick stops on an
        // error and its in-place retry); counting it would read error + retry as a freeze.
        val watchedContinuously = lastSnapshotAtMs >= 0L && now - lastSnapshotAtMs <= SNAPSHOT_GAP_REARM_MS
        lastSnapshotAtMs = now
        val shouldDetectStall = watchedContinuously &&
            hasRenderedFirstFrame &&
            player.playWhenReady &&
            player.isPlaying &&
            player.playbackState == Player.STATE_READY
        if (!shouldDetectStall) {
            // Also re-arms the timer at resume: a freeze right after a pause counts from the resume.
            if (positionStallActive) {
                // Watch ended (pause, buffering, gap) with a freeze open: close it in the log.
                Log.i("NuvioPosFreeze", "FREEZE_END unwatched stallMs=$currentPositionStallMs positionMs=$position")
            }
            lastPositionForStallMs = position
            positionLastAdvancedAtMs = now
            positionStallActive = false
            currentPositionStallMs = 0L
            return
        }

        // A backward jump (seek, rollback) restarts the watch; it never reads as a freeze (Spec 70 R2).
        // Same tolerance both ways, so clock jitter during a real freeze does not keep resetting it.
        if (position > lastPositionForStallMs + POSITION_PROGRESS_EPSILON_MS ||
            position < lastPositionForStallMs - POSITION_PROGRESS_EPSILON_MS
        ) {
            if (positionStallActive) {
                Log.i("NuvioPosFreeze", "FREEZE_END stallMs=${(now - positionLastAdvancedAtMs).coerceAtLeast(0L)} positionMs=$position")
                record(
                    name = "position_stall_recovered",
                    priority = Log.WARN,
                    eventTime = null,
                    details = details(
                        "stallMs" to (now - positionLastAdvancedAtMs).coerceAtLeast(0L).toString(),
                        "positionMs" to position.toString()
                    )
                )
            }
            lastPositionForStallMs = position
            positionLastAdvancedAtMs = now
            positionStallActive = false
            currentPositionStallMs = 0L
            return
        }

        val stalledForMs = (now - positionLastAdvancedAtMs).coerceAtLeast(0L)
        currentPositionStallMs = stalledForMs
        if (stalledForMs >= POSITION_STALL_THRESHOLD_MS) {
            longestPositionStallMs = maxOf(longestPositionStallMs, stalledForMs)
            if (!positionStallActive) {
                positionStallActive = true
                positionStallCount += 1
                Log.i("NuvioPosFreeze", "FREEZE_BEGIN stallMs=$stalledForMs positionMs=$position bufferedMs=${player.bufferedPosition.coerceAtLeast(0L)} state=${player.playbackState.playbackStateName()}")
                record(
                    name = "position_stall",
                    priority = Log.WARN,
                    eventTime = null,
                    details = details(
                        "stallMs" to stalledForMs.toString(),
                        "positionMs" to position.toString(),
                        "bufferedPositionMs" to player.bufferedPosition.coerceAtLeast(0L).toString(),
                        "state" to player.playbackState.playbackStateName()
                    )
                )
            }
        }
    }

    fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        playbackStateName = state.playbackStateName()
        positionMs = eventTime.currentPlaybackPositionMs.safeTimeMs()
        bufferedPositionMs = eventTime.bufferedPositionMs()
    }

    fun onPlayWhenReadyChanged(ready: Boolean) {
        playWhenReady = ready
    }

    fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
    }

    fun onIsLoadingChanged(loading: Boolean) {
        isLoading = loading
    }

    fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        playbackSpeed = playbackParameters.speed
        playbackPitch = playbackParameters.pitch
    }

    fun onSyntheticFirstFrame(player: Player?) {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
    }

    fun onRebufferStarted(player: Player?, count: Int) {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
        record(
            name = "rebuffer_start",
            priority = Log.WARN,
            eventTime = null,
            details = details(
                "count" to count.toString(),
                "positionMs" to (player?.currentPosition?.coerceAtLeast(0L)?.toString())
            )
        )
    }

    fun onRebufferEnded(player: Player?, totalMs: Long, lastDurationMs: Long) {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
        record(
            name = "rebuffer_end",
            priority = Log.WARN,
            eventTime = null,
            details = details(
                "lastDurationMs" to lastDurationMs.coerceAtLeast(0L).toString(),
                "totalMs" to totalMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun onVideoDecoderInitialized(decoderName: String) {
        videoDecoderName = decoderName
    }

    fun onAudioDecoderInitialized(decoderName: String) {
        audioDecoderName = decoderName
    }

    fun onVideoInputFormatChanged(format: Format) {
        videoFormat = format
    }

    fun onVideoTrackSnapshot(format: Format) {
        videoFormat = format
    }

    fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
        droppedFrames += count.coerceAtLeast(0)
        record(
            name = "dropped_video_frames",
            priority = Log.WARN,
            eventTime = eventTime,
            details = details(
                "count" to count.coerceAtLeast(0).toString(),
                "total" to droppedFrames.toString(),
                "elapsedMs" to elapsedMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {
        if (frameCount > 0) {
            videoFrameProcessingOffsetTotalUs += totalProcessingOffsetUs
            videoFrameProcessingOffsetCount += frameCount
        }
    }

    fun onVideoDisabled(counters: DecoderCounters) {
        counters.ensureUpdated()
        if (counters.videoFrameProcessingOffsetCount > 0) {
            videoFrameProcessingOffsetTotalUs = counters.totalVideoFrameProcessingOffsetUs
            videoFrameProcessingOffsetCount = counters.videoFrameProcessingOffsetCount
        }
    }

    fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        audioUnderrunCount += 1
        record(
            name = "audio_underrun",
            priority = Log.WARN,
            eventTime = eventTime,
            details = details(
                "count" to audioUnderrunCount.toString(),
                "bufferSize" to bufferSize.takeIf { it >= 0 }?.toString(),
                "bufferSizeMs" to bufferSizeMs.coerceAtLeast(0L).toString(),
                "elapsedSinceLastFeedMs" to elapsedSinceLastFeedMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun onBandwidthEstimate(totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidthBytesTotal += totalBytesLoaded.coerceAtLeast(0L)
        bandwidthEstimateBps = bitrateEstimate.takeIf { it > 0L }
    }

    fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        loadStartedCount += 1
        record(
            name = "load_started",
            priority = Log.INFO,
            eventTime = eventTime,
            details = loadDetails(loadEventInfo.toPlaybackLoad(mediaLoadData))
        )
    }

    fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        loadCompletedCount += 1
        totalBytesLoaded += loadEventInfo.bytesLoaded.coerceAtLeast(0L)
        val load = loadEventInfo.toPlaybackLoad(mediaLoadData)
        if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
            lastCompletedMediaLoad = load
        }
        record(
            name = "load_completed",
            priority = Log.INFO,
            eventTime = eventTime,
            details = loadDetails(load)
        )
    }

    fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        record(
            name = "load_canceled",
            priority = Log.INFO,
            eventTime = eventTime,
            details = loadDetails(loadEventInfo.toPlaybackLoad(mediaLoadData))
        )
    }

    fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean
    ) {
        loadErrorCount += 1
        record(
            name = "load_error",
            priority = Log.WARN,
            eventTime = eventTime,
            details = loadDetails(loadEventInfo.toPlaybackLoad(mediaLoadData)) + details(
                "exception" to error.javaClass.name,
                "message" to error.message,
                "httpStatus" to error.findCause<HttpDataSource.InvalidResponseCodeException>()
                    ?.responseCode?.toString(),
                "wasCanceled" to wasCanceled.toString()
            )
        )
    }

    fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        record(
            name = "player_error",
            priority = Log.WARN,
            eventTime = eventTime,
            details = details(
                "errorCode" to error.errorCode.toString(),
                "errorCodeName" to error.errorCodeName,
                "exception" to error.javaClass.name,
                "cause" to error.cause?.javaClass?.name,
                "message" to error.message
            )
        )
    }

    /** Refreshes the progress state from [player], then returns the PLAYBACK_STARTUP figures. */
    fun startupCounters(player: Player?, hasRenderedFirstFrame: Boolean): PlaybackStartupCounters {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame) }
        return PlaybackStartupCounters(
            videoFormat = videoFormat,
            bandwidthEstimateBps = bandwidthEstimateBps,
            loadStartedCount = loadStartedCount,
            loadCompletedCount = loadCompletedCount,
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            droppedFrames = droppedFrames,
            audioUnderrunCount = audioUnderrunCount
        )
    }

    /**
     * T9 (ysosrs 422d13e8e, capture instrument): mirrors warning-class events (WARN) and
     * Loader load lifecycle events (INFO) to logcat so a device capture can see load_error
     * exception/dataType and dropped_video_frames timing. media3 load events are per
     * Loader load, not per chunk, so this is low volume. Each call site names its level.
     */
    private fun record(
        name: String,
        priority: Int,
        eventTime: AnalyticsListener.EventTime? = null,
        details: Map<String, String> = emptyMap()
    ) {
        Log.println(priority, EXO_EVENT_LOG_TAG, buildEventLine(name, eventTime, details))
    }

    private fun buildEventLine(
        name: String,
        eventTime: AnalyticsListener.EventTime?,
        details: Map<String, String>
    ): String {
        val elapsedMs = (SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs).coerceAtLeast(0L)
        val position = eventTime?.currentPlaybackPositionMs.safeTimeMs() ?: positionMs
        val bufferedPosition = eventTime?.bufferedPositionMs() ?: bufferedPositionMs
        return "EXO_EVENT: name=$name elapsedMs=$elapsedMs host=$traceHost engine=$traceEngine " +
            "state=${playbackStateName ?: "n/a"} playWhenReady=${playWhenReady ?: false} " +
            "isPlaying=${isPlaying ?: false} isLoading=${isLoading ?: false} " +
            "speed=${playbackSpeed ?: -1f} pitch=${playbackPitch ?: -1f} " +
            "positionMs=${position ?: -1L} bufferedMs=${bufferedPosition ?: -1L} " +
            "details=${details.toTraceDetails()}"
    }
}

private const val EXO_EVENT_LOG_TAG = "NuvioExoEvent"

private fun Map<String, String>.toTraceDetails(): String =
    if (isEmpty()) {
        "n/a"
    } else {
        entries.joinToString(",") { "${it.key}=${it.value.compactTraceValue()}" }
    }

private fun LoadEventInfo.toPlaybackLoad(mediaLoadData: MediaLoadData): PlaybackLoad {
    return PlaybackLoad(
        host = uri.host,
        dataType = mediaLoadData.dataType.dataTypeName(),
        trackType = mediaLoadData.trackType.trackTypeName(),
        httpMethod = dataSpec.getHttpMethodString(),
        position = dataSpec.position.takeIf { it >= 0L },
        length = dataSpec.length.takeIf { it >= 0L },
        durationMs = loadDurationMs.takeIf { it >= 0L },
        bytesLoaded = bytesLoaded.takeIf { it >= 0L }
    )
}

private fun loadDetails(load: PlaybackLoad?): Map<String, String> =
    details(
        "host" to load?.host,
        "dataType" to load?.dataType,
        "trackType" to load?.trackType,
        "method" to load?.httpMethod,
        "position" to load?.position?.toString(),
        "length" to load?.length?.toString(),
        "bytesLoaded" to load?.bytesLoaded?.toString(),
        "durationMs" to load?.durationMs?.toString()
    )

private fun Long?.safeTimeMs(): Long? =
    this?.takeIf { it != C.TIME_UNSET && it >= 0L }

private fun AnalyticsListener.EventTime.bufferedPositionMs(): Long? {
    val position = currentPlaybackPositionMs.safeTimeMs() ?: return null
    return (position + totalBufferedDurationMs.coerceAtLeast(0L)).coerceAtLeast(position)
}

private fun Int.trackTypeName(): String =
    when (this) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_IMAGE -> "image"
        C.TRACK_TYPE_METADATA -> "metadata"
        C.TRACK_TYPE_CAMERA_MOTION -> "camera_motion"
        C.TRACK_TYPE_NONE -> "none"
        C.TRACK_TYPE_DEFAULT -> "default"
        C.TRACK_TYPE_UNKNOWN -> "unknown"
        else -> "custom_$this"
    }

private fun Int.dataTypeName(): String =
    when (this) {
        C.DATA_TYPE_MEDIA -> "media"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "media_initialization"
        C.DATA_TYPE_DRM -> "drm"
        C.DATA_TYPE_MANIFEST -> "manifest"
        C.DATA_TYPE_TIME_SYNCHRONIZATION -> "time_synchronization"
        C.DATA_TYPE_AD -> "ad"
        C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "media_progressive_live"
        C.DATA_TYPE_UNKNOWN -> "unknown"
        else -> "custom_$this"
    }

private fun details(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
