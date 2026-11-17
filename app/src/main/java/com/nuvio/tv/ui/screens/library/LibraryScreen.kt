@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeFilterPill
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.MarqueeTab
import com.nuvio.tv.ui.components.marquee.titleTypeEyebrow
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.screens.detail.formatMonth
import com.nuvio.tv.ui.screens.home.MarqueeHomeSkeleton
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.screens.home.rememberMarqueeBandBringIntoViewSpec
import com.nuvio.tv.ui.screens.search.DiscoverRowTitle
import com.nuvio.tv.ui.screens.search.MarqueeResultsHero
import com.nuvio.tv.ui.screens.search.rememberResultsHeroState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.formatAddonTypeLabel
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Library (approved sandbox `u7-library-marquee` v3, Spec 27): the tab row at y 76 under the top
 * bar with the Sort & filter pill at its end, then Home's band of shelves under a results hero for
 * the focused title (stages 1-3), or the Sort & filter grid in their place (stages 4-6), or on
 * the History tab the same band over the watch history (stage 7); the skeleton while the first
 * sync runs (stage 11) and the message block for an empty library, an empty grid (stage 9) or an
 * empty history (stage 10). Always a top-bar tab: content starts at the bar's `contentTop`.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val shelfRows = remember(uiState.shelves, context) { libraryShelfRows(context, uiState.shelves) }
    val isHistory = uiState.isHistory
    // Grouped here against today (read per composition), so a resident Library regroups when it
    // shows again on another day.
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val historyRows = remember(isHistory, uiState.history, uiState.hiddenNextUp, uiState.allItems, today, context) {
        if (isHistory) {
            libraryHistoryRows(
                context,
                libraryHistoryGroups(uiState.history, ZonedDateTime.now(zone)),
                uiState.hiddenNextUp,
                uiState.allItems,
                zone
            )
        } else emptyList()
    }
    // The band shows the shelves, or History's rows (stage 7) on the History tab.
    val bandRows = if (isHistory) historyRows else shelfRows
    // Back from Details: the band restores the saved card; nothing else takes focus first.
    val restoring = remember { mutableStateOf(viewModel.hasSavedFocus) }
    val endRestore = {
        if (restoring.value) {
            restoring.value = false
            viewModel.hasSavedFocus = false
            viewModel.savedRowScrollPositions = emptyMap()
        }
    }
    val band = rememberLibraryShelvesBand(
        viewModel = viewModel,
        listState = listState,
        rows = bandRows,
        shownTypeKey = uiState.activeTypeKey,
        onCardFocused = endRestore,
        onNavigateToDetail = onNavigateToDetail,
        // A Hidden card's OK (stage 8): back in Continue Watching, confirmed by a toast.
        onRestore = { keys ->
            viewModel.restoreNextUp(keys)
            Toast.makeText(context, context.getString(R.string.library_hidden_restored), Toast.LENGTH_SHORT).show()
        }
    )
    val showsGrid = uiState.showsGrid
    val gridContent = remember(uiState.visibleItems, uiState.landscapePostersEnabled) {
        libraryGridContent(uiState.visibleItems, uiState.landscapePostersEnabled)
    }
    val gridQueryKey = uiState.gridQueryKey
    val grid = rememberLibraryGridState(
        viewModel = viewModel,
        listState = listState,
        content = gridContent,
        queryKey = gridQueryKey,
        onCardFocused = endRestore,
        onNavigateToDetail = onNavigateToDetail
    )
    val tabRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val selectedTabRequester = tabRequesters.getOrPut(uiState.activeTypeKey) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var panelAnchor by remember { mutableStateOf<Rect?>(null) }

    // A new tab: the hero starts from its first shelf.
    LaunchedEffect(uiState.activeTypeKey) { band.onTabShown(uiState.activeTypeKey) }
    // A new grid list starts at the top; a See all focuses its first card.
    LaunchedEffect(gridQueryKey, showsGrid) { grid.onQuery(gridQueryKey, showsGrid) }

    // Initial focus (CMP-FOCUS-01), once per visit: the selected tab while the first sync runs;
    // then, back from Details, the saved card (its shelf may have shrunk or gone); else the first
    // shelf's first card (stage 1), or the tab when the library is empty (stage 9). Over the grid:
    // the saved grid card, else the pill.
    var initialFocusDone by remember { mutableStateOf(false) }
    val firstShelfKey = band.rows.firstOrNull()?.row?.key
    val restoreRow = band.rows.firstOrNull { restoring.value && it.row.key == viewModel.savedFocusRowKey }?.row
    val restoreGridCell = viewModel.savedFocusItemIndex.takeIf {
        restoring.value && viewModel.savedFocusRowKey == LIBRARY_GRID_KEY
    }
    LaunchedEffect(uiState.isLoading, showsGrid, firstShelfKey, restoreRow) {
        if (initialFocusDone) return@LaunchedEffect
        val gridSize = grid.grid.items.size
        when {
            uiState.isLoading -> {
                selectedTabRequester.requestFocusAfterFrames()
                return@LaunchedEffect
            }
            showsGrid && restoreGridCell != null && gridSize > 0 -> grid.focusCell(restoreGridCell.coerceAtMost(gridSize - 1))
            showsGrid -> {
                // No card takes focus: a pending restore ends here.
                endRestore()
                pillRequester.requestFocusAfterFrames()
            }
            restoreRow != null -> band.focus.requestPendingFocus(
                restoreRow.key,
                viewModel.savedFocusItemIndex.coerceAtMost(restoreRow.items.list.lastIndex)
            )
            firstShelfKey != null -> band.focus.requestPendingFocus(firstShelfKey, 0)
            else -> {
                endRestore()
                selectedTabRequester.requestFocusAfterFrames()
            }
        }
        initialFocusDone = true
    }
    // The focused card left the band (marked watched, removed from the library or the history,
    // restored from Hidden): focus takes the card at its place, or the tab once no row is left.
    LaunchedEffect(bandRows) {
        if (initialFocusDone && !band.refocusIfCardLeft()) selectedTabRequester.requestFocusAfterFrames()
    }
    // The same over the grid; the pill once no card is left (stage 9: "Nothing matches").
    LaunchedEffect(gridContent, showsGrid) {
        if (initialFocusDone && !grid.refocusIfCardLeft(showsGrid)) pillRequester.requestFocusAfterFrames()
    }

    // Back from the grid returns to the pill (stage 6).
    var listHasFocus by remember { mutableStateOf(false) }
    var tabRowHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = showsGrid && listHasFocus && !tabRowHasFocus) {
        scope.launch {
            grid.backToTop()
            pillRequester.requestFocusAfterFrames()
        }
    }

    // The grid's settled title, else the shelves' (opening the grid keeps the hero, stage 4).
    val gridHeroItem = if (showsGrid) grid.settledItem else null
    val heroItem = gridHeroItem ?: band.settledItem.value
    val heroEntry = if (gridHeroItem != null) grid.settledEntry else band.settledEntry.value
    val historyById = remember(uiState.history) { uiState.history.associateBy { it.id } }
    val heroId = heroEntry?.id ?: heroItem?.metaPreview?.id
    val hero = rememberResultsHeroState(
        settledItem = heroItem,
        eyebrow = libraryHeroEyebrow(
            item = heroItem,
            entry = heroEntry,
            watchFactLoaded = uiState.watchFactLoaded,
            watched = heroId?.let { historyById[it] },
            watchCount = heroId?.let { uiState.watchCounts?.get(it) } ?: 0,
            today = today,
            zone = zone
        )
    )
    // History's cards say how they were watched in their caption (no tick, stage 7).
    val shelfWatched = rememberLibraryWatchedPredicate(uiState.watchedMovieIds, uiState.watchedSeriesIds)
    val isItemWatched: (MetaPreview) -> Boolean = if (isHistory) NEVER_WATCHED else shelfWatched

    // History's rows are landscape (stage 7); the shelves follow the Card shape setting.
    val useLandscapePosters = isHistory || uiState.landscapePostersEnabled
    val geometry = libraryGeometry(
        showsGrid = showsGrid,
        gridForm = gridContent.grid.form,
        firstRow = band.rows.firstOrNull()?.row,
        settledRow = band.settledRow.value,
        landscapeRows = useLandscapePosters,
        captionedCards = isHistory
    )
    val marquee = NuvioTheme.marquee
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val bandSpec = rememberMarqueeBandBringIntoViewSpec(listState, geometry.bandBottomInset)
    val showShelves = !uiState.isLoading && !showsGrid && band.rows.isNotEmpty()
    val hasGridCards = !uiState.isLoading && showsGrid && gridContent.grid.items.isNotEmpty()
    val seeAllTitle = uiState.seeAllShelfKey?.let { key -> shelfRows.firstOrNull { it.row.key == key }?.row?.title }

    Box(modifier = Modifier.fillMaxSize()) {
        val heroLayer: @Composable () -> Unit = {
            MarqueeResultsHero(
                hero = hero,
                heroBottomInset = geometry.heroBottomInset,
                heroMaxHeight = geometry.heroMaxHeight,
                // The top bar draws the top scrim.
                drawTopScrim = false
            )
        }
        when {
            uiState.isLoading -> MarqueeHomeSkeleton(landscape = useLandscapePosters, compact = true)
            showsGrid && !hasGridCards -> MarqueeMessage(
                eyebrow = stringResource(R.string.nav_library),
                title = stringResource(R.string.library_filter_empty_title),
                body = AnnotatedString(stringResource(R.string.library_filter_empty_body))
            )
            showsGrid -> {
                heroLayer()
                val count = gridContent.grid.items.size
                DiscoverRowTitle(
                    title = seeAllTitle
                        ?: libraryFilterChoices(uiState, withSort = false).joinToString(FACT_SEPARATOR)
                            .ifEmpty { libraryTabLabel(uiState.activeTypeKey) },
                    subtitle = listOf(
                        pluralStringResource(R.plurals.library_title_count, count, count),
                        stringResource(uiState.selectedSortOption.labelResId)
                    ).joinToString(FACT_SEPARATOR),
                    modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = geometry.gridRowTitleTop)
                )
            }
            // Nothing watched yet (stage 10); focus stays on the tab.
            band.rows.isEmpty() && isHistory -> MarqueeMessage(
                eyebrow = stringResource(R.string.library_tab_history),
                title = stringResource(R.string.library_history_empty_title),
                body = AnnotatedString(stringResource(R.string.library_history_empty_body))
            )
            band.rows.isEmpty() -> MarqueeMessage(
                eyebrow = stringResource(R.string.nav_library),
                title = stringResource(R.string.library_empty_local_title, stringResource(R.string.library_type_items)),
                body = AnnotatedString(stringResource(R.string.library_empty_local_subtitle))
            )
            else -> heroLayer()
        }
        // A focused card's bottom goes to the band anchor (shelves move as on Home). Spec 96 PD2: the shelves and
        // grid opt into the trailer preview (the host's In the card mode decides; MarqueeRowItem leaves out Hidden
        // and History cards).
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides bandSpec,
            LocalTrailerPreviewEnabled provides true
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // The list starts below the bar and clips there: the tab row and the shelves
                    // above the focused one leave upward (stage 3).
                    .padding(top = geometry.listTop)
                    // Down from the top bar lands here (TopBarScaffold); Compose enters the tab row.
                    .focusRequester(LocalContentFocusRequester.current)
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    .dpadRepeatThrottle(),
                state = listState,
                contentPadding = PaddingValues(
                    top = marquee.library.tabRowTop,
                    // The last row can still reach the band anchor.
                    bottom = geometry.bandBottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(geometry.itemGap)
            ) {
                item(key = "library_tabs", contentType = "library_tabs") {
                    LibraryTabRow(
                        tabs = uiState.typeTabs,
                        selectedKey = uiState.activeTypeKey,
                        sourceLabel = stringResource(
                            if (uiState.isNuvioAccount) R.string.library_source_nuvio else R.string.library_source_local
                        ),
                        tabRequesters = tabRequesters,
                        onSelect = viewModel::onSelectTypeTab,
                        pillValue = seeAllTitle
                            ?: libraryFilterChoices(uiState, withSort = true).joinToString(FACT_SEPARATOR)
                                .ifEmpty { stringResource(R.string.library_filter_shelves) },
                        pillRequester = pillRequester,
                        onOpenPanel = { panelAnchor = it },
                        modifier = Modifier.onFocusChanged {
                            tabRowHasFocus = it.hasFocus
                            if (it.hasFocus) {
                                band.releaseFocusedCard()
                                grid.releaseFocusedCard()
                            }
                        }
                    )
                }
                if (showShelves || hasGridCards) {
                    item(key = "library_band_lead") { Spacer(modifier = Modifier.height(geometry.bandLeadHeight)) }
                }
                if (showShelves) {
                    libraryShelfItems(
                        band = band,
                        useLandscapePosters = useLandscapePosters,
                        accent = hero.accent,
                        isItemWatched = isItemWatched,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        onSeeAll = grid.openSeeAll.takeUnless { isHistory },
                        cardCaptions = isHistory
                    )
                }
                if (hasGridCards) libraryGridItems(grid, hero.accent, isItemWatched)
            }
        }
    }

    panelAnchor?.let { anchor ->
        LibraryFilterPanel(
            anchor = anchor,
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = {
                panelAnchor = null
                scope.launch { pillRequester.requestFocusAfterFrames() }
            }
        )
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        },
        onRemoveFromHistory = if (isHistory) viewModel::removeFromHistory else null
    )
}

private val NEVER_WATCHED: (MetaPreview) -> Boolean = { false }

/**
 * The hero eyebrow (stages 1, 3, 7; U4, U15, U1): the title's type eyebrow, when it was added
 * ("Added 2 Oct"), then its watch fact ([libraryWatchFact]) once [watchFactLoaded]. Null without a
 * settled card (the hero then draws nothing).
 */
@Composable
private fun libraryHeroEyebrow(
    item: ModernCarouselItem?,
    entry: LibraryEntry?,
    watchFactLoaded: Boolean,
    watched: LibraryHistoryEntry?,
    watchCount: Int,
    today: LocalDate,
    zone: ZoneId
): String? {
    val preview = item?.heroPreview ?: return null
    val type = preview.apiType?.let { titleTypeEyebrow(it, preview.isSeries, preview.seasonCount, preview.status) }
    val addedAt = entry?.listedAt?.takeIf { it > 0L }
    val added = addedAt?.let {
        stringResource(R.string.library_hero_added, formatDayMonth(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()))
    }
    val watchFact = if (watchFactLoaded) libraryWatchFact(isSeriesType(preview.apiType), watched, watchCount, today, zone) else null
    return listOfNotNull(type?.takeIf { it.isNotBlank() }, added, watchFact).joinToString(FACT_SEPARATOR).ifEmpty { null }
}

/**
 * How the title was watched, from its History entry [watched] (the latest play or mark): a series
 * names its latest episode ("S1 E2, 3 days ago"), a movie its plays to the end on this device
 * ([watchCount]: "Watched 2×, 3 days ago", "Watched once, in August"), else an unfinished play
 * ("35%, yesterday") or a mark ("Watched today"). Not in the History: "Not started" (series) or
 * "Not watched", whatever the count (Remove from history leaves the count).
 */
@Composable
private fun libraryWatchFact(
    isSeries: Boolean,
    watched: LibraryHistoryEntry?,
    watchCount: Int,
    today: LocalDate,
    zone: ZoneId
): String {
    if (watched == null) {
        return stringResource(if (isSeries) R.string.library_hero_not_started else R.string.library_hero_not_watched)
    }
    val ago = when (val at = libraryWatchedAgo(watched.watchedAt, today, zone)) {
        LibraryWatchedAgo.Today -> stringResource(R.string.library_ago_today)
        LibraryWatchedAgo.Yesterday -> stringResource(R.string.library_ago_yesterday)
        is LibraryWatchedAgo.Days -> pluralStringResource(R.plurals.library_ago_days, at.days, at.days)
        is LibraryWatchedAgo.InMonth -> stringResource(R.string.library_ago_in_month, formatMonth(at.month, at.withYear))
    }
    val context = LocalContext.current
    val episode = if (isSeries) watched.episodeLabel(context) else null
    val played = watched.playedLabel(context)
    return when {
        episode != null -> stringResource(R.string.library_hero_fact_when, episode, ago)
        watchCount == 1 -> stringResource(R.string.library_hero_watched_once, ago)
        watchCount > 1 -> stringResource(R.string.library_hero_watched_times, watchCount, ago)
        played != null -> stringResource(R.string.library_hero_fact_when, played, ago)
        else -> stringResource(R.string.library_history_watched_when, ago)
    }
}

/**
 * The tab row (stages 1, 2, 5, 7): one tab per library type with its count, then History (no
 * count), select on focus (they are views, Detail's rule), then the library source (NUVIO or LOCAL) and the Sort & filter pill
 * ([pillValue]: "Shelves" or the active choices) at the end. Entering the tabs lands on the
 * selected one; Right from the last tab reaches the pill, whose OK calls [onOpenPanel] with its
 * window bounds.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LibraryTabRow(
    tabs: List<LibraryTypeTab>,
    selectedKey: String,
    sourceLabel: String,
    tabRequesters: MutableMap<String, FocusRequester>,
    onSelect: (String) -> Unit,
    pillValue: String,
    pillRequester: FocusRequester,
    onOpenPanel: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(marquee.search.filterPillHeight)
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .focusRestorer(tabRequesters.getOrPut(selectedKey) { FocusRequester() })
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(marquee.library.tabGap)
        ) {
            // An empty library still has its All and History tabs (stage 9); History has no count.
            tabs.forEach { tab ->
                key(tab.key) {
                    MarqueeTab(
                        label = libraryTabLabel(tab.key),
                        count = tab.count.takeIf { it > 0 }?.toString(),
                        selected = tab.key == selectedKey,
                        onClick = { onSelect(tab.key) },
                        modifier = Modifier
                            .focusRequester(tabRequesters.getOrPut(tab.key) { FocusRequester() })
                            .onFocusChanged { if (it.isFocused) onSelect(tab.key) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = sourceLabel,
            style = NuvioTheme.textStyles.eyebrow,
            color = marquee.onImage3,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(marquee.library.tabGap))
        MarqueeFilterPill(
            label = stringResource(R.string.library_sort_and_filter),
            value = pillValue,
            pillRequester = pillRequester,
            onOpen = onOpenPanel,
            // Nothing to its right.
            modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
        )
    }
}

@Composable
private fun libraryTabLabel(key: String): String = when (key) {
    LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_type_all)
    LibraryTypeTab.HISTORY_KEY -> stringResource(R.string.library_tab_history)
    "movie" -> stringResource(R.string.type_movies)
    "series", "tv" -> stringResource(R.string.type_series_plural)
    else -> formatAddonTypeLabel(key)
}
