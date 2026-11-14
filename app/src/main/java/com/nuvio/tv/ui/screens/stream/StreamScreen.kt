@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.stream

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.ExternalPlayerInput
import com.nuvio.tv.core.player.ExternalPlayerLauncher
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.domain.model.missingUpstreams
import com.nuvio.tv.ui.components.P2pConsentDialog
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeHandOffStatus
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.MarqueeTitleBlock
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.components.sources.SavedFiltersQrDialog
import com.nuvio.tv.ui.components.sources.SourceFailureMark
import com.nuvio.tv.ui.components.sources.SourceFailureMarks
import com.nuvio.tv.ui.screens.player.contextFailureStrings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.sources.SourceRowMarker
import com.nuvio.tv.ui.components.sources.SourcesPanelSheet
import com.nuvio.tv.ui.components.sources.StreamSourcesPanel
import com.nuvio.tv.ui.components.sources.lastPlayedIndex
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamScreen(
    viewModel: StreamScreenViewModel = hiltViewModel(),
    startFromBeginning: Boolean = false,
    onBackPress: () -> Unit,
    onStreamSelected: (StreamPlaybackInfo) -> Unit,
    onAutoPlayResolved: (StreamPlaybackInfo) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // F-5: the pressed stream's limitKey, so a re-derive while the player is up cannot move the restore.
    var focusedStreamKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocusedStream by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    // Back from the internal player recomposes this screen at the start of the pop transition,
    // RESUMED only at its end: armed from the saved flag on entry, the restore runs before the
    // first-result focus would take the first card (F-5). ON_RESUME still consumes the saved flag:
    // a return from another app, and a second (no-op once focused) pass if this one missed.
    var restoreOnEntry by remember { mutableStateOf(pendingRestoreOnResume) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    var pendingTorrentPlaybackInfo by remember { mutableStateOf<StreamPlaybackInfo?>(null) }
    val p2pEnabled by viewModel.p2pEnabled.collectAsStateWithLifecycle(initialValue = false)
    val showImdbRatings by viewModel.showImdbRatings.collectAsStateWithLifecycle(initialValue = false)
    val streamBadgeSettings by viewModel.streamBadgeSettings.collectAsStateWithLifecycle(
        initialValue = StreamBadgeSettings()
    )

    fun openExternalInBrowser(playbackInfo: StreamPlaybackInfo): Boolean {
        if (!playbackInfo.isExternal) return false
        val url = playbackInfo.url?.takeIf { it.isNotBlank() } ?: return false
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching {
            context.startActivity(browserIntent)
        }.onFailure {
            ExternalPlayerLauncher.launch(
                context = context,
                input = ExternalPlayerInput(
                    url = url,
                    title = playbackInfo.title,
                    headers = playbackInfo.headers,
                    startFromBeginning = startFromBeginning
                )
            )
        }
        return true
    }

    fun launchInternalPlayer(playbackInfo: StreamPlaybackInfo) {
        viewModel.onInternalPlayerLaunching()
        onStreamSelected(playbackInfo)
    }

    /** An auto-play pick (direct flow or reused link): straight to the internal player. */
    fun launchAutoResolved(playbackInfo: StreamPlaybackInfo) {
        viewModel.onInternalPlayerLaunching()
        onAutoPlayResolved(playbackInfo)
    }

    fun routePlayback(playbackInfo: StreamPlaybackInfo) {
        if (openExternalInBrowser(playbackInfo)) {
            return
        }
        if (playbackInfo.isTorrent && !p2pEnabled) {
            pendingTorrentPlaybackInfo = playbackInfo
            showP2pConsentDialog = true
            return
        }
        launchInternalPlayer(playbackInfo)
    }

    fun routeAutoPlay(playbackInfo: StreamPlaybackInfo) {
        if (openExternalInBrowser(playbackInfo)) {
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return
        }
        // Always check P2P consent for torrents, even in direct auto-play flow
        if (playbackInfo.isTorrent && !p2pEnabled) {
            pendingTorrentPlaybackInfo = playbackInfo
            showP2pConsentDialog = true
            return
        }
        if (uiState.isDirectAutoPlayFlow) {
            launchAutoResolved(playbackInfo)
        } else {
            pendingRestoreOnResume = true
            routePlayback(playbackInfo)
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    BackHandler {
        onBackPress()
    }
    // The Sources panel's open sheet (Spec 65 P1): Back closes it before it leaves the screen.
    var sourcesSheet by remember { mutableStateOf<SourcesPanelSheet?>(null) }
    val savedStreamFilters by viewModel.savedStreamFilters.collectAsStateWithLifecycle(
        initialValue = com.nuvio.tv.core.stream.SavedStreamFilters.NONE
    )
    val sourcesPanelShown = uiState.autoPlayDecided && !uiState.showDirectAutoPlayOverlay
    BackHandler(enabled = sourcesSheet != null && sourcesPanelShown) {
        sourcesSheet = null
    }
    // A sheet never outlives its panel (the direct auto-play overlay replaces it).
    LaunchedEffect(sourcesPanelShown) {
        if (!sourcesPanelShown) sourcesSheet = null
    }
    // The saved filters' phone page (Spec 65 S9) runs only while its QR shows, here in the foreground.
    val savedFiltersPhone by viewModel.savedFiltersPhone.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.closeSavedFiltersPhonePage() }
    when (val phone = savedFiltersPhone) {
        null -> Unit
        is SavedFiltersPhoneState.Open -> SavedFiltersQrDialog(
            url = phone.url,
            hostLabel = phone.host,
            error = null,
            onDismiss = viewModel::closeSavedFiltersPhonePage
        )
        is SavedFiltersPhoneState.Failed -> SavedFiltersQrDialog(
            url = null,
            hostLabel = null,
            error = stringResource(phone.message),
            onDismiss = viewModel::closeSavedFiltersPhonePage
        )
    }

    LaunchedEffect(uiState.autoPlayStream) {
        val stream = uiState.autoPlayStream ?: return@LaunchedEffect
        // User aborted the auto-next chain that navigated here — don't auto-launch; show the list.
        if (viewModel.isAutoNextContinuationAborted()) {
            viewModel.consumeAbortedAutoNextContinuation()
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return@LaunchedEffect
        }
        val playbackInfo = viewModel.playbackInfoFor(stream)
        // S1g: warm the connection while the player is built. Placed after the
        // abort check above so a cancelled auto-next chain fires no request.
        com.nuvio.tv.core.player.PlayerPlaybackNetworking
            .prewarmPlaybackConnection(playbackInfo.url, playbackInfo.headers)
        // Torrent streams have url == null but carry an infoHash; navigation
        // builds a torrent:// sentinel URL downstream.
        if (playbackInfo.hasTarget) {
            viewModel.awaitStreamLinkCacheSave()
            routeAutoPlay(playbackInfo)
        } else {
            // A pick that opens nothing (a torrent whose hash cannot be read): show the list
            // instead of leaving the direct auto-play overlay up.
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    // Once streams are resolved, release the MainActivity auto-next loader so it doesn't
    // mask this screen (whether it auto-launches a player or shows the manual list).
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            viewModel.dismissExternalAutoNextOverlay()
        }
    }

    LaunchedEffect(uiState.autoPlayPlaybackInfo) {
        val playbackInfo = uiState.autoPlayPlaybackInfo ?: return@LaunchedEffect
        // User aborted the auto-next chain that navigated here — don't auto-launch; show the list.
        if (viewModel.isAutoNextContinuationAborted()) {
            viewModel.consumeAbortedAutoNextContinuation()
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return@LaunchedEffect
        }
        if (playbackInfo.hasTarget) {
            // Torrent cached links still need P2P consent
            if (playbackInfo.isTorrent && !p2pEnabled) {
                pendingTorrentPlaybackInfo = playbackInfo
                showP2pConsentDialog = true
                return@LaunchedEffect
            }
            launchAutoResolved(playbackInfo)
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onEvent(StreamScreenEvent.OnResume)
                if (pendingRestoreOnResume) {
                    restoreFocusedStream = true
                    pendingRestoreOnResume = false
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                // Stopped (the internal player covered us, an external stream opened in another
                // app, or the app went to the background): the stuck-loader timeout waits for the
                // screen to be visible again before treating the loader as stuck.
                viewModel.onHostStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val backdropUrl = uiState.backdrop ?: uiState.poster
    val accent = rememberAmbientAccentProvider(backdropUrl)
    val lastPlayedRow = remember(uiState.filteredStreams, uiState.lastPlayed) {
        lastPlayedIndex(uiState.filteredStreams, uiState.lastPlayed)
    }
    val marquee = NuvioTheme.marquee
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        // Home's backdrop treatment (approved sandbox u5 stage 1): left, bottom and top scrims.
        MarqueeBackdropImage(url = backdropUrl, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
        MarqueeScrims(
            leftStops = marquee.scrimLeftStops,
            bottomStops = marquee.scrimBottomStops,
            drawTopScrim = true
        )

        val showOverlay = uiState.showDirectAutoPlayOverlay
        // Don't render the block or the stream list until the ViewModel decides whether direct
        // autoplay is active: prevents a single-frame flash.
        if (uiState.autoPlayDecided) {
            // Direct auto-play (sandbox u6 stage 14): the same block with the hand-off status in
            // place of the plot and no panel; the player's loading screen draws it at the same anchor.
            MarqueeTitleBlock(
                title = uiState.title,
                logo = uiState.logo,
                contentType = uiState.contentType,
                season = uiState.season,
                episode = uiState.episode,
                episodeName = uiState.episodeName,
                year = uiState.year,
                runtimeMinutes = uiState.runtime,
                genres = uiState.genres,
                imdb = formatRatingScore(uiState.imdbRating?.takeIf { showImdbRatings }),
                accent = accent,
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                if (showOverlay) {
                    MarqueeHandOffStatus(accent = accent, message = uiState.directAutoPlayMessage)
                } else {
                    StreamPlot(uiState.description)
                }
            }
            if (!showOverlay) {
                // An open sheet dims the title block too (sandbox spec-65-stream-filters stages 4, 6, 7).
                if (sourcesSheet != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(marquee.stream.sheetDim)
                    )
                }
                // Spec 75 S6: a source that failed in the last day says why (store marks, read on resume).
                val recentFailures = uiState.recentFailures
                val failureMarkFor = remember(recentFailures, context) {
                    val strings = contextFailureStrings(context)
                    val markFor: (Stream) -> SourceFailureMark? = { stream ->
                        SourceFailureMarks.forRow(stream, recentFailures, emptySet(), strings)
                    }
                    markFor
                }
                StreamSourcesPanel(
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    view = uiState.listView,
                    availableAddons = uiState.availableAddons,
                    sourceChips = uiState.sourceChips,
                    showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                    useAddonFormatting = streamBadgeSettings.useAddonFormatting,
                    marker = SourceRowMarker.LAST_PLAYED,
                    markedIndex = lastPlayedRow,
                    focusMarkedRow = false,
                    failureMarkFor = failureMarkFor,
                    missingUpstreams = uiState.addonStreams.missingUpstreams(),
                    accent = accent,
                    sheet = sourcesSheet,
                    onSheetChange = { sourcesSheet = it },
                    onListSelected = { viewModel.onEvent(StreamScreenEvent.OnListSelected(it)) },
                    onFilterChange = { viewModel.onEvent(StreamScreenEvent.OnFilterChanged(it)) },
                    saved = savedStreamFilters,
                    onSaveFilter = viewModel::saveStreamFilter,
                    onTogglePin = viewModel::toggleStreamPin,
                    onEditFiltersOnPhone = viewModel::openSavedFiltersPhonePage,
                    onPreferSource = if (viewModel.canPreferSource) viewModel::preferSource else null,
                    onRefresh = { viewModel.onEvent(StreamScreenEvent.OnRefresh) },
                    onStreamSelected = { stream ->
                        TtffTrace.begin("press_manual")
                        focusedStreamKey = stream.limitKey()
                        val playbackInfo = viewModel.playbackInfoFor(stream)
                        // An addon's info row opens nothing: the press does nothing and the search goes on.
                        if (playbackInfo.hasTarget) {
                            // S1g: warm the connection while the player is built.
                            com.nuvio.tv.core.player.PlayerPlaybackNetworking
                                .prewarmPlaybackConnection(playbackInfo.url, playbackInfo.headers)
                            pendingRestoreOnResume = true
                            routePlayback(playbackInfo)
                            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                        }
                    },
                    focusedStreamKey = focusedStreamKey,
                    shouldRestoreFocusedStream = restoreFocusedStream || restoreOnEntry,
                    onRestoreFocusedStreamHandled = {
                        restoreFocusedStream = false
                        restoreOnEntry = false
                    },
                    onRetry = { viewModel.onEvent(StreamScreenEvent.OnRetry) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(marquee.stream.panelInset)
                )
            }
        }

        if (showP2pConsentDialog && pendingTorrentPlaybackInfo != null) {
            P2pConsentDialog(
                onEnableP2p = {
                    viewModel.enableP2p()
                    showP2pConsentDialog = false
                    val info = pendingTorrentPlaybackInfo!!
                    pendingTorrentPlaybackInfo = null
                    routePlayback(info)
                },
                onDismiss = {
                    showP2pConsentDialog = false
                    pendingTorrentPlaybackInfo = null
                    // Cancelled P2P consent — fall back to manual stream selection
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                }
            )
        }

    }
}

/** The title block's two-line plot (sandbox u5 stage 1). */
@Composable
private fun StreamPlot(description: String?) {
    if (description.isNullOrBlank()) return
    Text(
        text = description,
        style = NuvioTheme.textStyles.bodyHome,
        color = NuvioTheme.marquee.onImageBody,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
