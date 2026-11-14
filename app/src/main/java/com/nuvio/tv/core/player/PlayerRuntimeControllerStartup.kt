package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.util.TtffTrace
import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.toTrackPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true

    persistContentBingeGroup(navigationArgs.bingeGroup)

    val infoHash = navigationArgs.infoHash
    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    val startRequestLine =
        "PLAYER_START_REQUEST: clickElapsedMs=$clickElapsedMs host=${initialStreamUrl.safeHost()} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} infoHash=${infoHash != null} " +
            "startFromBeginning=${navigationArgs.startFromBeginning} streamName=${streamName ?: "n/a"}"
    TtffTrace.mirror(startRequestLine)
    Log.d(
        "PlayerStartup",
        "startInitialPlayback: infoHash=$infoHash host=${currentStreamUrl.safeHost()} " +
            "urlHash=${currentStreamUrl.hashCode().toUInt().toString(16)}"
    )
    val loadSavedProgress = !startFromBeginningPending
    if (infoHash != null && !initialStreamUrl.startsWith("http")) {
        launchTorrentStream(
            infoHash = infoHash,
            fileIdx = navigationArgs.fileIdx,
            filename = navigationArgs.filename,
            trackers = navigationArgs.torrentTrackers,
            loadSavedProgress = loadSavedProgress
        )
        return
    }

    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = loadSavedProgress
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    val playbackUrl = playbackRequest.url
    val playbackHeaders = playbackRequest.headers
    if (playbackUrl != currentStreamUrl || playbackHeaders != currentHeaders) {
        currentStreamUrl = playbackUrl
        currentHeaders = playbackHeaders
        forgetStreamEngineForNewStream()
        _uiState.update { it.copy(currentStreamUrl = playbackUrl) }
    }

    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    Log.i(
        PlayerRuntimeController.TAG,
        "PREPARE_PLAYBACK: clickElapsedMs=$clickElapsedMs host=${playbackUrl.safeHost()} " +
            "loadSavedProgress=$loadSavedProgress currentSeason=${currentSeason ?: -1} " +
            "currentEpisode=${currentEpisode ?: -1} " +
            "streamName=${_uiState.value.currentStreamName?.compactTraceValue() ?: "n/a"}"
    )
    clearPendingEngineSwitchTrackPreference()
    playbackPreparationJob?.cancel()

    // nt7 (task 2): start the saved-progress read NOW, concurrent with
    // everything up to and including player construction, instead of
    // serially between the preparing_metadata and initializing_player
    // phases (historical measurement: ~350 ms on the since-removed
    // Trakt-active path, 347/352 ms on the two plays of the 27 Jul capture,
    // where that branch collected the whole account's progress and
    // filtered client-side; the local read is far cheaper). The join sits in
    // initializePlayer immediately before the resume position is read
    // -- on both engine branches, before setMediaSource/setMedia -- so
    // the STATE_READY race this call's placement originally fixed (see
    // loadSavedProgressSuspend's KDoc) stays closed.
    savedProgressDeferred?.cancel()
    savedProgressDeferred = if (loadSavedProgress) {
        scope.async { loadSavedProgressSuspend(currentSeason, currentEpisode) }
    } else {
        null
    }

    playbackPreparationJob = scope.launch {
        setLoadingStatus(
            phase = "preparing_metadata",
            message = context.getString(com.nuvio.tv.R.string.player_loading_preparing)
        )
        if (trackSelection.persistedTrackPreference == null) {
            contentId?.let { id ->
                val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                Log.d(
                    PlayerRuntimeController.TAG,
                    "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                        "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                )
                trackSelection.preferenceLoaded(id, loaded)
            } ?: Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")
            // Subtitle delay is keyed per-videoId, so it is loaded separately
            // from the track selection above. This happens before
            // initializePlayer() because the renderers factory snapshots
            // subtitleDelayUs from _uiState.value.subtitleDelayMs on build.
            currentVideoId?.takeIf { it.isNotBlank() }?.let { vid ->
                val savedDelayMs = trackPreferenceDataStore.loadSubtitleDelayMs(vid)
                if (savedDelayMs != null && savedDelayMs != 0) {
                    subtitleDelayUs.set(savedDelayMs.toLong() * 1000L)
                    _uiState.update { it.copy(subtitleDelayMs = savedDelayMs) }
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "TRACK_PREF load: restored subtitleDelayMs=$savedDelayMs for videoId=$vid"
                    )
                }
            }
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                    "audio=${trackSelection.persistedTrackPreference?.audio?.language}/${trackSelection.persistedTrackPreference?.audio?.name} " +
                    "subtitle=${trackSelection.persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
            )
        }
        // Saved watch progress no longer loads here: since nt7 it runs
        // concurrently (savedProgressDeferred above) and initializePlayer joins
        // it before reading the resume position.
        if (loadSavedProgress) {
            // The phase marker is kept so capture timelines stay comparable --
            // it should now read ~0 ms.
            recordLoadingDiagnosticEvent(
                phase = "loading_saved_progress",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing)
            )
        }
        recordLoadingDiagnosticEvent(
            phase = "initializing_player",
            message = context.getString(com.nuvio.tv.R.string.player_loading_building)
        )
        initializePlayer(playbackUrl, playbackHeaders)
    }
}
