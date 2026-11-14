@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.imageLoader
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.marquee.MarqueeActionTile
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.recompositionHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import com.nuvio.tv.domain.model.isPlaceholderItemId
import kotlin.math.abs

private const val POSTER_PREFETCH_DISTANCE = 5
private const val NESTED_PREFETCH_COUNT = 2

// Vertical poster prefetch: rows just outside the viewport on BOTH flanks get their leading
// posters decoded ahead of arrival (direction-agnostic). 2 rows x 8 items is roughly one
// viewport-width of posters per warmed row on a 1080p UI.
private const val VERTICAL_POSTER_PREFETCH_AHEAD_ROWS = 2
private const val VERTICAL_POSTER_PREFETCH_ITEMS_PER_ROW = 8

// Room drawn outside a row's cards for the focused card's 1.06 scale, ring and glow; the row
// still reports only its cards' height, so the band geometry is unchanged.
private val ROW_FOCUS_OVERFLOW_TOP = 8.dp
private val ROW_FOCUS_OVERFLOW_BOTTOM = 32.dp

internal val LocalVerticalRowsScrolling = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * True while the user is fast-scrolling (DPAD held, the column-level handler drags the list
 * instead of moving focus card by card). Cards drop their focus chrome during the drag; it
 * returns on whichever card focus lands on when the key is released.
 */
internal val LocalFastScrollActive = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * Row-band focus state, one per band host: Home's [MarqueeHomeContent] (hero settle, trailer
 * arming, focus save/restore, written by [MarqueeHomeRowsList]) or Search's results. Cards write
 * it through [MarqueeRowSection].
 */
@Stable
internal class MarqueeBandFocus {
    val activeRowKey = mutableStateOf<String?>(null)
    val activeItemIndex = mutableIntStateOf(0)
    val pendingRowFocusKey = mutableStateOf<String?>(null)
    val pendingRowFocusIndex = mutableStateOf<Int?>(null)
    val pendingRowFocusNonce = mutableIntStateOf(0)
    val lastHeroNavigationAtMs = mutableLongStateOf(0L)
    val heroFocusSettleDelayMs = mutableLongStateOf(MODERN_HERO_FOCUS_DEBOUNCE_MS)
    val isFastScrolling = mutableStateOf(false)
    val lastFocusedContinueWatchingIndex = mutableIntStateOf(-1)
    val focusedCatalogSelection = mutableStateOf<FocusedCatalogSelection?>(null)
    val focusedHeroMediaNonce = mutableIntStateOf(0)

    fun clearPendingRowFocus() {
        pendingRowFocusKey.value = null
        pendingRowFocusIndex.value = null
    }

    /** Moves focus to the card (or trailing tile) at [index] of [rowKey] once it is composed. */
    fun requestPendingFocus(rowKey: String, index: Int) {
        pendingRowFocusKey.value = rowKey
        pendingRowFocusIndex.value = index
        pendingRowFocusNonce.intValue++
    }

    /**
     * The card (or trailing tile) at [index] of [rowKey] took focus: it becomes the band's active
     * card (hero settle pacing restarts when it changed) and its row's remembered index.
     */
    fun recordItemFocus(rowKey: String, index: Int, focusedItemByRow: MutableMap<String, Int>) {
        val rowBecameActive = activeRowKey.value != rowKey
        val itemChanged = activeItemIndex.intValue != index
        if (rowBecameActive || itemChanged) {
            val now = System.currentTimeMillis()
            heroFocusSettleDelayMs.longValue = heroSettleDelayMs(lastHeroNavigationAtMs.longValue, now)
            lastHeroNavigationAtMs.longValue = now
            activeRowKey.value = rowKey
            activeItemIndex.intValue = index
        }
        if (focusedItemByRow[rowKey] != index) focusedItemByRow[rowKey] = index
    }
}

/**
 * A row's trailing action tile after its last card (Search's See All, approved U4 QU4-4; Library's
 * See all with its [detail] count, u7 stage 4): card sized, focus index = the row's item count.
 * Identity-compared: callers remember one per row.
 */
@Stable
internal class MarqueeRowTile(
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val onClick: () -> Unit
)

/**
 * Vertical bring-into-view for a row band: the focused card's bottom edge goes to the band anchor,
 * [bottomInset] above the bottom of [listState]'s viewport.
 */
@Composable
internal fun rememberMarqueeBandBringIntoViewSpec(listState: LazyListState, bottomInset: Dp): BringIntoViewSpec {
    val inset = remember(bottomInset) { { bottomInset } }
    return rememberMarqueeBandBringIntoViewSpec(listState, inset)
}

/**
 * The band spec with an anchor that moves (Search's compact hero, Spec 38 Q4 B): [bottomInset] is
 * read on every calculation, so a focus move that also changes the anchor lands there in one scroll.
 */
@Composable
internal fun rememberMarqueeBandBringIntoViewSpec(listState: LazyListState, bottomInset: () -> Dp): BringIntoViewSpec {
    val density = LocalDensity.current
    val defaultSpec = LocalBringIntoViewSpec.current
    return remember(density, defaultSpec, bottomInset, listState) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val bottomInsetPx = with(density) { bottomInset().toPx() }
                val distance = offset + size - (containerSize - bottomInsetPx)
                if (abs(distance) < 1f) return 0f
                // Never fight the scroll bounds (first-row jank from impossible upward scrolls).
                if (distance < 0f && !listState.canScrollBackward) return 0f
                return distance
            }
        }
    }
}

@Composable
internal fun MarqueeHomeRowsList(
    carouselRows: StableList<HeroCarouselRow>,
    verticalRowListState: LazyListState,
    isVerticalRowsScrollingState: State<Boolean>,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    focusState: HomeScreenFocusState,
    focus: MarqueeBandFocus,
    activeRowIndex: State<Int>,
    contentFocusRequester: FocusRequester,
    topPadding: Dp,
    bottomPadding: Dp,
    verticalRowBringIntoViewSpec: BringIntoViewSpec,
    useLandscapePosters: Boolean,
    continueWatchingCardStyle: ContinueWatchingCardStyle,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    accent: () -> Color,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusedItemByRowMap = focusedItemByRow.value
    val latestOnRowItemFocused = rememberUpdatedState(onRowItemFocused)
    val activeRowKey: State<String?> = focus.activeRowKey
    val onFastScrollingChanged = remember(focus) { { scrolling: Boolean -> focus.isFastScrolling.value = scrolling } }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val itemFocusRequestersByRow = remember { mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>() }

    val home = NuvioTheme.marquee.home
    val density = LocalDensity.current
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val continueWatchingPoster = continueWatchingCardStyle == ContinueWatchingCardStyle.POSTER
    val latestRowsForPrefetch = rememberUpdatedState(carouselRows)

    LaunchedEffect(imageLoader, verticalRowListState, density, useLandscapePosters, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes) {
        snapshotFlow {
            val visible = verticalRowListState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: -1) to (visible.lastOrNull()?.index ?: -1)
        }
            .distinctUntilChanged()
            .debounce(120L)
            .collect { (firstVisibleRowIndex, lastVisibleRowIndex) ->
                withContext(Dispatchers.IO) {
                    fun warmRowLeadingItems(row: HeroCarouselRow?) {
                        if (row == null) return
                        val landscapeRow = row.usesLandscapeCards(useLandscapePosters)
                        for (i in 0 until minOf(VERTICAL_POSTER_PREFETCH_ITEMS_PER_ROW, row.items.list.size)) {
                            val spec = row.items.list[i].marqueeImageRequestSpec(
                                landscapeRow, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes, home
                            ) ?: continue
                            enqueueMarqueeImage(imageLoader, context, density, spec)
                        }
                    }
                    val rows = latestRowsForPrefetch.value.list
                    for (rowOffset in 1..VERTICAL_POSTER_PREFETCH_AHEAD_ROWS) {
                        warmRowLeadingItems(rows.getOrNull(lastVisibleRowIndex + rowOffset))
                        warmRowLeadingItems(rows.getOrNull(firstVisibleRowIndex - rowOffset))
                    }
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            val info = verticalRowListState.layoutInfo
            (info.visibleItemsInfo.firstOrNull()?.index ?: -1) to (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collectLatest { (firstVisible, lastVisible) ->
            if (lastVisible < 0) return@collectLatest
            // Fires only once the visible range holds still for 240 ms (collectLatest restarts).
            delay(240)
            val rows = latestRowsForPrefetch.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + 2)) {
                val row = rows.list.getOrNull(idx) ?: continue
                if (row.isLoading && row.items.list.firstOrNull()?.imageUrl.isPlaceholder()) {
                    latestOnRequestLazyCatalogLoad.value("${row.addonId}_${row.apiType}_${row.catalogId}")
                }
            }
        }
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec,
        LocalFastScrollActive provides focus.isFastScrolling,
        LocalVerticalRowsScrolling provides isVerticalRowsScrollingState
    ) {
        LazyColumn(
            state = verticalRowListState,
            modifier = modifier
                .fillMaxSize()
                .recompositionHighlighter()
                .focusRequester(contentFocusRequester)
                .focusRestorer(run {
                    // Down from the top bar returns to the last card (TopBarScaffold requests
                    // contentFocusRequester): Compose UI 1.11 restores one level, so the column
                    // picks the active row and the row restorer picks the card.
                    // Item keys are composite "<row.key>_<index>"; only a composed row resolves.
                    val plainKey = activeRowKey.value
                    val compositeKey = plainKey?.let { key ->
                        carouselRows.list.indexOfFirst { it.key == key }
                            .takeIf { it >= 0 }
                            ?.let { "${key}_$it" }
                    }
                    if (compositeKey != null &&
                        verticalRowListState.layoutInfo.visibleItemsInfo.any { it.key == compositeKey }
                    ) {
                        rowFocusRequesters[plainKey] ?: FocusRequester.Default
                    } else {
                        FocusRequester.Default
                    }
                })
                .dpadVerticalFastScroll(
                    scrollableState = verticalRowListState,
                    verticalVelocityDpPerSec = 2000f,
                    onFastScrollingChanged = onFastScrollingChanged,
                    shouldHaltForward = {
                        val info = verticalRowListState.layoutInfo
                        val lastIdx = carouselRows.list.size - 1
                        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                        lastIdx >= 0 && lastVisible != null &&
                            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                    },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = verticalRowListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = carouselRows.list.size - 1
                        val lastRowAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= layoutInfo.viewportEndOffset
                            } == true
                        // Rows above the focused one are hidden: land on the row whose cards
                        // sit nearest the band anchor, not the first one in the viewport.
                        val anchorPx = layoutInfo.viewportEndOffset - with(density) { bottomPadding.roundToPx() }
                        val upwardTopRow: LazyListItemInfo? = if (sign < 0) {
                            visibleItems.lastOrNull { it.offset + it.size <= anchorPx }
                        } else null
                        val targetRowIndex = when {
                            lastRowAtBottom -> lastIdx
                            upwardTopRow != null -> upwardTopRow.index
                            else -> visibleItems.firstOrNull { it.offset + it.size >= anchorPx }?.index
                                ?: visibleItems.lastOrNull()?.index
                                ?: verticalRowListState.firstVisibleItemIndex
                        }
                        val targetRow = carouselRows.list.getOrNull(targetRowIndex)
                        if (targetRow == null) null
                        else {
                            val savedIdx = (focusedItemByRowMap[targetRow.key] ?: 0)
                                .coerceIn(0, (targetRow.items.list.size - 1).coerceAtLeast(0))
                            focus.activeRowKey.value = targetRow.key
                            focus.activeItemIndex.intValue = savedIdx
                            targetRow.items.list.getOrNull(savedIdx)?.key ?: "${targetRow.key}_$savedIdx"
                        }
                    },
                ),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(home.rowGap)
        ) {
            itemsIndexed(
                items = carouselRows.list,
                key = { index, row -> "${row.key}_$index" },
                contentType = { _, row -> row.apiType ?: "marquee_home_row" }
            ) { rowIndex, row ->
                val stableOnRowItemFocused = remember {
                    { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                        focus.recordItemFocus(rowKey, index, focusedItemByRowMap)
                        if (isContinueWatchingRow) {
                            focus.lastFocusedContinueWatchingIndex.intValue = index
                            focus.focusedCatalogSelection.value = null
                        }
                        latestOnRowItemFocused.value(rowKey, index, isContinueWatchingRow)
                    }
                }
                val stableOnCatalogSelectionFocused = remember {
                    { selection: FocusedCatalogSelection ->
                        val isCollectionFolder = selection.payload is ModernPayload.CollectionFolder
                        if (focus.focusedCatalogSelection.value != selection || isCollectionFolder) {
                            focus.focusedCatalogSelection.value = selection
                            if (isCollectionFolder) focus.focusedHeroMediaNonce.intValue++
                        }
                    }
                }
                val isActiveRow = remember(row.key) { { row.key == activeRowKey.value } }
                MarqueeRowSection(
                    row = row,
                    rowIndex = rowIndex,
                    activeRowIndex = activeRowIndex,
                    isActiveRow = isActiveRow,
                    rowFocusRequester = rowFocusRequesters.getOrPut(row.key) { FocusRequester() },
                    itemFocusRequesters = itemFocusRequestersByRow.getOrPut(row.key) { StableRef(mutableMapOf()) },
                    defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                    focusStateCatalogRowScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                    focusedItemByRow = focusedItemByRow,
                    rowListStates = rowListStates,
                    loadMoreRequestedTotals = loadMoreRequestedTotals,
                    isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                    focus = focus,
                    onRowItemFocused = stableOnRowItemFocused,
                    onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                    useLandscapePosters = useLandscapePosters,
                    continueWatchingPoster = continueWatchingPoster,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    accent = accent,
                    isCatalogItemWatched = isCatalogItemWatched,
                    onCatalogItemLongPress = onCatalogItemLongPress,
                    onPreloadAdjacentItem = onPreloadAdjacentItem,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToFolderDetail = onNavigateToFolderDetail,
                    onLoadMoreCatalog = onLoadMoreCatalog,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingOptions = onContinueWatchingOptions
                )
            }
        }
    }
}

/**
 * One row of a Marquee band: title (plus the row's `titleSuffix`, the small addon name), then a
 * lazy row of cards whose focus feeds [focus]. Search passes [trailingTile] (See All); Library's
 * History passes [cardCaptions].
 */
@Composable
internal fun MarqueeRowSection(
    row: HeroCarouselRow,
    rowIndex: Int,
    activeRowIndex: State<Int>,
    isActiveRow: () -> Boolean,
    rowFocusRequester: FocusRequester,
    itemFocusRequesters: StableRef<MutableMap<Int, FocusRequester>>,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollIndex: Int,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    isVerticalRowsScrollingState: State<Boolean>,
    focus: MarqueeBandFocus,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    accent: () -> Color,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    // Home-only (Continue Watching, folders, trailers, paging): inert by default.
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit = {},
    continueWatchingPoster: Boolean = false,
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = false,
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onLoadMoreCatalog: (String, String, String) -> Unit = { _, _, _ -> },
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit = {},
    trailingTile: MarqueeRowTile? = null,
    // Library's History: each card's title and when under it (MarqueeRowItem's captioned).
    cardCaptions: Boolean = false,
    // Library's Hidden row: cards dim until focused; the focused one shows [focusChip].
    dimUntilFocused: Boolean = false,
    focusChip: String? = null
) {
    val focusedItemByRowMap = focusedItemByRow.value
    val rowListStatesMap = rowListStates.value
    val loadMoreTotals = loadMoreRequestedTotals.value
    val requesters = itemFocusRequesters.value
    val home = NuvioTheme.marquee.home
    val rowKey = row.key
    // Landscape row form when the setting or every card says so; else each card its own shape (Spec 41).
    val landscapeRow = remember(row.items, useLandscapePosters) { row.usesLandscapeCards(useLandscapePosters) }

    // Per-row derived state: invalidates only when THIS row's focused index changes.
    val rowFocusedIndex = remember(rowKey) { derivedStateOf { focusedItemByRowMap[rowKey] ?: 0 } }

    // The row's last focus target (the trailing tile, index = item count, is one). A State, so
    // the remembered isTargetItem below re-derives when the row shrinks.
    val lastFocusTarget = rememberUpdatedState(
        (row.items.list.size - (if (trailingTile != null) 0 else 1)).coerceAtLeast(0)
    )
    // A pending target past the end (the row shrank after it was set) falls to the new last card.
    fun isPendingTarget(index: Int): Boolean =
        focus.pendingRowFocusKey.value == rowKey &&
            (focus.pendingRowFocusIndex.value ?: 0).coerceAtMost(lastFocusTarget.value) == index

    // One focus wiring for the cards and the trailing tile: the pending card, or the active
    // row's remembered card, is the target; a gain reports it and ends a pending focus on it.
    // The remembered index is clamped too, so when the focused last card leaves (Spec 62 H4:
    // marked watched) the new last card takes focus.
    fun isFocusTarget(index: Int): Boolean =
        isPendingTarget(index) ||
            (isActiveRow() && rowFocusedIndex.value.coerceAtMost(lastFocusTarget.value) == index)
    fun onFocusGained(index: Int, isContinueWatchingRow: Boolean) {
        onRowItemFocused(rowKey, index, isContinueWatchingRow)
        if (isPendingTarget(index)) focus.clearPendingRowFocus()
    }

    // Blocks vertical focus exit for one pass while placeholder cards turn into real data.
    val blockingFocusExit = remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            // Rows above the focused one scroll out under the hero (Marquee spec): hidden, still
            // focusable, so Up reaches them and they reappear at the band anchor.
            .graphicsLayer { alpha = if (rowIndex < activeRowIndex.value) 0f else 1f }
            .then(
                if (blockingFocusExit.value) {
                    Modifier.focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                } else Modifier
            )
    ) {
        val isActive by remember(rowKey, rowIndex) { derivedStateOf { rowIndex == activeRowIndex.value } }
        MarqueeRowTitle(row = row, active = { isActive })

        val rowListState = rowListStatesMap.getOrPut(rowKey) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollIndex,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = NESTED_PREFETCH_COUNT)
            )
        }

        val firstItemImageUrl = row.items.list.firstOrNull()?.imageUrl
        val wasPlaceholder = remember { mutableStateOf(row.isLoading && firstItemImageUrl.isPlaceholder()) }
        LaunchedEffect(row.isLoading, firstItemImageUrl, isActiveRow) {
            val isNowReal = !row.isLoading || !firstItemImageUrl.isPlaceholder()
            val handOff = wasPlaceholder.value && isNowReal && isActiveRow()
            wasPlaceholder.value = row.isLoading && firstItemImageUrl.isPlaceholder()
            if (handOff) {
                blockingFocusExit.value = true
                // One frame later the real cards are composed; release the block even if cancelled.
                try {
                    delay(16)
                } finally {
                    blockingFocusExit.value = false
                }
            }
        }

        val currentRowState = rememberUpdatedState(row)
        val canObserveLoadMore = row.supportsSkip && row.hasMore &&
            !row.catalogId.isNullOrBlank() && !row.addonId.isNullOrBlank() && !row.apiType.isNullOrBlank()

        LaunchedEffect(rowKey, focus.pendingRowFocusNonce.intValue) {
            if (focus.pendingRowFocusKey.value != rowKey) return@LaunchedEffect
            val targetIndex = (focus.pendingRowFocusIndex.value ?: 0).coerceIn(0, lastFocusTarget.value)
            if (!rowListState.isScrollInProgress) runCatching { rowListState.scrollToItem(targetIndex) }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(rowKey, rowListState) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to layoutInfo.totalItemsCount
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        if (lastVisible < total - 4) {
                            loadMoreTotals.remove(rowState.key)
                            return@collect
                        }
                        if (rowState.hasMore && !rowState.isLoading && loadMoreTotals[rowState.key] != total) {
                            loadMoreTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                rowState.catalogId.orEmpty(),
                                rowState.addonId.orEmpty(),
                                rowState.apiType.orEmpty()
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val context = LocalContext.current
        val imageLoader = context.imageLoader
        val rowItemCount = row.items.list.size
        LaunchedEffect(rowKey, rowItemCount, landscapeRow, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes) {
            // Arms whenever this row becomes the focused one and the vertical scroll has stopped.
            snapshotFlow { isActiveRow() && !isVerticalRowsScrollingState.value }
                .distinctUntilChanged()
                .collectLatest { ready ->
                    if (!ready) return@collectLatest
                    delay(150) // Survive rapid vertical D-pad travel before spending image requests.
                    fun enqueueIfNeeded(item: ModernCarouselItem) {
                        item.marqueeImageRequestSpec(
                            landscapeRow, continueWatchingPoster, useEpisodeThumbnails, blurUnwatchedEpisodes, home
                        )?.let { enqueueMarqueeImage(imageLoader, context, density, it) }
                    }
                    val items = currentRowState.value.items.list
                    withContext(Dispatchers.IO) {
                        for (i in 0 until minOf(POSTER_PREFETCH_DISTANCE, items.size)) items.getOrNull(i)?.let(::enqueueIfNeeded)
                    }
                    snapshotFlow {
                        val info = rowListState.layoutInfo
                        (info.visibleItemsInfo.firstOrNull()?.index ?: 0) to (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
                    }
                        .distinctUntilChanged()
                        .collect { (firstVisibleIndex, lastVisibleIndex) ->
                            val currentItems = currentRowState.value.items.list
                            withContext(Dispatchers.IO) {
                                // Both flanks: the return leg of a traversal is warmed like the outbound leg.
                                for (i in (lastVisibleIndex + 1)..(lastVisibleIndex + POSTER_PREFETCH_DISTANCE)) {
                                    currentItems.getOrNull(i)?.let(::enqueueIfNeeded)
                                }
                                for (i in (firstVisibleIndex - POSTER_PREFETCH_DISTANCE) until firstVisibleIndex) {
                                    currentItems.getOrNull(i)?.let(::enqueueIfNeeded)
                                }
                            }
                        }
                }
        }

        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val rowStartPadding = NuvioTheme.layout.tvSafeHorizontal
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl) {
            NuvioScrollDefaults.rtlAwareRowBringIntoViewSpec(
                defaultSpec = defaultBringIntoViewSpec,
                isRtl = isRtl,
                startOffsetPx = with(density) { rowStartPadding.roundToPx() }
            )
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .recompositionHighlighter()
                    .focusOverflow(ROW_FOCUS_OVERFLOW_TOP, ROW_FOCUS_OVERFLOW_BOTTOM)
                    .focusRequester(rowFocusRequester)
                    .focusRestorer(run {
                        // Index-keyed requesters resolve only while composed.
                        val visibleIdx = rowListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                        val idx = rowFocusedIndex.value.takeIf { it in visibleIdx } ?: 0.takeIf { it in visibleIdx }
                        idx?.let { requesters[it] } ?: FocusRequester.Default
                    })
                    .focusGroup(),
                contentPadding = PaddingValues(
                    start = rowStartPadding,
                    end = rowStartPadding,
                    top = ROW_FOCUS_OVERFLOW_TOP,
                    bottom = ROW_FOCUS_OVERFLOW_BOTTOM
                ),
                horizontalArrangement = Arrangement.spacedBy(home.cardGap)
            ) {
                itemsIndexed(
                    items = row.items.list,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (val payload = item.payload) {
                            is ModernPayload.ContinueWatching -> "marquee_cw_card"
                            is ModernPayload.Catalog -> if (payload.itemId.isPlaceholderItemId()) "placeholder" else payload.itemType
                            is ModernPayload.CollectionFolder -> "marquee_folder_card"
                        }
                    }
                ) { index, item ->
                    val requester = requesters.getOrPut(index) { FocusRequester() }
                    val isContinueWatchingRow = rowKey == MODERN_CONTINUE_WATCHING_ROW_KEY || rowKey == MODERN_UPCOMING_ROW_KEY
                    val onFocused = remember(rowKey, index, isContinueWatchingRow) {
                        { onFocusGained(index, isContinueWatchingRow) }
                    }
                    // Only the ONE item that gains/loses target status recomposes.
                    val isTargetItem by remember(rowKey, index) { derivedStateOf { isFocusTarget(index) } }
                    val nextItem = row.items.list.getOrNull(index + 1)?.metaPreview
                    val prevItem = row.items.list.getOrNull(index - 1)?.metaPreview
                    MarqueeRowItem(
                        item = item,
                        requester = requester,
                        isTargetItem = isTargetItem,
                        blockFocus = item.isPlaceholderCard() && index > 0,
                        landscapeRow = landscapeRow,
                        continueWatchingPoster = continueWatchingPoster,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        useEpisodeThumbnails = useEpisodeThumbnails,
                        accent = accent,
                        isCatalogItemWatched = isCatalogItemWatched,
                        captioned = cardCaptions,
                        dimUntilFocused = dimUntilFocused,
                        focusChip = focusChip,
                        onFocused = onFocused,
                        onSettled = {
                            nextItem?.let(onPreloadAdjacentItem)
                            prevItem?.let(onPreloadAdjacentItem)
                            when (val payload = item.payload) {
                                is ModernPayload.Catalog -> if (!payload.itemId.isPlaceholderItemId()) {
                                    onCatalogSelectionFocused(FocusedCatalogSelection(payload.focusKey, payload))
                                }
                                is ModernPayload.CollectionFolder ->
                                    onCatalogSelectionFocused(FocusedCatalogSelection(payload.focusKey, payload))
                                is ModernPayload.ContinueWatching -> Unit
                            }
                        },
                        onClick = {
                            when (val payload = item.payload) {
                                is ModernPayload.ContinueWatching -> onContinueWatchingClick(payload.item)
                                is ModernPayload.Catalog -> if (!payload.itemId.isPlaceholderItemId()) {
                                    onNavigateToDetail(payload.itemId, payload.itemType, payload.addonBaseUrl)
                                }
                                is ModernPayload.CollectionFolder ->
                                    onNavigateToFolderDetail(payload.collectionId, payload.folderId)
                            }
                        },
                        onLongPress = {
                            when (val payload = item.payload) {
                                is ModernPayload.ContinueWatching -> onContinueWatchingOptions(payload.item)
                                is ModernPayload.Catalog -> item.metaPreview?.let { onCatalogItemLongPress(it, payload.addonBaseUrl) }
                                is ModernPayload.CollectionFolder -> Unit
                            }
                        }
                    )
                }
                if (trailingTile != null) {
                    item(key = TRAILING_TILE_KEY, contentType = TRAILING_TILE_KEY) {
                        val tileIndex = row.items.list.size
                        val requester = requesters.getOrPut(tileIndex) { FocusRequester() }
                        var isFocused by remember { mutableStateOf(false) }
                        val isTarget by remember(rowKey, tileIndex) { derivedStateOf { isFocusTarget(tileIndex) } }
                        // As MarqueeRowItem: a card that becomes the target takes focus.
                        LaunchedEffect(isTarget) {
                            if (isTarget && !isFocused) runCatching { requester.requestFocus() }
                        }
                        // The row's plain slot, not the first card's own shape (Spec 41 D2).
                        val size = row.marqueeRowSlotSize(landscapeRow, continueWatchingPoster, home)
                        MarqueeActionTile(
                            label = trailingTile.label,
                            icon = trailingTile.icon,
                            detail = trailingTile.detail,
                            onClick = trailingTile.onClick,
                            width = size.width,
                            height = size.height,
                            accent = accent,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged {
                                    isFocused = it.isFocused
                                    if (it.isFocused) onFocusGained(tileIndex, false)
                                }
                        )
                    }
                }
            }
        }
    }
}

private const val TRAILING_TILE_KEY = "marquee_row_trailing_tile"

/** Lets a lazy row draw [top]/[bottom] outside its reported height (focus scale, ring, glow). */
private fun Modifier.focusOverflow(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val topPx = top.roundToPx()
    val bottomPx = bottom.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - topPx - bottomPx).coerceAtLeast(0)) {
        placeable.place(0, -topPx)
    }
}

/**
 * A band row's title (white while [active], else `onImage2`) and its muted suffix (the addon
 * name), `home.rowTitleGap` above the cards. [active] is read here, so its change recomposes only
 * the title. Also the studio page header's row peek (Spec 29 stage 7).
 */
@Composable
internal fun MarqueeRowTitle(row: HeroCarouselRow, active: () -> Boolean) {
    val marquee = NuvioTheme.marquee
    val titleModifier = Modifier
        .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
        .padding(bottom = marquee.home.rowTitleGap)
    val title: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            text = row.title,
            style = NuvioTheme.textStyles.rowTitle,
            color = if (active()) NuvioTheme.colors.TextPrimary else marquee.onImage2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
    val titleSuffix = row.titleSuffix
    val headerAction = row.headerAction
    if (titleSuffix == null && headerAction == null) {
        title(titleModifier)
    } else {
        Row(
            modifier = titleModifier,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            title(Modifier.alignByBaseline().weight(1f, fill = false))
            if (titleSuffix != null) {
                Text(
                    text = titleSuffix,
                    style = NuvioTheme.textStyles.meta,
                    color = marquee.onImage3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline()
                )
            }
            if (headerAction != null) {
                MarqueeGlassTextButton(
                    label = headerAction.label,
                    onClick = headerAction.onClick,
                    icon = Icons.Filled.Layers,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}
