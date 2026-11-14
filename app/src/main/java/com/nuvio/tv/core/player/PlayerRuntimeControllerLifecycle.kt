package com.nuvio.tv.core.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer(flushPlaybackState: Boolean) {
    isReleasingPlayer = true
    if (flushPlaybackState) {
        // The exit (Go Back, Back, or the screen cleared): before the torrent stops (Spec 75 D6(5)).
        recordBoxEndingFailure()
        stopTorrentStream()
        flushPlaybackSnapshotForSwitchOrExit()
    }

    notifyAudioSessionUpdate(false)
    unregisterAudioDelayRouteCallback()
    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = null
    // T14: a pending AVR-wake check belongs to the released player. Safe when the check itself
    // is the caller: nothing after its releasePlayer() suspends.
    passthroughReturnCheckJob?.cancel()
    passthroughReturnCheckJob = null
    passthroughReturnCheckPending = false

    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    progressJob?.cancel()
    mpvTrackRefreshJob?.cancel()
    mpvTrackRefreshJob = null
    mpvTrackRefreshInProgress = false
    mpvAddonSubtitleSelectJob?.cancel()
    mpvAddonSubtitleSelectJob = null
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    hideStreamSourceIndicatorJob?.cancel()
    hideStreamSourceIndicatorJob = null
    _uiState.update { it.copy(showStreamSourceIndicator = false) }
    hidePlayerEngineSwitchInfoJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    subtitleAutoSyncLoadJob?.cancel()
    stopSidecarAddonSubtitle(clearView = true)
    subtitleTimingRefreshJob?.cancel()
    subtitleTimingRefreshJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    cancelPlayerInit()
    delayMpvResumeSeekUntilVideoTrack = false
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    errorRetryJob?.cancel()
    errorRetryJob = null
    // A deferred rebuild belongs to the released player. Safe when that job is the caller
    // (rebuildCurrentStream): nothing after its releasePlayer() suspends.
    deferredReinitJob?.cancel()
    deferredReinitJob = null
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
    releaseMpvPlayer()
    // Ordering note (main review F14): notifyAudioSessionUpdate(false) above
    // reads _exoPlayer.audioSessionId and MUST run before _exoPlayer is nulled
    // below, or the audio-effect close broadcast silently no-ops and the
    // session leaks. Keep that call ahead of this block in any refactor.
    _exoPlayer?.let { player ->
        // Main review F10/F11: pause()/clearMediaItems() dropped as redundant
        // pre-release round trips (see disposeExoPlayerBeforeRebuild). release()
        // itself must stay on Main — the engine enforces application-thread
        // access — so the Main-thread block is instead capped by
        // PLAYER_RELEASE_TIMEOUT_MS and measured here.
        runCatching { player.playWhenReady = false }
        runCatching { player.stop() }
        runCatching { player.clearVideoSurface() }
        val releaseStartMs = android.os.SystemClock.elapsedRealtime()
        runCatching { player.release() }
        val releaseMs = android.os.SystemClock.elapsedRealtime() - releaseStartMs
        android.util.Log.i(
            PlayerRuntimeController.TAG,
            "PLAYER_RELEASE: site=teardown exoReleaseMs=$releaseMs"
        )
    }
    _exoPlayer = null
    ffmpegAudioRenderer = null
    // Audio review F8: no player, no bypass.
    isAudioOutputBypassing = false
    updateAudioControlAvailability()
    playbackSpeedAwareAudioSink = null
    currentExoPlayerListener = null
    currentExoAnalyticsListener = null
    resetPlaybackTimeline()
    isReleasingPlayer = false
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
