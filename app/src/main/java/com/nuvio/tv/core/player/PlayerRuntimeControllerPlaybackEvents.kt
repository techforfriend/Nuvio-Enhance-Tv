package com.nuvio.tv.core.player

import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.core.torrent.formatTorrentSpeed
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import com.nuvio.tv.core.player.PlaceholderStreamPolicy.isShortPlaceholderDuration
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import kotlinx.coroutines.launch

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val CENTER_MIX_LEVEL_MIN_DB = -10
internal const val CENTER_MIX_LEVEL_MAX_DB = 30
internal const val AUDIO_DELAY_MIN_MS = -3000
internal const val AUDIO_DELAY_MAX_MS = 3000
internal const val AUDIO_DELAY_STEP_MS = 25
// Held +/- (upstream NM 45d5c83ab/43b981241): the step grows to this once held this long.
internal const val AUDIO_DELAY_HOLD_FAST_STEP_MS = 100
internal const val AUDIO_DELAY_HOLD_FAST_AFTER_MS = 2_000L
internal const val WATCH_PROGRESS_SAVE_INTERVAL_MS = 90_000L

// nt15: after this long with no storm-recovery seek, the per-playback recovery
// attempt budget resets, so a distinct later storm cluster gets a fresh 2 attempts
// instead of being starved by a cap the earlier cold-start cluster already spent.
// Chosen > the D >=3s spacing so a normal 2-attempt cluster cannot self-reset
// mid-cluster; < the gap that separated the run-2 clusters (~10.7s).
internal const val TRUEHD_STORM_ATTEMPT_RESET_MS = 6_000L
// nt11 (0.8.2): lock-snap classifier -- minimum one-tick forward stride
// treated as a snap suspect (10x a normal ~500 ms tick's advance).
internal const val SNAP_SHADOW_MIN_STRIDE_MS = 5_000L

// nt12 (0.8.2): a pending snap-recovery latch older than this is dropped --
// covers the 6 s budget reset plus spacing with margin, while guaranteeing a
// long-blocked recovery can never fire as a surprise rollback much later.
internal const val SNAP_RECOVERY_PENDING_TTL_MS = 15_000L

internal fun PlayerRuntimeController.applyAudioDelay(
    delayMs: Int,
    persistForCurrentRoute: Boolean = true
) {
    val clampedDelayMs = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayUs.set(clampedDelayMs.toLong() * 1000L)
    _uiState.update { it.copy(audioDelayMs = clampedDelayMs) }
    if (isUsingMpvEngine()) {
        mpvView?.setAudioDelayMs(clampedDelayMs)
    }
    if (persistForCurrentRoute) {
        persistAudioDelayForCurrentRoute(clampedDelayMs)
    }
}

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    return skipInterval(_uiState.value.activeSkipInterval ?: return false)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val seekMs = if (interval.endTime == SkipInterval.OPEN_END) {
        duration
    } else {
        (interval.endTime * 1000).toLong()
    }
    seekPlaybackTo(seekMs.coerceAtMost(duration), SeekParameters.NEXT_SYNC)
    scheduleProgressSyncAfterSeek()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    // Audio review F8: gain is a PCM processor — during bitstream bypass it is
    // a silent no-op, so the control reports unavailable instead of offering a
    // dead slider. MPV always decodes, so bypass only applies on ExoPlayer.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val wasActive = gainAudioProcessor.isGainEnabled()
    gainAudioProcessor.setGainDb(if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    val isActiveNow = gainAudioProcessor.isGainEnabled()

    if (wasActive != isActiveNow && !isUsingMpvEngine()) {
        playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
        _exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb)
    }
    _uiState.update {
        it.copy(
            audioAmplificationDb = clampedDb,
            isAudioAmplificationAvailable = isAudioAmplificationAvailable
        )
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    _uiState.update { state ->
        state.copy(centerMixLevelDb = clampedDb)
    }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    // Audio review F8: see applyAudioAmplification.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val isCenterMixAvailable =
        ffmpegAudioRenderer?.isCenterMixActive() == true && (selectedTrack?.channelCount ?: 0) > 2
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(
        if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB
    )
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = isAudioAmplificationAvailable,
            isCenterMixAvailable = isCenterMixAvailable
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }

    // If auto-play is enabled and the user dismissed the card earlier,
    // still auto-play the next episode when playback ends naturally.
    val state = _uiState.value
    if (state.postPlayDismissedForCurrentEpisode &&
        streamAutoPlayNextEpisodeEnabledSetting &&
        state.nextEpisode?.hasAired == true &&
        nextEpisodeVideo != null
    ) {
        playNextEpisode()
        return
    }

    resetPostPlayOverlayState(clearEpisode = false)
}

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

/**
 * Whether an ENDED / near-end event should count as a real episode finish.
 *
 * Debrid cache-sync placeholders and unplayable source responses (e.g. RAR-only
 * torrents, "service unavailable" error clips) often report a short duration and
 * reach STATE_ENDED. Treating those as natural completion marks the episode watched
 * and chains auto-play next through an entire season. Shares
 * [com.nuvio.tv.core.player.PlaceholderStreamPolicy.isShortPlaceholderDuration] with the
 * external-player result path in [com.nuvio.tv.core.player.ExternalPlaybackTracker].
 * [releaseVideo] (Spec 80 D20: a changelog release video, never a debrid answer) ends naturally
 * however short.
 */
internal fun shouldTreatAsNaturalPlaybackCompletion(
    hasRenderedFirstFrame: Boolean,
    hasFatalError: Boolean,
    durationMs: Long,
    releaseVideo: Boolean = false
): Boolean {
    if (hasFatalError) return false
    if (!hasRenderedFirstFrame) return false
    if (!releaseVideo && isShortPlaceholderDuration(durationMs)) return false
    return true
}

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            // nt32: the MAT wrapper engages AFTER isPlaying flips true under AFR
            // (the play-before-configure ordering, third confirmed instance), so the
            // one-shot analytics writers evaluate isMatActive() while it is still
            // false and never re-fire. Lazily fill on the tick instead - ordering-
            // immune, lands within one tick of engagement, and the null guard keeps
            // any earlier writer authoritative.
            if (currentAudioPathDescription == null &&
                matRoutingAudioSink?.isMatActive() == true
            ) {
                currentAudioPathDescription = MAT_AUDIO_PATH_DESCRIPTION
            }
            if (isUsingMpvEngine()) {
                val view = mpvView
                if (view != null) {
                    // Read before the failure slot: mpv posts its playback start before an END_FILE
                    // that follows it, so a failure after an observed start is taken this tick.
                    val playbackStarted = view.hasStartedPlayback()
                    // Spec 85 D3: mpv's own failed load ends the start and this job (the next stream
                    // restarts it).
                    val loadFailure = view.takeLoadFailure()
                    if (loadFailure != null && handleMpvLoadFailure(loadFailure)) return@launch
                    val pos = view.currentPositionMs().coerceAtLeast(0L)
                    val playerDuration = view.durationMs().coerceAtLeast(0L)
                    applyPendingMpvSeekIfNeeded(
                        view = view,
                        currentPositionMs = pos,
                        durationMs = playerDuration
                    )
                    val playingNow = view.isPlayingNow()
                    val cacheBuffering = PlayerStartupPlaybackPolicy.mpvCacheBuffering(
                        pausedForCache = view.isPausedForCacheNow(),
                        playing = playingNow,
                        coreIdle = view.isCoreIdleNow()
                    )
                    var firstFrameReady = hasRenderedFirstFrame
                        if (!firstFrameReady) {
                            // A resume start reports its seek target as time-pos once the file opens,
                            // before any frame (mpv get_current_time), so a position counts only after
                            // mpv's playback start (Spec 85; it fires on a paused start too, N7).
                            firstFrameReady = (pos > 0L && playbackStarted) ||
                                (playingNow && !cacheBuffering && playerDuration > 0L)
                            if (firstFrameReady) {
                                hasRenderedFirstFrame = true
                                cancelStartupWatchdog()
                                clearFailoverNoticeAfterFirstFrame()
                                val clickToFirstFrameMs = launchStartedAtElapsedMs
                                    ?.let { (android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                                    ?: -1L
                                val initToFirstFrameMs = (System.currentTimeMillis() - playerInitializationStartedAtMs)
                                    .coerceAtLeast(0L)
                                val mpvStartupLine =
                                    "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
                                        "initToFirstFrameMs=$initToFirstFrameMs playbackSpeed=${_uiState.value.playbackSpeed} " +
                                        "currentPositionMs=$pos durationMs=$playerDuration engine=MPV " +
                                        "host=${currentStreamUrl.safeHost()}"
                                TtffTrace.mirror(mpvStartupLine)
                                finishLoadingDiagnostics("mpv_first_frame_ready")
                                if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                    _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                }
                            }
                        }
                    if (playerDuration > lastKnownDuration) {
                        lastKnownDuration = playerDuration
                    }
                    // Spec 85 E7: a rejected placeholder ends this job (the next stream restarts it).
                    if (maybeRejectMpvPlaceholder(playerDuration)) return@launch
                    val displayPosition = pendingPreviewSeekPosition ?: pos
                    updatePlaybackTimeline(
                        currentPosition = displayPosition,
                        duration = playerDuration,
                        bufferedPosition = (pos + (view.demuxerCacheDurationSec() * 1000.0).toLong())
                            .coerceAtLeast(displayPosition)
                    )
                    val nearEnd = playerDuration > 0L && pos >= (playerDuration - 500L)
                    val naturalEnded = nearEnd && shouldTreatAsNaturalPlaybackCompletion(
                        hasRenderedFirstFrame = firstFrameReady,
                        hasFatalError = _uiState.value.error != null,
                        durationMs = playerDuration,
                        releaseVideo = releaseVideo
                    )
                    val wasEnded = _uiState.value.playbackEnded
                    // mpv picks its render target once it has video params; it is read only after this
                    // stream's first frame (a reused VO still reports the previous file's target before
                    // that, as Exo decides at first frame too); null keeps the last answer.
                    val hdrOutput = if (firstFrameReady) view.readOutputIsHdr() else null
                    _uiState.update { state ->
                        state.copy(
                            isHdrOutput = hdrOutput ?: state.isHdrOutput,
                            isPlaying = playingNow,
                            isBuffering = !firstFrameReady || cacheBuffering,
                            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
                            // Snap the loading hand-off bar to 100% once playback is
                            // ready so it completes on dismissal.
                            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
                            playbackEnded = naturalEnded
                        )
                    }
                    updateMpvAvailableTracks()
                    updateActiveSkipInterval(pos)
                    evaluatePostPlayOverlayVisibility(
                        positionMs = pos,
                        durationMs = playerDuration
                    )
                    if (naturalEnded && !wasEnded) {
                        // Short placeholders never set naturalEnded, so they cannot mark
                        // watched or auto-advance (see #2819).
                        handleNaturalPlaybackEnded()
                    }
                }
                delay(500)
                continue
            }

            _exoPlayer?.let { player ->
                refreshAudioOutputMode()
                // Spec 57 D2: the app playhead (the target while the decoder primes at 0).
                val pos = exoCarryPositionMs(player).coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                // 5c: duration backstop. Content-length was cleared at READY (2a);
                // here the decoded duration is trustworthy. Fires once per stream (the
                // reject sets the latch, and an error when no source is left).
                if (PlaceholderStreamPolicy.durationBackstopArmed(
                        hasRenderedFirstFrame = hasRenderedFirstFrame,
                        errorShown = _uiState.value.error != null,
                        rejectedThisStream = placeholderRejectedForStream
                    )
                ) {
                    val effectiveDurationMs = getEffectiveDuration(pos)
                    val placeholderDurationVerdict = PlaceholderStreamPolicy.evaluate(
                        contentLengthBytes = null,
                        durationMs = effectiveDurationMs,
                        expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }
                    )
                    if (placeholderDurationVerdict is PlaceholderStreamPolicy.Verdict.Reject &&
                        placeholderDurationVerdict.reason == PlaceholderStreamPolicy.Reason.ImplausibleDuration
                    ) {
                        rejectPlaceholderStream(placeholderDurationVerdict)
                        // The rest of the tick would run on a stopped (or, after an eager
                        // advance, released) placeholder; the next stream restarts the job.
                        return@launch
                    } else {
                        // Spec 35: only a stream the placeholder gate kept is judged, so a
                        // reject never also warns.
                        checkRuntimeMismatch(player, effectiveDurationMs)
                    }
                }

                // nt8: TrueHD startup-storm auto-recovery. The Amlogic MS12 TrueHD
                // bypass parser can start misaligned after a display-mode switch and
                // then hunts for a major sync indefinitely, consuming buffered audio
                // 3-4x faster than real time (the racing master clock is the visible
                // position jump). The proven cure is an in-place seek: it recreates
                // the AudioTrack on the settled system (reuse-on-flush is disabled),
                // after which MS12 locks instantly -- measured on device. Roll back by
                // the burned lead so the viewer resumes roughly where the storm began.
                // nt11: latch the player-timeline position on the FIRST tick that
                // observes an un-consumed storm (<=500ms after onset). D's spacing
                // gate can defer the actual recovery by ~3s, during which the racing
                // clock moves `pos` ~10s past onset; latching here captures onset
                // before that drift. Non-consuming peek -- the flag is cleared only
                // by consumeTruehdStormRecoverySignal() below.
                if (truehdStormOnsetPosMs < 0L &&
                    playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true
                ) {
                    truehdStormOnsetPosMs = pos
                }
                // nt15: reset the spent per-playback budget after a clean interval,
                // so a distinct later storm cluster is not starved by the earlier
                // cluster's spent cap. Only acts when already capped; never touches
                // lastRecoveryAtMs (the >=3s spacing gate) or the onset latch.
                if (truehdStormRecoveryAttempts >= 2 &&
                    truehdStormLastRecoveryAtMs != 0L &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= TRUEHD_STORM_ATTEMPT_RESET_MS
                ) {
                    truehdStormRecoveryAttempts = 0
                }
                // nt14: corroborated early budget reset. SNAP_GATE proved the
                // spent cap was the only constraint holding back a cure in the
                // R2 warm-switch capture while both instruments already agreed
                // the failure was live. When the sink holds a preserved
                // detection AND the timeline classifier flagged a suspect
                // within 5 s AND the budget is spent, open the budget now
                // instead of waiting out the 6 s reset. Bounds: once per 15 s,
                // stand-down after 8 total recoveries this playback; the >=3 s
                // D-spacing still applies to the consume itself.
                // nt15: a LATCHED snap verdict (snapRecoveryPendingPosMs >= 0)
                // with a fresh suspect also opens the budget -- the undetected-
                // snap class queued 2.0 s behind the spent cap in the nt14
                // acceptance capture (SNAP_GATE, four consecutive ticks), and
                // the classifier's five-capture zero-false-positive record plus
                // the bounded consume make single-instrument opening safe.
                if (truehdStormRecoveryAttempts >= 2 &&
                    (playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true ||
                        snapRecoveryPendingPosMs >= 0L) &&
                    android.os.SystemClock.elapsedRealtime() - snapLastSuspectWallMs <= 5_000L &&
                    android.os.SystemClock.elapsedRealtime() - snapEarlyResetLastAtMs >= 15_000L &&
                    stormRecoveryTotalThisPlayback < 8
                ) {
                    truehdStormRecoveryAttempts = 0
                    snapEarlyResetLastAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_EARLY_RESET corroborated: " +
                            "sinceSuspectMs=${android.os.SystemClock.elapsedRealtime() - snapLastSuspectWallMs} " +
                            "detected=${playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true} " +
                            "snapPendMs=$snapRecoveryPendingPosMs " +
                            "total=$stormRecoveryTotalThisPlayback"
                    )
                }
                if (_uiState.value.error == null && truehdStormRecoveryAttempts < 2 &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= 3_000L
                ) {
                    playbackSpeedAwareAudioSink?.consumeTruehdStormRecoverySignal()?.let { leadMs ->
                        truehdStormRecoveryAttempts += 1
                        stormRecoveryTotalThisPlayback += 1
                        truehdStormLastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
                        // nt15: a storm rollback supersedes any pending snap latch --
                        // stale pre-rollback timeline evidence must not fire a second
                        // rollback for the same event. Fresh events re-flag (proven
                        // across five captures).
                        snapRecoveryPendingPosMs = -1L
                        // nt11: roll back to the latched storm onset, not the raced
                        // consume-time pos. leadMs retained in the log for continuity.
                        val onsetPos = if (truehdStormOnsetPosMs >= 0L) truehdStormOnsetPosMs else pos
                        val target = (onsetPos - 500L).coerceAtLeast(0L)
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TRUEHD_STORM_RECOVERY: attempt=$truehdStormRecoveryAttempts " +
                                "leadMs=$leadMs onsetPos=${onsetPos}ms pos=${pos}ms seekTo=${target}ms"
                        )
                        player.seekTo(target)
                        // nt11: clear the latch so a second storm latches its own onset.
                        truehdStormOnsetPosMs = -1L
                    }
                }
                val displayPosition = pendingPreviewSeekPosition ?: pos
                // nt11 (0.8.2): lock-snap classifier. A class-3 MS12 lock-snap
                // steps the player timeline forward in one stride with NO
                // discontinuity event (media3 is following the poisoned master
                // clock); every legitimate jump (seek, scrub, auto-transition)
                // announces itself via onPositionDiscontinuity first. A SUSPECT
                // logs SNAP_TRACE and, since nt12, latches snapRecoveryPendingPosMs
                // in the TrueHD passthrough context; the SNAP_RECOVERY block below
                // consumes it with a rollback seek through the shared storm budget.
                run {
                    val snapNowWall = android.os.SystemClock.elapsedRealtime()
                    val snapLastPos = snapShadowLastTickPosMs
                    val snapLastWall = snapShadowLastTickWallMs
                    if (snapLastPos >= 0L && snapLastWall != 0L) {
                        val strideMs = pos - snapLastPos
                        val wallMs = snapNowWall - snapLastWall
                        val discExplained = snapShadowLastDiscontinuityWallMs >= snapLastWall
                        if (strideMs >= SNAP_SHADOW_MIN_STRIDE_MS &&
                            wallMs in 50L..5_000L &&
                            !discExplained &&
                            _uiState.value.playbackSpeed == 1.0f
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "SNAP_TRACE SUSPECT pos=${pos}ms last=${snapLastPos}ms " +
                                    "strideMs=$strideMs wallMs=$wallMs " +
                                    "sinceDiscMs=${snapNowWall - snapShadowLastDiscontinuityWallMs} " +
                                    "buffering=${_uiState.value.isBuffering}"
                            )
                            // nt14: stamp every suspect verdict (any disposition)
                            // -- the early-reset corroboration window reads this.
                            snapLastSuspectWallMs = snapNowWall
                            // nt12: latch the pre-snap position for recovery, gated
                            // to the TrueHD passthrough context and deferring to the
                            // sink detector whenever its signal is live. First flag
                            // wins; consumed below through the shared budget.
                            // nt13: claim an ORPHANED onset latch -- onset >= 0 with
                            // the detection flag false means the storm path can never
                            // consume (its signal was wiped); roll back to the onset,
                            // which is earlier than the tick latch and so safer.
                            if (snapRecoveryPendingPosMs < 0L &&
                                playbackSpeedAwareAudioSink?.isTruehdStormDetected() != true &&
                                playbackSpeedAwareAudioSink?.isTruehdPassthroughActive() == true
                            ) {
                                if (truehdStormOnsetPosMs >= 0L) {
                                    snapRecoveryPendingPosMs = truehdStormOnsetPosMs
                                    truehdStormOnsetPosMs = -1L
                                    Log.w(
                                        PlayerRuntimeController.TAG,
                                        "SNAP_ORPHAN_CLAIM onsetPos=${snapRecoveryPendingPosMs}ms"
                                    )
                                } else {
                                    snapRecoveryPendingPosMs = snapLastPos
                                }
                                snapRecoveryPendingAtWallMs = snapNowWall
                            }
                        }
                    }
                    snapShadowLastTickPosMs = pos
                    snapShadowLastTickWallMs = snapNowWall
                }

                // nt12 (0.8.2): drop a stale pending latch before consuming.
                if (snapRecoveryPendingPosMs >= 0L &&
                    android.os.SystemClock.elapsedRealtime() - snapRecoveryPendingAtWallMs > SNAP_RECOVERY_PENDING_TTL_MS
                ) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_RECOVERY_EXPIRED latchedPos=${snapRecoveryPendingPosMs}ms"
                    )
                    snapRecoveryPendingPosMs = -1L
                }
                // nt12 (0.8.2): consume a pending snap-recovery latch through the
                // SHARED storm budget -- same cap (2), same nt15 6 s reset (already
                // applied earlier this tick), same >=3 s spacing, same rollback
                // shape. The recovery seek is the proven cure (in-place seek,
                // AudioTrack recreated, MS12 locks instantly), and its
                // discontinuity stamps the classifier token, so the rollback
                // itself can never re-flag.
                if (snapRecoveryPendingPosMs >= 0L &&
                    _uiState.value.error == null &&
                    truehdStormRecoveryAttempts < 2 &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= 3_000L
                ) {
                    truehdStormRecoveryAttempts += 1
                    stormRecoveryTotalThisPlayback += 1
                    truehdStormLastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
                    val snapTarget = (snapRecoveryPendingPosMs - 500L).coerceAtLeast(0L)
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_RECOVERY: attempt=$truehdStormRecoveryAttempts " +
                            "latchedPos=${snapRecoveryPendingPosMs}ms pos=${pos}ms seekTo=${snapTarget}ms"
                    )
                    snapRecoveryPendingPosMs = -1L
                    player.seekTo(snapTarget)
                }
                updatePlaybackTimeline(
                    currentPosition = displayPosition,
                    duration = playerDuration.coerceAtLeast(0L),
                    bufferedPosition = player.bufferedPosition.coerceAtLeast(displayPosition)
                )
                playbackAnalyticsDiagnostics.recordProgressSnapshot(
                    player = player,
                    hasRenderedFirstFrame = hasRenderedFirstFrame
                )
                if (maybeRecoverFrozenPicture(player, pos)) {
                    // A failover is switching streams; the rest of the tick would act on the player it
                    // releases (as after a placeholder reject). The next stream restarts the job.
                    return@launch
                }
                // Update torrent rebuffer progress from ExoPlayer's buffer state
                if (isTorrentStream && _uiState.value.isBuffering && hasRenderedFirstFrame) {
                    val bufferedAheadMs = (player.bufferedPosition - pos).coerceAtLeast(0)
                    val bufferedSec = bufferedAheadMs / 1000f
                    val statsHidden = _uiState.value.hideTorrentStats
                    val message = if (statsHidden) {
                        null
                    } else {
                        val speed = formatTorrentSpeed(context, _uiState.value.torrentDownloadSpeed)
                        val peerInfo = context.getString(
                            R.string.player_torrent_peer_info,
                            _uiState.value.torrentSeeds,
                            _uiState.value.torrentPeers
                        )
                        val bufLabel = String.format("%.0fs", bufferedSec)
                        context.getString(
                            R.string.player_torrent_buffered_status,
                            bufLabel,
                            peerInfo,
                            speed
                        )
                    }
                    val progress = (bufferedSec / 10f).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(
                            torrentBufferingMessage = message,
                            torrentBufferingProgress = progress
                        )
                    }
                }
                updateActiveSkipInterval(pos)
                evaluatePostPlayOverlayVisibility(
                    positionMs = pos,
                    durationMs = playerDuration.coerceAtLeast(0L)
                )
                checkExoPlaybackOverrun(player, playerDuration)

                // Debug > buffer logs toggle; off by default.
                if (bufferLogsEnabled && player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastBufferLogTimeMs >= 10_000) {
                        lastBufferLogTimeMs = now
                        val bufAhead = (player.bufferedPosition - player.currentPosition) / 1000
                        val loading = player.isLoading
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s")
                        
                        if (NuvioExoPlayerPerformanceHelper.enabled) {
                            val defaultAllocator = _loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator
                            val totalFootprintBytes = defaultAllocator?.let { allocator ->
                                try {
                                    val method = allocator.javaClass.getMethod("getMemoryFootprint")
                                    method.invoke(allocator) as? Int ?: 0
                                } catch (e: Exception) {
                                    0
                                }
                            } ?: 0
                            val totalActiveBytes = defaultAllocator?.totalBytesAllocated ?: 0
                            val footprintMb = totalFootprintBytes / (1024 * 1024)
                            val activeMb = totalActiveBytes / (1024 * 1024)
                            Log.d("ExoMemory", "Off-heap OS ahead: $footprintMb MB, active: $activeMb MB")
                        }
                    }
                }
            }
            delay(500)
        }
    }
}

internal const val EXO_END_OVERRUN_GRACE_MS = 1_000L

internal enum class ExoOverrunAction { NONE, REARM, FORCE_END }

/**
 * Spec 61 Q2 (NM 105490c2d): what the progress tick does about a position past the duration.
 * [audioEnded] is only asked once everything else says "force", so the renderer read stays
 * off the normal tick.
 */
internal fun exoOverrunAction(
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    playerEnded: Boolean,
    alreadyHandled: Boolean,
    hasRenderedFirstFrame: Boolean,
    audioEnded: () -> Boolean
): ExoOverrunAction {
    if (durationMs <= 0L || isLive || playerEnded) return ExoOverrunAction.NONE
    val overrunMs = positionMs - durationMs
    // Back inside the stream after a forced end (the viewer sought back): detect again. Exactly
    // at the duration is still the end (a forward seek clamps there), as a real ENDED would be.
    if (overrunMs <= 0L) {
        return if (alreadyHandled && overrunMs < 0L) ExoOverrunAction.REARM else ExoOverrunAction.NONE
    }
    if (alreadyHandled || overrunMs < EXO_END_OVERRUN_GRACE_MS || !hasRenderedFirstFrame) {
        return ExoOverrunAction.NONE
    }
    // Audio still running past the duration means the duration is wrong, not the player.
    return if (audioEnded()) ExoOverrunAction.FORCE_END else ExoOverrunAction.NONE
}

/**
 * Whether at least one audio renderer is enabled and every enabled one has ended. A deliberate
 * read of playback-thread state from the app thread (no app-thread signal for it exists): only
 * asked past the duration plus the grace, a stale answer costs one 500 ms tick, a throw is "no".
 */
private fun exoAudioRendererEnded(player: ExoPlayer): Boolean =
    runCatching {
        val audio = (0 until player.rendererCount)
            .filter { player.getRendererType(it) == C.TRACK_TYPE_AUDIO }
            .map { player.getRenderer(it) }
            .filter { it.state != Renderer.STATE_DISABLED }
        audio.isNotEmpty() && audio.all { it.isEnded }
    }.getOrDefault(false)

/**
 * ExoPlayer only reaches STATE_ENDED once every enabled renderer reports isEnded(). Some
 * hardware decoders never let the video renderer read the end-of-stream marker: audio
 * finishes, the screen goes black and the clock keeps running past the duration, so watched
 * and next-episode never fire. Once audio has ended past the duration, finish naturally.
 */
internal fun PlayerRuntimeController.checkExoPlaybackOverrun(player: ExoPlayer, durationMs: Long) {
    val positionMs = player.currentPosition
    val action = exoOverrunAction(
        positionMs = positionMs,
        durationMs = durationMs,
        isLive = player.isCurrentMediaItemLive,
        playerEnded = player.playbackState == Player.STATE_ENDED,
        alreadyHandled = exoEndOverrunHandled,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        audioEnded = { exoAudioRendererEnded(player) }
    )
    when (action) {
        ExoOverrunAction.NONE -> return
        ExoOverrunAction.REARM -> {
            exoEndOverrunHandled = false
            _uiState.update { it.copy(playbackEnded = false) }
            // The forced end paused the player; a real ENDED resumes after a seek back.
            if (!userPausedManually) player.play()
            return
        }
        ExoOverrunAction.FORCE_END -> Unit
    }
    // Same test the STATE_ENDED listener applies; an unnatural end is asked again next tick.
    if (!shouldTreatAsNaturalPlaybackCompletion(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            hasFatalError = _uiState.value.error != null,
            durationMs = maxOf(durationMs, lastKnownDuration),
            releaseVideo = releaseVideo
        )
    ) {
        return
    }
    exoEndOverrunHandled = true
    Log.i(
        PlayerRuntimeController.TAG,
        "EXO_END_OVERRUN: forcing natural end positionMs=$positionMs durationMs=$durationMs"
    )
    _uiState.update { it.copy(playbackEnded = true, isBuffering = false) }
    handleNaturalPlaybackEnded()
    // Stop the runaway clock; the pause's own progress save still sees the full duration
    // through exoEndOverrunHandled in getEffectiveDuration.
    player.pause()
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(WATCH_PROGRESS_SAVE_INTERVAL_MS)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame || placeholderRejectedForStream) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Don't save progress for very short streams (< 2:01) — these are
    // typically error/warning messages or "stream not ready" placeholders that
    // would incorrectly mark content as watched when the user exits.
    if (isShortPlaceholderDuration(duration)) return

    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    // Spec 21 P3: a rejected placeholder's position is not the title's progress.
    if (!hasRenderedFirstFrame || placeholderRejectedForStream) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration)) return
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = currentPlaybackDurationMs()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = if (isUsingMpvEngine()) {
        position >= (effectiveDuration - 500L)
    } else {
        _exoPlayer?.playbackState == Player.STATE_ENDED || exoEndOverrunHandled
    }
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

/**
 * Handles a natural end-of-playback event for ExoPlayer / MPV.
 *
 * Short debrid placeholders and fatal-error states must not mark the episode
 * watched or trigger auto-play next.
 */
internal fun PlayerRuntimeController.handleNaturalPlaybackEnded() {
    val position = currentPlaybackPositionMs() ?: 0L
    val duration = getEffectiveDuration(position)
    val hasFatalError = _uiState.value.error != null
    if (!shouldTreatAsNaturalPlaybackCompletion(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            hasFatalError = hasFatalError,
            durationMs = duration,
            releaseVideo = releaseVideo
        )
    ) {
        Log.i(
            PlayerRuntimeController.TAG,
            "Ignoring non-natural ENDED: firstFrame=$hasRenderedFirstFrame " +
                "error=$hasFatalError durationMs=$duration positionMs=$position"
        )
        // Prevent PlayerScreen from dispatching onPlaybackEnded / next-episode navigation.
        _uiState.update { it.copy(playbackEnded = false) }
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
        return
    }

    saveWatchProgress()
    resetPostPlayStateAfterPlaybackEnded()
}

/**
 * Cancels any in-flight next-episode auto-play / still-watching prompt when a
 * fatal player error is shown. Callers should also clear [PlayerUiState.playbackEnded]
 * and [PlayerUiState.postPlayMode] in the same state update as the error message.
 */
internal fun PlayerRuntimeController.cancelNextEpisodeAutoPlayOnFatalError() {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    val parentContentId = contentId?.takeIf { it.isNotEmpty() } ?: return
    val parentContentType = contentType?.takeIf { it.isNotEmpty() } ?: return

    if (position < 1000) return
    // Spec 57 D4: from here the saved row is this session's own progress.
    startFromBeginningPending = false

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = WatchProgress(
        contentId = parentContentId,
        contentType = parentContentType,
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: parentContentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    scope.launch(kotlinx.coroutines.NonCancellable) {
        when (
            watchProgressSaveAction(
                isCompleted = progress.isCompleted(),
                alreadyMarkedCompleted = hasMarkedCurrentEpisodeCompleted
            )
        ) {
            WatchProgressSaveAction.MARK_COMPLETED -> {
                hasMarkedCurrentEpisodeCompleted = true
                // The repository also prunes this episode's (or movie's) Watch Next row.
                watchProgressRepository.markAsCompleted(progress)
                // Once per stream (the flag above): Library's "Watched 2×" (Spec 27e).
                watchCountDataStore.recordCompletedPlay(parentContentId, parentContentType)
            }
            WatchProgressSaveAction.REPRUNE_COMPLETED -> {
                // Every later save re-prunes: an upsert from an earlier save that finished
                // after the first prune would otherwise re-add a finished episode.
                runCatching {
                    tvRecommendationManager.onProgressRemoved(
                        progress.contentId,
                        progress.season,
                        progress.episode
                    )
                }
            }
            WatchProgressSaveAction.SAVE_IN_PROGRESS -> {
                watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
                runCatching { tvRecommendationManager.updateSingleWatchNextProgram(progress) }
            }
        }
    }
}

internal enum class WatchProgressSaveAction { MARK_COMPLETED, REPRUNE_COMPLETED, SAVE_IN_PROGRESS }

/**
 * What one progress save does. Once this playback session has marked the episode (or movie)
 * completed, every later save only re-prunes Watch Next, completed or not (T23, NM
 * `7e4e57195`): after the end the player can report an unknown duration (saved with the 5%
 * fallback) or a stale position, which overwrote the completed entry and synced the low value.
 * Seeking back to rewatch a scene after completion therefore keeps the title completed for
 * this session.
 */
internal fun watchProgressSaveAction(
    isCompleted: Boolean,
    alreadyMarkedCompleted: Boolean
): WatchProgressSaveAction = when {
    alreadyMarkedCompleted -> WatchProgressSaveAction.REPRUNE_COMPLETED
    isCompleted -> WatchProgressSaveAction.MARK_COMPLETED
    else -> WatchProgressSaveAction.SAVE_IN_PROGRESS
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(8000)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioOverlay &&
            !_uiState.value.showSubtitleOverlay &&
            !_uiState.value.showSpeedPanel && !_uiState.value.showChaptersPanel && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showSubtitleTimingDialog &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleTimingDialog = false,
            showSpeedPanel = false,
            showChaptersPanel = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    adjustSubtitleDelay(deltaMs = deltaMs, showOverlay = true)
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int, showOverlay: Boolean) {
    setSubtitleDelayMs(targetMs = _uiState.value.subtitleDelayMs + deltaMs, showOverlay = showOverlay)
}

internal fun PlayerRuntimeController.resetSubtitleDelay(showOverlay: Boolean = true) {
    setSubtitleDelayMs(targetMs = 0, showOverlay = showOverlay)
}

internal fun PlayerRuntimeController.setSubtitleDelayMs(targetMs: Int, showOverlay: Boolean = true) {
    val newDelayMs = targetMs.coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )
    val currentState = _uiState.value
    val keepInlineInSubtitleOverlay = showOverlay && currentState.showSubtitleOverlay

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(newDelayMs)
    }
    if (showOverlay) {
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showControls = if (keepInlineInSubtitleOverlay) it.showControls else false,
                showSubtitleDelayOverlay = if (keepInlineInSubtitleOverlay) false else true
            )
        }
    } else {
        hideSubtitleDelayOverlayJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showSubtitleDelayOverlay = false,
                showControls = true
            )
        }
    }

    refreshActiveSubtitleTrackAfterTimingChange()
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()

    if (!showOverlay || keepInlineInSubtitleOverlay) {
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
    } else {
        scheduleHideSubtitleDelayOverlay()
    }
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()

    // Spec 35: the runtime warning holds playback paused and owns the screen meanwhile.
    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually ||
        _uiState.value.runtimeMismatch != null
    ) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val s = _uiState.value
        val anyPanelOpen = s.showSubtitleOverlay ||
            s.showSpeedPanel || s.showChaptersPanel || s.showMoreDialog || s.showEpisodesPanel ||
            s.showSourcesPanel || s.showAudioOverlay ||
            s.showSubtitleTimingDialog || s.showSubtitleDelayOverlay
        if (!s.isPlaying && s.pauseOverlayEnabled && s.error == null && !anyPanelOpen) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    if (_uiState.value.showPauseOverlay) {
        cancelPauseOverlay()
        showControlsTemporarily()
    } else if (pauseOverlayJob != null && !_uiState.value.isPlaying && userPausedManually) {
        schedulePauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false, showMoreDialog = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    onUserInteraction()
    when (event) {
        PlayerEvent.OnPlayPause -> {
            if (isUsingMpvEngine()) {
                val playing = isPlaybackCurrentlyPlaying()
                if (mpvView?.isStopped() == true) {
                    // Spec 85: a stopped start (placeholder reject, failed start) has nothing to
                    // resume; restarting the progress job would hide a source advance's loading screen.
                } else if (playing) {
                    userPausedManually = true
                    setPlaybackPaused(true)
                    stopProgressUpdates()
                    stopWatchProgressSaving()
                    schedulePauseOverlay()
                } else {
                    userPausedManually = false
                    cancelPauseOverlay()
                    setPlaybackPaused(false)
                    startProgressUpdates()
                    startWatchProgressSaving()
                    scheduleHideControls()
                }
            } else {
                _exoPlayer?.let { player ->
                    // Play intent, not isPlaying: isPlaying is false while
                    // rebuffering, so pause pressed mid-rebuffer must still pause.
                    if (player.playWhenReady) {
                        userPausedManually = true
                        player.pause()
                        schedulePauseOverlay()
                        // nt6: a parked auto-restore subtitle attaches here,
                        // while paused, so the reload lands invisibly.
                        maybeAttachDeferredAddonSubtitle()
                    } else if (!exoEndOverrunHandled) {
                        // After a forced end (Spec 61 Q2) Play would only restart the runaway clock.
                        userPausedManually = false
                        cancelPauseOverlay()
                        player.play()
                    }
                }
            }
            showControlsTemporarily()
        }
        PlayerEvent.OnSeekForward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = PlayerScrubRates.STEP_SHORT_MS))
        }
        PlayerEvent.OnSeekBackward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -PlayerScrubRates.STEP_SHORT_MS))
        }
        is PlayerEvent.OnSeekBy -> {
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            val current = currentPlaybackPositionMs() ?: 0L
            // > 0, not >= 0: MPV reports 0 for live/unknown durations; accepting it pins
            // every seek target to ≤0 (D-pad seeks collapse to 0:00). Matches :74/:1007.
            val maxDuration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
            val target = (current + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            val seekParameters = if (event.deltaMs < 0L) {
                SeekParameters.PREVIOUS_SYNC
            } else {
                SeekParameters.NEXT_SYNC
            }
            seekPlaybackTo(target, seekParameters)
            updatePlaybackTimeline(currentPosition = target)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            val maxDuration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
            val startsPreview = pendingPreviewSeekPosition == null
            val basePosition = pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
            val target = (basePosition + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            pendingPreviewSeekPosition = target
            _uiState.update {
                it.copy(
                    pendingPreviewSeekPosition = target,
                    previewSeekOriginMs = if (startsPreview) basePosition else it.previewSeekOriginMs,
                    previewThumbPositionMs = target
                )
            }
            // Build 12b (Lever 1): tell the thumbnail worker to prioritise this bucket.
            SeekThumbnails.notePriority(target)
            schedulePendingPreviewSeekExpiry()
            updatePlaybackTimeline(currentPosition = target)
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            val target = pendingPreviewSeekPosition
            if (target != null) {
                pendingPreviewSeekExpiryJob?.cancel()
                seekPlaybackTo(target, SeekParameters.CLOSEST_SYNC)
                updatePlaybackTimeline(currentPosition = target)
                pendingPreviewSeekPosition = null
                _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = target) }
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            seekPlaybackTo(event.position, SeekParameters.CLOSEST_SYNC)
            updatePlaybackTimeline(currentPosition = event.position)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            rememberAudioSelection(event.index)
            // Tunnelled playback: in-place AudioTrack recreation inside a live
            // tunnel latches bad frame pacing on some vendor HALs (Prism+
            // report). Rebuild at position instead; no-op when tunneling off.
            if (!maybeRebuildForTunneledAudioSwitch(event.index)) {
                selectAudioTrack(event.index)
            }
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleDelayOverlay = false,
                    showSubtitleTimingDialog = false
                )
            }
        }
        is PlayerEvent.OnSetAudioDelayMs -> {
            applyAudioDelay(event.delayMs)
        }
        // Held +/-: relative to the applied delay, persisted once when the hold ends.
        is PlayerEvent.OnStepAudioDelayHeld -> {
            applyAudioDelay(_uiState.value.audioDelayMs + event.deltaMs, persistForCurrentRoute = false)
        }
        PlayerEvent.OnAudioDelayHoldReleased -> {
            persistAudioDelayForCurrentRoute(_uiState.value.audioDelayMs)
        }
        is PlayerEvent.OnSetAudioAmplificationDb -> {
            val clampedDb = event.db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
            applyAudioAmplification(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setAudioAmplificationDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSetPersistAudioAmplification -> {
            val currentDb = _uiState.value.audioAmplificationDb
            val currentCenterMixDb = _uiState.value.centerMixLevelDb
            _uiState.update { it.copy(persistAudioAmplification = event.enabled) }
            scope.launch {
                playerSettingsDataStore.setPersistAudioAmplification(
                    enabled = event.enabled,
                    dbToPersist = if (event.enabled) currentDb else null,
                    centerMixDbToPersist = if (event.enabled) currentCenterMixDb else null
                )
            }
        }
        is PlayerEvent.OnSetCenterMixLevelDb -> {
            val clampedDb = event.db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
            applyCenterMixLevel(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setCenterMixLevelDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            trackSelection.autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberInternalSubtitleSelection(event.index)
            selectSubtitleTrack(event.index)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null
                )
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            trackSelection.autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberSubtitleDisabled()
            disableSubtitles()
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                )
            }
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            trackSelection.autoSubtitleSelected = true
            rememberAddonSubtitleSelection(event.subtitle)
            selectAddonSubtitle(event.subtitle)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            if (isUsingMpvEngine()) {
                setPlaybackSpeedInternal(event.speed)
            } else {
                _exoPlayer?.let { player ->
                    player.setPlaybackSpeed(event.speed)
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .build()
                }
            }
            _uiState.update {
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedPanel = false,
                    showChaptersPanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleTimingDialog) {
                dismissSubtitleTimingDialog()
            }
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            val shouldShowControls = !_uiState.value.showControls
            _uiState.update {
                it.copy(
                    showControls = shouldShowControls,
                    showSeekOverlay = false,
                    showMoreDialog = if (shouldShowControls) it.showMoreDialog else false
                )
            }
            if (shouldShowControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = true,
                    showSubtitleOverlay = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleOverlay -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showAudioOverlay = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleTimingDialog -> {
            showSubtitleTimingDialog()
        }
        PlayerEvent.OnDismissSubtitleTimingDialog -> {
            dismissSubtitleTimingDialog()
        }
        PlayerEvent.OnCaptureSubtitleAutoSyncTime -> {
            captureSubtitleAutoSyncTime()
        }
        is PlayerEvent.OnApplySubtitleAutoSyncCue -> {
            applySubtitleAutoSyncCue(event.cueStartTimeMs)
        }
        PlayerEvent.OnReloadSubtitleAutoSyncCues -> {
            reloadSubtitleAutoSyncCues()
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs, event.showOverlay)
        }
        is PlayerEvent.OnResetSubtitleDelay -> {
            resetSubtitleDelay(event.showOverlay)
        }
        PlayerEvent.OnShowSpeedPanel -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            _uiState.update {
                it.copy(
                    showSpeedPanel = true,
                    showChaptersPanel = false,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowChaptersPanel -> {
            _uiState.update {
                it.copy(
                    showChaptersPanel = true,
                    showSpeedPanel = false,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedPanel = false,
                    showChaptersPanel = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(event.video)
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeListSelected -> {
            selectEpisodeList(event.selection)
        }
        is PlayerEvent.OnEpisodeFilterChanged -> {
            filterEpisodeList(event.filter)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceListSelected -> {
            selectSourceList(event.selection)
        }
        is PlayerEvent.OnSourceFilterChanged -> {
            filterSourceList(event.filter)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        is PlayerEvent.OnPreferSource -> {
            preferSource(event.stream)
        }
        PlayerEvent.OnDismissTransientOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleTimingDialog = false,
                    showSpeedPanel = false,
                    showChaptersPanel = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                )
            }
            scheduleHideControls()
        }
        PlayerEvent.OnParentalGuideHide -> {
            _uiState.update { it.copy(showParentalGuide = false) }
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        is PlayerEvent.OnRuntimeMismatchChoice -> {
            onRuntimeMismatchChoice(event.playAnyway)
        }
        PlayerEvent.OnSkipNetworkTest -> {
            networkTestJob?.cancel()
        }
        is PlayerEvent.OnDegradedNoticeShown -> {
            _uiState.update { if (it.degradedNotice == event.message) it.copy(degradedNotice = null) else it }
        }
        PlayerEvent.OnDismissPauseOverlay -> {
            cancelPauseOverlay()
        }
        PlayerEvent.OnSkipIntro -> {
            skipActiveInterval()
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode(userInitiated = true)
        }
        PlayerEvent.OnPlayPreviousEpisode -> {
            playPreviousEpisode()
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
        }
        PlayerEvent.OnStillWatchingContinue -> onStillWatchingContinue()
        PlayerEvent.OnDismissStillWatchingPrompt -> onDismissStillWatchingPrompt()
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            // Spec 62 H2c: the target is the style the view showed, carried in the event.
            scope.launch {
                if (event.hdr) {
                    playerSettingsDataStore.setSubtitleHdrTextColor(event.color)
                } else {
                    playerSettingsDataStore.setSubtitleTextColor(event.color)
                }
            }
        }
        is PlayerEvent.OnSetSubtitleHdrBrightness -> {
            scope.launch { playerSettingsDataStore.setSubtitleHdrBrightnessPercent(event.percent) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleEdgeStyle -> {
            scope.launch { playerSettingsDataStore.setSubtitleEdgeStyle(event.style) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        is PlayerEvent.OnResetSubtitleDefaults -> {
            // Spec 62 H2c: the target is the style the view showed, carried in the event; on the
            // HDR style its colour rows are the HDR ones, so those reset.
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                if (event.hdr) {
                    playerSettingsDataStore.setSubtitleHdrTextColor(defaults.hdrTextColor)
                    playerSettingsDataStore.setSubtitleHdrBrightnessPercent(defaults.hdrBrightnessPercent)
                } else {
                    playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
                }
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleEdgeStyle(defaults.edgeStyle)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            val newMode = nextAspectMode(state.aspectMode)
            val label = aspectModeLabel(newMode, context::getString)
            Log.d(PlayerRuntimeController.TAG, "Aspect mode toggled by user: ${state.aspectMode} -> $newMode ($label)")
            _uiState.update {
                it.copy(
                    aspectMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = label
                )
            }
            scope.launch {
                Log.d(PlayerRuntimeController.TAG, "Persisting aspect mode: $newMode")
                deviceLocalPlayerPreferences.setAspectMode(newMode)
            }
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
        PlayerEvent.OnSwitchInternalPlayerEngine -> {
            switchInternalPlayerEngineManually()
        }
        PlayerEvent.OnTogglePlaybackStats -> {
            _uiState.update { it.copy(showPlaybackStatsOverlay = !it.showPlaybackStatsOverlay) }
        }
        PlayerEvent.OnHidePlaybackStats -> {
            _uiState.update { it.copy(showPlaybackStatsOverlay = false) }
        }
    }
}

/**
 * Seek review F3: expire an uncommitted preview position ~3 s after the last
 * preview event, snapping the timeline back to the real position. Covers commits
 * swallowed by panels opening mid-gesture and controls-visibility changes.
 */
internal fun PlayerRuntimeController.schedulePendingPreviewSeekExpiry() {
    pendingPreviewSeekExpiryJob?.cancel()
    pendingPreviewSeekExpiryJob = scope.launch {
        kotlinx.coroutines.delay(3_000L)
        if (pendingPreviewSeekPosition != null) {
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            currentPlaybackPositionMs()?.let { updatePlaybackTimeline(currentPosition = it) }
        }
    }
}
