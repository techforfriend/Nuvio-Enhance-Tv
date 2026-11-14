package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.SourceChipItem

enum class PlayerExitReason {
    StillWatchingPrompt
}

sealed interface PostPlayMode {
    val nextEpisode: NextEpisodeInfo

    data class AutoPlay(
        override val nextEpisode: NextEpisodeInfo,
        val searching: Boolean = false,
        val sourceName: String? = null,
        val countdownSec: Int? = null,
    ) : PostPlayMode

    data class StillWatching(
        override val nextEpisode: NextEpisodeInfo,
        val countdownSec: Int? = null,
    ) : PostPlayMode

    fun copyWithNextEpisode(nextEpisode: NextEpisodeInfo): PostPlayMode {
        if (nextEpisode == this.nextEpisode) return this
        return when (this) {
            is AutoPlay -> copy(nextEpisode = nextEpisode)
            is StillWatching -> copy(nextEpisode = nextEpisode)
        }
    }

    fun blocksNaturalCompletion(): Boolean = when (this) {
        is StillWatching -> true
        is AutoPlay -> searching || countdownSec != null
    }
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val playbackEnded: Boolean = false,
    val pendingExitReason: PlayerExitReason? = null,
    val title: String = "",
    val contentName: String? = null, // Series/show name (for series content)
    val releaseYear: String? = null, // Release year for movies
    val contentType: String? = null,
    val currentStreamName: String? = null, // Name of the current stream source
    val currentStreamUrl: String? = null,
    val currentStreamInfoHash: String? = null, // InfoHash of the playing stream (findCurrentStreamIndex: source-panel highlight, dead-source failover)
    val currentStreamFileIdx: Int? = null, // FileIdx of the playing stream (findCurrentStreamIndex, as above)
    val currentStreamAddonName: String? = null, // Addon name of the currently playing stream
    val backdrop: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val castMembers: List<MetaCastMember> = emptyList(),
    /** Title facts for the loading screen and pause overlay (26/B5): the meta's genres and scores,
     *  the playing episode's runtime (else the movie's), from the player's own meta fetch. */
    val genres: List<String> = emptyList(),
    val imdbRating: Float? = null,
    val tmdbRating: Float? = null,
    val runtimeMinutes: Int? = null,
    val showControls: Boolean = true,
    val showSeekOverlay: Boolean = false,
    val pendingPreviewSeekPosition: Long? = null,
    /** Playing position when the pending preview began (the OSD bar's ghost starts here). Set when
     *  a preview starts and left stale after it ends: read it only while [pendingPreviewSeekPosition] != null. */
    val previewSeekOriginMs: Long? = null,
    val previewThumbPositionMs: Long? = null,
    val playbackSpeed: Float = 1f,
    val loadingOverlayEnabled: Boolean = true,
    val showPlayerLoadingStatus: Boolean = false,
    val showLoadingOverlay: Boolean = true,
    val loadingMessage: String? = null,
    val loadingProgress: Float? = null,
    val pauseOverlayEnabled: Boolean = false,
    val osdClockEnabled: Boolean = true,
    val showPauseOverlay: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    /** Container chapters of the playing source, sorted by start (format-capability F2/G1). */
    val chapters: List<PlaybackChapter> = emptyList(),
    val audioDelayMs: Int = 0,
    val audioAmplificationDb: Int = 0,
    val isAudioAmplificationAvailable: Boolean = false,
    val persistAudioAmplification: Boolean = false,
    val centerMixLevelDb: Int = 0,
    val isCenterMixAvailable: Boolean = false,
    val showAudioOverlay: Boolean = false,
    val showSubtitleOverlay: Boolean = false,
    val showSubtitleTimingDialog: Boolean = false,
    val showSubtitleDelayOverlay: Boolean = false,
    val subtitleDelayMs: Int = 0,
    val subtitleAutoSyncCues: List<SubtitleSyncCue> = emptyList(),
    val subtitleAutoSyncCapturedVideoMs: Long? = null,
    val subtitleAutoSyncStatus: String? = null,
    val subtitleAutoSyncError: String? = null,
    val subtitleAutoSyncLoading: Boolean = false,
    val subtitleAutoSyncLoadedTrackKey: String? = null,
    val showSpeedPanel: Boolean = false,
    /** F3 chapter list (Spec 25 U6f), opened from the More tray's Chapters pill. */
    val showChaptersPanel: Boolean = false,
    val showMoreDialog: Boolean = false,
    // Subtitle style settings
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    /**
     * The TV receives HDR (Dolby Vision, HDR10, HLG) for this stream (Spec 62 D7): ExoPlayer's
     * first-frame output type ([isHdrOutputType]), mpv's render target transfer. Selects the HDR
     * subtitle style and the controls dim; false until known.
     */
    val isHdrOutput: Boolean = false,
    // Addon subtitles
    val addonSubtitles: List<Subtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val selectedAddonSubtitle: Subtitle? = null,
    val installedSubtitleAddonOrder: List<String> = emptyList(),
    // Episodes/streams side panel (for series)
    val showEpisodesPanel: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    val episodesAll: List<Video> = emptyList(),
    val episodesAvailableSeasons: List<Int> = emptyList(),
    val episodesSelectedSeason: Int? = null,
    val episodes: List<Video> = emptyList(),
    val currentSeason: Int? = null,
    val currentEpisode: Int? = null,
    val currentVideoId: String? = null,
    val currentEpisodeTitle: String? = null,
    val blurUnwatchedEpisodes: Boolean = false,
    val episodeWatchProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val watchedEpisodeKeys: Set<Pair<Int, Int>> = emptySet(),
    val showEpisodeStreams: Boolean = false,
    val isLoadingEpisodeStreams: Boolean = false,
    val episodeStreamsError: String? = null,
    val episodeAllStreams: List<Stream> = emptyList(),
    // Upstreams a gateway reported as failed for the episode list (Spec 42 D7 foot).
    val episodeMissingUpstreams: List<String> = emptyList(),
    // The user's pick (Spec 51 M7); [episodeListView] is [episodeAllStreams] derived under it and
    // the stream list limit, with the effective selection (PlayerRuntimeControllerStreamLimit).
    val episodeListSelection: StreamListSelection = StreamListSelection.All,
    // The filters sheet's values on (Spec 65 P1): view-only, cleared each time the list opens (D18).
    val episodeFacetFilter: FacetFilter = FacetFilter.NONE,
    val episodeListView: StreamListView = StreamListView(),
    val episodeAvailableAddons: List<String> = emptyList(),
    val episodeSourceChips: List<SourceChipItem> = emptyList(),
    val episodeStreamsForVideoId: String? = null,
    val episodeStreamsSeason: Int? = null,
    val episodeStreamsEpisode: Int? = null,
    val episodeStreamsTitle: String? = null,
    // The episode list's target runtime (its Video.runtime), the limit's per-list runtime (Spec 51 M3).
    val episodeStreamsRuntimeMinutes: Int? = null,
    // Stream sources side panel (for switching streams during playback)
    val showSourcesPanel: Boolean = false,
    val isLoadingSourceStreams: Boolean = false,
    val sourceStreamsError: String? = null,
    // The list last loaded, possibly for a previous episode: logic acting on the playing
    // item's list reads PlayerRuntimeController.sourceStreamsForPlayingItem().
    val sourceAllStreams: List<Stream> = emptyList(),
    // Upstreams a gateway reported as failed in the last answer (Spec 42 D7 foot).
    val sourceMissingUpstreams: List<String> = emptyList(),
    // URLs proven dead this session (non-media body / 404 / 410); greyed in the panel.
    val deadSourceStreamUrls: Set<String> = emptySet(),
    // Spec 75 S5: the failover the loading screen names until the next source's first frame
    // (cleared with the failover budget too), and the chain the failover gave up on.
    val failoverNotice: FailoverNotice? = null,
    val exhaustedFailover: ExhaustedFailover? = null,
    // As the episode list's pair, for [sourceAllStreams].
    val sourceListSelection: StreamListSelection = StreamListSelection.All,
    val sourceFacetFilter: FacetFilter = FacetFilter.NONE,
    val sourceListView: StreamListView = StreamListView(),
    /** The show's preferred source (Spec 65 P6); both lists show its matches first. */
    val preferredSource: PreferredSource? = null,
    val sourceAvailableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    val showFileSizeBadges: Boolean = true,
    val useAddonFormatting: Boolean = false,
    /** The failure the error box shows (Spec 75 S4); null while nothing failed. */
    val error: PlaybackFailure? = null,
    val pendingSeekPosition: Long? = null, // For resuming from saved progress
    // Parental guide overlay
    val parentalWarnings: List<ParentalWarning> = emptyList(),
    val showParentalGuide: Boolean = false,
    val parentalGuideHasShown: Boolean = false,
    // Skip intro
    val activeSkipInterval: SkipInterval? = null,
    val skipIntervalDismissed: Boolean = false,
    // Next episode card
    val nextEpisode: NextEpisodeInfo? = null,
    val postPlayMode: PostPlayMode? = null,
    val postPlayDismissedForCurrentEpisode: Boolean = false,
    /** Spec 45 Previous: the episode before this one (same shape as [nextEpisode]; null when none). */
    val previousEpisode: NextEpisodeInfo? = null,
    /** Set while a Previous press finds a source: the post-play card shows it as "Previous episode"
     *  (Spec 45 P1). Kept apart from [postPlayMode], whose auto-play and focus rules are Next's. */
    val previousEpisodeSearch: NextEpisodeInfo? = null,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean = false,
    // Stream source badge
    val showStreamSourceIndicator: Boolean = false,
    val streamSourceIndicatorText: String = "",
    val showPlayerEngineSwitchInfo: Boolean = false,
    val playerEngineSwitchInfoText: String = "",
    // Frame rate matching
    val detectedFrameRateSource: FrameRateSource? = null,
    val detectedFrameRate: Float = 0f,
    val afrProbeRunning: Boolean = false,
    val internalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.OFF,
    val useLibass: Boolean = false,
    val libassRenderType: LibassRenderType = LibassRenderType.OVERLAY_OPEN_GL,
    val displayModeInfo: DisplayModeInfo? = null,
    val showDisplayModeInfo: Boolean = false,
    // Aspect ratio
    val aspectMode: AspectMode = AspectMode.ORIGINAL,
    val tunnelingEnabled: Boolean = false,
    val showAspectRatioIndicator: Boolean = false,
    val aspectRatioIndicatorText: String = "",
    // OSD top-band tech badges; triggers and the ExoPlayer-only rule: refreshOsdTechBadges
    val osdTechBadges: List<OsdTechBadge> = emptyList(),
    // How the selected audio leaves the box (P1); refreshAudioOutputMode. Null = unknown / mpv.
    val audioOutputMode: AudioOutputMode? = null,
    // Amber toast copy, once per stream URL (P2, data st8); refreshOsdTechBadges sets it.
    val degradedNotice: String? = null,
    // Spec 35: the file's length is far off the metadata runtime; playback holds at 0:00 under
    // the warning until Pick another stream / Play anyway (or any play) clears it.
    val runtimeMismatch: RuntimeMismatchPolicy.Mismatch? = null,
    // Spec 33 D6: the start holds for the network test (loading screen shows its steps; Back skips).
    val networkTestHold: NetworkTestHold? = null,
    // Spec 33 D7: what the network test set for the film it ran on (pause-screen chip), for as
    // long as that stream plays; [networkTestNoticePending] = its 4 s notice is still to show.
    val networkTestResult: NetworkTestResult? = null,
    val networkTestNoticePending: Boolean = false,
    // Live playback stats overlay (task 2.2)
    val showPlaybackStatsOverlay: Boolean = false,
    // Torrent streaming state
    val isTorrentStream: Boolean = false,
    val torrentDownloadSpeed: Long = 0L,
    val torrentUploadSpeed: Long = 0L,
    val torrentPeers: Int = 0,
    val torrentSeeds: Int = 0,
    val torrentTotalProgress: Float = 0f,
    // Whole torrent size + the streamed file's position in its list (Stats P2P section, P23); 0 = unknown.
    val torrentSizeBytes: Long = 0L,
    val torrentFileNumber: Int = 0,
    val torrentFileCount: Int = 0,
    // Torrent mid-playback rebuffering (shown on the buffering spinner, not loading overlay)
    val torrentBufferingMessage: String? = null,
    val torrentBufferingProgress: Float = 0f,
    // When true, suppress the passive torrent stats text (buffer, seeds, peers, speed) on the
    // loading overlay and rebuffering indicator; the Stats panel's P2P section ignores it.
    val hideTorrentStats: Boolean = true
) {
    /** The style cues are drawn with (Spec 62 H2). */
    val effectiveSubtitleStyle: SubtitleStyleSettings get() = subtitleStyle.forOutput(isHdrOutput)

    /** In-player style edits go to the HDR text colour and brightness. */
    val isEditingHdrSubtitleStyle: Boolean get() = subtitleStyle.hdrStyleApplies(isHdrOutput)

    /** The style the in-player editor shows. */
    val editableSubtitleStyle: SubtitleStyleSettings get() = subtitleStyle.editableForOutput(isHdrOutput)

    /** The factor controls and panels over the video draw at (1 = undimmed). */
    val playerControlsBrightness: Float get() = subtitleStyle.playerControlsBrightness(isHdrOutput)
}

/** The network test holding this start: the [service] being checked and the 1-based [step] running (0 = probing). */
data class NetworkTestHold(val service: String, val step: Int = 0)

/** The network test's outcome for the film it held: the serving [service] and this film's [connections]. */
data class NetworkTestResult(val service: String, val connections: Int)

data class PlaybackTimelineState(
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    /** Position (ms) up to which the player has buffered ahead of the playhead. */
    val bufferedPosition: Long = 0L
)

data class TrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val trackId: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isForced: Boolean = false,
    val isSelected: Boolean = false,
    val sampleRate: Int? = null,
    // Subtitle rows (P18): hearing-impaired track; the container's default flag.
    val isSdh: Boolean = false,
    val isDefault: Boolean = false
)

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val runtimeMinutes: Int? = null,
    /** Playable now: aired AND not marked unavailable by the addon (T31, P42); every
     *  next-episode card / prompt / auto-play gate reads it. */
    val hasAired: Boolean,
    /** Why it is not playable ("not aired yet" or "Unavailable"); null when [hasAired]. */
    val unairedMessage: String?,
    val isOtherType: Boolean = false
)

data class SubtitleSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

sealed class PlayerEvent {
    data object OnPlayPause : PlayerEvent()
    data object OnSeekForward : PlayerEvent()
    data object OnSeekBackward : PlayerEvent()
    data class OnSeekBy(val deltaMs: Long) : PlayerEvent()
    data class OnPreviewSeekBy(val deltaMs: Long) : PlayerEvent()
    data object OnCommitPreviewSeek : PlayerEvent()
    data class OnSeekTo(val position: Long) : PlayerEvent()
    data class OnSelectAudioTrack(val index: Int) : PlayerEvent()
    data class OnSetAudioDelayMs(val delayMs: Int) : PlayerEvent()
    data class OnStepAudioDelayHeld(val deltaMs: Int) : PlayerEvent()
    data object OnAudioDelayHoldReleased : PlayerEvent()
    data class OnSetAudioAmplificationDb(val db: Int) : PlayerEvent()
    data class OnSetPersistAudioAmplification(val enabled: Boolean) : PlayerEvent()
    data class OnSetCenterMixLevelDb(val db: Int) : PlayerEvent()
    data class OnSelectSubtitleTrack(val index: Int) : PlayerEvent()
    data object OnDisableSubtitles : PlayerEvent()
    data class OnSelectAddonSubtitle(val subtitle: Subtitle) : PlayerEvent()
    data class OnSetPlaybackSpeed(val speed: Float) : PlayerEvent()
    data object OnToggleControls : PlayerEvent()
    data object OnShowAudioOverlay : PlayerEvent()
    data object OnShowSubtitleOverlay : PlayerEvent()
    data object OnShowSubtitleTimingDialog : PlayerEvent()
    data object OnDismissSubtitleTimingDialog : PlayerEvent()
    data object OnCaptureSubtitleAutoSyncTime : PlayerEvent()
    data class OnApplySubtitleAutoSyncCue(val cueStartTimeMs: Long) : PlayerEvent()
    data object OnReloadSubtitleAutoSyncCues : PlayerEvent()
    data object OnShowSubtitleDelayOverlay : PlayerEvent()
    data object OnHideSubtitleDelayOverlay : PlayerEvent()
    data class OnAdjustSubtitleDelay(val deltaMs: Int, val showOverlay: Boolean = true) : PlayerEvent()
    data class OnResetSubtitleDelay(val showOverlay: Boolean = true) : PlayerEvent()
    data object OnShowSpeedPanel : PlayerEvent()
    data object OnShowChaptersPanel : PlayerEvent()
    data object OnShowMoreDialog : PlayerEvent()
    data object OnDismissMoreDialog : PlayerEvent()
    data object OnShowEpisodesPanel : PlayerEvent()
    data object OnDismissEpisodesPanel : PlayerEvent()
    data object OnBackFromEpisodeStreams : PlayerEvent()
    data class OnEpisodeSeasonSelected(val season: Int) : PlayerEvent()
    data class OnEpisodeSelected(val video: Video) : PlayerEvent()
    data object OnReloadEpisodeStreams : PlayerEvent()
    data class OnEpisodeListSelected(val selection: StreamListSelection) : PlayerEvent()
    data class OnEpisodeFilterChanged(val filter: FacetFilter) : PlayerEvent()
    data class OnEpisodeStreamSelected(val stream: Stream) : PlayerEvent()
    data object OnShowSourcesPanel : PlayerEvent()
    data object OnDismissSourcesPanel : PlayerEvent()
    data object OnReloadSourceStreams : PlayerEvent()
    data class OnSourceListSelected(val selection: StreamListSelection) : PlayerEvent()
    data class OnSourceFilterChanged(val filter: FacetFilter) : PlayerEvent()
    data class OnSourceStreamSelected(val stream: Stream) : PlayerEvent()
    /** Prefer [stream]'s source for the show (Spec 65 P6); null stops preferring one. */
    data class OnPreferSource(val stream: Stream?) : PlayerEvent()
    data object OnDismissTransientOverlay : PlayerEvent()
    data object OnParentalGuideHide : PlayerEvent()
    data class OnShowDisplayModeInfo(val info: DisplayModeInfo) : PlayerEvent()
    data object OnHideDisplayModeInfo : PlayerEvent()
    /** The degraded toast finished its 6 s; clears [PlayerUiState.degradedNotice] if it is still [message]. */
    data class OnDegradedNoticeShown(val message: String) : PlayerEvent()
    /** Spec 35 warning: [playAnyway] plays this stream, else the Sources panel opens. */
    data class OnRuntimeMismatchChoice(val playAnyway: Boolean) : PlayerEvent()
    /** Spec 33 D6: Back during the network test hold; the film plays now, the test runs next time. */
    data object OnSkipNetworkTest : PlayerEvent()
    data object OnDismissPauseOverlay : PlayerEvent()
    data object OnSkipIntro : PlayerEvent()
    data object OnDismissSkipIntro : PlayerEvent()
    data object OnPlayNextEpisode : PlayerEvent()
    data object OnPlayPreviousEpisode : PlayerEvent()
    data object OnDismissNextEpisodeCard : PlayerEvent()
    data object OnStillWatchingContinue : PlayerEvent()
    data object OnDismissStillWatchingPrompt : PlayerEvent()

    // Subtitle style events (for in-player style tab)
    data class OnSetSubtitleSize(val size: Int) : PlayerEvent()
    /** Sets the HDR text colour when [hdr] (the style view showed the HDR style), else the text colour. */
    data class OnSetSubtitleTextColor(val color: Int, val hdr: Boolean) : PlayerEvent()
    /** Spec 62 H2c: the HDR brightness, in percent (clamped and snapped by the store). */
    data class OnSetSubtitleHdrBrightness(val percent: Int) : PlayerEvent()
    data class OnSetSubtitleBold(val bold: Boolean) : PlayerEvent()
    data class OnSetSubtitleEdgeStyle(val style: SubtitleEdgeStyle) : PlayerEvent()
    data class OnSetSubtitleOutlineColor(val color: Int) : PlayerEvent()
    data class OnSetSubtitleVerticalOffset(val offset: Int) : PlayerEvent()
    /** [hdr]: the style view showed the HDR style, so its HDR colour and brightness reset instead of the text colour. */
    data class OnResetSubtitleDefaults(val hdr: Boolean) : PlayerEvent()
    data object OnToggleAspectRatio : PlayerEvent()
    data object OnSwitchInternalPlayerEngine : PlayerEvent()
    data object OnTogglePlaybackStats : PlayerEvent()
    data object OnHidePlaybackStats : PlayerEvent()
}

data class ParentalWarning(
    val label: String,
    val severity: String
)

data class DisplayModeInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val statusMessage: String? = null
)

enum class FrameRateSource {
    TRACK,
    PROBE
}

val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
