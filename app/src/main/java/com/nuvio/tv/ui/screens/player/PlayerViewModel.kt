package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AudioOutputMode
import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DoviBridge
import android.content.Context
import android.os.Debug
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.formatTorrentSpeed
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.nuvio.tv.core.player.NuvioMpvSurfaceView
import com.nuvio.tv.core.player.ParallelRangeDataSource
import com.nuvio.tv.core.player.PlaybackByteCounter
import com.nuvio.tv.core.player.PlaybackConnectionEvents
import com.nuvio.tv.core.player.PlaybackCpuClockSampler
import com.nuvio.tv.core.player.PlaybackThermalSampler
import com.nuvio.tv.core.player.PlaybackTimelineState
import com.nuvio.tv.core.player.PlaybackVideoColorTransfer
import com.nuvio.tv.core.player.PlayerEvent
import com.nuvio.tv.core.player.PlayerRuntimeController
import com.nuvio.tv.core.player.PlayerUiState
import com.nuvio.tv.core.player.FrameRateSource
import com.nuvio.tv.core.player.attachHostActivity
import com.nuvio.tv.core.player.attachMpvView
import com.nuvio.tv.core.player.bindExoSubtitleView
import com.nuvio.tv.core.player.consumeNetworkTestNotice
import com.nuvio.tv.core.player.failureBoxEngineTarget
import com.nuvio.tv.core.player.loadSourceListForFailureBox
import com.nuvio.tv.core.player.nextSourceAfterPlaying
import com.nuvio.tv.core.player.playNextSourceFromFailureBox
import com.nuvio.tv.core.player.retryFromFailureBox
import com.nuvio.tv.core.player.switchEngineFromFailureBox
import com.nuvio.tv.core.player.frameRateLabel
import com.nuvio.tv.core.player.hideControls
import com.nuvio.tv.core.player.isRefreshMatch
import com.nuvio.tv.core.player.isUsingMpvEngine
import com.nuvio.tv.core.player.onEvent
import com.nuvio.tv.core.player.onUserInteraction
import com.nuvio.tv.core.player.pauseForLifecycle
import com.nuvio.tv.core.player.resumeForLifecycle
import com.nuvio.tv.core.player.scheduleHideControls
import com.nuvio.tv.core.player.startInitialPlaybackIfNeeded

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: com.nuvio.tv.data.local.BingeGroupCacheDataStore,
    private val savedStreamFiltersDataStore: com.nuvio.tv.data.local.SavedStreamFiltersDataStore,
    private val preferredSourceDataStore: com.nuvio.tv.data.local.PreferredSourceDataStore,
    private val failedReleases: com.nuvio.tv.core.stream.FailedReleases,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    private val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    private val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    private val screensaverController: com.nuvio.tv.core.player.ScreensaverController,
    private val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    private val watchCountDataStore: com.nuvio.tv.data.local.WatchCountDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        // Release trailer player codec resources so the full-screen player can
        // claim hardware decoders without contention (prevents black screen).
        trailerPlayerPool.yield()
    }

    // Open while this player exists, paused or not; Continue Watching defers its episode-list
    // refreshes until it closes. Closed on exit (stopAndRelease) or onCleared, whichever is first.
    private val playerSession = com.nuvio.tv.core.player.PlayerSessionTracker.open()

    internal val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        prefetchSelectionSupplier = prefetchSelectionSupplier,
        addonRepository = addonRepository,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        preferredSourceDataStore = preferredSourceDataStore,
        failedReleases = failedReleases,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        streamBadgePresentation = streamBadgePresentation,
        tvRecommendationManager = tvRecommendationManager,
        watchCountDataStore = watchCountDataStore,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    /** Whether a preferred source can be kept for what is playing (Spec 65 P6): its content id is known. */
    val canPreferSource: Boolean = controller.contentId != null

    /** The profile's saved filters and Sources-row pins, for both Sources panels (Spec 65 P2). */
    val savedStreamFilters = savedStreamFiltersDataStore.state

    fun saveStreamFilter(filter: com.nuvio.tv.core.stream.FacetFilter, name: String) {
        viewModelScope.launch { savedStreamFiltersDataStore.save(filter, name) }
    }

    fun toggleStreamPin(pin: com.nuvio.tv.core.stream.StreamPin) {
        viewModelScope.launch { savedStreamFiltersDataStore.togglePin(pin) }
    }

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    init {
        // OLED screensaver: mirror playback-active (playing or buffering) so the idle
        // dimmer never engages during playback, restarts its clock on pause, and
        // auto-wakes on resume (including MediaSession resumes).
        viewModelScope.launch {
            controller.uiState
                .map { it.isPlaying || it.isBuffering }
                .distinctUntilChanged()
                .collect { active -> screensaverController.setPlaybackActive(active) }
        }
    }

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    /** Release filename for the current stream, surfaced for the loading overlay. */
    val currentFilename: String?
        get() = controller.currentFilename

    private val playbackStatsCpuSampler = ProcessCpuSampler()
    private val playbackStatsThermalSampler by lazy { PlaybackThermalSampler(context) }
    private val playbackStatsCpuClockSampler = PlaybackCpuClockSampler()

    // Recency state for event-counter dots (dropped frames, underruns, stalls):
    // a dot goes red only when its counter increased within the recency window.
    private var statsLastStreamUrl: String? = null
    private var statsPrevDropped: Int = -1
    private var statsDroppedIncreasedAtMs: Long = 0L
    private var statsPrevUnderruns: Int = -1
    private var statsUnderrunsIncreasedAtMs: Long = 0L
    private var statsPrevStalls: Int = -1
    private var statsStallsIncreasedAtMs: Long = 0L

    // Measured mux accumulator: bytes and media time are accumulated per tick rather
    // than divided cumulatively, so a seek (an implausible jump in buffered position)
    // starts a fresh epoch instead of poisoning the ratio. Both are reset when the
    // stream changes.
    private var statsPrevTransferredBytes: Long = -1L
    private var statsPrevBufferedMs: Long = -1L
    private var statsMuxBytes: Long = 0L
    private var statsMuxMediaMs: Long = 0L

    // Windowed mux rate ("now"): a short ring of recent (statsMuxBytes, statsMuxMediaMs)
    // running-total snapshots, one per HUD tick (~1 s). The windowed rate is the byte
    // delta over the media delta across the oldest-to-newest span in the ring. This is
    // download-derived mux, directly comparable to the Speed row: when a dense scene's
    // demand outruns the link the two converge, and it can never exceed delivered rate
    // because it is measured from bytes arriving. It is NOT decoder-side video bitrate.
    private val statsMuxWindowBytes = ArrayDeque<Long>()
    private val statsMuxWindowMediaMs = ArrayDeque<Long>()

    // Measured link throughput: sampled only across ticks where the loader was
    // actually pulling. ExoPlayer reports isLoading = false once the buffer is full,
    // and bytes/second measured over an idle window would read as a starved link.
    private var statsPrevNetworkBytes: Long = -1L
    private var statsPrevNetworkSampleAtMs: Long = 0L
    private var statsPrevIsLoading: Boolean = false
    private var statsMeasuredBps: Long? = null
    // nt19: recency state for the Rebuffers row (real STATE_BUFFERING count from the
    // controller), mirroring the dropped/underrun/stall recency fields above.
    private var statsPrevRebuffers: Int = -1
    private var statsRebuffersIncreasedAtMs: Long = 0L

    /**
     * TCP-connect round trip to the current stream URL's host (the app cannot
     * send true ICMP pings). One handshake approximates one network RTT; it
     * prefers the resolved serving host (post-redirect, as the Server row), and is a
     * fresh connection rather than the reused streaming one. Called from the
     * HUD effect's own 5 s loop, independent of the 1 Hz sample.
     */
    suspend fun samplePing(): Long? = withContext(Dispatchers.IO) {
        val url = runCatching { controller.getCurrentStreamUrl() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext null
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext null
        val host = PlaybackConnectionEvents.resolvedHost() ?: uri.host ?: return@withContext null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        runCatching {
            Socket().use { socket ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(host, port), 2_000)
                android.os.SystemClock.elapsedRealtime() - startedAt
            }
        }.getOrNull()
    }

    /**
     * One ~1 Hz sample for the live playback stats overlay (task 2.2 / nt26).
     * Called from a main-thread LaunchedEffect in PlayerScreen while the overlay
     * is visible; player state must be read on the application thread.
     * displayRefreshRateHz comes from the hosting view's display; lastPingMs is
     * the most recent samplePing() result.
     */
    fun samplePlaybackStats(
        displayRefreshRateHz: Float?,
        displayRateOptions: Int?,
        displayModeWidth: Int?,
        displayModeHeight: Int?,
        lastPingMs: Long?
    ): PlaybackStatsSample {
        val engine = controller.currentInternalPlayerEngine
        val mpvActive = controller.isUsingMpvEngine() ||
            (engine == InternalPlayerEngine.AUTO && controller.mpvView != null)
        if (mpvActive) {
            return PlaybackStatsSample(
                isExoPlayer = false,
                sections = buildStatsSections(listOf(StatsRow("Engine", "libmpv")))
            )
        }
        val t = PlaybackStatsThresholds
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val rows = mutableListOf<StatsRow>(StatsRow("Engine", "ExoPlayer"))
        val player = controller.exoPlayer
        val hud = controller.playbackAnalyticsDiagnostics.hudSample()
        val streamUrl = runCatching { controller.getCurrentStreamUrl() }.getOrNull()

        // Reset recency state when the stream changes.
        if (streamUrl != statsLastStreamUrl) {
            statsLastStreamUrl = streamUrl
            statsPrevDropped = -1
            statsDroppedIncreasedAtMs = 0L
            statsPrevUnderruns = -1
            statsUnderrunsIncreasedAtMs = 0L
            statsPrevStalls = -1
            statsStallsIncreasedAtMs = 0L
            statsPrevTransferredBytes = -1L
            statsPrevBufferedMs = -1L
            statsMuxBytes = 0L
            statsMuxMediaMs = 0L
            statsMuxWindowBytes.clear()
            statsMuxWindowMediaMs.clear()
            statsPrevNetworkBytes = -1L
            statsPrevNetworkSampleAtMs = 0L
            statsPrevIsLoading = false
            statsMeasuredBps = null
            statsPrevRebuffers = -1
            statsRebuffersIncreasedAtMs = 0L
        }
        if (statsPrevDropped in 0 until hud.droppedFrames) statsDroppedIncreasedAtMs = nowMs
        if (statsPrevUnderruns in 0 until hud.audioUnderrunCount) statsUnderrunsIncreasedAtMs = nowMs
        if (statsPrevStalls in 0 until hud.positionStallCount) statsStallsIncreasedAtMs = nowMs
        statsPrevDropped = hud.droppedFrames
        statsPrevUnderruns = hud.audioUnderrunCount
        statsPrevStalls = hud.positionStallCount
        val rebuffers = controller.rebufferCount
        if (statsPrevRebuffers in 0 until rebuffers) statsRebuffersIncreasedAtMs = nowMs
        statsPrevRebuffers = rebuffers

        fun recencyDot(total: Int, increasedAtMs: Long): StatsDot = when {
            total <= 0 -> StatsDot.GOOD
            nowMs - increasedAtMs <= t.RECENT_EVENT_WINDOW_MS -> StatsDot.BAD
            else -> StatsDot.WARN
        }

        // Byte accounting. The bandwidth meter cannot see a plain progressive
        // stream's bytes — it only publishes a sample when a transfer ends, and such a
        // load holds one transfer open for the whole file — so the HUD counts them
        // itself (PlaybackByteCounter) and derives both the mux rate and the link
        // throughput here.
        player?.let { p ->
            val bytesNow = hud.transferredBytesTotal
            val bufferedNow = p.bufferedPosition
            if (statsPrevTransferredBytes in 0L..bytesNow && statsPrevBufferedMs >= 0L) {
                val mediaDelta = bufferedNow - statsPrevBufferedMs
                if (mediaDelta in 0L..t.MUX_MAX_MEDIA_DELTA_MS) {
                    statsMuxBytes += bytesNow - statsPrevTransferredBytes
                    statsMuxMediaMs += mediaDelta
                } else {
                    // Seek: the bytes already counted no longer correspond to the
                    // media span ahead of us. Start a new epoch.
                    statsMuxBytes = 0L
                    statsMuxMediaMs = 0L
                    statsMuxWindowBytes.clear()
                    statsMuxWindowMediaMs.clear()
                }
            }
            statsPrevTransferredBytes = bytesNow
            statsPrevBufferedMs = bufferedNow

            // Record this tick's running totals and keep only the last WINDOW ticks.
            statsMuxWindowBytes.addLast(statsMuxBytes)
            statsMuxWindowMediaMs.addLast(statsMuxMediaMs)
            while (statsMuxWindowBytes.size > t.MUX_WINDOW_TICKS) {
                statsMuxWindowBytes.removeFirst()
                statsMuxWindowMediaMs.removeFirst()
            }

            val networkNow = hud.transferredNetworkBytes
            val loading = p.isLoading
            if (loading && statsPrevIsLoading &&
                statsPrevNetworkBytes in 0L..networkNow &&
                statsPrevNetworkSampleAtMs > 0L
            ) {
                val windowMs = nowMs - statsPrevNetworkSampleAtMs
                val windowBytes = networkNow - statsPrevNetworkBytes
                if (windowBytes >= 0L &&
                    windowMs in t.THROUGHPUT_MIN_WINDOW_MS..t.THROUGHPUT_MAX_WINDOW_MS
                ) {
                    val sampleBps = windowBytes * 8_000L / windowMs
                    val previous = statsMeasuredBps
                    statsMeasuredBps = if (previous == null) {
                        sampleBps
                    } else {
                        (previous * (1.0 - t.THROUGHPUT_SMOOTHING) +
                            sampleBps * t.THROUGHPUT_SMOOTHING).toLong()
                    }
                }
            }
            statsPrevNetworkBytes = networkNow
            statsPrevNetworkSampleAtMs = nowMs
            statsPrevIsLoading = loading
        }

        // Server + file (informational). The file name comes from the stream
        // URL's last path segment (the addon's stream label is a marketing
        // string, not the file); fall back to the label when the URL doesn't
        // end in something filename-shaped.
        val host = streamUrl?.let { runCatching { URI(it).host }.getOrNull() }
        // Add-on + provider (nt41). Add-on is retained UI state; provider is parsed
        // from the stream's marketing label (debrid store / library / host).
        val statsUi = controller.uiState.value
        statsUi.currentStreamAddonName?.takeIf { it.isNotBlank() }
            ?.let { rows += StatsRow("Add-on", it) }
        resolveStreamProvider(
            streamName = statsUi.currentStreamName,
            streamDescription = controller.currentStreamDescription,
            addonName = statsUi.currentStreamAddonName,
            host = host
        )?.let { rows += StatsRow("Provider", it) }
        // N6 V2: Server shows the real serving host -- the post-redirect CDN
        // once a redirect resolves, falling back to the URL host (the
        // redirector during TTFF, or a direct source) until then / when there
        // is no redirect.
        // Spec 49 D27 F4: the real host for every stream (no per-addon label).
        (PlaybackConnectionEvents.resolvedHost() ?: host)?.let { rows += StatsRow("Server", it) }
        // URI.path is already percent-decoded; decoding again turned '+' into a space.
        val urlFileName = streamUrl
            ?.let { runCatching { URI(it).path }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it.contains('.') }
        val scraperFileName = controller.currentFilename?.takeIf { it.isNotBlank() }
        (scraperFileName ?: urlFileName ?: controller.uiState.value.currentStreamName)
            ?.takeIf { it.isNotBlank() }
            ?.let { rows += StatsRow("File", it.replace("\n", " \u00b7 "), marquee = true) }

        // File size, whenever the source declared a content length at open(). It also
        // yields the exact average mux rate — size / duration — which needs no
        // measurement at all: it is immune to seeks, to prefetch, and to any gap in byte
        // accounting, and it is available as soon as the duration is known. None for an
        // adaptive stream (HLS/DASH): it has no single file. Read only once the duration is
        // known: before that the timeline is a placeholder with no manifest, so an HLS
        // playlist's length would show as the size for a tick.
        val durationMs = player?.duration?.takeIf { it > 0L }
        val fileSizeBytes = durationMs?.let {
            PlaybackByteCounter.mediaFileSizeFor(
                url = streamUrl,
                isAdaptiveStream = player.currentManifest != null
            )
        }
        val exactMuxBps = if (fileSizeBytes != null) {
            fileSizeBytes * 8_000L / durationMs
        } else {
            null
        }
        fileSizeBytes?.let { rows += StatsRow("Size", formatPlaybackStatsBytes(it)) }

        // Video line, HDR, bitrates.
        var effectiveMuxBps: Long? = null
        player?.videoFormat?.let { format ->
            val size = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null
            val fps = format.frameRate.takeIf { it > 0f }?.let { frameRateLabel(it) + " fps" }
            val codec = format.sampleMimeType?.substringAfter('/')
            val bitDepth = format.colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { "$it-bit" }
            listOfNotNull(size, fps, bitDepth, codec).joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { rows += StatsRow("Video", it) }
            videoCodecProfileLabel(format.codecs ?: controller.currentVideoTrackCodecs)
                ?.let { rows += StatsRow("Codec", it) }
            // The MKV Colour element is optional, and neither MatroskaExtractor nor the
            // vendored dvmkv copy falls back to the HEVC SPS when it is missing — so a
            // remux muxed without it reports colorInfo == null and this row used to call a
            // genuine HDR10 stream "SDR" while the decoder and display correctly went HDR
            // off the SPS. PlaybackVideoColorTransfer reads the SPS when the container is
            // silent.
            val colorTransfer = PlaybackVideoColorTransfer.of(format)
            val hdr = when {
                format.sampleMimeType?.contains("dolby-vision") == true -> "Dolby Vision"
                colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
                colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
            rows += StatsRow("HDR", hdr)
            // P12: the container's colour description (transfer from the SPS when it is silent)
            // and its HDR10 static metadata.
            colourLabel(format.colorInfo?.colorSpace, colorTransfer, format.colorInfo?.colorRange)
                ?.let { rows += StatsRow("Colour", it) }
            masteringLabel(format.colorInfo?.hdrStaticInfo)?.let { rows += StatsRow("Mastering", it) }

            // Video bitrate, in descending order of authority: a rate the container
            // declares; the file's exact average mux rate (size / duration); the rate
            // measured from bytes accumulated against media time since playback started
            // or the last seek. "avg mux" is the whole container, not video-only.
            val declared = (if (format.bitrate > 0) format.bitrate.toLong() else null)
                ?: (if (format.averageBitrate > 0) format.averageBitrate.toLong() else null)
            val floorBps = if (format.height >= t.MUX_FLOOR_HD_MIN_HEIGHT) {
                t.MUX_IMPLAUSIBLE_FLOOR_BPS
            } else {
                t.MUX_IMPLAUSIBLE_FLOOR_SD_BPS
            }
            val measuredMuxBps = if (statsMuxMediaMs > t.MUX_MIN_MEDIA_MS) {
                statsMuxBytes * 8_000L / statsMuxMediaMs
            } else {
                null
            }
            // Windowed ("now") mux rate over the ring span, when the ring holds a full
            // window and the media delta across it is large enough to be meaningful.
            val windowMuxBps = if (statsMuxWindowBytes.size >= t.MUX_WINDOW_TICKS) {
                val byteSpan = statsMuxWindowBytes.last() - statsMuxWindowBytes.first()
                val mediaSpan = statsMuxWindowMediaMs.last() - statsMuxWindowMediaMs.first()
                if (mediaSpan >= t.MUX_WINDOW_MIN_MEDIA_MS && byteSpan >= 0L) {
                    byteSpan * 8_000L / mediaSpan
                } else {
                    null
                }
            } else {
                null
            }
            val nowSuffix = if (windowMuxBps != null) {
                String.format(" · now %.1f Mbit/s", windowMuxBps / 1_000_000.0)
            } else {
                ""
            }
            when {
                declared != null ->
                    rows += StatsRow("V bitrate", String.format("%.1f Mbit/s", declared / 1_000_000.0))

                exactMuxBps != null -> {
                    effectiveMuxBps = exactMuxBps
                    rows += StatsRow(
                        "V bitrate",
                        String.format("avg mux %.1f Mbit/s", exactMuxBps / 1_000_000.0) + nowSuffix
                    )
                }

                measuredMuxBps == null -> Unit

                measuredMuxBps >= floorBps -> {
                    effectiveMuxBps = measuredMuxBps
                    rows += StatsRow(
                        "V bitrate",
                        String.format("avg mux %.1f Mbit/s · meas", measuredMuxBps / 1_000_000.0) + nowSuffix
                    )
                }

                else ->
                    // Below the floor for this resolution the counter is not seeing the
                    // source's bytes. Say that, rather than print a number that reads as a
                    // broken stream (and leave effectiveMuxBps null so the Speed dot does
                    // not judge against it).
                    rows += StatsRow("V bitrate", "avg mux — (no byte data)", StatsDot.WARN)
            }
        }

        // DV conversion state (fork-specific).
        val dvDiag = controller.lastPlaybackDiagnostics
        dvDiag.dvSourceProfile?.takeIf { it.isNotBlank() }?.let { sourceProfile ->
            val el = dvDiag.dvElType?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val (text, dot) = dvStatsRowValue(sourceProfile, el, dvDiag.dv7ModeEffective) {
                // The first-frame diagnostics snapshot can freeze zeros when it
                // races the converter's first RPU; the live per-playback counters
                // (reset at player init) are authoritative for "actively working".
                DoviBridge.getConversionSuccessCount() > 0L ||
                    DolbyVisionConversionStats.getCodecStringRewriteCount() > 0L ||
                    dvDiag.dv7DoviSignalRewrites > 0 || dvDiag.dv7DoviSuccess > 0
            }
            rows += StatsRow("DV", text, dot)
        }

        // nt18: DV source mastering metadata (static per stream), read from the
        // RPU. Gated on its own value rather than the DV-conversion line above,
        // so it also appears on strip/HDR10 paths where no source-profile line is
        // emitted. Informational only — no health dot.
        dvDiag.dvHdrMastering?.takeIf { it.isNotBlank() }?.let { mastering ->
            rows += StatsRow("DV HDR", mastering, StatsDot.NONE)
        }

        hud.videoDecoderName?.let { rows += StatsRow("Decoder", decoderLabel(it)) }

        // Under tunnelling the decoder's output goes straight to the display over a
        // hardware sideband path and the hardware presents the frames. ExoPlayer never
        // releases one, so it can see a source-level drop (a skip to a keyframe when badly
        // behind) but never a frame the display failed to show — and media3 says as much:
        // "In tunneling mode the device may do frame rate conversion, so in general we
        // can't keep track of the number of buffers in the codec"
        // (MediaCodecVideoRenderer.onQueueInputBuffer). The count is therefore partial,
        // not absent: keep the number, but say what it can and cannot see. A bare
        // "0 frames" with a green dot reads as "perfect" when it means "the drops that
        // matter here are invisible to me".
        val tunnelled = controller.uiState.value.tunnelingEnabled
        if (tunnelled) {
            rows += StatsRow(
                "Dropped",
                "${hud.droppedFrames} · source only (tunnelled)",
                recencyDot(hud.droppedFrames, statsDroppedIncreasedAtMs)
            )
        } else {
            rows += StatsRow(
                "Dropped",
                "${hud.droppedFrames} frames",
                recencyDot(hud.droppedFrames, statsDroppedIncreasedAtMs)
            )

            // Frame-processing offset: positive = frames ready early (headroom).
            hud.frameProcessingOffsetAvgUs?.let { avgUs ->
                rows += StatsRow(
                    "Frame lead",
                    String.format("%+.1f ms", avgUs / 1_000.0),
                    if (avgUs >= 0L) StatsDot.GOOD else StatsDot.BAD
                )
            }
        }

        // Refresh rate vs content fps: an AFR-worked check — but only where the app is in
        // a position to know. Two cases where it is not, and where a red dot would be an
        // alarm about something the app can neither see nor fix:
        //
        //  - Tunnelled: the hardware presents the frames. What Android reports as the
        //    display rate says nothing about what the panel is doing with the content.
        //  - One mode at this resolution: preferredDisplayModeId has nothing to switch to,
        //    so no app-side mechanism can change the rate. On a TV the logical display is
        //    often a fixed 60 Hz UI plane while video runs on a separate plane at its own
        //    rate — which Android never reports. The number is honest; the judgement is not
        //    ours to make.
        displayRefreshRateHz?.takeIf { it > 0f }?.let { hz ->
            val singleMode = displayRateOptions != null && displayRateOptions <= 1
            val contentFps = player?.videoFormat?.frameRate?.takeIf { it > 0f }
            val dot = when {
                tunnelled || singleMode -> StatsDot.NONE
                contentFps != null -> if (isRefreshMatch(hz, contentFps)) StatsDot.GOOD else StatsDot.BAD
                else -> StatsDot.NONE
            }
            val notes = buildList {
                if (tunnelled) add("tunnelled")
                if (singleMode) add("1 mode")
            }
            val suffix = if (notes.isEmpty()) "" else " · " + notes.joinToString(", ")
            // T9 (ysosrs 7e6483513): output resolution first, mode-descriptor style
            // ("3840×2160 · 23.98 Hz"). This is the negotiated output MODE, distinct from
            // the Video row's SOURCE resolution above it — a 4K file on a 1080p output
            // reads Video 3840×2160 / Output 1920×1080, making the downscale
            // self-evident. Deliberately no dot change: an output below source is not
            // something the app can fix.
            val modeSize = if ((displayModeWidth ?: 0) > 0 && (displayModeHeight ?: 0) > 0) {
                "${displayModeWidth}×${displayModeHeight} · "
            } else ""
            rows += StatsRow("Output", modeSize + String.format("%.2f Hz", hz) + suffix, dot)
        }
        rows += afrStatsRow(
            mode = statsUi.frameRateMatchingMode,
            detecting = statsUi.afrProbeRunning || controller.afrTrackSwitchInFlight,
            detectedFps = statsUi.detectedFrameRate,
            source = statsUi.detectedFrameRateSource,
            appliedRefreshHz = statsUi.displayModeInfo?.refreshRate
        )

        // Buffer health with startup grace and end-of-file guard.
        player?.let {
            val aheadMs = (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L)
            val aheadS = aheadMs / 1000.0
            val duration = it.duration
            val nearEof = duration > 0L && duration - it.bufferedPosition <= t.BUFFER_EOF_GUARD_MS
            val inGrace = it.currentPosition < t.BUFFER_STARTUP_GRACE_MS
            val dot = when {
                nearEof -> StatsDot.GOOD
                inGrace -> StatsDot.NONE
                aheadS >= t.BUFFER_GOOD_SECONDS -> StatsDot.GOOD
                aheadS >= t.BUFFER_WARN_SECONDS -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow("Buffer", String.format("%.1f s ahead", aheadS), dot)
        }

        // Throughput + speed ratio against the effective stream bitrate. media3's
        // bandwidth estimate is only representative while transfers actually end —
        // true on the chunked paths (parallel connections, MP4 session, HLS/DASH), and
        // false on a plain progressive load, where the estimate stops updating after
        // the extractor's opening reads. Trust the meter only while it has seen a fair
        // share of the bytes the HUD counted; otherwise show the HUD's own measurement.
        val meterRepresentative = hud.transferredNetworkBytes <= 0L ||
            hud.bandwidthBytesTotal * t.METER_COVERAGE_DIVISOR >= hud.transferredNetworkBytes
        val meterBps = hud.bandwidthEstimateBps?.takeIf { meterRepresentative }
        (statsMeasuredBps ?: meterBps)?.let { bps ->
            val reference = effectiveMuxBps
                ?: player?.videoFormat?.bitrate?.takeIf { it > 0 }?.toLong()
            val dot = if (reference != null && reference > 0L) {
                val ratio = bps.toDouble() / reference
                when {
                    ratio >= t.SPEED_RATIO_GOOD -> StatsDot.GOOD
                    ratio >= t.SPEED_RATIO_WARN -> StatsDot.WARN
                    else -> StatsDot.BAD
                }
            } else {
                StatsDot.NONE
            }
            val rate = String.format("%.1f Mbit/s", bps / 1_000_000.0)
            rows += StatsRow("Speed", if (statsMeasuredBps != null) "meas $rate" else "est $rate", dot)
        }

        // Ping (TCP connect RTT, sampled every ~5 s by the HUD effect).
        lastPingMs?.let { ping ->
            val dot = when {
                ping <= t.PING_GOOD_MS -> StatsDot.GOOD
                ping <= t.PING_WARN_MS -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow("Ping", "$ping ms", dot)
        }

        // Last media request duration (dotless in v1: legitimate values vary
        // too much with chunk size and path for honest absolute thresholds).
        // nt6: completed MEDIA loads only. On progressive streams the single
        // long read only ever ends by cancellation (seek/stop), so its running
        // duration is playback bookkeeping, not request latency — the row now
        // goes honestly absent there, as nt27 intended.
        hud.lastCompletedMediaLoadDurationMs?.takeIf { it > 0L }?.let {
            rows += StatsRow("Request", "$it ms")
        }
        // Rate-limit clamp (nt6): absent until the parallel engine's 429
        // clamp has tripped this session; WARN while latched (cooldown
        // counting down), informational once recovered. Reads the
        // ParallelRangeDataSource companion mirror (same package).
        if (ParallelRangeDataSource.hudClampTrips > 0) {
            if (ParallelRangeDataSource.hudClampLatched) {
                val nextS = ParallelRangeDataSource
                    .hudClampCooldownRemainingMs(android.os.SystemClock.uptimeMillis()) / 1000L
                rows += StatsRow(
                    "Rate limit",
                    "429 \u00b7 depth ${ParallelRangeDataSource.hudDepthCap}/${ParallelRangeDataSource.hudDepthConfigured}" +
                        " \u00b7 +1 in ${nextS}s",
                    StatsDot.WARN
                )
            } else {
                rows += StatsRow("Rate limit", "recovered \u00b7 ${ParallelRangeDataSource.hudClampTrips} \u00d7 429")
            }
        }
        // Hedge (nt19): 3a body-stall restart activity this session. Hidden until the
        // first fresh-connection restart fires. "N restarts" while escaping; appends
        // "(cap hit)" if any chunk exhausted the restart budget (uniformly-slow origin).
        if (ParallelRangeDataSource.hudHedgeRestarts > 0) {
            val hedgeExhausted = ParallelRangeDataSource.hudHedgeExhausted
            val hedgeValue = "${ParallelRangeDataSource.hudHedgeRestarts} restarts" +
                if (hedgeExhausted) " (cap hit)" else ""
            rows += StatsRow("Hedge", hedgeValue, if (hedgeExhausted) StatsDot.BAD else StatsDot.WARN)
        }
        if (hud.loadErrorCount > 0) {
            rows += StatsRow("Load errors", hud.loadErrorCount.toString(), StatsDot.WARN)
        }
        maxOf(hud.totalBytesLoaded, hud.bandwidthBytesTotal, hud.transferredBytesTotal)
            .takeIf { it > 0L }
            ?.let { rows += StatsRow("Loaded", formatPlaybackStatsBytes(it)) }

        // Rebuffers (nt19): real STATE_BUFFERING count from the runtime controller,
        // hidden until the first rebuffer. The old "Stalls" row read positionStallCount,
        // which only counts a "zombie" freeze (player READY+playing yet position frozen)
        // and reads 0 through a genuine rebuffer -- that now drives "Pos. freeze" below.
        if (rebuffers > 0) {
            rows += StatsRow(
                "Rebuffers",
                String.format("%d (%.1f s total)", rebuffers, controller.rebufferTotalMs / 1000.0),
                recencyDot(rebuffers, statsRebuffersIncreasedAtMs)
            )
        }
        // Pos. freeze (nt19): renderer/clock wedge -- position frozen while the player
        // still reports READY+playing (the VC-1-class fault). Hidden until non-zero.
        if (hud.positionStallCount > 0) run {
            val longest = hud.longestPositionStallMs
            val value = if (hud.positionStallCount > 0 && longest > 0L) {
                String.format("%d (longest %.1f s)", hud.positionStallCount, longest / 1000.0)
            } else {
                hud.positionStallCount.toString()
            }
            rows += StatsRow("Pos. freeze", value, recencyDot(hud.positionStallCount, statsStallsIncreasedAtMs))
        }

        // P2P (P20, P23): TorrServer's 1 s stats poll, mirrored into the controller's state.
        if (statsUi.isTorrentStream) {
            rows += StatsRow(
                "Speed",
                "↓ ${formatTorrentSpeed(context, statsUi.torrentDownloadSpeed)} · " +
                    "↑ ${formatTorrentSpeed(context, statsUi.torrentUploadSpeed)}",
                group = StatsGroup.P2P
            )
            rows += StatsRow(
                "Peers",
                "${statsUi.torrentPeers} peers · ${statsUi.torrentSeeds} seeds",
                group = StatsGroup.P2P
            )
            torrentStatsValue(
                sizeBytes = statsUi.torrentSizeBytes,
                fileNumber = statsUi.torrentFileNumber,
                fileCount = statsUi.torrentFileCount,
                progress = statsUi.torrentTotalProgress,
                formatSize = ::formatPlaybackStatsBytes
            )?.let { rows += StatsRow("Torrent", it, group = StatsGroup.P2P) }
        }

        // Audio: format + passthrough state + bitrate.
        player?.audioFormat?.let { format ->
            val codec = audioCodecLabel(format.sampleMimeType)
            val srcChannels = format.channelCount.takeIf { it > 0 }
            // T9 (ysosrs 5438846d9): the src→out arrow reads the sink's configured input
            // live — for a PCM decode (incl. FFmpeg downmix) it IS the decoder's PCM output,
            // so its channelCount is the real post-downmix count. Passthrough/MAT leave it
            // non-RAW — no arrow shown. The mode below is the controller's, at most one
            // progress tick older.
            val sinkInput = controller.playbackSpeedAwareAudioSink?.lastConfiguredInputFormat
            val outChannels = sinkInput
                ?.takeIf { it.sampleMimeType == MimeTypes.AUDIO_RAW }
                ?.channelCount?.takeIf { it > 0 }
            val channels = srcChannels?.let { s ->
                if (outChannels != null && outChannels != s) "${s}ch→${outChannels}ch" else "${s}ch"
            }
            val rate = format.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
            // The controller's output mode (audioOutputMode: the layer-honesty rule names our own
            // AC-3 transcode rather than calling it passthrough), refreshed on its progress tick.
            val outputMode = statsUi.audioOutputMode
            val matActive = outputMode == AudioOutputMode.MAT
            val mode = when (outputMode) {
                AudioOutputMode.MAT -> "MAT out (app-packed)"
                AudioOutputMode.AC3_TRANSCODE -> "AC-3 transcode"
                AudioOutputMode.BITSTREAM -> "passthrough"
                AudioOutputMode.PCM -> "PCM out"
                null -> null
            }
            val losslessMime = format.sampleMimeType?.let {
                it.contains("true-hd") || it.contains("truehd") || it.contains("dts")
            } == true
            val dot = when (outputMode) {
                AudioOutputMode.MAT, AudioOutputMode.AC3_TRANSCODE, AudioOutputMode.BITSTREAM -> StatsDot.GOOD
                AudioOutputMode.PCM -> if (losslessMime) StatsDot.WARN else StatsDot.NONE
                null -> StatsDot.NONE
            }
            listOfNotNull(codec, channels, rate, mode).joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { rows += StatsRow("Audio", it, dot) }
            // Nothing declares a bitrate for a lossless track — MKV has no field for it and
            // TrueHD / DTS-HD MA are genuinely variable-rate — so under passthrough the sink
            // measures the encoded bitstream on its way out.
            val declaredAudioBitrate = (if (format.bitrate > 0) format.bitrate.toLong() else null)
                ?: (if (format.averageBitrate > 0) format.averageBitrate.toLong() else null)
            val matMeasuredBitrate =
                if (matActive) controller.matRoutingAudioSink?.measuredInputBitrateBps() else null
            val measuredAudioBitrate = matMeasuredBitrate
                ?: controller.playbackSpeedAwareAudioSink?.measuredAudioBitrateBps()
            rows += StatsRow(
                "A bitrate",
                when {
                    declaredAudioBitrate != null -> formatPlaybackStatsBitrate(declaredAudioBitrate)
                    measuredAudioBitrate != null -> "meas " + formatPlaybackStatsBitrate(measuredAudioBitrate)
                    losslessMime -> "— (VBR)"
                    else -> "— (undeclared)"
                }
            )
            audioDecoderValue(outputMode, hud.audioDecoderName)
                ?.let { rows += StatsRow("Decoder", it, group = StatsGroup.AUDIO) }
        }
        // Underruns. media3's count is event-driven off AudioTrack.getUnderrunCount() deltas,
        // polled only while the renderer is feeding; the native figure is read straight off the
        // track on this tick. A native count above the event count means media3 missed
        // underruns the hardware reported. Both at zero while the HAL logs underrun-restarts
        // means the failure is below the client buffer, inside the vendor HAL.
        // The native figure is now shown whenever it is available, even when it agrees with
        // media3's count — because "media3=0 · native 0" is itself the diagnostic result on a
        // stuttering file: the hardware reported no underruns, so the fault is below the client
        // buffer. Hiding the native figure when equal made a positive finding look like an
        // absence. A native count above media3's still warns (media3 missed hardware underruns).
        val nativeUnderruns = controller.playbackSpeedAwareAudioSink?.nativeAudioTrackUnderrunCount()
        rows += StatsRow(
            "Underruns",
            if (nativeUnderruns != null) {
                "${hud.audioUnderrunCount} · native $nativeUnderruns"
            } else {
                hud.audioUnderrunCount.toString()
            },
            if (nativeUnderruns != null && nativeUnderruns > hud.audioUnderrunCount) {
                StatsDot.WARN
            } else {
                recencyDot(hud.audioUnderrunCount, statsUnderrunsIncreasedAtMs)
            }
        )

        // Route (nt6): the AudioTrack's routed output device and how many times it has
        // changed mid-track this playback. A count ticking up while Buffer stays healthy
        // and Underruns stays flat is the route-steal static signature (system sounds,
        // capture tools, HDMI renegotiation). Dotless: the number judges itself in context.
        controller.playbackSpeedAwareAudioSink?.sampleAudioRoute()?.let { route ->
            rows += StatsRow("Route", "${route.deviceLabel} · ${route.changeCount} changes")
        }

        // Audio-clock jitter. Under passthrough the audio clock is the master clock, so a
        // clock that jumps drags the video renderer into bulk frame drops — the visible skips
        // of a vendor HAL failing to pack its output. Nothing else on this panel sees that.
        controller.playbackSpeedAwareAudioSink?.audioClockJitter()?.let { jitter ->
            // nt35: the dot is driven by 1 s-window drift, not per-sample max. Per-sample
            // deviations alias sampling noise; sustained drift of position vs wall clock
            // over 1 s windows is the real clock-health signal (clean capture: |drift|
            // p90 ~23 ms/s). The red threshold is provisional until a session with a
            // genuinely failing HAL clock can validate it - the known MS12 fault is
            // state-dependent and did not reproduce in the nt34 diagnostic run.
            val dot = when {
                jitter.driftWindows == 0 -> StatsDot.GOOD
                jitter.driftMeanAbsMs < t.AUDIO_DRIFT_GOOD_MS &&
                    kotlin.math.abs(jitter.driftLastMs) < t.AUDIO_DRIFT_BAD_MS -> StatsDot.GOOD
                jitter.driftMeanAbsMs < t.AUDIO_DRIFT_BAD_MS -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow(
                "A jitter",
                "drift avg ${jitter.driftMeanAbsMs} ms/s · max ${jitter.driftMaxAbsMs} · " +
                    "${jitter.events} ev",
                dot
            )
        }

        // App CPU and memory.
        playbackStatsCpuSampler.samplePercent()?.let { pct ->
            val dot = when {
                pct < t.CPU_GOOD_PERCENT -> StatsDot.GOOD
                pct <= t.CPU_BAD_PERCENT -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow("App CPU", "$pct %", dot)
        }
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapMax = runtime.maxMemory()
        val heapFraction = if (heapMax > 0L) heapUsed.toDouble() / heapMax else 0.0
        val heapDot = when {
            heapFraction < t.HEAP_GOOD_FRACTION -> StatsDot.GOOD
            heapFraction <= t.HEAP_BAD_FRACTION -> StatsDot.WARN
            else -> StatsDot.BAD
        }
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        rows += StatsRow(
            "Memory",
            "heap ${heapUsed / (1024L * 1024L)}/${heapMax / (1024L * 1024L)} MB · native $nativeMb MB",
            heapDot
        )

        // SoC thermal. Real degrees when sysfs is app-readable on this device;
        // otherwise the framework's normalised headroom (1.0 = severe throttling).
        // Hidden entirely when neither source answers — no dead rows.
        playbackStatsThermalSampler.sample()?.let { thermal ->
            when {
                thermal.celsius != null -> {
                    val dot = when {
                        thermal.celsius < t.SOC_TEMP_WARN_C -> StatsDot.GOOD
                        thermal.celsius < t.SOC_TEMP_BAD_C -> StatsDot.WARN
                        else -> StatsDot.BAD
                    }
                    rows += StatsRow("SoC temp", String.format("%.1f °C", thermal.celsius), dot)
                }
                thermal.headroom != null -> {
                    val dot = when {
                        thermal.headroom < t.THERMAL_HEADROOM_WARN -> StatsDot.GOOD
                        thermal.headroom < t.THERMAL_HEADROOM_BAD -> StatsDot.WARN
                        else -> StatsDot.BAD
                    }
                    rows += StatsRow(
                        "SoC temp",
                        String.format("headroom %.2f (1.0 = throttle)", thermal.headroom),
                        dot
                    )
                }
            }
        }

        // CPU clock / throttle. No app-visible SoC temperature exists on this box (only a
        // TYPE_CPU sensor, which getThermalHeadroom ignores; sysfs thermal is SELinux-blocked),
        // so this reads cpufreq instead: the SoC throttles by capping clock frequency, and the
        // cap (scaling_max_freq) dropping below the hardware max is the throttle signal. Red
        // when capped. Hidden when cpufreq is unreadable.
        playbackStatsCpuClockSampler.sample()?.let { clock ->
            val dot = if (clock.isThrottled) StatsDot.BAD else StatsDot.GOOD
            rows += StatsRow(
                "CPU clock",
                String.format(
                    "%.2f GHz · cap %.2f GHz",
                    clock.currentKHz / 1_000_000f,
                    clock.capKHz / 1_000_000f
                ),
                dot
            )
        }
        return PlaybackStatsSample(isExoPlayer = true, sections = buildStatsSections(rows))
    }

    /**
     * Human names for the audio MIME types this fork actually sees; the raw MIME suffix
     * ("vnd.dts.hd") is not a codec name anyone recognises. This is the codec the
     * *container* declares, which is all that can be known at this level: object
     * extensions — DTS:X inside DTS-HD MA, Atmos inside TrueHD — are not signalled in the
     * container and ride inside the bitstream, so they are visible only to the receiver
     * that decodes it. A soundbar reporting DTS:X on a track shown here as DTS-HD MA is
     * both of them being right.
     */
    private fun audioCodecLabel(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        // MIME-honest: vnd.dts.hd covers both MA and HRA; the container label may
        // say MA but the MIME cannot confirm it. Matches the diagnostics card.
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 JOC"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mimeType?.substringAfter('/')
    }

    private fun formatPlaybackStatsBitrate(bps: Long): String = if (bps >= 1_000_000L) {
        String.format("%.2f Mbit/s", bps / 1_000_000.0)
    } else {
        String.format("%.0f kbit/s", bps / 1_000.0)
    }

    private fun formatPlaybackStatsBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        else -> "${bytes / 1024L} KB"
    }

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    /** Spec 75 S6: the failure store's marks now (the Sources panels' rows), read off the main thread. */
    suspend fun recentFailures(): com.nuvio.tv.core.stream.RecentFailures =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { failedReleases.recent() }

    /** Spec 86 R3: what the error box can offer now (an untried source, the other engine). */
    internal fun failureBoxOptions(): FailureBoxOptions = FailureBoxOptions(
        nextSource = controller.nextSourceAfterPlaying() != null,
        engineTarget = controller.failureBoxEngineTarget()
    )

    /** Spec 86 R3: the box is up; load the playing item's list if it has none, for Next source. */
    fun onFailureBoxShown() {
        controller.loadSourceListForFailureBox()
    }

    fun playNextSourceFromFailureBox() {
        controller.playNextSourceFromFailureBox()
    }

    fun retryFromFailureBox() {
        controller.retryFromFailureBox()
    }

    fun switchEngineFromFailureBox() {
        controller.switchEngineFromFailureBox()
    }

    fun stopAndRelease() {
        controller.stopAndRelease()
        playerSession.close()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: NuvioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    fun bindExoSubtitleView(subtitleView: androidx.media3.ui.SubtitleView?) {
        controller.bindExoSubtitleView(subtitleView)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    /** Spec 33 D7: the network test's notice has been shown. */
    fun consumeNetworkTestNotice() {
        controller.consumeNetworkTestNotice()
    }

    override fun onCleared() {
        screensaverController.setPlaybackActive(false)
        playerSession.close()
        controller.onCleared()
        // Allow the trailer player to be re-created when returning to home screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Launch the current stream in an external player via the centralized tracker.
     *
     * Keep the ViewModel alive until the external intent has been handed to the launcher.
     * This lets the caller navigate away only after a successful handoff, while failures
     * remain visible on the current player screen (#2560).
     */
    fun launchInExternalPlayer(
        activityContext: Context,
        resumePositionMs: Long,
        onResult: (Boolean) -> Unit
    ) {
        val url = controller.getCurrentStreamUrl()
        if (url.isBlank()) {
            onResult(false)
            return
        }
        val contentId = controller.contentId ?: run {
            onResult(false)
            return
        }
        val videoId = controller.currentVideoId ?: contentId
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = videoId,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year
        )
        val headers = controller.getCurrentHeaders()
        val nextEpisodeSnapshot = controller.metaVideos
            .takeIf { it.isNotEmpty() }
            ?.let { videos ->
                com.nuvio.tv.core.player.resolveExternalNextEpisodeSnapshot(
                    videos = videos,
                    currentSeason = metadata.season,
                    currentEpisode = metadata.episode
                )
            }

        // Capture already-loaded addon subtitles before handing off. Preparation stays in the
        // ViewModel scope because the player screen remains alive until the intent is sent.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val cachedSubtitles = subtitleInputs?.let { inputs ->
                try {
                    withTimeoutOrNull(10_000L) {
                        subtitleFileCache.cacheSubtitles(inputs)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // viewModelScope cancelled: do not release the player or launch externally.
                    throw e
                } catch (_: Exception) {
                    // Subtitle forwarding is best-effort; the external launch must still proceed.
                    null
                }
            }

            // A torrent's local URL needs its TorrServer session alive: detach it from the
            // controller first so neither the release below (stopTorrentStream) nor onCleared
            // stops it, and hand it to the tracker, which owns it from launchPlayer on.
            val torrentSession = controller.torrentSession.takeIf { controller.isTorrentStream }
            if (torrentSession != null) controller.torrentSession = null
            // Stop the internal player only after preparation has completed and immediately
            // before sending the external intent.
            controller.stopAndRelease()
            val launched = try {
                externalPlaybackTracker.launchPlayer(
                    metadata = metadata,
                    url = url,
                    title = metadata.buildPlayerTitle(),
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = cachedSubtitles,
                    nextEpisodeSnapshot = nextEpisodeSnapshot,
                    torrentSession = torrentSession,
                    context = activityContext
                )
            } catch (_: Exception) {
                false
            }
            onResult(launched)
        }
    }
}

/**
 * Value and dot for the HUD's DV row. [modeEffective] is the effective `Dv7HandlingMode` name;
 * [el] is the " · <EL type>" suffix or empty; [libdoviConverting] is read only for the libdovi
 * mode. Judges from the actual source profile first: a stream already Profile 8 (or 5) needs no
 * conversion, so an idle converter is healthy (the old text overclaimed "P7→8.1" from the mode
 * setting alone, nt28 field finding). STRIP_DV and OFF name their path instead of falling through
 * to "7 native" (Register A2 P14, ysosrs `d231791e5` wording). The two strip modes come before
 * "native": they drop the RPU of every P7/P8 source, and the MKV path records the stripped
 * source's profile (F-11), so a P8 there is not native. P5's strip is decided per stream (base
 * layer HDR10 SEI), so P5 always reads native.
 */
internal fun dvStatsRowValue(
    sourceProfile: String,
    el: String,
    modeEffective: String,
    libdoviConverting: () -> Boolean
): Pair<String, StatsDot> {
    val sourceProfileNum = sourceProfile.trim().toIntOrNull()
    val stripped = "P${sourceProfileNum ?: 7}"
    return when {
        sourceProfileNum == 5 -> "P5 native$el" to StatsDot.NONE
        modeEffective == "HDR10_BASE_LAYER" -> "$stripped→HDR10 base layer$el" to StatsDot.NONE
        modeEffective == "STRIP_DV" -> "$stripped→HDR10 (RPU stripped)$el" to StatsDot.NONE
        sourceProfileNum != null && sourceProfileNum != 7 -> "P$sourceProfileNum native$el" to StatsDot.NONE
        modeEffective == "DV81_LIBDOVI" ->
            "P7→8.1 libdovi$el" to if (libdoviConverting()) StatsDot.GOOD else StatsDot.WARN
        modeEffective == "OFF" -> "P7 · MediaCodec$el" to StatsDot.NONE
        else -> "$sourceProfile native$el" to StatsDot.NONE
    }
}

/**
 * Codec row value (P15): HEVC and H.264 codec strings read as profile + level ("hvc1.2.4.L153.B0" ->
 * "HEVC Main 10 L5.1", "avc1.640029" -> "H.264 High L4.1"); any other string (Dolby Vision's
 * "dvhe.07.06", AV1, VP9) is shown as written. Only the first entry of a comma list is read.
 */
internal fun videoCodecProfileLabel(codecs: String?): String? {
    val entry = codecs?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = entry.split('.')
    val parsed = when (parts[0]) {
        "hvc1", "hev1" -> {
            val profile = parts.getOrNull(1)?.trimStart('A', 'B', 'C')?.toIntOrNull()
            val level = parts.getOrNull(3)?.takeIf { it.startsWith('L') || it.startsWith('H') }
                ?.drop(1)?.toIntOrNull()
            if (profile != null && level != null) {
                val name = when (profile) {
                    1 -> "Main"
                    2 -> "Main 10"
                    4 -> "RExt"
                    else -> "profile $profile"
                }
                "HEVC $name ${codecLevelLabel(level / 30, (level % 30) / 3)}"
            } else {
                null
            }
        }
        "avc1", "avc3" -> {
            val hex = parts.getOrNull(1)?.takeIf { it.length == 6 }
            val profile = hex?.substring(0, 2)?.toIntOrNull(16)
            val level = hex?.substring(4, 6)?.toIntOrNull(16)
            if (profile != null && level != null) {
                val name = when (profile) {
                    66 -> "Baseline"
                    77 -> "Main"
                    100 -> "High"
                    110 -> "High 10"
                    else -> "profile $profile"
                }
                "H.264 $name ${codecLevelLabel(level / 10, level % 10)}"
            } else {
                null
            }
        }
        else -> null
    }
    return parsed ?: entry
}

private fun codecLevelLabel(major: Int, minor: Int): String = if (minor == 0) "L$major" else "L$major.$minor"

/**
 * Frame rate row (P16): what auto frame rate did with this stream. Matched / not matched judge the
 * AFR-applied mode ([appliedRefreshHz]: the last mode AFR applied this session, null before its
 * first switch) against the detected rate; the Output row above judges the live display mode.
 * [detecting] covers the probe and a track-driven switch in flight (the applied mode is still the
 * previous one then, as the frame-rate badge rule knows).
 */
internal fun afrStatsRow(
    mode: FrameRateMatchingMode,
    detecting: Boolean,
    detectedFps: Float,
    source: FrameRateSource?,
    appliedRefreshHz: Float?
): StatsRow {
    val (value, dot) = when {
        mode == FrameRateMatchingMode.OFF -> "AFR off" to StatsDot.NONE
        detecting -> "detecting…" to StatsDot.NONE
        detectedFps <= 0f -> "not detected" to StatsDot.NONE
        else -> {
            val from = when (source) {
                FrameRateSource.TRACK -> " · from container"
                FrameRateSource.PROBE -> " · from probe"
                null -> ""
            }
            val fps = "${frameRateLabel(detectedFps)} fps$from"
            when {
                appliedRefreshHz == null -> "no switch · $fps" to StatsDot.NONE
                isRefreshMatch(appliedRefreshHz, detectedFps) -> "matched · $fps" to StatsDot.GOOD
                else -> "${frameRateLabel(appliedRefreshHz)} Hz · $fps" to StatsDot.WARN
            }
        }
    }
    return StatsRow("Frame rate", value, dot)
}

/** Torrent row (P23): "57.9 GB · file 1 of 3 · 12%"; null until TorrServer reports a size. */
internal fun torrentStatsValue(
    sizeBytes: Long,
    fileNumber: Int,
    fileCount: Int,
    progress: Float,
    formatSize: (Long) -> String
): String? {
    if (sizeBytes <= 0L) return null
    val file = if (fileNumber > 0 && fileCount > 1) " · file $fileNumber of $fileCount" else ""
    return formatSize(sizeBytes) + file + " · ${(progress * 100).toInt()}%"
}

/** Colour row (P12): "BT.2020 · PQ · limited" from Media3's C constants; null when none is known. */
internal fun colourLabel(colorSpace: Int?, colorTransfer: Int?, colorRange: Int?): String? {
    val space = when (colorSpace) {
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        else -> null
    }
    val transfer = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "PQ"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "gamma 2.2"
        C.COLOR_TRANSFER_SRGB -> "sRGB"
        C.COLOR_TRANSFER_LINEAR -> "linear"
        else -> null
    }
    val range = when (colorRange) {
        C.COLOR_RANGE_LIMITED -> "limited"
        C.COLOR_RANGE_FULL -> "full"
        else -> null
    }
    return listOfNotNull(space, transfer, range).joinToString(" · ").ifEmpty { null }
}

/**
 * Mastering row (P12) from Media3's HDR static info (CTA-861.3 type 0, 25 bytes little-endian: type,
 * eight chromaticity shorts, max + min mastering luminance, MaxCLL, MaxFALL): "1000 nits · CLL 942 ·
 * FALL 318". Media3's Matroska path writes 1000 / 200 when the file omits MaxCLL / MaxFALL, so that
 * exact pair reads "CLL/FALL unknown" (a real 1000 / 200 cannot be told apart).
 */
internal fun masteringLabel(hdrStaticInfo: ByteArray?): String? {
    if (hdrStaticInfo == null || hdrStaticInfo.size < 25 || hdrStaticInfo[0] != 0.toByte()) return null
    fun u16(offset: Int): Int =
        (hdrStaticInfo[offset].toInt() and 0xFF) or ((hdrStaticInfo[offset + 1].toInt() and 0xFF) shl 8)
    val maxNits = u16(17)
    val cll = u16(21)
    val fall = u16(23)
    val light = when {
        cll == MKV_DEFAULT_MAX_CLL && fall == MKV_DEFAULT_MAX_FALL -> "CLL/FALL unknown"
        cll > 0 || fall > 0 -> "CLL $cll · FALL $fall"
        else -> null
    }
    return listOfNotNull(maxNits.takeIf { it > 0 }?.let { "$it nits" }, light).joinToString(" · ").ifEmpty { null }
}

private const val MKV_DEFAULT_MAX_CLL = 1000
private const val MKV_DEFAULT_MAX_FALL = 200

/** Audio Decoder row (P14): which decoder turns the track into PCM; none while the sink takes the bitstream. */
internal fun audioDecoderValue(outputMode: AudioOutputMode?, decoderName: String?): String? = when (outputMode) {
    AudioOutputMode.BITSTREAM, AudioOutputMode.MAT -> "none (passthrough)"
    else -> decoderName
}

/**
 * Video Decoder row value: the codec name plus HW / SW, by the name rule of Media3 1.8.0's
 * MediaCodecUtil.isSoftwareOnly pre-API 29 (Google/Android software codecs, and any name outside
 * the omx. / c2. / arc. namespaces, such as the FFmpeg extension's). Audio decoders get no suffix:
 * Media3 itself calls every one software there.
 */
internal fun decoderLabel(name: String): String =
    "$name · ${if (isSoftwareCodecName(name.lowercase(Locale.ROOT))) "SW" else "HW"}"

private fun isSoftwareCodecName(n: String): Boolean {
    if (n.startsWith("arc.")) return false
    return n.startsWith("omx.google.") ||
        n.startsWith("omx.ffmpeg.") ||
        (n.startsWith("omx.sec.") && n.contains(".sw.")) ||
        n == "omx.qcom.video.decoder.hevcswvdec" ||
        n.startsWith("c2.android.") ||
        n.startsWith("c2.google.") ||
        (!n.startsWith("omx.") && !n.startsWith("c2."))
}
