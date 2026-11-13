package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.core.util.extractReleaseYear
import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.PrefetchedSelectionGate
import com.nuvio.tv.core.player.SelectionOutcome
import com.nuvio.tv.core.player.SelectionSnapshot
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.stream.StreamLimitInputs
import com.nuvio.tv.core.stream.HiddenStream
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.core.stream.StreamTextSizeParser
import com.nuvio.tv.core.stream.deriveStreamList
import com.nuvio.tv.core.stream.hiddenStreamVerdicts
import com.nuvio.tv.core.stream.keptIn
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.core.stream.sourceChipAddonNames
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.repository.streamRequestType
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.ui.components.sources.toLastPlayed
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.core.util.parseRuntimeMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "StreamScreenViewModel"
private const val DIRECT_AUTOPLAY_HARD_TIMEOUT_MS = 60_000L

@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgePresentation: StreamBadgePresentation,
    streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val rejectedStreamUrls: com.nuvio.tv.core.stream.RejectedStreamUrls,
    private val torrentSettings: TorrentSettings,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var autoPlayHandledForSession = false
    private var ttffSourcesReadyMarked = false
    private var directAutoPlayModeInitializedForSession = false
    private var directAutoPlayFlowEnabledForSession = false
    private var streamLoadJob: Job? = null
    private var streamLoadScope: kotlinx.coroutines.CoroutineScope? = null
    private var streamLoadCompleted = false
    // Retry repeats the last load's kind: after a failed Refresh, a plain reload would keep
    // isLoading false over the stale allStreams and flash the empty message before results.
    private var lastLoadForcedRefresh = false
    private var sourceChipErrorDismissJob: Job? = null
    private var sessionFillJob: Job? = null
    private var pendingCacheSaveJob: Job? = null
    private var streamBadgePresentationJob: Job? = null
    // Main-thread only: read by scheduleStreamBadgePresentation and written by the badge
    // job's bookkeeping, both on Main, so the job's final pending check and the
    // isActive early-return cannot interleave (no lost wake-up).
    private var badgedAddonNames: Set<String> = emptySet()

    private val embeddedStreamGroupName: String by lazy {
        context.getString(R.string.stream_embedded_group)
    }
    private val embeddedStreamFallbackName: String by lazy {
        context.getString(R.string.stream_embedded_fallback_name)
    }

    private val videoId: String = savedStateHandle["videoId"] ?: ""
    // Id the addon stream/subtitle requests use. Differs from videoId only for a
    // meta-level play whose meta names a behaviorHints.defaultVideoId; videoId stays
    // the playback identity (player, watch progress, link cache).
    private val streamRequestId: String =
        resolveStreamRequestId(savedStateHandle.get<String>("streamRequestId"), videoId)
    private val contentType: String = savedStateHandle["contentType"] ?: ""
    private val title: String = savedStateHandle["title"] ?: ""
    private val poster: String? = savedStateHandle.getOptionalString("poster")
    private val backdrop: String? = savedStateHandle.getOptionalString("backdrop")
    private val logo: String? = savedStateHandle.getOptionalString("logo")
    private val season: Int? = savedStateHandle.get<String>("season")?.toIntOrNull()
    private val episode: Int? = savedStateHandle.get<String>("episode")?.toIntOrNull()
    private val episodeName: String? = savedStateHandle.getOptionalString("episodeName")
    private val runtime: Int? = savedStateHandle.get<String>("runtime")?.toIntOrNull()
    private val genres: String? = savedStateHandle.getOptionalString("genres")
    private val year: String? = savedStateHandle.getOptionalString("year")
    private val contentId: String? = savedStateHandle.getOptionalString("contentId")
    private val contentName: String? = savedStateHandle.getOptionalString("contentName")
    private val contentLanguage: String? = savedStateHandle.getOptionalString("contentLanguage")
    private val manualSelection: Boolean = savedStateHandle.get<String>("manualSelection")
        ?.toBooleanStrictOrNull()
        ?: false
    private val streamCacheKey: String = streamLinkCacheKey(contentType, videoId)

    private val _uiState = MutableStateFlow(
        StreamScreenUiState(
            videoId = videoId,
            contentType = contentType,
            title = title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            season = season,
            episode = episode,
            episodeName = episodeName,
            runtime = runtime,
            genres = genres,
            year = year
        )
    )
    val uiState: StateFlow<StreamScreenUiState> = _uiState.asStateFlow()
    val streamBadgeSettings = streamBadgeSettingsDataStore.settings

    /** The IMDb mark beside the meta line follows the overall ratings setting (as Home and Detail). */
    val showImdbRatings = layoutPreferenceDataStore.homeImdbRatingsVisibility
        .map { it.showRatings }
        .distinctUntilChanged()

    val p2pEnabled = torrentSettings.settings
        .map { it.p2pEnabled }
        .distinctUntilChanged()

    fun enableP2p() = torrentSettings.setP2pEnabled(true)

    private inline fun updateUiStateIfChanged(
        transform: (StreamScreenUiState) -> StreamScreenUiState
    ) {
        _uiState.update { state ->
            val next = transform(state)
            if (next == state) state else next
        }
    }

    private fun scheduleStreamBadgePresentation(groups: List<AddonStreams>) {
        // Only process addon groups that haven't been badged yet
        val newGroups = groups.filter { it.addonName !in badgedAddonNames }
        if (newGroups.isEmpty()) return

        // Don't cancel a running job — let it finish its current addons.
        // After it completes, it will check for any new addons that arrived.
        if (streamBadgePresentationJob?.isActive == true) return

        // Runs on Main (viewModelScope); only the badge parse and state merge hop to
        // Default, so the bookkeeping below stays on Main (see badgedAddonNames).
        streamBadgePresentationJob = viewModelScope.launch {
            var pending = newGroups
            while (pending.isNotEmpty()) {
                val allNewStreams = pending.flatMap { it.streams }
                withContext(Dispatchers.Default) {
                    for (chunk in allNewStreams.chunked(5)) {
                        ensureActive()
                        val chunkGroup = AddonStreams(addonName = "", addonLogo = null, streams = chunk)
                        val badgedChunk = streamBadgePresentation.apply(listOf(chunkGroup))
                            .firstOrNull()?.streams ?: chunk
                        ensureActive()
                        // Keyed by limitKey, not badgeMergeKey: two services' copies of one torrent
                        // file share the merge key, and the whole badged stream replaces the row,
                        // so a merge-key match would give copy B copy A's URL (Spec 51 R5-7).
                        val badgedByKey = badgedChunk.associateBy { it.limitKey() }
                        updateUiStateIfChanged { state ->
                            val updatedAddonStreams = state.addonStreams.map { group ->
                                group.copy(
                                    streams = group.streams.map { stream ->
                                        badgedByKey[stream.limitKey()] ?: stream
                                    }
                                )
                            }
                            // Badges change no stream's size or limit key: the hidden set stands.
                            state.copy(
                                addonStreams = updatedAddonStreams,
                                allStreams = updatedAddonStreams.flatMap { it.streams }
                            ).withListView(state.listView.hiddenVerdicts)
                        }
                    }
                }
                // Back on Main; withContext rethrows if cancelled meanwhile, so a
                // cancelled job never writes after loadStreams resets the set.
                // Mark processed addons as done
                badgedAddonNames = badgedAddonNames + pending.map { it.addonName }.toSet()
                // Check if new addons arrived while we were processing
                val currentAddons = _uiState.value.addonStreams
                pending = currentAddons.filter { it.addonName !in badgedAddonNames }
            }
        }
    }

    // Main only (written by the collector below and loadStreams' first read; read through
    // hiddenVerdictsFor by applySuccess, loadMetaDetails and rederiveList); null until the first
    // read: nothing is hidden until then.
    private var limitInputs: StreamLimitInputs? = null
    private val isEpisodeList = season != null && episode != null

    /** The limit's hidden set over [streams] (Spec 51 P4), with the held limit and the link now. */
    private fun hiddenVerdictsFor(streams: List<Stream>, runtimeMinutes: Int?): Map<String, HiddenStream> =
        hiddenStreamVerdicts(
            streams = streams,
            limit = limitInputs,
            transport = { NetworkTransportReader.current(context) },
            runtimeMinutes = runtimeMinutes,
            episode = isEpisodeList
        )

    /** Re-derives the list under the current streams, runtime and limit (M3). Main. */
    private fun rederiveList() {
        updateUiStateIfChanged { it.withListView(hiddenVerdictsFor(it.allStreams, it.runtime)) }
    }

    init {
        viewModelScope.launch {
            playerSettingsDataStore.streamLimitInputs.collect { inputs ->
                limitInputs = inputs
                rederiveList()
            }
        }
        viewModelScope.launch {
            _uiState
                .map { state -> state.directAutoPlayMessage }
                .distinctUntilChanged()
                .collectLatest { message ->
                    externalPlaybackTracker.updateAutoNextOverlayStatus(message)
                }
        }
        if (manualSelection) {
            // Returning from a playback error: keep the user on stream selection.
            autoPlayHandledForSession = true
            directAutoPlayModeInitializedForSession = true
            directAutoPlayFlowEnabledForSession = false
            _uiState.update {
                it.copy(
                    isDirectAutoPlayFlow = false,
                    showDirectAutoPlayOverlay = false,
                    autoPlayDecided = true,
                    autoPlayStream = null,
                    autoPlayPlaybackInfo = null,
                    directAutoPlayMessage = null
                )
            }
        }
        loadMetaDetails()
        loadStreams()
    }

    private fun SavedStateHandle.getOptionalString(key: String): String? {
        return get<String>(key)?.takeIf { it.isNotBlank() }
    }

    fun onEvent(event: StreamScreenEvent) {
        when (event) {
            is StreamScreenEvent.OnListSelected -> selectList(event.selection)
            StreamScreenEvent.OnAutoPlayConsumed -> {
                if (autoPlayHandledForSession &&
                    _uiState.value.autoPlayStream == null &&
                    _uiState.value.autoPlayPlaybackInfo == null
                ) {
                    return
                }
                autoPlayHandledForSession = true
                directAutoPlayFlowEnabledForSession = false
                // Don't dismiss overlay if external player is active - it should
                // stay visible until user returns from external player.
                val keepOverlay = externalPlaybackTracker.isTracking
                updateUiStateIfChanged {
                    it.copy(
                        autoPlayStream = null,
                        autoPlayPlaybackInfo = null,
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = if (keepOverlay) true else false,
                        directAutoPlayMessage = null
                    )
                }
            }
            StreamScreenEvent.OnRefresh -> {
                updateUiStateIfChanged {
                    // The rows clear until the refresh lands; the hidden set stays, so a pick or a
                    // badge pass before then cannot show over-limit rows.
                    it.copy(
                        isLoading = true,
                        error = null,
                        listSelection = StreamListSelection.All,
                        listView = StreamListView(hiddenVerdicts = it.listView.hiddenVerdicts)
                    )
                }
                loadStreams(forceRefresh = true)
            }
            StreamScreenEvent.OnRetry -> loadStreams(forceRefresh = lastLoadForcedRefresh)
            StreamScreenEvent.OnResume -> {
                hostInForeground.value = true
                refreshLastPlayed()
                // Reattach to the repository-owned session if playback stopped
                // this screen's collector (or its capped-session fill, see
                // cancelStreamsLoad) before every source finished.
                if (!streamLoadCompleted && streamLoadJob == null) {
                    loadStreams()
                }
            }
        }
    }

    fun cancelStreamsLoad() {
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        streamBadgePresentationJob?.cancel()
        // Stop the capped-session fill too so it doesn't compete with player startup.
        // A fill cut short leaves the manual list incomplete, so mark the load
        // incomplete: OnResume then reattaches via loadStreams, which refills.
        if (sessionFillJob?.isActive == true) streamLoadCompleted = false
        sessionFillJob?.cancel()
        updateUiStateIfChanged { it.copy(isLoading = false) }
    }

    private fun shouldUseDirectAutoPlayFlow(
        streamAutoPlayMode: StreamAutoPlayMode
    ): Boolean {
        return streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    }

    private fun loadStreams(forceRefresh: Boolean = false) {
        TtffTrace.begin("streams_load_start")
        lastLoadForcedRefresh = forceRefresh
        ttffSourcesReadyMarked = false
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        streamBadgePresentationJob?.cancel()
        if (forceRefresh || _uiState.value.addonStreams.isEmpty()) {
            badgedAddonNames = emptySet()
        }
        sourceChipErrorDismissJob?.cancel()
        sessionFillJob?.cancel()
        val newScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob())
        streamLoadScope = newScope
        streamLoadJob = newScope.launch {
            streamLoadCompleted = false
            // LOAD_SPLIT: on a StreamPrefetchCache hit the addon scrape is already
            // done, yet sources_ready still measured 1,049 ms (24 Jul 2026). These
            // marks attribute that residual: three sequential DataStore reads run
            // before the scrape is even built, and applySuccess does ordering,
            // badge-merge and chip-merge work on Main afterwards. Logging only.
            val loadSplitT0 = SystemClock.elapsedRealtime()
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            // Spec 51 P5: auto-play reads the held limit at its select; the collector keeps it
            // current, so this read only fills it before the collector's first value (never
            // overwriting a newer one the collector applied while it ran).
            if (limitInputs == null) {
                val read = playerSettingsDataStore.streamLimitInputs.first()
                if (limitInputs == null) limitInputs = read
            }
            android.util.Log.i(TAG, "LOAD_SPLIT playerSettings=${SystemClock.elapsedRealtime() - loadSplitT0}ms")
            if (manualSelection) {
                directAutoPlayModeInitializedForSession = true
                directAutoPlayFlowEnabledForSession = false
                autoPlayHandledForSession = true
            } else if (!directAutoPlayModeInitializedForSession) {
                directAutoPlayFlowEnabledForSession = shouldUseDirectAutoPlayFlow(
                    streamAutoPlayMode = playerSettings.streamAutoPlayMode
                )
                // In MANUAL mode, still enable direct auto-play if a persisted
                // binge group exists - same behavior as playNextEpisode in the player.
                if (!directAutoPlayFlowEnabledForSession &&
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode &&
                    playerSettings.streamAutoPlayReuseBingeGroup
                ) {
                    val bingeSplitT0 = SystemClock.elapsedRealtime()
                    val hasBingeGroup = contentId?.let { bingeGroupCacheDataStore.get(it) } != null
                    android.util.Log.i(TAG, "LOAD_SPLIT bingeGroup=${SystemClock.elapsedRealtime() - bingeSplitT0}ms")
                    if (hasBingeGroup) {
                        directAutoPlayFlowEnabledForSession = true
                    }
                }
                directAutoPlayModeInitializedForSession = true
            }

            if (
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex)
            ) {
                directAutoPlayFlowEnabledForSession = false
                autoPlayHandledForSession = true
            }

            val directFlowActive = directAutoPlayFlowEnabledForSession
            var resolvedAutoPlayTarget = false

            if (directFlowActive) {
                updateUiStateIfChanged {
                    it.copy(
                        isDirectAutoPlayFlow = true,
                        showDirectAutoPlayOverlay = true,
                        autoPlayDecided = true,
                        directAutoPlayMessage = if (playerSettings.showPlayerLoadingStatus) {
                            context.getString(R.string.stream_finding_source)
                        } else {
                            null
                        }
                    )
                }
            } else {
                updateUiStateIfChanged {
                    it.copy(autoPlayDecided = true)
                }
            }

            if (!autoPlayHandledForSession && playerSettings.streamReuseLastLinkEnabled) {
                var cached = streamLinkCacheDataStore.getValid(
                    contentKey = streamCacheKey,
                    maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                )
                // Self-heal (2026-08-30, Arshad's expired-link report): resolved debrid
                // links can die inside the reuse window. A positively-dead check evicts the entry
                // and falls through to a fresh resolve instead of 404ing into the error
                // screen on re-entry.
                if (cached != null && cached.url.isNotBlank() && !isHttpLinkAlive(cached.url, cached.headers)) {
                    streamLinkCacheDataStore.evict(streamCacheKey)
                    cached = null
                }
                if (cached != null) {
                    autoPlayHandledForSession = true
                    resolvedAutoPlayTarget = true
                    val isCachedTorrent = cached.infoHash != null && cached.url.isNullOrBlank()
                    updateUiStateIfChanged {
                        it.copy(
                            autoPlayPlaybackInfo = StreamPlaybackInfo(
                                url = cached.url.takeIf { u -> u.isNotBlank() },
                                title = title,
                                streamName = cached.streamName,
                                year = cached.year ?: year,
                                isExternal = false,
                                isTorrent = isCachedTorrent,
                                infoHash = cached.infoHash,
                                ytId = null,
                                headers = cached.headers,
                                contentId = contentId ?: videoId.substringBefore(":"),
                                contentType = contentType,
                                contentName = contentName ?: title,
                                poster = poster,
                                backdrop = backdrop,
                                logo = logo,
                                videoId = videoId,
                                season = season,
                                episode = episode,
                                episodeTitle = episodeName,
                                bingeGroup = cached.bingeGroup,
                                filename = cached.filename,
                                videoHash = cached.videoHash,
                                videoSize = cached.videoSize,
                                fileIdx = cached.fileIdx,
                                sources = cached.sources,
                                contentLanguage = cached.contentLanguage ?: contentLanguage
                            )
                        )
                    }
                }
            }

            updateUiStateIfChanged {
                it.copy(
                    isLoading = forceRefresh || it.allStreams.isEmpty(),
                    error = null,
                    showDirectAutoPlayOverlay = if (directFlowActive) true else it.showDirectAutoPlayOverlay
                )
            }

            val addonsSplitT0 = SystemClock.elapsedRealtime()
            val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
            android.util.Log.i(
                TAG,
                "LOAD_SPLIT installedAddons=${SystemClock.elapsedRealtime() - addonsSplitT0}ms " +
                    "preScrapeTotal=${SystemClock.elapsedRealtime() - loadSplitT0}ms"
            )
            val installedAddonOrder = installedAddons.map { it.displayName }
            val persistedBingeGroup = AutoPlaySelection.persistedBingeGroup(
                playerSettings, contentId, bingeGroupCacheDataStore::get
            )

            // One argument assembly for both selection call sites below, shared
            // with the prefetch ranking (PrefetchSelectionSupplier), which must
            // predict the same winner. Every field but the hidden set is a snapshot:
            // playerSettings from one .first(), installedAddonOrder from one
            // getInstalledAddons().first(), persistedBingeGroup from one cache read.
            // [hiddenKeys] is the limit's set over the candidates being ranked, under
            // the runtime known at that select (Spec 51 D8: nothing waits for one).
            val rejectedUrls = rejectedStreamUrls.snapshot()
            fun inputsFor(hiddenKeys: Set<String>) = AutoPlaySelection.Inputs.fromSettings(
                settings = playerSettings,
                installedAddonOrder = installedAddonOrder,
                preferredBingeGroup = persistedBingeGroup,
                rejectedUrls = rejectedUrls,
                hiddenKeys = hiddenKeys
            )

            fun applySuccess(addonStreamGroups: List<AddonStreams>, isAllLoaded: Boolean) {
                val applyT0 = SystemClock.elapsedRealtime()
                val orderedAddonStreams = StreamAutoPlaySelector.orderAddonStreams(
                    addonStreamGroups,
                    installedAddonOrder
                )
                val applyOrderMs = SystemClock.elapsedRealtime() - applyT0

                // Preserve badges already computed by prior badge jobs so they
                // don't vanish when repository emits fresh (badge-less) streams.
                val existingBadgedStreams = _uiState.value.allStreams
                    .filter { it.badges.isNotEmpty() }
                    .associateBy { it.limitKey() }

                val mergedAddonStreams = if (existingBadgedStreams.isEmpty()) {
                    orderedAddonStreams
                } else {
                    orderedAddonStreams.map { group ->
                        group.copy(
                            streams = group.streams.map { stream ->
                                val existing = existingBadgedStreams[stream.limitKey()]
                                if (existing != null && stream.badges.isEmpty()) {
                                    stream.copy(badges = existing.badges)
                                } else {
                                    stream
                                }
                            }
                        )
                    }
                }

                val allStreams = mergedAddonStreams.flatMap { it.streams }
                val applyBadgeMs = SystemClock.elapsedRealtime() - applyT0 - applyOrderMs
                // One hidden set for the list and the select below (Spec 51 P4/P5); timed
                // inside APPLY_SPLIT's select segment.
                val hidden = hiddenVerdictsFor(allStreams, _uiState.value.runtime)
                val availableAddons = mergedAddonStreams.map { it.addonName }
                // Auto-select only after all addons have responded or the
                // configured timeout has elapsed. This gives slower addons a
                // chance to return higher-quality streams before the selector
                // picks from whatever is available.
                val shouldAutoSelect = !autoPlayHandledForSession && !resolvedAutoPlayTarget && isAllLoaded
                val autoPlayInputs = inputsFor(hidden.keys)
                // R2: the rank normally happened here, on Main.immediate, and
                // measured 398/341/379/367 ms across four runs (24 Jul 2026),
                // 94% of it inside ReleaseFactsParser.factsFor. The
                // prefetch now does it 2.6-7.6 s early. The gate below discards
                // the cached winner unless every setting that decided it still
                // holds, so a divergent pick is structurally excluded rather
                // than detected. src=live with a reason is the honest fallback
                // and must not be read as a win.
                val selectedAutoPlayStream = if (!shouldAutoSelect) {
                    null
                } else {
                    val gateT0 = SystemClock.elapsedRealtime()
                    val outcome = PrefetchedSelectionGate.resolve(
                        prefetched = com.nuvio.tv.core.stream.StreamPrefetchCache.selectionFor(
                            type = contentType,
                            videoId = streamRequestId,
                            season = season,
                            episode = episode
                        ),
                        snapshot = SelectionSnapshot(
                            inputs = autoPlayInputs,
                            installedAddonOrder = installedAddonOrder
                        ),
                        streams = allStreams
                    )
                    when (outcome) {
                        is SelectionOutcome.Hit -> {
                            android.util.Log.i(
                                TAG,
                                "R2_SELECT src=prefetch " +
                                    "ms=${SystemClock.elapsedRealtime() - gateT0}"
                            )
                            outcome.stream
                        }
                        is SelectionOutcome.Live -> {
                            val live = AutoPlaySelection.select(
                                streams = allStreams,
                                inputs = autoPlayInputs
                            )
                            android.util.Log.i(
                                TAG,
                                "R2_SELECT src=live reason=${outcome.reason} " +
                                    "ms=${SystemClock.elapsedRealtime() - gateT0}"
                            )
                            live
                        }
                    }
                }
                // APPLY_SPLIT: the 615-874 ms residual (24 Jul 2026) sits between the
                // stream list arriving and the sources_ready mark below. Everything
                // timed here runs on Dispatchers.Main.immediate via viewModelScope,
                // and applySuccess runs twice -- once at isAllLoaded=false and again
                // on flow completion -- so order and badge work is paid twice while
                // the ranking is paid once. selectAutoPlayStream fans out to
                // StreamQualityRank.rank, which derives facts from every stream name.
                // Logging only.
                val applySelectMs = SystemClock.elapsedRealtime() - applyT0 - applyOrderMs - applyBadgeMs
                android.util.Log.i(
                    TAG,
                    "APPLY_SPLIT allLoaded=$isAllLoaded groups=${mergedAddonStreams.size} " +
                        "streams=${allStreams.size} order=${applyOrderMs}ms badge=${applyBadgeMs}ms " +
                        "select=${applySelectMs}ms ranked=$shouldAutoSelect " +
                        "total=${SystemClock.elapsedRealtime() - applyT0}ms"
                )
                if (shouldAutoSelect && !ttffSourcesReadyMarked) {
                    ttffSourcesReadyMarked = true
                    TtffTrace.mark("sources_ready")
                }
                if (selectedAutoPlayStream != null) {
                    TtffTrace.mark("auto_selected")
                    resolvedAutoPlayTarget = true
                }

                updateUiStateIfChanged {
                    it.copy(
                        isLoading = false,
                        addonStreams = mergedAddonStreams,
                        allStreams = allStreams,
                        availableAddons = availableAddons,
                        sourceChips = mergeSourceChipStatuses(
                            existing = _uiState.value.sourceChips,
                            succeededNames = mergedAddonStreams.map { it.addonName }
                        ),
                        // Preserve an already-resolved stream: the post-collect
                        // "isAllLoaded=true" pass re-runs the selector with
                        // shouldAutoSelect=false once a target is resolved, and
                        // would otherwise clobber the real pick with null before
                        // Compose observes it.
                        autoPlayStream = selectedAutoPlayStream ?: it.autoPlayStream,
                        error = null,
                        showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession || it.autoPlayPlaybackInfo != null) {
                            true
                        } else {
                            false
                        }
                    ).withListView(hidden)
                }
                scheduleStreamBadgePresentation(mergedAddonStreams)
            }

            if (shouldAttemptEmbeddedMetaStreamLookup(contentId, contentType, streamRequestId)) {
                getEmbeddedStreamsFromMeta()?.let { embeddedAddonStreams ->
                    Log.d(
                        TAG,
                        "Using embedded video streams for videoId=$videoId count=${embeddedAddonStreams.streams.size}"
                    )
                    applySuccess(listOf(embeddedAddonStreams), isAllLoaded = true)
                    updateSourceChipsForEmbedded(embeddedAddonStreams.addonName)
                    if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                        directAutoPlayFlowEnabledForSession = false
                        updateUiStateIfChanged {
                            it.copy(
                                isDirectAutoPlayFlow = false,
                                showDirectAutoPlayOverlay = false,
                                directAutoPlayMessage = null
                            )
                        }
                    }
                    return@launch
                }
            }

            val alreadySucceededNames = if (forceRefresh) {
                emptySet()
            } else {
                _uiState.value.addonStreams.mapTo(linkedSetOf()) { it.addonName }
            }
            updateSourceChipsForFetchStart(
                installedAddons = installedAddons,
                alreadySucceededNames = alreadySucceededNames
            )

            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            val isUnlimitedTimeout = playerSettings.streamAutoPlayTimeoutSeconds == PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED

            val streamLoadInner = launch {
                // S4a: a completed details-page prefetch is emitted here as one
                // Success followed by completion -- exactly the shape of a very
                // fast scrape -- so the collect below and the post-collect
                // isAllLoaded=true pass behave identically. A miss returns the
                // live repository flow unchanged.
                com.nuvio.tv.core.stream.StreamPrefetchCache.streamsFor(
                    repository = streamRepository,
                    type = contentType,
                    videoId = streamRequestId,
                    season = season,
                    episode = episode,
                    forceRefresh = forceRefresh,
                    title = title
                )
                    // Main review F2: each emission carries the FULL accumulated
                    // list and applySuccess reprocesses all of it (ordering,
                    // badge merge, chip merge) on Main. conflate() drops
                    // intermediate emissions that arrive while a presentation
                    // pass is still running — zero added latency for a lone
                    // addon, and burst arrivals from many addons collapse to
                    // one pass over the latest accumulation. The final
                    // emission is always delivered.
                    .conflate()
                    .collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            applySuccess(result.data, isAllLoaded = false)

                            if (autoSelectTriggered || resolvedAutoPlayTarget || autoPlayHandledForSession) {
                                // Already resolved — nothing more to do.
                            } else if (timeoutElapsed) {
                                // Timeout elapsed: run full auto-select (binge
                                // group preferred, then fallback to mode).
                                applySuccess(result.data, isAllLoaded = true)
                                if (resolvedAutoPlayTarget) {
                                    autoSelectTriggered = true
                                } else if (directAutoPlayFlowEnabledForSession && !isUnlimitedTimeout) {
                                    // Bounded/instant timeout: no match found,
                                    // show the picker.
                                    autoPlayHandledForSession = true
                                    directAutoPlayFlowEnabledForSession = false
                                    updateUiStateIfChanged {
                                        it.copy(
                                            isDirectAutoPlayFlow = false,
                                            showDirectAutoPlayOverlay = false,
                                            directAutoPlayMessage = null
                                        )
                                    }
                                }
                            } else if (directFlowActive && persistedBingeGroup != null) {
                                // Before timeout: eagerly check binge group only
                                // (no fallback to FIRST_STREAM/REGEX yet). If a
                                // match is found we can start playback immediately
                                // without waiting for the full timeout.
                                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(
                                    result.data, installedAddonOrder
                                )
                                val allStreams = orderedStreams.flatMap { it.streams }
                                // The set applySuccess just derived over this same data (limitKey
                                // ignores its badge merge): the list and this select share it.
                                val earlyMatch = AutoPlaySelection.select(
                                    streams = allStreams,
                                    inputs = inputsFor(_uiState.value.listView.hiddenVerdicts.keys),
                                    bingeGroupOnly = true
                                )
                                if (earlyMatch != null) {
                                    resolvedAutoPlayTarget = true
                                    autoSelectTriggered = true
                                    updateUiStateIfChanged {
                                        it.copy(
                                            autoPlayStream = earlyMatch,
                                            showDirectAutoPlayOverlay = true
                                        )
                                    }
                                }
                            }
                        }
                        is NetworkResult.Error -> {
                            if (directAutoPlayFlowEnabledForSession) {
                                directAutoPlayFlowEnabledForSession = false
                            }
                            updateUiStateIfChanged {
                                it.copy(
                                    isLoading = false,
                                    error = result.message,
                                    isDirectAutoPlayFlow = false,
                                    showDirectAutoPlayOverlay = false,
                                    directAutoPlayMessage = null
                                )
                            }
                        }
                        NetworkResult.Loading -> {
                            updateUiStateIfChanged {
                                it.copy(
                                    isLoading = forceRefresh || it.allStreams.isEmpty(),
                                    showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                                        true
                                    } else {
                                        it.showDirectAutoPlayOverlay
                                    }
                                )
                            }
                        }
                    }
                }
                // All addons finished — run auto-select if not yet triggered
                if (!autoSelectTriggered) {
                    autoSelectTriggered = true
                    lastSuccessData?.let { applySuccess(it, isAllLoaded = true) }
                }
                // Shape 2: if this collect's pool was cut short by the prefetch
                // completion cap (capHit) and chips are still loading, the missing
                // sources are alive in the session, not dead. Fill the manual list
                // from the session in the background WITHOUT gating auto-select
                // (already resolved above), then mark ERROR only for genuinely dead
                // addons. Non-capHit / fully-loaded collects mark immediately, as
                // before. Detached on viewModelScope so it never defers
                // streamLoadCompleted; cancelled at the next loadStreams and by
                // cancelStreamsLoad (which then marks the load incomplete).
                val hasLoadingChips = _uiState.value.sourceChips.any {
                    it.status == SourceChipStatus.LOADING
                }
                if (hasLoadingChips &&
                    com.nuvio.tv.core.stream.StreamPrefetchCache.capHitFor(
                        contentType, streamRequestId, season, episode
                    )
                ) {
                    sessionFillJob?.cancel()
                    sessionFillJob = viewModelScope.launch {
                        streamRepository.getStreamsFromAllAddons(
                            contentType, streamRequestId, season, episode, title = title
                        ).collect { fillResult ->
                            if (fillResult is NetworkResult.Success) {
                                applySuccess(fillResult.data, isAllLoaded = false)
                            }
                        }
                        markRemainingSourceChipsAsError()
                    }
                } else {
                    markRemainingSourceChipsAsError()
                }
                if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                    directAutoPlayFlowEnabledForSession = false
                    // All addons finished with no instant stream to auto-play: drop the loader and
                    // reveal the manual picker now instead of holding until the hard timeout. Clear
                    // isLoading too, so an empty result set doesn't leave the screen stuck loading.
                    updateUiStateIfChanged {
                        it.copy(
                            isLoading = false,
                            isDirectAutoPlayFlow = false,
                            showDirectAutoPlayOverlay = false,
                            directAutoPlayMessage = null
                        )
                    }
                    externalPlaybackTracker.releaseAutoNextOverlay(forceRelease = true)
                }
            }

            // Timeout semantics:
            // - 0 (instant): timeoutElapsed immediately, first addon response
            //   triggers auto-select; if no match -> dismiss overlay at once.
            // - 1-30s (bounded): wait the configured delay, then auto-select
            //   from whatever streams arrived; if no match -> dismiss overlay.
            // - unlimited: check each addon response as it arrives; if a match
            //   is found use it immediately; otherwise keep waiting until all
            //   addons finish or the hard timeout (60s) forces a fallback.
            val timeoutMs = playerSettings.streamAutoPlayTimeoutSeconds * 1_000L
            if (PlayerSettings.isBoundedTimeout(playerSettings.streamAutoPlayTimeoutSeconds)) {
                delay(timeoutMs)
            }
            timeoutElapsed = true
            if (!autoSelectTriggered && lastSuccessData != null) {
                applySuccess(lastSuccessData, isAllLoaded = true)
                if (resolvedAutoPlayTarget) {
                    autoSelectTriggered = true
                }
            }

            // For instant/bounded timeout: if streams arrived but no auto-play
            // target was resolved, tear down the overlay immediately so the
            // user sees the stream picker.
            // For unlimited: keep the overlay — we continue checking as more
            // addons respond until the hard timeout below.
            if (directFlowActive && !resolvedAutoPlayTarget && lastSuccessData != null && !isUnlimitedTimeout) {
                autoPlayHandledForSession = true
                directAutoPlayFlowEnabledForSession = false
                updateUiStateIfChanged {
                    it.copy(
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = false,
                        directAutoPlayMessage = null
                    )
                }
            }

            // Hard wall-clock fallback: if the upstream stream flow never terminates
            // (e.g. a stalled source keeps the stream channel open), the direct
            // autoplay overlay would otherwise stay visible indefinitely. Force a
            // teardown so the user lands in the manual stream list with whatever
            // results have already arrived.
            if (directFlowActive) {
                delay(DIRECT_AUTOPLAY_HARD_TIMEOUT_MS)
                // A resolved target that never actually started playback (e.g. a dead
                // Reuse Last Link) leaves the loader up, since every earlier teardown is
                // gated on !resolvedAutoPlayTarget. Only stuck if still foregrounded: a
                // stopped screen (player on top, app backgrounded) is not a stuck loader.
                val hasResolvedOverlay = resolvedAutoPlayTarget &&
                    _uiState.value.showDirectAutoPlayOverlay
                if (hasResolvedOverlay) {
                    DirectAutoPlayWatchdogPolicy.awaitForeground(hostInForeground)
                }
                val phantomStuck = DirectAutoPlayWatchdogPolicy.shouldTearDownResolvedTarget(
                    resolvedAutoPlayTarget = resolvedAutoPlayTarget,
                    hostInForeground = hostInForeground.value,
                    showDirectAutoPlayOverlay = _uiState.value.showDirectAutoPlayOverlay
                )
                if ((directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) || phantomStuck) {
                    Log.w(TAG, "Direct autoplay hard timeout reached; falling back to manual selection")
                    lastSuccessData?.let {
                        if (!autoSelectTriggered) {
                            autoSelectTriggered = true
                            applySuccess(it, isAllLoaded = true)
                        }
                    }
                    if (!resolvedAutoPlayTarget || phantomStuck) {
                        directAutoPlayFlowEnabledForSession = false
                        updateUiStateIfChanged {
                            it.copy(
                                isLoading = false,
                                isDirectAutoPlayFlow = false,
                                showDirectAutoPlayOverlay = false,
                                autoPlayPlaybackInfo = null,
                                directAutoPlayMessage = null
                            )
                        }
                        externalPlaybackTracker.releaseAutoNextOverlay(forceRelease = true)
                        streamLoadInner.cancel()
                        markRemainingSourceChipsAsError()
                    }
                }
            }
            // Wait for the inner collection to actually finish before
            // marking the load as completed.  Without this join the outer
            // coroutine races past the timeout/hard-timeout blocks and
            // sets streamLoadCompleted = true while addons are still
            // being fetched — which makes OnResume think there is nothing
            // left to do when the user returns from the player.
            streamLoadInner.join()
            // Only mark completed if the coroutine was NOT cancelled.
            // ensureActive() throws CancellationException if the scope
            // was cancelled (e.g. user selected a stream and navigated
            // to the player), preventing a false "completed" flag that
            // would block resumption on return.
            ensureActive()
            streamLoadCompleted = true
        }
    }

    private fun updateSourceChipsForFetchStart(
        installedAddons: List<com.nuvio.tv.domain.model.Addon>,
        alreadySucceededNames: Set<String> = emptySet()
    ) {
        val addonNames = installedAddons
            .filter { it.supportsStreamResourceForChip(contentType) }
            .map { it.displayName }

        val orderedNames = addonNames.distinct()
        if (orderedNames.isEmpty()) {
            updateUiStateIfChanged { it.withSourceChips(emptyList()) }
            return
        }

        updateUiStateIfChanged { state ->
            state.withSourceChips(
                orderedNames.map { name ->
                    if (name in alreadySucceededNames) {
                        SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
                    } else {
                        SourceChipItem(name = name, status = SourceChipStatus.LOADING)
                    }
                }
            )
        }
    }

    private fun updateSourceChipsForEmbedded(name: String) {
        updateUiStateIfChanged { state ->
            val chips = if (state.sourceChips.any { it.name == name }) {
                state.sourceChips.map { chip ->
                    if (chip.name == name) chip.copy(status = SourceChipStatus.SUCCESS) else chip
                }
            } else {
                listOf(SourceChipItem(name = name, status = SourceChipStatus.SUCCESS))
            }
            state.withSourceChips(chips)
        }
    }

    private fun mergeSourceChipStatuses(
        existing: List<SourceChipItem>,
        succeededNames: List<String>
    ): List<SourceChipItem> {
        if (succeededNames.isEmpty()) return existing
        if (existing.isEmpty()) {
            return succeededNames.distinct().map { name ->
                SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }

        val successSet = succeededNames.toSet()
        val updated = existing.map { chip ->
            if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
        }.toMutableList()

        val knownNames = updated.map { it.name }.toSet()
        succeededNames.forEach { name ->
            if (name !in knownNames) {
                updated += SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }
        return updated
    }

    private fun markRemainingSourceChipsAsError() {
        var markedAnyError = false
        updateUiStateIfChanged { state ->
            val hasPending = state.sourceChips.any { it.status == SourceChipStatus.LOADING }
            if (!hasPending) return@updateUiStateIfChanged state
            markedAnyError = true
            state.copy(
                sourceChips = state.sourceChips.map { chip ->
                    if (chip.status == SourceChipStatus.LOADING) {
                        chip.copy(status = SourceChipStatus.ERROR)
                    } else {
                        chip
                    }
                }
            )
        }
        if (markedAnyError) {
            scheduleErrorChipRemoval()
        }
    }

    private fun scheduleErrorChipRemoval() {
        sourceChipErrorDismissJob?.cancel()
        sourceChipErrorDismissJob = viewModelScope.launch {
            delay(1600L)
            updateUiStateIfChanged { state ->
                val remaining = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
                if (remaining.size == state.sourceChips.size) state else state.withSourceChips(remaining)
            }
        }
    }

    private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean =
        streamRequestType(type, streamRequestId) != null

    private suspend fun getEmbeddedStreamsFromMeta(): AddonStreams? {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return null
        val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
            .first { it !is NetworkResult.Loading }
        val meta = (result as? NetworkResult.Success)?.data ?: return null
        // Match the request id: a meta-level play with a defaultVideoId names a
        // video other than videoId (= meta id).
        val video = meta.videos.firstOrNull { it.id == streamRequestId } ?: return null
        if (video.streams.isEmpty()) return null

        val streams = video.streams.map { stream ->
            stream.copy(
                name = stream.name ?: stream.title ?: stream.description ?: embeddedStreamFallbackName,
                addonName = embeddedStreamGroupName,
                addonLogo = null
            )
        }

        return AddonStreams(
            addonName = embeddedStreamGroupName,
            addonLogo = null,
            streams = streams
        )
    }

    /** One meta read per entry: the plot and IMDb rating always, the route's missing art and facts when absent. */
    private fun loadMetaDetails() {
        val requiresMetadataLookup = genres.isNullOrBlank() || year.isNullOrBlank() || runtime == null
        val metaId = contentId ?: videoId.substringBefore(":")
        if (metaId.isBlank() || contentType.isBlank()) return

        viewModelScope.launch {
            val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
                .first { it !is NetworkResult.Loading }

            if (result !is NetworkResult.Success) return@launch

            val meta = result.data
            // The route never carries the plot or the rating: written on every entry.
            val description = streamDescription(meta, season, episode)
            if (!requiresMetadataLookup) {
                updateUiStateIfChanged { it.copy(description = description, imdbRating = meta.imdbRating) }
                return@launch
            }
            val metaGenres = meta.genres.takeIf { it.isNotEmpty() }
                ?.joinToString(" • ") { localizedGenreLabel(context, it) }
            val metaYear = extractReleaseYear(meta.releaseInfo)
            val metaRuntime = streamRuntimeMinutes(meta, season, episode)

            updateUiStateIfChanged { state ->
                val next = state.copy(
                    poster = state.poster ?: meta.poster,
                    backdrop = state.backdrop ?: meta.backdropUrl,
                    logo = state.logo ?: meta.logo,
                    genres = state.genres?.takeIf { it.isNotBlank() } ?: metaGenres,
                    year = state.year?.takeIf { it.isNotBlank() } ?: metaYear,
                    runtime = state.runtime ?: metaRuntime,
                    description = description,
                    imdbRating = meta.imdbRating
                )
                // A runtime landing after the streams re-derives the list (Spec 51 M3).
                if (next.runtime == state.runtime) next
                else next.withListView(hiddenVerdictsFor(next.allStreams, next.runtime))
            }
        }
    }

    /** A user's pick; the hidden set stands (Spec 51 M7). */
    private fun selectList(selection: StreamListSelection) {
        updateUiStateIfChanged { state ->
            if (state.listSelection == selection) state
            else state.copy(listSelection = selection).withListView(state.listView.hiddenVerdicts)
        }
    }

    fun onInternalPlayerLaunching() {
        updateUiStateIfChanged {
            it.copy(showDirectAutoPlayOverlay = false, directAutoPlayMessage = null)
        }
    }

    /** True when this screen was reached by an auto-next that the user has since aborted, so its
     *  pending auto-launch should be skipped (fall back to the source list). */
    fun isAutoNextContinuationAborted(): Boolean = externalPlaybackTracker.isAutoNextContinuationAborted()

    fun consumeAbortedAutoNextContinuation() = externalPlaybackTracker.consumeAbortedAutoNextContinuation()

    /** Release the MainActivity auto-next loader once this Stream screen has settled. Hides the
     *  overlay only; it must not abort the chain, or a fast settle would suppress the next advance. */
    fun dismissExternalAutoNextOverlay() {
        externalPlaybackTracker.releaseAutoNextOverlay(forceRelease = true)
    }

    // Foreground gate for the stuck-loader timeout; set false on ON_STOP (player on top, external
    // stream in another app, app backgrounded). True at init since the screen starts visible.
    private val hostInForeground = MutableStateFlow(true)

    fun onHostStopped() {
        hostInForeground.value = false
    }

    /**
     * Fail-open HEAD check for a remembered stream link (2026-08-30): only a positive
     * "gone" answer (400/401/403/404/410) invalidates the cached link; network errors
     * and 429 keep it, so a flaky check or a host rate limit never blocks playback.
     * 405 (HEAD unsupported) counts as alive — a GET may still work. Sends the link's
     * cached proxy headers, so a link that needs them isn't misread as gone (401/403).
     */
    private suspend fun isHttpLinkAlive(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.instanceFollowRedirects = true
            try {
                when (conn.responseCode) {
                    400, 401, 403, 404, 410 -> false
                    else -> true
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(true)
    }

    /**
     * Press-time entry point: builds the playback info for the selected stream.
     * Nothing is resolved (addon streams arrive playable); the TTFF marks keep
     * their historical names so traces stay comparable.
     */
    fun playbackInfoFor(stream: Stream): StreamPlaybackInfo {
        TtffTrace.mark("resolve_start")
        TtffTrace.mark("resolve_skip_direct")
        cancelStreamsLoad()
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.getEffectiveInfoHash(),
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),  // Use explicit contentId or extract from videoId
            contentType = contentType,
            contentName = contentName ?: title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            // Structured size first, else the size in the stream text (Spec 33: the per-film
            // network rule needs size / runtime; no addon sends a bitrate).
            videoSize = StreamTextSizeParser.effectiveSizeBytes(stream),
            // Already resolved by loadMetadataIfNeeded, including the per-episode
            // case; the route value is only a seed for it.
            runtimeMinutes = _uiState.value.runtime,
            addonName = stream.addonName,
            addonLogo = stream.addonLogo,
            streamDescription = stream.description,
            fileIdx = stream.getEffectiveFileIdx(),
            sources = stream.sources,
            contentLanguage = contentLanguage,
            subtitles = stream.subtitles,
            launchStartedAtMs = TtffTrace.t0ElapsedMsOrNull()
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank() && !playbackInfo.isExternal) {
            pendingCacheSaveJob = viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers,
                    filename = playbackInfo.filename,
                    videoHash = playbackInfo.videoHash,
                    videoSize = playbackInfo.videoSize,
                    bingeGroup = playbackInfo.bingeGroup,
                    contentLanguage = contentLanguage,
                    year = year
                )
            }
        }
        return playbackInfo
    }

    suspend fun awaitStreamLinkCacheSave() {
        pendingCacheSaveJob?.join()
    }

    /**
     * P10 "Last played", read on every resume: entry, and Back from a player, whose own saves
     * (torrents, source switches) the cache holds too. Any age counts: the marker is not the
     * reuse window.
     */
    private fun refreshLastPlayed() {
        viewModelScope.launch {
            pendingCacheSaveJob?.join()
            val cached = streamLinkCacheDataStore.getValid(streamCacheKey, maxAgeMs = Long.MAX_VALUE)
            updateUiStateIfChanged { it.copy(lastPlayed = cached?.toLastPlayed()) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        sourceChipErrorDismissJob?.cancel()
    }
}

/**
 * X17: the id addon stream/subtitle requests use. A meta-level play whose meta names a
 * `behaviorHints.defaultVideoId` carries it as the route's streamRequestId; absent or
 * blank, streams are requested for [videoId] itself. [videoId] stays the playback
 * identity (player, watch progress, link cache: see [streamLinkCacheKey]).
 */
internal fun resolveStreamRequestId(routeStreamRequestId: String?, videoId: String): String =
    routeStreamRequestId?.takeIf { it.isNotBlank() } ?: videoId

/** Reuse-last-link cache key (persisted): keyed by the playback videoId, never the request id. */
internal fun streamLinkCacheKey(contentType: String, videoId: String): String =
    "${contentType.lowercase()}|$videoId"

/**
 * Whether to look for streams embedded in the meta's own videos before scraping: always
 * for type "other", otherwise when the requested video is not an episode id of the meta
 * (e.g. a defaultVideoId or channel video). Compares [streamRequestId] because that is the
 * id the embedded lookup matches the meta's videos against.
 */
internal fun shouldAttemptEmbeddedMetaStreamLookup(
    contentId: String?,
    contentType: String,
    streamRequestId: String
): Boolean {
    val metaId = contentId?.takeIf { it.isNotBlank() } ?: return false
    if (contentType.isBlank()) return false
    if (contentType.equals("other", ignoreCase = true)) return true
    return !metaId.equals(streamRequestId.substringBefore(":"), ignoreCase = true)
}

data class StreamPlaybackInfo(
    val url: String?,
    val title: String,
    val streamName: String,
    val year: String?,
    val isExternal: Boolean,
    val isTorrent: Boolean,
    val infoHash: String?,
    val ytId: String?,
    val headers: Map<String, String>?,
    // Watch progress metadata
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val bingeGroup: String?,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val runtimeMinutes: Int? = null,
    val addonName: String? = null,
    val addonLogo: String? = null,
    val streamDescription: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val contentLanguage: String? = null,
    /** The selected stream's own subtitles; not persisted, so cached-link replays carry none. */
    val subtitles: List<com.nuvio.tv.domain.model.Subtitle> = emptyList(),
    // TTFF T.1: SystemClock.elapsedRealtime() at the press that started this
    // launch (title press for auto-select, stream selection for manual).
    val launchStartedAtMs: Long? = null
)

/**
 * [chips] as the source chips, re-derived (Spec 51 m3): a picked addon whose chip left the row
 * (failed and removed, uninstalled before a reload) is dropped back to All, so a later reload that
 * brings its chip back does not reopen an empty view of it. Every writer that can drop a name
 * goes through here; applySuccess only adds names and re-derives itself.
 */
private fun StreamScreenUiState.withSourceChips(chips: List<SourceChipItem>): StreamScreenUiState =
    copy(sourceChips = chips, listSelection = listSelection.keptIn(sourceChipAddonNames(availableAddons, chips)))
        .withListView(listView.hiddenVerdicts)

/** [allStreams] derived under [listSelection], [hidden] and the chip row (Spec 51 P4). */
private fun StreamScreenUiState.withListView(hidden: Map<String, HiddenStream>): StreamScreenUiState =
    copy(listView = deriveStreamList(allStreams, listSelection, hidden, sourceChipAddonNames(availableAddons, sourceChips)))

/**
 * A Stream list's runtime from its meta: the episode's for an episode, else the title's. The
 * Stream screen's own lookup and the Details prefetch's (Spec 51 P5), so both rank alike.
 */
internal fun streamRuntimeMinutes(meta: Meta, season: Int?, episode: Int?): Int? =
    if (season != null && episode != null) meta.episodeVideo(season, episode)?.runtime
    else parseRuntimeMinutes(meta.runtime)?.takeIf { it > 0 }

/** The meta's video for [season] / [episode]; null for a movie or an episode the meta lacks. */
internal fun Meta.episodeVideo(season: Int?, episode: Int?): Video? =
    if (season == null || episode == null) null
    else videos.firstOrNull { it.season == season && it.episode == episode }

/** The plot under the Stream screen title: the episode's overview, else the title's description. */
internal fun streamDescription(meta: Meta, season: Int?, episode: Int?): String? =
    meta.episodeVideo(season, episode)?.overview?.takeIf { it.isNotBlank() }
        ?: meta.description?.takeIf { it.isNotBlank() }
