package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.toInProgressItem
import com.nuvio.tv.core.util.StartupLatencyTrace

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.canMarkWatched
import com.nuvio.tv.ui.components.toCompletedProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext internal val appContext: Context,
    internal val addonRepository: AddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val collectionsDataStore: CollectionsDataStore,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val continueWatchingSettings: ContinueWatchingSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val trailerService: TrailerService,
    internal val trailerSettingsDataStore: com.nuvio.tv.data.local.TrailerSettingsDataStore,
    internal val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    internal val streamRepository: StreamRepository,
    internal val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    internal val homeRefreshSignal: com.nuvio.tv.core.util.HomeRefreshSignal,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    activeRatingCap: com.nuvio.tv.core.profile.ActiveRatingCap
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        internal const val STARTUP_GRACE_PERIOD_MS = 1_500L
        internal const val CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS = 1_000L
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 5
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        private const val MAX_ENRICHMENT_CACHE_SIZE = 64
        private const val MAX_PREFETCH_CACHE_SIZE = 64
        private const val MAX_CW_CACHE_SIZE = 64

        private fun <K, V> createLruMap(maxSize: Int): MutableMap<K, V> {
            val lru = object : LinkedHashMap<K, V>(maxSize + 4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                    size > maxSize
            }
            return java.util.Collections.synchronizedMap(lru)
        }
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    // Spec 64 K1c: the one exit every Continue Watching publish path reaches, so the cap is
    // applied here; internal code keeps reading and writing the unfiltered _uiState.
    val uiState: StateFlow<HomeUiState> = ContinueWatchingRatingGate(
        scope = viewModelScope,
        readCap = activeRatingCap::current,
        lookUpRating = ContinueWatchingRatingGate.metaRepositoryLookup(metaRepository)
    ).gate(_uiState)

    internal val _modernHomePresentation = MutableStateFlow(ModernHomePresentationState())
    val modernHomePresentation: StateFlow<ModernHomePresentationState> = _modernHomePresentation.asStateFlow()

    /** True once the CW pipeline has completed its first emission (items or empty). */
    internal val _initialCwResolved = MutableStateFlow(false)
    val initialCwResolved: StateFlow<Boolean> = _initialCwResolved.asStateFlow()

    // S4a-3: Continue Watching navigates straight to Screen.Stream
    // (NuvioNavHost.kt:205 -> createContinueWatchingRoute), bypassing the
    // details page entirely, so MetaDetailsViewModel never exists and S4a's
    // prefetch cannot fire on this path -- structurally, not intermittently.
    //
    // Measured 24 Jul 2026 (Xiaomi, nt21, auto-play Best quality): a
    // details-page play reached a full stream list in 346 ms, while three
    // consecutive Continue Watching plays of the SAME title took 1,254-2,351
    // ms of live scrape. Those were the later, upstream-warm runs, so the
    // confound runs against the conclusion rather than for it.
    //
    // The key is derived from the same ContinueWatchingItem the click routes
    // with, so unlike S4b there is no prediction side that can diverge from
    // what actually plays. Top item only: for a binge that is the next
    // episode. Debounced because the CW list settles in stages while
    // enrichment and Supabase sync land; collectLatest cancels a pending delay
    // when the target changes, so a settling list scrapes once, not N times.
    private val CW_STREAM_PREFETCH_DEBOUNCE_MS = 500L


    private data class CwPrefetchTarget(
        val type: String,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        /** R2: needed to read the binge-group cache the stream screen reads. */
        val contentId: String?,
        /** Spec 51 P5: the runtime the Stream route carries (In Progress), for the limit. */
        val runtimeMinutes: Int?
    )

    private fun cwPrefetchTargetOf(item: ContinueWatchingItem?): CwPrefetchTarget? = when (item) {
        is ContinueWatchingItem.InProgress -> CwPrefetchTarget(
            item.progress.contentType,
            item.progress.videoId,
            item.progress.season,
            item.progress.episode,
            item.progress.contentId,
            PlaceholderStreamPolicy.runtimeMinutesFromProgress(item.progress.duration)
        )
        is ContinueWatchingItem.NextUp -> CwPrefetchTarget(
            item.info.contentType,
            item.info.videoId,
            item.info.season,
            item.info.episode,
            item.info.contentId,
            // NextUpInfo has no runtime; the Next Up route carries none either.
            runtimeMinutes = null
        )
        null -> null
    }

    // T27 (upstream-sync P23): this ViewModel keeps running under Details and the player, where a
    // progress save can make another title the CW front; that must not start a scrape + prewarm
    // GET mid-playback. New prefetches start only while Home is resumed (HomeScreen sets this).
    private val homeOnTop = MutableStateFlow(true)

    fun setHomeOnTop(onTop: Boolean) {
        homeOnTop.value = onTop
    }

    private val cwPrefetchObserver: Job = viewModelScope.launch {
        collectPrefetchTargetsWhileOnTop(
            // Gated uiState (Spec 64 K1c): never warm a title the cap hides.
            targets = uiState.map { state -> cwPrefetchTargetOf(state.continueWatchingItems.firstOrNull()) },
            onTop = homeOnTop,
            debounceMs = CW_STREAM_PREFETCH_DEBOUNCE_MS
        ) { target -> prefetchCw(target, source = "cw", prewarm = true) }
    }

    /**
     * A background warm of [target]'s streams; rank (and, with [prewarm], the winner's
     * connection warm) run after the scrape.
     */
    private suspend fun prefetchCw(target: CwPrefetchTarget, source: String, prewarm: Boolean) {
        // P2: same completion cap as the details path.
        val capMs = playerSettingsDataStore.playerSettings.first().eagerReadyCapMs()
        com.nuvio.tv.core.stream.StreamPrefetchCache.prefetch(
            repository = streamRepository,
            type = target.type,
            videoId = target.videoId,
            season = target.season,
            episode = target.episode,
            source = source,
            background = true,
            capMs = capMs,
            rank = { groups ->
                prefetchSelectionSupplier.rankAndPrewarm(
                    groups = groups,
                    contentId = target.contentId,
                    runtimeMinutes = target.runtimeMinutes,
                    episode = target.season != null && target.episode != null,
                    film = com.nuvio.tv.core.stream.ContentIdentity.isFilmType(target.type),
                    prewarm = prewarm
                )
            }
        )
    }

    // S4a-3b: any Continue Watching card, on focus-dwell. The settle-time
    // observer above covers only the FRONT item; every other card played
    // from cold (full scrape and rank both after the press). Focus
    // is not intent, so this is debounced harder than the settle path --
    // 600 ms, matching the details page's episode-focus debounce -- and
    // collectLatest cancels a pending delay when focus moves on, so
    // scanning the row prefetches nothing until a card actually settles.
    // It runs as a background warm: it may never cancel a ui-owned
    // (details hero) scrape, and StreamPrefetchCache stays single-flight,
    // so successive settled cards replace each other rather than fanning
    // out. Rank stays dwell-gated by construction (it only runs after this
    // debounce AND the scrape complete); no prewarm GET here (T30: focus is
    // not consent; the press warms the link). Unaired Next Up
    // cards are skipped for the same
    // reason the binge lookahead gates on hasAired: nothing to scrape yet.
    private val CW_FOCUS_PREFETCH_DEBOUNCE_MS = 600L

    private val cwFocusPrefetchRequests =
        MutableSharedFlow<CwPrefetchTarget>(extraBufferCapacity = 16)

    fun onContinueWatchingItemFocused(index: Int) {
        // [index] is a position in the gated row the UI shows (Spec 64 K1c).
        val item = uiState.value.continueWatchingItems.getOrNull(index) ?: return
        if (item is ContinueWatchingItem.NextUp && !item.info.hasAired) return
        val target = cwPrefetchTargetOf(item) ?: return
        cwFocusPrefetchRequests.tryEmit(target)
    }

    private val cwFocusPrefetchObserver: Job = viewModelScope.launch {
        cwFocusPrefetchRequests
            .distinctUntilChanged()
            .collectLatest { target ->
                delay(CW_FOCUS_PREFETCH_DEBOUNCE_MS)
                // T27: a card pressed inside the dwell has already left Home.
                if (!homeOnTop.value) return@collectLatest
                prefetchCw(target, source = "cw_focus", prewarm = false)
            }
    }
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _scrollToTopTrigger = MutableStateFlow(0)
    val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    internal val _currentLocaleTag = MutableStateFlow(LocaleCache.localeTag)

    fun notifyLocaleChanged() {
        val tag = LocaleCache.localeTag
        if (_currentLocaleTag.value != tag) {
            _currentLocaleTag.value = tag
        }
    }

    fun requestScrollToTop() {
        clearFocusState()
        _scrollToTopTrigger.value++
    }

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()

    internal fun addEnrichedPreview(id: String, preview: MetaPreview) {
        _enrichedPreviews.update { current ->
            val updated = current + (id to preview)
            if (updated.size <= MAX_ENRICHMENT_CACHE_SIZE) updated
            else LinkedHashMap(updated).apply { while (size > MAX_ENRICHMENT_CACHE_SIZE) remove(keys.first()) }
        }
    }

    /** Items for which enrichment was attempted but produced no enriched data. */
    internal val _failedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    val failedEnrichmentIds: StateFlow<Set<String>> = _failedEnrichmentIds.asStateFlow()

    internal val catalogStateLock = Any()
    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogItemKeyIndex = mutableMapOf<String, MutableSet<String>>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var collectionsCache: List<Collection> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var followAddonsOrderEnabled: Boolean = false
    internal var customCatalogTitles: Map<String, String> = emptyMap()
    internal var catalogUpdateJob: Job? = null
    private var catalogRowsDirty = false
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal val pendingCatalogLoads = PendingCatalogLoads()
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    // The title [trailerPreviewJob] looks up: meaningful only while that job is active (not
    // cleared on completion). Main-only.
    internal var trailerPreviewPendingItemId: String? = null
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewJob: Job? = null
    internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    internal val prefetchedExternalMetaIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val backgroundMetaPrefetchedIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val externalMetaPrefetchInFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwMetaCache: MutableMap<String, CwMetaSummary?> = createLruMap(MAX_CW_CACHE_SIZE)
    internal val cwMetaNegativeCacheTimestamps: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** cwMetaCache key → wall-clock ms when that episode list is fetched again (Spec 36 S11). */
    internal val cwMetaRefreshAtMs: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** cwMetaCache keys whose due refresh failed or was deferred by an open player. */
    internal val cwMetaStaleKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Ultra-light cache for badge evaluation: contentId → set of aired (season, episode) pairs. */
    internal val cwBadgeEpisodeCache: MutableMap<String, Set<Pair<Int, Int>>?> = createLruMap(MAX_CW_CACHE_SIZE)
    /** Per-series check-again time (epochMs): next known release, else a fallback interval. */
    internal val cwBadgeNextSeasonMs: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** Snapshot of watchedShowEpisodes keys from the last badge evaluation cycle. */
    @Volatile
    internal var cwLastBadgeEpisodeKeys: Set<String> = emptySet()
    internal val cwNextUpResolutionCache = Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val cwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val discoveredOlderNextUpItems = Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val cwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwProcessedOlderSeedContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /**
     * Older seeds concluded with no next episode (meta resolved): contentId to the concluded
     * seed (season, episode). The disk Next Up snapshot can still hold their stale card when the
     * concluding cycle was cancelled before persisting, so cached cards seeded at or before that
     * seed are never re-published. Same lifetime as [cwProcessedOlderSeedContentIds], which
     * keeps the seed from being re-processed; dropped when a seed released for re-lookup
     * (releaseOlderSeedsDueAgain) then resolves a next episode.
     */
    internal val cwOlderSeedsWithoutNextUp = ConcurrentHashMap<String, Pair<Int, Int>>()
    internal val cwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    /** In-memory cache of enriched InProgress items per contentId+episode key. */
    internal val cwEnrichedInProgressOverlay = ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    /** Bumped to force the CW pipeline to re-run (e.g. after cache clear). */
    internal val cwPipelineRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Tracks the active CW pipeline coroutine so it can be cancelled on profile switch. */
    internal var cwPipelineJob: Job? = null
    internal val fullyWatchedSeriesIds get() = watchedSeriesStateHolder
    internal var focusEnrichJob: Job? = null
    internal var pendingFocusEnrichItemId: String? = null
    /** Item that was focused during startup grace period — will be enriched once grace ends. */
    internal var deferredEnrichItem: MetaPreview? = null
    internal val adjacentItemPrefetchJobs = AdjacentPrefetchJobs()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var seriesWatchedObserverJob: Job? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
    internal val startupStartedAtMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null

    // Lazy catalog loading
    internal val eagerCatalogLoadCount: Int = 8
    internal val lazyLoadRequestedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val pendingLazyCatalogs = linkedMapOf<String, Pair<Addon, CatalogDescriptor>>()
    // nt2: reserved-headroom background sweep state
    @Volatile
    internal var catalogSweepJob: Job? = null
    internal val catalogSweepInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    /** All placeholder descriptors for homeRow construction. */
    internal data class PlaceholderDescriptor(
        val catalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    )
    internal val placeholderDescriptors = mutableListOf<PlaceholderDescriptor>()
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        StartupLatencyTrace.mark("home_vm_init")
        posterOptions.bind(viewModelScope)
        observeStartupAuthNotice()
        viewModelScope.launch {
            profileManager.activeProfileReady.first { it }
            StartupLatencyTrace.mark("home_profile_ready")
            observeLayoutPreferences()
            observeModernHomePresentation()
            // Badges load before the CW pipeline can write them, so its writes land on this
            // profile's ids, never on ids left from the previous profile (Spec 48 P2 review).
            watchedSeriesStateHolder.loadFromDisk()
            loadContinueWatching()
            observeExternalMetaPrefetchPreference()
            observeTrailerSourceChanges()
            observeContinueWatchingSortMode()
            loadHomeCatalogOrderPreference()
            loadFollowAddonsOrder()
            loadDisabledHomeCatalogPreference()
            loadCustomCatalogTitles()
            observeBlurUnwatchedEpisodes()
            observeCollections()
            observeInstalledAddons()

            // Settings' clear-cache action: reload every catalogue through the
            // same forceReload path the error-screen Retry uses, so cleared
            // caches are repopulated with fresh network data immediately while
            // the current rows stay visible (the isReload branch).
            viewModelScope.launch {
                homeRefreshSignal.events.collect {
                    loadAllCatalogs(addonsCache, forceReload = true)
                }
            }

            // Clear CW state when the active profile id changes while this VM lives
            // (e.g. the active profile is deleted, or sync's replaceAllProfiles drops it
            // and falls back to profile 1) so items don't leak between profiles.
            // layoutPreferencesReady is deliberately NOT reset here: its only true-setter
            // sits behind distinctUntilChanged in the layout-prefs pipeline, so identical
            // prefs would never re-emit and Home would stay on the loader.
            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    // Cancel old pipeline — prevents racing writes from stale coroutines.
                    cwPipelineJob?.cancel()
                    cwPipelineJob = null
                    // Clear all in-memory CW caches (and the CW rows) so data from the
                    // previous profile doesn't leak into the new one. The new profile's
                    // persisted revalidation deadlines are kept: clearInMemory() below resets
                    // the in-memory ones and loadFromDisk() restores them.
                    clearAllCwInMemoryCaches(clearPersistedValidation = false)
                    clearFocusState()
                    // Reset so the new profile's pipeline signals first completion correctly.
                    _initialCwResolved.value = false
                    // Clear watched badges so they don't leak between profiles; loaded before
                    // the new CW pipeline starts writing them.
                    watchedSeriesStateHolder.clearInMemory()
                    watchedSeriesStateHolder.loadFromDisk(profileId = newId)
                    loadContinueWatching()
                    _uiState.update { it.copy(movieWatchedStatus = emptyMap()) }
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_GRACE_PERIOD_MS)
            startupGracePeriodActive = false
            // Trigger enrichment for the initial focused item once grace ends.
            deferredEnrichItem?.let { item ->
                deferredEnrichItem = null
                onItemFocusPipeline(item)
            }
        }

        // Observe manual cache clear from Advanced settings.
        viewModelScope.launch {
            var lastSeen = cwEnrichmentCache.cacheCleared.value
            cwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    clearAllCwInMemoryCaches()
                }
            }
        }
    }

    /**
     * @param clearPersistedValidation also wipes the active profile's persisted series
     *   revalidation deadlines, forcing every series to be re-validated.
     */
    private fun clearAllCwInMemoryCaches(clearPersistedValidation: Boolean = true) {
        cwMetaCache.clear()
        cwMetaNegativeCacheTimestamps.clear()
        cwMetaRefreshAtMs.clear()
        cwMetaStaleKeys.clear()
        cwBadgeEpisodeCache.clear()
        cwBadgeNextSeasonMs.clear()
        cwNextUpResolutionCache.clear()
        cwNextUpNegativeCacheTimestamps.clear()
        discoveredOlderNextUpItems.clear()
        cwLastProcessedNextUpContentIds.clear()
        cwProcessedOlderSeedContentIds.clear()
        cwOlderSeedsWithoutNextUp.clear()
        cwEnrichedNextUpOverlay.clear()
        cwEnrichedInProgressOverlay.clear()
        cwLastBadgeEpisodeKeys = emptySet()
        if (clearPersistedValidation) watchedSeriesStateHolder.clearValidationState()
        _uiState.update { it.copy(continueWatchingItems = emptyList(), upcomingItems = emptyList()) }
        // Bump trigger so the pipeline's collectLatest restarts with fresh state.
        cwPipelineRefreshTrigger.value++
    }

    internal fun remainingContinueWatchingEnrichmentGraceMs(
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        return (CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs))
            .coerceAtLeast(0L)
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeModernHomePresentation() = observeModernHomePresentationPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    /** T7 (ysosrs 1e319ab2a): see observeTrailerSourceChangesPipeline. */
    private fun observeTrailerSourceChanges() = observeTrailerSourceChangesPipeline()

    private fun observeContinueWatchingSortMode() {
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.continueWatchingSortMode
                .distinctUntilChanged()
                .collect { mode ->
                    continueWatchingSortMode = mode
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    // Clear caches so the new sort is applied immediately on next pipeline run
                    clearAllCwInMemoryCaches()
                }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(useEpisodeThumbnailsInCw = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(continueWatchingEnabled = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingCardStyle
                .distinctUntilChanged()
                .collect { style ->
                    _uiState.update { it.copy(continueWatchingCardStyle = style) }
                }
        }
        // When "next up from furthest episode" changes, clear CW caches and retrigger pipeline
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
    }

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadFollowAddonsOrder() = loadFollowAddonsOrderPipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun loadCustomCatalogTitles() = loadCustomCatalogTitlesPipeline()

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(3200)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            is HomeEvent.OnMarkContinueWatchingWatched -> markContinueWatchingWatched(event.item)
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() {
        // Immediately restore last known CW from disk cache for instant display.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
            if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) return@launch
            val dismissedNextUp = continueWatchingSettings.dismissedNextUpKeys.first()
            // Render cached items immediately — don't wait for allProgress.
            // The pipeline will replace these with live data once it completes.
            val inProgressItems = cachedInProgress.map { cached -> cached.toInProgressItem() }
            // Release flags are recomputed so a row cached before its episode aired gets the
            // right badge at cold start, as in the pipeline.
            val nextUpItems = cachedNextUp
                .filter { nextUpDismissKey(it.contentId, it.seedSeason, it.seedEpisode) !in dismissedNextUp }
                .map { cached -> ContinueWatchingItem.NextUp(info = cached.toFreshNextUpInfo()) }
            val sortMode = layoutPreferenceDataStore.continueWatchingSortMode.first()
            val items = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = nextUpItems,
                mode = sortMode
            )
            if (items.isNotEmpty()) {
                val (mainItems, upcomingOnly) = splitUpcomingItems(items, sortMode)
                _uiState.update { it.copy(continueWatchingItems = mainItems, upcomingItems = upcomingOnly) }
                _initialCwResolved.value = true
            }
        }
        loadContinueWatchingPipeline()
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    // Spec 62 H4: the CW row updates from the progress flow, as after details' Mark as watched.
    private fun markContinueWatchingWatched(item: ContinueWatchingItem) {
        if (!item.canMarkWatched()) return
        viewModelScope.launch {
            try {
                watchProgressRepository.markAsCompleted(item.toCompletedProgress())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark continue watching item as watched", e)
            }
        }
    }

    private fun observeCollections() = observeCollectionsPipeline()

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    /**
     * Coalesces, never restarts: a request while a pass is waiting or running only marks the rows
     * dirty, and the job runs another pass for it. Every catalog arrival requests one, so a
     * restarting debounce let a steady stream of arrivals postpone the publish (and cancel a pass
     * mid-compute). Main-thread only, like every caller.
     */
    internal fun scheduleUpdateCatalogRows() {
        catalogRowsDirty = true
        if (catalogUpdateJob?.isActive == true) return
        catalogUpdateJob = viewModelScope.launch {
            while (catalogRowsDirty) {
                val firstRender = !hasRenderedFirstCatalog && hasAnyCatalogRows()
                if (firstRender) {
                    hasRenderedFirstCatalog = true
                    StartupLatencyTrace.mark("first_catalog_rendered")
                }
                delay(catalogUpdateDebounceMs(firstRender, pendingCatalogLoads.count))
                // Cleared before the pass snapshots state, so a later arrival gets its own pass.
                catalogRowsDirty = false
                updateCatalogRows()
                // Spec 69 M1: the first rows reach the UI state here, after the debounce above.
                if (firstRender) StartupLatencyTrace.mark("first_catalog_published")
            }
        }
    }

    /**
     * Called from the UI when a placeholder catalog row becomes visible.
     */
    fun requestLazyCatalogLoad(catalogKey: String) {
        if (catalogKey in lazyLoadRequestedKeys) {
            return
        }
        val pair = synchronized(catalogStateLock) {
            pendingLazyCatalogs.remove(catalogKey)
        }
        if (pair == null) {
            return
        }
        if (!lazyLoadRequestedKeys.add(catalogKey)) {
            return
        }
        val (addon, catalog) = pair
        val generation = catalogLoadGeneration
        pendingCatalogLoads.add()
        loadCatalogPipeline(addon, catalog, generation)
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        val nextState = _focusState.value.copy(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }
}
