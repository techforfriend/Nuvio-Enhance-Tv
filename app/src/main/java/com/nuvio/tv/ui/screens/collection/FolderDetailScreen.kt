@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueeTab
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.LocalFastScrollActive
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.home.MarqueeHomeContent
import com.nuvio.tv.ui.screens.home.MarqueeHomeSkeleton
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.ui.screens.home.rememberMarqueeBandBringIntoViewSpec
import com.nuvio.tv.ui.screens.search.DiscoverRowTitle
import com.nuvio.tv.ui.screens.search.MarqueeResultsHero
import com.nuvio.tv.ui.screens.search.catalogGridFastScroll
import com.nuvio.tv.ui.screens.search.catalogGridItems
import com.nuvio.tv.ui.screens.search.GridBandSkeleton
import com.nuvio.tv.ui.screens.search.gridBandGeometry
import com.nuvio.tv.ui.screens.search.rememberCatalogGridBand
import com.nuvio.tv.ui.screens.search.rememberResultsHeroState
import com.nuvio.tv.ui.screens.tmdb.TmdbKeyNeededMessage
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.requestFocusAfterFrames

@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBack: () -> Unit,
    onAddTmdbKey: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val followLayoutFocusState by viewModel.followLayoutFocusState.collectAsStateWithLifecycle()
    val folder = uiState.folder

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (folder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.folder_detail_not_found), color = NuvioTheme.colors.TextSecondary)
        }
        return
    }

    val enrichingItemId by viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val enrichedPreviews by viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds by viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val trailerPreviewUrls by viewModel.trailerPreviewUrls.collectAsStateWithLifecycle()
    val trailerPreviewAudioUrls by viewModel.trailerPreviewAudioUrls.collectAsStateWithLifecycle()
    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    // Each mode draws it: a Details open from the options marks the Tabs band's return first.
    val posterOptionsHost: @Composable (onLeavingForDetail: () -> Unit) -> Unit = { onLeavingForDetail ->
        PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onLeavingForDetail()
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }

    // Spec 28 Q1: stored ROWS and FOLLOW_LAYOUT both show as Home's rows.
    if (uiState.viewMode.displayMode == FolderViewMode.ROWS) {
        FolderRowsContent(
            uiState = uiState,
            focusState = followLayoutFocusState,
            enrichingItemId = enrichingItemId,
            enrichedPreviews = enrichedPreviews,
            failedEnrichmentIds = failedEnrichmentIds,
            onNavigateToDetail = onNavigateToDetail,
            onLoadMoreCatalog = viewModel::loadMoreForCatalog,
            onRetrySourceLoad = viewModel::retry,
            onAddTmdbKey = onAddTmdbKey,
            onBack = onBack,
            onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
                viewModel.saveFollowLayoutFocusState(vi, vo, rk, ikm, m, ri, ii)
            },
            onItemFocus = viewModel::onItemFocused,
            onPreloadAdjacentItem = viewModel::preloadAdjacentItem,
            onCatalogItemLongPress = { item, addonBaseUrl ->
                viewModel.posterOptions.show(item, addonBaseUrl)
            },
            trailerPreviewUrls = trailerPreviewUrls,
            trailerPreviewAudioUrls = trailerPreviewAudioUrls,
            onRequestTrailerPreview = viewModel::requestTrailerPreview
        )
        posterOptionsHost {}
    } else {
        // Spec 96 PD2: the Tabs grid opts into the trailer preview (In the card mode decides in the host).
        CompositionLocalProvider(LocalTrailerPreviewEnabled provides true) {
            FolderTabsContent(
                uiState = uiState,
                onSelectTab = viewModel::selectTab,
                onLoadMore = viewModel::loadMoreItems,
                onRetryTab = viewModel::retrySource,
                onAddTmdbKey = onAddTmdbKey,
                onBack = onBack,
                onPrefetch = viewModel::preloadAdjacentItem,
                onLongPress = { item, addonBaseUrl -> viewModel.posterOptions.show(item, addonBaseUrl) },
                onNavigateToDetail = onNavigateToDetail,
                posterOptionsHost = posterOptionsHost
            )
        }
    }
}

/** A card's watched tick from the folder's movie watched status. */
@Composable
private fun rememberFolderWatched(status: Map<String, Boolean>): (MetaPreview) -> Boolean =
    remember(status) { { item -> status[homeItemStatusKey(item.id, item.apiType)] == true } }

/** What a Tabs-mode tab shows (stages 1, 3, 4; NO_KEY: Spec 52 T1). */
internal enum class FolderTabState { GRID, LOADING, NO_KEY, FAILED, EMPTY }

/**
 * [GRID] once its row has a card (a later page error keeps the grid), else [LOADING] while the
 * first page loads, [NO_KEY] for a TMDB source with no key set, [FAILED] with another error
 * (Retry), [EMPTY] when it settled with nothing.
 */
internal fun FolderTab.tabState(): FolderTabState = when {
    catalogRow?.items?.isNotEmpty() == true -> FolderTabState.GRID
    isLoading -> FolderTabState.LOADING
    error != null && needsTmdbKey -> FolderTabState.NO_KEY
    error != null -> FolderTabState.FAILED
    else -> FolderTabState.EMPTY
}

/** The tab's type suffix (stage 1): Movie / Series localized, an addon type as declared; none for All. */
@Composable
private fun folderTabTypeSuffix(tab: FolderTab): String? = when {
    tab.isAllTab || tab.typeLabel.isBlank() -> null
    tab.rawType.equals("movie", ignoreCase = true) -> stringResource(R.string.type_movie)
    tab.rawType.equals("series", ignoreCase = true) -> stringResource(R.string.type_series)
    else -> tab.typeLabel
}

// The tab row's list item, then the band lead; grid row 0 follows.
private const val FOLDER_TABS_FIRST_GRID_ITEM = 2

/**
 * Tabs display mode (Spec 28 stages 1, 3, 4): the breadcrumb, the source tabs (All first,
 * select on focus) and the selected tab's row as a grid band ([rememberCatalogGridBand]) under
 * its hero and row title. A tab without cards shows the skeleton, its error with Retry (never
 * focused on its own: the tabs select on focus), the no-key message (Spec 52 T1, same focus rule)
 * or "Nothing here yet". Tab switches start the
 * band at the top; no per-tab focus memory (Library's tabs do the same).
 */
@Composable
private fun FolderTabsContent(
    uiState: FolderDetailUiState,
    onSelectTab: (Int) -> Unit,
    onLoadMore: (Int) -> Unit,
    onRetryTab: (Int) -> Unit,
    onAddTmdbKey: () -> Unit,
    onBack: () -> Unit,
    onPrefetch: (MetaPreview) -> Unit,
    onLongPress: (MetaPreview, String) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    posterOptionsHost: @Composable (onLeavingForDetail: () -> Unit) -> Unit
) {
    val breadcrumb = folderBreadcrumb(uiState)
    if (uiState.tabs.isEmpty()) {
        FolderStateMessage(
            breadcrumb = breadcrumb,
            title = stringResource(R.string.folder_detail_no_sources_title),
            body = stringResource(R.string.folder_detail_no_sources_body)
        )
        return
    }
    val tabIndex = uiState.selectedTabIndex.coerceIn(0, uiState.tabs.lastIndex)
    val tab = uiState.tabs[tabIndex]
    val tabState = tab.tabState()
    val showsGrid = tabState == FolderTabState.GRID
    val landscapeSetting = uiState.layoutPrefs?.modernLandscapePostersEnabled == true
    val isItemWatched = rememberFolderWatched(uiState.movieWatchedStatus)

    val listState = rememberLazyListState()
    val tabRequesters = remember(uiState.tabs.size) { List(uiState.tabs.size) { FocusRequester() } }
    val selectedTabRequester = tabRequesters[tabIndex]
    val band = rememberCatalogGridBand(
        row = tab.catalogRow,
        rowKey = "folder_tab_$tabIndex",
        loading = tab.isLoading,
        landscapeSetting = landscapeSetting,
        listState = listState,
        firstGridItem = FOLDER_TABS_FIRST_GRID_ITEM,
        active = showsGrid,
        headerFocusRequester = selectedTabRequester,
        isItemWatched = isItemWatched,
        onLoadMore = { onLoadMore(tabIndex) },
        onPrefetch = onPrefetch,
        onLongPress = onLongPress,
        onOpenDetail = { meta, addonBaseUrl ->
            HeroBackdropState.updateForTitle(meta.id, meta.apiType, meta.backdropUrl)
            onNavigateToDetail(meta.id, meta.apiType, addonBaseUrl)
        }
    )
    // A tab that settles without cards on the first open: its tab takes focus (no card will).
    val settledWithoutCards = tabState == FolderTabState.FAILED || tabState == FolderTabState.EMPTY ||
        tabState == FolderTabState.NO_KEY
    LaunchedEffect(settledWithoutCards) {
        if (settledWithoutCards && band.focusPending) selectedTabRequester.requestFocusAfterFrames()
    }

    // The folder is pushed (no top bar): the tabs sit where Library's do under it (y 76).
    val marquee = NuvioTheme.marquee
    val form = band.grid.form
    val geometry = gridBandGeometry(listTop = marquee.topBar.contentTop, form = form)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val bandSpec = rememberMarqueeBandBringIntoViewSpec(listState, geometry.bandBottomInset)
    val anchorInsetPx = with(LocalDensity.current) { geometry.bandBottomInset.roundToPx() }
    val hero = rememberResultsHeroState(settledItem = band.settledItem.value)

    var listHasFocus by remember { mutableStateOf(false) }
    var tabRowHasFocus by remember { mutableStateOf(false) }
    // Back from the grid returns to the selected tab; from the tabs it leaves the folder.
    BackHandler(enabled = showsGrid && listHasFocus && !tabRowHasFocus) { band.backToHeader() }

    Box(modifier = Modifier.fillMaxSize()) {
        when (tabState) {
            FolderTabState.GRID -> {
                MarqueeResultsHero(
                    hero = hero,
                    heroBottomInset = geometry.heroBottomInset,
                    heroMaxHeight = geometry.heroMaxHeight,
                    // No top bar: the breadcrumb sits on the art.
                    drawTopScrim = true
                )
                DiscoverRowTitle(
                    title = tab.label,
                    subtitle = listOfNotNull(
                        folderTabTypeSuffix(tab),
                        tab.catalogRow?.addonName?.takeIf { !tab.isAllTab && it.isNotBlank() }
                    ).joinToString(FACT_SEPARATOR),
                    modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = geometry.rowTitleTop)
                )
            }
            FolderTabState.LOADING -> GridBandSkeleton(form)
            FolderTabState.FAILED -> FolderMessageLayer(
                eyebrow = listOfNotNull(tab.label, folderTabTypeSuffix(tab)).joinToString(FACT_SEPARATOR),
                title = stringResource(R.string.folder_detail_tab_error_title, tab.label),
                body = tab.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.folder_detail_load_error_message),
                // Retry leaves with the message: focus goes back to the (already selected) tab.
                onRetry = {
                    runCatching { selectedTabRequester.requestFocus() }
                    onRetryTab(tabIndex)
                },
                // The tabs select on focus: a focused Retry would pull focus off them.
                retryTakesFocus = false
            )
            // Not focused on its own either: the tabs select on focus (Down reaches the buttons).
            FolderTabState.NO_KEY -> TmdbKeyNeededMessage(
                eyebrow = stringResource(R.string.collections_header),
                onAddKey = onAddTmdbKey,
                onBack = onBack,
                takesFocus = false
            )
            FolderTabState.EMPTY -> FolderMessageLayer(title = stringResource(R.string.folder_detail_empty_title))
        }
        MarqueeBreadcrumb(breadcrumb)
        // Drawn after the layer: the message and the skeleton paint the whole screen black.
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides bandSpec,
            LocalFastScrollActive provides band.isFastScrolling
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // The list clips under the breadcrumb: the tabs leave upward as the grid moves.
                    .padding(top = geometry.listTop)
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    .then(if (showsGrid) Modifier.catalogGridFastScroll(band, anchorInsetPx) else Modifier.dpadRepeatThrottle()),
                state = listState,
                contentPadding = PaddingValues(
                    top = marquee.library.tabRowTop,
                    // The last grid row can still reach the band anchor.
                    bottom = geometry.bandBottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(geometry.itemGap)
            ) {
                item(key = "folder_tabs", contentType = "folder_tabs") {
                    // The band spec is vertical: the tabs scroll sideways as usual.
                    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                        FolderTabRow(
                            tabs = uiState.tabs,
                            selectedIndex = tabIndex,
                            showTypeSuffix = uiState.catalogTypeSuffixEnabled,
                            tabRequesters = tabRequesters,
                            onSelect = onSelectTab,
                            onTabFocused = band::cancelInitialFocus,
                            modifier = Modifier.onFocusChanged { tabRowHasFocus = it.hasFocus }
                        )
                    }
                }
                if (showsGrid) {
                    item(key = "folder_band_lead") { Spacer(modifier = Modifier.height(geometry.bandLeadHeight)) }
                    catalogGridItems(band, hero.accent)
                }
            }
        }
    }
    posterOptionsHost(band::onLeavingForDetail)
}

/**
 * The source tabs (stage 1; Library's tab row pattern): select on focus (they are views), the
 * type suffix after the label when the setting is on; scrolls sideways when the tabs overflow.
 * Entering the row lands on the selected tab.
 */
@Composable
private fun FolderTabRow(
    tabs: List<FolderTab>,
    selectedIndex: Int,
    showTypeSuffix: Boolean,
    tabRequesters: List<FocusRequester>,
    onSelect: (Int) -> Unit,
    onTabFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(marquee.search.filterPillHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
            .focusRestorer(tabRequesters[selectedIndex])
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(marquee.library.tabGap)
    ) {
        tabs.forEachIndexed { index, tab ->
            key(index) {
                MarqueeTab(
                    label = tab.label,
                    count = if (showTypeSuffix) folderTabTypeSuffix(tab) else null,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .focusRequester(tabRequesters[index])
                        .onFocusChanged {
                            if (it.isFocused) {
                                onTabFocused()
                                onSelect(index)
                            }
                        }
                )
            }
        }
    }
}

/** The breadcrumb: collection › folder (blank parts dropped). */
private fun folderBreadcrumb(uiState: FolderDetailUiState): List<String> = listOfNotNull(
    uiState.collectionTitle.takeIf { it.isNotBlank() },
    uiState.folder?.title?.takeIf { it.isNotBlank() }
)

/**
 * Rows display mode (Spec 28 stage 2 = the U1 folder view): Home's Marquee rows, hero and
 * trailers via [MarqueeHomeContent] with the breadcrumb and no Continue Watching. Before rows
 * exist it draws its own states (stages 3-4; MHC draws nothing without rows): Home's skeleton
 * while sources load, else a message for a failure (Retry, focused), an empty folder or a
 * folder without catalogs.
 */
@Composable
private fun FolderRowsContent(
    uiState: FolderDetailUiState,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    failedEnrichmentIds: Set<String> = emptySet(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit = { _, _, _ -> },
    onRetrySourceLoad: () -> Unit = {},
    onAddTmdbKey: () -> Unit = {},
    onBack: () -> Unit = {},
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onRequestTrailerPreview: (String, String, String?, String) -> Unit = { _, _, _, _ -> }
) {
    val homeState = uiState.followLayoutHomeState
    val breadcrumb = folderBreadcrumb(uiState)

    when (val rowsState = uiState.rowsState()) {
        FolderRowsState.NO_SOURCES -> {
            FolderStateMessage(
                breadcrumb = breadcrumb,
                title = stringResource(R.string.folder_detail_no_sources_title),
                body = stringResource(R.string.folder_detail_no_sources_body)
            )
            return
        }
        FolderRowsState.NO_KEY -> {
            TmdbKeyNeededMessage(
                eyebrow = stringResource(R.string.collections_header),
                onAddKey = onAddTmdbKey,
                onBack = onBack,
                breadcrumb = breadcrumb
            )
            return
        }
        FolderRowsState.FAILED -> {
            FolderStateMessage(
                breadcrumb = breadcrumb,
                title = stringResource(R.string.folder_detail_load_error_title),
                body = stringResource(R.string.folder_detail_load_error_message),
                onRetry = onRetrySourceLoad
            )
            return
        }
        FolderRowsState.EMPTY -> {
            FolderStateMessage(breadcrumb = breadcrumb, title = stringResource(R.string.folder_detail_empty_title))
            return
        }
        // The rows build runs off the main thread, so rows may lag the first loaded source;
        // before any source has items the build holds shimmer rows only, so LOADING never shows it.
        FolderRowsState.LOADING, FolderRowsState.ROWS -> if (
            rowsState == FolderRowsState.LOADING ||
            homeState == null ||
            homeState.modernHomePresentation.rows.list.isEmpty()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MarqueeHomeSkeleton(landscape = uiState.layoutPrefs?.modernLandscapePostersEnabled == true)
                MarqueeBreadcrumb(breadcrumb)
            }
            return
        }
    }
    val noOpCwClick: (ContinueWatchingItem) -> Unit = remember { { } }
    val noOpRemoveCw: (String, Int?, Int?, Boolean) -> Unit = remember { { _, _, _, _ -> } }
    val noOpFolderDetail: (String, String) -> Unit = remember { { _, _ -> } }
    val isItemWatched = rememberFolderWatched(homeState.movieWatchedStatus)

    MarqueeHomeContent(
        uiState = homeState,
        modernPresentation = homeState.modernHomePresentation,
        focusState = focusState,
        enrichingItemId = enrichingItemId,
        enrichedPreviews = enrichedPreviews,
        failedEnrichmentIds = failedEnrichmentIds,
        trailerPreviewUrls = trailerPreviewUrls,
        trailerPreviewAudioUrls = trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = noOpCwClick,
        onRequestTrailerPreview = onRequestTrailerPreview,
        onLoadMoreCatalog = onLoadMoreCatalog,
        onRemoveContinueWatching = noOpRemoveCw,
        isCatalogItemWatched = isItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = noOpFolderDetail,
        onItemFocus = onItemFocus,
        onPreloadAdjacentItem = onPreloadAdjacentItem,
        onSaveFocusState = onSaveFocusState,
        topLine = { MarqueeBreadcrumb(breadcrumb) }
    )
}

/** A folder state in Home's message block (Spec 28 Q8) under the breadcrumb; Retry takes focus. */
@Composable
private fun FolderStateMessage(
    breadcrumb: List<String>,
    title: String,
    body: String? = null,
    onRetry: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        FolderMessageLayer(title = title, body = body, onRetry = onRetry)
        MarqueeBreadcrumb(breadcrumb)
    }
}

/**
 * Home's message block, full screen (it paints black). With [onRetry] a Retry button, focused
 * when [retryTakesFocus] (nothing else on screen is focusable).
 */
@Composable
private fun FolderMessageLayer(
    title: String,
    eyebrow: String? = null,
    body: String? = null,
    onRetry: (() -> Unit)? = null,
    retryTakesFocus: Boolean = true
) {
    val retryFocusRequester = remember { FocusRequester() }
    MarqueeMessage(
        eyebrow = eyebrow,
        title = title,
        body = body?.let(::AnnotatedString),
        actionLabel = onRetry?.let { stringResource(R.string.action_retry) },
        actionIcon = Icons.Default.Refresh,
        onAction = onRetry,
        focusRequester = retryFocusRequester.takeIf { onRetry != null && retryTakesFocus }
    )
}
