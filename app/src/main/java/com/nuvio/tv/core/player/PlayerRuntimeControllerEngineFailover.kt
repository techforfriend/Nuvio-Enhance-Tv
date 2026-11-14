package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.nuvio.tv.R
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (!allowEngineFailover) return false
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (startupEngineFailoverTriggered) return false
    // Spec 85 G1: one automatic switch per URL per stream, either direction (D3's loop guard).
    if (currentStreamUrl in engineHopStreamUrls) return false
    if (!isStartupPhaseForEngineFailover()) return false

    // Task 1.6 (Option A), narrowed by Spec 85 D1: an active MPV session (manual switch, the MPV/AUTO
    // engine settings, or a start-on-mpv name, Spec 85 G8) may fail back to ExoPlayer (when ExoPlayer
    // cannot open it either, next-source failover takes over). ExoPlayer goes to MPV only for a file
    // it cannot play at all (hopToMpvForFormat: track, video-decoder and container triggers, never
    // Dolby Vision); every other ExoPlayer startup failure stays with the retry ladders and
    // next-source failover, since MPV loses this fork's passthrough/DV pipeline.
    val mpvActive = currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER ||
        (currentInternalPlayerEngine == InternalPlayerEngine.AUTO && mpvView != null)
    if (!mpvActive) return false
    engineHopStreamUrls.add(currentStreamUrl)
    val targetEngine = InternalPlayerEngine.EXOPLAYER
    rememberCurrentTrackPreferenceForEngineSwitch()
    Log.w(PlayerRuntimeController.TAG, "Startup playback error; auto-switching engine: $detailedError")
    startupEngineFailoverTriggered = true

    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_message, targetEngineLabel)

    hidePlayerEngineSwitchInfoJob?.cancel()
    showRecoveryOverlay()
    _uiState.update {
        it.copy(
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage
        )
    }

    // Failover always lands on ExoPlayer, so no MPV hard restart is pending
    // (releasePlayer clears delayMpvResumeSeekUntilVideoTrack).
    pendingMpvHardRestartOnNextAttach = false
    rebuildCurrentStream(
        "startup engine failover",
        // No frame came, so the start target is the carry (Spec 85: mpv was handed it and the
        // pending resume cleared).
        positionMs = mpvStartPositionMs,
        // A paused manual switch to mpv that fails over lands on Exo still paused (N7).
        paused = userPausedManually,
        engine = targetEngine,
        allowEngineFailover = false
    )
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }

    return true
}

/** Spec 85 G2: whether this ExoPlayer start may still hop to mpv ([MpvEngineHopPolicy.hopAllowed]). */
internal fun PlayerRuntimeController.mpvFormatHopAvailable(): Boolean = MpvEngineHopPolicy.hopAllowed(mpvHopGateInput())

private fun PlayerRuntimeController.mpvHopGateInput() = MpvEngineHopPolicy.GateInput(
    exoActive = currentInternalPlayerEngine == InternalPlayerEngine.EXOPLAYER && _exoPlayer != null,
    firstFrameRendered = hasRenderedFirstFrame,
    inBackground = isInBackground,
    switchUsedForUrl = currentStreamUrl in engineHopStreamUrls,
    videoMime = currentVideoTrackMimeType,
    videoCodecs = currentVideoTrackCodecs,
    dvSourceProfile = DolbyVisionConversionStats.getLastSourceProfile()
)

/**
 * Spec 85 E1/E2/E6/E8: the track triggers ([MpvEngineHopPolicy.reasonForTracks]) on an ExoPlayer
 * tracks update; true when the start hopped to mpv (the caller stops reading these tracks: the
 * player is released). In the background a trigger is only held for resume
 * ([maybeHopToMpvHeldInBackground]); the rest of the update runs as usual.
 */
internal fun PlayerRuntimeController.maybeHopToMpvForTracks(tracks: Tracks): Boolean {
    // The gate first, so a closed one never reads the tracks.
    if (!MpvEngineHopPolicy.hopAllowed(mpvHopGateInput().copy(inBackground = false))) return false
    val reason = MpvEngineHopPolicy.reasonForTracks(
        video = tracks.hopFacts(C.TRACK_TYPE_VIDEO),
        audio = tracks.hopFacts(C.TRACK_TYPE_AUDIO),
        adaptive = _exoPlayer?.currentManifest != null
    ) ?: return false
    // Held in the background: the player stays, so the caller reads the rest of the update.
    return maybeHopToMpv(reason.name) && !isInBackground
}

/**
 * Spec 85 G2, on resume: the track trigger the background held back. Its first-frame term is
 * waived: a paused ExoPlayer with a live surface (ON_PAUSE) renders its first frame, but playback
 * never ran. The other gate terms still apply. True when it hopped.
 */
internal fun PlayerRuntimeController.maybeHopToMpvHeldInBackground(): Boolean {
    val reason = backgroundHeldMpvHopReason ?: return false
    backgroundHeldMpvHopReason = null
    if (!MpvEngineHopPolicy.hopAllowed(mpvHopGateInput().copy(firstFrameRendered = false))) return false
    hopToMpvForFormat(reason)
    return true
}

/**
 * Spec 85 G2/G6/G7: the hop for a trigger met outside the error ladder's snapshot (a tracks
 * update, the parsing probe's tail, the first-frame watchdog), gated now. In the background the
 * reason is held for resume ([maybeHopToMpvHeldInBackground]). True when it hopped or is held;
 * false leaves the caller's own fallback to run.
 */
internal fun PlayerRuntimeController.maybeHopToMpv(reason: String): Boolean {
    val gate = mpvHopGateInput()
    if (!MpvEngineHopPolicy.hopAllowed(gate.copy(inBackground = false))) return false
    if (gate.inBackground) {
        backgroundHeldMpvHopReason = reason
        return true
    }
    hopToMpvForFormat(reason)
    return true
}

/**
 * Spec 85 G3: restart this ExoPlayer start on mpv at its start position, once per URL (G1). No
 * engine-switch notice (no new visible element without a mockup). Synchronous, as the mpv->Exo
 * failover is: the URL is marked in the same step that rebuilds, so no other rebuild can slip in
 * between and leave a marked URL on ExoPlayer (releasing the player from its own onTracksChanged
 * has precedent in the libass switch, a Main.immediate launch that runs inline). Keep it off a
 * launch or yield.
 *
 * AFR: the frame rate ExoPlayer's tracks reported is dropped, so mpv's own preflight detects and
 * switches the display and holds its start for the settle (it skips when a rate is already known);
 * ExoPlayer's track-AFR coroutine stands down on the new stream generation.
 *
 * Track restore: on the first tracks update ExoPlayer has applied no restore yet, so nothing is
 * carried and mpv restores this URL's pending carry or the title's saved choice as if the stream
 * had started there (a carry of ExoPlayer's default pick would outrank the saved choice). Once an
 * update has run the restore (a trigger the background held back), the effective selections are
 * carried instead, since the restore consumed the saved choice.
 */
internal fun PlayerRuntimeController.hopToMpvForFormat(reason: String) {
    engineHopStreamUrls.add(currentStreamUrl)
    val positionMs = _exoPlayer?.let(::exoCarryPositionMs) ?: 0L
    if (hasScannedTextTracksOnce) seedTrackPreferenceCarryForPlayerRebuild()
    _uiState.update { it.copy(detectedFrameRate = 0f, detectedFrameRateSource = null) }
    Log.w(
        PlayerRuntimeController.TAG,
        "FORMAT_HOP reason=$reason mime=${currentVideoTrackMimeType ?: "none"} " +
            "codecs=${currentVideoTrackCodecs ?: "none"} host=${currentStreamUrl.safeHost()} positionMs=$positionMs"
    )
    rebuildCurrentStream(
        "format hop $reason",
        positionMs = positionMs,
        paused = rebuildStartsPaused(),
        engine = InternalPlayerEngine.MVP_PLAYER
    )
}

private fun Tracks.hopFacts(trackType: Int): List<MpvEngineHopPolicy.TrackFact> =
    groups.filter { it.type == trackType }.flatMap { group ->
        (0 until group.length).map { i ->
            val format = group.getTrackFormat(i)
            MpvEngineHopPolicy.TrackFact(
                mime = format.sampleMimeType,
                codecs = format.codecs,
                support = group.getTrackSupport(i),
                selected = group.isTrackSelected(i)
            )
        }
    }

/**
 * The engine a manual switch goes to: the other one; under AUTO, ExoPlayer when an mpv view is attached,
 * else mpv. One home for the More tray's Switch engine and the error box's Play with (Spec 86 R3).
 */
internal fun manualEngineSwitchTarget(current: InternalPlayerEngine, mpvViewAttached: Boolean): InternalPlayerEngine =
    when (current) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
        InternalPlayerEngine.AUTO -> if (mpvViewAttached) InternalPlayerEngine.EXOPLAYER else InternalPlayerEngine.MVP_PLAYER
    }

internal fun PlayerRuntimeController.manualEngineSwitchTarget(): InternalPlayerEngine =
    manualEngineSwitchTarget(currentInternalPlayerEngine, mpvViewAttached = mpvView != null)

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    if (currentStreamUrl.isBlank()) return

    val targetEngine = manualEngineSwitchTarget()
    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_manual_message, targetEngineLabel)

    rememberCurrentTrackPreferenceForEngineSwitch()

    // Window NOTE N7 (2026-10-09): a switch made while paused stays paused (mpv's position-0
    // exception lives in PlayerStartupPlaybackPolicy.mpvStartsPaused).
    val startPaused = userPausedManually

    seedSourceSwitchResumeFromLivePosition()

    startupEngineFailoverTriggered = false
    // Spec 85 G1: a manual switch to mpv re-arms its URL's one automatic switch (mpv may still fail
    // back to ExoPlayer), as it re-arms the flag above; a manual switch to ExoPlayer uses it up, so
    // the format hop never undoes the user's choice.
    if (targetEngine == InternalPlayerEngine.MVP_PLAYER) {
        engineHopStreamUrls.remove(currentStreamUrl)
    } else {
        engineHopStreamUrls.add(currentStreamUrl)
    }
    userPausedManually = false
    resetErrorRetryState()
    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showControls = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleDelayOverlay = false,
            showSpeedPanel = false,
            showChaptersPanel = false,
            showMoreDialog = false,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage,
            // A degraded notice describes the engine being left.
            degradedNotice = null
        )
    }

    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = switchingToMpv
    delayMpvResumeSeekUntilVideoTrack = switchingToMpv

    // Save progress, but keep the TorrServer torrent: the new engine re-opens
    // the same URL (flushPlaybackState=true would stopTorrentStream()).
    flushPlaybackSnapshotForSwitchOrExit()
    rebuildCurrentStream("engine switch", positionMs = 0L, paused = startPaused, engine = targetEngine)

    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }
}

private fun PlayerRuntimeController.isStartupPhaseForEngineFailover(): Boolean {
    val state = _uiState.value
    val currentPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: playbackTimeline.value.currentPosition
    return !hasRenderedFirstFrame && (state.showLoadingOverlay || state.isBuffering || currentPosition <= 0L)
}

private fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String {
    return when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
        InternalPlayerEngine.AUTO -> context.getString(R.string.playback_player_auto)
    }
}

internal fun PlayerRuntimeController.clearPendingEngineSwitchTrackPreference() {
    trackSelection.pendingEngineSwitchTrackPreference = null
}

private fun PlayerRuntimeController.rememberCurrentTrackPreferenceForEngineSwitch() {
    val state = _uiState.value
    val sourceEngine = currentInternalPlayerEngine

    val rememberedAudio = captureCurrentAudioSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    val rememberedSubtitle = resolveCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )

    val mergedPreference = PlayerRuntimeController.TrackPreference(
        audio = rememberedAudio,
        subtitle = rememberedSubtitle
    )
    val capturedPreference = mergedPreference.takeUnless { it.audio == null && it.subtitle == null }
    trackSelection.engineSwitchCaptured(
        capturedPreference?.let { preference ->
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = currentStreamUrl,
                preference = preference,
                sourceEngine = sourceEngine
            )
        }
    )
}

private fun PlayerRuntimeController.captureCurrentAudioSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedTrackSelection? {
    val mpvSelected = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        mpvView?.readTrackSnapshot()?.audioTracks?.firstOrNull { it.isSelected }
    } else {
        null
    }
    if (mpvSelected != null) {
        return PlayerRuntimeController.RememberedTrackSelection(
            language = mpvSelected.language,
            name = mpvSelected.name,
            trackId = mpvSelected.id.toString()
        )
    }

    val exoSelected = run {
        val player = _exoPlayer ?: return@run null
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                return@run PlayerRuntimeController.RememberedTrackSelection(
                    language = format.language,
                    name = format.label ?: format.language ?: "Audio",
                    trackId = format.id
                )
            }
        }
        null
    }
    if (exoSelected != null) {
        return exoSelected
    }

    val uiTrack = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }

    return uiTrack?.let { track ->
        PlayerRuntimeController.RememberedTrackSelection(
            language = track.language,
            name = track.name,
            trackId = track.trackId
        )
    }
}

private fun PlayerRuntimeController.captureCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    data class SelectedExoTextTrack(
        val id: String?,
        val language: String?,
        val label: String?,
        val isForced: Boolean,
        val indexHint: Int?,
        val languageIndexHint: Int?
    )

    val mpvSnapshot = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) mpvView?.readTrackSnapshot() else null
    if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        val mpvSelectedSubtitle = mpvSnapshot?.subtitleTracks?.firstOrNull { it.isSelected }
        if (mpvSelectedSubtitle != null) {
            if (mpvSelectedSubtitle.isExternal) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = mpvSelectedSubtitle.name,
                    language = mpvSelectedSubtitle.language
                )
                if (addon != null) {
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = mpvSelectedSubtitle.language,
                    name = mpvSelectedSubtitle.name,
                    trackId = mpvSelectedSubtitle.id.toString(),
                    isForced = mpvSelectedSubtitle.isForced,
                    selectedUiTrackOverride = resolveUiSubtitleTrackForEngineSwitchHints(
                        state = state,
                        trackId = mpvSelectedSubtitle.id.toString(),
                        language = mpvSelectedSubtitle.language,
                        name = mpvSelectedSubtitle.name
                    ),
                    allowUiStateFallbackForHints = false
                )
            )
        }
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val exoPlayer = _exoPlayer
        val exoTextTrackDisabled =
            exoPlayer?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        val exoSelectedText = run {
            val player = exoPlayer ?: return@run null
            val internalTracks = mutableListOf<SelectedExoTextTrack>()
            var selectedTrack: SelectedExoTextTrack? = null
            player.currentTracks.groups.forEach { group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val trackId = format.id
                    val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val isAddonTrack =
                        trackId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true
                    val currentTrack = SelectedExoTextTrack(
                        id = trackId,
                        language = format.language,
                        label = format.label,
                        isForced = isForced,
                        indexHint = if (isAddonTrack) null else internalTracks.size,
                        languageIndexHint = null
                    )
                    if (!isAddonTrack) {
                        internalTracks += currentTrack
                    }
                    if (group.isTrackSelected(i)) {
                        selectedTrack = currentTrack
                    }
                }
            }
            val selected = selectedTrack ?: run {
                val preferredTargets = subtitleLanguageTargets()
                internalTracks.firstOrNull { track ->
                    preferredTargets.any { target ->
                        val normalizedTarget = target.trim().lowercase()
                        if (normalizedTarget == "forced") {
                            track.isForced
                        } else {
                            PlayerSubtitleUtils.matchesLanguageCode(track.language, target)
                        }
                    }
                } ?: internalTracks.firstOrNull { !it.isForced }
                    ?: internalTracks.firstOrNull()
            } ?: return@run null
            val selectedIndex = selected.indexHint ?: return@run selected
            val selectedInternalTrack = internalTracks.getOrNull(selectedIndex) ?: return@run selected
            val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                language = selectedInternalTrack.language,
                name = selectedInternalTrack.label ?: selectedInternalTrack.language,
                trackId = selectedInternalTrack.id
            )
            val languageCandidates = internalTracks.indices.filter { index ->
                val candidate = internalTracks[index]
                val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = candidate.language,
                    name = candidate.label ?: candidate.language,
                    trackId = candidate.id
                )
                candidateVariant == selectedVariant ||
                    (!selectedInternalTrack.language.isNullOrBlank() &&
                        PlayerSubtitleUtils.matchesLanguageCode(
                            candidate.language,
                            selectedInternalTrack.language
                        ))
            }
            selected.copy(
                languageIndexHint = languageCandidates.indexOf(selectedIndex).takeIf { it >= 0 }
            )
        }
        if (exoSelectedText == null && exoTextTrackDisabled) {
            return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        }
        if (exoSelectedText != null) {
            val formatId = exoSelectedText.id
            val formatLanguage = exoSelectedText.language
            val formatLabel = exoSelectedText.label
            if (formatId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = formatId,
                    language = formatLanguage
                )
                if (addon != null) {
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }

            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = PlayerRuntimeController.RememberedTrackSelection(
                    language = formatLanguage,
                    name = formatLabel ?: formatLanguage ?: "Subtitle",
                    trackId = formatId,
                    indexHint = exoSelectedText.indexHint,
                    languageIndexHint = exoSelectedText.languageIndexHint,
                    isForcedHint = exoSelectedText.isForced
                )
            )
        }
    }

    val uiTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
        ?: state.subtitleTracks.firstOrNull { it.isSelected }
    if (uiTrack != null) {
        return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                state = state,
                language = uiTrack.language,
                name = uiTrack.name,
                trackId = uiTrack.trackId,
                isForced = uiTrack.isForced
            )
        )
    }
    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val uiFallbackTrack = pickUiInternalSubtitleTrackForEngineSwitchFallback(state)
        if (uiFallbackTrack != null) {
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = uiFallbackTrack.language,
                    name = uiFallbackTrack.name,
                    trackId = uiFallbackTrack.trackId,
                    isForced = uiFallbackTrack.isForced,
                    selectedUiTrackOverride = uiFallbackTrack
                )
            )
        }
    }

    state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
        return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = selectedAddonFromUi.id,
            url = selectedAddonFromUi.url,
            language = selectedAddonFromUi.lang,
            addonName = selectedAddonFromUi.addonName
        )
    }

    val mpvHasSubtitleTracks = mpvSnapshot?.subtitleTracks?.isNotEmpty() == true
    val exoHasSubtitleTracks = if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        run {
            val player = _exoPlayer ?: return@run false
            player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && group.length > 0
            }
        }
    } else {
        false
    }
    if (mpvHasSubtitleTracks || exoHasSubtitleTracks) {
        return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    }

    return null
}

private fun PlayerRuntimeController.resolveCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    val capturedSelection = captureCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    val shouldUseFallback =
        capturedSelection == null || capturedSelection == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    if (!shouldUseFallback) {
        return capturedSelection
    }

    trackSelection.effectiveSubtitleSelectionForEngineSwitch
        ?.takeIf { effective -> effective.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { effectiveSelection ->
            return effectiveSelection
        }

    trackSelection.explicitSubtitleSelectionForEngineSwitch
        ?.takeIf { explicit -> explicit.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { explicitSelection ->
            return explicitSelection
        }

    trackSelection.rememberedTrackPreference?.subtitle?.let { rememberedSelection ->
        return rememberedSelection
    }

    return capturedSelection
}

private fun PlayerRuntimeController.pickUiInternalSubtitleTrackForEngineSwitchFallback(
    state: PlayerUiState
): TrackInfo? {
    if (state.subtitleTracks.isEmpty()) {
        return null
    }

    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let {
        return it
    }
    state.subtitleTracks.firstOrNull { it.isSelected }?.let {
        return it
    }

    val preferredIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = subtitleLanguageTargets()
    )
    if (preferredIndex >= 0) {
        state.subtitleTracks.getOrNull(preferredIndex)?.let {
            return it
        }
    }

    state.subtitleTracks.firstOrNull { !it.isForced }?.let {
        return it
    }
    return state.subtitleTracks.firstOrNull()
}

internal fun PlayerRuntimeController.buildRememberedInternalSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    language: String?,
    name: String?,
    trackId: String?,
    isForced: Boolean,
    selectedUiTrackOverride: TrackInfo? = null,
    allowUiStateFallbackForHints: Boolean = true
): PlayerRuntimeController.RememberedTrackSelection {
    val selectedUiTrack = selectedUiTrackOverride
        ?: if (allowUiStateFallbackForHints) {
            state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
                ?: state.subtitleTracks.firstOrNull { it.isSelected }
        } else {
            null
        }

    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = language,
        name = name,
        trackId = trackId,
        indexHint = selectedUiTrack?.index?.takeIf { it >= 0 },
        languageIndexHint = selectedUiTrack?.let { track ->
            subtitleLanguageOrdinalHintForEngineSwitch(state.subtitleTracks, track)
        },
        isForcedHint = selectedUiTrack?.isForced ?: isForced
    )
    return selection
}

private fun PlayerRuntimeController.resolveUiSubtitleTrackForEngineSwitchHints(
    state: PlayerUiState,
    trackId: String?,
    language: String?,
    name: String?
): TrackInfo? {
    val normalizedTrackId = normalizeTrackMatchValue(trackId)
    if (!normalizedTrackId.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.trackId) == normalizedTrackId
        }?.let {
            return it
        }
    }

    val normalizedLanguage = normalizeTrackMatchValue(language)
    val normalizedName = normalizeTrackMatchValue(name)

    if (!normalizedLanguage.isNullOrBlank() && !normalizedName.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage &&
                normalizeTrackMatchValue(track.name) == normalizedName
        }?.let {
            return it
        }
    }

    if (!normalizedLanguage.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage && track.isSelected
        }?.let {
            return it
        }
    }

    return null
}

internal fun PlayerRuntimeController.subtitleLanguageOrdinalHintForEngineSwitch(
    tracks: List<TrackInfo>,
    selectedTrack: TrackInfo
): Int? {
    val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = selectedTrack.language,
        name = selectedTrack.name,
        trackId = selectedTrack.trackId
    )
    val selectedTrackPosition = tracks.indexOfFirst { it.index == selectedTrack.index }
    if (selectedTrackPosition < 0) return null

    val candidates = tracks.indices.filter { index ->
        val candidate = tracks[index]
        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = candidate.language,
            name = candidate.name,
            trackId = candidate.trackId
        )
        candidateVariant == selectedVariant ||
            (!selectedTrack.language.isNullOrBlank() &&
                PlayerSubtitleUtils.matchesLanguageCode(candidate.language, selectedTrack.language))
    }

    val ordinal = candidates.indexOf(selectedTrackPosition).takeIf { it >= 0 }
    return ordinal
}

private fun PlayerRuntimeController.findAddonSubtitleByTrackIdOrLanguage(
    state: PlayerUiState,
    trackId: String?,
    language: String?
): com.nuvio.tv.domain.model.Subtitle? {
    val normalizedTrackId = trackId?.trim()
    if (!normalizedTrackId.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            val addonTrackId = buildAddonSubtitleTrackId(subtitle)
            addonTrackId.equals(normalizedTrackId, ignoreCase = true) ||
                normalizedTrackId.contains(addonTrackId, ignoreCase = true)
        }?.let {
            return it
        }
    }
    val lang = language?.trim()
    if (!lang.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, lang)
        }?.let {
            return it
        }
    }
    return null
}
