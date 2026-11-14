package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier
import com.nuvio.tv.core.assessment.DeviceSetupCoordinator
import com.nuvio.tv.core.assessment.DeviceSetupRecord
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.stream.ResolutionLimit
import com.nuvio.tv.core.stream.ResolutionLimitMode
import com.nuvio.tv.core.stream.ServiceLimitFigure
import com.nuvio.tv.core.stream.StreamLimitMode
import com.nuvio.tv.core.stream.StreamLimitSettings
import com.nuvio.tv.core.stream.networkLimitFigures
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.ThumbnailCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Playback pane draws, read from the stores. */
data class PlaybackSettingsUiState(
    val player: PlayerSettings,
    val torrent: TorrentSettingsData,
    /** Enabled addons that serve streams, by display name (the Allowed addons choices). */
    val streamAddonNames: List<String>,
    val seekThumbnailsEnabled: Boolean,
    val thisTv: ThisTvState
)

/**
 * Buffer & network › This TV (Spec 33 D5): the network tables, the one undo slot, the setup
 * record, and the group's action still running, if any ([running]). [streamLimit] is the stream
 * list limit (Spec 51 P7), [limitFigures] the services that set it on the link the TV was on when
 * the state was built, [resolutionLimit] the resolution limit with the TV's largest mode (Spec 58 F3).
 */
data class ThisTvState(
    val tables: ServiceNetworkTables,
    val undo: DeviceAssessmentApplier.UndoSlot?,
    val setup: DeviceSetupRecord?,
    val running: ThisTvAction?,
    val streamLimit: StreamLimitSettings,
    val limitFigures: List<ServiceLimitFigure>,
    val resolutionLimit: ResolutionLimit = ResolutionLimit()
) {
    val busy: Boolean get() = running != null
}

/** This TV's long-running actions (one at a time). */
enum class ThisTvAction { UNDO, SET_UP_AGAIN }

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val torrentSettings: TorrentSettings
) : ViewModel() {

    val playerSettings: Flow<PlayerSettings> = playerSettingsDataStore.playerSettings

    fun setP2pEnabled(enabled: Boolean) = torrentSettings.setP2pEnabled(enabled)
    fun setHideTorrentStats(enabled: Boolean) = torrentSettings.setHideTorrentStats(enabled)

    val lastPlaybackDiagnostics: Flow<LastPlaybackDiagnostics> = playerSettingsDataStore.lastPlaybackDiagnostics
    private val installedAddonNames: Flow<List<String>> = addonRepository.getInstalledAddons().map { addons ->
        addons
            .enabledAddons()
            .filter { addon ->
                addon.resources.any { resource ->
                    resource.name.equals("stream", ignoreCase = true)
                }
            }
            .map { it.displayName }
            .distinct()
            .sorted()
    }

    private val thisTvRunning = MutableStateFlow<ThisTvAction?>(null)

    // The limit inputs carry the decoded tables too, so the group reads them from there. The link is
    // read per emission: a Wi-Fi / Ethernet switch while the pane is open shows on the next one
    // (as the stream lists, Spec 51 R4-8).
    private val thisTvState: Flow<ThisTvState> = combine(
        playerSettingsDataStore.streamLimitInputs,
        playerSettingsDataStore.assessmentRevertSnapshot,
        playerSettingsDataStore.deviceSetupRecordJson,
        thisTvRunning
    ) { limit, snapshot, setup, running ->
        ThisTvState(
            tables = limit.tables,
            undo = DeviceAssessmentApplier.undoSlotOf(snapshot),
            setup = DeviceSetupRecord.decode(setup),
            running = running,
            streamLimit = limit.settings,
            limitFigures = networkLimitFigures(limit.tables, NetworkTransportReader.current(context)),
            resolutionLimit = limit.resolution
        )
    }

    /** Null until every store has emitted; the pane draws its rows only after that. */
    val uiState: StateFlow<PlaybackSettingsUiState?> = combine(
        playerSettingsDataStore.playerSettings,
        torrentSettings.settings,
        installedAddonNames,
        SeekThumbnailPreferences.enabledFlow(context),
        thisTvState
    ) { player, torrent, addonNames, seekThumbnails, thisTv ->
        PlaybackSettingsUiState(player, torrent, addonNames, seekThumbnails, thisTv)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // This TV (Spec 33 D5). The actions run on the ViewModel scope, so leaving the pane does not
    // cut a half-written apply or revert short; [thisTvRunning] keeps two from overlapping.

    fun setAutomaticNetwork(enabled: Boolean) {
        viewModelScope.launch { updateNetworkTables { it.copy(automatic = enabled) } }
    }

    fun setStreamLimitMode(mode: StreamLimitMode) {
        viewModelScope.launch { playerSettingsDataStore.setStreamLimitMode(mode) }
    }

    fun setStreamLimitManualMbps(mbps: Int) {
        viewModelScope.launch { playerSettingsDataStore.setStreamLimitManualMbps(mbps) }
    }

    fun setResolutionLimitMode(mode: ResolutionLimitMode) {
        viewModelScope.launch { playerSettingsDataStore.setResolutionLimitMode(mode) }
    }

    /** "Run again": every table re-tests on its service's next qualifying film (D6). */
    fun requestNetworkRetest(onDone: () -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            updateNetworkTables { it.copy(retestRequestedAtMs = now) }
            onDone()
        }
    }

    /** Restores the undo slot; [onDone] gets false when there was nothing to restore. */
    fun undoLastChange(onDone: (Boolean) -> Unit) = runThisTvAction(ThisTvAction.UNDO) {
        onDone(DeviceAssessmentApplier.revert(playerSettingsDataStore))
    }

    /** Runs the device setup again; [activity] lets it read the display (frame-rate matching). */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun setUpThisTvAgain(activity: Activity?, onDone: (writtenCount: Int) -> Unit) =
        runThisTvAction(ThisTvAction.SET_UP_AGAIN) {
            val outcome = DeviceSetupCoordinator.setUp(
                context, activity, playerSettingsDataStore, DeviceSetupRecord.Kind.AGAIN
            )
            onDone(outcome.writtenCount)
        }

    private fun runThisTvAction(action: ThisTvAction, block: suspend () -> Unit) {
        if (!thisTvRunning.compareAndSet(expect = null, update = action)) return
        viewModelScope.launch {
            try {
                block()
            } finally {
                thisTvRunning.value = null
            }
        }
    }

    private suspend fun updateNetworkTables(transform: (ServiceNetworkTables) -> ServiceNetworkTables) {
        playerSettingsDataStore.updateServiceNetworkTablesJson { json ->
            val current = ServiceNetworkTables.decode(json)
            val next = transform(current)
            if (next == current) json else next.encode()
        }
    }

    /**
     * A network row changed by hand turns Automatic network off (Spec 33 decision): the user's
     * values then apply to every film until Automatic is switched back on. Here, not in the
     * store setters, because the applier and its revert write through those same setters.
     */
    private suspend fun networkChangedByHand() {
        updateNetworkTables { it.copy(automatic = false) }
    }

    fun setSeekThumbnailsEnabled(enabled: Boolean) {
        viewModelScope.launch { SeekThumbnailPreferences.setEnabled(context, enabled) }
    }

    fun clearSeekThumbnailCache(onDone: () -> Unit) {
        viewModelScope.launch {
            ThumbnailCache.clearAll(context)
            onDone()
        }
    }

    suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
        playerSettingsDataStore.setInternalPlayerEngine(engine)
    }

    suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
        playerSettingsDataStore.setAutoSwitchInternalPlayerOnError(enabled)
    }

    // Audio settings

    suspend fun setDecoderPriority(priority: Int) {
        playerSettingsDataStore.setDecoderPriority(priority)
    }

    suspend fun setDownmixEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDownmixEnabled(enabled)
    }

    suspend fun setAudioOutputChannels(channels: AudioOutputChannels) {
        playerSettingsDataStore.setAudioOutputChannels(channels)
    }

    suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean) {
        playerSettingsDataStore.setMaintainOriginalAudioOnDownmix(enabled)
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTunnelingEnabled(enabled)
    }

    suspend fun setForceOpticalPassthrough(enabled: Boolean) {
        playerSettingsDataStore.setForceOpticalPassthrough(enabled)
    }

    suspend fun setAllowAc3Passthrough(allowed: Boolean) {
        playerSettingsDataStore.setAllowAc3Passthrough(allowed)
    }

    suspend fun setAllowEac3Passthrough(allowed: Boolean) {
        playerSettingsDataStore.setAllowEac3Passthrough(allowed)
    }

    suspend fun setAllowTrueHdPassthrough(allowed: Boolean) {
        playerSettingsDataStore.setAllowTrueHdPassthrough(allowed)
    }

    suspend fun setAllowDtsPassthrough(allowed: Boolean) {
        playerSettingsDataStore.setAllowDtsPassthrough(allowed)
    }

    suspend fun setAllowDtsHdPassthrough(allowed: Boolean) {
        playerSettingsDataStore.setAllowDtsHdPassthrough(allowed)
    }

    suspend fun setMatPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setMatPassthroughEnabled(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        playerSettingsDataStore.setSkipSilence(enabled)
    }

    suspend fun setRememberAudioDelayPerDevice(enabled: Boolean) {
        playerSettingsDataStore.setRememberAudioDelayPerDevice(enabled)
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        playerSettingsDataStore.setPreferredAudioLanguage(language)
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(language)
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setLoadingOverlayEnabled(enabled)
    }

    suspend fun setShowPlayerLoadingStatus(enabled: Boolean) {
        playerSettingsDataStore.setShowPlayerLoadingStatus(enabled)
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setPauseOverlayEnabled(enabled)
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        playerSettingsDataStore.setOsdClockEnabled(enabled)
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSkipIntroEnabled(enabled)
    }

    suspend fun setParentalGuideEnabled(enabled: Boolean) {
        playerSettingsDataStore.setParentalGuideEnabled(enabled)
    }

    suspend fun setAutoSkipSegmentTypeEnabled(segmentType: AutoSkipSegmentType, enabled: Boolean) {
        playerSettingsDataStore.setAutoSkipSegmentTypeEnabled(segmentType, enabled)
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        playerSettingsDataStore.setFrameRateMatchingMode(mode)
    }

    suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setResolutionMatchingEnabled(enabled)
    }

    suspend fun setAfrSettleHoldSeconds(seconds: Int) {
        playerSettingsDataStore.setAfrSettleHoldSeconds(seconds)
    }

    suspend fun disableAfrAndResolution() {
        playerSettingsDataStore.setFrameRateMatchingMode(FrameRateMatchingMode.OFF)
        playerSettingsDataStore.setResolutionMatchingEnabled(false)
    }

    suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        playerSettingsDataStore.setMpvHardwareDecodeMode(mode)
    }


    suspend fun setDv5ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setDv5ToDv81Enabled(enabled)
    }

    suspend fun setDeniedCodecHandling(mode: com.nuvio.tv.data.local.DeniedCodecHandling) {
        playerSettingsDataStore.setDeniedCodecHandling(mode)
    }

    suspend fun setDv7HandlingMode(mode: Dv7HandlingMode) {
        playerSettingsDataStore.setDv7HandlingMode(mode)
        // The conversion-mode override only applies when handling is Convert to
        // DV8.1. Any other mode clears the override back to None so a stale forced
        // mode can't linger (disabled in UI but still stored).
        if (mode != Dv7HandlingMode.DV81_LIBDOVI) {
            playerSettingsDataStore.setDv7LibdoviModeOverride(-1)
        }
    }

    suspend fun setDv7LibdoviModeOverride(mode: Int) {
        playerSettingsDataStore.setDv7LibdoviModeOverride(mode)
    }

    suspend fun setStripHdr10PlusSei(enabled: Boolean) {
        playerSettingsDataStore.setStripHdr10PlusSei(enabled)
    }

    suspend fun setInjectHdr10MetadataOnStrip(enabled: Boolean) {
        playerSettingsDataStore.setInjectHdr10MetadataOnStrip(enabled)
    }

    suspend fun setUseLibass(enabled: Boolean) {
        playerSettingsDataStore.setUseLibass(enabled)
    }

    suspend fun setSubtitlePreferredLanguage(language: String) {
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerSettingsDataStore.setSubtitleSecondaryLanguage(language)
    }

    suspend fun setUseForcedSubtitles(enabled: Boolean) {
        playerSettingsDataStore.setUseForcedSubtitles(enabled)
    }

    suspend fun setAddonSubtitlesEnabled(enabled: Boolean) {
        playerSettingsDataStore.setAddonSubtitlesEnabled(enabled)
    }

    suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleShowOnlyPreferredLanguages(enabled)
    }

    suspend fun setSubtitleStripSdh(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleStripSdh(enabled)
    }

    suspend fun setSubtitleSize(size: Int) {
        playerSettingsDataStore.setSubtitleSize(size)
    }

    suspend fun setSubtitleVerticalOffset(offset: Int) {
        playerSettingsDataStore.setSubtitleVerticalOffset(offset)
    }

    suspend fun setSubtitleBold(bold: Boolean) {
        playerSettingsDataStore.setSubtitleBold(bold)
    }

    suspend fun setSubtitleTextColor(color: Int) {
        playerSettingsDataStore.setSubtitleTextColor(color)
    }

    suspend fun setSubtitleBackgroundColor(color: Int) {
        playerSettingsDataStore.setSubtitleBackgroundColor(color)
    }

    suspend fun setSubtitleEdgeStyle(style: SubtitleEdgeStyle) {
        playerSettingsDataStore.setSubtitleEdgeStyle(style)
    }

    suspend fun setSubtitleOutlineColor(color: Int) {
        playerSettingsDataStore.setSubtitleOutlineColor(color)
    }

    suspend fun setSubtitleOutlineWidth(width: Int) {
        playerSettingsDataStore.setSubtitleOutlineWidth(width)
    }

    // Spec 62 H2b (D7): the HDR and Dolby Vision subtitle style.
    suspend fun setSubtitleHdrStyleEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleHdrStyleEnabled(enabled)
    }

    suspend fun setSubtitleHdrTextColor(color: Int) {
        playerSettingsDataStore.setSubtitleHdrTextColor(color)
    }

    suspend fun setSubtitleHdrBrightnessPercent(percent: Int) {
        playerSettingsDataStore.setSubtitleHdrBrightnessPercent(percent)
    }

    suspend fun setSubtitleHdrDimPlayerControls(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleHdrDimPlayerControls(enabled)
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMinBufferMs(ms)
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMaxBufferMs(ms)
    }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackMs(ms)
    }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackAfterRebufferMs(ms)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setBufferTargetSizeMb(mb: Int) {
        val current = playerSettings.first()
        if (!current.useParallelConnections || current.nuvioPerformanceModeEnabled || current.allowLargeTargetBuffer) {
            playerSettingsDataStore.setBufferTargetSizeMb(mb)
            return
        }
        val currentChunkMb = Math.ceil(current.parallelChunkSizeKb / 1024.0).toInt()
        val (adjBuffer, adjChunkMb) = MemoryBudget.enforce(
            mb,
            currentChunkMb,
            current.parallelConnectionCount
        )
        if (adjBuffer == mb && adjChunkMb == currentChunkMb) {
            playerSettingsDataStore.setBufferTargetSizeMb(mb)
        } else {
            playerSettingsDataStore.updateMemorySettings(
                targetBufferSizeMb = adjBuffer,
                parallelChunkSizeKb = adjChunkMb * 1024
            )
        }
    }

    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        playerSettingsDataStore.setBufferBackBufferDurationMs(ms)
    }

    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) {
        playerSettingsDataStore.setBufferRetainBackBufferFromKeyframe(retain)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun resetBufferSettingsToDefaults() {
        playerSettingsDataStore.resetBufferSettingsToDefaults()
        val current = playerSettings.first()
        if (!current.useParallelConnections || current.nuvioPerformanceModeEnabled || current.allowLargeTargetBuffer) return

        val currentChunkMb = Math.ceil(current.parallelChunkSizeKb / 1024.0).toInt()
        val (adjBuffer, adjChunkMb) = MemoryBudget.enforce(
            MemoryBudget.defaultBufferSizeMb,
            currentChunkMb,
            current.parallelConnectionCount
        )
        if (adjChunkMb != currentChunkMb ||
            adjBuffer != MemoryBudget.defaultBufferSizeMb
        ) {
            playerSettingsDataStore.updateMemorySettings(
                targetBufferSizeMb = adjBuffer,
                parallelChunkSizeKb = adjChunkMb * 1024
            )
        }
    }

    suspend fun resetNetworkSettingsToDefaults() {
        networkChangedByHand()
        playerSettingsDataStore.resetNetworkSettingsToDefaults()
    }

    suspend fun setBufferEngineEnabled(enabled: Boolean) {
        playerSettingsDataStore.setBufferEngineEnabled(enabled)
    }

    suspend fun setParallelNetworkEnabled(enabled: Boolean) {
        // Turning Custom network on is what Automatic needs (inAutomaticShape), not a value choice.
        if (!enabled) networkChangedByHand()
        playerSettingsDataStore.setParallelNetworkEnabled(enabled)
    }

    suspend fun setEnableHttp2(enabled: Boolean) {
        networkChangedByHand()
        playerSettingsDataStore.setEnableHttp2(enabled)
    }

    suspend fun setAllowLargeTargetBuffer(enabled: Boolean) {
        playerSettingsDataStore.setAllowLargeTargetBuffer(enabled)
    }
    suspend fun setBufferBudgetManaged(enabled: Boolean) {
        playerSettingsDataStore.setBufferBudgetManaged(enabled)
    }
    suspend fun setUseParallelConnections(enabled: Boolean) {
        networkChangedByHand()
        if (!enabled) {
            playerSettingsDataStore.setUseParallelConnections(false)
            return
        }
        val current = playerSettings.first()
        if (current.nuvioPerformanceModeEnabled || current.allowLargeTargetBuffer) {
            playerSettingsDataStore.setUseParallelConnections(true)
            return
        }
        val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
        val currentChunkMb = Math.ceil(current.parallelChunkSizeKb / 1024.0).toInt()
        val (adjBuffer, adjChunkMb) = MemoryBudget.enforce(
            bufferMb,
            currentChunkMb,
            current.parallelConnectionCount
        )
        if (adjBuffer == bufferMb && adjChunkMb == currentChunkMb) {
            playerSettingsDataStore.setUseParallelConnections(true)
        } else {
            playerSettingsDataStore.updateMemorySettings(
                useParallelConnections = true,
                targetBufferSizeMb = if (adjBuffer != bufferMb) adjBuffer else null,
                parallelChunkSizeKb = adjChunkMb * 1024
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setParallelConnectionCount(count: Int) {
        networkChangedByHand()
        val current = playerSettings.first()
        if (current.nuvioPerformanceModeEnabled || current.allowLargeTargetBuffer) {
            playerSettingsDataStore.setParallelConnectionCount(count)
            return
        }
        if (count <= current.parallelConnectionCount) {
            playerSettingsDataStore.setParallelConnectionCount(count)
        } else {
            val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
            val maxChunk = MemoryBudget.maxChunkMb(bufferMb, count)
            val currentChunkMb = Math.ceil(current.parallelChunkSizeKb / 1024.0).toInt()
            val newChunkMb = currentChunkMb.coerceAtMost(maxChunk)
            if (newChunkMb == currentChunkMb) {
                playerSettingsDataStore.setParallelConnectionCount(count)
            } else {
                playerSettingsDataStore.updateMemorySettings(
                    parallelConnectionCount = count,
                    parallelChunkSizeKb = newChunkMb * 1024
                )
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setParallelChunkSizeKb(kb: Int) {
        networkChangedByHand()
        val current = playerSettings.first()
        if (current.nuvioPerformanceModeEnabled || current.allowLargeTargetBuffer) {
            playerSettingsDataStore.setParallelChunkSizeKb(kb)
            return
        }
        val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
        val maxChunk = MemoryBudget.maxChunkMb(bufferMb, current.parallelConnectionCount)
        val clampedChunkKb = kb.coerceAtMost(maxChunk * 1024)
        playerSettingsDataStore.setParallelChunkSizeKb(clampedChunkKb)
    }

    suspend fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        playerSettingsDataStore.setStreamAutoPlayMode(mode)
    }

    suspend fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        playerSettingsDataStore.setStreamAutoPlaySource(source)
    }

    suspend fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(addons)
    }

    suspend fun setStreamAutoPlayRegex(regex: String) {
        playerSettingsDataStore.setStreamAutoPlayRegex(regex)
    }

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    suspend fun setStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeFallbackEnabled(enabled)
    }

    suspend fun setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
    }

    suspend fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayReuseBingeGroup(enabled)
    }

    suspend fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        playerSettingsDataStore.setStreamAutoPlayTimeoutSeconds(seconds)
    }

    suspend fun setStreamAutoPlayEagerReadyEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayEagerReadyEnabled(enabled)
    }

    suspend fun setStillWatchingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStillWatchingEnabled(enabled)
    }

    suspend fun setStillWatchingEpisodeThreshold(threshold: Int) {
        playerSettingsDataStore.setStillWatchingEpisodeThreshold(threshold)
    }

    suspend fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        playerSettingsDataStore.setNextEpisodeThresholdMode(mode)
    }

    suspend fun setNextEpisodeThresholdPercent(percent: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdPercent(percent)
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    suspend fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(enabled)
    }

    suspend fun setStreamReuseLastLinkCacheHours(hours: Int) {
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(hours)
    }

    suspend fun setNuvioPerformanceModeEnabled(enabled: Boolean) {
        playerSettingsDataStore.setNuvioPerformanceModeEnabled(enabled)
    }
}
