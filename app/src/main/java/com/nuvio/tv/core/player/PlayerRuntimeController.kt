package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceCallback
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class PlayerRuntimeController(
    internal val context: Context,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    internal val streamRepository: StreamRepository,
    // S5 part 3: the binge lookahead ranks (and prewarms) the next episode's winner.
    internal val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    internal val addonRepository: AddonRepository,
    internal val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    internal val parentalGuideRepository: ParentalGuideRepository,
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    internal val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    internal val preferredSourceDataStore: com.nuvio.tv.data.local.PreferredSourceDataStore,
    internal val failedReleases: com.nuvio.tv.core.stream.FailedReleases,
    internal val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    internal val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    internal val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    internal val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    internal val torrentService: TorrentService,
    internal val torrentSettings: com.nuvio.tv.core.torrent.TorrentSettings,
    internal val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    internal val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    internal val watchCountDataStore: com.nuvio.tv.data.local.WatchCountDataStore,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        // Stall watchdog: re-seeks past the buffered edge if bufferedPosition stops
        // advancing during STATE_BUFFERING. Fires before OkHttp's readTimeout.
        internal const val STALL_WATCHDOG_THRESHOLD_MS = 15_000L
        internal const val STALL_WATCHDOG_POLL_INTERVAL_MS = 1_000L

        // Startup watchdog (nt34): covers starting_stream -> first frame. The stall
        // watchdog's remedy is a self-seek and it bails when buffered <= playhead;
        // the first-frame watchdog only arms at STATE_READY. A hang before READY
        // (the vendor Codec2 service wedging during decoder allocation, 2026-07-15)
        // previously produced an infinite spinner with no error. Margin: worst
        // legitimate observed first frame is ~14.5 s from press (nt32 TTFF baselines).
        internal const val STARTUP_WATCHDOG_TIMEOUT_MS = 20_000L
        // T3 (ysosrs 01b6b4bae): hard ceiling for extend-on-buffered-progress (and, Spec 86 D3,
        // extend-while-bytes-arrive) — bounds
        // the last extension grant, so the latest possible fire is the ceiling plus one
        // interval. Extend/fire arithmetic lives in PlayerStartupWatchdogPolicy.
        internal const val STARTUP_WATCHDOG_CEILING_MS = 60_000L
        internal const val ADDON_SUBTITLE_TRACK_ID_PREFIX = "nuvio-addon-sub:"
    }

    internal data class PendingAudioSelection(
        val language: String?,
        val name: String?,
        val streamUrl: String
    )

    internal data class RememberedTrackSelection(
        val language: String?,
        val name: String?,
        val trackId: String? = null,
        val indexHint: Int? = null,
        val languageIndexHint: Int? = null,
        val isForcedHint: Boolean? = null
    )

    internal sealed class RememberedSubtitleSelection {
        data object Disabled : RememberedSubtitleSelection()
        data class Internal(
            val track: RememberedTrackSelection
        ) : RememberedSubtitleSelection()
        data class Addon(
            val id: String,
            val url: String,
            val language: String,
            val addonName: String
        ) : RememberedSubtitleSelection()
    }

    internal data class TrackPreference(
        val audio: RememberedTrackSelection? = null,
        val subtitle: RememberedSubtitleSelection? = null
    )

    internal data class PendingEngineSwitchTrackPreference(
        val streamUrl: String,
        val preference: TrackPreference,
        val sourceEngine: InternalPlayerEngine
    )

    internal data class ExplicitSubtitleSelectionForEngineSwitch(
        val streamUrl: String,
        val selection: RememberedSubtitleSelection
    )

    internal val navigationArgs = PlayerNavigationArgs.from(savedStateHandle)
    internal val initialStreamUrl: String = navigationArgs.streamUrl
    internal val title: String = navigationArgs.title
    internal val streamName: String? = navigationArgs.streamName
    internal val year: String? = navigationArgs.year
    internal val headersJson: String? = navigationArgs.headersJson
    internal val contentId: String? = navigationArgs.contentId
    internal val contentType: String? = navigationArgs.contentType
    internal val contentName: String? = navigationArgs.contentName
    internal val poster: String? = navigationArgs.poster
    internal val backdrop: String? = navigationArgs.backdrop
    internal val logo: String? = navigationArgs.logo
    internal val videoId: String? = navigationArgs.videoId
    internal val initialSeason: Int? = navigationArgs.initialSeason
    internal val initialEpisode: Int? = navigationArgs.initialEpisode
    internal val initialEpisodeTitle: String? = navigationArgs.initialEpisodeTitle
    internal val launchStartedAtElapsedMs: Long? = navigationArgs.launchStartedAtMs
    internal val rememberedAudioLanguage: String? = navigationArgs.rememberedAudioLanguage
    internal val rememberedAudioName: String? = navigationArgs.rememberedAudioName

    /** Spec 80 D20: a changelog release video: no stored diagnostics, and it ends on its own however short. */
    internal val releaseVideo: Boolean = navigationArgs.releaseVideo
    internal val mediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)

    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize

    /**
     * The playing stream's listed release facts (addon filename hint, size from the listing), the
     * ones a candidate is keyed by in the failure store (Spec 65 P5). Unlike [currentFilename]
     * (may be URL-derived) and [currentVideoSize] (the hasher overwrites it with the exact size),
     * they are set only from the stream. Seeded from the route, which on a Reuse-last-link start
     * carries the cache's values (possibly the hasher's size): a key that then misses only
     * weakens the exclusion to the URL key, never excludes another file. They also find the playing
     * row in a reloaded list (Spec 86 R10, `playingRowIndex`); a Reuse-last-link start carries no
     * addon name, so that match is off there.
     */
    internal var currentListedFilename: String? = navigationArgs.filename
    internal var currentListedSize: Long? = navigationArgs.videoSize

    /**
     * Expected runtime in minutes from the title's metadata, resolved by the
     * stream screen before the press. Null when unknown; consumers must treat
     * null as "do not judge" rather than as zero.
     *
     * A var, not a val: this controller survives a binge transition, so the value
     * must follow the episode rather than stay pinned to the runtime carried in
     * the original nav args. Updated in playNextEpisode from Video.runtime.
     */
    internal var expectedRuntimeMinutes: Int? = navigationArgs.runtimeMinutes

    /**
     * One-shot guard for the placeholder probe. STATE_READY fires again after
     * seeks and rebuffers; the probe is about the file, not the moment, so it
     * runs once per stream. On mpv it latches the duration check after the first
     * frame instead (Spec 85 E7).
     */
    internal var placeholderProbeDone: Boolean = false
    /** Spec 21 P3: this stream was rejected as a placeholder (auto-advance or error); reset per stream. */
    internal var placeholderRejectedForStream: Boolean = false
    /** Spec 35: the runtime mismatch check has judged this stream (it runs once per stream). */
    internal var runtimeMismatchChecked: Boolean = false
    /** Spec 35: this stream started from a saved position > 0 (a resume), so it never warns. */
    internal var resumePositionApplied: Boolean = false
    /**
     * Spec 57 D2: while non-null, the player decodes from 0 to prime the video decoder and seeks here after
     * the prime frame (PlayerStartPosition.shouldPrimeFromZero). Read through exoCarryPositionMs; a user seek
     * during the prime moves it (seekPlaybackTo).
     */
    internal var startPrimeTargetMs: Long? = null
    /**
     * Spec 57 D2: this build's video track needs a decoder warmed from 0 to start mid-file
     * (PlayerStartPosition.needsWarmDecoder), so an error retry rebuilds instead of re-preparing in place.
     */
    internal var videoNeedsWarmDecoder: Boolean = false
    /**
     * Spec 57 D4: the session started with "Start from beginning" and has saved no progress yet, so a source
     * switch or failover must not reload the old resume point (PlayerStartPosition.readsSavedResumeOnSwitch).
     */
    internal var startFromBeginningPending: Boolean = navigationArgs.startFromBeginning
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    /** The playing stream's own subtitles; merged ahead of subtitle-addon results in fetchAddonSubtitlesNow. */
    internal var currentStreamSubtitles: List<com.nuvio.tv.domain.model.Subtitle> = navigationArgs.streamSubtitles
    internal var currentStreamDescription: String? = navigationArgs.streamDescription
    internal var contentLanguage: String? = navigationArgs.contentLanguage
    internal var currentStreamUrl: String
    internal var currentStreamResponseHeaders: Map<String, String> = emptyMap()
    internal var currentStreamMimeType: String?
    internal var currentHeaders: Map<String, String>

    init {
        val initialPlaybackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(
            initialStreamUrl,
            PlayerMediaSourceFactory.parseHeaders(headersJson)
        )
        currentStreamUrl = initialPlaybackRequest.url
        currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
            url = initialPlaybackRequest.url,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders
        )
        currentHeaders = initialPlaybackRequest.headers
    }

    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentHeaders(): Map<String, String> = currentHeaders

    fun stopAndRelease() {
        // nt33: the diagnostics record persists at FIRST FRAME, and on a mid-episode
        // back-out no later persist runs at all (BUFFER_SUMMARY absent across the
        // 8 Aug captures proves the natural-end path is skipped), so under AFR -
        // where the first frame renders during the settle, before MAT engages - the
        // card's audioPath could never be anything but null however it was written.
        // Re-persist once at teardown from the tick-filled field, guarded to the
        // clean-playback case so an error record is never clobbered by a stale
        // 'Played' one.
        val audioPathAtTeardown = currentAudioPathDescription
        val lastRecord = lastPlaybackDiagnostics
        if (audioPathAtTeardown != null &&
            lastRecord.result == "Played" &&
            lastRecord.audioPath == null &&
            !playbackFatalErrorRecorded
        ) {
            val updated = lastRecord.copy(audioPath = audioPathAtTeardown)
            lastPlaybackDiagnostics = updated
            persistLastPlaybackDiagnostics(updated)
        }
        releasePlayer(flushPlaybackState = true)
    }

    /**
     * Stores [diagnostics] as the last playback (Settings › Diagnostics, the stream test, the
     * device sweep's target), off the caller's path; never for a release video (Spec 80 D20), which
     * keeps the user's last real title there.
     */
    internal fun persistLastPlaybackDiagnostics(diagnostics: LastPlaybackDiagnostics) {
        if (releaseVideo) return
        scope.launch {
            runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(diagnostics) }
        }
    }

    internal var currentVideoId: String? = videoId
    internal var currentSeason: Int? = initialSeason
    internal var currentEpisode: Int? = initialEpisode
    internal var currentEpisodeTitle: String? = initialEpisodeTitle

    internal val _uiState = MutableStateFlow(
        PlayerUiState(
            title = title,
            contentName = contentName,
            currentStreamName = streamName,
            currentStreamUrl = currentStreamUrl,
            currentStreamInfoHash = navigationArgs.infoHash,
            currentStreamFileIdx = navigationArgs.fileIdx,
            currentStreamAddonName = navigationArgs.addonName,
            releaseYear = year,
            runtimeMinutes = navigationArgs.runtimeMinutes?.takeIf { it > 0 },
            contentType = contentType,
            backdrop = backdrop,
            logo = logo,
            showLoadingOverlay = true,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    internal fun consumePendingExitReason() {
        _uiState.update { it.copy(pendingExitReason = null) }
    }

    internal val _playbackTimeline = MutableStateFlow(PlaybackTimelineState())
    val playbackTimeline: StateFlow<PlaybackTimelineState> = _playbackTimeline.asStateFlow()

    internal fun updatePlaybackTimeline(
        currentPosition: Long = _playbackTimeline.value.currentPosition,
        duration: Long = _playbackTimeline.value.duration,
        bufferedPosition: Long = _playbackTimeline.value.bufferedPosition
    ) {
        _playbackTimeline.update {
            it.copy(
                currentPosition = currentPosition.coerceAtLeast(0L),
                duration = duration.coerceAtLeast(0L),
                bufferedPosition = bufferedPosition.coerceAtLeast(0L)
            )
        }
    }

    internal fun resetPlaybackTimeline() {
        _playbackTimeline.value = PlaybackTimelineState()
    }

    internal var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer?
        get() = _exoPlayer
    @Volatile var videoAspectRatio: Float = 0f
    @Volatile var exoPlayerView: androidx.media3.ui.PlayerView? = null
    internal var _loadControl: DefaultLoadControl? = null
    internal var playbackSpeedAwareAudioSink: PlaybackSpeedAwareAudioSink? = null
    internal var matRoutingAudioSink: com.nuvio.tv.core.player.mat.MatRoutingAudioSink? = null

    internal var progressJob: Job? = null
    internal var firstFrameWatchdogJob: Job? = null
    internal var stallWatchdogJob: Job? = null
    internal var startupWatchdogJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hidePlayerEngineSwitchInfoJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
    internal var subtitleAutoSyncLoadJob: Job? = null
    /** ExoPlayer sidecar path: external addon cues without setMediaSource (preserves buffer). */
    internal var sidecarSubtitleJob: Job? = null
    internal var activeSidecarSubtitleKey: String? = null
    internal var sidecarTimedCues: List<androidx.media3.extractor.text.CuesWithTiming> = emptyList()
    internal var lastSidecarCueSignature: Long? = null
    internal var exoSubtitleViewRef: WeakReference<androidx.media3.ui.SubtitleView>? = null
    /** Cancels previous TEXT-track bounce jobs when subtitle delay is adjusted repeatedly. */
    internal var subtitleTimingRefreshJob: Job? = null
    internal var nextEpisodeAutoPlayJob: Job? = null
    internal var stillWatchingPromptJob: Job? = null
    internal var sourceStreamsJob: Job? = null
    internal var sourceBadgeJob: Job? = null
    internal var sourceBadgedAddonNames: Set<String> = emptySet()
    internal var sourceStreamsScope: kotlinx.coroutines.CoroutineScope? = null
    internal var episodeStreamsScope: kotlinx.coroutines.CoroutineScope? = null
    internal var episodeBadgeJob: Job? = null
    internal var sourceChipErrorDismissJob: Job? = null
    internal var sourceStreamsCacheRequestKey: String? = null
    internal var sourceStreamsFetchCompleted: Boolean = false
    internal var hostActivityRef: WeakReference<Activity>? = null
    internal var initialPlaybackStarted: Boolean = false
    internal var lastPlaybackDiagnostics: LastPlaybackDiagnostics =
        LastPlaybackDiagnostics.EMPTY
    /** Set when this playback hit a fatal error; guards the teardown diagnostics re-persist. */
    internal var playbackFatalErrorRecorded: Boolean = false
    internal val playbackAnalyticsDiagnostics = PlayerPlaybackAnalyticsDiagnostics()
    /** Spec 75 S3: the six steps of the attempt now opening; the playback HTTP client reports into it. */
    internal val stepClock = PlaybackStepClock().also { PlaybackConnectionEvents.stepClock = it }
    /** The Finding sources time of the stream now playing: the launch's search, null once switched in the player. */
    internal var stepSourcesFoundMs: Long? = navigationArgs.sourcesFoundMs
    internal var loadingDiagnosticsStartedAtMs: Long = 0L
    internal var currentLoadingPhase: String = "idle"
    internal var currentLoadingPhaseStartedAtMs: Long = 0L
    internal var lastLoadingDiagnosticSignature: String = ""
    internal var startupPhaseSequence: Int = 0

    internal var lastSavedPosition: Long = 0L
    internal val saveThresholdMs = 5000L
    internal var hasMarkedCurrentEpisodeCompleted: Boolean = false
    internal var lastKnownDuration: Long = 0L

    internal var playbackStartedForParentalGuide = false
    internal var hasRenderedFirstFrame = false
    // Spec 61 Q2: the Exo progress tick forced a natural end for a position past the duration.
    internal var exoEndOverrunHandled = false
    internal var shouldEnforceAutoplayOnFirstReady = true

    internal var rebufferCount: Int = 0

    // T9 (ysosrs 22aecb0e8, nt14): wall time (elapsedRealtime) of the last
    // DISCONTINUITY_REASON_SEEK, whether the currently-open buffering episode was
    // seek-induced (excluded from rebuffer stats), and the grace window
    // (upstream capture: seek-induced entries 2-4ms after the stamp, genuine
    // ones >=7.9s from any seek).
    internal var lastSeekWallMs: Long = 0L
    internal var currentRebufferSeekInduced: Boolean = false
    internal val seekRebufferGraceMs: Long = 1_500L

    // nt8: TrueHD startup-storm auto-recovery attempts this playback session (cap 2).
    internal var truehdStormRecoveryAttempts: Int = 0

    // nt9: wall time of the last storm-recovery seek. Attempts are spaced so the
    // second lands after the post-mode-switch settle window (~5-8 s measured)
    // instead of 0.7 s after the first, which was provably wasted on device.
    internal var truehdStormLastRecoveryAtMs: Long = 0L
    // nt11: player-timeline position (ms) latched on the first tick that observes
    // an un-consumed storm, so recovery rolls back to onset, not the raced pos.
    // -1L = no storm currently latched.
    internal var truehdStormOnsetPosMs: Long = -1L
    // nt11 (0.8.2): lock-snap classifier state (last tick position/wall time and
    // last discontinuity stamp). Its SUSPECT verdict feeds the nt12 snap-recovery
    // latch below, so it drives SNAP_RECOVERY seeks.
    internal var snapShadowLastTickPosMs: Long = -1L
    internal var snapShadowLastTickWallMs: Long = 0L
    internal var snapShadowLastDiscontinuityWallMs: Long = 0L

    // nt12 (0.8.2): pending snap-recovery latch -- the pre-snap tick position
    // (-1L when none) and the wall time it was latched, consumed through the
    // shared storm recovery budget with a freshness TTL.
    internal var snapRecoveryPendingPosMs: Long = -1L
    internal var snapRecoveryPendingAtWallMs: Long = 0L

    // nt14 (0.8.2): corroborated early budget reset state -- wall time of the
    // last classifier SUSPECT (any disposition), wall time of the last early
    // reset, and the total recoveries this playback (stand-down ceiling).
    internal var snapLastSuspectWallMs: Long = 0L
    internal var snapEarlyResetLastAtMs: Long = 0L
    internal var stormRecoveryTotalThisPlayback: Int = 0

    // Spec 70 R2 (D5): frozen-picture recovery state per player build (new stream or same-URL rebuild;
    // reset with the storm latches):
    // the elapsedRealtime of the last recovery seek (null = none) and whether rung 2 (failover) ran.
    internal var freezeRecoverySeekAtMs: Long? = null
    internal var freezeFailoverDone: Boolean = false
    internal var rebufferTotalMs: Long = 0L
    internal var rebufferStartedAtMs: Long = 0L
    /**
     * Back buffer (ms) the live ExoPlayer was built with. media3 bakes it at
     * construction and never re-reads it, so the reuse branch restores the built
     * value; first frame reports it in BACK_BUFFER_RESOLVED and never changes it.
     */
    internal var effectiveBackBufferDurationMs: Int = 0
    /** Custom LoadControl for this playback (null when using stock); used to resolve the back buffer at first frame. */
    internal var currentBitrateAwareLoadControl: BitrateAwareLoadControl? = null
    /** nt12: the per-stream listeners registered on the live ExoPlayer, tracked so a
     *  reused instance can drop the previous stream's listeners before re-adding. */
    internal var currentExoPlayerListener: androidx.media3.common.Player.Listener? = null
    internal var currentExoAnalyticsListener: androidx.media3.exoplayer.analytics.AnalyticsListener? = null
    /** nt12: fingerprint of the constructor-baked configuration of the live ExoPlayer;
     *  a transition may reuse the instance only when the fresh derivation matches. */
    internal var lastExoConstructionFingerprint: ExoConstructionFingerprint? = null
    /** nt16: the settings last pushed onto the media-source factory, so the chunk-0
     *  pre-start can derive geometry without suspending on the settings Flow. */
    @Volatile internal var lastAppliedPlayerSettings: PlayerSettings? = null
    internal var metaVideos: List<Video> = emptyList()
    internal var metaGenres: List<String> = emptyList()
    /** The meta's runtime text (movies), for [playingRuntimeMinutes] on item switches. */
    internal var metaRuntime: String? = null
    internal var metaCountry: String? = null
    /** Meta `imdb_id`: routes subtitle requests to `tt`-only addons for tmdb:/mal:/kitsu: content. */
    internal var metaImdbId: String? = null
    internal var metaFetchJob: Job? = null
    /** In-flight fetchAddonSubtitles; cancelled when replaced or on a stream change. */
    internal var addonSubtitleFetchJob: Job? = null
    internal var nextEpisodeVideo: Video? = null
    internal var previousEpisodeVideo: Video? = null
    internal var userPausedManually = false

    internal var isInBackground: Boolean = false
    internal var pendingBackgroundCrashRecovery: Boolean = false
    internal var backgroundCrashSavedPositionMs: Long = 0L

    // The online sources' intervals; playback reads effectiveSkipIntervals() (Spec 61 Q6).
    internal var skipIntervals: List<SkipInterval> = emptyList()
    internal val chapterSkipMerge = ChapterSkipMerge()
    internal var skipIntroEnabled: Boolean = true
    internal var parentalGuideEnabled: Boolean = false
    internal var autoSkipSegmentTypes: Set<AutoSkipSegmentType> = emptySet()
    internal var playerSettingsInitialized: Boolean = false
    internal var skipIntroFetchedKey: String? = null
    /** Content id the settings collector last requested a parental guide for. */
    internal var parentalGuideFetchedKey: String? = null
    internal val autoSkippedIntervalKeys: MutableSet<String> = mutableSetOf()
    /** Track-preference channels and restore latches (Spec 21 P6). */
    internal val trackSelection = TrackSelectionState()
    internal var lastSubtitlePreferredLanguage: String? = null
    internal var lastSubtitleSecondaryLanguage: String? = null
    internal var lastUseForcedSubtitles: Boolean? = null
    internal var pendingAddonSubtitleLanguage: String? = null
    internal var pendingAddonSubtitleTrackId: String? = null
    internal var pendingAudioSelectionAfterSubtitleRefresh: PendingAudioSelection? = null
    // nt6: an auto-restored addon subtitle whose attach would require a
    // mid-playback media reload is parked here and attached at the next user
    // pause (or superseded by any explicit selection). See
    // autoSelectAddonSubtitleDeferringReload.
    internal var deferredAutoAddonSubtitle: com.nuvio.tv.domain.model.Subtitle? = null
    internal var attachedAddonSubtitleKeys: Set<String> = emptySet()
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var autoSwitchInternalPlayerOnErrorEnabled: Boolean = false
    internal var addonSubtitlesEnabled: Boolean = false
    internal var startupEngineFailoverTriggered: Boolean = false
    // Spec 85 G1: URLs that took their one automatic engine switch (either direction) since the
    // stream identity last changed (forgetStreamEngineForNewStream clears it), so a file neither
    // engine plays moves to the next source instead of looping. A manual switch to mpv re-arms its
    // URL; a manual switch to ExoPlayer uses it up (the hop never undoes that choice).
    internal val engineHopStreamUrls: MutableSet<String> = mutableSetOf()
    // Spec 85 G2: this start's track trigger the background held back, for resume (reset per start).
    internal var backgroundHeldMpvHopReason: String? = null
    // Spec 85: where the current mpv start was asked to begin. initializeMpvPlayer clears the pending
    // resume once it hands the position to mpv, so the mpv->Exo failover carries it from here.
    internal var mpvStartPositionMs: Long = 0L
    internal var runtimeInternalPlayerEngineOverride: InternalPlayerEngine? = null
    internal var sessionPlayerEngine: InternalPlayerEngine? = null
    internal var currentInternalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var streamAutoPlayPreferBingeGroupForNextEpisodeSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = 98f
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var stillWatchingEnabledSetting: Boolean = false
    internal var stillWatchingEpisodeThresholdSetting: Int =
        PlayerSettings.DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD
    internal var mpvHardwareDecodeModeSetting: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    internal var mpvPreferredAudioLanguages: List<String> = emptyList()
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup

    /**
     * The stream playing, for the next episode's continuation (Spec 65 P7): set from the stream on
     * every switch (applyStreamMetadata); at launch rebuilt from the route, which carries its name,
     * addon, description, filename and size but not its title or AIOStreams parse (a step that
     * needs those then misses, and the configured mode decides).
     */
    internal var currentPlayingStream: Stream = Stream(
        name = navigationArgs.streamName,
        title = null,
        description = navigationArgs.streamDescription,
        url = navigationArgs.streamUrl,
        ytId = null,
        infoHash = navigationArgs.infoHash,
        fileIdx = navigationArgs.fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = navigationArgs.bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = navigationArgs.videoSize,
            filename = navigationArgs.filename
        ),
        addonName = navigationArgs.addonName.orEmpty(),
        addonLogo = null
    )
    internal var hasInitializedAudioAmplificationForSession: Boolean = false
    internal var hasInitializedCenterMixForSession: Boolean = false
    internal var rememberAudioDelayPerDeviceEnabled: Boolean = false
    internal var currentAudioOutputRoute: AudioOutputRoute? = null
    internal var audioOutputRouteCallback: AudioDeviceCallback? = null
    internal var audioRouteChangeJob: Job? = null
    // Audio device ids seen by the route callback (seeded at registration), and the
    // elapsedRealtime of the last real add/remove: the F3 "route is changing" signal.
    internal var knownAudioDeviceIds: Set<Int> = emptySet()
    @Volatile internal var lastAudioDeviceChangeAtMs: Long = Long.MIN_VALUE
    // T14 AVR-wake rebuild: a real device add awaiting the debounced check, the check job, and
    // the per-stream rebuild count (schedulePassthroughReturnCheck).
    internal var passthroughReturnCheckPending: Boolean = false
    internal var passthroughReturnCheckJob: Job? = null
    internal var passthroughReturnRebuildUrl: String? = null
    internal var passthroughReturnRebuilds: Int = 0

    internal var lastBufferLogTimeMs: Long = 0L
    internal var suppressBufferingUiForSeek: Boolean = false
    internal var seekBufferingUiJob: Job? = null
    internal var seekSuppressTimeoutJob: Job? = null
    internal var seekBufferingUiDeferred: Boolean = false
    internal val seekBufferingUiDelayMs = 1000L

    internal var bufferLogsEnabled: Boolean = false
    internal val gainAudioProcessor = GainAudioProcessor()
    internal var trackSelector: DefaultTrackSelector? = null
    internal var currentMediaSession: MediaSession? = null
    internal var ffmpegAudioRenderer: FfmpegAudioRenderer? = null
    internal var mpvView: NuvioMpvSurfaceView? = null
    internal var mpvInitializationInProgress: Boolean = false
    internal var mpvTrackRefreshJob: Job? = null
    /** In-flight MPV addon-subtitle download+attach; a newer pick cancels it. */
    internal var mpvAddonSubtitleSelectJob: Job? = null
    internal var mpvTrackRefreshInProgress: Boolean = false
    internal var pendingMpvHardRestartOnNextAttach: Boolean = false
    internal var delayMpvResumeSeekUntilVideoTrack: Boolean = false
    // AFR review R5 settle parity (mpv waits on lastAfrModeSwitchElapsedMs instead, Spec 85): set when the AFR preflight
    // actually changed the display mode, consumed by initializePlayer to hold
    // playback start briefly so the (tunneled) pipeline does not begin inside
    // the mode transition.
    internal var exoDelayStartAfterAfrSwitch: Boolean = false
    // nt6 AFR option 1: frame rate taken from ExoPlayer's reported track format
    // between prepare and first frame, replacing the MediaExtractor probe on the
    // ExoPlayer engine path. trackAfrAttemptedForCurrentStream gates one attempt
    // per stream; afrTrackSwitchInFlight holds playback start while a track-driven
    // display-mode switch settles; afrModeAppliedPreStart records that the
    // cache-hit preflight already applied a mode so the track path stands down.
    internal var trackAfrAttemptedForCurrentStream: Boolean = false
    @Volatile internal var afrTrackSwitchInFlight: Boolean = false
    // Spec 62 H1: the preflight path's settle hold is running. Every start site stands down
    // while it is set (a later READY, the first frame, the track-gate release, the first-frame
    // watchdog, the in-place auto-retry); its timer in startPlaybackThroughAfrGates clears it and starts playback.
    // Reset per stream with afrTrackSwitchInFlight; the timer checks afrTrackGeneration.
    @Volatile internal var afrSettleHoldInFlight: Boolean = false
    /** An AFR gate holds playback start: one home for the start sites' check (Spec 62 H1). */
    internal val afrStartGateHeld: Boolean
        get() = afrTrackSwitchInFlight || afrSettleHoldInFlight
    // Spec 50 I6: elapsedRealtime of the last real display-mode switch this app made (when applied, and again at the
    // track-AFR settle end), for AfrNoisyPolicy and the mpv start's settle hold (afrSettleHoldRemainingMs, Spec 85).
    @Volatile internal var lastAfrModeSwitchElapsedMs: Long = 0L
    // P-F3: per-stream generation stamp for the track-AFR coroutine. An old
    // stream's coroutine reaching its finally block must not collapse the new
    // stream's start-hold; incremented at every per-stream AFR reset.
    @Volatile internal var afrTrackGeneration: Int = 0
    internal var afrModeAppliedPreStart: Boolean = false
    // C-2: the raw fps of a provisional seed applied by the cache preflight from
    // prewarm head bytes, or 0f. The track-format path validates the real
    // reported rate against this and corrects on mismatch. Reset per stream.
    internal var afrSeededRateRaw: Float = 0f
    // nt6 fix B: hard cap on total automatic recoveries per stream URL, across
    // all fallback ladders, so a persistently failing pipeline (e.g. wedged
    // hardware decoder) surfaces an error in bounded time instead of silently
    // re-preparing for minutes.
    internal var autoRecoveryBudgetUrl: String = ""
    internal var autoRecoveryCountForCurrentStream: Int = 0
    internal var pauseOverlayJob: Job? = null
    internal val pauseOverlayDelayMs = 5000L
    internal val seekProgressSyncDebounceMs = 700L
    internal val audioDelayUs = AtomicLong(0L)
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    // Seek review F3: auto-expire an uncommitted preview. The ACTION_UP commit is
    // swallowed when a panel (Still Watching / end-of-episode) opens mid-gesture,
    // and nothing else cleared the pending value - the timeline stayed pinned to
    // the phantom position and the next gesture committed from it, potentially
    // across an episode boundary.
    internal var pendingPreviewSeekExpiryJob: kotlinx.coroutines.Job? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    internal var isReleasingPlayer: Boolean = false
    internal var cachedDecoderPriority: Int = 1
    internal var hasTriedAudioPcmFallback: Boolean = false
    internal var pendingAudioPcmFallbackRebuild: Boolean = false
    internal var parsingErrorProbeAttempted: Boolean = false
    internal var errorRetryCount: Int = 0
    // Spec 86 R6: live-edge re-anchors this stream (PlayerErrorRecoveryPolicy.MAX_LIVE_EDGE_FIXES).
    internal var liveEdgeFixCount: Int = 0
    internal var consecutiveAutoPlayCount: Int = 0
    internal var errorRetryJob: Job? = null
    // Spec 21 P7-R2: the pending scheduleDeferredPlayerReinitialize rebuild; releasePlayer cancels it.
    internal var deferredReinitJob: Job? = null
    internal var stableProgressResetJob: Job? = null
    @Volatile internal var currentPlayerSettings: PlayerSettings = PlayerSettings()

    // DV_HDR10 recovery (Spec 61 Q9): streams replayed as the HDR10 base layer. A DV profile 5
    // stream has no base layer, so it lands in dvConversionDisarmedStreamUrls instead: same
    // retry with conversion off and the DV ladder latched, but no base-layer exposure.
    internal val dv7ToHevcForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val dvConversionDisarmedStreamUrls: MutableSet<String> = mutableSetOf()
    // Streams where manual Convert-to-DV8.1 mode 2 failed to play, so the next
    // attempt is forced to libdovi mode 1 before falling back to HDR10 base layer.
    internal val dv7Mode1ForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1SoftwarePreferredStreamUrls: MutableSet<String> = mutableSetOf()
    // F5 fix: streams that hit a 4001 on a policy-denied audio format and must be
    // rebuilt with the FFmpeg audio renderer preferred (audio-local reorder).
    internal val preferFfmpegAudioStreamUrls: MutableSet<String> = mutableSetOf()
    // Policy the current player was built with; lets error recovery test denial
    // without re-deriving settings.
    internal var currentAudioPassthroughPolicy: com.nuvio.tv.core.player.AudioPassthroughPolicy? = null
    // Output route key the current player was built on (F3 outage guard: a refusal after the
    // route changed is not learned; recordAudioTrackRejectionIfBitstream).
    internal var audioRouteKeyAtPlayerBuild: String? = null
    // P5 same-config retries of a refused bitstream AudioTrack open (tryBitstreamAudioTrackSameConfigRetry).
    internal val bitstreamAudioRetryBudget = BitstreamAudioRetryBudget()
    internal val vc1TrackSelectionBypassStreamUrls: MutableSet<String> = mutableSetOf()
    // Spec 86 R1: URLs that had the first-frame watchdog's black-picture rebuild (next time: next source).
    internal val blackStartRebuiltStreamUrls: MutableSet<String> = mutableSetOf()
    // Spec 86 R2: URLs that had (or came from) the mid-play link refresh; session-long, so a second
    // refusal of the same link fails over instead of refreshing again.
    internal val linkRefreshedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val safeAudioForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val audioDisabledForcedStreamUrls: MutableSet<String> = mutableSetOf()
    // URLs proven dead this session (sniff failure on a non-media body, or HTTP
    // 404/410): auto-failover skips them and the source panel greys them out.
    internal val deadSourceStreamUrls: MutableSet<String> = mutableSetOf()
    // Spec 86 R5: the list rows the failover left for the playing item, by limitKey (cleared on an
    // episode switch). A torrent row has no http URL for deadSourceStreamUrls to hold, and the wrap
    // would walk back up to it.
    internal val deadSourceRowKeys: MutableSet<String> = mutableSetOf()
    internal var deadSourceFailoverCount: Int = 0
    // Spec 75 S5: the sources this failover chain left, oldest first; reset with the count.
    internal var failedOverSources: List<TriedSource> = emptyList()
    // The failure the store last recorded, so the exit never records a failover's failure twice.
    internal var lastRecordedFailure: PlaybackFailure? = null
    internal var hasRetriedAfterMimeOverrideClear: Boolean = false
    internal var isMapDv7ToHevcActiveForCurrentPlayback: Boolean = false
    internal var isManualDv81Mode2ActiveForCurrentPlayback: Boolean = false
    internal var isExperimentalDv7ToDv81ActiveForCurrentPlayback: Boolean = false
    /**
     * F-1: the track selector kept this playback's converted DV7 video out of tunnel mode
     * (set on the playback thread, read when the UI re-derives its tunnelling flag).
     */
    @Volatile internal var isTunnelingSuppressedForDvConversion: Boolean = false
    internal var isVc1SoftwareFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1TrackSelectionBypassActiveForCurrentPlayback: Boolean = false
    internal var isSafeAudioModeActiveForCurrentPlayback: Boolean = false
    internal var isAudioDisabledForCurrentPlayback: Boolean = false

    internal var playerInitializationStartedAtMs: Long = 0L

    internal var playbackPreparationJob: Job? = null
    // The initializePlayer coroutine; cancelled by cancelPlayerInit (L1-82).
    internal var playerInitJob: Job? = null
    // Spec 33 D6: the running network test (Back cancels it) and the last stream that held for
    // one, so a re-init of the same stream (failover, rebuild) never holds it twice.
    internal var networkTestJob: Job? = null
    internal var networkTestHeldUrl: String? = null
    /** Spec 57 D4: services held for the network test in this session (one hold each, whatever the outcome). */
    internal val networkTestHeldServices: MutableSet<String> = mutableSetOf()
    // nt7 (task 2): saved-progress read launched at
    // preparePlaybackBeforeStart, joined in initializePlayer before
    // either engine reads the resume position.
    internal var savedProgressDeferred: kotlinx.coroutines.Deferred<Unit>? = null

    internal var requestedUseLibassByUser: Boolean = false
    internal var libassPipelineOverrideForCurrentStream: Boolean? = null
    internal var activePlayerUsesLibass: Boolean = false
    internal var libassPipelineSwitchInFlight: Boolean = false
    internal var hasDetectedAssSsaTrackForCurrentStream: Boolean = false
    internal var libassPipelineDecisionStreamUrl: String? = null
    internal var torrentStreamJob: Job? = null
    internal var torrentStateObserverJob: Job? = null
    internal var isTorrentStream: Boolean = navigationArgs.infoHash != null && !initialStreamUrl.startsWith("http")
    /**
     * TorrentService.beginSession() value of this player's latest torrent; null = none.
     * Reassigned per torrent start and, unlike isTorrentStream, not cleared by
     * stopTorrentStream, so onCleared can still hand it to shutdownTorrServerAfterSession.
     */
    internal var torrentSession: Long? = null
    /**
     * Last-seen TorrentSettings.p2pEnabled (false until the first emission). Keeps torrent rows
     * out of automatic picks and rejects manual in-player torrent switches before teardown (both
     * via isP2pPermitted); TorrentService.startStream enforces the consent itself.
     */
    internal var p2pEnabled: Boolean = false
    internal var currentInfoHash: String? = navigationArgs.infoHash
    internal var currentFileIdx: Int? = navigationArgs.fileIdx
    internal var currentTorrentSources: List<String>? =
        navigationArgs.sourcesJson?.let { raw ->
            runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { s -> s.isNotEmpty() }
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    internal var currentStreamHasVideoTrack: Boolean = false
    internal var currentVideoTrackIsLikelyVc1: Boolean = false
    internal var currentVideoTrackMimeType: String? = null
    internal var currentVideoTrackCodecs: String? = null
    // Audio review F9: negotiated audio output path, from onAudioTrackInitialized.
    // e.g. "TrueHD -> Passthrough (TrueHD, 48 kHz, 8ch)" / "DTS-HD -> PCM decode".
    internal var currentAudioPathDescription: String? = null
    // Audio review F8: true while the negotiated AudioTrack encoding is
    // non-PCM (bitstream bypass). Gain and skip-silence are PCM processors, so
    // they are silent no-ops in this state; the UI gates on this instead of
    // offering a dead slider. Set from onAudioTrackInitialized (Exo only —
    // MPV always decodes), reset per playback and on release.
    internal var isAudioOutputBypassing: Boolean = false
    internal var currentVideoTrackWidth: Int = 0
    internal var currentVideoTrackHeight: Int = 0
    internal var currentVideoTrackBitrate: Int = -1
    internal var currentVideoTrackColorTransfer: Int? = null
    // The OSD's HDR badge: the first-frame diagnostics' output-truth label (DV conversion and
    // the DV7 mode are only settled then); null until this stream's first frame.
    internal var osdVideoHdrType: String? = null
    // Stream URL the degraded toast was raised for: once per playback, and a rebuild retry of the
    // same URL (where most fallbacks start) does not repeat it.
    internal var degradedNoticeStreamUrl: String? = null
    internal var currentVideoTrackSelected: Boolean = false
    internal var currentVideoTrackBestSupport: Int = C.FORMAT_UNSUPPORTED_TYPE
    internal var lastLoggedVideoTrackSignature: String? = null

    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    /**
     * Spec 51: the stream list limit as last read; null until the first read, when nothing is
     * hidden. Written by observeStreamLimit's collector (Main) and by awaitLimitInputs' first read
     * only while still null, which the binge lookahead's rank may run on the prefetch IO scope
     * (hence volatile); the collector's next emission corrects an older value stored there.
     */
    @Volatile internal var streamLimitInputs: com.nuvio.tv.core.stream.StreamLimitInputs? = null
    /** The playing stream's limit key while a derive found it over the limit (one LIMIT log per stream, R4-13). */
    internal var playingOverLimitKey: String? = null
    internal val streamCacheKey: String?
        get() {
            val type = contentType?.lowercase()
            val vid = currentVideoId
            return if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
        }

    init {
        // NOTE: Saved watch progress is loaded inside preparePlaybackBeforeStart()
        // via loadSavedProgressSuspend() — NOT here.  Loading it in the init block
        // was a fire-and-forget coroutine that raced against initializePlayer(),
        // causing the resume seek to be silently lost when ExoPlayer's STATE_READY
        // fired before the DB read completed.
        observeSubtitleSettings()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
        observeTorrentSettings()
        observeStreamBadgeSettings()
        observeDeviceLocalAspectMode()
        observeStreamLimit()
        observePreferredSource()
    }

    private fun observeTorrentSettings() {
        scope.launch {
            torrentSettings.settings.collect { settings ->
                p2pEnabled = settings.p2pEnabled
                _uiState.update { it.copy(hideTorrentStats = settings.hideTorrentStats) }
            }
        }
    }

    private fun observeStreamBadgeSettings() {
        scope.launch {
            streamBadgeSettingsDataStore.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showFileSizeBadges = settings.showFileSizeBadges,
                        useAddonFormatting = settings.useAddonFormatting
                    )
                }
            }
        }
    }

    fun onCleared() {
        if (PlaybackConnectionEvents.stepClock === stepClock) PlaybackConnectionEvents.stepClock = null
        releasePlayer(flushPlaybackState = true)
        stopTorrentStream()
        shutdownTorrServerAfterSession(torrentSession, torrentService)
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
        sourceStreamsScope?.cancel()
        sourceStreamsScope = null
        episodeStreamsScope?.cancel()
        episodeStreamsScope = null
    }

}
