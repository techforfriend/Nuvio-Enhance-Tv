package com.nuvio.tv.ui.screens.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nuvio.tv.domain.model.isSearchable
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX
import com.nuvio.tv.domain.model.isPlaceholderItemId

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaPrefetcher: com.nuvio.tv.ui.components.MetaFocusPrefetcher,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    /** Each result row's first visible card index (Marquee rows restore an index, as on Home). */
    var savedRowScrollPositions: Map<String, Int> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()
    private var searchRunJob: Job? = null
    private var activeSearchQuery: String? = null
    private var searchGeneration = 0L
    private var catalogRowsUpdateJob: Job? = null
    private var liveSearchJob: Job? = null
    private var lastRequestKey: String? = null
    private var lastCompletedRequestKey: String? = null
    private var hasRenderedFirstCatalog = false
    // Spec 36/S7 (B4-4): catalogs of the current run that answered (rows or error), and whether
    // the run finished. Only an unanswered catalog of a running search may keep showing the
    // previous query's row; otherwise a failed or cancelled catalog showed stale results forever.
    private val answeredCatalogKeys = HashSet<String>()
    private var searchSettled = false
    private var hideUnreleasedContent = false
    // Spec 40: whether the active run left out search-on-Enter catalogs (null until it knows), so
    // a submit never reuses a live run that skipped them; and whether the last run asked them
    // (Retry repeats the same kind of run).
    private var activeRunMissesOnEnter: Boolean? = null
    private var lastRunIncludedOnEnter = false

    private companion object {
        /**
         * Live search fires while typing, but each run fans out to every enabled addon catalog, so
         * it waits for a typing pause to avoid a request storm per keystroke.
         */
        const val LIVE_SEARCH_DEBOUNCE_MS = 350L

        /** Spec 36/S7 (B4-5): at most one row update per this window after the first answer. */
        const val CATALOG_ROWS_THROTTLE_MS = 150L

        /** Keyboard completions, taken from the first addon's live-search results. */
        const val MAX_SUGGESTIONS = 8
        const val MAX_RECENT_SEARCHES = 8
    }

    init {
        posterOptions.bind(viewModelScope)
        metaPrefetcher.bind(viewModelScope)
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        // Drives Search's Discover button and empty-query layout; Discover itself is DiscoverViewModel.
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogAddonNameEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogAddonNameEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(landscapePostersEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
        // Screen.Search.QUERY_ROUTE (e.g. a cast name from details): prefill and run it.
        // Navigation already decoded the arg once; decoding again would turn '+' into a space.
        savedStateHandle.get<String>(Screen.Search.QUERY_ARG)?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            performSearch(query)
        }
    }

    fun prefetchMetaOnFocus(id: String, type: String) = metaPrefetcher.prefetchOnFocus(id, type)

    fun getCachedBackdrop(id: String, type: String): String? = metaPrefetcher.cachedBackdrop(id, type)

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.RememberSearchFromTextInput -> rememberSearchFromTextInput()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            SearchEvent.Retry -> {
                // An explicit retry must refetch even though nothing about the request changed.
                lastRequestKey = null
                lastCompletedRequestKey = null
                cancelSearchRun()
                performSearch(
                    uiState.value.submittedQuery.ifBlank { uiState.value.query },
                    includeOnEnter = lastRunIncludedOnEnter
                )
            }
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            it.copy(
                query = query,
                error = null,
                isSearching = false,
                // Keep whatever is on screen while a keystroke waits to run. Clearing here flashed
                // the no-results state on every letter, because on a remote each letter outlasts the
                // debounce. The screen renders skeleton rows for this window instead.
                catalogRows = if (trimmedInput.length < MIN_SEARCH_QUERY_LENGTH) emptyList() else it.catalogRows
            )
        }

        // Drop in-flight requests for the previous keystroke before scheduling the next run.
        cancelSearchRun()

        // Live search: results follow what you type, like mobile. Debounced because each run hits
        // every enabled addon catalog, except the search-on-Enter ones (Spec 40), which wait for a
        // submit.
        liveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
            liveSearchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(LIVE_SEARCH_DEBOUNCE_MS)
                performSearch(query)
            }
        } else {
            // Emptying the field has to retire the submitted query too. Leaving it set kept the
            // screen in its results state with nothing to show, instead of falling back to recent
            // searches, until the screen was rebuilt by navigating away and back.
            performSearch(query)
        }
    }

    /**
     * Keyboard completions come from the live-search run itself (no request of their own): the
     * item names in the first addon's search rows (first pages already ranked by
     * [rankByTitleMatch]), ordered by [rankSuggestions]. Rows are read in
     * manifest order ([catalogOrder]), not response-arrival order ([catalogsMap]), so the order is
     * the same on every identical search.
     */
    private fun publishSuggestions(query: String, suggestionAddonId: String) {
        val names = catalogOrder.mapNotNull { catalogsMap[it] }
            .filter { row -> row.addonId == suggestionAddonId }
            .flatMapTo(linkedSetOf<String>()) { row -> row.items.map { it.name } }
        val ranked = rankSuggestions(names, query, MAX_SUGGESTIONS)
        _uiState.update { it.copy(suggestions = ranked) }
    }

    private fun submitSearch() {
        // An explicit submit skips the remaining debounce and also asks the search-on-Enter
        // addons the live run leaves out (Spec 40 D2).
        liveSearchJob?.cancel()
        performSearch(_uiState.value.query, rememberToHistory = true, includeOnEnter = true)
    }

    /**
     * Moving from the text input into the results confirms the current query the same way
     * Done/submit does. Live search may already have results on screen without an explicit
     * submit; saving here
     * avoids losing useful history while still not recording every keystroke prefix.
     */
    private fun rememberSearchFromTextInput() {
        val state = _uiState.value
        val query = state.submittedQuery.trim().ifBlank { state.query.trim() }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return
        val hasRealResults = state.catalogRows.any { row ->
            row.items.any { item -> !item.id.isPlaceholderItemId() }
        } || catalogsMap.values.any { row ->
            row.items.any { item -> !item.id.isPlaceholderItemId() }
        }
        if (!hasRealResults) return
        viewModelScope.launch {
            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
        }
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun resetCatalogAccumulator() {
        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        answeredCatalogKeys.clear()
        searchSettled = false
    }

    private fun cancelSearchRun() {
        searchGeneration++
        searchRunJob?.cancel()
        searchRunJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        activeSearchQuery = null
        activeRunMissesOnEnter = null
    }

    /**
     * Identifies a search by everything that changes what it would return: the query, the released
     * filter, and the exact set of catalogs it would hit. Enabling an addon or flipping the filter
     * changes the key, so those still refetch.
     */
    private fun buildRequestKey(
        query: String,
        searchTargets: List<Pair<Addon, CatalogDescriptor>>
    ): String = buildString {
        append(query.lowercase())
        append('|')
        append(hideUnreleasedContent)
        append('|')
        append(
            searchTargets.joinToString(separator = "|") { (addon, catalog) ->
                "${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
            }
        )
    }


    /**
     * Runs [rawQuery]. [includeOnEnter]: also ask the addons marked search-on-Enter (Spec 40); a
     * run without them lists their catalogs in [SearchUiState.searchOnEnterWaits] instead.
     */
    private fun performSearch(
        rawQuery: String,
        rememberToHistory: Boolean = false,
        includeOnEnter: Boolean = false
    ) {
        val query = rawQuery.trim()
        _uiState.update {
            it.copy(
                submittedQuery = submittedSearchQuery(query),
                query = rawQuery
            )
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            cancelSearchRun()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()
            lastRequestKey = null
            lastCompletedRequestKey = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList(),
                    suggestions = emptyList(),
                    searchOnEnterWaits = emptyList()
                )
            }
            // Search never renders Discover (the Discover button opens its own route, backed by
            // DiscoverViewModel), so an empty query loads nothing here.
            return
        }

        // Submit can immediately follow the debounced live-search launch. Reuse an active run for
        // the same query, but cancel a different query's entire scope before starting this one. A
        // submit does not reuse a live run that left out (or may leave out) search-on-Enter addons.
        if (
            activeSearchQuery == query && searchRunJob?.isActive == true &&
            (!includeOnEnter || activeRunMissesOnEnter == false)
        ) return
        cancelSearchRun()
        val generation = searchGeneration
        activeSearchQuery = query
        // A submit run never misses on-Enter addons, so a second submit during its lookup reuses it.
        activeRunMissesOnEnter = if (includeOnEnter) false else null
        // Set before the addon lookup: Retry after a failed live run must stay live.
        lastRunIncludedOnEnter = includeOnEnter

        val job = viewModelScope.launch {
            val (addons, onEnterKeys) = try {
                addonRepository.getInstalledAddons().first().enabledAddons() to
                    // Case-insensitive, as the marks' prune on uninstall.
                    addonRepository.getSearchOnSubmitUrls().first().mapTo(HashSet(), ::addonUrlMatchKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration && activeSearchQuery == query) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: context.getString(com.nuvio.tv.R.string.search_error_load_addons_failed)) }
                }
                return@launch
            }

            if (generation != searchGeneration || activeSearchQuery != query) return@launch

            val allTargets = buildSearchTargets(addons)
            val (onEnterTargets, liveTargets) =
                allTargets.partition { (addon, _) -> addonUrlMatchKey(addon.baseUrl) in onEnterKeys }
            // A submit keeps manifest order (on-Enter rows take their usual place, mockup stage 4).
            val searchTargets = if (includeOnEnter) allTargets else liveTargets
            val waits = if (includeOnEnter) emptyList() else searchOnEnterWaits(onEnterTargets)
            activeRunMissesOnEnter = waits.isNotEmpty()

            // Same query against the same catalogs, and that run either finished or is still
            // arriving, so there is nothing new to fetch. Without this, pressing Done after live
            // search had already run the query tore the rows down and refetched everything, and
            // deleting a letter then retyping it did the same. A run that was cancelled part way
            // is deliberately not counted, so it gets to finish rather than staying half filled.
            val requestKey = buildRequestKey(query, searchTargets)
            val alreadySatisfied = requestKey == lastRequestKey &&
                (requestKey == lastCompletedRequestKey || activeSearchJobs.any { it.isActive })
            if (alreadySatisfied) {
                // Equal keys mean equal asked catalogs, not equal waits (a marked addon disabled
                // since the last run), so the waiting lines follow this lookup.
                _uiState.update { it.copy(searchOnEnterWaits = waits) }
                // An explicit submit that lands on a query the live search already finished still
                // counts as a search the user confirmed, so remember it instead of skipping out
                // of the history save below.
                if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                    viewModelScope.launch {
                        searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                    }
                }
                return@launch
            }
            lastRequestKey = requestKey

            // Committed to a new run: drop the previous query's work and accumulated rows.
            activeSearchJobs.forEach { it.cancel() }
            activeSearchJobs = emptyList()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()

            // Rows are left alone here. Clearing them produced an empty frame between the old
            // results and the placeholders below, which is the flash this screen used to show.
            _uiState.update {
                it.copy(
                    isSearching = true,
                    error = null,
                    installedAddons = addons,
                    suggestions = emptyList(),
                    searchOnEnterWaits = waits
                )
            }

            if (searchTargets.isEmpty() && waits.isNotEmpty()) {
                // Every search catalog waits for a submit: nothing to ask yet, and no error.
                lastCompletedRequestKey = requestKey
                searchSettled = true
                _uiState.update { it.copy(isSearching = false, catalogRows = emptyList()) }
                return@launch
            }
            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = context.getString(R.string.search_error_no_catalogs),
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }
            val suggestionAddonId = searchTargets.first().first.id

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "${PLACEHOLDER_ITEM_ID_PREFIX}${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = PLACEHOLDER_IMAGE_URL,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            // Only shimmer when there is nothing real to look at. If the previous query's results
            // are still up, they stay until this query's results replace them, so refining a search
            // is a single swap rather than results -> shimmer -> results on every letter.
            _uiState.update { state ->
                val showingRealRows = state.catalogRows.any { row ->
                    !row.items.firstOrNull()?.id.isPlaceholderItemId()
                }
                if (showingRealRows) state else state.copy(catalogRows = placeholderRows)
            }

            val jobs = searchTargets.map { (addon, catalog) ->
                launch {
                    loadCatalog(addon, catalog, query, generation, suggestionAddonId)
                }
            }
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            try {
                jobs.joinAll()
            } finally {
                if (
                    generation == searchGeneration &&
                    activeSearchQuery == query &&
                    uiState.value.submittedQuery.trim() == query
                ) {
                    lastCompletedRequestKey = requestKey
                    searchSettled = true
                    catalogRowsUpdateJob?.cancel()
                    updateCatalogRowsNow()
                    _uiState.update { it.copy(isSearching = false) }
                    // Only explicit submit (or moving into results from the text input — see
                    // rememberSearchFromTextInput)
                    // writes history. Live search fires per keystroke and would otherwise record
                    // every prefix ("do", "dog") even when the user never confirmed the search.
                    if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                        viewModelScope.launch {
                            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                        }
                    }
                }
            }
        }
        searchRunJob = job
    }

    private suspend fun loadCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String,
        generation: Long,
        suggestionAddonId: String
    ) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        addonBaseUrl = addon.baseUrl,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    catalogsMap[key] = result.data.copy(items = rankByTitleMatch(result.data.items, query))
                    answeredCatalogKeys += key
                    scheduleCatalogRowsUpdate()
                    if (addon.id == suggestionAddonId) publishSuggestions(query, suggestionAddonId)
                }
                is NetworkResult.Error -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    answeredCatalogKeys += catalogKey(
                        addonId = addon.id,
                        addonBaseUrl = addon.baseUrl,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update { it.copy(error = result.message) }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun isCurrentSearch(generation: Long, query: String): Boolean =
        generation == searchGeneration && uiState.value.submittedQuery.trim() == query

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val (key, currentRow) = catalogsMap.entries.firstOrNull { (_, row) ->
            row.addonId == addonId && row.apiType == type && row.catalogId == catalogId
        }?.let { it.key to it.value } ?: return

        if (currentRow.isLoading || !currentRow.hasMore) {
            return
        }

        // Blank (unsubmitted) query: bail BEFORE flagging the row, or nothing
        // would ever reset the loading flag.
        val query = uiState.value.query.trim()
        if (query.isBlank()) {
            return
        }

        val loadingRow = currentRow.copy(isLoading = true)
        catalogsMap[key] = loadingRow
        scheduleCatalogRowsUpdate()

        // Heals OUR loading flag by identity — a superseding run that early-returns
        // without resetting the accumulator leaves this row live, and a newer run's
        // row must never be touched.
        fun healLoadingFlag() {
            if (catalogsMap[key] === loadingRow) {
                catalogsMap[key] = loadingRow.copy(isLoading = false)
                scheduleCatalogRowsUpdate()
            }
        }

        viewModelScope.launch {
            // Same staleness guard loadCatalog has: a new search run bumps the
            // generation, and a late page (or its error path) from a superseded
            // query must not merge into (or overwrite) the new query's rows.
            val generation = searchGeneration
            val addon = uiState.value.installedAddons.find { it.id == addonId && it.baseUrl == currentRow.addonBaseUrl }
                ?: uiState.value.installedAddons.find { it.id == addonId } ?: run {
                healLoadingFlag()
                return@launch
            }

            val nextSkip = currentRow.nextCatalogSkip()
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        if (generation != searchGeneration) {
                            healLoadingFlag()
                            return@collect
                        }
                        val latestRow = catalogsMap[key] ?: currentRow
                        val mergedRow = latestRow.mergeCatalogPage(result.data)
                        catalogsMap[key] = mergedRow
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        if (generation != searchGeneration) {
                            healLoadingFlag()
                            return@collect
                        }
                        // Reset the flag on the row as it exists NOW, not the
                        // pre-request snapshot (a landed page may have replaced it).
                        catalogsMap[key] = (catalogsMap[key] ?: currentRow).copy(isLoading = false)
                        scheduleCatalogRowsUpdate()
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    /**
     * Spec 36/S7 (B4-5): the first answer renders at once, then at most one update per
     * [CATALOG_ROWS_THROTTLE_MS]. A pending update is never restarted (the old cancel-and-restart
     * debounce held every row back while answers kept arriving); it reads the latest map when it fires.
     */
    private fun scheduleCatalogRowsUpdate() {
        if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
            // First real rows: render now, ahead of any update an earlier error queued.
            hasRenderedFirstCatalog = true
            catalogRowsUpdateJob?.cancel()
            catalogRowsUpdateJob = viewModelScope.launch { updateCatalogRowsNow() }
            return
        }
        if (catalogRowsUpdateJob?.isActive == true) return
        catalogRowsUpdateJob = viewModelScope.launch {
            kotlinx.coroutines.delay(CATALOG_ROWS_THROTTLE_MS)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.map { key ->
                catalogsMap[key]
                    ?: state.catalogRows.find {
                        it.stableKey() == key
                    }?.takeIf { key !in answeredCatalogKeys && !searchSettled }
            }.filterNotNull().filter { row ->
                // Keep placeholder rows (shimmer) and rows with real items.
                // Drop rows that came back empty from the API.
                val isPlaceholder = row.isLoading &&
                    row.items.firstOrNull()?.id.isPlaceholderItemId()
                isPlaceholder || row.items.isNotEmpty()
            }
            val filteredRows = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                orderedRows.map { row ->
                    if (row.isLoading && row.items.firstOrNull()?.id.isPlaceholderItemId()) {
                        row
                    } else {
                        row.filterReleasedItems(today)
                    }
                }
            } else {
                orderedRows
            }
            state.copy(
                catalogRows = filteredRows
            )
        }
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> catalog.isSearchable() }
                .map { catalog -> addon to catalog }
        }

        // Spec 36/S7 (B4-6): the same catalog listed twice is searched once.
        return allSearchTargets.distinctBy { (addon, catalog) ->
            catalogKey(addon.id, addon.baseUrl, catalog.apiType, catalog.id)
        }
    }

    private fun catalogKey(addonId: String, addonBaseUrl: String, type: String, catalogId: String): String {
        return catalogRowStableKey(addonId, addonBaseUrl, type, catalogId)
    }
}
