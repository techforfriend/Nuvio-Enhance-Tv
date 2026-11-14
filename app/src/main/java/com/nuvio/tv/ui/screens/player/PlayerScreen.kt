@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeLoadingScreen
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import com.nuvio.tv.core.stream.RecentFailures
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.sources.SourceFailureMark
import com.nuvio.tv.ui.components.sources.SourceFailureMarks
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.core.assessment.NETWORK_TEST_STEPS
import com.nuvio.tv.core.assessment.networkTestDisplayStep
import com.nuvio.tv.core.assessment.networkTestMinutesLeft
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.LoadingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.tv.core.player.findCurrentStreamIndex
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.components.sources.SourceRowMarker
import com.nuvio.tv.ui.components.sources.SourcesPanelSheet
import com.nuvio.tv.ui.components.sources.StreamSourcesPanel
import com.nuvio.tv.ui.components.rememberRawSvgPainter
import com.nuvio.tv.core.player.AspectMode
import com.nuvio.tv.core.player.FailoverNotice
import com.nuvio.tv.core.player.triedFor
import com.nuvio.tv.core.player.NuvioMpvSurfaceView
import com.nuvio.tv.core.player.PlaybackTimelineState
import com.nuvio.tv.core.player.PlayerEvent
import com.nuvio.tv.core.player.PlayerExitReason
import com.nuvio.tv.core.player.PlayerRuntimeController
import com.nuvio.tv.core.player.PlayerScrubRates
import com.nuvio.tv.core.player.PlayerSubtitleUtils
import com.nuvio.tv.core.player.PostPlayMode
import com.nuvio.tv.core.player.SUBTITLE_DELAY_STEP_MS
import com.nuvio.tv.core.player.SubtitleSyncCue
import com.nuvio.tv.core.player.addExoAspectLayoutChangeListener
import com.nuvio.tv.core.player.applyExoAspectMode
import com.nuvio.tv.core.player.bindExoSubtitleView
import com.nuvio.tv.core.player.applySubtitleLook
import com.nuvio.tv.core.player.getAssHandlerCompat
import com.nuvio.tv.core.player.isSkipIntroCanFocus

/** How long the network test's notice stays (Spec 33, approved stage t-notice: 4 s). */
private const val NETWORK_TEST_NOTICE_MS = 4_000L

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBackPress: (currentVideoId: String?, currentSeason: Int?, currentEpisode: Int?, autoPlayEnabled: Boolean, playbackCompleted: Boolean) -> Unit,
    onPlaybackErrorBack: () -> Unit = { onBackPress(null, null, null, false, false) },
    onPlaybackEnded: ((nextVideoId: String?, nextSeason: Int?, nextEpisode: Int?, exitReason: PlayerExitReason?) -> Unit)? = null,
    /** Spec 33 D7: opens Settings › Buffer & network over the film; null = Settings is locked (no chip). */
    onOpenNetworkSettings: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val progressBarFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    val moreFocusRequester = remember { FocusRequester() }
    val episodesButtonFocusRequester = remember { FocusRequester() }
    val sourcesButtonFocusRequester = remember { FocusRequester() }
    // The row button that opened the Episodes or Sources panel: focus returns to it when the
    // panel closes (Arshad 2026-10-09, wave F), as the tray's Back returns to More.
    var panelOpenerFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var skipButtonActuallyVisible by remember { mutableStateOf(false) }
    var focusMoreAfterTrayClose by remember { mutableStateOf(false) }
    // Back from the Speed or Chapters panel reopens the tray on that pill (Spec 25 Q4); reset when it closes.
    var trayOpenOn by remember { mutableStateOf<TrayPill?>(null) }
    // Pause overlay cast (Spec 25 U6h): UI-local, never a PlayerEvent (every event cancels the
    // overlay, P-6). The chip holding focus, the chip whose person is open, and a chip to focus
    // once composed (Down; Back from the person). Reset each time the overlay shows.
    var pauseCastFocused by remember { mutableStateOf<Int?>(null) }
    var pauseCastDetail by remember { mutableStateOf<Int?>(null) }
    var pauseCastFocusRequest by remember { mutableStateOf<Int?>(null) }
    val pauseCast = remember(uiState.castMembers) { uiState.castMembers.take(PAUSE_CAST_MAX) }
    // Spec 33 D7: the pause screen's Network chip and the network test's notice.
    var pauseNetworkFocused by remember { mutableStateOf(false) }
    val networkChipFocusRequester = remember { FocusRequester() }
    // Saveable: the player's composition is gone while Settings is on top, and focus returns to
    // the chip when the film comes back.
    var focusNetworkChipOnReturn by rememberSaveable { mutableStateOf(false) }
    // Settings opened from the chip: leaving for it keeps the film's display mode (below).
    var leavingForSettings by remember { mutableStateOf(false) }
    var networkTestNotice: String? = null
    var networkChipText: String? = null
    uiState.networkTestResult?.let { result ->
        val connections = pluralStringResource(R.plurals.network_test_connections, result.connections, result.connections)
        networkTestNotice = stringResource(R.string.network_test_notice, result.service, connections)
        // Hidden on mpv: Buffer & network is an ExoPlayer chip, and the mpv view composed on the
        // way back would load the stream afresh.
        if (onOpenNetworkSettings != null && uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER) {
            networkChipText = stringResource(R.string.pause_network_chip, result.service, connections)
        }
    }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    var subtitleDelayFocusTarget by remember { mutableStateOf(SubtitleDelayFocusTarget.SLIDER) }
    val subtitleDelayResetFocusRequester = remember { FocusRequester() }
    val subtitleDelaySyncLineFocusRequester = remember { FocusRequester() }
    var subtitleTimingConsumeNextConfirmKeyUp by remember { mutableStateOf(false) }
    var exitDispatched by remember { mutableStateOf(false) }
    var externalHandoffInProgress by remember { mutableStateOf(false) }

    val exitPlayer: () -> Unit = exitPlayer@{
        if (exitDispatched) return@exitPlayer
        exitDispatched = true
        val timeline = viewModel.playbackTimeline.value
        viewModel.stopAndRelease()
        val completed = timeline.isCompleted()
        onBackPress(uiState.currentVideoId, uiState.currentSeason, uiState.currentEpisode, uiState.backTreatsSwitchAsAutoPlay, completed)
    }
    val exitPlayerFromError: () -> Unit = exitPlayerFromError@{
        // The error box's Go Back during an external handoff (Spec 86 R3): the handoff leaves itself.
        if (exitDispatched || externalHandoffInProgress) return@exitPlayerFromError
        exitDispatched = true
        viewModel.stopAndRelease()
        onPlaybackErrorBack()
    }
    // Back or Down in the OSD's More tray: close it and return focus to More.
    val closeTray = {
        focusMoreAfterTrayClose = true
        viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
    }

    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnBackPress by rememberUpdatedState(onBackPress)
    // The More tray's External player and the error box's (Spec 86 R3).
    val openExternalPlayer: () -> Unit = {
        if (!externalHandoffInProgress) {
            externalHandoffInProgress = true
            val timeline = viewModel.playbackTimeline.value
            val completed = timeline.isCompleted()
            viewModel.launchInExternalPlayer(context, timeline.currentPosition) { launched ->
                externalHandoffInProgress = false
                if (launched && !exitDispatched) {
                    exitDispatched = true
                    currentOnBackPress(
                        uiState.currentVideoId,
                        uiState.currentSeason,
                        uiState.currentEpisode,
                        uiState.backTreatsSwitchAsAutoPlay,
                        completed
                    )
                }
            }
        }
    }
    val nextEpisodeForEndPrompt = uiState.nextEpisode?.takeIf { it.hasAired }
    val shouldConfirmNextEpisodeOnEnd =
        uiState.playbackEnded &&
            uiState.error == null &&
            uiState.backTreatsSwitchAsAutoPlay &&
            !uiState.streamAutoPlayNextEpisodeEnabled &&
            nextEpisodeForEndPrompt != null &&
            uiState.previousEpisodeSearch == null
    val returnToDetailsFromEndPrompt = {
        viewModel.stopAndRelease()
        currentOnBackPress(
            uiState.currentVideoId,
            uiState.currentSeason,
            uiState.currentEpisode,
            true,
            true
        )
    }
    val continueToNextEpisodeFromEndPrompt = {
        val next = nextEpisodeForEndPrompt
        if (next != null) {
            viewModel.stopAndRelease()
            val cb = currentOnPlaybackEnded
            if (cb != null) {
                cb(next.videoId, next.season, next.episode, null)
            } else {
                currentOnBackPress(
                    uiState.currentVideoId,
                    uiState.currentSeason,
                    uiState.currentEpisode,
                    false,
                    true
                )
            }
        }
    }

    // The open sheet of each stream list (Sources; an episode's streams; Spec 65 P1): Back closes
    // it first, and it never outlives its panel. One state per panel, so a sheet left by one is
    // never drawn, even for a frame, by the other.
    val sourcesPanelShown = uiState.showSourcesPanel && uiState.error == null
    val episodeStreamsShown = uiState.showEpisodesPanel && uiState.showEpisodeStreams && uiState.error == null
    // Spec 75 S6: the Sources panels mark a source that failed in the last day; the store is read as
    // a panel opens and again when this session's dead set grows (each failover records first).
    val recentFailures by produceState(
        RecentFailures.EMPTY,
        sourcesPanelShown || episodeStreamsShown,
        uiState.deadSourceStreamUrls
    ) {
        if (sourcesPanelShown || episodeStreamsShown) {
            value = viewModel.recentFailures()
        }
    }
    val failureStrings = remember(context) { contextFailureStrings(context) }
    val sourceMarkFor: (Stream) -> SourceFailureMark? = remember(recentFailures, uiState.deadSourceStreamUrls, failureStrings) {
        val dead = uiState.deadSourceStreamUrls
        { stream -> SourceFailureMarks.forRow(stream, recentFailures, dead, failureStrings) }
    }
    val episodeMarkFor: (Stream) -> SourceFailureMark? = remember(recentFailures, failureStrings) {
        { stream -> SourceFailureMarks.forRow(stream, recentFailures, emptySet(), failureStrings) }
    }
    var sourcesSheet by remember { mutableStateOf<SourcesPanelSheet?>(null) }
    var episodeSheet by remember { mutableStateOf<SourcesPanelSheet?>(null) }
    val savedStreamFilters by viewModel.savedStreamFilters.collectAsState(
        initial = com.nuvio.tv.core.stream.SavedStreamFilters.NONE
    )
    LaunchedEffect(sourcesPanelShown) { if (!sourcesPanelShown) sourcesSheet = null }
    // Spec 75 S5: each failover's toast names the reason; the only signal when the loading screen
    // is off. Existing Toast pattern (Arshad 2026-08-30: a silent swap confuses).
    LaunchedEffect(uiState.failoverNotice) {
        val notice = uiState.failoverNotice ?: return@LaunchedEffect
        android.widget.Toast.makeText(
            context,
            PlaybackFailureCopy.failoverToast(notice.mark, contextFailureStrings(context)),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    LaunchedEffect(episodeStreamsShown) { if (!episodeStreamsShown) episodeSheet = null }
    val handleBackPress = handleBackPress@{
        if (externalHandoffInProgress) return@handleBackPress
        if (uiState.networkTestHold != null) {
            // Spec 33 D6: Back skips the network test this time; the film starts now.
            viewModel.onEvent(PlayerEvent.OnSkipNetworkTest)
        } else if (shouldConfirmNextEpisodeOnEnd) {
            returnToDetailsFromEndPrompt()
        } else if (uiState.error != null) {
            exitPlayerFromError()
        } else if (uiState.runtimeMismatch != null) {
            // Spec 35: Back on the runtime warning = Pick another stream.
            viewModel.onEvent(PlayerEvent.OnRuntimeMismatchChoice(playAnyway = false))
        } else if (uiState.showAudioOverlay || uiState.showSubtitleOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
        } else if (uiState.showSpeedPanel || uiState.showChaptersPanel) {
            trayOpenOn = if (uiState.showSpeedPanel) TrayPill.SPEED else TrayPill.CHAPTERS
            viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
        } else if (uiState.showPauseOverlay) {
            val detail = pauseCastDetail
            if (detail != null) {
                // Person view -> overview, that chip focused (stage 11c).
                pauseCastDetail = null
                pauseCastFocusRequest = detail
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
            }
        } else if (uiState.showMoreDialog) {
            closeTray()
        } else if (uiState.showSubtitleTimingDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog)
        } else if (uiState.showSubtitleDelayOverlay) {
            viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
        } else if (uiState.showSourcesPanel) {
            if (sourcesSheet != null) {
                sourcesSheet = null
            } else if (uiState.currentStreamUrl.isNullOrBlank()) {
                exitPlayer()
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
            }
        } else if (uiState.showEpisodesPanel) {
            if (uiState.showEpisodeStreams && episodeSheet != null) {
                episodeSheet = null
            } else if (uiState.showEpisodeStreams) {
                viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams)
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel)
            }
        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay) {
            viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
            // Transfer focus to skip button if it's still visible; else back to the
            // player surface so the D-pad keeps working (Spec 36/S10, B7-17).
            if (skipButtonActuallyVisible) {
                runCatching { skipIntroFocusRequester.requestFocus() }
            } else {
                runCatching { containerFocusRequester.requestFocus() }
            }
        } else if (skipButtonActuallyVisible && !uiState.showControls) {
            viewModel.onEvent(PlayerEvent.OnDismissSkipIntro)
        } else if (uiState.postPlayMode is PostPlayMode.StillWatching) {
            viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt)
        } else if (uiState.showControls) {
            viewModel.hideControls()
        } else if (uiState.showPlaybackStatsOverlay) {
            viewModel.onEvent(PlayerEvent.OnHidePlaybackStats)
        } else {
            exitPlayer()
        }
    }

    BackHandler {
        handleBackPress()
    }

    LaunchedEffect(uiState.playbackEnded, uiState.error, uiState.pendingExitReason, shouldConfirmNextEpisodeOnEnd) {
        val explicitReason = uiState.pendingExitReason
        // A Previous press still finding a source (Spec 45) holds the end like a Next search does. Not
        // a key, as postPlayMode is not: a search ending in the sources panel must leave the ended
        // state held under it (a re-run would dispatch Next / exit over the panel).
        val shouldDispatchNatural = uiState.playbackEnded &&
            uiState.error == null &&
            uiState.postPlayMode?.blocksNaturalCompletion() != true &&
            uiState.previousEpisodeSearch == null &&
            !shouldConfirmNextEpisodeOnEnd &&
            explicitReason == null
        when {
            explicitReason == PlayerExitReason.StillWatchingPrompt -> {
                viewModel.stopAndRelease()
                val cb = currentOnPlaybackEnded
                if (cb != null) {
                    cb(null, null, null, PlayerExitReason.StillWatchingPrompt)
                } else {
                    currentOnBackPress(
                        uiState.currentVideoId,
                        uiState.currentSeason,
                        uiState.currentEpisode,
                        uiState.backTreatsSwitchAsAutoPlay,
                        true
                    )
                }
                viewModel.consumePendingExitReason()
            }
            shouldDispatchNatural -> {
                viewModel.stopAndRelease()
                val next = uiState.nextEpisode?.takeIf { it.hasAired }
                val cb = currentOnPlaybackEnded
                if (cb != null) {
                    cb(next?.videoId, next?.season, next?.episode, null)
                } else {
                    currentOnBackPress(
                        uiState.currentVideoId,
                        uiState.currentSeason,
                        uiState.currentEpisode,
                        uiState.backTreatsSwitchAsAutoPlay,
                        true
                    )
                }
            }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.pauseForLifecycle()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Re-create the MediaSession so media controls work in foreground.
                    // Don't auto-resume playback — let the user press play.
                    viewModel.resumeForLifecycle()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bump UI thread priority to THREAD_PRIORITY_DISPLAY (-4) while the player is active.
    // The Linux scheduler favors the thread under CPU pressure (background addon prefetch,
    // Trakt sync, image decode), reducing dropped frames at scene cuts and during decoder
    // spin-up. Restored on dispose so non-player screens stay at default priority.
    DisposableEffect(Unit) {
        val tid = android.os.Process.myTid()
        val previousPriority = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching {
            android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DISPLAY)
        }
        onDispose {
            runCatching {
                android.os.Process.setThreadPriority(tid, previousPriority)
            }
        }
    }

    // Frame rate matching lifecycle.
    // T-series Build 3 (seek-thumbnail): worker lifecycle. Toggle default OFF; P3 main-file
    // provider, <=1080p SDR only; gate + eligibility live in SeekThumbnails. Log tag ThumbWorker.
    val seekThumbsEnabled = SeekThumbnailPreferences.enabledFlow(context).collectAsState(initial = false)
    LaunchedEffect(seekThumbsEnabled.value, uiState.currentStreamUrl) {
        SeekThumbnails.stopSession()
        val thumbSourceUrl = uiState.currentStreamUrl
        if (!seekThumbsEnabled.value || thumbSourceUrl.isNullOrBlank()) return@LaunchedEffect
        SeekThumbnails.startWhenEligible(
            context = context.applicationContext,
            url = thumbSourceUrl,
            titleKey = uiState.title,
            playerProvider = { viewModel.exoPlayer }
        )
    }
    DisposableEffect(Unit) { onDispose { SeekThumbnails.stopSession() } }
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(activity) {
        viewModel.attachHostActivity(activity)
        viewModel.startInitialPlaybackIfNeeded()
    }
    DisposableEffect(activity) {
        onDispose {
            viewModel.attachHostActivity(null)
        }
    }
    LaunchedEffect(uiState.frameRateMatchingMode) {
        if (activity != null &&
            uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF
        ) {
            com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
        }
    }
    // Restore original display mode when leaving the player
    val currentFrameRateMatchingMode by rememberUpdatedState(uiState.frameRateMatchingMode)
    DisposableEffect(activity) {
        onDispose {
            // Spec 33 D7: Settings from the Network chip comes back to this film, so its display
            // mode stays; the restore runs when the player itself is left.
            if (activity != null && !leavingForSettings) {
                if (currentFrameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP) {
                    com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
                } else {
                    com.nuvio.tv.core.player.FrameRateUtils.clearOriginalDisplayMode()
                }
            }
        }
    }

    // Request focus for key events when controls visibility or panel state changes
    LaunchedEffect(
        uiState.showControls,
        uiState.showEpisodesPanel,
        uiState.showSourcesPanel,
        uiState.showSubtitleDelayOverlay,
        uiState.showSubtitleTimingDialog,
        uiState.showAudioOverlay,
        uiState.showSubtitleOverlay,
        uiState.showSpeedPanel,
        uiState.showChaptersPanel,
        shouldConfirmNextEpisodeOnEnd,
        uiState.runtimeMismatch,
    ) {
        // The end prompt and the runtime warning focus their own buttons.
        if (shouldConfirmNextEpisodeOnEnd || uiState.runtimeMismatch != null) return@LaunchedEffect
        if (uiState.showControls && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog &&
            !uiState.showSpeedPanel &&
            !uiState.showChaptersPanel &&
            // Back from Speed / Chapters reopens the tray, which focuses its own pill.
            !uiState.showMoreDialog
        ) {
            // Wait for AnimatedVisibility to finish, then focus the button that opened the closed
            // panel, else Play/Pause (a requester not yet attached throws: runCatching).
            kotlinx.coroutines.delay(250)
            val opener = panelOpenerFocusRequester
            panelOpenerFocusRequester = null
            if (opener == null || !runCatching { opener.requestFocus() }.getOrDefault(false)) {
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        } else if (!uiState.showControls) {
            if (!uiState.showEpisodesPanel && !uiState.showSourcesPanel) panelOpenerFocusRequester = null
            // When controls are hidden, let skip intro button take focus if visible
            val skipVisible = uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed
            val nextEpisodeVisible = uiState.postPlayMode is PostPlayMode.AutoPlay
            if (!skipVisible && !nextEpisodeVisible) {
                try {
                    containerFocusRequester.requestFocus()
                } catch (e: Exception) {
                    // Focus requester may not be ready yet
                }
            }
            // If skip or next episode card is visible, their own LaunchedEffect will request focus
        }
    }

    // Initial focus on container - the LaunchedEffect above will handle focusing controls
    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }
    LaunchedEffect(uiState.showSubtitleDelayOverlay) {
        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
    }
    LaunchedEffect(uiState.showMoreDialog) {
        if (!uiState.showMoreDialog) trayOpenOn = null
    }
    // The pause overlay owns focus on the container until Down: the controls/panels refocus effect
    // skips the container while a Skip Intro interval is active, leaving focus to the pill this
    // overlay hides. Each show starts clean; the open person view stays through the exit fade (no
    // overview flash), but a pending chip request is dropped and a focused chip hands focus back
    // at once, since a resume without a key (MediaSession, engine) shows no controls to take it.
    LaunchedEffect(uiState.showPauseOverlay) {
        // Back from the Settings the Network chip opened: focus returns to the chip. One-shot,
        // whichever way the film comes back (the overlay may be off by then).
        val toChip = focusNetworkChipOnReturn && networkChipText != null
        focusNetworkChipOnReturn = false
        if (uiState.showPauseOverlay) {
            pauseCastFocused = null
            pauseCastDetail = null
            pauseCastFocusRequest = null
            pauseNetworkFocused = false
            if (!toChip || !runCatching { networkChipFocusRequester.requestFocus() }.getOrDefault(false)) {
                runCatching { containerFocusRequester.requestFocus() }
            }
        } else {
            pauseCastFocusRequest = null
            if (pauseCastFocused != null || pauseNetworkFocused) runCatching { containerFocusRequester.requestFocus() }
        }
    }
    // Spec 33 D7 (stage t-notice): the network test's result over the film, once, when it plays
    // (with the loading overlay setting off, that screen can be gone before any picture); the app
    // toast (MarqueeToastHost) at NETWORK_TEST_NOTICE_MS.
    val marqueeToast = LocalMarqueeToast.current
    val noticeDue = uiState.networkTestNoticePending && uiState.isPlaying &&
        !uiState.showLoadingOverlay && uiState.error == null
    LaunchedEffect(noticeDue, networkTestNotice) {
        val notice = networkTestNotice
        if (!noticeDue || notice == null) return@LaunchedEffect
        marqueeToast?.show(notice, NETWORK_TEST_NOTICE_MS)
        viewModel.consumeNetworkTestNotice()
    }
    LaunchedEffect(uiState.showSubtitleTimingDialog) {
        if (!uiState.showSubtitleTimingDialog) {
            subtitleTimingConsumeNextConfirmKeyUp = false
        }
    }
    // Closing the More tray: focus sits on a tray pill as the tray fades out, and the
    // controls/panels refocus effect (keyed on showControls and the panel flags) does not key on
    // showMoreDialog (that would also take focus on open). Move it to More now and once more
    // after the exit animation (250 ms settle, as that effect).
    LaunchedEffect(focusMoreAfterTrayClose) {
        if (!focusMoreAfterTrayClose) return@LaunchedEffect
        runCatching { moreFocusRequester.requestFocus() }
        delay(250)
        if (!uiState.showMoreDialog && uiState.showControls && uiState.error == null) {
            runCatching { moreFocusRequester.requestFocus() }
        }
        focusMoreAfterTrayClose = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(containerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // Consume the confirm KEY_UP that opened the subtitle timing dialog before
                // the newly focused "Sync" button can treat it as a second click. Preview
                // is required: after open, focus moves into the dialog so onKeyEvent on
                // this container no longer receives the release.
                if (subtitleTimingConsumeNextConfirmKeyUp &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    subtitleTimingConsumeNextConfirmKeyUp = false
                    return@onPreviewKeyEvent true
                }

                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    return@onPreviewKeyEvent when (keyEvent.nativeKeyEvent.action) {
                        KeyEvent.ACTION_DOWN -> true
                        KeyEvent.ACTION_UP -> {
                            handleBackPress()
                            true
                        }
                        else -> true
                    }
                }

                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_CAPTIONS) {
                    return@onPreviewKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent true
                }

                if (uiState.showSubtitleDelayOverlay) {
                    viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
                } else if (
                    !uiState.showEpisodesPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleTimingDialog &&
                    !uiState.showSpeedPanel &&
                    !uiState.showChaptersPanel
                ) {
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay)
                }
                true
            }
            .onKeyEvent { keyEvent ->
                // KEY_UP confirm for Sync Line is consumed in onPreviewKeyEvent so it still
                // runs after focus moves into the timing dialog.
                if (uiState.showSubtitleDelayOverlay) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (subtitleDelayFocusTarget) {
                            SubtitleDelayFocusTarget.SLIDER -> {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS))
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS))
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                            SubtitleDelayFocusTarget.RESET -> {
                                val towardSyncLine = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                }
                                val awayFromSyncLine = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                }
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        viewModel.onEvent(PlayerEvent.OnResetSubtitleDelay())
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    towardSyncLine -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    awayFromSyncLine -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                            SubtitleDelayFocusTarget.SYNC_LINE -> {
                                val towardReset = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                }
                                val awayFromReset = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                }
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        // KEY_DOWN open: swallow the trailing KEY_UP so the
                                        // dialog's Sync control does not treat it as a click.
                                        subtitleTimingConsumeNextConfirmKeyUp = true
                                        viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    towardReset -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.RESET
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    awayFromReset -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                        }
                    }
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        return@onKeyEvent true
                    }
                    if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
                        // While open, consume all non-back keys to avoid accidental dismissal.
                        return@onKeyEvent true
                    }
                }

                // When a side panel or dialog is open, let it handle all keys
                val panelOrDialogOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
                        uiState.showAudioOverlay || uiState.showSubtitleOverlay ||
                        uiState.showSpeedPanel || uiState.showChaptersPanel ||
                        uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
                        uiState.showMoreDialog ||
                        shouldConfirmNextEpisodeOnEnd ||
                        uiState.runtimeMismatch != null ||
                        // Spec 75 S4: the error box's two buttons take D-pad Left/Right (no seek).
                        uiState.error != null ||
                        uiState.postPlayMode is PostPlayMode.StillWatching
                if (panelOrDialogOpen) return@onKeyEvent false

                if (uiState.showPauseOverlay) {
                    val action = pauseOverlayKeyAction(
                        keyCode = keyEvent.nativeKeyEvent.keyCode,
                        isKeyDown = keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN,
                        focusedChip = pauseCastFocused,
                        chipCount = pauseCast.size,
                        detailOpen = pauseCastDetail != null,
                        isRtl = isRtl,
                        hasNetworkChip = networkChipText != null,
                        networkChipFocused = pauseNetworkFocused
                    )
                    return@onKeyEvent when (action) {
                        PauseKeyAction.RESUME -> {
                            viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        PauseKeyAction.DISMISS -> {
                            viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
                            true
                        }
                        PauseKeyAction.FOCUS_CAST -> {
                            pauseCastFocusRequest = 0
                            true
                        }
                        PauseKeyAction.FOCUS_NETWORK -> {
                            runCatching { networkChipFocusRequester.requestFocus() }
                            true
                        }
                        PauseKeyAction.LEAVE_CHIPS -> {
                            runCatching { containerFocusRequester.requestFocus() }
                            true
                        }
                        PauseKeyAction.CONSUME -> true
                        PauseKeyAction.PASS -> false
                    }
                }

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                                return@onKeyEvent true
                            }
                        }
                        // Seek review F2: media FF/RW commit on release, matching
                        // the DPAD preview/commit model.
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                            return@onKeyEvent true
                        }
                    }
                    return@onKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                                true
                            } else {
                                // Let the focused button handle it
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val overlayButtonsCoexist = skipButtonActuallyVisible &&
                                uiState.postPlayMode is PostPlayMode.AutoPlay
                            if (!uiState.showControls && !overlayButtonsCoexist) {
                                val isLeft =
                                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                val deltaMs = PlayerScrubRates.deltaMsForHold(
                                    holdDurationMs = keyEvent.nativeKeyEvent.eventTime - keyEvent.nativeKeyEvent.downTime,
                                    forward = !isLeft
                                )
                                viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(deltaMs))
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                // or both skip and next-episode buttons are on screen
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!uiState.showControls) {
                                    viewModel.onEvent(PlayerEvent.OnToggleControls)
                                } else {
                                    try {
                                        progressBarFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        val skipVisible = skipButtonActuallyVisible
                                        if (skipVisible) {
                                            try {
                                                skipIntroFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay) {
                                            try {
                                                nextEpisodeFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else {
                                            viewModel.hideControls()
                                        }
                                    }
                                }
                                true
                            }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnToggleControls)
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!uiState.isPlaying) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (uiState.isPlaying) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        // Seek review F2: these previously fired a *real* seek on
                        // every ACTION_DOWN including auto-repeats - holding FF was
                        // 10-20 discrete seeks in a couple of seconds, each
                        // reopening the datasource chain (a 429 generator against
                        // per-IP CDN limiters with parallel connections on). Route
                        // through the existing preview/commit machinery instead:
                        // accumulate on repeat, one network seek on key release.
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            val isRewind =
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                            val deltaMs = PlayerScrubRates.deltaMsForHold(
                                holdDurationMs = keyEvent.nativeKeyEvent.eventTime - keyEvent.nativeKeyEvent.downTime,
                                forward = !isRewind
                            )
                            viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(deltaMs))
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        val isSelectedSubtitleAss = PlayerSubtitleUtils.isSelectedSubtitleAssOrSsa(
            uiState.selectedAddonSubtitle,
            uiState.subtitleTracks.getOrNull(uiState.selectedSubtitleTrackIndex)
        )
        val osdTokens = NuvioTheme.marquee.osd
        // One provider for the OSD, the mini seek, the post-play card and the Sources / Episodes
        // panels, outside their visibility blocks: cached per backdrop URL, so a re-show never
        // re-animates or (unloadable art) re-fetches it.
        val osdAccent = rememberAmbientAccentProvider(uiState.backdrop)
        val osdVisible = uiState.showControls && uiState.error == null &&
            !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
            uiState.runtimeMismatch == null &&
            !uiState.showSubtitleDelayOverlay &&
            !uiState.showEpisodesPanel &&
            !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay &&
            !uiState.showSubtitleOverlay &&
            !uiState.showSpeedPanel &&
            !uiState.showChaptersPanel &&
            uiState.postPlayMode !is PostPlayMode.StillWatching
        // Spec 62 H2d (D7): with Dim player controls on, everything drawn over HDR video draws at the
        // HDR brightness. Opt-in per overlay: a new overlay over the picture adds `.then(controlsDim)`.
        // PlayerOsd dims itself (it reads uiState). Outside it: the video surfaces (and the cues they
        // draw), the loading and error screens (not over the picture) and the black scrims (no RGB).
        val controlsDim = rememberPlayerControlsDim(uiState.playerControlsBrightness)
        // The post-play cards slide in by half their width: their layer takes the slide overflow.
        val postPlayDim = rememberPlayerControlsDim(uiState.playerControlsBrightness, slideOverflow = true)
        // Video Player
        if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
            MpvPlayerSurface(
                viewModel = viewModel,
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering,
                aspectMode = uiState.aspectMode,
                subtitleStyle = uiState.effectiveSubtitleStyle,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            viewModel.exoPlayer?.let { player ->
                ExoPlayerSurface(
                    player = player,
                    controller = viewModel.controller,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    aspectMode = uiState.aspectMode,
                    useLibass = uiState.useLibass,
                    libassRenderType = uiState.libassRenderType,
                    subtitleStyle = uiState.effectiveSubtitleStyle,
                    ownSsaStyle = isSelectedSubtitleAss && !uiState.useLibass,
                    onBindSubtitleView = viewModel::bindExoSubtitleView,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val isEpisode = uiState.currentSeason != null && uiState.currentEpisode != null
        // Title facts from the player's meta fetch (26/B5), shared by the loading screen and pause.
        val playerContext = LocalContext.current
        val titleGenres = remember(uiState.genres, playerContext) {
            uiState.genres.joinToString(", ") { localizedGenreLabel(playerContext, it) }.takeIf { it.isNotBlank() }
        }
        val titleImdb = formatRatingScore(uiState.imdbRating)
        val titleTmdb = formatRatingScore(uiState.tmdbRating)
        // Spec 33 D6 (stage t-hold): the network test hold shows even with the loading overlay
        // or its status turned off, since the start waits on it for a minute or two.
        val networkTestHold = uiState.networkTestHold
        val loadingVisible = (uiState.showLoadingOverlay || networkTestHold != null) && uiState.error == null
        // Spec 75 S5: the notice clears at the first frame, as the screen starts fading out; the
        // fade keeps the last one drawn so the block does not jump.
        val liveNotice = uiState.failoverNotice.takeIf { networkTestHold == null }
        val heldNotice = remember { mutableStateOf<FailoverNotice?>(null) }
        if (loadingVisible) SideEffect { heldNotice.value = liveNotice }
        val failoverNotice = if (loadingVisible) liveNotice else heldNotice.value
        MarqueeLoadingScreen(
            visible = loadingVisible,
            backdropUrl = uiState.backdrop,
            // The series name over the episode, as the OSD title.
            title = uiState.contentName?.takeIf { isEpisode } ?: uiState.title,
            logo = uiState.logo,
            contentType = uiState.contentType,
            season = uiState.currentSeason,
            episode = uiState.currentEpisode,
            episodeName = uiState.currentEpisodeTitle,
            year = uiState.releaseYear,
            accent = osdAccent,
            runtimeMinutes = uiState.runtimeMinutes,
            genres = titleGenres,
            imdb = titleImdb,
            tmdb = titleTmdb,
            eyebrowStatus = stringResource(R.string.loading_eyebrow_starting),
            headline = networkTestHold?.let { stringResource(R.string.network_test_hold_checking, it.service) },
            progressLabel = networkTestHold?.let {
                stringResource(R.string.network_test_hold_step, networkTestDisplayStep(it.step), NETWORK_TEST_STEPS)
            },
            message = if (networkTestHold != null) {
                val minutes = networkTestMinutesLeft(networkTestHold.step)
                if (minutes == 0) stringResource(R.string.network_test_hold_under_a_minute)
                else pluralStringResource(R.plurals.network_test_hold_minutes_left, minutes, minutes)
            } else {
                uiState.loadingMessage.takeIf { uiState.showPlayerLoadingStatus || uiState.isTorrentStream }
            },
            sourceLine = run {
                val provider = resolveStreamProvider(
                    streamName = uiState.currentStreamName,
                    streamDescription = null,
                    addonName = uiState.currentStreamAddonName,
                    host = null
                )
                listOfNotNull(uiState.currentStreamAddonName?.takeIf { it.isNotBlank() }, provider)
                    .distinct()
                    .joinToString(" \u00b7 ")
                    .takeIf { it.isNotBlank() }
            },
            filename = if (networkTestHold != null) stringResource(R.string.network_test_hold_note) else viewModel.currentFilename,
            progress = uiState.loadingProgress,
            reserveStatusLines = true,
            // Spec 75 S5: a failover names its reason whatever the Loading status setting says.
            notice = failoverNotice?.let { notice ->
                remember(notice, playerContext) { PlaybackFailureCopy.failoverLine(notice, contextFailureStrings(playerContext)) }
            },
            footer = failoverNotice?.previousSteps?.let { steps -> @Composable { PreviousSourceStepLine(steps) } },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
        )

        PauseOverlay(
            visible = uiState.showPauseOverlay && uiState.error == null && !uiState.showLoadingOverlay &&
                uiState.runtimeMismatch == null,
            title = uiState.title,
            logo = uiState.logo,
            episodeTitle = uiState.currentEpisodeTitle,
            season = uiState.currentSeason,
            episode = uiState.currentEpisode,
            year = uiState.releaseYear,
            runtimeMinutes = uiState.runtimeMinutes,
            genres = titleGenres,
            imdb = titleImdb,
            tmdb = titleTmdb,
            description = uiState.description,
            cast = pauseCast,
            castDetailIndex = pauseCastDetail,
            castFocusRequest = pauseCastFocusRequest,
            accent = osdAccent,
            onCastFocusChanged = { index, focused ->
                if (focused) {
                    pauseCastFocused = index
                } else if (pauseCastFocused == index) {
                    pauseCastFocused = null
                }
            },
            onCastFocusRequestHandled = { pauseCastFocusRequest = null },
            onCastClick = { index ->
                // Focus leaves the chip before it leaves composition with the overview.
                runCatching { containerFocusRequester.requestFocus() }
                pauseCastDetail = index
            },
            networkChipText = networkChipText,
            networkChipFocusRequester = networkChipFocusRequester,
            onNetworkChipFocusChanged = { pauseNetworkFocused = it },
            onNetworkChipClick = {
                onOpenNetworkSettings?.let { open ->
                    leavingForSettings = true
                    focusNetworkChipOnReturn = true
                    open()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.5f)
                .then(controlsDim)
        ) {
            OsdClockHost(viewModel = viewModel, playbackSpeed = uiState.playbackSpeed)
        }

        // Live playback stats overlay (task 2.2 / nt26), sampled at ~1 Hz while
        // visible. The sample lives in local state so the 1 Hz tick recomposes
        // only this small subtree, not anything that reads uiState. Ping (a TCP
        // connect RTT on the IO dispatcher, up to 2 s) runs in its own 5 s loop so a
        // slow connect never stalls the 1 Hz sample (Register A2 P14); both loops
        // resume on the main thread, so lastPingMs needs no synchronisation.
        var playbackStatsSample by remember { mutableStateOf<PlaybackStatsSample?>(null) }
        val statsHostView = LocalView.current
        LaunchedEffect(uiState.showPlaybackStatsOverlay) {
            if (!uiState.showPlaybackStatsOverlay) {
                playbackStatsSample = null
                return@LaunchedEffect
            }
            var lastPingMs: Long? = null
            launch {
                while (true) {
                    lastPingMs = viewModel.samplePing()
                    delay(5_000L)
                }
            }
            while (true) {
                val statsDisplay = statsHostView.display
                // Active Display.Mode, not DisplayMetrics/Configuration: on Amlogic-class
                // boxes the app framebuffer commonly renders at 1080p while HDMI outputs
                // 4K — the metrics APIs report the framebuffer; Display.getMode() reports
                // the negotiated output mode, the same object AFR switches through
                // preferredDisplayModeId, so the row stays self-consistent with its dot.
                val statsActiveMode = statsDisplay?.mode
                val refreshRateHz = statsActiveMode?.refreshRate
                // Modes on offer at the current resolution. When there is only one, no
                // app-side mechanism can change the display rate — preferredDisplayModeId
                // has nothing to switch to — so the HUD must not judge the rate as if the
                // app could have fixed it.
                val displayRateOptions = statsDisplay?.let { d ->
                    val active = d.mode
                    d.supportedModes.count {
                        it.physicalWidth == active.physicalWidth &&
                            it.physicalHeight == active.physicalHeight
                    }
                }
                playbackStatsSample = viewModel.samplePlaybackStats(
                    refreshRateHz,
                    displayRateOptions,
                    statsActiveMode?.physicalWidth,
                    statsActiveMode?.physicalHeight,
                    lastPingMs
                )
                delay(1_000L)
            }
        }
        // Seek preview bubble (thumbnail + time over the playhead) during a preview seek.
        SeekPreviewBubbleHost(
            uiState = uiState,
            viewModel = viewModel,
            osdVisible = osdVisible,
            modifier = Modifier.zIndex(2.65f).then(controlsDim)
        )

        // While the OSD shows, the panel ends above its tray and seek bar (clipped; the full
        // panel returns when the controls hide). The OSD hides its title + badges meanwhile.
        PlaybackStatsOverlay(
            visible = uiState.showPlaybackStatsOverlay && uiState.error == null,
            sample = playbackStatsSample,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = osdTokens.statsInset,
                    top = osdTokens.statsInset,
                    bottom = if (osdVisible) osdTokens.statsBottomInset else osdTokens.statsInset
                )
                .clipToBounds()
                .zIndex(2.7f)
                .then(controlsDim)
        )

        // Buffering indicator — isolated in its own composable scope so that
        // isBuffering state changes only recompose this small subtree instead
        // of the entire PlayerScreen.
        PlayerBufferingIndicator(
            isBuffering = uiState.isBuffering,
            showLoadingOverlay = uiState.showLoadingOverlay,
            isTorrentStream = uiState.isTorrentStream,
            torrentBufferingMessage = uiState.torrentBufferingMessage,
            torrentBufferingProgress = uiState.torrentBufferingProgress,
            contentModifier = controlsDim
        )

        // Playback error (sandbox u6 stage 15, Spec 75 S4): the message block over the backdrop
        // and veil, the cause in plain words + step line; Spec 86 R3: the cause's fix buttons lead
        // the row, then Go Back (it exits as built; Back does the same, handleBackPress) and Details.
        uiState.error?.let { failure ->
            LaunchedEffect(failure) { viewModel.onFailureBoxShown() }
            // Re-read when the list lands or a source is marked dead (Next source), or the engine changes.
            val options = remember(failure, uiState.sourceAllStreams, uiState.deadSourceStreamUrls, uiState.internalPlayerEngine) {
                viewModel.failureBoxOptions()
            }
            PlaybackFailureBox(
                failure = failure,
                options = options,
                onAction = onAction@{ action ->
                    // The external handoff stops this player once its subtitles are cached.
                    if (externalHandoffInProgress) return@onAction
                    when (action) {
                        FailureBoxAction.RETRY -> viewModel.retryFromFailureBox()
                        FailureBoxAction.SWITCH_ENGINE -> viewModel.switchEngineFromFailureBox()
                        FailureBoxAction.NEXT_SOURCE -> viewModel.playNextSourceFromFailureBox()
                        FailureBoxAction.EXTERNAL_PLAYER -> openExternalPlayer()
                    }
                },
                onGoBack = exitPlayerFromError,
                backdropUrl = uiState.backdrop,
                modifier = Modifier.zIndex(3f),
                tried = uiState.exhaustedFailover.triedFor(failure)
            )
        }

        // Spec 35 runtime warning (sandbox spec-35-runtime-warning, shape A) over the paused first frame.
        val runtimeMismatch = uiState.runtimeMismatch
        if (runtimeMismatch != null && uiState.error == null) {
            RuntimeMismatchOverlay(
                mismatch = runtimeMismatch,
                title = uiState.contentName?.takeIf { isEpisode } ?: uiState.title,
                season = uiState.currentSeason,
                episode = uiState.currentEpisode,
                sourceLine = uiState.currentStreamAddonName?.takeIf { it.isNotBlank() },
                filename = viewModel.currentFilename,
                accent = osdAccent,
                onPickAnother = { viewModel.onEvent(PlayerEvent.OnRuntimeMismatchChoice(playAnyway = false)) },
                onPlayAnyway = { viewModel.onEvent(PlayerEvent.OnRuntimeMismatchChoice(playAnyway = true)) },
                modifier = Modifier.zIndex(3f).then(controlsDim)
            )
        }

        val endPromptEpisode = nextEpisodeForEndPrompt.takeIf { shouldConfirmNextEpisodeOnEnd }
        if (endPromptEpisode != null) {
            NextEpisodeEndPromptOverlay(
                nextEpisode = endPromptEpisode,
                onContinue = continueToNextEpisodeFromEndPrompt,
                onReturnToDetails = returnToDetailsFromEndPrompt,
                modifier = controlsDim
            )
        }

        // Skip pill bottom-left: above the OSD's seek bar with controls, lower without them. The
        // lift is read in the offset lambda (placement), so it never recomposes PlayerScreen.
        val skipLift by animateDpAsState(
            targetValue = if (uiState.showControls) osdTokens.skipBottomControls - osdTokens.skipBottomHidden else 0.dp,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
            label = "skipLift"
        )
        // The OSD's More tray spans that row: the pill steps aside (faded, unfocusable) while it is
        // open. Its countdown is already paused, since the tray implies visible controls.
        val skipStepsAside = osdVisible && uiState.showMoreDialog
        val skipAsideAlpha by animateFloatAsState(
            targetValue = if (skipStepsAside) 0f else 1f,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
            label = "skipAsideAlpha"
        )
        // The in-tree Speed panel (U6e) lost the Dialog window's focus trap: Left from a speed (or
        // chapter) row must not land on Skip Intro; nor may the pill take focus from the pause
        // overlay's cast row while it fades out (U6h).
        val skipIntroCanFocus = !skipStepsAside && !uiState.showSpeedPanel && !uiState.showChaptersPanel &&
            !uiState.showPauseOverlay && uiState.runtimeMismatch == null &&
            isSkipIntroCanFocus(subtitleOverlayVisible = uiState.showSubtitleOverlay)
        SkipIntroButton(
            interval = if (uiState.showPauseOverlay || uiState.showLoadingOverlay || uiState.runtimeMismatch != null) {
                null
            } else {
                uiState.activeSkipInterval
            },
            dismissed = uiState.skipIntervalDismissed,
            controlsVisible = uiState.showControls,
            // Autoplay next-episode card owns focus; subtitle menu must keep D-pad focus (#2874).
            suppressFocus = uiState.postPlayMode is PostPlayMode.AutoPlay || !skipIntroCanFocus,
            canFocus = skipIntroCanFocus,
            onSkip = {
                // Spec 36/S10 (B7-18): the skip button leaves; hand focus to Play/Pause.
                if (uiState.showControls) {
                    runCatching { playPauseFocusRequester.requestFocus() }
                }
                viewModel.onEvent(PlayerEvent.OnSkipIntro)
            },
            onVisibilityChanged = { skipButtonActuallyVisible = it },
            onFocused = { viewModel.scheduleHideControls() },
            focusRequester = skipIntroFocusRequester,
            downFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
            upFocusRequester = if (uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog) {
                if (subtitleDelayFocusTarget == SubtitleDelayFocusTarget.RESET) {
                    subtitleDelayResetFocusRequester
                } else {
                    subtitleDelaySyncLineFocusRequester
                }
            } else {
                null
            },
            rightFocusRequester = if (uiState.postPlayMode is PostPlayMode.AutoPlay) nextEpisodeFocusRequester else null,
            // Up: with controls visible the area above is display-only (stay); hidden, Up shows them.
            onUpKey = {
                if (!uiState.showControls) viewModel.onEvent(PlayerEvent.OnToggleControls)
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = osdTokens.skipBottomHidden)
                .offset { IntOffset(0, -skipLift.roundToPx()) }
                .graphicsLayer { alpha = skipAsideAlpha }
                .zIndex(2.1f)
                .then(controlsDim)
        )
        // The post-play card (Next, still watching, Spec 45's Previous search) yields to these.
        val postPlayCardAllowed = uiState.error == null &&
            !shouldConfirmNextEpisodeOnEnd &&
            !uiState.showLoadingOverlay &&
            !uiState.showPauseOverlay &&
            !uiState.showEpisodesPanel &&
            !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay &&
            !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog &&
            !uiState.showSpeedPanel &&
            !uiState.showChaptersPanel &&
            !uiState.showMoreDialog
        val postPlayCardModifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(
                end = NuvioTheme.layout.tvSafeHorizontal,
                bottom = if (uiState.showControls) osdTokens.postPlayBottomControls else 30.dp
            )
            .zIndex(2.1f)
            .then(postPlayDim)
        PreviousEpisodeSearchCard(
            episode = uiState.previousEpisodeSearch.takeIf { postPlayCardAllowed },
            blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
            accent = osdAccent,
            modifier = postPlayCardModifier,
        )
        PostPlayOverlay(
            mode = uiState.postPlayMode.takeIf { postPlayCardAllowed },
            controlsVisible = uiState.showControls,
            blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
            accent = osdAccent,
            nextEpisodeFocusRequester = nextEpisodeFocusRequester,
            progressBarFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
            leftFocusRequester = if (skipButtonActuallyVisible) skipIntroFocusRequester else null,
            onPlayNext = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
            onContinueStillWatching = { viewModel.onEvent(PlayerEvent.OnStillWatchingContinue) },
            onDismissStillWatching = { viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt) },
            modifier = postPlayCardModifier,
        )

        // Parental guide overlay (shows when video first starts playing)
        ParentalGuideOverlay(
            warnings = uiState.parentalWarnings,
            isVisible = uiState.showParentalGuide,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnParentalGuideHide)
            },
            modifier = Modifier.align(Alignment.TopStart).then(controlsDim)
        )

        DisplayModeOverlay(
            info = uiState.displayModeInfo,
            // nt5: gate on !showLoadingOverlay so the badge (and its 5 s timer)
            // only start once the picture is up. Preflight AFR sets
            // showDisplayModeInfo before playback begins; without this gate the
            // timer runs and expires over the loading screen on slow (e.g.
            // debrid) starts, so the badge is already gone by the time the
            // first frame renders.
            isVisible = uiState.showDisplayModeInfo && !uiState.showLoadingOverlay,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnHideDisplayModeInfo)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2.2f)
                .then(controlsDim)
        )

        // Degraded toast (data st8): once per playback, 6 s, once the picture is up (as above).
        DegradedNoticeToast(
            message = uiState.degradedNotice,
            visible = !uiState.showLoadingOverlay,
            onShown = { viewModel.onEvent(PlayerEvent.OnDegradedNoticeShown(it)) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = NuvioTheme.marquee.osd.toastTop)
                .zIndex(2.7f)
                .then(controlsDim)
        )

        // Player OSD (wave U3b, PlayerOsd.kt): top band (title, tech badges, clock) + seek bar,
        // times, transport row with Previous / Next at its ends (Spec 45), More tray.
        PlayerOsd(
            visible = osdVisible,
            uiState = uiState,
            viewModel = viewModel,
            accent = osdAccent,
            playPauseFocusRequester = playPauseFocusRequester,
            seekBarFocusRequester = progressBarFocusRequester,
            moreFocusRequester = moreFocusRequester,
            episodesButtonFocusRequester = episodesButtonFocusRequester,
            sourcesButtonFocusRequester = sourcesButtonFocusRequester,
            seekBarUpFocusRequester = when {
                skipButtonActuallyVisible -> skipIntroFocusRequester
                uiState.postPlayMode is PostPlayMode.AutoPlay -> nextEpisodeFocusRequester
                else -> null
            },
            trayOpenOn = trayOpenOn,
            onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
            onSeekBack = { viewModel.onEvent(PlayerEvent.OnSeekBackward) },
            onSeekForward = { viewModel.onEvent(PlayerEvent.OnSeekForward) },
            onShowSubtitles = { viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay) },
            onShowAudio = { viewModel.onEvent(PlayerEvent.OnShowAudioOverlay) },
            onShowEpisodes = {
                panelOpenerFocusRequester = episodesButtonFocusRequester
                viewModel.onEvent(PlayerEvent.OnShowEpisodesPanel)
            },
            onShowSources = {
                panelOpenerFocusRequester = sourcesButtonFocusRequester
                viewModel.onEvent(PlayerEvent.OnShowSourcesPanel)
            },
            onToggleTray = {
                if (uiState.showMoreDialog) closeTray() else viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
            },
            onCloseTray = closeTray,
            onStartOver = {
                viewModel.onEvent(PlayerEvent.OnSeekTo(0L))
                if (!uiState.isPlaying) viewModel.onEvent(PlayerEvent.OnPlayPause)
                closeTray()
            },
            onShowSpeed = { viewModel.onEvent(PlayerEvent.OnShowSpeedPanel) },
            onShowChapters = { viewModel.onEvent(PlayerEvent.OnShowChaptersPanel) },
            onToggleAspect = { viewModel.onEvent(PlayerEvent.OnToggleAspectRatio) },
            onToggleStats = { viewModel.onEvent(PlayerEvent.OnTogglePlaybackStats) },
            onSwitchEngine = { viewModel.onEvent(PlayerEvent.OnSwitchInternalPlayerEngine) },
            onPlayNextEpisode = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
            onPlayPreviousEpisode = { viewModel.onEvent(PlayerEvent.OnPlayPreviousEpisode) },
            onOpenExternalPlayer = openExternalPlayer,
            onInteraction = {
                viewModel.scheduleHideControls()
                viewModel.onUserInteraction()
            },
            onHideControls = { viewModel.hideControls() }
        )

        // Aspect ratio indicator (floating pill)
        AnimatedVisibility(
            visible = uiState.showAspectRatioIndicator,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .then(controlsDim)
        ) {
            AspectRatioIndicator(text = uiState.aspectRatioIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showStreamSourceIndicator,
            enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 128.dp)
                .then(controlsDim)
        ) {
            StreamSourceIndicator(text = uiState.streamSourceIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showPlayerEngineSwitchInfo && uiState.error == null,
            enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            modifier = Modifier
                .align(Alignment.Center)
                .zIndex(2.35f)
                .then(controlsDim)
        ) {
            PlayerEngineSwitchIndicator(
                title = stringResource(R.string.player_engine_switching_title),
                message = uiState.playerEngineSwitchInfoText
            )
        }

        // Subtitle delay strip (Spec 25 stage 8), shown with the controls hidden
        AnimatedVisibility(
            visible = uiState.showSubtitleDelayOverlay &&
                !uiState.showControls &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleTimingDialog &&
                !uiState.showSpeedPanel &&
                !uiState.showChaptersPanel,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = NuvioTheme.marquee.playerPanel.stripTop)
                .zIndex(2.3f)
                .then(controlsDim)
        ) {
            SubtitleDelayStrip(
                subtitleDelayMs = uiState.subtitleDelayMs,
                glow = osdAccent,
                isResetButtonFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.RESET,
                isSyncLineButtonFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.SYNC_LINE,
                isSliderFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.SLIDER,
                resetFocusRequester = subtitleDelayResetFocusRequester,
                syncLineFocusRequester = subtitleDelaySyncLineFocusRequester,
                onResetFocused = { subtitleDelayFocusTarget = SubtitleDelayFocusTarget.RESET },
                onSyncLineFocused = { subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE },
                onResetDelay = {
                    viewModel.onEvent(PlayerEvent.OnResetSubtitleDelay())
                    subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                },
                onOpenSyncByLine = {
                    subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                    // The button's onClick already runs on confirm KEY_UP — no trailing release
                    // to swallow. KEY_DOWN open (SYNC_LINE branch above) sets the flag.
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                }
            )
        }

        AnimatedVisibility(
            visible = uiState.showSeekOverlay && !uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
                !uiState.showMoreDialog,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter).then(controlsDim)
        ) {
            OsdMiniSeek(viewModel = viewModel, chapters = uiState.chapters, glow = osdAccent)
        }

        // Episodes/streams side panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            // Scrim (fades in/out, no slide)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Panel itself (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            ),
            modifier = controlsDim
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Sandbox u6 stages 2-3: the episode list, or (OK on an episode) that episode's
                // sources in the shared panel, first result focused. Back steps sources -> list ->
                // closed (handleBackPress), so neither draws a Close or Back button.
                val panelModifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(NuvioTheme.marquee.stream.panelInset)
                if (uiState.showEpisodeStreams) {
                    val season = uiState.episodeStreamsSeason
                    val episode = uiState.episodeStreamsEpisode
                    val episodeCode = if (season != null && episode != null) {
                        stringResource(R.string.season_episode_format, season, episode)
                    } else {
                        null
                    }
                    StreamSourcesPanel(
                        isLoading = uiState.isLoadingEpisodeStreams,
                        error = uiState.episodeStreamsError,
                        view = uiState.episodeListView,
                        availableAddons = uiState.episodeAvailableAddons,
                        sourceChips = uiState.episodeSourceChips,
                        showFileSizeBadges = uiState.showFileSizeBadges,
                        useAddonFormatting = uiState.useAddonFormatting,
                        failureMarkFor = episodeMarkFor,
                        accent = osdAccent,
                        sheet = episodeSheet,
                        onSheetChange = { episodeSheet = it },
                        returnFocusOnSheetClose = episodeStreamsShown,
                        onListSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeListSelected(it)) },
                        onFilterChange = { viewModel.onEvent(PlayerEvent.OnEpisodeFilterChanged(it)) },
                        saved = savedStreamFilters,
                        onSaveFilter = viewModel::saveStreamFilter,
                        onTogglePin = viewModel::toggleStreamPin,
                        onRefresh = { viewModel.onEvent(PlayerEvent.OnReloadEpisodeStreams) },
                        onStreamSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeStreamSelected(it)) },
                        onRetry = { viewModel.onEvent(PlayerEvent.OnReloadEpisodeStreams) },
                        // Episode rows: the preferred source's menu (Spec 65 P6).
                        onPreferSource = if (viewModel.canPreferSource) {
                            { viewModel.onEvent(PlayerEvent.OnPreferSource(it)) }
                        } else {
                            null
                        },
                        contextParts = listOfNotNull(episodeCode, uiState.episodeStreamsTitle?.takeIf { it.isNotBlank() }),
                        missingUpstreams = uiState.episodeMissingUpstreams,
                        modifier = panelModifier
                    )
                } else {
                    EpisodesPanel(
                        uiState = uiState,
                        playingProgress = {
                            val timeline = viewModel.playbackTimeline.value
                            timeline.duration.takeIf { it > 0 }?.let { timeline.currentPosition.toFloat() / it }
                        },
                        accent = osdAccent,
                        onSeasonSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSeasonSelected(it)) },
                        onEpisodeSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSelected(it)) },
                        modifier = panelModifier
                    )
                }
            }
        }

        // Sources panel scrim
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Sources panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            ),
            modifier = controlsDim
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Sandbox u6 stage 1: the U5 panel; the playing source carries "Playing" and takes
                // focus (N6); Back closes it (handleBackPress), so it draws no Close button.
                val playingRow = findCurrentStreamIndex(
                    streams = uiState.sourceListView.streams,
                    currentStreamInfoHash = uiState.currentStreamInfoHash,
                    currentStreamFileIdx = uiState.currentStreamFileIdx,
                    currentStreamAddonName = uiState.currentStreamAddonName,
                    currentStreamUrl = uiState.currentStreamUrl
                )
                StreamSourcesPanel(
                    isLoading = uiState.isLoadingSourceStreams,
                    error = uiState.sourceStreamsError,
                    view = uiState.sourceListView,
                    availableAddons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    showFileSizeBadges = uiState.showFileSizeBadges,
                    useAddonFormatting = uiState.useAddonFormatting,
                    marker = SourceRowMarker.PLAYING,
                    markedIndex = playingRow,
                    focusMarkedRow = true,
                    accent = osdAccent,
                    sheet = sourcesSheet,
                    onSheetChange = { sourcesSheet = it },
                    // The panel slides out still composed: a sheet cleared then must not pull focus back in.
                    returnFocusOnSheetClose = sourcesPanelShown,
                    onListSelected = { viewModel.onEvent(PlayerEvent.OnSourceListSelected(it)) },
                    onFilterChange = { viewModel.onEvent(PlayerEvent.OnSourceFilterChanged(it)) },
                    saved = savedStreamFilters,
                    onSaveFilter = viewModel::saveStreamFilter,
                    onTogglePin = viewModel::toggleStreamPin,
                    onRefresh = { viewModel.onEvent(PlayerEvent.OnReloadSourceStreams) },
                    onStreamSelected = { viewModel.onEvent(PlayerEvent.OnSourceStreamSelected(it)) },
                    onRetry = { viewModel.onEvent(PlayerEvent.OnReloadSourceStreams) },
                    // Only while an episode plays: a film's rows get no menu (Spec 65 D16).
                    onPreferSource = if (viewModel.canPreferSource && uiState.currentSeason != null && uiState.currentEpisode != null) {
                        { viewModel.onEvent(PlayerEvent.OnPreferSource(it)) }
                    } else {
                        null
                    },
                    failureMarkFor = sourceMarkFor,
                    missingUpstreams = uiState.sourceMissingUpstreams,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(NuvioTheme.marquee.stream.panelInset)
                )
            }
        }

        // Audio track dialog
        AudioSelectionOverlay(
            visible = uiState.showAudioOverlay,
            tracks = uiState.audioTracks,
            selectedIndex = uiState.selectedAudioTrackIndex,
            audioDelayMs = uiState.audioDelayMs,
            audioAmplificationDb = uiState.audioAmplificationDb,
            isAmplificationAvailable = uiState.isAudioAmplificationAvailable,
            persistAmplification = uiState.persistAudioAmplification,
            centerMixLevelDb = uiState.centerMixLevelDb,
            isCenterMixAvailable = uiState.isCenterMixAvailable,
            outputMode = uiState.audioOutputMode,
            accent = osdAccent,
            onTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(it)) },
            onAudioDelayChange = { viewModel.onEvent(PlayerEvent.OnSetAudioDelayMs(it)) },
            onAudioDelayHoldStep = { viewModel.onEvent(PlayerEvent.OnStepAudioDelayHeld(it)) },
            onAudioDelayHoldEnd = { viewModel.onEvent(PlayerEvent.OnAudioDelayHoldReleased) },
            onAmplificationChange = { viewModel.onEvent(PlayerEvent.OnSetAudioAmplificationDb(it)) },
            onPersistAmplificationChange = {
                viewModel.onEvent(PlayerEvent.OnSetPersistAudioAmplification(it))
            },
            onCenterMixLevelChange = {
                viewModel.onEvent(PlayerEvent.OnSetCenterMixLevelDb(it))
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
                .then(controlsDim)
        )

        SubtitleSelectionOverlay(
            visible = uiState.showSubtitleOverlay,
            internalTracks = uiState.subtitleTracks,
            selectedInternalIndex = uiState.selectedSubtitleTrackIndex,
            addonSubtitles = uiState.addonSubtitles,
            selectedAddonSubtitle = uiState.selectedAddonSubtitle,
            subtitleStyle = uiState.editableSubtitleStyle,
            isEditingHdrStyle = uiState.isEditingHdrSubtitleStyle,
            subtitleDelayMs = uiState.subtitleDelayMs,
            installedSubtitleAddonOrder = uiState.installedSubtitleAddonOrder,
            isLoadingAddons = uiState.isLoadingAddonSubtitles,
            useLibass = uiState.useLibass,
            isUsingMpv = uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER,
            playingFileName = viewModel.currentFilename?.takeIf { it.isNotBlank() } ?: uiState.currentStreamName,
            accent = osdAccent,
            onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },
            onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },
            onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
            onEvent = { viewModel.onEvent(it) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
                .then(controlsDim)
        )

        PlayerOverlayScaffold(
            visible = uiState.showSubtitleTimingDialog &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSpeedPanel &&
                !uiState.showChaptersPanel &&
                !uiState.showMoreDialog,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.35f)
                .then(controlsDim)
        ) {
            val panelTokens = NuvioTheme.marquee.playerPanel
            // Stage 9: a flat scrim under the centred shell.
            Box(modifier = Modifier.fillMaxSize().background(panelTokens.syncScrim))
            SubtitleTimingDialogHost(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = panelTokens.syncTop),
                subtitleDelayMs = uiState.subtitleDelayMs,
                selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                cues = uiState.subtitleAutoSyncCues,
                capturedVideoMs = uiState.subtitleAutoSyncCapturedVideoMs,
                statusMessage = uiState.subtitleAutoSyncStatus,
                errorMessage = uiState.subtitleAutoSyncError,
                isLoadingCues = uiState.subtitleAutoSyncLoading,
                onCaptureNow = { viewModel.onEvent(PlayerEvent.OnCaptureSubtitleAutoSyncTime) },
                onCueSelected = { cue ->
                    viewModel.onEvent(PlayerEvent.OnApplySubtitleAutoSyncCue(cue.startTimeMs))
                }
            )
        }

        ChaptersPanel(
            visible = uiState.showChaptersPanel,
            chapters = uiState.chapters,
            positionMs = { viewModel.playbackTimeline.value.currentPosition },
            onChapterSelected = { chapter ->
                viewModel.onEvent(PlayerEvent.OnSeekTo(chapter.startMs))
                viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
                .then(controlsDim)
        )

        SpeedSelectionPanel(
            visible = uiState.showSpeedPanel,
            currentSpeed = uiState.playbackSpeed,
            onSpeedSelected = { viewModel.onEvent(PlayerEvent.OnSetPlaybackSpeed(it)) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
                .then(controlsDim)
        )
    }
}

@Composable
private fun MpvPlayerSurface(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    subtitleStyle: SubtitleStyleSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val mpvView = remember(context) {
        NuvioMpvSurfaceView(context)
    }

    AndroidView(
        factory = { mpvView },
        modifier = modifier
    )

    DisposableEffect(viewModel, mpvView) {
        viewModel.attachMpvView(mpvView)
        onDispose {
            viewModel.attachMpvView(null)
        }
    }

    DisposableEffect(mpvView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            mpvView.applyAspectMode(latestAspectMode)
        }
        mpvView.addOnLayoutChangeListener(listener)
        onDispose {
            mpvView.removeOnLayoutChangeListener(listener)
        }
    }

    LaunchedEffect(mpvView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (mpvView.keepScreenOn != shouldKeepScreenOn) {
            mpvView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(mpvView, aspectMode) {
        mpvView.applyAspectMode(aspectMode)
    }

    LaunchedEffect(mpvView, subtitleStyle) {
        mpvView.applySubtitleStyle(subtitleStyle)
    }
}

@Composable
private fun ExoPlayerSurface(
    player: ExoPlayer,
    controller: PlayerRuntimeController,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    useLibass: Boolean,
    libassRenderType: LibassRenderType,
    subtitleStyle: SubtitleStyleSettings,
    ownSsaStyle: Boolean,
    onBindSubtitleView: (androidx.media3.ui.SubtitleView?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val latestBindSubtitleView by rememberUpdatedState(onBindSubtitleView)
    val latestSubtitleStyle by rememberUpdatedState(subtitleStyle)
    val latestOwnSsaStyle by rememberUpdatedState(ownSsaStyle)
    val playerView = remember(context, player) {
        PlayerView(context).apply {
            useController = false
            keepScreenOn = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            enableComposeSurfaceSyncWorkaroundIfAvailable()
            this.player = player
        }
    }

    AndroidView(
        factory = { playerView },
        modifier = modifier,
        update = {
            it.syncLibassOverlay(
                player = player,
                enabled = useLibass,
                renderType = libassRenderType
            )
            latestBindSubtitleView(it.subtitleView)
        }
    )

    DisposableEffect(playerView, player) {
        if (playerView.player !== player) {
            playerView.player = player
        }
        latestBindSubtitleView(playerView.subtitleView)
        onDispose {
            latestBindSubtitleView(null)
            if (playerView.player === player) {
                playerView.player = null
            }
        }
    }

    DisposableEffect(playerView) {
        controller.exoPlayerView = playerView
        onDispose {
            if (controller.exoPlayerView === playerView) {
                controller.exoPlayerView = null
            }
        }
    }

    DisposableEffect(player, playerView) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                controller.videoAspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                } else {
                    0f
                }
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }

            override fun onRenderedFirstFrame() {
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Re-apply subtitle style when tracks change so style is applied
                // even when subtitles are enabled after initial player setup.
                playerView.post {
                    playerView.applySubtitleStyleIfNeeded(latestSubtitleStyle, latestOwnSsaStyle)
                }
            }
        }
        player.addListener(listener)
        playerView.post {
            playerView.applyExoAspectMode(latestAspectMode)
        }
        onDispose {
            player.removeListener(listener)
        }
    }

    DisposableEffect(playerView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            playerView.post {
                playerView.applyExoAspectMode(latestAspectMode)
            }
        }
        val removeListener = addExoAspectLayoutChangeListener(playerView, listener)
        onDispose {
            removeListener()
        }
    }

    LaunchedEffect(playerView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (playerView.keepScreenOn != shouldKeepScreenOn) {
            playerView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(playerView, aspectMode) {
        playerView.applyExoAspectMode(aspectMode)
    }

    LaunchedEffect(playerView, player, useLibass, libassRenderType) {
        playerView.syncLibassOverlay(
            player = player,
            enabled = useLibass,
            renderType = libassRenderType
        )
    }

    LaunchedEffect(playerView, subtitleStyle, ownSsaStyle) {
        playerView.applySubtitleStyleIfNeeded(subtitleStyle, ownSsaStyle)
    }
}

private fun PlayerView.enableComposeSurfaceSyncWorkaroundIfAvailable() {
    runCatching {
        javaClass
            .getMethod("setEnableComposeSurfaceSyncWorkaround", java.lang.Boolean.TYPE)
            .invoke(this, false)
    }
}

private fun PlayerView.applyExoAspectMode(mode: AspectMode) {
    setTag(R.id.player_view_aspect_mode_tag, mode)
    applyExoAspectMode(this, mode)
}

private data class SubtitleAppliedConfig(val style: SubtitleStyleSettings, val ownSsaStyle: Boolean)

private fun PlayerView.applySubtitleStyleIfNeeded(subtitleStyle: SubtitleStyleSettings, ownSsaStyle: Boolean) {
    val config = SubtitleAppliedConfig(subtitleStyle, ownSsaStyle)
    if (getTag(R.id.player_view_subtitle_style_tag) == config) {
        return
    }
    val subView = subtitleView
    if (subView == null) {
        // SubtitleView not yet available (no track selected yet). Don't set the
        // tag so that when subtitles become active the style is re-applied.
        return
    }
    setTag(R.id.player_view_subtitle_style_tag, config)
    subView.applySubtitleLook(subtitleStyle, ownSsaStyle)
}

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderType: LibassRenderType
) {
    val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
        R.id.libass_overlay_container_gl
    } else {
        R.id.libass_overlay_container
    }
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderType.usesOverlaySubtitleView()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    setTag(R.id.libass_overlay_bound_player, player)
}

private fun LibassRenderType.usesOverlaySubtitleView(): Boolean {
    return this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL
}

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleView) {
            removeViewAt(index)
        }
    }
}

@Composable
private fun SubtitleTimingDialogHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    SubtitleTimingDialog(
        modifier = modifier,
        currentPositionMs = playbackTimeline.currentPosition,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        cues = cues,
        capturedVideoMs = capturedVideoMs,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        isLoadingCues = isLoadingCues,
        onCaptureNow = onCaptureNow,
        onCueSelected = onCueSelected
    )
}

@Composable
private fun AspectRatioIndicator(text: String) {
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio, NuvioTheme.spacing.xl)

    // Floating pill indicator for aspect ratio changes
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(NuvioTheme.spacing.xl)
            )
            .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = Color(0xFF3B3B3B),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = customAspectPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

        // Text
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White
        )
    }
}

@Composable
private fun StreamSourceIndicator(text: String) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerEngineSwitchIndicator(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 22.dp, vertical = NuvioTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center
        )
    }
}

private enum class SubtitleDelayFocusTarget {
    SLIDER,
    RESET,
    SYNC_LINE
}

/** Watched far enough to count as completed on exit or external hand-off. */
private fun PlaybackTimelineState.isCompleted(): Boolean =
    duration > 0L && (currentPosition.toFloat() / duration.toFloat()) >= WatchProgress.COMPLETED_THRESHOLD

/**
 * Buffering indicator extracted into its own composable to isolate
 * recomposition scope. When [isBuffering] toggles, only this subtree
 * is recomposed — the rest of [PlayerScreen] is skipped by Compose.
 */
@Composable
private fun PlayerBufferingIndicator(
    isBuffering: Boolean,
    showLoadingOverlay: Boolean,
    isTorrentStream: Boolean,
    torrentBufferingMessage: String?,
    torrentBufferingProgress: Float,
    /** On the spinner block only, so a layer (the HDR dim) never spans the screen while it animates. */
    contentModifier: Modifier = Modifier
) {
    if (!isBuffering || showLoadingOverlay) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = contentModifier) {
            if (isTorrentStream && torrentBufferingMessage != null) {
                // Torrent rebuffer: spinner + download stats + progress bar
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadingIndicator()
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    Text(
                        text = torrentBufferingMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    if (torrentBufferingProgress > 0f) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        Box(
                            modifier = Modifier
                                .width(200.dp)
                                .height(3.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(NuvioTheme.radii.xxs)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(torrentBufferingProgress.coerceIn(0f, 1f))
                                    .height(3.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(NuvioTheme.radii.xxs)
                                    )
                            )
                        }
                    }
                }
            } else {
                LoadingIndicator()
            }
        }
    }
}
