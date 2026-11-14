package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.connectionsInUse
import com.nuvio.tv.core.assessment.perPlayNetwork
import com.nuvio.tv.core.assessment.playbackDeviceLimits
import com.nuvio.tv.core.assessment.qualifiesForNetworkRule
import com.nuvio.tv.core.assessment.serviceKeyOf
import android.content.Context
import android.content.res.Resources
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.core.di.trimImageMemoryCacheForPlayback
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.common.Tracks


// AFR review F1: absolute cap on how long playback start may wait for the AFR
// preflight. In-probe timeouts (NextLib 6s + extractor 4s budgets) are advisory
// against blocked native calls; the extractor now also has a force-release
// watchdog. This is the final backstop.
private const val AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS = 15_000L

/**
 * The attributes playback uses. One home: the TV sink's pinned capability probe, the F3 live
 * re-probe (liveOutputClaimsPassthrough) and the T14 AVR-wake check must ask the platform the
 * same question.
 */
internal val movieAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * AFR review F6/R4: with Nuvio's own AFR off, leave media3's built-in
 * Surface.setFrameRate path on ONLY_IF_SEAMLESS so displays/firmware that
 * support seamless switching still get a free, blackout-less refresh match.
 * With Nuvio AFR on, keep it OFF so the two mechanisms don't fight.
 */
private fun nuvioFrameRateStrategy(mode: FrameRateMatchingMode): Int =
    if (mode == FrameRateMatchingMode.OFF) {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    } else {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    }
// Main review F10: ExoPlayer.release() blocks the calling thread until the
// internal playback thread joins, bounded by this timeout — and the app-layer
// caller is always Main (viewModelScope). Moving release() off Main is
// engine-blocked: the fork AAR's ExoPlayerImpl.release() calls
// verifyApplicationThread(), which throws off the application looper
// (verified by decompile). The supported lever is this timeout: 1000ms caps
// the worst-case Main block (was 3000ms) while comfortably covering healthy
// releases (typically <100ms, slow codec flush ~500ms). A genuinely wedged
// decoder behaves identically either way — release() gives up and the
// playback thread is abandoned — it just stops holding the UI hostage for an
// extra 2s first. PLAYER_RELEASE: log lines measure real-world durations.
internal const val PLAYER_RELEASE_TIMEOUT_MS = 1000L
private const val PLAYER_REBUILD_SETTLE_DELAY_MS = 120L
private const val ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS = 2_000
private const val ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L

/** Audio Path row for the app-side TrueHD-to-MAT wrapper, which emits no track-init event. */
internal const val MAT_AUDIO_PATH_DESCRIPTION = "TrueHD \u2192 MAT passthrough, app-packed (IEC61937 192 kHz, 8ch)"

private fun PlayerRuntimeController.resolveCurrentStreamMimeType(url: String) {
    currentStreamMimeType?.let { resolvedMimeType ->
        Log.d(
            PlayerRuntimeController.TAG,
            "Resolved stream mimeType=$resolvedMimeType for host=${url.safeHost()}"
        )
        return
    }
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} for host=${url.safeHost()}"
    )
}

/**
 * nt12: the constructor-baked configuration of an ExoPlayer build. Everything here
 * is fixed at construction (renderers-factory arguments, load-control geometry,
 * bandwidth-meter mode, the libass build fork); per-media-source and live-settable
 * state (track-selector parameters, frame-rate strategy, extractors config) is
 * deliberately excluded and re-applied on the reuse branch instead.
 */
internal data class ExoConstructionFingerprint(
    val useLibass: Boolean,
    val isHls: Boolean,
    val performanceModeEnabled: Boolean,
    val bufferEngineEnabled: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferDurationMs: Int,
    val retainBackBufferFromKeyframe: Boolean,
    val targetBufferSizeMb: Int,
    val bufferBudgetManaged: Boolean,
    val allowLargeTargetBuffer: Boolean,
    val downmixEnabled: Boolean,
    val audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    val maintainOriginalAudioOnDownmix: Boolean,
    val forceOpticalPassthroughActive: Boolean,
    val matPassthroughEnabled: Boolean,
    val initialForcePcm: Boolean,
    val audioPassthroughPolicy: com.nuvio.tv.core.player.AudioPassthroughPolicy,
    val deniedTranscodeMimes: Set<String>,
    val preferFfmpegAudio: Boolean,
    val extensionRendererMode: Int,
    val convertToDv81Active: Boolean,
    val mapDv7ToHevc: Boolean,
    val tunnelingEnabled: Boolean
)

/**
 * nt14: push the current player settings onto the shared media-source factory.
 *
 * Extracted from initializePlayer because the nt13 chunk-0 pre-start runs
 * earlier, in switchToEpisodeStream, and the factory's geometry fields
 * (parallel on/off, connection count, chunk size) are what the companion
 * session store keys on. Applied only inside initializePlayer, the pre-start
 * would key a session on whatever the PREVIOUS stream left behind, and the
 * player would then decline to adopt it. Idempotent, so calling it from both
 * sites is safe.
 */
internal fun PlayerRuntimeController.applyMediaSourceFactorySettings(playerSettings: PlayerSettings) {
    if (playerSettings.parallelNetworkEnabled) {
        mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
        mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
        mediaSourceFactory.parallelChunkSizeKb = playerSettings.parallelChunkSizeKb
        mediaSourceFactory.nuvioPerformanceModeEnabled = playerSettings.nuvioPerformanceModeEnabled
    } else {
        // Reset each playback so the factory doesn't keep last stream's state.
        mediaSourceFactory.useParallelConnections = false
        mediaSourceFactory.nuvioPerformanceModeEnabled = false
    }
    lastAppliedPlayerSettings = playerSettings
}

/**
 * Spec 33 D4: the settings this play runs on, [saved] with the per-service network row for this
 * film when the rule applies (perPlayNetwork; a copy, nothing written). Read once per
 * initializePlayer, before anything consumes buffer or network values.
 */
internal suspend fun PlayerRuntimeController.withPerPlayNetwork(saved: PlayerSettings, url: String): PlayerSettings {
    val sizeBytes = currentVideoSize
    // Cheap exit first: most plays never need the tables read.
    if (!qualifiesForNetworkRule(url, sizeBytes, isTorrentStream)) return saved
    val result = perPlayNetwork(
        saved = saved,
        tables = ServiceNetworkTables.decode(playerSettingsDataStore.serviceNetworkTablesJson.first()),
        url = url,
        sizeBytes = sizeBytes,
        runtimeMinutes = expectedRuntimeMinutes,
        isTorrent = isTorrentStream,
        limits = playbackDeviceLimits(context),
        controllerHttp2 = mediaSourceFactory.chunkClientHttp2
    )
    result.choice?.let { choice ->
        val c = choice.config
        Log.i(
            PlayerRuntimeController.TAG,
            "NETWORK_RULE: service=${serviceKeyOf(url)} row=${choice.row.connections}x${choice.row.chunkMb}MB " +
                "rowMbps=${"%.1f".format(choice.row.mbps)} mayStall=${choice.mayStall} " +
                "parallel=${c.parallel} ${c.connections}x${c.chunkMb}MB target=${c.targetBufferMb}MB " +
                "backMs=${c.backBufferMs} maxMs=${c.maxBufferMs} h2=${c.http2}"
        )
    }
    return result.settings
}

/**
 * nt10: returns true when an ExoPlayer was actually released, so the caller
 * can skip the settle that only a release needs.
 */
internal fun PlayerRuntimeController.disposeExoPlayerBeforeRebuild(): Boolean {
    notifyAudioSessionUpdate(false)
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (_: Exception) {
    }
    _exoPlayer?.let { player ->
        // Main review F10/F11: pause()/clearMediaItems() dropped — each is a
        // redundant player-thread round trip immediately before release()
        // (playWhenReady=false already pauses; release() discards the item
        // queue). stop() is kept: it halts renderers early, which shortens the
        // release join on a live pipeline.
        runCatching { player.playWhenReady = false }
        runCatching { player.stop() }
        runCatching { player.clearVideoSurface() }
        val releaseStartMs = android.os.SystemClock.elapsedRealtime()
        runCatching { player.release() }
        val releaseMs = android.os.SystemClock.elapsedRealtime() - releaseStartMs
        Log.i(
            PlayerRuntimeController.TAG,
            "PLAYER_RELEASE: site=rebuild exoReleaseMs=$releaseMs timeoutMs=$PLAYER_RELEASE_TIMEOUT_MS"
        )
    }
    val releasedExistingPlayer = _exoPlayer != null
    _exoPlayer = null
    playbackSpeedAwareAudioSink = null
    currentExoPlayerListener = null
    currentExoAnalyticsListener = null
    return releasedExistingPlayer
}

/**
 * Applies a [PlayerStartupPlaybackPolicy] start action through the fork's AFR gates.
 * [start] is whether the action starts playback (always true except a paused
 * TunneledFirstReady).
 *
 *  - Track-AFR gate (nt6): while a track-format-driven AFR switch is in flight the
 *    start is suppressed; resumePlaybackAfterTrackAfrIfHeld() (deadline-bounded, with
 *    its own settle delay) resumes playback when the gate releases.
 *  - Display-AFR settle hold (AFR review R5 / community 0.7.14 QMS stutter report):
 *    when the preflight actually changed the display mode, hold the first start for
 *    the "Wait after display switch" setting (Spec 62 H1, default 2 s) so the A/V
 *    pipeline does not begin inside the HDMI mode transition. On QMS-capable chains
 *    the seamless switch reports complete almost instantly, so an immediate start can
 *    otherwise start the hardware clock mid-transition and latch bad frame pacing for
 *    the whole title. While it runs (afrSettleHoldInFlight) every other start site
 *    stands down: the tunneled post-READY seek's second READY and the first frame
 *    used to start playback at once, skipping the hold. Timer-released, never
 *    event-gated — cannot deadlock startup; no-switch starts are unaffected.
 */
private fun PlayerRuntimeController.startPlaybackThroughAfrGates(
    player: ExoPlayer,
    start: Boolean,
    holdForAfrSettle: Boolean
) {
    if (!start) return
    if (afrStartGateHeld) return
    val settleHoldMs = afrSettleHoldMs(currentPlayerSettings.afrSettleHoldSeconds)
    if (holdForAfrSettle && settleHoldMs > 0L) {
        // Park the player as the track gate does: an in-place auto-retry before the first READY
        // may already have set playWhenReady (review d3f0b468). A no-op on the normal path.
        player.playWhenReady = false
        afrSettleHoldInFlight = true
        // The nt12 path reuses the ExoPlayer across streams: the generation, not the
        // instance, says this hold still belongs to the current stream.
        val generation = afrTrackGeneration
        scope.launch {
            kotlinx.coroutines.delay(settleHoldMs)
            if (afrTrackGeneration != generation) return@launch
            afrSettleHoldInFlight = false
            // A track-AFR switch that began meanwhile starts playback on its own release.
            if (_exoPlayer === player && !userPausedManually && !afrTrackSwitchInFlight) {
                player.play()
            }
        }
        return
    }
    player.play()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    // A newer init supersedes any still in its suspending prelude: left running,
    // the stale coroutine would dispose this one's player or build the old URL.
    cancelPlayerInit()
    if (url.isEmpty()) {
        // Spec 75: an attempt of its own (NO_URL fails at Getting the link), not the previous one's marks.
        stepClock.start(android.os.SystemClock.elapsedRealtime(), stepSourcesFoundMs, url = null)
        val failure = playbackFailure(
            cause = com.nuvio.tv.core.stream.FailureCause.NO_URL,
            message = context.getString(R.string.player_error_no_stream_url)
        )
        _uiState.update { it.copy(error = failure, showLoadingOverlay = false) }
        return
    }

    // LAZY + assign-before-start: the body can run inline (Main.immediate) and
    // re-enter initializePlayer (engine/source failover); the nested call's job
    // must be the one left in playerInitJob.
    val initJob = scope.launch(start = CoroutineStart.LAZY) {
        try {
            // nt12 reuse: snapshot the live player's constructor-baked companions
            // before the per-stream resets and construction overwrite the fields.
            // Consumed only on the reuse branch at the build fork.
            val previousTrackSelectorForReuse = trackSelector
            val previousLoadControlForReuse = _loadControl
            val previousBitrateAwareLoadControlForReuse = currentBitrateAwareLoadControl
            // The back buffer is baked into ExoPlayerImplInternal at construction and never
            // re-read, so a reused player keeps the value it was first built with. Capture it
            // here, before the per-stream reset below zeroes the field, so the reuse branch can
            // report what the engine is actually running.
            val previousEffectiveBackBufferMsForReuse = effectiveBackBufferDurationMs
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            // Spec 61 Q3: settled before the first suspension, so a same-stream restart fired
            // during this prelude never reads the previous stream's engine
            // (currentStreamEngineForRestart). The session engine is set once resolved, below.
            runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
            sessionPlayerEngine = null
            trackSelection.autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
            backgroundHeldMpvHopReason = null
            lastPlaybackDiagnostics = LastPlaybackDiagnostics.EMPTY
            playbackFatalErrorRecorded = false
            playbackAnalyticsDiagnostics.reset()
            resetLoadingOverlayForNewStream(streamUrl = url)
            trimImageMemoryCacheForPlayback(context)
            if (startPaused) {
                userPausedManually = true
                shouldEnforceAutoplayOnFirstReady = false
            }
            val applyPcmFallbackOnStartup = pendingAudioPcmFallbackRebuild
            if (!applyPcmFallbackOnStartup) {
                hasTriedAudioPcmFallback = false
            }
            // F9: fresh stream, fresh Audio Path row. Without this the field was
            // write-once per controller, so a stream whose track-init event never
            // fires inherited the previous stream's row - a wrong answer, worse
            // than the dash.
            currentAudioPathDescription = null
            // nt6 AFR option 1: fresh stream, fresh track-AFR state.
            // P-F3: bump the generation so an in-flight track-AFR coroutine
            // from the previous stream stands down in its finally.
            afrTrackGeneration++
            trackAfrAttemptedForCurrentStream = false
            afrTrackSwitchInFlight = false
            afrSettleHoldInFlight = false
            afrModeAppliedPreStart = false
            afrSeededRateRaw = 0f
            // Seek review F3: never carry a preview position into a new player /
            // stream - a leaked value would commit the *new* episode to the old
            // preview position on the next gesture.
            pendingPreviewSeekExpiryJob?.cancel()
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            // Audio review F8: fresh stream, fresh output-mode state; the F9
            // listener re-derives it at AudioTrack init.
            isAudioOutputBypassing = false
            playerInitializationStartedAtMs = System.currentTimeMillis()
            // Reset per playback; only the ExoPlayer custom-buffer path sets a real value.
            effectiveBackBufferDurationMs = 0
            currentBitrateAwareLoadControl = null

            val savedPlayerSettings = playerSettingsDataStore.playerSettings.first()
            // var only for the Spec 33 D6 network test hold below, which may rewrite the network rows.
            var playerSettings = withPerPlayNetwork(savedPlayerSettings, url)
            currentPlayerSettings = playerSettings
            rememberAudioDelayPerDeviceEnabled = playerSettings.rememberAudioDelayPerDevice
            // Always watch output-device changes so Bluetooth connect/disconnect can switch
            // PCM/passthrough policy in place (Media3 1.8.0 BT semantics; do not rebuild).
            registerAudioDelayRouteCallback()
            currentAudioOutputRoute = AudioOutputRouteDetector.detect(context)
            if (rememberAudioDelayPerDeviceEnabled) {
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }
            cachedDecoderPriority = playerSettings.decoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
            mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
            var effectiveInternalPlayerEngine = startupEngineBeforeAuto(
                override = overrideInternalPlayerEngine,
                setting = playerSettings.internalPlayerEngine,
                startsOnMpvByName = PlayerMediaSourceFactory.startsOnMpvByName(url, currentFilename)
            )
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.AUTO) {
                effectiveInternalPlayerEngine = resolveAutoInternalPlayerEngine()
            }
            // The settings observer re-derives the engine as override ?: this ?: setting on every
            // settings write, so the engine this start runs is kept here: an automatic choice (AUTO
            // or a start-on-mpv name) would flip back, and an engine changed in Settings over the
            // paused film (Spec 33 D7 pause chip) would swap the surface under the live player.
            sessionPlayerEngine = sessionEngineToKeep(
                override = overrideInternalPlayerEngine,
                effective = effectiveInternalPlayerEngine
            )
            currentInternalPlayerEngine = effectiveInternalPlayerEngine
            playbackAnalyticsDiagnostics.setTraceContext(
                host = url.safeHost(),
                engine = effectiveInternalPlayerEngine.name
            )
            _uiState.update { it.withNetworkTestResultFor(url, networkTestHeldUrl) }
            // Spec 33 D6: the first play from a streaming service holds here for its network test,
            // before any LoadControl or player exists (mpv never reads the buffer / network rows).
            // Only buffer and network fields can change, all read below this point.
            val networkTest = if (effectiveInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER) {
                holdForNetworkTestIfDue(savedPlayerSettings, url, headers)
            } else {
                null
            }
            if (networkTest != null) {
                // Spec 75: the test's own requests to this URL are not the play's link or
                // connection; the attempt (and its step times) starts after the hold.
                stepClock.start(android.os.SystemClock.elapsedRealtime(), stepSourcesFoundMs, url)
                playerSettings = withPerPlayNetwork(playerSettingsDataStore.playerSettings.first(), url)
                currentPlayerSettings = playerSettings
                // Spec 59 D3: an alias to a service already measured is no test result to show.
                if (networkTest.measured) {
                    val result = NetworkTestResult(networkTest.serviceKey, connectionsInUse(playerSettings))
                    _uiState.update { it.copy(networkTestResult = result, networkTestNoticePending = true) }
                }
            }
            val deviceAspectMode = deviceLocalPlayerPreferences.aspectMode.first()
            // F-1: reset before the UI flag below, so a settings write in this start's preflight
            // window cannot re-derive it from the previous playback's guard.
            isTunnelingSuppressedForDvConversion = false
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    aspectMode = deviceAspectMode,
                    tunnelingEnabled = playerSettings.effectiveTunnelingEnabled &&
                            effectiveInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER
                )
            }
            setLoadingStatus(
                phase = "detecting_format",
                message = context.getString(R.string.player_loading_detecting_format)
            )

            resolveCurrentStreamMimeType(url = url)

            val afrJob = async {
                if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                    // MPV has no track-format path; keep the full probing preflight.
                    // 0.8.0 merge: thread the resolved mimeType through so the
                    // probe can route extensionless MP4/MKV URLs correctly
                    // (upstream's MatroskaAfrProbe preflight fix).
                    runAfrPreflightIfEnabled(
                        url = url,
                        headers = headers,
                        frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                        resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                        mimeType = currentStreamMimeType
                    )
                } else {
                    // nt6 AFR option 1 (ExoPlayer): cache-only, instant. On a
                    // cache miss the frame rate comes from ExoPlayer's reported
                    // track format after prepare — no MediaExtractor/NextLib
                    // network probe on this path at all.
                    runAfrCachePreflightIfEnabled(
                        url = url,
                        headers = headers,
                        frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                        resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                    )
                }
            }
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                mpvInitializationInProgress = true
                try {
                    // AFR review F1: never let the probe hold playback hostage -
                    // the in-probe timeouts are advisory against blocked native
                    // calls, so back them with an absolute deadline here.
                    // (0.7.20 merge: kept over upstream's 12 s total timeout --
                    // upstream's own probe chain is 10 s OkHttp + 10 s NextLib +
                    // 5 s extractor, so a 12 s cap can truncate its own fallback;
                    // 15 s matches upstream's asserted worst-case probe budget.)
                    withTimeoutOrNull(AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS) { afrJob.await() }
                        ?: run {
                            Log.w(PlayerRuntimeController.TAG, "AFR preflight exceeded absolute deadline; starting playback without it")
                            afrJob.cancel()
                        }
                    // Spec 62 H1 (D9): the same "Wait after display switch" hold as Exo's paths, for
                    // what is left of it since the last real switch, whoever made it (Spec 85: the
                    // preflight above finds the panel already in mode after ExoPlayer's pre-start
                    // switch and a hop to mpv).
                    val mpvSettleHoldMs = afrSettleHoldRemainingMs(
                        holdMs = afrSettleHoldMs(playerSettings.afrSettleHoldSeconds),
                        lastSwitchElapsedMs = lastAfrModeSwitchElapsedMs,
                        nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                    )
                    if (mpvSettleHoldMs > 0L) {
                        Log.d(PlayerRuntimeController.TAG, "AFR settle hold: delaying MPV start by ${mpvSettleHoldMs}ms (rest of the hold since the last display switch)")
                        delay(mpvSettleHoldMs)
                    }
                    setLoadingStatus(
                        phase = "mpv_buffering",
                        message = context.getString(R.string.player_loading_buffering)
                    )
                    // nt7 (task 2): the MPV path reads the resume position
                    // inside initializeMpvPlayer (a plain fun), so the join
                    // happens here, in this coroutine, before entry.
                    awaitSavedProgressLoad()
                    initializeMpvPlayer(url = url, headers = headers, allowEngineFailover = allowEngineFailover)
                    fetchAddonSubtitles()
                } finally {
                    // Cancelled = superseded or released: cancelPlayerInit already
                    // cleared the flag, and a newer init may have set it since.
                    if (isActive) mpvInitializationInProgress = false
                }
                return@launch
            }
            mpvInitializationInProgress = false

            // ── ExoPlayer Dolby Vision Logic (mode-driven via Dv7HandlingMode) ──
            DoviBridge.resetRuntimeCounters()
            DolbyVisionConversionStats.reset()
            rebufferCount = 0
            truehdStormRecoveryAttempts = 0
            truehdStormLastRecoveryAtMs = 0L
            truehdStormOnsetPosMs = -1L
            snapShadowLastTickPosMs = -1L
            snapShadowLastTickWallMs = 0L
            snapShadowLastDiscontinuityWallMs = 0L
            snapRecoveryPendingPosMs = -1L
            snapRecoveryPendingAtWallMs = 0L
            snapLastSuspectWallMs = 0L
            snapEarlyResetLastAtMs = 0L
            stormRecoveryTotalThisPlayback = 0
            freezeRecoverySeekAtMs = null
            freezeFailoverDone = false
            rebufferTotalMs = 0L
            rebufferStartedAtMs = 0L
            lastSeekWallMs = 0L
            currentRebufferSeekInduced = false

            // Resolve effective DV7 mode — AUTO consults the display-capability policy.
            // The persisted enum stays as-is; only the runtime behavior is derived per playback.
            var effectiveDv7Mode: Dv7HandlingMode
            val dv7AutoResult: DolbyVisionBaseLayerPolicy.Result?
            when (playerSettings.dv7HandlingMode) {
                Dv7HandlingMode.AUTO -> {
                    val result = DolbyVisionBaseLayerPolicy.resolve(
                        context = context,
                        bridgeReady = DoviBridge.isLibraryLoaded
                    )
                    dv7AutoResult = result
                    effectiveDv7Mode = when (result.decision) {
                        DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> Dv7HandlingMode.OFF
                        DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> Dv7HandlingMode.DV81_LIBDOVI
                        else -> Dv7HandlingMode.HDR10_BASE_LAYER
                    }
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "DV7_AUTO: decision=${result.decision} " +
                                "effectiveMode=$effectiveDv7Mode " +
                                "hdrCapsKnown=${result.hdrCapsKnown} " +
                                "displayDv=${result.displayDv} " +
                                "displayHdr10=${result.displayHdr10} " +
                                "displayHdr10Plus=${result.displayHdr10Plus} " +
                                "codecDvheDtb=${result.codecSupportsDvheDtb} " +
                                "bridgeReady=${result.bridgeReady} " +
                                "api=${result.apiLevel} " +
                                "host=${url.safeHost()}"
                    )
                }
                else -> {
                    dv7AutoResult = null
                    effectiveDv7Mode = playerSettings.dv7HandlingMode
                }
            }
            // Spec 61 Q9: a stream the DV_HDR10 recovery rungs forced plays exactly as the
            // manual HDR10 base-layer mode (extractor base layer + RPU strip, conversion off).
            // The renderer's mapDV7ToHevc alone does nothing in the vendored AAR. A profile 5
            // stream (no base layer) only gets conversion disarmed and the DV ladder latched.
            val dv7Hdr10ForcedForUrl = dv7ToHevcForcedStreamUrls.contains(url)
            val dvConversionDisarmedForUrl = dv7Hdr10ForcedForUrl ||
                dvConversionDisarmedStreamUrls.contains(url)
            if (dv7Hdr10ForcedForUrl) {
                effectiveDv7Mode = Dv7HandlingMode.HDR10_BASE_LAYER
            }

            // Experimental: explicit libdovi conversion-mode override. Only applies
            // when DV7 handling is Convert to DV8.1 (the modes are libdovi conversion
            // modes, so they're only meaningful while conversion is active). Picks
            // which conversion mode runs; -1 (None) uses the auto-selected mode.
            val libdoviModeOverride = playerSettings.dv7LibdoviModeOverride
            val libdoviModeOverrideActive = libdoviModeOverride in 0..4 &&
                    playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI &&
                    effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            if (libdoviModeOverrideActive) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_LIBDOVI_OVERRIDE: forcing conversion mode=$libdoviModeOverride"
                )
            }

            // DV7 to DV8.1 libdovi probe — only runs when the effective mode requests it.
            val dv7ToDv81SettingActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            val dv7ToDv81Probe = if (dv7ToDv81SettingActive) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                val reason = when (effectiveDv7Mode) {
                    Dv7HandlingMode.HDR10_BASE_LAYER -> "hdr10-base-layer-mode"
                    Dv7HandlingMode.STRIP_DV -> "strip-dv-mode"
                    Dv7HandlingMode.OFF -> "dv7-mode-off"
                    Dv7HandlingMode.AUTO -> "auto-mode-no-dv81"  // unreachable; AUTO is collapsed above
                    Dv7HandlingMode.DV81_LIBDOVI -> "setting-disabled"  // unreachable
                }
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = reason,
                    bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
                    extractorHookReady = DoviBridge.isExtractorHookReadyInBuild
                )
            }
            // A recovery retry must not rebuild the conversion pipeline that just failed.
            isExperimentalDv7ToDv81ActiveForCurrentPlayback =
                dv7ToDv81SettingActive && dv7ToDv81Probe.supported && !dvConversionDisarmedForUrl
            // AUTO fallback: if AUTO chose DV81 but the probe failed for this stream,
            // downgrade to HDR10_BASE_LAYER so the user still gets a picture.
            if (playerSettings.dv7HandlingMode == Dv7HandlingMode.AUTO &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !dv7ToDv81Probe.supported
            ) {
                effectiveDv7Mode = Dv7HandlingMode.HDR10_BASE_LAYER
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_AUTO_FALLBACK: dv81-probe-failed reason=${dv7ToDv81Probe.reason} " +
                            "fallback=HDR10_BASE_LAYER host=${url.safeHost()}"
                )
            }
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: mode=${playerSettings.dv7HandlingMode} " +
                        "effectiveMode=$effectiveDv7Mode " +
                        "forcedHdr10=$dv7Hdr10ForcedForUrl " +
                        "conversionDisarmed=$dvConversionDisarmedForUrl " +
                        "dv81Active=$dv7ToDv81SettingActive " +
                        "dv5Compat=${playerSettings.dv5ToDv81Enabled} " +
                        "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
                        "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
                        "extractorHookReady=${dv7ToDv81Probe.extractorHookReady} " +
                        "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                        "reason=${dv7ToDv81Probe.reason} " +
                        "bridge=${dv7ToDv81Probe.bridgeVersion ?: "n/a"} " +
                        "host=${url.safeHost()}"
            )

            // ── Diagnostics builder ──
            // Built incrementally during init; written to DataStore on terminal events
            // (first frame rendered = "Played", or final error display = "Error: ...").
            var currentDiagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = url.safeHost(),
                streamUrl = url,
                filename = currentFilename ?: streamName ?: title,
                headersJson = org.json.JSONObject(headers).toString(),
                hdrCapsKnown = dv7AutoResult?.hdrCapsKnown ?: false,
                displayDv = dv7AutoResult?.displayDv ?: false,
                displayHdr10 = dv7AutoResult?.displayHdr10 ?: false,
                displayHdr10Plus = dv7AutoResult?.displayHdr10Plus ?: false,
                // DV7 F3: in manual modes the AUTO probe never runs; query the
                // decoder capability directly so the Diagnostics row is honest.
                codecDv7Supported = dv7AutoResult?.codecSupportsDvheDtb
                    ?: DolbyVisionBaseLayerPolicy.queryCodecDv7Support(),
                dv81DecoderName = null,
                bridgeReady = DoviBridge.isLibraryLoaded,
                bridgeVersion = dv7ToDv81Probe.bridgeVersion,
                bridgeReason = dv7ToDv81Probe.reason,
                dv7ModeRequested = playerSettings.dv7HandlingMode.name,
                dv7ModeEffective = effectiveDv7Mode.name,
                dv7AutoDecision = dv7AutoResult?.decision?.name,
                dvSourceProfile = null,
                dv7DoviCalls = 0,
                dv7DoviSuccess = 0,
                dv7DoviSignalRewrites = 0,
                bufferEngineEnabled = false,
                parallelNetworkEnabled = false,
                firstFrameMs = -1L,
                result = "Pending"
            )

            // ── Buffer & Network ──
            // Master toggles off => stock Media3 (DefaultLoadControl, single connection,
            // no cache). DV7 to DV8.1 conversion runs libdovi off-heap, outside the heap
            // budget; a large heap buffer on top of that is what pushed the Fire TV into the
            // lowmemorykiller spiral, so for confirmed DV7 on a low heap we drop the back buffer
            // and shrink the budget at first frame (below).
            val libdoviConversionActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            NuvioExoPlayerPerformanceHelper.updateSettings(playerSettings, context)
            NuvioExoPlayerPerformanceHelper.enabled = playerSettings.nuvioPerformanceModeEnabled
            val streamMime = currentStreamMimeType
            val isHls = streamMime != null && (
                streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                streamMime.lowercase().contains("mpegurl") ||
                streamMime.lowercase().contains("m3u8")
            )
            val rawBandwidthMeter = if (NuvioExoPlayerPerformanceHelper.enabled) {
                NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(context)
            } else {
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
                    .build()
            }
            val bandwidthMeter = SafeBandwidthMeter(rawBandwidthMeter, isHls)
            // nt12 reuse: the custom-buffer budget must be visible at the build fork so a
            // reused BitrateAwareLoadControl can be reset to build-equivalent state.
            var customBufferBudgetBytes = 0L
            val loadControl = if (playerSettings.nuvioPerformanceModeEnabled) {
                effectiveBackBufferDurationMs = NuvioExoPlayerPerformanceHelper.backBufferMs
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-native-perf master=on; NuvioExoPlayerPerformanceHelper.buildLoadControl host=${url.safeHost()}"
                )
                NuvioExoPlayerPerformanceHelper.buildLoadControl()
            } else if (playerSettings.bufferEngineEnabled) {
                val bufferSettings = playerSettings.bufferSettings
                // Managed (default) caps the buffer at the device budget; off uses Target Buffer Size.
                // Stay full here even on a DV display; first frame tightens only for confirmed DV7.
                val budgetManaged = playerSettings.bufferBudgetManaged
                val budgetMbEffective = if (budgetManaged) {
                    MemoryBudget.budgetMb
                } else {
                    MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
                        .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
                }
                customBufferBudgetBytes = budgetMbEffective.toLong() * 1024L * 1024L
                // Build with the user's back buffer so seek-back works immediately (it can't
                // depend on the player re-polling the LoadControl). First frame only lowers it
                // to 0 for confirmed DV7 on a low heap (MemoryBudget.isLowHeapTier); everything else keeps it.
                val backBufferMsAtBuild = bufferSettings.backBufferDurationMs
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-custom master=on " +
                            "lowHeap=${MemoryBudget.isLowHeapTier} lowRam=${MemoryBudget.isLowRamTier} " +
                            "allowLarge=${playerSettings.allowLargeTargetBuffer} " +
                            "dv7conv=$libdoviConversionActive " +
                            "managed=$budgetManaged " +
                            "backBufferMsAtBuild=$backBufferMsAtBuild (lowered to 0 only for real DV7) " +
                            "budgetMb=$budgetMbEffective host=${url.safeHost()}"
                )
                effectiveBackBufferDurationMs = backBufferMsAtBuild
                val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(
                    true,
                    C.DEFAULT_BUFFER_SEGMENT_SIZE,
                    /* initialAllocationCount = */ 0, // Spec 36/S2 (B7-4): fills on demand
                    playerSettings.nuvioPerformanceModeEnabled
                )
                BitrateAwareLoadControl(
                    minBufferMs = bufferSettings.minBufferMs,
                    maxBufferMs = bufferSettings.maxBufferMs,
                    bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
                    // Allow buffering past the byte budget until the minimum time threshold is
                    // met. Without this, high-bitrate remux files (e.g. 100+ Mbps UHD MKV with
                    // multiple audio tracks) exhaust the RAM-tier byte cap in <5s of content before
                    // minBufferMs is satisfied, leaving ExoPlayer stuck in STATE_BUFFERING.
                    prioritizeTimeOverSizeThresholds = true,
                    backBufferDurationMs = backBufferMsAtBuild,
                    // Wired to the stored setting (was hardcoded true); the
                    // BufferSettings default is true, so only an explicit
                    // assessment/preset write flips it.
                    retainBackBufferFromKeyframe = bufferSettings.retainBackBufferFromKeyframe,
                    budgetBytes = customBufferBudgetBytes,
                    allocator = allocator
                ).also { currentBitrateAwareLoadControl = it }
            } else {
                // Stock LoadControl: DefaultLoadControl configured with 1.5s back buffer so 1s rewind doesn't clear buffer.
                effectiveBackBufferDurationMs = 1_500
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-stock master=off; DefaultLoadControl " +
                            "(1.5s back buffer) host=${url.safeHost()}"
                )
                DefaultLoadControl.Builder()
                    .setBackBuffer(1_500, /* retainBackBufferFromKeyframe = */ true)
                    .build()
            }
            _loadControl = loadControl

            // The low-heap + confirmed DV7 case is handled dynamically at first frame
            // (back buffer shrink + budget reduction) rather than blanket-disabling user
            // settings at init, since the stream content isn't known yet at this point.
            applyMediaSourceFactorySettings(playerSettings)

            // Log the effective state (post-gating), not the raw settings.
            Log.i(
                PlayerRuntimeController.TAG,
                "BUFFER_NETWORK: bufferEngine=${playerSettings.bufferEngineEnabled} " +
                        "parallelNetwork=${playerSettings.parallelNetworkEnabled} " +
                        "useParallel=${mediaSourceFactory.useParallelConnections} " +
                        "host=${url.safeHost()}"
            )

            currentDiagnostics = currentDiagnostics.copy(
                bufferEngineEnabled = playerSettings.bufferEngineEnabled,
                parallelNetworkEnabled = playerSettings.parallelNetworkEnabled
            )

            val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive

            prepareStreamStartSubtitles(playerSettings)
            // AFR review F1: absolute deadline (see MPV path above).
            withTimeoutOrNull(AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS) { afrJob.await() }
                ?: run {
                    Log.w(PlayerRuntimeController.TAG, "AFR preflight exceeded absolute deadline; starting playback without it")
                    afrJob.cancel()
                }

            // ── Libass Setup (From 0.5.7-beta/Left) ──
            // requestedUseLibassByUser was set by prepareStreamStartSubtitles above.
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> true
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = requestedLibassRenderType
            _uiState.update {
                it.copy(
                    useLibass = useLibass,
                    libassRenderType = playerSettings.libassRenderType
                )
            }
            // ── Track Selector Setup ──
            val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
                ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS,
                AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
            )
            trackSelector = object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
                override fun selectAllTracks(
                    mappedTrackInfo: MappedTrackInfo,
                    rendererFormatSupports: Array<out Array<out IntArray>>,
                    rendererMixedMimeTypeAdaptationSupports: IntArray,
                    params: Parameters
                ): Array<ExoTrackSelection.Definition?> {
                    val streamMime = currentStreamMimeType
                    val isHls = streamMime != null && (
                        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                        streamMime.lowercase().contains("mpegurl") ||
                        streamMime.lowercase().contains("m3u8")
                    )
                    Log.d("NuvioTrackSelector", "selectAllTracks run: streamMime=$streamMime, isHls=$isHls")
                    if (isHls) {
                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                                for (groupIndex in 0 until trackGroups.length) {
                                    val group = trackGroups[groupIndex]
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getFormat(trackIndex)
                                        val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                        val formatSupport = RendererCapabilities.getFormatSupport(support)
                                        Log.d("NuvioTrackSelector", "Evaluating track: id=${format.id}, res=${format.width}x${format.height}, mime=${format.sampleMimeType}, codecs=${format.codecs}, support=${formatSupport}")
                                        if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                            val mime = format.sampleMimeType
                                            val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                            val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                            val codecs = format.codecs?.lowercase() ?: ""
                                            val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                            val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                            val isStandard8Bit = !is10Bit && !isHdr

                                            Log.d("NuvioTrackSelector", "Conditions for id=${format.id}: isAvcOrHevc=$isAvcOrHevc, isAtMost1080p=$isAtMost1080p, isStandard8Bit=$isStandard8Bit")
                                            if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                                Log.i("NuvioTrackSelector", "Upgraded track support to FORMAT_HANDLED for id=${format.id}")
                                                rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                    RendererCapabilities.create(
                                                        C.FORMAT_HANDLED,
                                                        RendererCapabilities.ADAPTIVE_SEAMLESS,
                                                        RendererCapabilities.getTunnelingSupport(support),
                                                        RendererCapabilities.getHardwareAccelerationSupport(support),
                                                        RendererCapabilities.getDecoderSupport(support)
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // F-1 (Arshad 2026-10-09): never tunnel a converted DV7 start. The Shield's
                    // hidden DOVI decoder (DolbyVisionCodecFallback) fails
                    // configureVideoTunnelMode on the dvhe.08 output (4001 -> teardown -> a
                    // disarmed retry, ~3.5 s). DefaultTrackSelector.selectTracks (1.8.0) decides
                    // tunnelling after this call from this same array, so a track marked
                    // TUNNELING_NOT_SUPPORTED here plays untunnelled; nothing else changes.
                    if (params.tunnelingEnabled) {
                        val codecRewrites = DolbyVisionConversionStats.getCodecStringRewriteCount()
                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_VIDEO) continue
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                for (trackIndex in 0 until group.length) {
                                    if (!dvConversionBlocksTunneling(group.getFormat(trackIndex).codecs, codecRewrites)) continue
                                    val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                    if (RendererCapabilities.getTunnelingSupport(support) !=
                                        RendererCapabilities.TUNNELING_SUPPORTED
                                    ) continue
                                    rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                        RendererCapabilities.create(
                                            RendererCapabilities.getFormatSupport(support),
                                            RendererCapabilities.getAdaptiveSupport(support),
                                            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                                            RendererCapabilities.getHardwareAccelerationSupport(support),
                                            RendererCapabilities.getDecoderSupport(support)
                                        )
                                    if (!isTunnelingSuppressedForDvConversion) {
                                        isTunnelingSuppressedForDvConversion = true
                                        _uiState.update { it.copy(tunnelingEnabled = false) }
                                        Log.i(
                                            PlayerRuntimeController.TAG,
                                            "F1_TUNNEL_GUARD: converted DV7 track codecs=${group.getFormat(trackIndex).codecs} " +
                                                "plays untunnelled"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    return super.selectAllTracks(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        rendererMixedMimeTypeAdaptationSupports,
                        params
                    )
                }
            }.apply {
                setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
                if (playerSettings.effectiveTunnelingEnabled && !safeAudioModeEnabled) {
                    setParameters(buildUponParameters().setTunnelingEnabled(true))
                } else if (safeAudioModeEnabled) {
                    setParameters(buildUponParameters().setTunnelingEnabled(false).setConstrainAudioChannelCountToDeviceCapabilities(true))
                }
                if (audioDisabledForStream) {
                    setParameters(buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO)))
                }
                if (vc1TrackSelectionBypassActive) {
                    setParameters(
                        buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setForceHighestSupportedBitrate(true)
                    )
                }

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray()))
                }

                val captioningManager = context?.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT))
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(buildUponParameters().setPreferredTextLanguage(locale.isO3Language))
                    }
                }
                // Forced mode: disable ExoPlayer auto text selection; our logic handles it.
                if (playerSettings.subtitleStyle.useForcedSubtitles) {
                    setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
                }
                // When forced subtitles are disabled, tell ExoPlayer to ignore
                // SELECTION_FLAG_FORCED so it won't auto-select forced tracks.
                if (!playerSettings.subtitleStyle.useForcedSubtitles) {
                    val currentFlags = parameters.ignoredTextSelectionFlags
                    setParameters(
                        buildUponParameters().setIgnoredTextSelectionFlags(
                            currentFlags or C.SELECTION_FLAG_FORCED
                        )
                    )
                }
            }

            // ── Extractors & DV Hook ──
            val extractorsFactory = DefaultExtractorsFactory()
                // Splice info ignored: VOD never uses SCTE-35, and 1.8.0 maps stream type 0x86 to
                // that reader, which in HDMV TS is DTS-HD MA (crash class: google/ExoPlayer#8911).
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                        DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                )
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
                // Spec 61 Q4: the non-Matroska extractors (TS carries PGS too) leave picture
                // subtitles raw as well. The media source factory's own setSubtitleParserFactory
                // does not reach this instance: the wrappers around it do not forward the call.
                .setSubtitleParserFactory(RenderTimeSubtitles.extractionParsers(DefaultSubtitleParserFactory()))

            // Manual Convert-to-DV8.1 uses mode 2; if a prior attempt at this stream
            // failed to play, force mode 1 this time (before the HDR10 fallback).
            val dv7Mode1Forced = dv7Mode1ForcedStreamUrls.contains(url)
            val manualDv81Selected = playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
            isManualDv81Mode2ActiveForCurrentPlayback =
                manualDv81Selected &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !libdoviModeOverrideActive &&
                !dv7Mode1Forced
            // DV7 conversion is handled app-side by DolbyVisionExtractorsFactory and the
            // vendored Matroska extractor (wired into effectiveExtractorsFactory below).

            audioDelayUs.set(_uiState.value.audioDelayMs.toLong() * 1000L)
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)

            // ── Fallback Codec Setup ──
            // mapDv7ToHevc follows the effective mode or a recovery retry. It also gates the DV
            // recovery ladder (errorRecoveryStreamState), so a retry that fails again does not
            // loop; the renderer itself ignores it in this AAR.
            val mapDv7ToHevcEnabled = effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                    dvConversionDisarmedForUrl
            val isHdr10BaseLayerModeActive = dv7Hdr10ForcedForUrl || when (playerSettings.dv7HandlingMode) {
                Dv7HandlingMode.AUTO -> dv7AutoResult?.displayDv != true
                else -> effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                        effectiveDv7Mode == Dv7HandlingMode.STRIP_DV
            }
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            // DV7 review F2: key this off the effective mode, not the AUTO policy
            // result. dv7AutoResult is null in every manual mode, so the previous
            // gate silently withheld findDvDecodersIgnoringProfile() from users who
            // manually select Convert-to-DV8.1 - exactly the hidden-DV-decoder
            // hardware that fallback selector was built for. effectiveDv7Mode
            // already collapses AUTO to DV81_LIBDOVI when the policy chose convert.
            val convertToDv81Active = !mapDv7ToHevcEnabled &&
                    effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            val codecSelector = createDolbyVisionFallbackCodecSelector(
                convertToDv81Active = convertToDv81Active
            )
            val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
            val preferFfmpegAudioActive = preferFfmpegAudioStreamUrls.contains(url)
            isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
            // Bluetooth media sink (A2DP / LE Audio): Media3 only advertises PCM. Do not attempt
            // optical/HDMI passthrough — decode to PCM and let the BT stack encode SBC/AAC/aptX/LDAC.
            val isBluetoothAudioOutput = currentAudioOutputRoute?.isBluetooth == true ||
                AudioOutputRouteDetector.isBluetoothMediaOutput(context)
            // Bluetooth output rules (no optical passthrough, 2.0 downmix): AudioOutputPlan.
            val audioOutputPlan = AudioOutputPlan.of(playerSettings, isBluetooth = isBluetoothAudioOutput)
            val isForcePassthroughActive = audioOutputPlan.forceOpticalPassthrough
            // Audio review F4: force-AC3 no longer escalates the *global*
            // extension mode to PREFER (which put software AV1 video decode
            // ahead of MediaCodec). The FFmpeg audio renderer is instead
            // reordered audio-locally in buildAudioRenderers. Bluetooth and the
            // PCM audio fallback keep upstream's global PREFER, paired with
            // preferSoftwareAudioOnly below, which demotes the video renderers
            // back to MediaCodec-first (only VC-1 software fallback wants PREFER
            // for video).
            val effectiveDecoderPriority = effectiveDecoderPriority(
                decoderPriority = playerSettings.decoderPriority,
                preferExtensions = vc1SoftwareFallbackActive || hasTriedAudioPcmFallback || isBluetoothAudioOutput,
                forcePassthroughActive = isForcePassthroughActive
            )
            if (isBluetoothAudioOutput) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media output active (route=${currentAudioOutputRoute?.key}): " +
                        "PCM-only sink, stereo downmix, no optical passthrough"
                )
            }

            // ── Renderers Factory (Combining Libass offsets + Audio Gain + Video Fallback) ──
            // Per-format passthrough overrides. softwareDecodersAvailable gates the whole
            // policy on a fallback decoder existing at all: with Decoder Priority set to
            // "Device only" the FFmpeg audio renderer is absent from the renderer list, so
            // denying passthrough could leave a format with no decoder. In that state the
            // policy denies nothing. Hoisted to a local val because the reuse fingerprint
            // below must see the same value the sink is built with.
            // F3: correct the policy with codecs this chain has been observed to reject at
            // AudioTrack open() (confirmed across two sessions, outages excluded, cleared when
            // the codec's toggle changes), keyed to the current route so an ARC->eARC change
            // starts clean.
            val currentAudioRouteKey = detectAudioRouteKey()
            audioRouteKeyAtPlayerBuild = currentAudioRouteKey
            val audioPassthroughPolicy = audioPassthroughPolicyOf(
                settings = playerSettings,
                effectiveDecoderPriority = effectiveDecoderPriority,
                learnedDeniedGroups = learnedDeniedAudioGroups(playerSettings.audioRejectionsConfirmed, currentAudioRouteKey)
            )
            // F5: denied-codec handling. Compute which denied formats should be
            // transcoded to AC-3 (opt-in, per chain) rather than decoded to PCM.
            // Empty in every default configuration; see DeniedTranscodePlanner for
            // the guards (incl. the sink-fallback guard on AC-3 usability).
            val deniedTranscodeMimes = com.nuvio.tv.core.player.DeniedTranscodePlanner.effectiveTranscodeMimes(
                policy = audioPassthroughPolicy,
                transcodeDeniedToAc3 = playerSettings.deniedCodecHandling ==
                    com.nuvio.tv.data.local.DeniedCodecHandling.TRANSCODE_AC3,
                forcePassthroughActive = isForcePassthroughActive
            )
            // F5 fix: expose the policy to error recovery (tryDeniedAudioFfmpegFallback).
            currentAudioPassthroughPolicy = audioPassthroughPolicy
            val renderersFactory = SubtitleOffsetRenderersFactory(
                audioPassthroughPolicy = audioPassthroughPolicy,
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                audioDelayUsProvider = audioDelayUs::get,
                shouldStripSdhProvider = {
                    currentPlayerSettings.subtitleStyle.stripSdh
                },
                isBuiltInSubtitleProvider = {
                    _uiState.value.selectedAddonSubtitle == null
                },
                isSidecarAddonSubtitleActiveProvider = {
                    isSidecarAddonSubtitleActive()
                },
                videoBoundsFractionProvider = {
                    val pv = exoPlayerView
                    if (pv != null) pv.videoBoundsFraction(videoAspectRatio) else null
                },
                gainAudioProcessor = gainAudioProcessor,
                audioOutputPlan = audioOutputPlan,
                deniedTranscodeMimes = deniedTranscodeMimes,
                preferFfmpegAudio = preferFfmpegAudioActive,
                matPassthroughEnabled = playerSettings.matPassthroughEnabled,
                bluetoothForcePcm = isBluetoothAudioOutput,
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                initialForcePcm = hasTriedAudioPcmFallback || isBluetoothAudioOutput,
                preferSoftwareAudioOnly = (isBluetoothAudioOutput || hasTriedAudioPcmFallback) &&
                    !vc1SoftwareFallbackActive,
                onPlaybackSpeedAwareAudioSinkCreated = { playbackSpeedAwareAudioSink = it },
                onMatRoutingAudioSinkCreated = { matRoutingAudioSink = it },
                onFfmpegAudioRendererChanged = { renderer ->
                    ffmpegAudioRenderer = renderer
                    renderer?.setDeniedTranscodeMimes(deniedTranscodeMimes)
                    renderer?.applyAudioOutputPlan(audioOutputPlan)
                    applyCenterMixLevel(_uiState.value.centerMixLevelDb)
                    updateAudioControlAvailability()
                }
            ).apply { mapDv7ToHevc = mapDv7ToHevcEnabled }
                .setExtensionRendererMode(effectiveDecoderPriority)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)

            // The app-level factory performs DV7 conversion for the in-band-RPU containers
            // (MP4/fMP4/TS); MKV goes through the vendored extractor. Pass-through for non-DV.
            val stripDvRpuEnabled = playerSettings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV ||
                    effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER
            if (stripDvRpuEnabled) {
                Log.i(PlayerRuntimeController.TAG, "DV_RPU_STRIP: enabled — will remove DV RPU NALs")
            }
            val stripHdr10PlusSei = playerSettings.stripHdr10PlusSei
            if (stripHdr10PlusSei) {
                Log.i(PlayerRuntimeController.TAG, "HDR10PLUS_STRIP: enabled — will remove HDR10+ SEI NALs")
            }

            // Audio review F1: the factory is now unconditional. When no DV
            // feature is active it wraps with an inactive config - only the
            // Matroska swap fires (for the DTS-HD sniff); MP4/TS extractors are
            // returned untouched via the early return in wrap().
            val effectiveExtractorsFactory: ExtractorsFactory =
                    DolbyVisionExtractorsFactory(
                        delegate = extractorsFactory,
                        config = DolbyVisionConversionConfig(
                            active = isExperimentalDv7ToDv81ActiveForCurrentPlayback,
                            forcedMode = when {
                                libdoviModeOverrideActive -> libdoviModeOverride
                                dv7Mode1Forced -> 1
                                else -> -1
                            },
                            dv5Enabled = playerSettings.dv5ToDv81Enabled,
                            manualDv81 = manualDv81Selected && !dv7Mode1Forced
                        ),
                        stripDvRpu = stripDvRpuEnabled,
                        stripHdr10PlusSei = stripHdr10PlusSei,
                        injectHdr10Sei = playerSettings.injectHdr10MetadataOnStrip,
                        hdr10BaseLayerMode = isHdr10BaseLayerModeActive
                    )

            setLoadingStatus(
                phase = "building_player",
                message = context.getString(R.string.player_loading_building)
            )
            // ── Build ExoPlayer ──
            val buildDefaultPlayer = {
                // The actual MediaSource is built by mediaSourceFactory.createMediaSource()
                // (setMediaSource below), NOT the DefaultMediaSourceFactory on the builder.
                // So the DV7 app-level factory must be wired in here, otherwise
                // createMediaSource falls back to a plain DefaultExtractorsFactory and the
                // conversion never runs. (The libass path wires it via buildWithAssSupportCompat.)
                mediaSourceFactory.configureSubtitleParsing(
                    // Audio review F1: always pass the wrapped factory so the
                    // MKV DTS-HD sniff applies on this path too.
                    extractorsFactory = effectiveExtractorsFactory,
                    subtitleParserFactory = null
                )
                val playerDataSourceFactory = LoggingDataSourceFactory(PlayerPlaybackNetworking.createDataSourceFactory(context, headers), "BUILDER_DEFAULT")
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode))
                    .build()
            }

            val constructionFingerprint = ExoConstructionFingerprint(
                useLibass = useLibass,
                isHls = isHls,
                performanceModeEnabled = playerSettings.nuvioPerformanceModeEnabled,
                bufferEngineEnabled = playerSettings.bufferEngineEnabled,
                minBufferMs = playerSettings.bufferSettings.minBufferMs,
                maxBufferMs = playerSettings.bufferSettings.maxBufferMs,
                bufferForPlaybackMs = playerSettings.bufferSettings.bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs = playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs,
                backBufferDurationMs = playerSettings.bufferSettings.backBufferDurationMs,
                retainBackBufferFromKeyframe = playerSettings.bufferSettings.retainBackBufferFromKeyframe,
                targetBufferSizeMb = playerSettings.bufferSettings.targetBufferSizeMb,
                bufferBudgetManaged = playerSettings.bufferBudgetManaged,
                allowLargeTargetBuffer = playerSettings.allowLargeTargetBuffer,
                downmixEnabled = playerSettings.downmixEnabled,
                audioOutputChannels = playerSettings.audioOutputChannels,
                maintainOriginalAudioOnDownmix = playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthroughActive = isForcePassthroughActive,
                matPassthroughEnabled = playerSettings.matPassthroughEnabled,
                initialForcePcm = hasTriedAudioPcmFallback,
                audioPassthroughPolicy = audioPassthroughPolicy,
                deniedTranscodeMimes = deniedTranscodeMimes,
                preferFfmpegAudio = preferFfmpegAudioActive,
                extensionRendererMode = effectiveDecoderPriority,
                convertToDv81Active = convertToDv81Active,
                mapDv7ToHevc = mapDv7ToHevcEnabled,
                // 0.8.5: record the effective flag (raw toggle gated by
                // prefer-app decoder) — construction uses it, so reuse must too.
                tunnelingEnabled = playerSettings.effectiveTunnelingEnabled
            )
            val reuseCandidatePlayer = _exoPlayer
            val reuseLivePlayer = reuseCandidatePlayer != null &&
                !useLibass && !activePlayerUsesLibass &&
                previousTrackSelectorForReuse != null &&
                constructionFingerprint == lastExoConstructionFingerprint
            if (reuseLivePlayer) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "PLAYER_REUSE: reusing live ExoPlayer across transition; " +
                        "fingerprint unchanged host=${url.safeHost()}"
                )
                // Drop the previous stream's listeners before anything below can
                // fire a callback into them: a playWhenReady flip observed by the
                // old listener would set userPausedManually and start the new
                // stream paused.
                currentExoPlayerListener?.let { staleListener ->
                    runCatching { reuseCandidatePlayer.removeListener(staleListener) }
                }
                currentExoPlayerListener = null
                currentExoAnalyticsListener?.let { staleListener ->
                    runCatching { reuseCandidatePlayer.removeAnalyticsListener(staleListener) }
                }
                currentExoAnalyticsListener = null
                runCatching { reuseCandidatePlayer.playWhenReady = false }
                // The freshly derived companions cannot be installed on a live
                // player; restore the live instances and re-apply the fresh
                // per-stream state onto them.
                previousTrackSelectorForReuse.setParameters(trackSelector!!.parameters)
                trackSelector = previousTrackSelectorForReuse
                _loadControl = previousLoadControlForReuse
                currentBitrateAwareLoadControl = previousBitrateAwareLoadControlForReuse
                previousBitrateAwareLoadControlForReuse?.let { liveLoadControl ->
                    // The back buffer is NOT re-applied here: media3 captured it when this
                    // player was first constructed and will not re-read it. Report the
                    // captured value rather than the newly configured one (which the engine
                    // never adopted) or 0 (the per-stream reset at the top of initializePlayer).
                    effectiveBackBufferDurationMs = previousEffectiveBackBufferMsForReuse
                    liveLoadControl.setBudgetBytesOverride(customBufferBudgetBytes)
                }
                reuseCandidatePlayer.setVideoChangeFrameRateStrategy(
                    nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode)
                )
                // The rebuild path wires the per-stream extractors factory inside
                // buildDefaultPlayer(); the reuse path must wire it itself so DV
                // conversion and subtitle parsing follow the new stream.
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = effectiveExtractorsFactory,
                    subtitleParserFactory = null
                )
            } else {
            // nt10: the settle exists to let a just-released decoder and its
            // surface stand down before the next one is built. On a FRESH
            // start there is nothing to stand down: the 27 Jul capture shows
            // no PLAYER_RELEASE line on the startup path at all, so the
            // 120 ms was dead time on the critical path of every first play.
            // It still runs on a genuine rebuild, which is the case it was
            // written for. Beyond its own cost, the delay holds back
            // prepare() -- and prepare() is what opens the datasource and
            // starts chunk 0, whose ~470 ms of server TTFB can only be
            // overlapped, never removed.
            if (disposeExoPlayerBeforeRebuild()) {
                delay(PLAYER_REBUILD_SETTLE_DELAY_MS)
            }

            _exoPlayer = if (useLibass) {
                val playerDataSourceFactory = LoggingDataSourceFactory(PlayerPlaybackNetworking.createDataSourceFactory(context, headers), "BUILDER_LIBASS")
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode))
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        dataSourceFactory = playerDataSourceFactory,
                        extractorsFactory = effectiveExtractorsFactory,
                        renderersFactory = renderersFactory,
                        // Spec 36/S3 (B2-24): libass honours the subtitle/audio delay too.
                        assRendererWrapper = { renderer ->
                            SubtitleOffsetRenderer(renderer, subtitleDelayUs::get, audioDelayUs::get)
                        }
                    )
            } else {
                buildDefaultPlayer()
            }
            } // nt12: end rebuild branch of the reuse fork
            lastExoConstructionFingerprint = constructionFingerprint
            activePlayerUsesLibass = useLibass
            libassPipelineSwitchInFlight = false

            _exoPlayer?.apply {
                // On Android TV, disable audio focus handling: focus-driven ducking applies
                // a software volume multiplier, which can't be applied to a compressed
                // bitstream, so ExoPlayer drops passthrough (Atmos/TrueHD/DTS-HD) to PCM.
                // Keep focus handling on phones/tablets, where ducking/pausing for calls
                // and other apps' audio matters. Matches the upstream PR guard.
                val handleAudioFocus = !context.packageManager
                    .hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                setAudioAttributes(movieAudioAttributes, handleAudioFocus)
                setPlaybackSpeed(_uiState.value.playbackSpeed)
                if (applyPcmFallbackOnStartup) {
                    pendingAudioPcmFallbackRebuild = false
                    hasTriedAudioPcmFallback = true
                }

                if (playerSettings.skipSilence) skipSilenceEnabled = true
                setHandleAudioBecomingNoisy(true)

                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, this).build()
                    }
                    updateMediaSessionMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAudioAmplification(_uiState.value.audioAmplificationDb)
                applyCenterMixLevel(_uiState.value.centerMixLevelDb)

                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                // Addon subtitles attach after start (sidecar or media reload),
                // never with the initial media source.
                attachedAddonSubtitleKeys = emptySet()
                // nt7 (task 2): join the saved-progress read before the
                // resume position is resolved. Runway to here is the whole
                // player build; SAVED_PROGRESS_AWAIT prices the residual.
                awaitSavedProgressLoad()
                // A rebuild (failover, source/engine switch, DV/audio fallback,
                // retry) carries its position in pendingSeekPosition: start the
                // new player there instead of buffering at 0 and seeking on the
                // first READY. The carry is the newer live position of the same
                // title, so it outranks a saved resume (before, a saved resume > 0
                // won at init); episode switches clear the carry. Spec 37 / A5-23.
                val carriedOver = _uiState.value.pendingSeekPosition?.takeIf { it > 0L }
                if (carriedOver != null) {
                    pendingResumeProgress = null
                    _uiState.update { it.copy(pendingSeekPosition = null) }
                }
                val initialResumePosition = PlayerStartPosition.choose(carriedOver) {
                    resolvePendingInitialResumePosition()
                }
                Log.i(
                    PlayerRuntimeController.TAG,
                    "PLAYER_INIT: engine=EXOPLAYER host=${url.safeHost()} " +
                        "playbackSpeed=${_uiState.value.playbackSpeed} " +
                        "resumePositionMs=$initialResumePosition carried=${carriedOver != null} mime=${currentStreamMimeType ?: "unknown"} " +
                        "bufferEngine=${playerSettings.bufferEngineEnabled} parallel=${mediaSourceFactory.useParallelConnections} " +
                        "tunneling=${playerSettings.effectiveTunnelingEnabled}"
                )
                val initialMediaSource = mediaSourceFactory.createMediaSource(
                    context = context,
                    url = url,
                    headers = headers,
                    filename = currentFilename,
                    responseHeaders = currentStreamResponseHeaders,
                    mimeTypeOverride = currentStreamMimeType,
                    audioDelayUsProvider = audioDelayUs::get,
                    mediaMetadata = buildMediaSessionMetadata()
                )

                if (initialResumePosition > 0L) {
                    setMediaSource(initialMediaSource, initialResumePosition)
                    resumePositionApplied = true
                    clearPendingInitialResumePosition()
                    updatePlaybackTimeline(currentPosition = initialResumePosition)
                } else {
                    setMediaSource(initialMediaSource)
                }

                setLoadingStatus(
                    phase = "starting_stream",
                    message = context.getString(R.string.player_loading_starting)
                )
                scheduleStartupWatchdog()
                // Read at READY, after track selection: an F-1 guarded start plays untunnelled.
                val isTunneledPlayback = { tunnelsCurrentPlayback(playerSettings) }
                // Hold playWhenReady=false through prepare(). The first STATE_READY
                // (ColdStartPrime) starts playback through the AFR gates; nothing defers the
                // start to onRenderedFirstFrame(). Spec 57 D2: while the decoder primes from 0,
                // the first READY that counts is the one after the seek to the target.
                //
                // Exception: tunneled playback bypasses the normal video rendering pipeline
                // so onRenderedFirstFrame() never fires — TunneledFirstReady starts on READY.
                // (0.8.0 merge: the fork's AFR settle hold and track-AFR start gate now
                // wrap the policy's start actions — see startPlaybackThroughAfrGates.)
                // R5: the settle hold is owed by *this* player build only, and only
                // for an automatic start. Consumed here — the point the pre-merge code
                // consumed it — so a start that happens later (a rebuffer, or a manual
                // play after a paused start) is never delayed by a mode transition that
                // finished long ago.
                var afrSettleHoldPending =
                    exoDelayStartAfterAfrSwitch && !startPaused && !userPausedManually
                exoDelayStartAfterAfrSwitch = false
                // Spec 57 D2: the prime-from-0 decision is taken once per player build. Reset here too:
                // a reused player's old listener could set a target between the stream reset and now.
                var startPrimeDecided = false
                startPrimeTargetMs = null
                videoNeedsWarmDecoder = false
                playWhenReady = false
                prepare()

                currentExoPlayerListener?.let { staleListener -> removeListener(staleListener) }
                currentExoPlayerListener = null
                val exoPlayerListenerForStream = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (isReleasingPlayer) return
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) { lastKnownDuration = playerDuration }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
                        // Only mark playbackEnded for real finishes so PlayerScreen does not
                        // dispatch next-episode navigation for short debrid/error placeholders.
                        // A forced end (Spec 61 Q2) leaves the player READY: its ended state is held
                        // until the progress tick re-arms it after a seek back.
                        val naturalEnded = exoEndOverrunHandled || playbackState == Player.STATE_ENDED &&
                            shouldTreatAsNaturalPlaybackCompletion(
                                hasRenderedFirstFrame = hasRenderedFirstFrame,
                                hasFatalError = _uiState.value.error != null,
                                durationMs = playerDuration.coerceAtLeast(0L).let { d ->
                                    maxOf(d, lastKnownDuration)
                                },
                                releaseVideo = releaseVideo
                            )
                        _uiState.update {
                            it.copy(
                                isBuffering = if (NuvioExoPlayerPerformanceHelper.shouldSuppressBufferingUi(
                                    suppressBufferingUiForSeek, seekBufferingUiDeferred, isBuffering
                                )) false else isBuffering,
                                playbackEnded = naturalEnded
                            )
                        }
                        updateAudioControlAvailability()

                        // Rebuffer telemetry: a rebuffer is STATE_BUFFERING entered
                        // AFTER the first frame (initial startup buffering is excluded).
                        // Accumulate time spent rebuffering; closed out on any non-buffering state.
                        // T9 (ysosrs 22aecb0e8, nt14): buffering within seekRebufferGraceMs of a
                        // user seek is the seek's own refill, not a starvation rebuffer. Excluded
                        // from count/totalMs/analytics; duration tracking and the
                        // passthrough-resync arm still run for every episode. Known limit: a seek
                        // INTO a starved region is excluded too (STALL_WATCHDOG / Rate-limit row
                        // still surface it).
                        if (playbackState == Player.STATE_BUFFERING) {
                            if (hasRenderedFirstFrame && rebufferStartedAtMs == 0L) {
                                rebufferStartedAtMs = SystemClock.elapsedRealtime()
                                currentRebufferSeekInduced =
                                    rebufferStartedAtMs - lastSeekWallMs <= seekRebufferGraceMs
                                if (!currentRebufferSeekInduced) {
                                    rebufferCount += 1
                                    playbackAnalyticsDiagnostics.onRebufferStarted(this@apply, rebufferCount)
                                    Log.i(
                                        PlayerRuntimeController.TAG,
                                        "REBUFFER: count=$rebufferCount totalRebufferMs=$rebufferTotalMs " +
                                            "bufferEngine=${currentDiagnostics.bufferEngineEnabled} " +
                                            "dv7dovi=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                                            "host=${currentStreamUrl.safeHost()}"
                                    )
                                } else {
                                    Log.i(
                                        PlayerRuntimeController.TAG,
                                        "REBUFFER_SEEK_EXCLUDED: sinceSeekMs=${rebufferStartedAtMs - lastSeekWallMs}"
                                    )
                                }
                            }
                        } else if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            if (!currentRebufferSeekInduced) {
                                rebufferTotalMs += lastRebufferMs
                                playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                            }
                            rebufferStartedAtMs = 0L
                            currentRebufferSeekInduced = false
                            playbackSpeedAwareAudioSink?.armPassthroughResync()
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering),
                                        detail = "overlay_reopened"
                                    )
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = context.getString(R.string.player_loading_buffering))
                                } else {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering)
                                    )
                                    state.copy(loadingMessage = context.getString(R.string.player_loading_buffering))
                                }
                            }
                        }

                        // Arm stall watchdog while buffering.
                        if (playbackState == Player.STATE_BUFFERING) {
                            maybeScheduleStallWatchdog()
                        } else {
                            cancelStallWatchdog()
                        }

                        // Spec 57 D2: while the decoder primes at 0 this whole READY block waits (start,
                        // pending seeks, subtitle pick, first-frame watchdog: no audio at 0); the READY
                        // after the seek to the target runs it.
                        if (playbackState == Player.STATE_READY && startPrimeTargetMs == null) {
                            // 5c: the byte floor acts here -- content-length is stable at
                            // READY. The duration backstop is deferred to the progress tick
                            // (see rejectPlaceholderStream). Returning skips autoplay for a
                            // rejected placeholder; the policy's runtime guard is the fail-safe.
                            val placeholderVerdict = probePlaceholderStream(this@apply)
                            if (placeholderVerdict is PlaceholderStreamPolicy.Verdict.Reject &&
                                placeholderVerdict.reason == PlaceholderStreamPolicy.Reason.ImplausibleSize
                            ) {
                                rejectPlaceholderStream(placeholderVerdict)
                                return
                            }
                            // The policy picks the READY start action and
                            // startPlaybackThroughAfrGates applies it; ColdStartPrime
                            // starts playback here (see the prepare() comment). Tunneled
                            // playback never fires onRenderedFirstFrame(), so
                            // TunneledFirstReady also stands in for the first frame.
                            val readyTransition = PlayerStartupPlaybackPolicy.onStateReady(
                                PlayerStartupPlaybackPolicy.ReadyState(
                                    shouldEnforceAutoplayOnFirstReady = shouldEnforceAutoplayOnFirstReady,
                                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                                    userPausedManually = userPausedManually,
                                    startPaused = startPaused,
                                    isTunneledPlayback = isTunneledPlayback(),
                                )
                            )
                            shouldEnforceAutoplayOnFirstReady =
                                readyTransition.nextState.shouldEnforceAutoplayOnFirstReady
                            if (readyTransition.nextState.hasRenderedFirstFrame && isTunneledPlayback()) {
                                hasRenderedFirstFrame = true
                                blackStartRebuiltStreamUrls.remove(currentStreamUrl)
                                cancelStartupWatchdog()
                                retractStartupWatchdogErrorAfterFirstFrame()
                                clearFailoverNoticeAfterFirstFrame()
                            }
                            when (val action = readyTransition.action) {
                                is PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady -> {
                                    playbackAnalyticsDiagnostics.onSyntheticFirstFrame(this@apply)
                                    if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                        _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                    }
                                    // nt6 / R5: the policy already folds startPaused and
                                    // userPausedManually into the action; the fork's
                                    // track-AFR gate and display-AFR settle hold wrap it.
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        start = action.startsPlayback,
                                        holdForAfrSettle = afrSettleHoldPending
                                    )
                                    afrSettleHoldPending = false
                                    // Force MediaCodec video decoder & AudioSink flush/re-alignment on initial
                                    // tunneled startup behind the loading overlay so playback starts immediately.
                                    // Note: ExoPlayer ignores seeks if target position == current position,
                                    // so we add a 100ms delta to guarantee an actual MediaCodec flush.
                                    if (_uiState.value.pendingSeekPosition == null) {
                                        val initialPos = currentPosition
                                        seekTo((initialPos + 100L).coerceAtLeast(100L))
                                    }
                                    finishLoadingDiagnostics("first_frame_ready")
                                    currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                                    _uiState.update {
                                        it.copy(
                                            showLoadingOverlay = false,
                                            loadingMessage = null,
                                            loadingProgress = if (it.loadingProgress != null) 1f else null,
                                            showPlayerEngineSwitchInfo = false
                                        )
                                    }
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.ColdStartPrime -> {
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        start = action.startsPlayback,
                                        holdForAfrSettle = afrSettleHoldPending
                                    )
                                    afrSettleHoldPending = false
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.PreFirstFrameResume -> {
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        start = action.startsPlayback,
                                        holdForAfrSettle = false
                                    )
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.PostFirstFrameResume -> {
                                    // A rebuffer inside the track-AFR settle window
                                    // must not restart playback mid-switch; the gate
                                    // release resumes it (hasRenderedFirstFrame is
                                    // already true on this branch).
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        start = action.startsPlayback,
                                        holdForAfrSettle = false
                                    )
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.None -> Unit
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                if (position > 0L) resumePositionApplied = true
                                if (NuvioExoPlayerPerformanceHelper.enabled) {
                                    seekBufferingUiDeferred = true
                                    seekBufferingUiJob?.cancel()
                                    seekBufferingUiJob = scope.launch {
                                        delay(seekBufferingUiDelayMs)
                                        seekBufferingUiDeferred = false
                                    }
                                }
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()
                            // Performance mode rebuilds track selection only until the first
                            // frame, not on every post-seek READY; stock mode always rebuilds.
                            if (!NuvioExoPlayerPerformanceHelper.enabled || !hasRenderedFirstFrame) {
                                trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                            }
                            maybeScheduleFirstFrameWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
                        }

                        if (playbackState == Player.STATE_ENDED) {
                            // Re-persist diagnostics with the final rebuffer totals (the
                            // first-frame snapshot captured 0, since rebuffers accrue after).
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "BUFFER_SUMMARY: rebuffers=$rebufferCount rebufferTotalMs=$rebufferTotalMs " +
                                    "bufferEngine=${currentDiagnostics.bufferEngineEnabled} host=${currentStreamUrl.safeHost()}"
                            )
                            if (currentDiagnostics.result == "Played") {
                                currentDiagnostics = currentDiagnostics.copy(
                                    rebufferCount = rebufferCount,
                                    rebufferTotalMs = rebufferTotalMs
                                )
                                val endDiagnostics = currentDiagnostics
                                lastPlaybackDiagnostics = endDiagnostics
                                persistLastPlaybackDiagnostics(endDiagnostics)
                            }
                            // Marks watched + auto-play next only for real episode finishes;
                            // short debrid/error placeholders are ignored (see #2819).
                            handleNaturalPlaybackEnded()
                        }

                        refreshStableProgressResetGate()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // nt35: gate the audio-clock jitter sensor on actual playback -
                        // paused/rebuffering time must not count as clock jitter.
                        playbackSpeedAwareAudioSink?.setPlaybackActive(isPlaying)
                        // Spec 35: any play clears the runtime warning (the viewer chose this file).
                        _uiState.update {
                            it.copy(isPlaying = isPlaying, runtimeMismatch = if (isPlaying) null else it.runtimeMismatch)
                        }
                        if (isPlaying) {
                            userPausedManually = false
                            cancelPauseOverlay()
                            startProgressUpdates()
                            startWatchProgressSaving()
                            scheduleHideControls()
                            tryShowParentalGuide()
                        } else {
                            if (userPausedManually) schedulePauseOverlay() else cancelPauseOverlay()
                            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                                stopProgressUpdates()
                            }
                            stopWatchProgressSaving()
                            if (playbackState == Player.STATE_BUFFERING) {
                                saveWatchProgressIfNeeded()
                            } else {
                                saveWatchProgress()
                            }
                        }
                        refreshStableProgressResetGate()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // Before updateAvailableTracks: it can rebuild the player synchronously (libass
                        // switch), which then carries the target and resets it for the new build.
                        if (!startPrimeDecided && !hasRenderedFirstFrame) maybeStartPrimeFromZero(tracks)
                        updateAvailableTracks(tracks)
                    }

                    /**
                     * Spec 57 D2: decided at the first selected video track (it precedes the first
                     * frame). On a match, decode from 0 and seek to the start after the prime frame
                     * (onRenderedFirstFrame); the codec may already hold the start keyframe, and the
                     * seek's flush plus sample 0's in-band parameter sets bring it up.
                     */
                    private fun maybeStartPrimeFromZero(tracks: Tracks) {
                        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
                        startPrimeDecided = true
                        val format = (0 until group.length).firstOrNull(group::isTrackSelected)
                            ?.let(group::getTrackFormat) ?: return
                        val startMs = currentPosition
                        val tunneled = isTunneledPlayback()
                        videoNeedsWarmDecoder = PlayerStartPosition.needsWarmDecoder(
                            containerMimeType = format.containerMimeType,
                            sampleMimeType = format.sampleMimeType,
                            initializationData = format.initializationData,
                            tunneled = tunneled,
                        )
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "START_PRIME_CHECK: container=${format.containerMimeType} sample=${format.sampleMimeType} " +
                                "initData=${format.initializationData.map { it.size }} tunneled=$tunneled " +
                                "startMs=$startMs needsWarm=$videoNeedsWarmDecoder"
                        )
                        if (!PlayerStartPosition.shouldPrimeFromZero(videoNeedsWarmDecoder, startMs)) return
                        startPrimeTargetMs = startMs
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "START_PRIME: ${format.sampleMimeType} ${format.codecs} in ${format.containerMimeType} " +
                                "has no out-of-band parameter sets; decoding from 0, then seeking to ${startMs}ms"
                        )
                        seekTo(0L)
                    }

                    override fun onRenderedFirstFrame() {
                        startPrimeTargetMs?.let { targetMs ->
                            // The decoder began its sequence at 0: go to the start. Not the first
                            // frame: the overlay, the startup watchdog and the start stay as they are.
                            startPrimeTargetMs = null
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "START_PRIME: decoder primed at ${currentPosition}ms; seeking to ${targetMs}ms"
                            )
                            seekTo(targetMs)
                            return
                        }
                        val isFirstFrame = !hasRenderedFirstFrame  // capture BEFORE flipping
                        hasRenderedFirstFrame = true
                        // Spec 86 R1: the black-picture rebuild is one per black start, not per session.
                        blackStartRebuiltStreamUrls.remove(currentStreamUrl)
                        cancelStartupWatchdog()
                        retractStartupWatchdogErrorAfterFirstFrame()
                        clearFailoverNoticeAfterFirstFrame()
                        // T14: a receiver that came back during this build (its device add
                        // landed before the callback or the player existed) is caught here;
                        // the check is idempotent and rebuilds only on a pinned/live mismatch.
                        if (isFirstFrame) schedulePassthroughReturnCheck()
                        // NuvioTV fork (T2, ysosrs cad15d29e): pre-populate the source
                        // list in the background once playback is healthy, so a
                        // mid-play failover (an unrecoverable malformed hole, or an
                        // HTTP dead source) has alternatives without the user having
                        // opened the sources panel first. Cheap - the details screen
                        // already fetched these, so this is a cache hit. Guarded on
                        // the playing item's list being empty, so it runs once per
                        // item (episode switches included) and never re-fires on a
                        // failover re-prepare.
                        if (sourceStreamsForPlayingItem().isEmpty()) {
                            loadSourceStreams(forceRefresh = false)
                        }
                        if (isFirstFrame && _uiState.value.postPlayDismissedForCurrentEpisode) {
                            _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                        }
                        updateAudioControlAvailability()
                        // Start playback now that the first video frame is
                        // visible: audio and video begin in sync.
                        // nt6: while a track-format AFR switch is settling, hold
                        // the start; resumePlaybackAfterTrackAfrIfHeld() starts
                        // playback when the gate releases (deadline-bounded).
                        // Spec 62 H1: the preflight settle hold's timer starts it likewise.
                        if (!startPaused && !userPausedManually && !afrStartGateHeld) {
                            playWhenReady = true
                            play()
                        }
                        refreshStableProgressResetGate()
                        cancelFirstFrameWatchdog()
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                loadingProgress = if (it.loadingProgress != null) 1f else null,
                                showPlayerEngineSwitchInfo = false
                            )
                        }
                        finishLoadingDiagnostics("first_frame_rendered")

                        if (isFirstFrame) {
                            currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) return
                        cancelFirstFrameWatchdog()
                        // Spec 75 S4: the failure as it stands now (cause, step line, facts); the
                        // rungs carry it, a failover records its mark, the box shows it.
                        val failure = playbackFailureOf(error)
                        val detailedError = failure.message
                        cancelStableProgressReset()
                        // Spec 21 P1: the rung order lives in PlayerErrorRecoveryPolicy; this
                        // listener runs the route.
                        val positionMs = exoCarryPositionMs(this@apply)
                        val route = PlayerErrorRecoveryPolicy.route(
                            PlayerErrorRecoveryPolicy.classify(error),
                            errorRecoveryStreamState()
                        )
                        val rungs = when (route) {
                            is PlayerErrorRecoveryPolicy.Route.Ladder -> route.rungs
                            PlayerErrorRecoveryPolicy.Route.Background -> {
                                // Save the position, free the decoder, and rebuild paused on resume.
                                backgroundCrashSavedPositionMs = positionMs.takeIf { it > 0L } ?: 0L
                                pendingBackgroundCrashRecovery = true
                                // Rebuild-carry: resumeForLifecycle rebuilds the player
                                // directly on resume — seed the effective selections now
                                // or they silently vanish with the released instance (same
                                // class as the ErrorRecovery sites; uiState survives the
                                // flushPlaybackState=false release, and nothing clears the
                                // channel between here and the resume rebuild).
                                seedTrackPreferenceCarryForPlayerRebuild()
                                errorRetryJob?.cancel()
                                errorRetryJob = scope.launch {
                                    releasePlayer(flushPlaybackState = false)
                                }
                                return
                            }
                            // Spec 86 R6: re-anchor in place, ahead of the budget below.
                            PlayerErrorRecoveryPolicy.Route.LiveEdge -> {
                                recoverBehindLiveWindow(this@apply)
                                return
                            }
                        }

                        // nt6 fix B: every rung re-initialises playback and each is
                        // individually self-gated, but chained they can loop a persistently
                        // failing pipeline for minutes (observed: decoder-start timeouts
                        // re-preparing an 18.5 GB moov-at-tail MP4 on every cycle). One shared
                        // budget bounds the total; when it's spent, fall through to the
                        // terminal error surface.
                        val autoRecoveryBudgetAvailable = consumeAutoRecoveryBudget(detailedError)
                        // P5: a refused bitstream open retries the same configuration (after
                        // waiting for the output) before any audio fallback below.
                        val bitstreamRetryScheduled = autoRecoveryBudgetAvailable &&
                            tryBitstreamAudioTrackSameConfigRetry(error)
                        // Build 1: record any audio-track open failure on a bitstream input
                        // as ground-truth evidence (F2b/F3). F3 learns it only when no
                        // same-config retry was scheduled (a refusal the retry survives is a blip).
                        recordAudioTrackRejectionIfBitstream(error, learn = !bitstreamRetryScheduled)
                        if (bitstreamRetryScheduled) return
                        if (autoRecoveryBudgetAvailable &&
                            rungs.any { rung ->
                                executeErrorRecoveryRung(rung, error, failure, positionMs)
                            }
                        ) {
                            return
                        }

                        if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            // T9 review: the seek-exclusion travels with the episode — the
                            // error path must not bill an excluded refill to the stats.
                            if (!currentRebufferSeekInduced) {
                                rebufferTotalMs += lastRebufferMs
                                playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                            }
                            rebufferStartedAtMs = 0L
                            currentRebufferSeekInduced = false
                        }

                        val errorDiagnostics = currentDiagnostics.copy(
                            rebufferCount = rebufferCount,
                            rebufferTotalMs = rebufferTotalMs,
                            result = "Error: $detailedError"
                        )
                        lastPlaybackDiagnostics = errorDiagnostics
                        playbackFatalErrorRecorded = true
                        persistLastPlaybackDiagnostics(errorDiagnostics)

                        // Fatal error: stop any next-episode auto-play that may have been
                        // armed by a short placeholder ENDED or residual post-play state.
                        cancelNextEpisodeAutoPlayOnFatalError()
                        _uiState.update {
                            it.copy(
                                // The plain-words box (Spec 75 S4); detailedError still
                                // flows to diagnostics above.
                                error = failure,
                                showLoadingOverlay = false,
                                showPauseOverlay = false,
                                playbackEnded = false,
                                postPlayMode = null
                            )
                        }
                    }
                }
                currentExoPlayerListener = exoPlayerListenerForStream
                addListener(exoPlayerListenerForStream)

                currentExoAnalyticsListener?.let { staleListener -> removeAnalyticsListener(staleListener) }
                currentExoAnalyticsListener = null
                val exoAnalyticsListenerForStream = object : AnalyticsListener {
                    // The OSD audio badge: a switched track's format (or the extractor's
                    // DTS-HD refinement) reaches the renderer after onTracksChanged. ExoPlayer
                    // stores the format before forwarding this event.
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                    ) {
                        refreshOsdTechBadges()
                    }

                    override fun onAudioTrackInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
                    ) {
                        stepClock.soundOpened(android.os.SystemClock.elapsedRealtime())
                        // Audio review F9: the single cheapest observability win.
                        // Whether audio is passed through, decoded to PCM, or
                        // transcoded was previously invisible everywhere in-app.
                        val sourceMime = this@apply.audioFormat?.sampleMimeType
                        val sourceCodec = describeAudioMime(sourceMime)
                        // F9 label polish: the only transcode this app performs is
                        // x -> AC-3 (F5 denied-codec handling / Force AC-3). When the
                        // sink opened AC-3 for a non-AC-3 source, "Passthrough (AC-3)"
                        // is true at the sink layer but hides the conversion - name it.
                        val sinkPath = if (
                            audioTrackConfig.encoding == C.ENCODING_AC3 &&
                            sourceMime != null &&
                            sourceMime != MimeTypes.AUDIO_AC3
                        ) {
                            "AC-3 transcode"
                        } else {
                            describeAudioEncoding(audioTrackConfig.encoding)
                        }
                        val detail = buildString {
                            append(sinkPath)
                            append(" (")
                            append(audioTrackConfig.sampleRate / 1000)
                            append(" kHz")
                            val ch = Integer.bitCount(audioTrackConfig.channelConfig)
                            if (ch > 0) append(", ${ch}ch")
                            if (audioTrackConfig.tunneling) append(", tunneled")
                            if (audioTrackConfig.offload) append(", offload")
                            append(')')
                        }
                        currentAudioPathDescription = "$sourceCodec \u2192 $detail"
                        // Audio review F8: gate gain/skip-silence on the actual
                        // negotiated output mode rather than "a player exists".
                        val bypassing = when (audioTrackConfig.encoding) {
                            C.ENCODING_PCM_16BIT,
                            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_24BIT,
                            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_32BIT,
                            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_8BIT,
                            C.ENCODING_PCM_FLOAT -> false
                            else -> true
                        }
                        if (bypassing != isAudioOutputBypassing) {
                            isAudioOutputBypassing = bypassing
                            updateAudioControlAvailability()
                        }
                    }

                    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                        playbackAnalyticsDiagnostics.onPlaybackStateChanged(eventTime, state)
                    }

                    override fun onPlayWhenReadyChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playWhenReady: Boolean,
                        reason: Int
                    ) {
                        playbackAnalyticsDiagnostics.onPlayWhenReadyChanged(playWhenReady)
                        if (AfrNoisyPolicy.shouldResume(
                                noisyPause = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                                playWhenReady = playWhenReady,
                                userPausedManually = userPausedManually,
                                lastModeSwitchElapsedMs = lastAfrModeSwitchElapsedMs,
                                nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                            )
                        ) {
                            Log.i(PlayerRuntimeController.TAG, "AFR_NOISY_IGNORED: HDMI re-plug after a mode switch; resuming")
                            // Inline on the player looper: Media3's ListenerSet queues the nested events.
                            _exoPlayer?.play()
                        }
                    }

                    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                        playbackAnalyticsDiagnostics.onIsPlayingChanged(isPlaying)
                        // F9 fallback: the extension-renderer (FFmpeg) path never
                        // delivers onAudioTrackInitialized (boundary unnamed - see
                        // 2026-08-05 handover), so once audio is actually playing and
                        // the row is still empty, derive it from what the app owns:
                        // renderer input format (source) vs the format the sink was
                        // configured with. PCM at the sink = decode; same compressed
                        // MIME = passthrough; different compressed MIME = transcode.
                        // The null guard keeps the richer F9 event authoritative
                        // whenever it does fire, and the per-stream reset keeps this
                        // one-shot per stream.
                        // nt31: the MAT wrapper never configures the delegate and
                        // emits no track-init event, so both existing writers miss it
                        // (proven: the '-' row, 8 Aug captures). Name the path from
                        // the wrapper's own state.
                        if (isPlaying && currentAudioPathDescription == null &&
                            matRoutingAudioSink?.isMatActive() == true
                        ) {
                            currentAudioPathDescription = MAT_AUDIO_PATH_DESCRIPTION
                        }
                        if (isPlaying && currentAudioPathDescription == null) {
                            runCatching {
                                val source = this@apply.audioFormat
                                val sinkFormat = playbackSpeedAwareAudioSink?.lastConfiguredInputFormat
                                if (source?.sampleMimeType != null && sinkFormat?.sampleMimeType != null) {
                                    val src = describeAudioMime(source.sampleMimeType)
                                    val detail = buildString {
                                        when {
                                            sinkFormat.sampleMimeType == MimeTypes.AUDIO_RAW -> append("PCM decode")
                                            sinkFormat.sampleMimeType == source.sampleMimeType ->
                                                append("Passthrough (").append(src).append(')')
                                            else -> append(describeAudioMime(sinkFormat.sampleMimeType)).append(" transcode")
                                        }
                                        append(" (")
                                        if (sinkFormat.sampleRate > 0) append(sinkFormat.sampleRate / 1000).append(" kHz")
                                        if (sinkFormat.channelCount > 0) append(", ").append(sinkFormat.channelCount).append("ch")
                                        append(')')
                                    }
                                    currentAudioPathDescription = "$src \u2192 $detail"
                                }
                            }
                        }
                    }

                    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
                        playbackAnalyticsDiagnostics.onIsLoadingChanged(isLoading)
                    }

                    override fun onPlaybackParametersChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playbackParameters: PlaybackParameters
                    ) {
                        playbackAnalyticsDiagnostics.onPlaybackParametersChanged(playbackParameters)
                    }

                    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                        playbackAnalyticsDiagnostics.onPlayerError(eventTime, error)
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        currentDiagnostics = currentDiagnostics.copy(dv81DecoderName = decoderName)
                        playbackAnalyticsDiagnostics.onVideoDecoderInitialized(decoderName)
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "VIDEO_DECODER: name=$decoderName initMs=$initializationDurationMs host=${currentStreamUrl.safeHost()}"
                        )
                    }

                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        playbackAnalyticsDiagnostics.onVideoInputFormatChanged(format)
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioDecoderInitialized(decoderName)
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs)
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        playbackAnalyticsDiagnostics.onVideoFrameProcessingOffset(
                            totalProcessingOffsetUs = totalProcessingOffsetUs,
                            frameCount = frameCount
                        )
                    }

                    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                        playbackAnalyticsDiagnostics.onVideoDisabled(decoderCounters)
                    }

                    override fun onAudioUnderrun(
                        eventTime: AnalyticsListener.EventTime,
                        bufferSize: Int,
                        bufferSizeMs: Long,
                        elapsedSinceLastFeedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioUnderrun(
                            eventTime = eventTime,
                            bufferSize = bufferSize,
                            bufferSizeMs = bufferSizeMs,
                            elapsedSinceLastFeedMs = elapsedSinceLastFeedMs
                        )
                    }

                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        playbackAnalyticsDiagnostics.onBandwidthEstimate(
                            totalBytesLoaded = totalBytesLoaded,
                            bitrateEstimate = bitrateEstimate
                        )
                    }

                    // The collector fires both overloads per load; this one is not deprecated.
                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        retryCount: Int
                    ) {
                        playbackAnalyticsDiagnostics.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCanceled(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean
                    ) {
                        playbackAnalyticsDiagnostics.onLoadError(
                            eventTime = eventTime,
                            loadEventInfo = loadEventInfo,
                            mediaLoadData = mediaLoadData,
                            error = error,
                            wasCanceled = wasCanceled
                        )
                    }

                    override fun onPositionDiscontinuity(
                        eventTime: AnalyticsListener.EventTime,
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "SEEK_TRACE DISCONTINUITY er=${SystemClock.elapsedRealtime()} reason=$reason " +
                                "oldMs=${oldPosition.positionMs} newMs=${newPosition.positionMs} " +
                                "eventRealtimeMs=${eventTime.realtimeMs}"
                        )
                        // T9 (ysosrs 22aecb0e8, nt14): seek stamp for the rebuffer seek-grace
                        // (reason SEEK only; the snap classifier's stamp below stays unconditional).
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            lastSeekWallMs = SystemClock.elapsedRealtime()
                        }
                        // nt11 (0.8.2): stamp for the snap classifier --
                        // a stride NOT preceded by this stamp is a snap suspect.
                        snapShadowLastDiscontinuityWallMs = SystemClock.elapsedRealtime()
                    }
                }
                currentExoAnalyticsListener = exoAnalyticsListenerForStream
                addAnalyticsListener(exoAnalyticsListenerForStream)
            }
            fetchAddonSubtitles()
        } catch (e: CancellationException) {
            // KT-CONC-03: a superseded/released init must not fail over (marking
            // the URL dead) or record an error.
            throw e
        } catch (e: Exception) {
            // A superseded init (already cancelled) can still throw a plain
            // exception from its tail; it must not fail over either.
            currentCoroutineContext().ensureActive()
            val displayError = e.toDisplayMessage(context, context.getString(com.nuvio.tv.R.string.player_error_initialize_failed))
            val failure = engineFailure(displayError)
            if (attemptStartupExhaustedSourceFailover(failure)) {
                return@launch
            }
            val diagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = currentStreamUrl.safeHost(),
                filename = currentFilename ?: streamName ?: title,
                result = "Error: $displayError"
            )
            lastPlaybackDiagnostics = diagnostics
            playbackFatalErrorRecorded = true
            persistLastPlaybackDiagnostics(diagnostics)
            _uiState.update {
                it.copy(
                    error = failure,
                    showLoadingOverlay = false
                )
            }
        }
    }
    playerInitJob = initJob
    initJob.start()
}

/**
 * Cancels an in-flight initializePlayer coroutine (L1-82). Called at the top of
 * initializePlayer and from releasePlayer. Safe from inside that coroutine (its
 * engine/source failover re-enters both): only non-suspending code follows those
 * calls, so the self-cancel just ends it after the newer init has started.
 */
internal fun PlayerRuntimeController.cancelPlayerInit() {
    playerInitJob?.cancel()
    playerInitJob = null
    mpvInitializationInProgress = false
}

internal suspend fun PlayerRuntimeController.resolveAutoInternalPlayerEngine(): InternalPlayerEngine {
    val streamMetadataText = buildString {
        currentFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        currentStreamDescription?.let { appendLine(it) }
        append(title)
    }
    val isHdrOrDv = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""").containsMatchIn(streamMetadataText)

    return if (isHdrOrDv) {
        InternalPlayerEngine.EXOPLAYER
    } else {
        val hasAnimeId = currentVideoId?.startsWith("kitsu:") == true ||
                currentVideoId?.startsWith("mal:") == true ||
                currentVideoId?.startsWith("anilist:") == true

        if (hasAnimeId) return InternalPlayerEngine.MVP_PLAYER

        metaFetchJob?.let { job ->
            withTimeoutOrNull(META_FETCH_JOIN_TIMEOUT_MS) { job.join() }
        }

        val hasAnimeGenre = metaGenres.any { it.equals("anime", ignoreCase = true) }
        val isAnimationFromJapan = (metaGenres.any { it.equals("animation", ignoreCase = true) } &&
                metaCountry?.contains("Japan", ignoreCase = true) == true)

        val isAnime = hasAnimeGenre || isAnimationFromJapan

        if (isAnime) InternalPlayerEngine.MVP_PLAYER else InternalPlayerEngine.EXOPLAYER
    }
}

/**
 * Start engine before AUTO resolution: a manual override wins; else a file ExoPlayer cannot open
 * starts on mpv ([PlayerMediaSourceFactory.startsOnMpvByName]: Blu-ray .m2ts/.mts, format-capability
 * G2, mpv's FFmpeg demuxes BDAV and decodes the HDMV tracks, audio as PCM since mpv has no
 * passthrough; WMV/ASF, Spec 85 G8); else the setting (may be AUTO).
 */
internal fun startupEngineBeforeAuto(
    override: InternalPlayerEngine?,
    setting: InternalPlayerEngine,
    startsOnMpvByName: Boolean
): InternalPlayerEngine = override
    ?: if (startsOnMpvByName) InternalPlayerEngine.MVP_PLAYER else setting

/**
 * The engine the settings observer keeps for the session: the one this start runs; null under a
 * manual override, which is tracked separately. A changed engine setting applies from the next start.
 */
internal fun sessionEngineToKeep(
    override: InternalPlayerEngine?,
    effective: InternalPlayerEngine
): InternalPlayerEngine? = effective.takeIf { override == null }

/**
 * Spec 61 Q3 (NM 8dd8e5500): the engine a restart of the same stream asks for. A retry, a
 * fallback rung or a crash recovery stays on the engine the stream plays in: the one picked in
 * the player or by the startup failover ([override]), else the one this start resolved
 * ([session]), so an mpv-to-ExoPlayer failover is not undone by the next network retry and an
 * engine changed in Settings mid-film waits for the next stream. Null only before any start.
 */
internal fun sameStreamRestartEngine(
    override: InternalPlayerEngine?,
    session: InternalPlayerEngine?
): InternalPlayerEngine? = override ?: session

internal fun PlayerRuntimeController.currentStreamEngineForRestart(): InternalPlayerEngine? =
    sameStreamRestartEngine(override = runtimeInternalPlayerEngineOverride, session = sessionPlayerEngine)

/**
 * Called where the stream identity changes (another source or episode): from here until the new
 * stream's initializePlayer runs, a restart of "the current stream" is a restart of the new one,
 * and must not carry the previous stream's engine (red team d5df2bbf F3).
 */
internal fun PlayerRuntimeController.forgetStreamEngineForNewStream() {
    runtimeInternalPlayerEngineOverride = null
    sessionPlayerEngine = null
    // Spec 85 G1: the new stream (a re-pick of the same URL included) gets its own one automatic
    // engine switch; the guard only has to stop a loop within one stream's starts.
    engineHopStreamUrls.clear()
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            AudioLanguageOption.ORIGINAL -> contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            else -> normalized
        }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages
            .mapNotNull(::normalize)
            + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> {
            val originalLang = normalize(contentOriginalLanguage)
            if (originalLang != null) {
                listOfNotNull(
                    originalLang,
                    normalize(secondaryPreferredAudioLanguage)
                ).distinct()
            } else {
                // Fallback to device languages when original language is unknown
                (deviceLanguages
                    .mapNotNull(::normalize)
                    + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
                ).distinct()
            }
        }
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal fun resolveDeviceAudioLanguages(): List<String> {
    val localeList = Resources.getSystem().configuration.locales
    return List(localeList.size()) { localeList[it].isO3Language }
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    // A fetch still running for the previous stream must not write its results
    // (merged with that stream's own subtitles) over the new stream's state.
    addonSubtitleFetchJob?.cancel()
    addonSubtitleFetchJob = null
    trackSelection.streamStartSubtitlesReset()
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    // A pick parked for the previous stream must not attach at the new one's
    // first pause; the new stream runs its own subtitle restore.
    deferredAutoAddonSubtitle = null
    attachedAddonSubtitleKeys = emptySet()
    stopSidecarAddonSubtitle(clearView = true)
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = false
        )
    }
}

internal fun PlayerRuntimeController.prepareStreamStartSubtitles(playerSettings: PlayerSettings) {
    requestedUseLibassByUser = playerSettings.useLibass
    if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
        libassPipelineDecisionStreamUrl = currentStreamUrl
        libassPipelineOverrideForCurrentStream = null
        libassPipelineSwitchInFlight = false
        hasDetectedAssSsaTrackForCurrentStream = false
    }
    resetAddonSubtitleStateForNewStream()
}

/** [streamUrl]: the URL this build opens (the step clock counts only its calls, Spec 75). */
internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream(streamUrl: String = currentStreamUrl) {
    // N6 V2: drop the previous session's resolved serving host so a new
    // stream (initial play or any switch) never shows the prior title's
    // CDN host during TTFF.
    PlaybackConnectionEvents.clearResolvedHost()
    // Spec 75 S3: every player build is a new attempt of the step line (a retry re-measures).
    PlaybackConnectionEvents.stepClock = stepClock
    stepClock.start(android.os.SystemClock.elapsedRealtime(), stepSourcesFoundMs, streamUrl)
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    cancelStartupWatchdog()
    val preparingMessage = context.getString(R.string.player_loading_preparing)
    resetLoadingDiagnostics(
        phase = "preparing",
        message = preparingMessage,
        progress = null
    )
    hasRenderedFirstFrame = false
    exoEndOverrunHandled = false
    startPrimeTargetMs = null
    videoNeedsWarmDecoder = false
    hasMarkedCurrentEpisodeCompleted = false
    // The placeholder probe is a per-stream measurement, not a
    // per-screen one: the reuse path re-enters initializePlayer on the
    // same controller, so without this reset it evaluated only the first
    // stream of a binge and was inert on every transition after it.
    placeholderProbeDone = false
    placeholderRejectedForStream = false
    // Spec 35: the runtime check and its resume flag are per stream too.
    runtimeMismatchChecked = false
    resumePositionApplied = false
    _uiState.update { it.copy(runtimeMismatch = null) }
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    hasRetriedAfterMimeOverrideClear = false
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
    isVc1SoftwareFallbackActiveForCurrentPlayback = false
    isVc1TrackSelectionBypassActiveForCurrentPlayback = false
    isSafeAudioModeActiveForCurrentPlayback = false
    isAudioDisabledForCurrentPlayback = false
    playerInitializationStartedAtMs = 0L
    lastKnownDuration = 0L
    currentStreamHasVideoTrack = false
    currentVideoTrackIsLikelyVc1 = false
    currentVideoTrackMimeType = null
    currentVideoTrackCodecs = null
    currentVideoTrackWidth = 0
    currentVideoTrackHeight = 0
    currentVideoTrackBitrate = -1
    currentVideoTrackColorTransfer = null
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    osdVideoHdrType = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingMessage = preparingMessage,
            loadingProgress = null,
            osdTechBadges = emptyList(),
            audioOutputMode = null,
            isHdrOutput = false
        )
    }
}

/**
 * The renderer extension mode a build runs: PREFER when software decode must lead (VC-1 fallback,
 * the PCM audio fallback, Bluetooth; paired with preferSoftwareAudioOnly so video stays on
 * MediaCodec except for VC-1), at least ON under force-optical passthrough, else the setting.
 * Shared by the VOD and Live TV players.
 */
internal fun effectiveDecoderPriority(
    decoderPriority: Int,
    preferExtensions: Boolean,
    forcePassthroughActive: Boolean
): Int = when {
    preferExtensions -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    forcePassthroughActive -> maxOf(decoderPriority, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    else -> decoderPriority
}

/**
 * F3: the passthrough groups this chain was observed to refuse on [routeKey] (`route::GROUP`
 * entries confirmed across sessions). None without a known route, so an ARC->eARC change starts clean.
 */
internal fun learnedDeniedAudioGroups(
    confirmed: Set<String>,
    routeKey: String?
): Set<com.nuvio.tv.core.player.AudioPassthroughPolicy.Group> {
    if (routeKey == null) return emptySet()
    return confirmed.mapNotNull { entry ->
        val parts = entry.split("::")
        if (parts.size != 2 || parts[0] != routeKey) return@mapNotNull null
        runCatching { com.nuvio.tv.core.player.AudioPassthroughPolicy.Group.valueOf(parts[1]) }.getOrNull()
    }.toSet()
}

/**
 * The per-format passthrough policy from the user's toggles. softwareDecodersAvailable gates it on
 * a fallback decoder existing: with Decoder Priority "Device only" the FFmpeg audio renderer is
 * absent, so the policy denies nothing.
 */
internal fun audioPassthroughPolicyOf(
    settings: PlayerSettings,
    effectiveDecoderPriority: Int,
    learnedDeniedGroups: Set<com.nuvio.tv.core.player.AudioPassthroughPolicy.Group>
) = com.nuvio.tv.core.player.AudioPassthroughPolicy(
    allowAc3 = settings.allowAc3Passthrough,
    allowEac3 = settings.allowEac3Passthrough,
    allowTrueHd = settings.allowTrueHdPassthrough,
    allowDts = settings.allowDtsPassthrough,
    allowDtsHd = settings.allowDtsHdPassthrough,
    softwareDecodersAvailable = effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
    learnedDeniedGroups = learnedDeniedGroups
)

// ── CUSTOM RENDERERS FOR AUDIO/SUBTITLES ──

/** VOD and Live TV renderers: passthrough policy, pinned TV capabilities, FFmpeg audio, MAT, offsets. */
internal class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long,
    private val shouldStripSdhProvider: () -> Boolean,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean = { false },
    private val videoBoundsFractionProvider: () -> RectF?,
    private val gainAudioProcessor: GainAudioProcessor,
    private val audioOutputPlan: AudioOutputPlan,
    private val deniedTranscodeMimes: Set<String>,
    private val preferFfmpegAudio: Boolean,
    private val matPassthroughEnabled: Boolean,
    private val audioPassthroughPolicy: com.nuvio.tv.core.player.AudioPassthroughPolicy,
    private val bluetoothForcePcm: Boolean = false,
    private val playbackSpeedProvider: () -> Float,
    private val initialForcePcm: Boolean = false,
    /**
     * When true, [EXTENSION_RENDERER_MODE_PREFER] applies to audio only — video stays on the
     * platform MediaCodec path so Bluetooth PCM policy or the PCM audio fallback does not force
     * software video decode.
     */
    private val preferSoftwareAudioOnly: Boolean = false,
    private val onPlaybackSpeedAwareAudioSinkCreated: (PlaybackSpeedAwareAudioSink) -> Unit,
    private val onMatRoutingAudioSinkCreated: (com.nuvio.tv.core.player.mat.MatRoutingAudioSink?) -> Unit,
    private val onFfmpegAudioRendererChanged: (FfmpegAudioRenderer?) -> Unit
) : DefaultRenderersFactory(context) {

    /**
     * The renderer's DV7-to-HEVC mapping flag. Set this, never the final [setMapDV7ToHevc]
     * directly: [buildVideoRenderers] rebuilds the platform video renderer from a fresh
     * builder and can only read the flag from here (the base class keeps it private). The
     * vendored lib-exoplayer AAR stores the flag but never acts on it (its getDecoderInfos
     * ignores the argument), so HDR10 base-layer playback comes from the extractors
     * (hdr10BaseLayerMode, RPU strip); this keeps the renderer consistent if a rebuilt AAR
     * honours it. The factory's other renderer-only video settings are never set in this
     * app, so the builder's defaults already equal the factory's.
     */
    var mapDv7ToHevc: Boolean = false
        set(value) {
            field = value
            setMapDV7ToHevc(value)
        }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoExtensionMode = when {
            !preferSoftwareAudioOnly -> extensionRendererMode
            extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER -> EXTENSION_RENDERER_MODE_ON
            else -> extensionRendererMode
        }
        val vc1RestampTmp = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            videoExtensionMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            vc1RestampTmp
        )
        // VC-1 pts-repair: replace ONLY the platform MediaCodec video renderer with a
        // subclass that restamps VC-1 output timestamps; extension renderers (VP9/AV1)
        // and their PREFER/ON ordering pass through untouched.
        for (vc1RestampRenderer in vc1RestampTmp) {
            if (vc1RestampRenderer::class.java == MediaCodecVideoRenderer::class.java) {
                val vc1RestampBuilder = MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(
                        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                    )
                    .setMapDV7ToHevc(mapDv7ToHevc)
                out.add(Vc1PtsRepairVideoRenderer(vc1RestampBuilder))
            } else {
                out.add(vc1RestampRenderer)
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // Bluetooth: pin Media3-equivalent DEFAULT (PCM-only) so TV HDMI profiles / force-optical
        // cannot advertise AC3/DTS passthrough while audio is routed to A2DP.
        // Otherwise, on Android TV, pin audio capabilities: build the sink WITHOUT a Context so
        // media3 does NOT install a live AudioCapabilitiesReceiver. On the context
        // path, any audio-device change (e.g. a Bluetooth remote idling for battery,
        // then reinitialising) makes media3 re-query capabilities mid-playback; if
        // the HAL transiently omits the passthrough encoding, DefaultAudioSink
        // renegotiates and drops Atmos/TrueHD/DTS-HD to PCM. VLC avoids this by not
        // subscribing to those events. We probe once (with the MEDIA/MOVIE
        // attributes playback uses) and pin.
        // On phones/tablets, keep the live receiver — headphone/Bluetooth/routing
        // changes are common there and must be handled dynamically.
        // TRADE-OFF (TV): capabilities fixed at build time — no adaptation if the
        // output genuinely changes mid-playback; a cold AVR/soundbar wake-up may be
        // probed before it reports passthrough (recovered by the AVR-wake rebuild, T14:
        // pinnedCapabilities below + schedulePassthroughReturnCheck). A Bluetooth
        // route flip does NOT rebuild the player: the route callback updates the live
        // sink in place (applyBluetoothAudioRouteInPlace -> setBluetoothForcePcm), so the
        // bluetoothForcePcm branch here only reflects the route at build time, and the
        // capabilities pinned at build persist until the next rebuild.
        val isTelevision =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val pinnedTvCapabilities = if (!bluetoothForcePcm && isTelevision) {
            AudioCapabilities.getCapabilities(context, movieAudioAttributes, null)
        } else {
            null
        }
        // why: pinning needs the context-free Builder + setAudioCapabilities (see above); Media3
        // deprecates both and has no non-deprecated way to pin, Builder(context) always goes live.
        @Suppress("DEPRECATION")
        val sinkBuilder = when {
            bluetoothForcePcm -> {
                DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioOutputRouteDetector.bluetoothPcmOnlyCapabilities())
            }
            pinnedTvCapabilities != null -> {
                DefaultAudioSink.Builder().setAudioCapabilities(pinnedTvCapabilities)
            }
            else -> DefaultAudioSink.Builder(context)
        }
        val builder = sinkBuilder
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
        val baseAudioSink = builder.build()
        // Read-only capability snapshot for the diagnostics page. Never opens an
        // AudioTrack - startup IEC61937 activity is a wedge risk on some HALs.
        AudioCapabilityReport.capture(context)
        // Diagnostic harness (build 1): arm with
        //   adb shell settings put global nuvio_fault_reject_mime audio/vnd.dts.hd
        // disarm with `settings delete global nuvio_fault_reject_mime`. Read at build
        // time, so arm it before starting playback. Inert when the setting is absent.
        val faultInjectRejectMime = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "nuvio_fault_reject_mime")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (faultInjectRejectMime != null) {
            Log.w(PlayerRuntimeController.TAG, "FAULT_INJECT armed: passthrough for $faultInjectRejectMime will be refused")
        }
        val playbackSpeedAwareAudioSink = PlaybackSpeedAwareAudioSink(
            baseAudioSink,
            initialForcePcm,
            forceAc3Support = audioOutputPlan.forceOpticalPassthrough || deniedTranscodeMimes.isNotEmpty(),
            passthroughPolicy = audioPassthroughPolicy,
            faultInjectRejectMime = faultInjectRejectMime,
            forcePcmForBluetooth = bluetoothForcePcm,
            pinnedCapabilities = pinnedTvCapabilities
        )
        playbackSpeedAwareAudioSink.setInitialPlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioSinkCreated(playbackSpeedAwareAudioSink)
        // §9.5: wrap the existing sink so TrueHD can be routed through the app-side
        // MAT/IEC61937 packer when the toggle is on. The wrapper delegates every call
        // to playbackSpeedAwareAudioSink unless it is actively routing TrueHD, so with
        // the toggle off (or on a box whose IEC61937 sink will not open) behaviour is
        // exactly as before.
        // §9.5: only wrap when the MAT toggle is on. When off, the wrapper is never
        // constructed - zero app-side MAT code is in the audio path (not merely dormant).
        return if (matPassthroughEnabled) {
            val matSink = com.nuvio.tv.core.player.mat.MatRoutingAudioSink(playbackSpeedAwareAudioSink)
            onMatRoutingAudioSinkCreated(matSink)
            matSink
        } else {
            // A rebuild with the toggle off must clear any stale wrapper reference so
            // the HUD/Audio Path never read MAT state from a released sink.
            onMatRoutingAudioSinkCreated(null)
            playbackSpeedAwareAudioSink
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val playbackAwareSink = audioSink as? PlaybackSpeedAwareAudioSink
        val startIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (playbackAwareSink != null && out.size > startIndex) {
            val mediaCodecAudioRendererIndex = (startIndex until out.size)
                .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
                ?: startIndex
            out[mediaCodecAudioRendererIndex] =
                PlaybackSpeedAwareAudioRenderer(
                    rendererContext = context,
                    codecAdapterFactory = getCodecAdapterFactory(),
                    mediaCodecSelector = mediaCodecSelector,
                    enableDecoderFallback = enableDecoderFallback,
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    playbackSpeedAwareAudioSink = playbackAwareSink
                )
        }
        applyFfmpegRendererSettings(out)
        // Audio review F4: when Force AC-3 Transcoding is on, the FFmpeg audio
        // renderer must outrank MediaCodec audio for the transcode to engage -
        // but the previous approach (forcing EXTENSION_RENDERER_MODE_PREFER
        // globally) also reordered *video* extension renderers, putting software
        // AV1 (Libgav1) ahead of the hardware decoder. Reorder audio-locally:
        // move FFmpeg audio renderers ahead of other audio renderers in the
        // block we just built, leaving video renderer order untouched.
        if ((audioOutputPlan.forceOpticalPassthrough || preferFfmpegAudio) && out.size > startIndex) {
            val audioBlock = out.subList(startIndex, out.size)
            val reordered = audioBlock.sortedByDescending { it is FfmpegAudioRenderer }
            for (i in reordered.indices) audioBlock[i] = reordered[i]
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = SdhFilteringTextOutput(output, shouldStripSdhProvider),
            isBuiltInSubtitleProvider = isBuiltInSubtitleProvider,
            isSidecarAddonSubtitleActiveProvider = isSidecarAddonSubtitleActiveProvider,
            videoBoundsFractionProvider = videoBoundsFractionProvider
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        // Before the wrap below hides the TextRenderer type.
        RenderTimeSubtitles.enableRenderTimeDecoding(out, startIndex)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
                audioDelayUsProvider = audioDelayUsProvider
            )
        }
    }

    private fun applyFfmpegRendererSettings(out: ArrayList<Renderer>) {
        val ffmpegRenderers = out.filterIsInstance<FfmpegAudioRenderer>()
        ffmpegRenderers.forEach { renderer ->
            renderer.setDeniedTranscodeMimes(deniedTranscodeMimes)
            renderer.applyAudioOutputPlan(audioOutputPlan)
        }
        onFfmpegAudioRendererChanged(ffmpegRenderers.firstOrNull())
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        if (cueGroup.cues.isEmpty()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(CueGroup(normalizeCues(cueGroup.cues), cueGroup.presentationTimeUs))
    }

    // why: TextRenderer still calls both overloads and ExoPlayerImpl rebroadcasts this one to
    // Player.Listener.onCues(List), so the decorator forwards it unchanged (as SdhFilteringTextOutput).
    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        if (cues.isEmpty()) {
            delegate.onCues(cues)
            return
        }
        delegate.onCues(normalizeCues(cues))
    }

    /** Processes each cue (copying the list only once a cue changes), then merges overlaps. */
    private fun normalizeCues(cues: List<Cue>): List<Cue> {
        var modifiedList: ArrayList<Cue>? = null
        val count = cues.size
        for (i in 0 until count) {
            val original = cues[i]
            val processed = processCue(original)
            if (processed !== original) {
                if (modifiedList == null) {
                    modifiedList = ArrayList(count)
                    for (j in 0 until i) {
                        modifiedList.add(cues[j])
                    }
                }
                modifiedList.add(processed)
            } else {
                modifiedList?.add(original)
            }
        }
        return PlayerSubtitleUtils.mergeOverlappingCues(modifiedList ?: cues)
    }

    private fun processCue(cue: Cue): Cue {
        var processed = SubtitleMojibakeSanitizer.sanitizeCue(cue)
        processed = PlayerSubtitleRtlFix.fixCueText(processed, isBuiltInSubtitleProvider())
        // 0.9.0: normalize line positions for ALL text cues (embedded + addon) — converted
        // subs carrying stray vertical offsets render at the standard position. Bitmap
        // (PGS), vertical-type, and top/middle-placed cues are kept (Spec 36/S3).
        processed = PlayerSubtitleUtils.normalizeBottomCuePosition(processed)
        if (processed.bitmap != null) {
            val bounds = videoBoundsFractionProvider()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
                val isIdentity = bounds.left == 0f && bounds.top == 0f
                    && bounds.width() == 1f && bounds.height() == 1f
                if (!isIdentity) {
                    processed = remapBitmapCueToVideoBounds(processed, bounds)
                }
            }
        }
        return processed
    }

    private fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
        val builder = cue.buildUpon()
        if (cue.position != Cue.DIMEN_UNSET) {
            builder.setPosition(bounds.left + cue.position * bounds.width())
        }
        if (cue.size != Cue.DIMEN_UNSET) {
            builder.setSize(cue.size * bounds.width())
        }
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
            builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
        }
        if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
        }
        return builder.build()
    }
}

internal class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val subtitleOffsetUs = subtitleDelayUsProvider()
        val audioOffsetUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs + audioOffsetUs - subtitleOffsetUs).coerceAtLeast(0L)
        
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

/** Upstream `PassthroughOutputReturn.SECOND_CHECK_DELAY_MS`: an HDMI drop's refusal precedes its capability change. */
private const val AUDIO_REJECTION_SETTLE_MS = 3_000L

/** A device add/remove this long before a refusal (or up to the settle re-check) marks the route as changing. */
private const val AUDIO_REJECTION_DEVICE_QUIET_MS = 10_000L

internal fun PlayerRuntimeController.recordAudioTrackRejectionIfBitstream(error: PlaybackException, learn: Boolean) {
    // A 5002 write failure is a track that opened and then died: never a refusal (P1).
    if (!isAudioTrackInitFailure(error.errorCode, error.causeChainText())) return
    val format = failedAudioTrackInputFormat(error) ?: return
    val label = AudioTrackRejectionLog.labelForMime(format.sampleMimeType) ?: return
    val routeKey = detectAudioRouteKey()
    AudioTrackRejectionLog.record(label, routeKey, System.currentTimeMillis())
    Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION encoding=$label route=$routeKey")
    // F3: persist for cross-session learning, keyed by policy group and route, at most once
    // per session per group (the two-session confirm guard lives in the datastore), only when
    // no same-config retry was scheduled ([learn], P5), and only when the outage guard passes
    // both now and after the settle delay.
    if (!learn) return
    val group = com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(format.sampleMimeType) ?: return
    val routeAtBuild = audioRouteKeyAtPlayerBuild
    val refusedAtMs = android.os.SystemClock.elapsedRealtime()
    fun learning(route: String?) = audioRejectionLearning(
        routeAtBuild = routeAtBuild,
        routeNow = route,
        deviceChangedNearRefusal = lastAudioDeviceChangeAtMs >= refusedAtMs - AUDIO_REJECTION_DEVICE_QUIET_MS,
        outputStillClaims = liveOutputClaimsPassthrough(format)
    )
    val atRefusal = learning(routeKey)
    if (atRefusal != AudioRejectionLearning.LEARN) {
        Log.i(PlayerRuntimeController.TAG, "AUDIO_REJECTION_NOT_LEARNED encoding=$label reason=$atRefusal")
        return
    }
    val entry = "$routeKey::${group.name}"
    scope.launch {
        delay(AUDIO_REJECTION_SETTLE_MS)
        val afterSettle = learning(detectAudioRouteKey())
        if (afterSettle != AudioRejectionLearning.LEARN) {
            Log.i(PlayerRuntimeController.TAG, "AUDIO_REJECTION_NOT_LEARNED encoding=$label reason=$afterSettle (after settle)")
            return@launch
        }
        if (AudioTrackRejectionLog.markGroupFirstThisSession(entry)) {
            playerSettingsDataStore.recordAudioRejection(entry)
        }
    }
}

internal fun PlayerRuntimeController.detectAudioRouteKey(): String? =
    runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()

/**
 * Whether the output, probed live (the sink's pinned capabilities are from build time), still
 * takes [format] as a bitstream; same attributes as the TV sink probe.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.liveOutputClaimsPassthrough(format: androidx.media3.common.Format): Boolean =
    runCatching {
        AudioCapabilities.getCapabilities(context, movieAudioAttributes, null)
            .isPassthroughPlaybackSupported(format, movieAudioAttributes)
    }.getOrDefault(false)

/**
 * F-1: true for a video track this playback converted from DV7 (its codec string was rewritten
 * to profile 8, [codecStringRewrites] > 0); such a start must not be tunnelled.
 */
internal fun dvConversionBlocksTunneling(codecs: String?, codecStringRewrites: Long): Boolean =
    codecStringRewrites > 0 && DvCodecString.profile(codecs) == 8

/** Whether this playback tunnels: the setting, minus an F-1 guarded start (valid once tracks are selected). */
internal fun PlayerRuntimeController.tunnelsCurrentPlayback(
    settings: com.nuvio.tv.data.local.PlayerSettings
): Boolean = settings.effectiveTunnelingEnabled && !isTunnelingSuppressedForDvConversion

/** Human-friendly codec name for the diagnostics card. */
private fun friendlyVideoCodecName(mimeType: String?, codecs: String?): String? {
    val mime = mimeType?.lowercase()
    return when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        mime == MimeTypes.VIDEO_H265 -> "HEVC"
        mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_VP9 -> "VP9"
        mime.startsWith("video/") -> mime.removePrefix("video/").uppercase()
        else -> codecs ?: mime
    }
}

private fun createDolbyVisionFallbackCodecSelector(
    convertToDv81Active: Boolean = false
): MediaCodecSelector {
    // HDR10 base-layer playback comes from the extractors (hdr10BaseLayerMode, RPU strip);
    // the renderer's setMapDV7ToHevc is a no-op in the vendored AAR. We must NOT force video/dolby-vision to the HEVC
    // decoder here: that also catches DV5, which has no HDR10 base layer and ends up
    // decoded without its reshaping (wrong colors). DV5 keeps the DV decoder.
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
        if (defaults.isNotEmpty()) {
            return@MediaCodecSelector defaults
        }
        // VC-1 MIME bridge. media3 tags Blu-ray VC-1 as video/wvc1, but Amlogic
        // (S905X5) registers its VC-1 decoders as video/vc1, so a video/wvc1 query
        // returns nothing on hardware that can decode it. Proven on AM9 Pro via
        // MediaCodecList: supportedTypes=[video/vc1] for c2.amlogic.vc1.decoder and
        // .sw; queryable_as_video/wvc1=[]. Re-query under video/vc1; the returned
        // MediaCodecInfo carries codecMimeType=video/vc1, which the renderer sets as
        // the MediaFormat KEY_MIME at configure, so the codec gets a string it accepts.
        if (mimeType.equals(MimeTypes.VIDEO_VC1, ignoreCase = true)) {
            val vc1Decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                "video/vc1", requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (vc1Decoders.isNotEmpty()) {
                return@MediaCodecSelector vc1Decoders
            }
        }
        if (convertToDv81Active && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            return@MediaCodecSelector DolbyVisionCodecFallback.findDvDecodersIgnoringProfile()
        }
        defaults
    }
}

private class SafeBandwidthMeter(
    private val delegate: BandwidthMeter,
    private val isHls: Boolean
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long {
        val raw = delegate.bitrateEstimate
        return if (isHls) maxOf(raw, 25_000_000L) else raw
    }

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = delegate.transferListener

    override fun addEventListener(
        eventHandler: android.os.Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        delegate.addEventListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}

/** Audio review F9: human-readable source codec from the selected track's mime. */
private fun describeAudioMime(mime: String?): String = when (mime) {
    androidx.media3.common.MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    androidx.media3.common.MimeTypes.AUDIO_DTS -> "DTS"
    androidx.media3.common.MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    androidx.media3.common.MimeTypes.AUDIO_AC3 -> "AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
    androidx.media3.common.MimeTypes.AUDIO_AC4 -> "AC-4"
    androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
    androidx.media3.common.MimeTypes.AUDIO_FLAC -> "FLAC"
    androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
    null -> "Audio"
    else -> mime.substringAfterLast('/').uppercase()
}

/** Audio review F9: sink output mode from the negotiated AudioTrack encoding. */
private fun describeAudioEncoding(encoding: Int): String = when (encoding) {
    C.ENCODING_AC3 -> "Passthrough (AC-3)"
    C.ENCODING_E_AC3 -> "Passthrough (E-AC-3)"
    C.ENCODING_E_AC3_JOC -> "Passthrough (E-AC-3 JOC)"
    C.ENCODING_DOLBY_TRUEHD -> "Passthrough (TrueHD)"
    C.ENCODING_DTS -> "Passthrough (DTS)"
    C.ENCODING_DTS_HD -> "Passthrough (DTS-HD)"
    C.ENCODING_AC4 -> "Passthrough (AC-4)"
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_FLOAT -> "PCM decode"
    else -> "Encoding $encoding"
}

/**
 * The current stream's DV source profile: the one the extractors recorded (the MKV transformer
 * and the MP4/TS track output record it when converting or stripping, i.e. whenever they rewrite
 * the codec string), else [preferredCodecs], else the selected video track's codec string.
 */
internal fun PlayerRuntimeController.currentDvSourceProfile(preferredCodecs: String? = null): Int? =
    DolbyVisionConversionStats.getLastSourceProfile()
        ?: DvCodecString.profile(preferredCodecs ?: currentVideoTrackCodecs)

private fun PlayerRuntimeController.recordFirstFrameDiagnostics(
    player: ExoPlayer,
    currentDiagnostics: LastPlaybackDiagnostics,
    playerSettings: com.nuvio.tv.data.local.PlayerSettings
): LastPlaybackDiagnostics {
    val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs).coerceAtLeast(0L)
    val conversionCalls = DoviBridge.getConversionCallCount()
    val conversionSucceeded = DoviBridge.getConversionSuccessCount()
    val signalingRewrites = DolbyVisionConversionStats.getCodecStringRewriteCount()
    val sourceProfile = currentDvSourceProfile()
    val clickToFirstFrameMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    val startupCounters = playbackAnalyticsDiagnostics.startupCounters(player, hasRenderedFirstFrame = true)
    val videoFormat = startupCounters.videoFormat
    val playbackStartupLine =
        "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
            "initToFirstFrameMs=$startupMs playbackSpeed=${player.playbackParameters.speed} " +
            "pitch=${player.playbackParameters.pitch} startPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
            "currentPositionMs=${player.currentPosition.coerceAtLeast(0L)} bufferedMs=${player.bufferedPosition.coerceAtLeast(0L)} " +
            "durationMs=${player.duration.takeIf { it > 0L } ?: -1L} " +
            "video=${videoFormat?.sampleMimeType ?: currentVideoTrackMimeType ?: "n/a"} " +
            "codecs=${videoFormat?.codecs ?: currentVideoTrackCodecs ?: "n/a"} " +
            "size=${videoFormat?.width?.takeIf { it > 0 } ?: currentVideoTrackWidth}x" +
            "${videoFormat?.height?.takeIf { it > 0 } ?: currentVideoTrackHeight} " +
            "frameRate=${videoFormat?.frameRate?.takeIf { it > 0f } ?: -1f} " +
            "bitrate=${videoFormat?.bitrate?.takeIf { it > 0 } ?: -1} " +
            "bandwidthBps=${startupCounters.bandwidthEstimateBps ?: -1L} " +
            "loads=${startupCounters.loadCompletedCount}/${startupCounters.loadStartedCount} " +
            "bytesLoaded=${startupCounters.totalBytesLoaded} droppedFrames=${startupCounters.droppedFrames} " +
            "audioUnderruns=${startupCounters.audioUnderrunCount} rebufferCount=$rebufferCount " +
            "host=${currentStreamUrl.safeHost()} engine=$currentInternalPlayerEngine"
    TtffTrace.mirror(playbackStartupLine)

    val dvConversionOccurred = conversionSucceeded > 0 ||
        signalingRewrites > 0 ||
        sourceProfile != null

    currentBitrateAwareLoadControl?.let { lc ->
        val budgetManaged = playerSettings.bufferBudgetManaged
        val keepZeroForDv7 = budgetManaged && conversionSucceeded > 0L &&
                MemoryBudget.isLowHeapTier
        // Only the BUDGET can be tightened at runtime. The back buffer was fixed when
        // this player was constructed and media3 will not re-read it, so it is reported
        // as built rather than as intended.
        if (keepZeroForDv7) {
            lc.setBudgetBytesOverride(
                MemoryBudget.conversionBudgetMb.toLong() * 1024L * 1024L
            )
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "BACK_BUFFER_RESOLVED: dvConversion=$dvConversionOccurred " +
                    "lowHeap=${MemoryBudget.isLowHeapTier} lowRam=${MemoryBudget.isLowRamTier} " +
                    "effectiveBackBufferMs=$effectiveBackBufferDurationMs " +
                    "managed=$budgetManaged " +
                    "budgetMb=${when {
                        keepZeroForDv7 -> MemoryBudget.conversionBudgetMb
                        budgetManaged -> MemoryBudget.budgetMb
                        else -> MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                    }} " +
                    "host=${currentStreamUrl.safeHost()}"
        )
    }
    val capabilityReport = AudioCapabilityReport.latest
    val finalDiagnostics = currentDiagnostics.copy(
        firstFrameMs = startupMs,
        // N6 V2 follow-on: persist the post-redirect serving host the NET_CONN
        // listener captured for this session, so Diagnostics can show it after
        // playback (the live holder is process-scoped and cleared at each new
        // stream). At first frame, bytes have demonstrably flowed through the
        // listener-carrying client, so the value is populated whenever the
        // source actually redirected.
        resolvedServingHost = PlaybackConnectionEvents.resolvedHost(),
        dv7DoviCalls = conversionCalls.toInt(),
        dv7DoviSuccess = conversionSucceeded.toInt(),
        dv7DoviSignalRewrites = signalingRewrites.toInt(),
        dvSourceProfile = sourceProfile?.toString(),
        dvElType = when (DolbyVisionConversionStats.getLastElType()) {
            DoviBridge.EL_TYPE_FEL -> DV_LAYER_FEL
            DoviBridge.EL_TYPE_MEL -> DV_LAYER_MEL
            DoviBridge.EL_TYPE_NONE, -1 -> "unknown"
            else -> null
        },
        dv7RpuDrops = DolbyVisionConversionStats.getRpuDropCount().toInt(),
        dvHdrMastering = DolbyVisionConversionStats.getLastRpuMetadata()?.toDiagnosticLine(),
        videoResolution = if (currentVideoTrackWidth > 0 && currentVideoTrackHeight > 0)
            "${currentVideoTrackWidth}x${currentVideoTrackHeight}" else null,
        videoCodec = friendlyVideoCodecName(currentVideoTrackMimeType, currentVideoTrackCodecs),
        videoHdrType = friendlyVideoHdrType(
            currentVideoTrackMimeType,
            currentVideoTrackColorTransfer,
            currentDiagnostics.dv7ModeEffective,
            dvConversionOccurred
        ),
        // nt33: the record is finalised AT FIRST FRAME, which under AFR precedes MAT
        // engagement entirely (the first frame renders during the settle, proven in
        // the 8 Aug capture), so no writer running at or after engagement can ever
        // land in this snapshot. Evaluate the MAT fallback at the snapshot itself -
        // ordering-proof by construction, reading the same live wrapper state the
        // HUD row already proves readable.
        audioPath = currentAudioPathDescription
            ?: if (matRoutingAudioSink?.isMatActive() == true)
                MAT_AUDIO_PATH_DESCRIPTION
            else null,
        audioCapabilities = capabilityReport?.text,
        audioListedNotOpened = capabilityReport?.listedNotOpened,
        videoBitrate = run {
            val durationMsVal = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val sizeBytes = currentVideoSize
            if (sizeBytes != null && sizeBytes > 0L && durationMsVal > 0L) {
                val durationSecs = durationMsVal / 1000.0
                ((sizeBytes * 8.0) / durationSecs).toInt()
            } else {
                currentVideoTrackBitrate
            }
        },
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        result = "Played"
    )
    lastPlaybackDiagnostics = finalDiagnostics
    osdVideoHdrType = finalDiagnostics.videoHdrType
    // Spec 62 D7: the output type also picks the HDR subtitle style (PlayerUiState.isHdrOutput).
    _uiState.update { it.copy(isHdrOutput = isHdrOutputType(finalDiagnostics.videoHdrType)) }
    refreshOsdTechBadges()
    persistLastPlaybackDiagnostics(finalDiagnostics)
    return finalDiagnostics
}

private fun PlayerView.videoBoundsFraction(aspectRatio: Float): RectF? {
    val subtitleView = this.subtitleView ?: return null
    val viewWidth = subtitleView.width.toFloat()
    val viewHeight = subtitleView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    if (aspectRatio > 0f) {
        val parentRatio = viewWidth / viewHeight
        return if (parentRatio > aspectRatio) {
            val fitW = viewHeight * aspectRatio
            val leftPx = (viewWidth - fitW) / 2f
            RectF(leftPx / viewWidth, 0f, (leftPx + fitW) / viewWidth, 1f)
        } else {
            val fitH = viewWidth / aspectRatio
            val topPx = (viewHeight - fitH) / 2f
            RectF(0f, topPx / viewHeight, 1f, (topPx + fitH) / viewHeight)
        }
    }

    val contentFrame = getTag(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        ?: findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.also { setTag(androidx.media3.ui.R.id.exo_content_frame, it) }
        ?: return null
    val frameWidth = contentFrame.width.toFloat()
    val frameHeight = contentFrame.height.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return null
    if (frameWidth > viewWidth || frameHeight > viewHeight) return null
    val left = contentFrame.x / viewWidth
    val top = contentFrame.y / viewHeight
    return RectF(
        left,
        top,
        left + frameWidth / viewWidth,
        top + frameHeight / viewHeight,
    )
}
