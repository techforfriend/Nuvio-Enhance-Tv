package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.P2pConsentDialog
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    /** Settings' deep entry: the chip to open (SettingsEntry), null = the pane's own choice. */
    initialSection: PlaybackSection? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.settings_playback)
    val subtitle = stringResource(R.string.playback_pane_subtitle)
    // Rows wait for every stored value: a placeholder row would drop the first toggle.
    val state = uiState ?: run {
        SettingsPane(title = title, subtitle = subtitle) {}
        return
    }
    val playerSettings = state.player
    val torrentSettings = state.torrent
    val toast = LocalMarqueeToast.current
    val seekThumbnailsClearedMessage = stringResource(R.string.playback_seek_thumbnails_cleared)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val retestQueuedMessage = stringResource(R.string.this_tv_test_queued_toast)
    val undoDoneMessage = stringResource(R.string.this_tv_undo_done_toast)
    val thisTvActions = remember(viewModel, toast, context) {
        ThisTvActions(
            onSetAutomatic = viewModel::setAutomaticNetwork,
            onRunAgain = { viewModel.requestNetworkRetest { toast?.show(retestQueuedMessage) } },
            onUndo = { viewModel.undoLastChange { restored -> if (restored) toast?.show(undoDoneMessage) } },
            onSetUpAgain = {
                viewModel.setUpThisTvAgain(context.findActivity()) { written ->
                    toast?.show(context.getString(R.string.this_tv_setup_again_done_toast, written))
                }
            },
            onSetStreamLimitMode = viewModel::setStreamLimitMode,
            onSetStreamLimitManualMbps = viewModel::setStreamLimitManualMbps,
            onSetResolutionLimitMode = viewModel::setResolutionLimitMode
        )
    }

    var showP2pConsentDialog by remember { mutableStateOf(false) }

    PlaybackSettingsSections(
        title = title,
        subtitle = subtitle,
        initialFocusRequester = initialFocusRequester,
        playerSettings = playerSettings,
        seekThumbnailsEnabled = state.seekThumbnailsEnabled,
        onSetSeekThumbnailsEnabled = viewModel::setSeekThumbnailsEnabled,
        onClearSeekThumbnailCache = {
            viewModel.clearSeekThumbnailCache { toast?.show(seekThumbnailsClearedMessage) }
        },
        onSetPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setPreferredAudioLanguage(language) }
        },
        onSetSecondaryPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setSecondaryPreferredAudioLanguage(language) }
        },
        onSetAudioOutputChannels = { channels ->
            coroutineScope.launch { viewModel.setAudioOutputChannels(channels) }
        },
        onSetDecoderPriority = { priority ->
            coroutineScope.launch { viewModel.setDecoderPriority(priority) }
        },
        onSetMpvHardwareDecodeMode = { mode ->
            coroutineScope.launch { viewModel.setMpvHardwareDecodeMode(mode) }
        },
        onSetDv7HandlingMode = { mode ->
            coroutineScope.launch { viewModel.setDv7HandlingMode(mode) }
        },
        onSetDeniedHandling = { mode ->
            coroutineScope.launch { viewModel.setDeniedCodecHandling(mode) }
        },
        onSetSubtitlePreferredLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitlePreferredLanguage(language) }
        },
        onSetSubtitleSecondaryLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitleSecondaryLanguage(language) }
        },
        onSetSubtitleTextColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleTextColor(color) }
        },
        onSetSubtitleBackgroundColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleBackgroundColor(color) }
        },
        onSetSubtitleOutlineColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleOutlineColor(color) }
        },
        streamAddonNames = state.streamAddonNames,
        onSetStreamAutoPlaySelectedAddons = { selected ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySelectedAddons(selected) }
        },
        onSetStreamAutoPlayRegex = { regex ->
            coroutineScope.launch { viewModel.setStreamAutoPlayRegex(regex) }
        },
        onSetInternalPlayerEngine = { engine ->
            coroutineScope.launch { viewModel.setInternalPlayerEngine(engine) }
        },
        onSetStreamAutoPlayMode = { mode ->
            coroutineScope.launch { viewModel.setStreamAutoPlayMode(mode) }
        },
        onSetStreamAutoPlaySource = { source ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySource(source) }
        },
        onSetReuseLastLinkCacheHours = { hours ->
            coroutineScope.launch { viewModel.setStreamReuseLastLinkCacheHours(hours) }
        },
        onSetNextEpisodeThresholdMode = { mode ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdMode(mode) }
        },
        onSetStreamAutoPlayNextEpisodeEnabled = { enabled ->
            coroutineScope.launch { viewModel.setStreamAutoPlayNextEpisodeEnabled(enabled) }
        },
        onSetStreamAutoPlayNextEpisodeFallbackEnabled = { enabled ->
            coroutineScope.launch { viewModel.setStreamAutoPlayNextEpisodeFallbackEnabled(enabled) }
        },
        onSetStreamAutoPlayPreferBingeGroupForNextEpisode = { enabled ->
            coroutineScope.launch {
                viewModel.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
            }
        },
        onSetStreamAutoPlayReuseBingeGroup = { enabled ->
            coroutineScope.launch {
                viewModel.setStreamAutoPlayReuseBingeGroup(enabled)
            }
        },
        onSetAutoSwitchInternalPlayerOnError = { enabled ->
            coroutineScope.launch { viewModel.setAutoSwitchInternalPlayerOnError(enabled) }
        },
        onSetNextEpisodeThresholdPercent = { percent ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdPercent(percent) }
        },
        onSetNextEpisodeThresholdMinutesBeforeEnd = { minutes ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdMinutesBeforeEnd(minutes) }
        },
        onSetStreamAutoPlayTimeoutSeconds = { seconds ->
            coroutineScope.launch { viewModel.setStreamAutoPlayTimeoutSeconds(seconds) }
        },
        onSetStreamAutoPlayEagerReadyEnabled = { enabled ->
            coroutineScope.launch { viewModel.setStreamAutoPlayEagerReadyEnabled(enabled) }
        },
        onSetReuseLastLinkEnabled = { enabled -> coroutineScope.launch { viewModel.setStreamReuseLastLinkEnabled(enabled) } },
        onSetStillWatchingEnabled = { enabled ->
            coroutineScope.launch { viewModel.setStillWatchingEnabled(enabled) }
        },
        onSetStillWatchingEpisodeThreshold = { threshold ->
            coroutineScope.launch { viewModel.setStillWatchingEpisodeThreshold(threshold) }
        },
        onSetShowPlayerLoadingStatus = { enabled -> coroutineScope.launch { viewModel.setShowPlayerLoadingStatus(enabled) } },
        onSetLoadingOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setLoadingOverlayEnabled(enabled) } },
        onSetPauseOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setPauseOverlayEnabled(enabled) } },
        onSetOsdClockEnabled = { enabled -> coroutineScope.launch { viewModel.setOsdClockEnabled(enabled) } },
        onSetSkipIntroEnabled = { enabled -> coroutineScope.launch { viewModel.setSkipIntroEnabled(enabled) } },
        onSetParentalGuideEnabled = { enabled -> coroutineScope.launch { viewModel.setParentalGuideEnabled(enabled) } },
        onSetAutoSkipSegmentTypeEnabled = { segmentType, enabled ->
            coroutineScope.launch { viewModel.setAutoSkipSegmentTypeEnabled(segmentType, enabled) }
        },
        onSetFrameRateMatchingMode = { mode -> coroutineScope.launch { viewModel.setFrameRateMatchingMode(mode) } },
        onSetResolutionMatchingEnabled = { enabled ->
            coroutineScope.launch { viewModel.setResolutionMatchingEnabled(enabled) }
        },
        onSetAfrSettleHoldSeconds = { seconds ->
            coroutineScope.launch { viewModel.setAfrSettleHoldSeconds(seconds) }
        },
        onDisableAfrAndResolution = { coroutineScope.launch { viewModel.disableAfrAndResolution() } },
        onSetDownmixEnabled = { enabled ->
            coroutineScope.launch { viewModel.setDownmixEnabled(enabled) }
        },
        onSetMaintainOriginalAudioOnDownmix = { enabled ->
            coroutineScope.launch { viewModel.setMaintainOriginalAudioOnDownmix(enabled) }
        },
        onSetSkipSilence = { enabled -> coroutineScope.launch { viewModel.setSkipSilence(enabled) } },
        onSetRememberAudioDelayPerDevice = { enabled ->
            coroutineScope.launch { viewModel.setRememberAudioDelayPerDevice(enabled) }
        },
        onSetTunnelingEnabled = { enabled -> coroutineScope.launch { viewModel.setTunnelingEnabled(enabled) } },
        onSetForceOpticalPassthrough = { enabled -> coroutineScope.launch { viewModel.setForceOpticalPassthrough(enabled) } },
        onSetAllowAc3Passthrough = { allowed -> coroutineScope.launch { viewModel.setAllowAc3Passthrough(allowed) } },
        onSetAllowEac3Passthrough = { allowed -> coroutineScope.launch { viewModel.setAllowEac3Passthrough(allowed) } },
        onSetAllowTrueHdPassthrough = { allowed -> coroutineScope.launch { viewModel.setAllowTrueHdPassthrough(allowed) } },
        onSetMatPassthroughEnabled = { enabled -> coroutineScope.launch { viewModel.setMatPassthroughEnabled(enabled) } },
        onSetAllowDtsPassthrough = { allowed -> coroutineScope.launch { viewModel.setAllowDtsPassthrough(allowed) } },
        onSetAllowDtsHdPassthrough = { allowed -> coroutineScope.launch { viewModel.setAllowDtsHdPassthrough(allowed) } },
        onSetDv5ToDv81Enabled = { enabled ->
            coroutineScope.launch { viewModel.setDv5ToDv81Enabled(enabled) }
        },
        onSetNuvioPerformanceModeEnabled = { enabled ->
            coroutineScope.launch { viewModel.setNuvioPerformanceModeEnabled(enabled) }
        },
        onSetStripHdr10PlusSei = { enabled ->
            coroutineScope.launch { viewModel.setStripHdr10PlusSei(enabled) }
        },
        onSetInjectHdr10Sei = { enabled ->
            coroutineScope.launch { viewModel.setInjectHdr10MetadataOnStrip(enabled) }
        },
        onSetBufferEngineEnabled = { enabled ->
            coroutineScope.launch { viewModel.setBufferEngineEnabled(enabled) }
        },
        onSetParallelNetworkEnabled = { enabled ->
            coroutineScope.launch { viewModel.setParallelNetworkEnabled(enabled) }
        },
        onSetSubtitleSize = { newSize -> coroutineScope.launch { viewModel.setSubtitleSize(newSize) } },
        onSetSubtitleVerticalOffset = { newOffset -> coroutineScope.launch { viewModel.setSubtitleVerticalOffset(newOffset) } },
        onSetSubtitleBold = { bold -> coroutineScope.launch { viewModel.setSubtitleBold(bold) } },
        onSetUseForcedSubtitles = { enabled -> coroutineScope.launch { viewModel.setUseForcedSubtitles(enabled) } },
        onSetAddonSubtitlesEnabled = { enabled -> coroutineScope.launch { viewModel.setAddonSubtitlesEnabled(enabled) } },
        onSetSubtitleShowOnlyPreferredLanguages = { enabled ->
            coroutineScope.launch { viewModel.setSubtitleShowOnlyPreferredLanguages(enabled) }
        },
        onSetSubtitleStripSdh = { enabled ->
            coroutineScope.launch { viewModel.setSubtitleStripSdh(enabled) }
        },
        onSetSubtitleEdgeStyle = { style -> coroutineScope.launch { viewModel.setSubtitleEdgeStyle(style) } },
        onSetSubtitleHdrStyleEnabled = { coroutineScope.launch { viewModel.setSubtitleHdrStyleEnabled(it) } },
        onSetSubtitleHdrTextColor = { coroutineScope.launch { viewModel.setSubtitleHdrTextColor(it) } },
        onSetSubtitleHdrBrightness = { coroutineScope.launch { viewModel.setSubtitleHdrBrightnessPercent(it) } },
        onSetSubtitleHdrDimPlayerControls = { coroutineScope.launch { viewModel.setSubtitleHdrDimPlayerControls(it) } },
        onSetUseLibass = { enabled -> coroutineScope.launch { viewModel.setUseLibass(enabled) } },
        p2pEnabled = torrentSettings.p2pEnabled,
        onSetP2pEnabled = { enabled ->
            if (enabled && !torrentSettings.p2pEnabled) {
                showP2pConsentDialog = true
            } else {
                viewModel.setP2pEnabled(enabled)
            }
        },
        hideTorrentStats = torrentSettings.hideTorrentStats,
        onSetHideTorrentStats = { enabled -> viewModel.setHideTorrentStats(enabled) },
        onSetUseParallelConnections = { enabled ->
            coroutineScope.launch { viewModel.setUseParallelConnections(enabled) }
        },
        onSetParallelConnectionCount = { count ->
            coroutineScope.launch { viewModel.setParallelConnectionCount(count) }
        },
        onSetParallelChunkSizeKb = { kb ->
            coroutineScope.launch { viewModel.setParallelChunkSizeKb(kb) }
        },
        onSetBufferMinBufferMs = { ms ->
            coroutineScope.launch { viewModel.setBufferMinBufferMs(ms) }
        },
        onSetBufferMaxBufferMs = { ms ->
            coroutineScope.launch { viewModel.setBufferMaxBufferMs(ms) }
        },
        onSetBufferForPlaybackMs = { ms ->
            coroutineScope.launch { viewModel.setBufferForPlaybackMs(ms) }
        },
        onSetBufferForPlaybackAfterRebufferMs = { ms ->
            coroutineScope.launch { viewModel.setBufferForPlaybackAfterRebufferMs(ms) }
        },
        onSetBufferTargetSizeMb = { mb ->
            coroutineScope.launch { viewModel.setBufferTargetSizeMb(mb) }
        },
        onSetBufferBackBufferDurationMs = { ms ->
            coroutineScope.launch { viewModel.setBufferBackBufferDurationMs(ms) }
        },
        onSetAllowLargeTargetBuffer = { enabled ->
            coroutineScope.launch { viewModel.setAllowLargeTargetBuffer(enabled) }
        },
        onSetBufferBudgetManaged = { enabled ->
            coroutineScope.launch { viewModel.setBufferBudgetManaged(enabled) }
        },
        onResetBufferSettingsToDefaults = {
            coroutineScope.launch { viewModel.resetBufferSettingsToDefaults() }
        },
        onResetNetworkSettingsToDefaults = {
            coroutineScope.launch { viewModel.resetNetworkSettingsToDefaults() }
        },
        onSetEnableHttp2 = { enabled ->
            coroutineScope.launch { viewModel.setEnableHttp2(enabled) }
        },
        thisTv = state.thisTv,
        thisTvActions = thisTvActions,
        requestedSection = initialSection
    )

    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                viewModel.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false }
        )
    }
}

@Composable
internal fun LanguageSelectionDialog(
    title: String,
    selectedLanguage: String?,
    showNoneOption: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val tmdbTitle = stringResource(R.string.tmdb_language_dialog_title)
    val sortedLanguages = remember {
        val baseList = if (title == tmdbTitle) AVAILABLE_TMDB_LANGUAGES else AVAILABLE_SUBTITLE_LANGUAGES
        baseList.sortedBy { it.displayName.lowercase() }
    }
    val languageOptions: List<SettingsPickerOption<String?>> = buildList {
        if (showNoneOption) {
            add(SettingsPickerOption(null, stringResource(R.string.action_none)))
        }
        extraOptions.forEach { (code, name) ->
            add(SettingsPickerOption(code, name, trailing = code.uppercase()))
        }
        sortedLanguages.forEach { language ->
            add(SettingsPickerOption(language.code, language.displayName, trailing = language.code.uppercase()))
        }
    }

    SettingsPickerPanel(
        title = title,
        options = languageOptions,
        selectedValue = selectedLanguage,
        onSelect = onLanguageSelected,
        onDismiss = onDismiss
    )
}
