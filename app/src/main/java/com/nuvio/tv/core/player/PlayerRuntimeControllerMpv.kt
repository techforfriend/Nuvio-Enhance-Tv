package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import android.util.Log
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MPV_RESUME_SEEK_TOLERANCE_MS = 1500L

internal fun PlayerRuntimeController.attachMpvView(view: NuvioMpvSurfaceView?) {
    if (mpvView === view) return
    val outgoing = mpvView
    mpvView = view
    // Spec 85: a view that left the screen never renders again, so its core goes with it (an HTTP
    // episode switch that lands on ExoPlayer only stopped it; the switch keeps the player, nt12).
    outgoing?.let { runCatching { it.releasePlayer() } }

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (mpvInitializationInProgress) return

    runCatching {
        // Before setMedia, so a throw there fails over at this start's position.
        val startPositionMs = _uiState.value.pendingSeekPosition ?: pendingResumeProgress?.position ?: 0L
        mpvStartPositionMs = startPositionMs
        performPendingMpvHardRestartIfNeeded(view)
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.setMedia(currentStreamUrl, currentHeaders)
        applyMpvSessionSettingsAndStart(view, startPositionMs = startPositionMs)
        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        scheduleHideControls()
        // Last, so a throw above (handled below) never leaves it armed.
        scheduleMpvStartupWatchdog(view)
    }.onFailure {
        val detailedError = it.message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_surface_failed)
        // An engine fault: the Exo failover or the error box, never the next source.
        handleMpvStartupFailure(engineFailure(detailedError), sourceFailover = false)
    }
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true
) {
    // Shared Exo teardown: closes the audio-effect session while _exoPlayer is
    // still set (F14 ordering), releases the media session, stops and releases.
    disposeExoPlayerBeforeRebuild()
    trackSelector = null
    currentMediaSession = null

    val view = mpvView
    if (view == null) {
        setLoadingStatus(
            phase = "mpv_waiting_surface",
            message = context.getString(com.nuvio.tv.R.string.player_loading_building),
            showOverlay = true
        )
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        setLoadingStatus(
            phase = "mpv_starting",
            message = context.getString(com.nuvio.tv.R.string.player_loading_starting),
            showOverlay = true
        )
        // Spec 85 F2: every mpv start loads on a fresh core, so the view's per-core state (load
        // failure, playback started, a core stopPlayback ended) never carries over. The HTTP episode
        // switch keeps the player (nt12, an Exo gain) and would otherwise reuse a live core. This
        // release also serves a pending manual-switch hard restart.
        pendingMpvHardRestartOnNextAttach = false
        view.releasePlayer()
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        val initialResumePosition = resolvePendingInitialResumePosition()
            .takeIf { it > 0L }
            ?: (_uiState.value.pendingSeekPosition?.coerceAtLeast(0L) ?: 0L)
        mpvStartPositionMs = initialResumePosition
        view.setMedia(url, headers, initialResumePosition)
        Log.i(
            PlayerRuntimeController.TAG,
            "PLAYER_INIT: engine=MPV host=${url.safeHost()} " +
                "playbackSpeed=${_uiState.value.playbackSpeed} resumePositionMs=$initialResumePosition"
        )
        if (initialResumePosition > 0L) {
            clearPendingInitialResumePosition()
            _uiState.update { it.copy(pendingSeekPosition = null) }
            updatePlaybackTimeline(currentPosition = initialResumePosition)
        }
        applyMpvSessionSettingsAndStart(view, startPositionMs = initialResumePosition)

        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1,
                chapters = emptyList(),
                showChaptersPanel = false
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        scheduleHideControls()
        // Last, so a throw above (handled below) never leaves it armed.
        scheduleMpvStartupWatchdog(view)
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libmpv initialize failed: ${error.message}", error)
        val detailedError = error.message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed)
        // An engine fault: the Exo failover or the error box, never the next source.
        handleMpvStartupFailure(
            engineFailure(detailedError),
            sourceFailover = false,
            allowEngineFailover = allowEngineFailover
        )
    }
}

/**
 * The per-session settings both mpv start paths apply after setMedia (speed, amplification,
 * audio/subtitle language and style, delays, BT route, aspect), then start (or stay paused) and
 * run any pending resume seek. One home, so a setting cannot be missed on one path.
 */
private fun PlayerRuntimeController.applyMpvSessionSettingsAndStart(
    view: NuvioMpvSurfaceView,
    startPositionMs: Long
) {
    view.setPlaybackSpeed(_uiState.value.playbackSpeed)
    view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
    view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
    view.applySubtitleLanguagePreferences(
        preferred = _uiState.value.subtitleStyle.preferredLanguage,
        secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
    )
    view.applySubtitleStyle(_uiState.value.effectiveSubtitleStyle)
    view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
    view.applyBluetoothAudioRoute(currentAudioOutputRoute?.isBluetooth == true)
    view.setAudioDelayMs(_uiState.value.audioDelayMs)
    view.applyAspectMode(_uiState.value.aspectMode)
    // initializePlayer(startPaused = true) sets userPausedManually; Exo honours it at READY and
    // first frame, mpv here (window NOTE N7: it always unpaused, also on paused rebuilds).
    view.setPaused(PlayerStartupPlaybackPolicy.mpvStartsPaused(userPausedManually, startPositionMs))
    applyPendingMpvSeekIfNeeded(view)
}

internal fun PlayerRuntimeController.releaseMpvPlayer() {
    runCatching { mpvView?.releasePlayer() }
}

internal fun PlayerRuntimeController.pauseForLifecycle() {
    // Mark we're in background so onPlayerError can defer recovery to onResume.
    isInBackground = true

    // Release the MediaSession so the system doesn't route media commands
    // (play/pause, audio focus) to this player while the app is in the background.
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Mark as user-paused so autoplay logic doesn't resume playback.
    userPausedManually = true
    shouldEnforceAutoplayOnFirstReady = false

    if (isUsingMpvEngine()) {
        mpvView?.setPaused(true)
        stopWatchProgressSaving()
        stopProgressUpdates()
        _uiState.update { it.copy(isPlaying = false) }
        return
    }
    _exoPlayer?.let { player ->
        // Disable automatic audio focus handling so ExoPlayer can't
        // re-acquire focus and set playWhenReady=true behind our back.
        player.setAudioAttributes(player.audioAttributes, false)
        player.playWhenReady = false
        player.pause()
    }
}

internal fun PlayerRuntimeController.resumeForLifecycle() {
    isInBackground = false

    // Spec 85 G9: a load failure mpv reported in the background, when the progress job was stopped.
    if (isUsingMpvEngine() && !hasRenderedFirstFrame) {
        val loadFailure = mpvView?.takeLoadFailure()
        if (loadFailure != null && handleMpvLoadFailure(loadFailure)) return
    }

    // If the codec crashed while in background, the player was released to free
    // resources. Rebuild it now with the saved position so the user comes back
    // to a clean, paused player ready to play.
    if (pendingBackgroundCrashRecovery) {
        pendingBackgroundCrashRecovery = false
        val savedPosition = backgroundCrashSavedPositionMs
        backgroundCrashSavedPositionMs = 0L
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        if (currentStreamUrl.isNotEmpty()) {
            initializePlayer(
                currentStreamUrl,
                currentHeaders,
                overrideInternalPlayerEngine = currentStreamEngineForRestart(),
                startPaused = true
            )
        }
        return
    }

    val player = _exoPlayer
    if (player != null && !isUsingMpvEngine()) {
        // Spec 85 G2: a track trigger the background held back (the hop rebuilds the player).
        if (maybeHopToMpvHeldInBackground()) return
        // Restore automatic audio focus handling that was disabled in pauseForLifecycle().
        player.setAudioAttributes(player.audioAttributes, true)

        // Re-create the MediaSession so media controls work in the foreground.
        if (currentMediaSession == null) {
            try {
                currentMediaSession = androidx.media3.session.MediaSession.Builder(context, player).build()
                updateMediaSessionMetadata()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // T14: a receiver add while backgrounded was skipped (the check bails in background);
        // look again now (idempotent; paused state is kept by the rebuild).
        schedulePassthroughReturnCheck()
    }
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!isUsingMpvEngine()) return
    if (mpvTrackRefreshInProgress) return
    val view = mpvView ?: return
    val streamUrlAtRefresh = currentStreamUrl
    mpvTrackRefreshInProgress = true
    mpvTrackRefreshJob = scope.launch {
        try {
            val snapshot = view.readTrackSnapshot()
            if (!isUsingMpvEngine() || mpvView !== view || currentStreamUrl != streamUrlAtRefresh) return@launch
            applyMpvTrackSnapshot(snapshot)
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "Failed to refresh MPV track snapshot: ${error.message}")
        } finally {
            mpvTrackRefreshInProgress = false
        }
    }
}

private fun PlayerRuntimeController.applyMpvTrackSnapshot(snapshot: MpvTrackSnapshot) {
    if (snapshot.audioTracks.isNotEmpty() || snapshot.subtitleTracks.isNotEmpty()) {
        stepClock.tracksRead(android.os.SystemClock.elapsedRealtime())
    }
    val audioTracks = snapshot.audioTracks
        .mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }.joinToString(" ")
            val displayName = if (codecSuffix.isBlank()) {
                track.name
            } else {
                "${track.name} ($codecSuffix)"
            }
            TrackInfo(
                index = index,
                name = displayName,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                channelCount = track.channelCount,
                isSelected = track.isSelected
            )
        }

    val internalSubtitleTracks = snapshot.subtitleTracks
        .filterNot { it.isExternal }
        .mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                isForced = track.isForced,
                isSelected = track.isSelected
            )
        }

    val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = internalSubtitleTracks.indexOfFirst { it.isSelected }
    val selectedExternalSubtitleTrack = snapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }
    val selectedExternalSubtitle = selectedExternalSubtitleTrack != null

    if (internalSubtitleTracks.isNotEmpty() || hasRenderedFirstFrame) {
        hasScannedTextTracksOnce = true
    }
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)

    _uiState.update { state ->
        val selectedAddonFromMpvTrack = selectedExternalSubtitleTrack?.let { track ->
            state.addonSubtitles.firstOrNull { subtitle ->
                buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
            }
        }

        val addonSelection = when {
            selectedAddonFromMpvTrack != null -> selectedAddonFromMpvTrack
            selectedExternalSubtitle -> null
            selectedSubtitleIndex >= 0 -> null
            else -> state.selectedAddonSubtitle
        }
        val normalizedSelectedSubtitleIndex = if (selectedExternalSubtitle) {
            -1
        } else {
            selectedSubtitleIndex
        }

        if (
            state.audioTracks == audioTracks &&
            state.subtitleTracks == internalSubtitleTracks &&
            state.selectedAudioTrackIndex == selectedAudioIndex &&
            state.selectedSubtitleTrackIndex == normalizedSelectedSubtitleIndex &&
            state.selectedAddonSubtitle == addonSelection &&
            state.chapters == snapshot.chapters
        ) {
            state
        } else {
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = internalSubtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = normalizedSelectedSubtitleIndex,
                selectedAddonSubtitle = addonSelection,
                chapters = snapshot.chapters
            )
        }
    }
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = internalSubtitleTracks
    )
    applyLosslessAudioDefaultIfUnset(audioTracks)
}

private fun PlayerRuntimeController.performPendingMpvHardRestartIfNeeded(view: NuvioMpvSurfaceView): Boolean {
    if (!pendingMpvHardRestartOnNextAttach) return false
    pendingMpvHardRestartOnNextAttach = false
    runCatching {
        Log.d(PlayerRuntimeController.TAG, "Applying MPV hard restart for manual engine switch")
        view.releasePlayer()
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "MPV hard restart release failed: ${it.message}")
    }
    return true
}

internal fun PlayerRuntimeController.applyPendingMpvSeekIfNeeded(
    view: NuvioMpvSurfaceView,
    currentPositionMs: Long = view.currentPositionMs().coerceAtLeast(0L),
    durationMs: Long = view.durationMs().coerceAtLeast(0L)
) {
    if (!isUsingMpvEngine()) return
    if (delayMpvResumeSeekUntilVideoTrack) {
        if (!view.hasVideoTrackSelectedNow()) return
        delayMpvResumeSeekUntilVideoTrack = false
    }

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val queuedPosition = state.pendingSeekPosition ?: savedResume?.position
    if (queuedPosition == null) return
    if (queuedPosition <= 0L && savedResume == null) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            val requiresDurationForPercentResume = savedResume.progressPercent != null &&
                savedResume.duration <= 0L
            if (requiresDurationForPercentResume) {
                return
            }
            savedResume.position.coerceAtLeast(0L)
        }
        else -> queuedPosition.coerceAtLeast(0L)
    }

    if (target <= 0L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val isAlreadyAtTarget = currentPositionMs >= target ||
        abs(currentPositionMs - target) <= MPV_RESUME_SEEK_TOLERANCE_MS
    if (isAlreadyAtTarget) {
        if (state.pendingSeekPosition != null || savedResume != null) {
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    view.seekToMs(target)
    view.setSubtitleDelayMs(state.subtitleDelayMs)
    view.setAudioDelayMs(state.audioDelayMs)
    if (state.pendingSeekPosition != target) {
        _uiState.update { it.copy(pendingSeekPosition = target) }
    }
}

internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
}

internal fun PlayerRuntimeController.currentPlaybackPositionMs(): Long? {
    return if (isUsingMpvEngine()) {
        mpvView?.currentPositionMs()
    } else {
        _exoPlayer?.let(::exoCarryPositionMs)
    }
}

/**
 * The Exo playhead as the app means it: the resume target while the decoder primes from 0 (Spec 57 D2), else the
 * player's position. Read by currentPlaybackPositionMs (timeline, switch seeds, saves) and the rebuild carries; an
 * in-place reload of the same player keeps the raw position (0 during the prime), so the prime frame still comes.
 */
internal fun PlayerRuntimeController.exoCarryPositionMs(player: androidx.media3.common.Player): Long =
    startPrimeTargetMs ?: player.currentPosition

internal fun PlayerRuntimeController.currentPlaybackDurationMs(): Long {
    return if (isUsingMpvEngine()) {
        mpvView?.durationMs() ?: 0L
    } else {
        _exoPlayer?.duration ?: 0L
    }
}

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

/**
 * Play intent survives transient states: ExoPlayer reports isPlaying=false while buffering,
 * but pausing on a route change then would strand playback stopped once buffering ends.
 */
internal fun PlayerRuntimeController.hasActivePlayIntent(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.playWhenReady == true
    }
}

internal fun PlayerRuntimeController.seekPlaybackTo(
    positionMs: Long,
    seekParameters: SeekParameters = SeekParameters.CLOSEST_SYNC
) {
    if (isUsingMpvEngine()) {
        mpvView?.let { view ->
            view.seekToMs(positionMs)
            // Keep subtitle/audio delay sticky during FF/RW seeks.
            view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
            view.setAudioDelayMs(_uiState.value.audioDelayMs)
        }
    } else {
        // Spec 57 D2: a seek before the prime frame moves the target instead of entering the cold codec mid-file.
        if (startPrimeTargetMs != null) {
            startPrimeTargetMs = positionMs.coerceAtLeast(0L)
            return
        }
        _exoPlayer?.let { player ->
            if (NuvioExoPlayerPerformanceHelper.enabled) {
                val currentPos = player.currentPosition
                val isForwardSeek = positionMs >= currentPos
                val inBuffer = isForwardSeek && NuvioExoPlayerPerformanceHelper.isSeekInBuffer(player, positionMs)
                if (inBuffer) {
                    suppressBufferingUiForSeek = true
                    scheduleSeekSuppressTimeout()
                } else {
                    // Out of buffer or backward seek: show spinner immediately
                    seekBufferingUiDeferred = false
                    suppressBufferingUiForSeek = false
                    seekBufferingUiJob?.cancel()
                    _uiState.update { it.copy(isBuffering = true) }
                }
                // Scrubbing-mode plumbing removed (seek review F4): the params it
                // built were byte-identical to the defaults and
                // setScrubbingModeEnabled(true) was never called anywhere, so the
                // engine ignored them entirely - dead code that misled readers
                // into thinking a fast-scrub path existed.
            }
            player.setSeekParameters(seekParameters)
            player.seekTo(positionMs)
            // Seek review F1: SeekParameters is persistent player state, and this
            // is the only place it was ever set - never restored. Five raw
            // player.seekTo() sites (audio-track-change nudge, long-pause resume,
            // error recovery, resume progress, stall watchdog) inherited whatever
            // direction the user last seeked: a stale NEXT_SYNC turned the
            // "seek back 1ms to unstick buffering" nudge into a forward jump of
            // up to a full GOP on every audio-track change. Set/seek/reset
            // messages are processed in order on the playback thread, so the
            // requested seek still uses [seekParameters].
            player.setSeekParameters(SeekParameters.DEFAULT)
        }
    }
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    if (isUsingMpvEngine()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    if (isUsingMpvEngine()) {
        mpvView?.setPaused(paused)
        _uiState.update { it.copy(isPlaying = !paused) }
    } else {
        _exoPlayer?.let { player ->
            if (paused) player.pause() else player.play()
        }
    }
}

internal fun PlayerRuntimeController.pauseForStillWatchingPrompt() {
    setPlaybackPaused(true)
    if (isUsingMpvEngine()) {
        stopProgressUpdates()
        stopWatchProgressSaving()
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !isUsingMpvEngine()) return
    scope.launch {
        // If track switch forces a pause, nudge playback back only when needed.
        repeat(6) {
            if (!isUsingMpvEngine()) return@launch
            val view = mpvView ?: return@launch
            val pausedByCache = view.isPausedForCacheNow()
            val coreIdle = view.isCoreIdleNow()
            if (view.isPlayingNow() && !pausedByCache && !coreIdle) {
                _uiState.update { state ->
                    if (state.isPlaying) state else state.copy(isPlaying = true, isBuffering = false)
                }
                return@launch
            }
            view.setPaused(false)
            _uiState.update { it.copy(isPlaying = true, isBuffering = false) }
            delay(120L)
        }
    }
}

/**
 * After an in-buffer seek, automatically clear the buffering-UI suppression
 * flag after a short timeout so normal buffering states resume if the seek
 * takes longer than expected. Re-arming replaces the previous timer so an
 * earlier seek's timer cannot clear a later seek's suppression early.
 */
internal fun PlayerRuntimeController.scheduleSeekSuppressTimeout() {
    seekSuppressTimeoutJob?.cancel()
    seekSuppressTimeoutJob = scope.launch {
        delay(NuvioExoPlayerPerformanceHelper.SEEK_SUPPRESS_TIMEOUT_MS)
        suppressBufferingUiForSeek = false
    }
}
