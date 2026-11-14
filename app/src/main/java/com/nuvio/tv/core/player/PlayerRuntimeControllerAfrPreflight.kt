package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * AFR preflight probe budgets.
 *
 * Keep user-facing wait short. Cold debrid/CDN TTFB is handled by a tiny Range warmup
 * inside the OkHttp probe (not by inflating these ceilings). NextLib/extractor only use
 * whatever remains of the total deadline after OkHttp.
 */
internal const val AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS = 15_000L
internal const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 10_000L
internal const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 5_000L
internal const val AFR_PREFLIGHT_TOTAL_TIMEOUT_MS = 18_000L
/** Minimum remaining time to attempt NextLib / extractor after OkHttp. */
internal const val AFR_PREFLIGHT_MIN_STAGE_MS = 2_000L

/** Current display mode id of [activity]'s window, read on Main; null when unavailable. */
private suspend fun currentDisplayModeId(activity: android.app.Activity): Int? =
    withContext(Dispatchers.Main) {
        activity.window?.decorView?.display?.mode?.modeId
    }

/**
 * Shared gate of both preflights: clears the start-delay flags, then returns the host activity,
 * or null when AFR is off (detection state reset), the host is gone, or a preflight for this
 * stream already ran.
 */
private fun PlayerRuntimeController.beginAfrPreflight(
    frameRateMatchingMode: FrameRateMatchingMode,
    label: String
): android.app.Activity? {
    exoDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.copy(
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        }
        return null
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "$label skipped: host activity unavailable")
        return null
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "$label: already running or completed, skipping")
        return null
    }
    return activity
}

/**
 * Shared tail of both preflights: publishes [detection], matches the display to it and, when
 * the mode actually changed, delays the start so the panel settles. Returns true when a
 * display mode was applied (changed or not).
 */
private suspend fun PlayerRuntimeController.applyPreflightDetection(
    activity: android.app.Activity,
    detection: FrameRateUtils.FrameRateDetection,
    resolutionMatchingEnabled: Boolean
): Boolean {
    _uiState.update {
        it.copy(
            detectedFrameRate = detection.snapped,
            detectedFrameRateSource = FrameRateSource.PROBE
        )
    }
    val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
        activity = activity,
        detectedFps = detection.snapped,
        prefer23976Near24 = detection.raw in 23.95f..23.999f
    )
    val initialDisplayModeId = currentDisplayModeId(activity)

    val result = FrameRateUtils.matchFrameRateAndWait(
        activity = activity,
        frameRate = targetFrameRate,
        videoWidth = detection.videoWidth,
        videoHeight = detection.videoHeight,
        resolutionMatchingEnabled = resolutionMatchingEnabled
    ) ?: return false

    val switchedDisplayMode = initialDisplayModeId != null &&
        initialDisplayModeId != result.appliedMode.modeId
    exoDelayStartAfterAfrSwitch = switchedDisplayMode
    if (switchedDisplayMode) lastAfrModeSwitchElapsedMs = SystemClock.elapsedRealtime()

    _uiState.update {
        it.copy(
            displayModeInfo = DisplayModeInfo(
                width = result.appliedMode.physicalWidth,
                height = result.appliedMode.physicalHeight,
                refreshRate = result.appliedMode.refreshRate
            ),
            showDisplayModeInfo = true
        )
    }
    return true
}

/**
 * C-2: derive a video frame rate from the prewarm's already-held head bytes
 * (MKV only). Returns a provisional FrameRateDetection and records the seeded
 * raw rate on the controller so the track-format path can validate it against
 * ExoPlayer's reported rate after prepare (see maybeRunTrackFormatAfr). Gated
 * on ladder proximity: a rate that is not close to a standard rate is not
 * trusted to switch the panel, so it is left to the post-prepare path. The
 * entry is provisional only -- it is NOT written to the persistent cache here;
 * the track-format path promotes it after validation.
 */
private fun PlayerRuntimeController.seedFrameRateFromPrewarmedHead(url: String): FrameRateUtils.FrameRateDetection? {
    val head = com.nuvio.tv.core.player.PrefetchWindowStore.peekHead(android.net.Uri.parse(url))
        ?: return null
    val hint = com.nuvio.tv.core.player.MatroskaAfrProbe.parseVideoFrameRateFromHead(head) ?: return null
    if (!FrameRateUtils.isNearStandardRate(hint.rawFps)) {
        Log.d(PlayerRuntimeController.TAG, "AFR seed rejected: fps=${hint.rawFps} not near a standard rate")
        return null
    }
    val snapped = FrameRateUtils.snapToStandardRate(hint.rawFps)
    afrSeededRateRaw = hint.rawFps
    Log.i(PlayerRuntimeController.TAG, "AFR seed: fps=${hint.rawFps} snapped=$snapped ${hint.width}x${hint.height} (provisional, from prewarm head)")
    return FrameRateUtils.FrameRateDetection(
        raw = hint.rawFps,
        snapped = snapped,
        videoWidth = hint.width,
        videoHeight = hint.height
    )
}

/**
 * nt6 AFR option 1 (ExoPlayer engine path): cache-only preflight.
 *
 * Applies a display-mode switch before prepare() ONLY when a cached detection
 * (or a C-2 prewarm-head seed) exists (instant). No NextLib probe, no MediaExtractor probe — on a cache miss
 * the frame rate comes from ExoPlayer's own track format after prepare (see
 * PlayerRuntimeControllerAfrTrack.kt), so the blocking-native-probe hang on
 * non-faststart MP4s is structurally impossible on this path. The full probing
 * preflight below remains in use for the MPV engine only.
 */
internal suspend fun PlayerRuntimeController.runAfrCachePreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    val activity = beginAfrPreflight(frameRateMatchingMode, "AFR cache preflight") ?: return

    // Keyed with the filename so entries written by the cache writers (the MPV
    // probing preflight, filename-keyed since upstream 0.7.19, and the
    // track-format path in PlayerRuntimeControllerAfrTrack.kt) are visible
    // here. Falls back to the URL-based key when no filename is known — the
    // same rule every writer uses, so the two sides can never disagree.
    val cached = AfrFpsCache.getCachedFrameRate(url, headers, currentFilename)
        ?: seedFrameRateFromPrewarmedHead(url)
        ?: run {
            Log.d(PlayerRuntimeController.TAG, "AFR cache preflight: miss; deferring to track-format AFR after prepare")
            return
        }

    Log.d(PlayerRuntimeController.TAG, "AFR cache preflight: cache hit! Using cached FPS=${cached.snapped}")
    if (applyPreflightDetection(activity, cached, resolutionMatchingEnabled)) {
        // Track-format AFR stands down when the cache path already ran a
        // mode selection for this stream (whether or not the mode changed).
        afrModeAppliedPreStart = true
    }
}

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    mimeType: String? = null
) {
    val activity = beginAfrPreflight(frameRateMatchingMode, "AFR preflight") ?: return

    _uiState.update {
        it.copy(
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    // Original stream headers (without Range) – used for NextLib bypass decision.
    // If these contain auth/custom headers, NextLib is skipped (MediaInfoBuilder cannot forward them).
    val streamHeaders = AfrMediaProbe.streamHeadersForAfrProbe(headers)
    // Extractor fallback headers – add Connection: close for proper connection teardown.
    val probeHeaders = AfrMediaProbe.extractorProbeHeaders(headers)
    val effectiveMimeType = mimeType ?: currentStreamMimeType
    val filename = currentFilename
    val deadlineElapsedRealtime = SystemClock.elapsedRealtime() + AFR_PREFLIGHT_TOTAL_TIMEOUT_MS

    fun remainingMs(): Long =
        (deadlineElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    try {
        val cached = AfrFpsCache.getCachedFrameRate(url, headers, filename)
        if (cached != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: cache hit! Using cached FPS=${cached.snapped}")
            applyPreflightDetection(activity, cached, resolutionMatchingEnabled)
            return
        }

        val okHttpBudget = minOf(AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS, remainingMs())
        val okHttpDetection = if (okHttpBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
            withTimeoutOrNull(okHttpBudget) {
                withContext(Dispatchers.IO) {
                    // Blocking OkHttp calls ignore coroutine cancellation; hand the probe a
                    // signal tied to this job so timeout/cancel actually stops the downloads.
                    val probeJob = coroutineContext[Job]
                    AfrHttpProbe.detectFrameRateWithOkHttpProbe(
                        context = context,
                        sourceUrl = url,
                        headers = streamHeaders,
                        mimeType = effectiveMimeType,
                        filename = filename,
                        isCancelled = { probeJob?.isActive != true }
                    )
                }
            }
        } else {
            null
        }

        val detection = if (okHttpDetection != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: OkHttp probe succeeded! FPS=${okHttpDetection.snapped}")
            okHttpDetection
        } else {
            val nextLibBudget = minOf(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS, remainingMs())
            val nextLibDetection = if (nextLibBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
                withTimeoutOrNull(nextLibBudget) {
                    withContext(Dispatchers.IO) {
                        AfrMediaProbe.detectFrameRateFromNextLib(
                            context = context,
                            sourceUrl = url,
                            headers = streamHeaders,
                            mimeType = effectiveMimeType,
                            filename = filename
                        )
                    }
                }
            } else {
                null
            }
            if (nextLibDetection != null) {
                nextLibDetection
            } else {
                val fallbackBudget = minOf(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS, remainingMs())
                if (fallbackBudget < AFR_PREFLIGHT_MIN_STAGE_MS) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight: no time left for extractor fallback (remaining=${remainingMs()}ms)"
                    )
                    null
                } else {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight NextLib probe failed/timed out; trying extractor fallback (${fallbackBudget}ms)"
                    )
                    withTimeoutOrNull(fallbackBudget) {
                        withContext(Dispatchers.IO) {
                            AfrMediaProbe.detectFrameRateFromExtractor(
                                context = context,
                                sourceUrl = url,
                                headers = probeHeaders
                            )
                        }
                    }
                }
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (OkHttp + NextLib + extractor fallback)"
            )
            return
        }

        AfrFpsCache.cacheFrameRate(url, headers, detection, currentFilename)
        applyPreflightDetection(activity, detection, resolutionMatchingEnabled)
    } finally {
        withContext(NonCancellable) {
            _uiState.update { it.copy(afrProbeRunning = false) }
        }
    }
}
