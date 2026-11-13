package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.flow.stateIn
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbDetailFacts
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.withTmdbFacts
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.core.player.toParentalWarnings
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.search.toDiscoverCatalogs
import com.nuvio.tv.domain.model.isEpisodeWatched
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.playRequestVideoId
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.core.util.isUnreleased
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.sources.lastPlayedIndex
import com.nuvio.tv.ui.components.sources.toLastPlayed
import com.nuvio.tv.ui.screens.stream.streamLinkCacheKey
import com.nuvio.tv.ui.screens.stream.streamRuntimeMinutes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

private const val TAG = "MetaDetailsViewModel"
private const val STREAM_PREFETCH_DEBOUNCE_MS = 300L

// S4a-2: episode focus moves on every D-pad press while scrolling a season,
// which is far faster than opening a details page, so this is deliberately
// longer than STREAM_PREFETCH_DEBOUNCE_MS. collectLatest cancels a pending
// delay when focus moves again, so scrolling past an episode starts no work
// at all and only a deliberate pause triggers a scrape. [inferred] -- chosen
// as 2x the hero debounce; Spec 42 P3 raised it to 1.5 s and capped it at
// EPISODE_FOCUS_PREFETCH_MAX scrapes per Details page: with one stream gateway each scrape is a
// full server-side fan-out that keeps running after focus moves on (the client call is not
// cancelled, StreamSearchSessionCache), so slow scrolling cost one gateway search per episode.
private const val EPISODE_FOCUS_PREFETCH_DEBOUNCE_MS = 1_500L
private const val EPISODE_FOCUS_PREFETCH_MAX = 2

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    // S4a: used only to pre-scrape the play target while this page is open.
    private val streamRepository: com.nuvio.tv.domain.repository.StreamRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val imdbEpisodeRatingsRepository: ImdbEpisodeRatingsRepository,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    private val streamLinkCacheDataStore: com.nuvio.tv.data.local.StreamLinkCacheDataStore,
    savedStateHandle: SavedStateHandle,
    private val healthStore: AddonHealthStore,
    private val addonRepository: AddonRepository,
    private val parentalGuideRepository: ParentalGuideRepository
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()

    /** The loaded meta's episode list, re-emitted only when it changes. */
    private val metaVideos = _uiState.map { it.meta?.videos }.distinctUntilChanged()

    // S4a: the details page resolves its play target (NextToWatch -> the
    // "Next S1 E2" / "Resume ..." button) well before the press, but the addon
    // scrape cannot start until StreamScreenViewModel exists, which is after it.
    // Measured 24 Jul 2026 (Xiaomi, auto-play Best quality): that scrape is
    // 1,949-2,896 ms and is paid on every play. Kick it off here instead.
    //
    // Debounced so an accidental open that is immediately backed out of does not
    // scrape. Every millisecond of debounce is a millisecond of head start given
    // up, so it is deliberately short. StreamPrefetchCache keeps at most one
    // prefetch in flight and drops the result if nothing consumes it.
    private data class StreamPrefetchTarget(
        val type: String,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        /** The meta id, for the binge-group cache the stream screen reads. */
        val contentId: String?
    )

    private var lastStreamPrefetchKey: String? = null
    private var streamPrefetchJob: Job? = null

    // T27 (upstream-sync P23): this ViewModel keeps running under the player, where a progress
    // save can flip the hero target (episode completed -> next one); that must not start a scrape +
    // prewarm GET mid-playback. New prefetches start only while Details is resumed; an in-flight
    // one is left alone (the Stream screen joins it after a Play press). A hero target skipped
    // while away starts on resume; an episode-focus one is dropped (focus is not intent).
    // Main-thread confined, like the lifecycle events and the observers that read them.
    private var detailsOnTop = true
    private var heroPrefetchSkippedWhileAway: Pair<StreamPrefetchTarget, String>? = null

    /** Fork: details-page source line. Key of the hero-target prefetch; SEARCHING until the supplier signals. */
    private val heroSourceKey = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // P10 (data-additions st2): the hero target's stream-link cache key (set with heroSourceKey,
    // setHeroTarget) and a nonce bumped on each resume (Back from the player is when the cache
    // changes). The cached link is read and parsed off the main thread whenever either changes; any
    // age counts, as on the Sources screen.
    private val heroLinkKey = MutableStateFlow<String?>(null)
    private val heroLinkRefresh = MutableStateFlow(0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val heroLastPlayed = combine(heroLinkKey, heroLinkRefresh) { key, _ -> key }
        .mapLatest { key ->
            key?.let { streamLinkCacheDataStore.getValid(it, maxAgeMs = Long.MAX_VALUE)?.toLastPlayed() }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)

    /** The hero target: the source line's prefetch key and its stream-link cache key, always together. */
    private fun setHeroTarget(sourceKey: String?, linkKey: String?) {
        heroSourceKey.value = sourceKey
        heroLinkKey.value = linkKey
    }

    val heroSourceSignal: kotlinx.coroutines.flow.StateFlow<com.nuvio.tv.core.stream.SourcePrefetchSignal?> =
        kotlinx.coroutines.flow.combine(
            heroSourceKey,
            prefetchSelectionSupplier.uiSignals,
            playerSettingsDataStore.playerSettings,
            heroLastPlayed
        ) { key, signals, settings, lastPlayed ->
            val sig = key?.let { signals[it] }
            when {
                key == null -> null
                settings.streamAutoPlayMode == com.nuvio.tv.data.local.StreamAutoPlayMode.MANUAL -> null
                sig == null -> com.nuvio.tv.core.stream.SourcePrefetchSignal(
                    key, com.nuvio.tv.core.stream.SourcePrefetchPhase.SEARCHING, null
                )
                sig.phase == com.nuvio.tv.core.stream.SourcePrefetchPhase.EMPTY -> null
                // "Last played" when the auto-play winner is the source played last (Sources' matcher).
                else -> sig.copy(lastPlayed = sig.winner != null && lastPlayedIndex(listOf(sig.winner), lastPlayed) == 0)
            }
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            null
        )

    /**
     * nt4: kick the stream prefetch as early as the play target is knowable -
     * at raw-meta time, before enrichment - so the prewarm has the maximum head
     * start to finish before the user presses. Mirrors the post-applyMeta
     * observer's target selection exactly (movie -> playRequestVideoId; series ->
     * the nextToWatch episode, or nothing when there is no next), so the key matches
     * and the observer's later fire is a dedup no-op rather than a re-scrape.
     */
    private fun launchEarlyStreamPrefetch(rawMeta: Meta) {
        viewModelScope.launch {
            val isSeries = rawMeta.videos.any { it.season != null }
            val target: StreamPrefetchTarget? = if (isSeries) {
                val ntw = computeNextToWatchFromStore(rawMeta)
                // Fork (caught-up fix, A2): caught-up suppresses the hero
                // source line entirely - no early scrape, no hero key. The
                // Play button still targets the last episode; a press pays a
                // normal scrape under the user's auto/manual selection mode.
                // Without this gate the early path fires for the (non-null)
                // replay target and the line flashes before the observer
                // hides it.
                if (ntw.isCaughtUp) {
                    null
                } else ntw.nextVideoId?.let {
                    StreamPrefetchTarget(rawMeta.apiType, it, ntw.nextSeason, ntw.nextEpisode, rawMeta.id)
                }
            } else {
                StreamPrefetchTarget(rawMeta.apiType, rawMeta.playRequestVideoId(), null, null, rawMeta.id)
            }
            target?.let {
                onStreamPrefetchTarget(it.type, it.videoId, it.season, it.episode, it.contentId, "details_hero_early", rawMeta.name)
            }
        }
    }

    private fun onStreamPrefetchTarget(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        contentId: String?,
        source: String,
        /** The title, for sources that resolve by search instead of by id. */
        title: String? = null
    ): Boolean {
        if (type.isBlank() || videoId.isBlank()) return false
        // Hero targets (the Play button's) vs a focused episode (focus is not intent).
        val isHero = source != "episode_focus"
        val key = com.nuvio.tv.core.stream.StreamPrefetchCache.keyOf(type, videoId, season, episode)
        if (!detailsOnTop) {
            if (isHero) {
                // Key the source line to the new target (SEARCHING), never the old target's result.
                setHeroTarget(key, streamLinkCacheKey(type, videoId))
                heroPrefetchSkippedWhileAway =
                    StreamPrefetchTarget(type, videoId, season, episode, contentId) to source
            }
            return false
        }
        if (key == lastStreamPrefetchKey) return false
        lastStreamPrefetchKey = key
        if (isHero) setHeroTarget(key, streamLinkCacheKey(type, videoId))
        streamPrefetchJob?.cancel()
        streamPrefetchJob = viewModelScope.launch {
            delay(STREAM_PREFETCH_DEBOUNCE_MS)
            // P2: cap the prefetch's wait at the auto-play timeout when eager
            // ready is on, so a slow source (a bridge measured at 6-9s for 2-3
            // streams) no longer holds up ranking, prewarm and the hero
            // source line. Null = pre-P2 behaviour (wait for full completion),
            // used when the toggle is off or the timeout is instant/unlimited.
            val capMs = playerSettingsDataStore.playerSettings.first().eagerReadyCapMs()
            if (!detailsOnTop) {
                // Left during the debounce: forget the key; a hero target starts on resume.
                if (lastStreamPrefetchKey == key) lastStreamPrefetchKey = null
                if (isHero) {
                    heroPrefetchSkippedWhileAway =
                        StreamPrefetchTarget(type, videoId, season, episode, contentId) to source
                }
                return@launch
            }
            // S4a covers BOTH shapes: a movie's own id, and a series' hero
            // target (resume/next episode). Both now rank and prewarm
            // through the same supplier the Continue Watching path uses
            // (a focused episode ranks only, T30: the press warms).
            com.nuvio.tv.core.stream.StreamPrefetchCache.prefetch(
                repository = streamRepository,
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
                title = title,
                source = source,
                capMs = capMs,
                republishOnDedup = isHero,
                rank = { groups ->
                    prefetchSelectionSupplier.rankAndPrewarm(
                        groups = groups,
                        contentId = contentId,
                        // The target's own runtime from the held meta, by the Stream screen's rule.
                        runtimeMinutes = _uiState.value.meta?.let { streamRuntimeMinutes(it, season, episode) },
                        episode = season != null && episode != null,
                        uiKey = if (isHero) key else null,
                        prewarm = isHero
                    )
                }
            )
        }
        return true
    }


    /**
     * S4a-2: the episode list is the last uncovered play path.
     *
     * S4a prefetches the hero button's target -- a movie, or a series' resume/
     * next episode -- and S4a-3 the top Continue Watching entry. Picking any
     * OTHER episode from the list has always started from cold: full scrape,
     * full rank, all after the press.
     *
     * The recorded blocker ("fires in a sub-composable with neither the
     * ViewModel nor onEvent in scope") was half right. MetaDetailsContent
     * genuinely does not receive the ViewModel -- but lastFocusedEpisodeIdBySeason
     * is a ViewModel-owned map already being written from that callback, so the
     * data flow existed and only the hook was missing. One threaded callback,
     * not a new MetaDetailsEvent.
     *
     * Focus is not intent, so this is debounced harder than the hero path and
     * capped per page (Spec 42 P3); the rank runs only after a scrape completes,
     * so a scroll-through ranks nothing. No prewarm GET on focus (T30): the
     * press warms the link.
     */
    fun onEpisodeFocusedForPrefetch(episode: Video) {
        val meta = _uiState.value.meta ?: return
        if (episode.id.isBlank()) return
        episodeFocusPrefetchRequests.tryEmit(
            StreamPrefetchTarget(
                type = meta.apiType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
                contentId = meta.id
            )
        )
    }

    private val episodeFocusPrefetchRequests =
        MutableSharedFlow<StreamPrefetchTarget>(extraBufferCapacity = 16)

    // Collector-only (collectLatest runs one block at a time): no synchronisation needed.
    private var episodeFocusPrefetchCount = 0

    private val episodeFocusPrefetchObserver: Job = viewModelScope.launch {
        episodeFocusPrefetchRequests
            .distinctUntilChanged()
            .collectLatest { target ->
                delay(EPISODE_FOCUS_PREFETCH_DEBOUNCE_MS)
                if (episodeFocusPrefetchCount >= EPISODE_FOCUS_PREFETCH_MAX) return@collectLatest
                // Counted only when a prefetch starts (not the hero target again, not while away).
                if (onStreamPrefetchTarget(
                    target.type,
                    target.videoId,
                    target.season,
                    target.episode,
                    target.contentId,
                    "episode_focus",
                    _uiState.value.meta?.name
                )
                ) episodeFocusPrefetchCount++
            }
    }

    private val streamPrefetchObserver: Job = viewModelScope.launch {
        _uiState
            .map { state ->
                val meta = state.meta
                val ntw = state.nextToWatch
                val nextId = ntw?.nextVideoId
                when {
                    meta == null -> null
                    // Fork (caught-up fix, A2): checked BEFORE nextId - the
                    // caught-up target is the non-null LAST episode, which
                    // would otherwise match the branch below and key the hero
                    // line to a replay the user did not ask to preview.
                    ntw?.isCaughtUp == true -> null
                    // Series: the hero button plays this exact video id.
                    nextId != null -> StreamPrefetchTarget(meta.apiType, nextId, ntw.nextSeason, ntw.nextEpisode, meta.id)
                    // Movie: the hero button requests the meta's default video
                    // (behaviorHints.defaultVideoId, else the meta id itself).
                    state.seasons.isEmpty() -> StreamPrefetchTarget(meta.apiType, meta.playRequestVideoId(), null, null, meta.id)
                    else -> null
                }
            }
            .distinctUntilChanged()
            .collectLatest { target ->
                if (target != null) {
                    onStreamPrefetchTarget(
                        target.type,
                        target.videoId,
                        target.season,
                        target.episode,
                        target.contentId,
                        "details_hero",
                        meta.name
                    )
                } else {
                    // Fork (B1): a null target must CLEAR the hero-key state.
                    // Otherwise the source line stays keyed to the previous
                    // target with no prefetch behind it and the combine
                    // synthesises SEARCHING forever (the caught-up spin, and
                    // any future null-target transition). lastStreamPrefetchKey
                    // is cleared with it so a later return to the SAME target
                    // re-enters onStreamPrefetchTarget - re-keying the line via
                    // a cheap cache-dedup republish - instead of dedup-skipping
                    // with the line still hidden.
                    setHeroTarget(null, null)
                    lastStreamPrefetchKey = null
                    heroPrefetchSkippedWhileAway = null
                }
            }
    }

    private val localizedContext: Context
        get() {
            val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
                ?: return context
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private var moreLikeThisJob: Job? = null
    private var tmdbDetailsJob: Job? = null
    private var aboutJob: Job? = null
    /** The last TMDB detail facts and the meta id they belong to; main thread only. */
    private var tmdbDetailFacts: TmdbFactsFor? = null

    private class TmdbFactsFor(val metaId: String, val facts: TmdbDetailFacts)

    val lastFocusedEpisodeIdBySeason = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    private var episodeRatingsJob: Job? = null
    private var nextToWatchJob: Job? = null
    /** applyLoadedMeta's post-paint nextToWatch refine; see calculateNextToWatch's guard. */
    private var refineNextToWatchJob: Job? = null

    private var trailerDelayMs = 7000L
    private var trailerAutoplayEnabled = false
    private var trailerHasPlayed = false
    private var suppressSeasonAutoSwitch = false

    private var isPlayButtonFocused = false
    private var hideUnreleasedContent = false

    /** Content ID used for watch-progress and watched-items lookups.
     *  Starts as the navigation [itemId] (which may be "tmdb:123") and is
     *  updated to [Meta.id] once meta loads (typically an IMDB ID like "tt0396375").
     *  This ensures progress is read from the same key it was written under. */
    private val _effectiveContentId = MutableStateFlow(itemId)
    private val _optimisticUnmarks = mutableSetOf<Pair<Int, Int>>()

    init {
        posterOptions.bind(viewModelScope)
        observeMetaViewSettings()
        observeTrailerAutoplaySettings()
        observeLibraryState()
        observeWatchProgress()
        observeWatchedEpisodes()
        observeMovieWatched()
        observeMovieResumeProgress()
        observeRelatedWatchedStatus()
        observeBlurUnwatchedEpisodes()
        observeOverallRatingsVisibility()
        observeDetailImdbRatingsVisibility()
        observeShowFullReleaseDate()
        observeHideUnreleasedContent()
        loadMeta()
    }

    private fun observeHideUnreleasedContent() {
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    hideUnreleasedContent = enabled
                }
        }
    }

    private fun observeMetaViewSettings() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.trailerButtonEnabled == enabled) {
                            state
                        } else {
                            state.copy(trailerButtonEnabled = enabled)
                        }
                    }
                }
        }
    }

    private fun setTrailerPlaybackState(
        isPlaying: Boolean,
        showControls: Boolean,
        hideLogo: Boolean
    ) {
        _uiState.update { state ->
            if (state.isTrailerPlaying == isPlaying &&
                state.showTrailerControls == showControls &&
                state.hideLogoDuringTrailer == hideLogo
            ) {
                state
            } else {
                state.copy(
                    isTrailerPlaying = isPlaying,
                    showTrailerControls = showControls,
                    hideLogoDuringTrailer = hideLogo
                )
            }
        }
    }

    private fun updateNextToWatch(nextToWatch: NextToWatch) {
        _uiState.update { state ->
            if (state.nextToWatch == nextToWatch) return@update state
            val nextSeason = nextToWatch.nextSeason
            val meta = state.meta
            val shouldSwitchSeason = !suppressSeasonAutoSwitch &&
                nextSeason != null &&
                nextSeason != state.selectedSeason &&
                meta != null &&
                state.seasons.contains(nextSeason)
            if (shouldSwitchSeason) {
                state.copy(
                    nextToWatch = nextToWatch,
                    selectedSeason = nextSeason,
                    episodesForSeason = getEpisodesForSeason(meta.videos, nextSeason)
                )
            } else {
                state.copy(nextToWatch = nextToWatch)
            }
        }
    }

    private fun observeTrailerAutoplaySettings() {
        viewModelScope.launch {
            trailerSettingsDataStore.settings.collectLatest { settings ->
                trailerAutoplayEnabled = settings.enabled
                trailerDelayMs = settings.delaySeconds * 1000L
                if (!settings.enabled) {
                    idleTimerJob?.cancel()
                }
            }
        }
    }

    fun onEvent(event: MetaDetailsEvent) {
        when (event) {
            is MetaDetailsEvent.OnSeasonSelected -> selectSeason(event.season)
            MetaDetailsEvent.OnToggleLibrary -> toggleLibrary()
            MetaDetailsEvent.OnRetry -> loadMeta()
            MetaDetailsEvent.OnUserInteraction -> handleUserInteraction()
            MetaDetailsEvent.OnPlayButtonFocused -> handlePlayButtonFocused()
            MetaDetailsEvent.OnTrailerButtonClick -> handleTrailerButtonClick()
            MetaDetailsEvent.OnTrailerEnded -> handleTrailerEnded()
            is MetaDetailsEvent.OnSharedTrailerSelected -> handleSharedTrailerSelected(event.trailer)
            MetaDetailsEvent.OnDismissSharedTrailer -> dismissSharedTrailerOverlay()
            MetaDetailsEvent.OnRetrySharedTrailer -> retrySharedTrailer()
            MetaDetailsEvent.OnToggleMovieWatched -> toggleMovieWatched()
            is MetaDetailsEvent.OnToggleEpisodeWatched -> toggleEpisodeWatched(event.video)
            is MetaDetailsEvent.OnMarkSeasonWatched -> markSeasonWatched(event.season)
            is MetaDetailsEvent.OnMarkSeasonUnwatched -> markSeasonUnwatched(event.season)
            is MetaDetailsEvent.OnMarkPreviousEpisodesWatched -> markPreviousEpisodesWatched(event.video)
            is MetaDetailsEvent.OnMarkPreviousSeasonsWatched -> markPreviousSeasonsWatched(event.season)
            MetaDetailsEvent.OnLibraryLongPress -> openListPicker()
            is MetaDetailsEvent.OnPickerMembershipToggled -> togglePickerMembership(event.listKey)
            MetaDetailsEvent.OnPickerSave -> savePickerMembership()
            MetaDetailsEvent.OnPickerDismiss -> dismissListPicker()
            MetaDetailsEvent.OnClearMessage -> clearMessage()
            MetaDetailsEvent.OnLifecyclePause -> handleLifecyclePause()
            MetaDetailsEvent.OnLifecycleResume -> handleLifecycleResume()
            MetaDetailsEvent.OnAboutOpened -> loadAboutExtras()
        }
    }

    /**
     * The About sheet's extras, once per title: the release-region order and the genres
     * Discover can open first, then the parents guide (a network fetch, cached by its
     * repository). Nothing here blocks the sheet; each fills its rows when it lands.
     */
    private fun loadAboutExtras() {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.about.metaId == meta.id || aboutJob?.isActive == true) return
        aboutJob = viewModelScope.launch {
            val regions = tmdbMetadataService.preferredReleaseRegions(tmdbSettingsDataStore.settings.first().language)
            val genres = discoverGenres(meta.apiType)
            _uiState.update { state ->
                if (state.meta?.id != meta.id) state
                else state.copy(about = DetailAboutExtras(metaId = meta.id, releaseRegions = regions, discoverGenres = genres))
            }
            val imdbId = (meta.imdbId ?: meta.id).substringBefore(':').takeIf { it.startsWith("tt") } ?: return@launch
            val warnings = parentalGuideRepository.getParentalGuide(imdbId)
                ?.toParentalWarnings(context::getString)
                .orEmpty()
            if (warnings.isEmpty()) return@launch
            _uiState.update { state ->
                if (state.about.metaId != meta.id) state
                else state.copy(about = state.about.copy(parentalWarnings = warnings))
            }
        }
    }

    /** Lowercased genre options of the enabled addons' Discover catalogs of [type]; empty with Discover off. */
    private suspend fun discoverGenres(type: String): Set<String> {
        if (layoutPreferenceDataStore.discoverLocation.first() == DiscoverLocation.OFF) return emptySet()
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "About sheet: installed addons unavailable", e)
            return emptySet()
        }
        return addons.toDiscoverCatalogs()
            .filter { it.type.equals(type, ignoreCase = true) }
            .flatMap { it.genres }
            .mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
    }

    private fun observeLibraryState() {
        // Observe library membership on the *same* (id, type) pair that
        // `toggleLibrary` writes via `meta.toLibraryEntryInput()`. Falling back
        // to navigation (itemId, itemType) until meta loads keeps the button
        // responsive but pre-meta (when toggle is unavailable anyway).
        val canonicalKey = _uiState
            .map { state ->
                val id = state.meta?.id?.takeIf { it.isNotBlank() } ?: itemId
                val type = state.meta?.apiType?.takeIf { it.isNotBlank() } ?: itemType
                id to type
            }
            .distinctUntilChanged()

        viewModelScope.launch {
            canonicalKey
                .flatMapLatest { (id, type) -> libraryRepository.isInLibrary(itemId = id, itemType = type) }
                .distinctUntilChanged()
                .collectLatest { inLibrary ->
                    _uiState.update { state ->
                        if (state.isInLibrary == inLibrary) state else state.copy(isInLibrary = inLibrary)
                    }
                }
        }
    }

    private fun observeWatchProgress() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                if (itemType.equals("other", ignoreCase = true)) {
                    // For "other" type, videos lack season/episode.
                    // Build progress map by matching video IDs to their
                    // position in the meta video list. Combined with the
                    // video list so a meta that loads after the progress
                    // emission still gets its map.
                    combine(
                        watchProgressRepository.allProgress,
                        metaVideos,
                    ) { allProgress, videoList ->
                        val videos = videoList ?: emptyList()
                        val progressByVideoId = allProgress
                            .filter { it.contentId == cid }
                            .associateBy { it.videoId }
                        val result = mutableMapOf<Pair<Int, Int>, WatchProgress>()
                        videos.forEachIndexed { index, video ->
                            val progress = progressByVideoId[video.id]
                            if (progress != null) {
                                // Use synthetic season=1, episode=index+1 as key
                                result[1 to (index + 1)] = progress
                            }
                        }
                        result as Map<Pair<Int, Int>, WatchProgress>
                    }
                } else {
                    watchProgressRepository.getAllEpisodeProgress(cid)
                }
            }
                .distinctUntilChanged()
                .collectLatest { progressMap ->
                _uiState.update { state ->
                    if (state.episodeProgressMap == progressMap) {
                        state
                    } else {
                        state.copy(episodeProgressMap = progressMap)
                    }
                }
                // Recalculate next to watch when progress changes
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
    }

    private fun observeWatchedEpisodes() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                combine(
                    watchedItemsPreferences.getWatchedEpisodesForContent(cid),
                    watchProgressRepository.getAllEpisodeProgress(cid),
                    // Re-evaluates the optimistic sets when the episode list changes.
                    metaVideos,
                ) { localWatched, progressMap, _ ->
                    val fromProgress = progressMap.filterValues { it.isCompleted() }.keys
                    val merged = (localWatched + fromProgress).toMutableSet()
                    // Remove optimistic unmarks — episodes the user just batch-unmarked
                    // that may still linger in localWatched/fromProgress briefly.
                    merged -= _optimisticUnmarks
                    merged as Set<Pair<Int, Int>>
                }
            }
                .distinctUntilChanged()
                .collectLatest { watchedSet ->
                _uiState.update { state ->
                    if (state.watchedEpisodes == watchedSet) {
                        state
                    } else {
                        state.copy(watchedEpisodes = watchedSet)
                    }
                }
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
        // Re-calculate next-to-watch when "furthest episode" preference changes
        viewModelScope.launch {
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .collectLatest {
                    calculateNextToWatch()
                }
        }
    }

    /**
     * Spec 48 P3 (upstream NM f81a93426): this ViewModel stays alive under the player, so a
     * movie's Resume line and button follow its saved progress instead of the state from
     * before Play. Series take theirs from [observeWatchProgress].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMovieResumeProgress() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid -> watchProgressRepository.getProgress(cid) }
                .distinctUntilChanged()
                .collectLatest { calculateNextToWatch() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMovieWatched() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid -> watchProgressRepository.isWatched(cid) }
                .distinctUntilChanged()
                .collectLatest { watched ->
                _uiState.update { state ->
                    if (state.isMovieWatched == watched) state else state.copy(isMovieWatched = watched)
                }
            }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurUnwatchedEpisodes
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.blurUnwatchedEpisodes == enabled) state else state.copy(blurUnwatchedEpisodes = enabled)
                }
                }
        }
    }

    private fun observeDetailImdbRatingsVisibility() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailImdbRatingsVisibility
                .distinctUntilChanged()
                .collectLatest { visibility ->
                    _uiState.update { state ->
                        if (state.detailImdbRatingsVisibility == visibility) {
                            state
                        } else {
                            state.copy(detailImdbRatingsVisibility = visibility)
                        }
                    }
                }
        }
    }

    private fun observeOverallRatingsVisibility() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeImdbRatingsVisibility
                .distinctUntilChanged()
                .collectLatest { visibility ->
                    _uiState.update { state ->
                        if (state.overallRatingsVisibility == visibility) {
                            state
                        } else {
                            state.copy(overallRatingsVisibility = visibility)
                        }
                    }
                }
        }
    }

    private fun observeRelatedWatchedStatus() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds
            ) { movieIds, seriesIds ->
                buildMap {
                    movieIds.forEach { id -> put("${id}|movie", true) }
                    seriesIds.forEach { id -> put("${id}|series", true) }
                }
            }.distinctUntilChanged().collect { status ->
                _uiState.update { state ->
                    if (state.relatedWatchedStatus == status) state else state.copy(relatedWatchedStatus = status)
                }
            }
        }
    }

    private fun observeShowFullReleaseDate() {
        viewModelScope.launch {
            layoutPreferenceDataStore.showFullReleaseDate
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.showFullReleaseDate == enabled) state else state.copy(showFullReleaseDate = enabled)
                }
            }
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            // Metadata timing instrument. Grep anchor: MetaTiming. metaLoadStartMs
            // is read in applyLoadedMeta (always the next step on the
            // meta) to measure addon-meta-ready and total text-ready latency.
            // Logging only.
            metaLoadStartMs = android.os.SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null,
                    moreLikeThis = emptyList(),
                    moreLikeThisSource = null,
                    collection = emptyList(),
                    collectionName = null,
                    isSharedTrailerOverlayVisible = false,
                    isSharedTrailerLoading = false,
                    sharedTrailerUrl = null,
                    sharedTrailerAudioUrl = null,
                    sharedTrailerErrorMessage = null,
                    selectedSharedTrailer = null
                )
            }

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)
            // Update effective content ID as early as possible so watch-progress
            // observers use the canonical (usually IMDB) ID, not the navigation ID.
            if (metaLookupId != itemId) {
                _effectiveContentId.value = metaLookupId
            }
            val preferExternal = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()

            if (preferExternal) {
                // 1) Try meta addons first
                metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            applyLoadedMeta(result.data)
                        }
                        is NetworkResult.Error -> {
                            // 2) Fallback: try originating addon if meta addons failed
                            val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                            val preferredMeta: Meta? = preferred?.let { baseUrl ->
                                when (val fallbackResult = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                                    .first { it !is NetworkResult.Loading }) {
                                    is NetworkResult.Success -> fallbackResult.data
                                    else -> null
                                }
                            }

                            if (preferredMeta != null) {
                                applyLoadedMeta(preferredMeta)
                            } else if (!tryApplyTmdbFallbackMeta()) {
                                val errorMsg = buildMetaLoadErrorMessage(result.message, metaLookupId)
                                _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                            }
                        }
                        NetworkResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                    }
                }
            } else {
                // Original: prefer catalog addon
                val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                val preferredMeta: Meta? = preferred?.let { baseUrl ->
                    when (val result = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                        .first { it !is NetworkResult.Loading }) {
                        is NetworkResult.Success -> result.data
                        else -> null
                    }
                }

                if (preferredMeta != null) {
                    applyLoadedMeta(preferredMeta)
                } else {
                    metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                        when (result) {
                            is NetworkResult.Success -> applyLoadedMeta(result.data)
                            is NetworkResult.Error -> {
                                if (!tryApplyTmdbFallbackMeta()) {
                                    val errorMsg = buildMetaLoadErrorMessage(result.message, metaLookupId)
                                    _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                                }
                            }
                            NetworkResult.Loading -> {
                                _uiState.update { it.copy(isLoading = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun tryApplyTmdbFallbackMeta(): Boolean {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return false
        val type = ContentType.fromString(itemType)
        val settings = tmdbSettingsDataStore.settings.first()
        val enrichment = tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId.toString(),
            contentType = type,
            language = settings.language
        ) ?: return false
        val meta = Meta(
            id = itemId,
            type = type,
            rawType = itemType,
            name = enrichment.localizedTitle ?: enrichment.originalTitle
                ?: context.getString(R.string.detail_tmdb_fallback_title, tmdbId),
            poster = enrichment.poster,
            posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = enrichment.backdrop,
            logo = enrichment.logo,
            description = enrichment.description,
            releaseInfo = enrichment.releaseInfo,
            status = enrichment.status,
            imdbRating = null,
            tmdbRating = enrichment.rating?.toFloat(),
            genres = enrichment.genres,
            runtime = enrichment.runtimeMinutes?.toString(),
            director = enrichment.director,
            writer = enrichment.writer,
            cast = enrichment.castMembers.map { it.name },
            castMembers = enrichment.castMembers,
            videos = emptyList(),
            productionCompanies = enrichment.productionCompanies,
            networks = enrichment.networks,
            ageRating = enrichment.ageRating,
            ageRatingDescriptors = enrichment.ageRatingDescriptors,
            country = enrichment.countries?.joinToString(", "),
            awards = null,
            language = enrichment.language,
            links = emptyList(),
            // Honor the TMDB "Trailers" toggle on this synthetic fallback meta
            // (issue #1647), the same gate TrailerService applies to its
            // TMDB->YouTube path; without it the fallback path would smuggle
            // TMDB trailers in unconditionally.
            trailers = if (settings.useTrailers) enrichment.trailers else emptyList()
        )
        applyLoadedMeta(meta)
        return true
    }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val raw = itemId.trim()
        if (!raw.startsWith("tmdb:", ignoreCase = true)) return raw

        val tmdbNumericId = raw
            .substringAfter(':', missingDelimiterValue = "")
            .substringBefore(':')
            .toIntOrNull()
            ?: return raw

        // Use a short timeout so a blocked TMDB API doesn't stall the detail screen.
        return kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            tmdbService.tmdbToImdb(tmdbNumericId, itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: raw
    }

    private fun buildMetaLoadErrorMessage(originalMessage: String?, lookupId: String): String {
        val base = originalMessage ?: "Failed to load metadata"
        return "$base\n\nID: $lookupId"
    }

    /**
     * Render-raw-first (0.8.4-nt1): publishes meta to the UI state only — the
     * part that makes the details screen appear. Split out of applyMeta so the
     * addon meta can be painted immediately, before the progress reads in
     * applyLoadedMeta, then re-published by applyMeta. Side effects run once,
     * on applyMeta below.
     */
    private fun publishMetaToUi(addonMeta: Meta) {
        val meta = addonMeta.withTmdbFacts(tmdbDetailFacts?.takeIf { it.metaId == addonMeta.id }?.facts)
        // Update the effective content ID so watch-progress observers pick up
        // the canonical ID (e.g. IMDB "tt0396375") instead of the navigation ID
        // (which may be "tmdb:13836").  Don't downgrade from an IMDB ID to a
        // less canonical one (e.g. tmdb:) — progress is usually keyed by IMDB.
        if (meta.id.isNotBlank() && meta.id != itemId) {
            val currentIsImdb = _effectiveContentId.value.startsWith("tt")
            val newIsImdb = meta.id.startsWith("tt")
            if (!currentIsImdb || newIsImdb) {
                _effectiveContentId.value = meta.id
            }
        }

        val seasons = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()
            .ifEmpty {
                // For "other" type content videos lack season/episode numbers.  
                // Treat them as a single virtual season so the episodes UI can display them.
                if (meta.videos.isNotEmpty()) listOf(1) else emptyList()
            }

        val defaultEpisodeSeason = findPreferredDefaultEpisode(meta)?.season
        // Prefer addon-specified default episode season, otherwise first regular season (> 0), fallback to season 0 (specials)
        val selectedSeason = defaultEpisodeSeason
            ?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.firstOrNull()
            ?: 1
        val episodesForSeason = getEpisodesForSeason(meta.videos, selectedSeason)

        _uiState.update {
            // If nextToWatch already set a season (from pre-computed remap), prefer it
            // over the default season selection.
            val effectiveSeason = it.nextToWatch?.nextSeason
                ?.takeIf { s -> s in seasons }
                ?: selectedSeason
            val effectiveEpisodes = if (effectiveSeason != selectedSeason) {
                getEpisodesForSeason(meta.videos, effectiveSeason)
            } else {
                episodesForSeason
            }
            it.copy(
                isLoading = false,
                meta = meta,
                seasons = seasons,
                selectedSeason = effectiveSeason,
                episodesForSeason = effectiveEpisodes,
                error = null
            )
        }

    }

    private fun applyMeta(meta: Meta) {
        publishMetaToUi(meta)
        // Calculate next to watch after meta is loaded
        reevaluateSeriesWatchedBadge()
        calculateNextToWatch()

        // Start fetching trailer after meta is loaded
        fetchTrailerUrl()
    }

    /** Metadata timing instrument (grep anchor: MetaTiming). Set in loadMeta. */
    @Volatile
    private var metaLoadStartMs: Long = 0L

    private suspend fun applyLoadedMeta(meta: Meta) {
        // MetaTiming: addon meta is in hand here (cache hit or network); this
        // line and META_APPLY bracket the progress reads before applyMeta.
        android.util.Log.i(
            "MetaTiming",
            "META_FETCH ms=${android.os.SystemClock.elapsedRealtime() - metaLoadStartMs} id=${meta.id}"
        )
        // nt4: fire the stream prefetch off the meta NOW, before the progress
        // reads below. The play target is already determined here (a movie's id,
        // or the series nextToWatch computed from the addon episode list), so this
        // is the same target the post-applyMeta observer would compute, sooner -
        // giving the prewarm more head start to complete before the press. The
        // observer's later fire dedups on lastStreamPrefetchKey, so this does not
        // double-scrape.
        launchEarlyStreamPrefetch(meta)
        // Fire all independent async jobs immediately — they run in parallel.
        loadMoreLikeThisAsync(meta)
        // Render-raw-first (0.8.4-nt1): paint the meta NOW so the details screen
        // appears immediately; applyMeta below re-publishes it and runs the side
        // effects once.
        publishMetaToUi(meta)
        loadTmdbDetailsIfEnabled(meta)

        // Pre-compute nextToWatch before applyMeta so the PlayButton text is stable
        // from the first composition — prevents focus invalidation from late recomposition.
        updateNextToWatch(computeNextToWatchFromStore(meta))

        android.util.Log.i(
            "MetaTiming",
            "META_APPLY total_ms=${android.os.SystemClock.elapsedRealtime() - metaLoadStartMs}"
        )
        viewModelScope.launch {
            healthStore.record(
                AddonHealthStore.METADATA_KEY,
                HealthOutcome.SUCCESS,
                android.os.SystemClock.elapsedRealtime() - metaLoadStartMs
            )
        }
        // 0.8.4 merge (#3): first paint stays fast (above); after it, wait up to
        // 150 ms for a non-empty local progress map, then re-read and refine
        // nextToWatch, so progress that lands in local storage just after first
        // paint replaces a stale "Play S1E1" with "Resume". There is no
        // remote-loaded wait. updateNextToWatch no-ops when the value is unchanged.
        // Launched (and tracked) before applyMeta so calculateNextToWatch's
        // empty-state guard already covers applyMeta's own recompute; a newer
        // meta supersedes an older refine.
        refineNextToWatchJob?.cancel()
        refineNextToWatchJob = viewModelScope.launch {
            val cid = _effectiveContentId.value
            val refreshedProgress = withTimeoutOrNull(150L) {
                watchProgressRepository.getAllEpisodeProgress(cid).first { it.isNotEmpty() }
            } ?: watchProgressRepository.getAllEpisodeProgress(cid).first()
            val refreshedWatched = watchedItemsPreferences
                .getWatchedEpisodesForContent(cid)
                .first()
            val refined = computeNextToWatch(meta, refreshedProgress, refreshedWatched)
            updateNextToWatch(refined)
        }
        applyMeta(meta)
        loadEpisodeRatingsAsync(meta)
    }

    private fun loadMoreLikeThisAsync(meta: Meta) {
        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            val settings = tmdbSettingsDataStore.settings.first()
            if (!shouldLoadMoreLikeThis(settings)) {
                _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                return@launch
            }
            val source = MoreLikeThisSource.TMDB
            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbId = resolveTmdbIdFor(meta)
            if (tmdbId.isNullOrBlank()) {
                _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                return@launch
            }

            // The service owns the guarantee: failure -> empty list, cancellation rethrown (KT-CONC-03).
            val rawRecommendations = tmdbMetadataService.fetchMoreLikeThis(
                tmdbId = tmdbId,
                contentType = tmdbContentType,
                language = settings.language
            )

            val recommendations = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                rawRecommendations.filterNot { it.isUnreleased(today) }
            } else {
                rawRecommendations
            }

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(
                        moreLikeThis = recommendations,
                        moreLikeThisSource = source.takeIf { recommendations.isNotEmpty() }
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun shouldLoadMoreLikeThis(settings: TmdbSettings): Boolean {
        return settings.enabled && settings.useMoreLikeThis
    }

    /**
     * Behind the TMDB master flag: the facts addon metas lack (Spec 26e: studios/networks,
     * original title, TMDB score and votes, the local certification), filled only where
     * the addon meta is empty, then the franchise/collection row (plus the Collections
     * toggle; series have no TMDB collection). The facts fetch caches the movie's
     * collection ref, so the row costs no second details request. Settings are read
     * inside the job so the render path never waits on them.
     */
    private fun loadTmdbDetailsIfEnabled(meta: Meta) {
        tmdbDetailsJob?.cancel()
        tmdbDetailsJob = viewModelScope.launch {
            val settings = tmdbSettingsDataStore.settings.first()
            if (!settings.enabled) {
                tmdbDetailFacts = null
                return@launch
            }
            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbId = resolveTmdbIdFor(meta) ?: return@launch
            tmdbMetadataService.fetchDetailFacts(
                tmdbId = tmdbId,
                contentType = tmdbContentType,
                language = settings.language
            )?.let { facts ->
                tmdbDetailFacts = TmdbFactsFor(meta.id, facts)
                _uiState.update { state ->
                    val shown = state.meta
                    if (shown == null || shown.id != meta.id) state else state.copy(meta = shown.withTmdbFacts(facts))
                }
            }
            if (settings.useCollections) loadTmdbCollection(tmdbId, tmdbContentType, settings.language)
        }
    }

    private suspend fun loadTmdbCollection(tmdbId: String, tmdbContentType: ContentType, language: String) {
        val collectionRef = tmdbMetadataService.fetchCollectionRef(
            tmdbId = tmdbId,
            contentType = tmdbContentType,
            language = language
        ) ?: return

        // The service owns the guarantee: failure -> empty list, cancellation rethrown (KT-CONC-03).
        val items = tmdbMetadataService.fetchMovieCollection(
            collectionId = collectionRef.id,
            language = language
        )

        val filteredItems = if (hideUnreleasedContent) {
            val today = LocalDate.now()
            items.filterNot { it.isUnreleased(today) }
        } else {
            items
        }

        _uiState.update { state ->
            state.copy(collection = filteredItems, collectionName = collectionRef.name)
        }
    }

    private fun loadEpisodeRatingsAsync(meta: Meta) {
        episodeRatingsJob?.cancel()

        if (!meta.isSeriesApiType()) {
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null
                )
            }
            return
        }

        episodeRatingsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = true,
                    episodeRatingsError = null
                )
            }

            // Ratings the addon supplied on meta.videos[].rating. The repository below
            // still wins wherever it has an entry.
            val addonRatings: Map<Pair<Int, Int>, Double> = meta.videos.mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                val rating = video.rating ?: return@mapNotNull null
                (season to episode) to rating
            }.toMap()

            try {
                val tmdbContentType = resolveTmdbContentType(meta)
                if (tmdbContentType !in listOf(ContentType.SERIES, ContentType.TV)) {
                    _uiState.update {
                        it.copy(
                            episodeImdbRatings = addonRatings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                    return@launch
                }

                val tmdbId = resolveTmdbIdFor(meta)?.toIntOrNull()
                val imdbId = extractImdbId(meta.id) ?: extractImdbId(itemId)

                if (tmdbId == null && imdbId == null) {
                    _uiState.update { state ->
                        if (state.meta == null || state.meta.id != meta.id) {
                            state
                        } else {
                            state.copy(
                                episodeImdbRatings = addonRatings,
                                isEpisodeRatingsLoading = false,
                                episodeRatingsError = if (addonRatings.isEmpty()) {
                                    localizedContext.getString(R.string.ratings_unavailable)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    return@launch
                }

                val ratings = imdbEpisodeRatingsRepository.getEpisodeRatings(
                    imdbId = imdbId,
                    tmdbId = tmdbId
                )

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = addonRatings + ratings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load episode ratings for ${meta.id}: ${error.message}")
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = addonRatings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = if (addonRatings.isEmpty()) {
                                localizedContext.getString(R.string.ratings_load_error)
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    /** TMDB id for [meta], falling back to the navigation id when the meta id resolves none. */
    private suspend fun resolveTmdbIdFor(meta: Meta): String? =
        tmdbService.ensureTmdbId(meta.id, resolveTmdbContentType(meta).toApiString())
            ?: tmdbService.ensureTmdbId(itemId, itemType)

    private fun resolveTmdbContentType(meta: Meta): ContentType {
        val fromRoute = parseApiTypeToContentType(itemType)
        if (fromRoute != null) return fromRoute

        val fromMetaApi = parseApiTypeToContentType(meta.apiType)
        if (fromMetaApi != null) return fromMetaApi

        return when (meta.type) {
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            ContentType.MOVIE -> ContentType.MOVIE
            else -> ContentType.MOVIE
        }
    }

    private fun parseApiTypeToContentType(apiType: String?): ContentType? {
        val normalized = apiType?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "movie", "film" -> ContentType.MOVIE
            "series", "tv", "show", "tvshow" -> ContentType.SERIES
            else -> null
        }
    }

    private fun selectSeason(season: Int) {
        val meta = _uiState.value.meta ?: return
        val episodes = getEpisodesForSeason(meta.videos, season)
        _uiState.update {
            it.copy(
                selectedSeason = season,
                episodesForSeason = episodes
            )
        }
    }

    private fun getEpisodesForSeason(videos: List<Video>, season: Int): List<Video> {
        val filtered = videos.filter { it.season == season }
        if (filtered.isNotEmpty()) return filtered.sortedBy { it.episode }
        // Fallback: if no videos match the season (e.g. "other" type with
        // null seasons), return all videos with synthetic season/episode
        // numbers so the episode UI can track watched state.
        if (videos.isNotEmpty() && videos.all { it.season == null }) {
            return videos.mapIndexed { index, video ->
                video.copy(season = 1, episode = index + 1)
            }
        }
        return emptyList()
    }

    private fun reevaluateSeriesWatchedBadge() {
        val contentId = _effectiveContentId.value
        val meta = _uiState.value.meta ?: return
        val isSeries = meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        if (!isSeries) return

        val episodes = meta.watchableEpisodes()
        if (episodes.isEmpty()) return

        val state = _uiState.value
        val allWatched = episodes.all { video ->
            isEpisodeWatched(state.episodeProgressMap, state.watchedEpisodes, video.season!!, video.episode!!)
        }

        val allIds = buildSet {
            add(contentId)
            meta.id.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
            itemId.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
        }
        watchedSeriesStateHolder.setSeriesFullyWatched(allIds, fullyWatched = allWatched)
    }

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = _uiState.value.episodeProgressMap
        val watchedEpisodes = _uiState.value.watchedEpisodes
        // 0.8.4 merge (#3) / L5-79: see shouldKeepPrecomputedNextToWatch.
        if (shouldKeepPrecomputedNextToWatch(
                refineInFlight = refineNextToWatchJob?.isActive == true,
                progressMap = progressMap,
                watchedEpisodes = watchedEpisodes,
                hasNextToWatch = _uiState.value.nextToWatch != null
            )
        ) {
            return
        }
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            val nextToWatch = computeNextToWatch(meta, progressMap, watchedEpisodes)
            updateNextToWatch(nextToWatch)
        }
    }

    private suspend fun computeNextToWatch(
        meta: Meta,
        progressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet()
    ): NextToWatch {
        if (!meta.isSeriesApiType()) {
            val progress = watchProgressRepository.getProgress(_effectiveContentId.value).first()
            return if (progress != null && shouldResumeProgress(progress)) {
                NextToWatch(
                    watchProgress = progress,
                    isResume = true,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_resume)
                )
            } else {
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_play)
                )
            }
        }

        val allEpisodes = meta.videos
            .filter { it.season != null && it.episode != null }
            .filter { it.available != false }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (allEpisodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = meta.id,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        val nonSpecialEpisodes = allEpisodes.filter { (it.season ?: 0) > 0 }
        val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
        val useFurthestEpisode = layoutPreferenceDataStore.nextUpFromFurthestEpisode.first()
        val latestSeriesProgress = if (useFurthestEpisode) {
            // When using furthest episode mode, consider both progressMap entries
            // AND watchedEpisodes (batch marks) to find the furthest watched episode.
            val furthestFromProgress = progressMap.values
                .filter { it.isCompleted() || shouldResumeProgress(it) }
                .maxWithOrNull(
                    compareBy<WatchProgress> { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            val furthestFromWatched = watchedEpisodes
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                ?.let { (s, e) ->
                    // Only use watchedEpisodes entry if it's further than progressMap
                    val progressFurthest = furthestFromProgress?.let { (it.season ?: 0) to (it.episode ?: 0) }
                    if (progressFurthest == null || s > progressFurthest.first || (s == progressFurthest.first && e > progressFurthest.second)) {
                        episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                            syntheticCompletedProgress(video, lastWatched = System.currentTimeMillis())
                        }
                    } else null
                }
            furthestFromWatched ?: furthestFromProgress
        } else {
            progressMap.values
                .sortedWith(
                    compareByDescending<WatchProgress> { it.lastWatched }
                        .thenByDescending { it.season ?: 0 }
                        .thenByDescending { it.episode ?: 0 }
                )
                .firstOrNull()
        }
        val effectiveLatestProgress = latestSeriesProgress ?: run {
            if (watchedEpisodes.isEmpty()) null
            else {
                val watchedWithTimestamps = watchedItemsPreferences
                    .getWatchedEpisodesWithTimestamps(_effectiveContentId.value)
                    .first()
                val highest = watchedWithTimestamps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Pair<Int, Int>, Long>> { it.value }
                        .thenBy { it.key.first }
                        .thenBy { it.key.second })
                highest?.let { (key, watchedAt) ->
                    val (s, e) = key
                    episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                        syntheticCompletedProgress(video, lastWatched = watchedAt)
                    }
                }
            }
        }
        val defaultEpisode = findPreferredDefaultEpisode(meta)?.takeIf { preferred ->
            episodePool.any { it.id == preferred.id }
        }

        return buildNextToWatchFromLatestProgress(
            latestProgress = effectiveLatestProgress,
            episodes = episodePool,
            fallbackProgressMap = progressMap,
            watchedEpisodes = watchedEpisodes,
            metaId = meta.id,
            defaultEpisode = defaultEpisode,
            isRewatchMode = !useFurthestEpisode
        )
    }

    /** Completed-progress stand-in for a watched-set entry, so it can anchor next-to-watch. */
    private fun syntheticCompletedProgress(video: Video, lastWatched: Long): WatchProgress =
        WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = "series",
            name = "",
            poster = null, backdrop = null, logo = null,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            position = 1L, duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f
        )

    /** Next-to-watch for [meta] from the stored progress and watched sets (not the observed UI state). */
    private suspend fun computeNextToWatchFromStore(meta: Meta): NextToWatch {
        val progressMap = watchProgressRepository
            .getAllEpisodeProgress(_effectiveContentId.value)
            .first()
        val watchedEpisodes = watchedItemsPreferences
            .getWatchedEpisodesForContent(_effectiveContentId.value)
            .first()
        return computeNextToWatch(meta, progressMap, watchedEpisodes)
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
        metaId: String,
        defaultEpisode: Video? = null,
        isRewatchMode: Boolean = false
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        if (latestProgress?.season != null && latestProgress.episode != null) {
            val season = latestProgress.season
            val episode = latestProgress.episode
            val matchedIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }

            if (shouldResumeProgress(latestProgress)) {
                val matchedEpisode = if (matchedIndex >= 0) episodes[matchedIndex] else null
                return NextToWatch(
                    watchProgress = latestProgress,
                    isResume = true,
                    nextVideoId = matchedEpisode?.id ?: latestProgress.videoId,
                    nextSeason = season,
                    nextEpisode = episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, season, episode)
                )
            }

            if (latestProgress.isCompleted() && matchedIndex >= 0) {
                if (isRewatchMode) {
                    // In rewatch mode, simply take the next episode regardless of watched state
                    val next = episodes.getOrNull(matchedIndex + 1)
                    if (next != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = next.id,
                            nextSeason = next.season,
                            nextEpisode = next.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, next.season, next.episode)
                        )
                    }
                } else {
                    // Normal mode: skip already watched episodes
                    val nextUnwatched = episodes.subList(matchedIndex + 1, episodes.size)
                        .firstOrNull { candidate ->
                            val s = candidate.season ?: return@firstOrNull true
                            val e = candidate.episode ?: return@firstOrNull true
                            !isEpisodeWatched(fallbackProgressMap, watchedEpisodes, s, e)
                        }
                    if (nextUnwatched != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = nextUnwatched.id,
                            nextSeason = nextUnwatched.season,
                            nextEpisode = nextUnwatched.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, nextUnwatched.season, nextUnwatched.episode)
                        )
                    }
                }
            }
        }

        var resumeEpisode: Video? = null
        var resumeProgress: WatchProgress? = null
        var nextUnwatchedEpisode: Video? = null

        for (episode in episodes) {
            val season = episode.season ?: continue
            val ep = episode.episode ?: continue
            val progress = fallbackProgressMap[season to ep]

            if (progress != null) {
                if (shouldResumeProgress(progress)) {
                    resumeEpisode = episode
                    resumeProgress = progress
                    break
                } else if (progress.isCompleted()) {
                    continue
                }
            }
            // Check watchedEpisodes — covers both batch marks and episodes
            // that haven't propagated to episodeProgressMap yet.
            if (progress == null && (season to ep) in watchedEpisodes) {
                continue
            }
            if (progress == null) {
                if (nextUnwatchedEpisode == null) {
                    nextUnwatchedEpisode = episode
                }
                if (resumeEpisode == null) {
                    break
                }
            }
        }

        return when {
            resumeEpisode != null && resumeProgress != null -> {
                NextToWatch(
                    watchProgress = resumeProgress,
                    isResume = true,
                    nextVideoId = resumeEpisode.id,
                    nextSeason = resumeEpisode.season,
                    nextEpisode = resumeEpisode.episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, resumeEpisode.season, resumeEpisode.episode)
                )
            }
            nextUnwatchedEpisode != null -> {
                val hasWatchedSomething = fallbackProgressMap.isNotEmpty()
                val preferredEpisode = if (hasWatchedSomething) nextUnwatchedEpisode else (defaultEpisode ?: nextUnwatchedEpisode)
                val s = preferredEpisode.season
                val e = preferredEpisode.episode
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = preferredEpisode.id,
                    nextSeason = s,
                    nextEpisode = e,
                    displayText = if (hasWatchedSomething) {
                        localizedContext.getString(R.string.detail_btn_next_episode, s, e)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play_episode, s, e)
                    }
                )
            }
            else -> {
                // Fork (caught-up fix): this branch is reached only when every
                // available episode is watched and none is resumable - e.g.
                // the newest aired episode of an ongoing series was just
                // finished and the next is unreleased. It previously targeted
                // episodes.firstOrNull(), resetting the Play button to S1E1.
                // Target the LAST episode instead (an honest replay) and flag
                // the state so the hero source line suppresses itself (A2).
                val lastEpisode = episodes.lastOrNull()
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = lastEpisode?.id ?: metaId,
                    nextSeason = lastEpisode?.season,
                    nextEpisode = lastEpisode?.episode,
                    displayText = if (lastEpisode != null) {
                        localizedContext.getString(R.string.detail_btn_play_episode, lastEpisode.season, lastEpisode.episode)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play)
                    },
                    isCaughtUp = true
                )
            }
        }
    }

    private fun findPreferredDefaultEpisode(meta: Meta): Video? {
        val defaultVideoId = meta.behaviorHints?.defaultVideoId ?: return null
        return meta.videos.firstOrNull { it.id == defaultVideoId && it.available != false }
    }

    private fun shouldResumeProgress(progress: WatchProgress): Boolean {
        if (progress.isCompleted()) return false
        if (progress.progressPercentage >= 0.02f) return true

        return progress.position > 0L ||
            progress.progressPercent?.let { it > 0f } == true
    }

    private fun toggleLibrary() {
        if (_uiState.value.defaultLibraryTogglePending) return
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val input = meta.toLibraryEntryInput()
            val wasInLibrary = _uiState.value.isInLibrary
            _uiState.update { it.copy(defaultLibraryTogglePending = true) }
            runCatching {
                libraryRepository.toggleDefault(input)
            }.onSuccess {
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                val message = if (wasInLibrary) {
                    localizedContext.getString(R.string.detail_removed_from_library)
                } else {
                    localizedContext.getString(R.string.detail_added_to_library)
                }
                showMessage(message)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_library_failed),
                    isError = true
                )
            }
        }
    }

    // Long-press list picker: the app's own library is its only row.
    private fun openListPicker() {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                val input = meta.toLibraryEntryInput()
                val inLibrary = libraryRepository.isInLibrary(input.itemId, input.itemType).first()
                _uiState.update {
                    it.copy(
                        showListPicker = true,
                        pickerMembership = mapOf(LOCAL_LIBRARY_LIST_KEY to inLibrary),
                        pickerPending = false,
                        pickerError = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed),
                        showListPicker = false
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed), isError = true)
            }
        }
    }

    private fun togglePickerMembership(listKey: String) {
        if (listKey != LOCAL_LIBRARY_LIST_KEY) return
        _uiState.update { current ->
            current.copy(
                pickerMembership = current.pickerMembership +
                    (listKey to (current.pickerMembership[listKey] != true)),
                pickerError = null
            )
        }
    }

    private fun savePickerMembership() {
        if (_uiState.value.pickerPending) return
        val meta = _uiState.value.meta ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                val input = meta.toLibraryEntryInput()
                val desired = _uiState.value.pickerMembership[LOCAL_LIBRARY_LIST_KEY] == true
                libraryRepository.setInLibrary(input, inLibrary = desired)
            }.onSuccess {
                completePickerSave()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed)
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed), isError = true)
            }
        }
    }

    private fun dismissListPicker() {
        _uiState.update {
            it.copy(
                showListPicker = false,
                pickerPending = false,
                pickerError = null,
                defaultLibraryTogglePending = false
            )
        }
    }

    private fun completePickerSave() {
        _uiState.update {
            it.copy(
                pickerPending = false,
                showListPicker = false,
                pickerError = null,
                defaultLibraryTogglePending = false
            )
        }
        showMessage(localizedContext.getString(R.string.detail_lists_updated))
    }

    private fun toggleMovieWatched() {
        val meta = _uiState.value.meta ?: return
        if (meta.apiType != "movie") return
        if (_uiState.value.isMovieWatchedPending) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMovieWatchedPending = true) }
            runCatching {
                if (_uiState.value.isMovieWatched) {
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value)
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta))
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_watched))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_watched_failed),
                    isError = true
                )
            }
            _uiState.update { it.copy(isMovieWatchedPending = false) }
        }
    }

    private fun toggleEpisodeWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val season = video.season ?: return
        val episode = video.episode ?: return
        val pendingKey = episodePendingKey(video)
        if (_uiState.value.episodeWatchedPendingKeys.contains(pendingKey)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKey)
            }

            val state = _uiState.value
            val isWatched = isEpisodeWatched(state.episodeProgressMap, state.watchedEpisodes, season, episode)
            runCatching {
                if (isWatched) {
                    _optimisticUnmarks += season to episode
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, season = season, episode = episode)
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_unwatched))
                } else {
                    _optimisticUnmarks -= season to episode
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_watched))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_episode_watched_failed),
                    isError = true
                )
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKey)
            }
        }
    }

    fun isSeasonFullyWatched(season: Int): Boolean {
        val state = _uiState.value
        val meta = state.meta ?: return false
        val episodes = episodeSource(meta.apiType, meta.videos, state)
            .filter { it.season == season && it.episode != null }
        if (episodes.isEmpty()) return false
        return episodes.all { video ->
            val s = video.season ?: return@all false
            val e = video.episode ?: return@all false
            isEpisodeWatched(state.episodeProgressMap, state.watchedEpisodes, s, e)
        }
    }

    private fun markSeasonWatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            applyBatchWatched(
                meta = meta,
                request = BatchWatchedRequest.MarkSeason(season),
                nothingToDoRes = R.string.detail_all_episodes_watched,
                doneRes = R.string.detail_marked_episodes_watched,
                failureLog = "Failed to batch mark season $season as watched"
            )
        }
    }

    private fun markSeasonUnwatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            applyBatchWatched(
                meta = meta,
                request = BatchWatchedRequest.UnmarkSeason(season),
                nothingToDoRes = R.string.detail_no_watched_episodes,
                doneRes = R.string.detail_marked_episodes_unwatched,
                failureLog = "Failed to batch unmark season $season"
            )
        }
    }

    // Unlike the season actions, this one does not set suppressSeasonAutoSwitch.
    private fun markPreviousEpisodesWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val targetSeason = video.season ?: return
        val targetEpisode = video.episode ?: return

        viewModelScope.launch {
            applyBatchWatched(
                meta = meta,
                request = BatchWatchedRequest.MarkPreviousEpisodes(targetSeason, targetEpisode),
                nothingToDoRes = R.string.detail_all_previous_watched,
                doneRes = R.string.detail_marked_previous_watched,
                failureLog = "Failed to batch mark previous episodes as watched"
            )
        }
    }

    private fun markPreviousSeasonsWatched(targetSeason: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            applyBatchWatched(
                meta = meta,
                request = BatchWatchedRequest.MarkPreviousSeasons(targetSeason),
                nothingToDoRes = R.string.detail_all_previous_seasons_watched,
                doneRes = R.string.detail_marked_episodes_watched,
                failureLog = "Failed to batch mark previous seasons as watched"
            )
        }
    }

    /**
     * Shared body of the batch watched actions. Marks the [batchWatchedTargets] of [request]
     * watched (or unwatched, for an unmark) in one repository call, with optimistic unmarks
     * and pending keys around it, then re-evaluates the series badge and reports the count.
     * A failed write is logged only, as before.
     */
    private suspend fun applyBatchWatched(
        meta: Meta,
        request: BatchWatchedRequest,
        @StringRes nothingToDoRes: Int,
        @StringRes doneRes: Int,
        failureLog: String
    ) {
        val markWatched = request.markWatched
        val targets = batchWatchedTargets(request, meta.apiType, meta.videos, _uiState.value)
        if (targets.isEmpty()) {
            showMessage(localizedContext.getString(nothingToDoRes))
            return
        }

        val episodeKeys = targets.map { it.season!! to it.episode!! }
        if (markWatched) {
            _optimisticUnmarks -= episodeKeys
        } else {
            _optimisticUnmarks += episodeKeys
        }

        val pendingKeys = targets.map { episodePendingKey(it) }.toSet()
        _uiState.update {
            it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
        }

        runCatching {
            if (markWatched) {
                watchProgressRepository.markAsCompletedBatch(targets.map { buildCompletedEpisodeProgress(meta, it) })
            } else {
                watchProgressRepository.removeFromHistoryBatch(
                    contentId = _effectiveContentId.value,
                    episodes = episodeKeys
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "$failureLog: ${error.message}")
        }

        _uiState.update {
            it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
        }
        reevaluateSeriesWatchedBadge()
        showMessage(localizedContext.getString(doneRes, targets.size))
    }

    private fun buildCompletedMovieProgress(meta: Meta): WatchProgress {
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.backdropUrl,
            logo = meta.logo,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun buildCompletedEpisodeProgress(meta: Meta, video: Video): WatchProgress {
        val runtimeMs = video.runtime?.toLong()?.times(60_000L) ?: 1L
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = video.thumbnail ?: meta.backdropUrl,
            logo = meta.logo,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            position = runtimeMs,
            duration = runtimeMs,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun episodePendingKey(video: Video): String {
        return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { state ->
            if (state.userMessage == message && state.userMessageIsError == isError) {
                state
            } else {
                state.copy(
                    userMessage = message,
                    userMessageIsError = isError
                )
            }
        }
    }

    private fun clearMessage() {
        _uiState.update { state ->
            if (state.userMessage == null && !state.userMessageIsError) {
                state
            } else {
                state.copy(userMessage = null, userMessageIsError = false)
            }
        }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val normalized = rawId.trim()
        return if (normalized.startsWith("tt", ignoreCase = true)) {
            normalized.substringBefore(':')
        } else {
            null
        }
    }

    private fun Meta.toLibraryEntryInput(): LibraryEntryInput {
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = preferredAddonBaseUrl
        )
    }

    // --- Trailer ---

    private fun fetchTrailerUrl() {
        val meta = _uiState.value.meta ?: return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            val source = trailerService.getTrailerPlaybackSourceForItem(
                itemId = meta.id,
                title = meta.name,
                releaseInfo = meta.releaseInfo,
                apiType = meta.apiType
            ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytId"
                )
            }
            val url = source?.videoUrl
            val audioUrl = source?.audioUrl

            _uiState.update { state ->
                if (state.trailerUrl == url && state.trailerAudioUrl == audioUrl) {
                    state
                } else {
                    state.copy(
                        trailerUrl = url,
                        trailerAudioUrl = audioUrl
                    )
                }
            }

            if (url != null && isPlayButtonFocused) {
                startIdleTimer()
            }
        }
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()

        val state = _uiState.value
        if (state.trailerUrl == null || state.isTrailerPlaying) return
        if (!trailerAutoplayEnabled) return
        if (trailerHasPlayed) return
        if (!isPlayButtonFocused) return

        idleTimerJob = viewModelScope.launch {
            delay(trailerDelayMs)
            setTrailerPlaybackState(
                isPlaying = true,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handlePlayButtonFocused() {
        if (isPlayButtonFocused) return
        isPlayButtonFocused = true
        startIdleTimer()
    }

    private fun handleUserInteraction() {
        val state = _uiState.value
        val shouldStopAutoTrailer = state.isTrailerPlaying && !state.showTrailerControls
        val hasActiveIdleTimer = idleTimerJob?.isActive == true
        if (!isPlayButtonFocused && !hasActiveIdleTimer && !shouldStopAutoTrailer) {
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false

        if (shouldStopAutoTrailer) {
            trailerHasPlayed = true
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handleLifecycleResume() {
        detailsOnTop = true
        heroLinkRefresh.value++
        val (target, source) = heroPrefetchSkippedWhileAway ?: return
        heroPrefetchSkippedWhileAway = null
        onStreamPrefetchTarget(target.type, target.videoId, target.season, target.episode, target.contentId, source, _uiState.value.meta?.name)
    }

    private fun handleLifecyclePause() {
        detailsOnTop = false
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        dismissSharedTrailerOverlay()
        val state = _uiState.value
        if (state.isTrailerPlaying && !state.showTrailerControls) {
            trailerHasPlayed = true
            setTrailerPlaybackState(isPlaying = false, showControls = false, hideLogo = false)
        }
    }

    private fun handleTrailerButtonClick() {
        val state = _uiState.value
        if (state.trailerUrl.isNullOrBlank()) return
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = true,
            showControls = true,
            hideLogo = true
        )
    }

    private fun handleTrailerEnded() {
        trailerHasPlayed = true
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = false,
            showControls = false,
            hideLogo = false
        )
    }

    private fun handleSharedTrailerSelected(trailer: MetaTrailer) {
        val ytId = trailer.ytId?.trim().orEmpty()
        if (ytId.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    isSharedTrailerOverlayVisible = true,
                    isSharedTrailerLoading = false,
                    sharedTrailerUrl = null,
                    sharedTrailerAudioUrl = null,
                    sharedTrailerErrorMessage = localizedContext.getString(R.string.detail_trailer_error),
                    selectedSharedTrailer = trailer
                )
            }
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        if (_uiState.value.isTrailerPlaying) {
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }

        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = true,
                isSharedTrailerLoading = true,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null,
                selectedSharedTrailer = trailer
            )
        }

        viewModelScope.launch {
            val source = trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = "https://www.youtube.com/watch?v=$ytId"
            )

            _uiState.update { state ->
                if (state.selectedSharedTrailer?.ytId != trailer.ytId) {
                    state
                } else {
                    state.copy(
                        isSharedTrailerOverlayVisible = true,
                        isSharedTrailerLoading = false,
                        sharedTrailerUrl = source?.videoUrl,
                        sharedTrailerAudioUrl = source?.audioUrl,
                        sharedTrailerErrorMessage = if (source == null) {
                            localizedContext.getString(R.string.detail_trailer_error)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private fun retrySharedTrailer() {
        _uiState.value.selectedSharedTrailer?.let(::handleSharedTrailerSelected)
    }

    private fun dismissSharedTrailerOverlay() {
        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = false,
                isSharedTrailerLoading = false,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null
            )
        }
    }
}

/** Series per the API type (SERIES/TV metas resolve to exactly "series"/"tv"). */
private fun Meta.isSeriesApiType(): Boolean = apiType == "series" || apiType == "tv"

/**
 * 0.8.4 merge (#3): true while the applyLoadedMeta refine is in flight and an observer-driven
 * recompute would run from not-yet-loaded (empty) progress over a precomputed nextToWatch.
 * Once the refine is done, empty sets are real (e.g. the only watched episode was unmarked)
 * and must recompute (L5-79).
 */
internal fun shouldKeepPrecomputedNextToWatch(
    refineInFlight: Boolean,
    progressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    hasNextToWatch: Boolean
): Boolean = refineInFlight && progressMap.isEmpty() && watchedEpisodes.isEmpty() && hasNextToWatch

/**
 * Episodes the season/batch actions pick from. For "other" type, episodesForSeason
 * (synthetic season/episode numbers); for regular series, the meta's videos so any season
 * can be addressed.
 */
private fun episodeSource(apiType: String, videos: List<Video>, state: MetaDetailsUiState): List<Video> =
    if (apiType.equals("other", ignoreCase = true)) state.episodesForSeason else videos

/** A batch watched action from the season and episode menus. */
internal sealed interface BatchWatchedRequest {
    data class MarkSeason(val season: Int) : BatchWatchedRequest
    data class UnmarkSeason(val season: Int) : BatchWatchedRequest
    /** Episodes of [season] before [episode]. */
    data class MarkPreviousEpisodes(val season: Int, val episode: Int) : BatchWatchedRequest
    /** Every regular season (> 0, so not specials) before [season]. */
    data class MarkPreviousSeasons(val season: Int) : BatchWatchedRequest
}

/** False only for an unmark: the other requests mark episodes watched. */
internal val BatchWatchedRequest.markWatched: Boolean
    get() = this !is BatchWatchedRequest.UnmarkSeason

/**
 * Episodes [request] changes: those it covers in the [episodeSource] (all carry season and
 * episode) that are not already in the requested state per [state]'s progress and watched set.
 */
internal fun batchWatchedTargets(
    request: BatchWatchedRequest,
    apiType: String,
    videos: List<Video>,
    state: MetaDetailsUiState
): List<Video> = episodeSource(apiType, videos, state).filter { video ->
    val season = video.season ?: return@filter false
    val episode = video.episode ?: return@filter false
    val covered = when (request) {
        is BatchWatchedRequest.MarkSeason -> season == request.season
        is BatchWatchedRequest.UnmarkSeason -> season == request.season
        is BatchWatchedRequest.MarkPreviousEpisodes -> season == request.season && episode < request.episode
        is BatchWatchedRequest.MarkPreviousSeasons -> season < request.season && season > 0
    }
    covered && isEpisodeWatched(state.episodeProgressMap, state.watchedEpisodes, season, episode) != request.markWatched
}
