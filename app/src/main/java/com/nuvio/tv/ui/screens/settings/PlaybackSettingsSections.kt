@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.ui.util.findActivity

/** The pane's chips (stages playback-streams / -video / -subs), in order. */
internal enum class PlaybackSection { GENERAL, STREAMS, AUDIO_VIDEO, SUBTITLES, P2P, BUFFER_NETWORK }

/** Buffer & network tunes ExoPlayer only, so its chip shows for EXOPLAYER and AUTO (as built). */
internal fun visiblePlaybackSections(engine: InternalPlayerEngine): List<PlaybackSection> =
    if (engine.usesExoPlayer) {
        PlaybackSection.entries
    } else {
        PlaybackSection.entries - PlaybackSection.BUFFER_NETWORK
    }

/** The chip to show: [selected] while it is visible, else General. */
internal fun shownPlaybackSection(selected: PlaybackSection, visible: List<PlaybackSection>): PlaybackSection =
    if (selected in visible) selected else PlaybackSection.GENERAL

private const val GROUP_GENERAL = "playback_general"

/**
 * Spec 31, Playback pane (sandbox u11-settings-marquee v6, stages playback-streams / -picker /
 * -advanced / -video / -subs): up to six chips over one list (Buffer & network only for ExoPlayer /
 * Auto), every one on the Settings kit (Spec 31 § Playback plan). [title] / [subtitle] come from
 * the caller, which also draws them while the stores load.
 */
@Composable
internal fun PlaybackSettingsSections(
    title: String,
    subtitle: String,
    initialFocusRequester: FocusRequester?,
    playerSettings: PlayerSettings,
    seekThumbnailsEnabled: Boolean,
    onSetSeekThumbnailsEnabled: (Boolean) -> Unit,
    onClearSeekThumbnailCache: () -> Unit,
    modifier: Modifier = Modifier,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetAudioOutputChannels: (AudioOutputChannels) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetMpvHardwareDecodeMode: (MpvHardwareDecodeMode) -> Unit,
    onSetDv7HandlingMode: (Dv7HandlingMode) -> Unit,
    onSetDeniedHandling: (DeniedCodecHandling) -> Unit,
    onSetSubtitlePreferredLanguage: (String) -> Unit,
    onSetSubtitleSecondaryLanguage: (String?) -> Unit,
    onSetSubtitleTextColor: (Int) -> Unit,
    onSetSubtitleBackgroundColor: (Int) -> Unit,
    onSetSubtitleOutlineColor: (Int) -> Unit,
    streamAddonNames: List<String>,
    onSetStreamAutoPlaySelectedAddons: (Set<String>) -> Unit,
    onSetStreamAutoPlayRegex: (String) -> Unit,
    onSetInternalPlayerEngine: (InternalPlayerEngine) -> Unit,
    onSetStreamAutoPlayMode: (StreamAutoPlayMode) -> Unit,
    onSetStreamAutoPlaySource: (StreamAutoPlaySource) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onSetNextEpisodeThresholdMode: (NextEpisodeThresholdMode) -> Unit,
    onSetStreamAutoPlayNextEpisodeEnabled: (Boolean) -> Unit,
    onSetStreamAutoPlayNextEpisodeFallbackEnabled: (Boolean) -> Unit,
    onSetStreamAutoPlayPreferBingeGroupForNextEpisode: (Boolean) -> Unit,
    onSetStreamAutoPlayReuseBingeGroup: (Boolean) -> Unit,
    onSetAutoSwitchInternalPlayerOnError: (Boolean) -> Unit,
    onSetNextEpisodeThresholdPercent: (Float) -> Unit,
    onSetNextEpisodeThresholdMinutesBeforeEnd: (Float) -> Unit,
    onSetStreamAutoPlayTimeoutSeconds: (Int) -> Unit,
    onSetStreamAutoPlayEagerReadyEnabled: (Boolean) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetStillWatchingEnabled: (Boolean) -> Unit,
    onSetStillWatchingEpisodeThreshold: (Int) -> Unit,
    onSetShowPlayerLoadingStatus: (Boolean) -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetOsdClockEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetParentalGuideEnabled: (Boolean) -> Unit,
    onSetAutoSkipSegmentTypeEnabled: (AutoSkipSegmentType, Boolean) -> Unit,
    onSetFrameRateMatchingMode: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
    onSetAfrSettleHoldSeconds: (Int) -> Unit,
    onDisableAfrAndResolution: () -> Unit,
    onSetDownmixEnabled: (Boolean) -> Unit,
    onSetMaintainOriginalAudioOnDownmix: (Boolean) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetRememberAudioDelayPerDevice: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetForceOpticalPassthrough: (Boolean) -> Unit,
    onSetAllowAc3Passthrough: (Boolean) -> Unit,
    onSetAllowEac3Passthrough: (Boolean) -> Unit,
    onSetAllowTrueHdPassthrough: (Boolean) -> Unit,
    onSetMatPassthroughEnabled: (Boolean) -> Unit,
    onSetAllowDtsPassthrough: (Boolean) -> Unit,
    onSetAllowDtsHdPassthrough: (Boolean) -> Unit,
    onSetDv5ToDv81Enabled: (Boolean) -> Unit,
    onSetStripHdr10PlusSei: (Boolean) -> Unit,
    onSetInjectHdr10Sei: (Boolean) -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetUseForcedSubtitles: (Boolean) -> Unit,
    onSetAddonSubtitlesEnabled: (Boolean) -> Unit,
    onSetSubtitleShowOnlyPreferredLanguages: (Boolean) -> Unit,
    onSetSubtitleStripSdh: (Boolean) -> Unit,
    onSetSubtitleEdgeStyle: (SubtitleEdgeStyle) -> Unit,
    onSetSubtitleHdrStyleEnabled: (Boolean) -> Unit,
    onSetSubtitleHdrTextColor: (Int) -> Unit,
    onSetSubtitleHdrBrightness: (Int) -> Unit,
    onSetSubtitleHdrDimPlayerControls: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    p2pEnabled: Boolean,
    onSetP2pEnabled: (Boolean) -> Unit,
    hideTorrentStats: Boolean,
    onSetHideTorrentStats: (Boolean) -> Unit,
    onSetNuvioPerformanceModeEnabled: (Boolean) -> Unit,
    onSetBufferEngineEnabled: (Boolean) -> Unit,
    onSetParallelNetworkEnabled: (Boolean) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeKb: (Int) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetAllowLargeTargetBuffer: (Boolean) -> Unit,
    onSetBufferBudgetManaged: (Boolean) -> Unit,
    onResetBufferSettingsToDefaults: () -> Unit,
    onSetEnableHttp2: (Boolean) -> Unit,
    onResetNetworkSettingsToDefaults: () -> Unit,
    thisTv: ThisTvState,
    thisTvActions: ThisTvActions,
    /** Settings' deep entry (SettingsEntry): selects this chip once while non-null. */
    requestedSection: PlaybackSection? = null
) {
    val groups = settingsAdvancedGroups()
    var selectedSection by rememberSaveable { mutableStateOf(PlaybackSection.GENERAL) }
    LaunchedEffect(requestedSection) { requestedSection?.let { selectedSection = it } }
    val sections = visiblePlaybackSections(playerSettings.internalPlayerEngine)
    val section = shownPlaybackSection(selectedSection, sections)
    var streamsPicker by rememberSaveable { mutableStateOf<StreamsPicker?>(null) }
    val listState = rememberLazyListState()
    // Each chip opens at its top (the chips share one list).
    LaunchedEffect(section) { listState.scrollToItem(0) }

    var audioVideoPicker by rememberSaveable { mutableStateOf<AudioVideoPicker?>(null) }
    var subtitlePicker by rememberSaveable { mutableStateOf<SubtitlePicker?>(null) }
    var hdrRowFocused by remember { mutableStateOf(false) }
    val frameRateFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var displayCapabilities by remember { mutableStateOf(DisplayCapabilities.Snapshot.Unknown) }
    val audioVideoShown = section == PlaybackSection.AUDIO_VIDEO
    LaunchedEffect(activity, audioVideoShown) {
        if (!audioVideoShown) return@LaunchedEffect
        if (activity == null) {
            Log.w("DisplayCapabilities", "Settings: could not resolve host Activity from LocalContext")
            return@LaunchedEffect
        }
        val snapshot = DisplayCapabilities.detect(activity)
        displayCapabilities = snapshot
        DisplayCapabilities.logSummary(snapshot)
    }
    SettingsPane(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        listState = listState,
        chips = {
            SettingsSectionChips(
                items = sections,
                selected = section,
                label = { playbackSectionLabel(it) },
                onSelect = { selectedSection = it }
            )
            // Pinned with the chips, so it stays in view while any Subtitles row changes the look.
            // Spec 62 D12: the HDR look while an HDR row has focus with the HDR style on.
            if (section == PlaybackSection.SUBTITLES) {
                val style = playerSettings.subtitleStyle
                val hdrPreview = hdrRowFocused && style.hdrStyleEnabled
                SubtitlePreview(style = style, hdr = hdrPreview)
            }
        }
    ) {
        when (section) {
            PlaybackSection.GENERAL -> generalItems(
                playerSettings = playerSettings,
                seekThumbnailsEnabled = seekThumbnailsEnabled,
                onSetSeekThumbnailsEnabled = onSetSeekThumbnailsEnabled,
                onClearSeekThumbnailCache = onClearSeekThumbnailCache,
                onSetLoadingOverlayEnabled = onSetLoadingOverlayEnabled,
                onSetPauseOverlayEnabled = onSetPauseOverlayEnabled,
                onSetOsdClockEnabled = onSetOsdClockEnabled,
                onSetSkipIntroEnabled = onSetSkipIntroEnabled,
                onSetParentalGuideEnabled = onSetParentalGuideEnabled,
                onSetAutoSkipSegmentTypeEnabled = onSetAutoSkipSegmentTypeEnabled,
                groupOpen = groups.isOpen(GROUP_GENERAL),
                onToggleGroup = { groups.toggle(GROUP_GENERAL) },
                entryFocus = initialFocusRequester
            )
            PlaybackSection.STREAMS -> streamsItems(
                settings = playerSettings,
                onOpenPicker = { streamsPicker = it },
                onSetTimeoutSeconds = onSetStreamAutoPlayTimeoutSeconds,
                onSetNextEpisodeEnabled = onSetStreamAutoPlayNextEpisodeEnabled,
                onSetAutoSwitchOnError = onSetAutoSwitchInternalPlayerOnError,
                onSetEagerReadyEnabled = onSetStreamAutoPlayEagerReadyEnabled,
                onSetReuseLastLinkEnabled = onSetReuseLastLinkEnabled,
                onSetFallbackEnabled = onSetStreamAutoPlayNextEpisodeFallbackEnabled,
                onSetStillWatchingEnabled = onSetStillWatchingEnabled,
                onSetStillWatchingThreshold = onSetStillWatchingEpisodeThreshold,
                onSetPreferBingeGroup = onSetStreamAutoPlayPreferBingeGroupForNextEpisode,
                onSetReuseBingeGroup = onSetStreamAutoPlayReuseBingeGroup,
                onSetThresholdPercent = onSetNextEpisodeThresholdPercent,
                onSetThresholdMinutes = onSetNextEpisodeThresholdMinutesBeforeEnd,
                onSetShowLoadingStatus = onSetShowPlayerLoadingStatus,
                groupOpen = groups.isOpen(GROUP_STREAMS),
                onToggleGroup = { groups.toggle(GROUP_STREAMS) },
                entryFocus = initialFocusRequester
            )
            PlaybackSection.AUDIO_VIDEO -> audioVideoItems(
                settings = playerSettings,
                afrProblem = afrCapabilityProblem(
                    displayCapabilities,
                    playerSettings.frameRateMatchingMode,
                    playerSettings.resolutionMatchingEnabled
                ),
                resolutionSwitchingSupported = !displayCapabilities.apiSupported ||
                    displayCapabilities.supportsResolutionSwitching,
                onOpenPicker = { audioVideoPicker = it },
                onSetFrameRateMatchingMode = onSetFrameRateMatchingMode,
                onSetResolutionMatchingEnabled = onSetResolutionMatchingEnabled,
                onSetAfrSettleHoldSeconds = onSetAfrSettleHoldSeconds,
                onDisableAfrAndResolution = onDisableAfrAndResolution,
                onSetSkipSilence = onSetSkipSilence,
                onSetRememberAudioDelayPerDevice = onSetRememberAudioDelayPerDevice,
                onSetDownmixEnabled = onSetDownmixEnabled,
                onSetMaintainOriginalAudioOnDownmix = onSetMaintainOriginalAudioOnDownmix,
                onSetTunnelingEnabled = onSetTunnelingEnabled,
                onSetForceOpticalPassthrough = onSetForceOpticalPassthrough,
                onSetAllowAc3Passthrough = onSetAllowAc3Passthrough,
                onSetAllowEac3Passthrough = onSetAllowEac3Passthrough,
                onSetAllowTrueHdPassthrough = onSetAllowTrueHdPassthrough,
                onSetMatPassthroughEnabled = onSetMatPassthroughEnabled,
                onSetAllowDtsPassthrough = onSetAllowDtsPassthrough,
                onSetAllowDtsHdPassthrough = onSetAllowDtsHdPassthrough,
                onSetDv5ToDv81Enabled = onSetDv5ToDv81Enabled,
                onSetStripHdr10PlusSei = onSetStripHdr10PlusSei,
                onSetInjectHdr10Sei = onSetInjectHdr10Sei,
                groupOpen = groups.isOpen(GROUP_AUDIO_VIDEO),
                onToggleGroup = { groups.toggle(GROUP_AUDIO_VIDEO) },
                entryFocus = initialFocusRequester,
                frameRateFocus = frameRateFocus
            )
            PlaybackSection.SUBTITLES -> subtitleItems(
                settings = playerSettings,
                onOpenPicker = { subtitlePicker = it },
                onSetUseForcedSubtitles = onSetUseForcedSubtitles,
                onSetAddonSubtitlesEnabled = onSetAddonSubtitlesEnabled,
                onSetSize = onSetSubtitleSize,
                onSetVerticalOffset = onSetSubtitleVerticalOffset,
                onSetBold = onSetSubtitleBold,
                onSetEdgeStyle = onSetSubtitleEdgeStyle,
                onSetHdrStyleEnabled = onSetSubtitleHdrStyleEnabled,
                onSetHdrBrightness = onSetSubtitleHdrBrightness,
                onSetHdrDimPlayerControls = onSetSubtitleHdrDimPlayerControls,
                onHdrRowFocused = { hdrRowFocused = it },
                onSetUseLibass = onSetUseLibass,
                onSetShowOnlyPreferredLanguages = onSetSubtitleShowOnlyPreferredLanguages,
                onSetStripSdh = onSetSubtitleStripSdh,
                groupOpen = groups.isOpen(GROUP_SUBTITLES),
                onToggleGroup = { groups.toggle(GROUP_SUBTITLES) },
                entryFocus = initialFocusRequester
            )
            PlaybackSection.P2P -> p2pItems(
                p2pEnabled = p2pEnabled,
                onSetP2pEnabled = onSetP2pEnabled,
                hideTorrentStats = hideTorrentStats,
                onSetHideTorrentStats = onSetHideTorrentStats,
                entryFocus = initialFocusRequester
            )
            PlaybackSection.BUFFER_NETWORK -> bufferNetworkItems(
                settings = playerSettings,
                onSetNuvioPerformanceModeEnabled = onSetNuvioPerformanceModeEnabled,
                onSetBufferEngineEnabled = onSetBufferEngineEnabled,
                onSetParallelNetworkEnabled = onSetParallelNetworkEnabled,
                onSetBufferMinBufferMs = onSetBufferMinBufferMs,
                onSetBufferMaxBufferMs = onSetBufferMaxBufferMs,
                onSetBufferForPlaybackMs = onSetBufferForPlaybackMs,
                onSetBufferForPlaybackAfterRebufferMs = onSetBufferForPlaybackAfterRebufferMs,
                onSetBufferTargetSizeMb = onSetBufferTargetSizeMb,
                onSetBufferBackBufferDurationMs = onSetBufferBackBufferDurationMs,
                onSetAllowLargeTargetBuffer = onSetAllowLargeTargetBuffer,
                onSetBufferBudgetManaged = onSetBufferBudgetManaged,
                onResetBufferToDefaults = onResetBufferSettingsToDefaults,
                onSetUseParallelConnections = onSetUseParallelConnections,
                onSetParallelConnectionCount = onSetParallelConnectionCount,
                onSetParallelChunkSizeKb = onSetParallelChunkSizeKb,
                onSetEnableHttp2 = onSetEnableHttp2,
                onResetNetworkToDefaults = onResetNetworkSettingsToDefaults,
                thisTv = thisTv,
                thisTvActions = thisTvActions,
                entryFocus = initialFocusRequester
            )
        }
    }

    streamsPicker?.let { picker ->
        StreamsPickerPanel(
            picker = picker,
            settings = playerSettings,
            streamAddonNames = streamAddonNames,
            onSetMode = onSetStreamAutoPlayMode,
            onSetSource = onSetStreamAutoPlaySource,
            onSetSelectedAddons = onSetStreamAutoPlaySelectedAddons,
            onSetRegex = onSetStreamAutoPlayRegex,
            onSetEngine = onSetInternalPlayerEngine,
            onSetReuseCacheHours = onSetReuseLastLinkCacheHours,
            onSetThresholdMode = onSetNextEpisodeThresholdMode,
            onDismiss = { streamsPicker = null }
        )
    }

    audioVideoPicker?.let { picker ->
        AudioVideoPickerPanel(
            picker = picker,
            settings = playerSettings,
            onSetDv7HandlingMode = onSetDv7HandlingMode,
            onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
            onSetSecondaryPreferredAudioLanguage = onSetSecondaryPreferredAudioLanguage,
            onSetDecoderPriority = onSetDecoderPriority,
            onSetAudioOutputChannels = onSetAudioOutputChannels,
            onSetDeniedHandling = onSetDeniedHandling,
            onSetMpvHardwareDecodeMode = onSetMpvHardwareDecodeMode,
            onDismiss = { audioVideoPicker = null }
        )
    }

    subtitlePicker?.let { picker ->
        SubtitlePickerPanel(
            picker = picker,
            settings = playerSettings,
            onSetPreferredLanguage = onSetSubtitlePreferredLanguage,
            onSetSecondaryLanguage = onSetSubtitleSecondaryLanguage,
            onSetTextColor = onSetSubtitleTextColor,
            onSetBackgroundColor = onSetSubtitleBackgroundColor,
            onSetOutlineColor = onSetSubtitleOutlineColor,
            onSetHdrTextColor = onSetSubtitleHdrTextColor,
            onDismiss = { subtitlePicker = null }
        )
    }
}

@Composable
private fun playbackSectionLabel(section: PlaybackSection): String = stringResource(
    when (section) {
        PlaybackSection.GENERAL -> R.string.playback_section_general
        PlaybackSection.STREAMS -> R.string.playback_chip_streams
        PlaybackSection.AUDIO_VIDEO -> R.string.playback_chip_audio_video
        PlaybackSection.SUBTITLES -> R.string.playback_section_subtitles
        PlaybackSection.P2P -> R.string.playback_chip_p2p
        PlaybackSection.BUFFER_NETWORK -> R.string.playback_chip_buffer_network
    }
)

/** General chip: overlays, clock, skip intro, content warnings; thumbnails and auto-skip in the group. */
private fun LazyListScope.generalItems(
    playerSettings: PlayerSettings,
    seekThumbnailsEnabled: Boolean,
    onSetSeekThumbnailsEnabled: (Boolean) -> Unit,
    onClearSeekThumbnailCache: () -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetOsdClockEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetParentalGuideEnabled: (Boolean) -> Unit,
    onSetAutoSkipSegmentTypeEnabled: (AutoSkipSegmentType, Boolean) -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    item(key = "general_loading_overlay") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_loading_overlay),
            subtitle = stringResource(R.string.playback_loading_overlay_sub),
            checked = playerSettings.loadingOverlayEnabled,
            onCheckedChange = onSetLoadingOverlayEnabled,
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "general_pause_overlay") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_pause_overlay),
            subtitle = stringResource(R.string.playback_pause_overlay_sub),
            checked = playerSettings.pauseOverlayEnabled,
            onCheckedChange = onSetPauseOverlayEnabled
        )
    }
    item(key = "general_osd_clock") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_osd_clock),
            subtitle = stringResource(R.string.playback_show_clock_sub),
            checked = playerSettings.osdClockEnabled,
            onCheckedChange = onSetOsdClockEnabled
        )
    }
    item(key = "general_skip_intro") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_skip_intro),
            subtitle = stringResource(R.string.playback_skip_intro_sub),
            checked = playerSettings.skipIntroEnabled,
            onCheckedChange = onSetSkipIntroEnabled
        )
    }
    item(key = "general_parental_guide") {
        SettingsSwitchRow(
            title = stringResource(R.string.playback_parental_guide),
            subtitle = stringResource(R.string.playback_parental_guide_sub),
            checked = playerSettings.parentalGuideEnabled,
            onCheckedChange = onSetParentalGuideEnabled
        )
    }
    settingsAdvancedGroup(key = GROUP_GENERAL, count = 5, open = groupOpen, onToggle = onToggleGroup) {
        item(key = "general_seek_thumbnails") {
            SettingsSwitchRow(
                title = stringResource(R.string.playback_seek_thumbnails),
                subtitle = stringResource(R.string.playback_seek_thumbnails_sub),
                checked = seekThumbnailsEnabled,
                onCheckedChange = onSetSeekThumbnailsEnabled
            )
        }
        item(key = "general_seek_thumbnails_clear") {
            SettingsValueRow(
                title = stringResource(R.string.playback_seek_thumbnails_clear),
                subtitle = stringResource(R.string.playback_seek_thumbnails_clear_sub),
                value = null,
                onClick = onClearSeekThumbnailCache,
                trailingIcon = null
            )
        }
        autoSkipRow(
            type = AutoSkipSegmentType.INTRO,
            title = R.string.auto_skip_intro,
            subtitle = R.string.auto_skip_intro_sub,
            playerSettings = playerSettings,
            onSetAutoSkipSegmentTypeEnabled = onSetAutoSkipSegmentTypeEnabled
        )
        autoSkipRow(
            type = AutoSkipSegmentType.RECAP,
            title = R.string.auto_skip_recap,
            subtitle = R.string.auto_skip_recap_sub,
            playerSettings = playerSettings,
            onSetAutoSkipSegmentTypeEnabled = onSetAutoSkipSegmentTypeEnabled
        )
        autoSkipRow(
            type = AutoSkipSegmentType.OUTRO,
            title = R.string.auto_skip_outro,
            subtitle = R.string.auto_skip_outro_sub,
            playerSettings = playerSettings,
            onSetAutoSkipSegmentTypeEnabled = onSetAutoSkipSegmentTypeEnabled
        )
    }
}

/** An automatic-skip row; skipping needs Skip Intro's segment detection (dims without it). */
private fun LazyListScope.autoSkipRow(
    type: AutoSkipSegmentType,
    @StringRes title: Int,
    @StringRes subtitle: Int,
    playerSettings: PlayerSettings,
    onSetAutoSkipSegmentTypeEnabled: (AutoSkipSegmentType, Boolean) -> Unit
) {
    item(key = "general_auto_skip_${type.name.lowercase()}") {
        val reason = playbackRowDimReason(PlaybackRow.AUTO_SKIP, playerSettings)
        SettingsSwitchRow(
            title = stringResource(title),
            subtitle = stringResource(reason ?: subtitle),
            checked = type in playerSettings.autoSkipSegmentTypes,
            onCheckedChange = { onSetAutoSkipSegmentTypeEnabled(type, it) },
            enabled = reason == null
        )
    }
}

/** P2P chip: two rows, no group. Turning P2P on goes through the consent dialog (the caller's). */
private fun LazyListScope.p2pItems(
    p2pEnabled: Boolean,
    onSetP2pEnabled: (Boolean) -> Unit,
    hideTorrentStats: Boolean,
    onSetHideTorrentStats: (Boolean) -> Unit,
    entryFocus: FocusRequester?
) {
    item(key = "p2p_enabled") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_p2p_title),
            subtitle = stringResource(R.string.settings_p2p_subtitle),
            checked = p2pEnabled,
            onCheckedChange = onSetP2pEnabled,
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "p2p_hide_stats") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_p2p_hide_stats_title),
            subtitle = stringResource(R.string.settings_p2p_hide_stats_subtitle),
            checked = hideTorrentStats,
            onCheckedChange = onSetHideTorrentStats
        )
    }
}
