@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.metrics.performance.PerformanceMetricsState
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalTopBarFocused
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.nuvio.tv.domain.model.isPlaceholderItemId

/**
 * Vertical cache window for the row band, so a D-pad press never composes the incoming row on
 * the press frame. The band's viewport is the full screen with the hidden rows above the anchor
 * already composed, so a fraction of it (about one row each way) matches the old 1f/1f window
 * over the half-height Modern viewport. Confirm with gfxinfo/meminfo in the Shield window (S17).
 */
private val MARQUEE_ROWS_CACHE_WINDOW = LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.35f)

private const val TRAILER_REQUEST_DELAY_MS = 150L
// Focus dwell before the hero trailer plays: the retired "Backdrop Expand Delay" default (U1c).
private const val TRAILER_START_DELAY_MS = 3_000L

/**
 * Marquee Home (wave U slice U1c; docs/design/marquee-design-system-2026-10-06.md §Screens
 * "Home"): the settled item's backdrop full-bleed under the scrims, a bottom-anchored hero, and
 * one row band whose focused row sits at a fixed anchor while rows above it scroll out under the
 * hero. Also renders a pushed screen's rows (collection folder, studio / network page: no CW), whose
 * [topLine] (a breadcrumb or a context line) is drawn in place of the top bar over the top scrim.
 */
@Composable
fun MarqueeHomeContent(
    uiState: HomeUiState,
    modernPresentation: ModernHomePresentationState,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    failedEnrichmentIds: Set<String> = emptySet(),
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onMarkContinueWatchingWatched: (ContinueWatchingItem) -> Unit = {},
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {},
    onRowItemFocusedCallback: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    topLine: (@Composable () -> Unit)? = null,
    // CloudStream Home section: rows built from the selected provider's main page
    // (empty when no CS3 plugin is runnable) and the card-click handler.
    cloudStreamRows: StableList<HeroCarouselRow> = StableList(),
    onCloudStreamCardClick: (String, String) -> Unit = { _, _ -> }
) {
    val topBarFocused = LocalTopBarFocused.current
    val topBarFocusedState = rememberUpdatedState(topBarFocused)
    val lifecycleOwner = LocalLifecycleOwner.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    // Trailer autoplay plays behind the hero only (Marquee layout verdicts: EXPANDED_CARD retires).
    val autoplayEnabled = uiState.focusedPosterBackdropTrailerEnabled
    val continueWatchingPoster = uiState.continueWatchingCardStyle == ContinueWatchingCardStyle.POSTER
    // Home rows = addon catalog rows + the CloudStream section (appended last).
    // Everything below (hero, focus, lookups) works off this merged list.
    val carouselRows = remember(modernPresentation.rows, cloudStreamRows) {
        if (cloudStreamRows.list.isEmpty()) {
            modernPresentation.rows
        } else {
            (modernPresentation.rows.list + cloudStreamRows.list).asStable()
        }
    }

    val hasCollections = remember(uiState.homeRows) { uiState.homeRows.any { it is HomeRow.CollectionRow } }
    if (hasCollections && uiState.catalogRows.isEmpty() && uiState.installedAddonsCount > 0 && uiState.isLoading) {
        MarqueeHomeSkeleton(landscape = homeSkeletonLandscape(uiState, continueWatchingPending = false))
        return
    }
    if (carouselRows.list.isEmpty()) return

    // Lookups merged the same way: CS3 rows are absent from the pipeline's
    // lookups, so focus restore, active-row tracking and the stale-selection
    // check would otherwise treat them as unknown.
    val lookups = remember(carouselRows, modernPresentation.lookups) {
        val base = modernPresentation.lookups
        if (cloudStreamRows.list.isEmpty()) {
            base
        } else {
            val rows = carouselRows.list
            CarouselRowLookups(
                rowIndexByKey = (base.rowIndexByKey.map +
                    rows.mapIndexed { index, row -> row.key to index }.toMap()).asStable(),
                rowByKey = (base.rowByKey.map + rows.associateBy { it.key }).asStable(),
                rowKeyByGlobalRowIndex = (base.rowKeyByGlobalRowIndex.map +
                    rows.associate { it.globalRowIndex to it.key }).asStable(),
                activeRowKeys = (base.activeRowKeys.set + rows.map { it.key }).asStable(),
                activeCatalogItemIds = (base.activeCatalogItemIds.set +
                    rows.flatMap { row ->
                        row.items.list.mapNotNull {
                            (it.payload as? ModernPayload.Catalog)?.itemId
                        }
                    }).asStable(),
            )
        }
    }
    val rowIndexByKey = lookups.rowIndexByKey
    val rowByKey = lookups.rowByKey

    val verticalRowListState = rememberLazyListState(
        cacheWindow = MARQUEE_ROWS_CACHE_WINDOW,
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrollingState = remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }
    val rowListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val loadMoreRequestedTotals = remember { mutableStateMapOf<String, Int>() }
    val focusedItemByRow = remember { mutableStateMapOf<String, Int>() }
    if (focusedItemByRow.isEmpty() && focusState.hasSavedFocus) {
        // Every row's card is saved on leave, so every row gets it back, not only the focused one.
        focusedItemByRow.putAll(savedRowFocusIndices(rowByKey, focusState.focusedItemKeyByRow))
    }

    // Band focus state shared with MarqueeHomeRowsList; the aliases below are the same objects.
    val focus = remember { MarqueeBandFocus() }
    val activeRowKey = focus.activeRowKey
    val activeItemIndex = focus.activeItemIndex
    val pendingRowFocusKey = focus.pendingRowFocusKey
    val pendingRowFocusIndex = focus.pendingRowFocusIndex
    val pendingRowFocusNonce = focus.pendingRowFocusNonce
    val restoredFromSavedState = remember { mutableStateOf(false) }
    // The hero shows the SETTLED item (focus debounce), keyed so rebuilt rows refresh its data.
    val settledRowKey = remember { mutableStateOf(carouselRows.list.firstOrNull()?.key) }
    val settledItemKey = remember { mutableStateOf(carouselRows.list.firstOrNull()?.items?.list?.firstOrNull()?.key) }
    val optionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndex = focus.lastFocusedContinueWatchingIndex
    val lastHeroNavigationAtMs = focus.lastHeroNavigationAtMs
    val heroFocusSettleDelayMs = focus.heroFocusSettleDelayMs
    val focusedCatalogSelection = focus.focusedCatalogSelection
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    // Catalog selection whose trailer may play (armed after the start delay).
    val trailerFocusKey = remember { mutableStateOf<String?>(null) }
    val focusedHeroMediaNonce = focus.focusedHeroMediaNonce
    var endedCollectionHeroVideoPlaybackKey by remember { mutableStateOf<String?>(null) }

    fun settleOn(rowKey: String, itemKey: String?) {
        settledRowKey.value = rowKey
        settledItemKey.value = itemKey
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) verticalRowListState.scrollToItem(0, 0)
    }

    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(LocalView.current)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling -> metricsHolder.state?.putState("HomeScrolling", scrolling.toString()) }
    }
    LaunchedEffect(enrichingItemId) {
        metricsHolder.state?.putState("HeroEnriching", (enrichingItemId != null).toString())
    }

    // Arm the hero trailer once a catalog card has held focus for the start delay; the top bar
    // taking focus disarms it (#2815).
    LaunchedEffect(
        focusedCatalogSelection.value?.focusKey,
        autoplayEnabled,
        verticalRowListState.isScrollInProgress,
        topBarFocused
    ) {
        trailerFocusKey.value = null
        if (!autoplayEnabled || topBarFocused || verticalRowListState.isScrollInProgress) return@LaunchedEffect
        val selection = focusedCatalogSelection.value ?: return@LaunchedEffect
        if (selection.payload !is ModernPayload.Catalog) return@LaunchedEffect
        delay(TRAILER_START_DELAY_MS)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (!topBarFocusedState.value &&
            !verticalRowListState.isScrollInProgress &&
            focusedCatalogSelection.value?.focusKey == selection.focusKey
        ) {
            trailerFocusKey.value = selection.focusKey
        }
    }

    LaunchedEffect(focusedCatalogSelection.value?.focusKey, autoplayEnabled, verticalRowListState.isScrollInProgress) {
        if (!autoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
        val selection = focusedCatalogSelection.value
        val payload = selection?.payload as? ModernPayload.Catalog
        if (selection == null || payload == null) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (selection.focusKey == lastRequestedTrailerFocusKey) return@LaunchedEffect
        delay(TRAILER_REQUEST_DELAY_MS)
        if (focusedCatalogSelection.value?.focusKey != selection.focusKey) return@LaunchedEffect
        onRequestTrailerPreview(payload.itemId, payload.trailerTitle, payload.trailerReleaseInfo, payload.trailerApiType)
        lastRequestedTrailerFocusKey = selection.focusKey
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus) {
        rowListStates.keys.retainAll(lookups.activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(lookups.activeRowKeys)
        val staleSelection = focusedCatalogSelection.value?.let { selection ->
            when (val payload = selection.payload) {
                is ModernPayload.Catalog ->
                    !payload.itemId.isPlaceholderItemId() && payload.itemId !in lookups.activeCatalogItemIds.set
                is ModernPayload.CollectionFolder -> carouselRows.list.none { row ->
                    row.items.list.any { (it.payload as? ModernPayload.CollectionFolder)?.focusKey == selection.focusKey }
                }
                is ModernPayload.ContinueWatching -> true
            }
        } ?: false
        if (staleSelection) {
            focusedCatalogSelection.value = null
            trailerFocusKey.value = null
        }
        val currentCatalogPayload = focusedCatalogSelection.value?.payload as? ModernPayload.Catalog
        if (currentCatalogPayload != null && currentCatalogPayload.itemId.isPlaceholderItemId()) {
            val realPayload = activeRowKey.value?.let { rowByKey[it] }
                ?.items?.list?.getOrNull(activeItemIndex.intValue)?.payload as? ModernPayload.Catalog
            if (realPayload != null && !realPayload.itemId.isPlaceholderItemId()) {
                focusedCatalogSelection.value = FocusedCatalogSelection(realPayload.focusKey, realPayload)
                lastRequestedTrailerFocusKey = null
                trailerFocusKey.value = null
            }
        }

        carouselRows.list.forEach { row ->
            if (row.items.list.isNotEmpty() && row.key !in focusedItemByRow) focusedItemByRow[row.key] = 0
        }

        if (!restoredFromSavedState.value && focusState.hasSavedFocus) {
            val savedRowKey = focusState.focusedRowKey ?: when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingEnabled &&
                    uiState.continueWatchingItems.isNotEmpty() -> MODERN_CONTINUE_WATCHING_ROW_KEY
                focusState.focusedRowIndex >= 0 -> lookups.rowKeyByGlobalRowIndex.map[focusState.focusedRowIndex]
                else -> null
            }
            val resolvedRow = savedRowKey?.let { rowByKey[it] } ?: carouselRows.list.firstOrNull()
            if (resolvedRow != null) {
                val savedItemKey = focusState.focusedItemKeyByRow[resolvedRow.key]
                val resolvedIndex = if (savedItemKey != null) {
                    resolvedRow.items.list.indexOfFirst { it.key == savedItemKey }.coerceAtLeast(0)
                } else {
                    focusState.focusedItemIndex.coerceIn(0, (resolvedRow.items.list.size - 1).coerceAtLeast(0))
                }
                activeRowKey.value = resolvedRow.key
                activeItemIndex.intValue = resolvedIndex
                focusedItemByRow[resolvedRow.key] = resolvedIndex
                settleOn(resolvedRow.key, resolvedRow.items.list.getOrNull(resolvedIndex)?.key)
                pendingRowFocusKey.value = resolvedRow.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
                restoredFromSavedState.value = true
                return@LaunchedEffect
            }
        }

        val hadActiveRow = activeRowKey.value != null
        val existingActive = activeRowKey.value?.let { rowByKey[it] }
        val resolvedActive = when {
            existingActive != null -> existingActive
            hadActiveRow -> null // Wait for the row to reappear.
            else -> carouselRows.list.firstOrNull()
        }
        if (resolvedActive != null) {
            val resolvedIndex = focusedItemByRow[resolvedActive.key]
                ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0)) ?: 0
            activeRowKey.value = resolvedActive.key
            activeItemIndex.intValue = resolvedIndex
            focusedItemByRow[resolvedActive.key] = resolvedIndex
            if (!hadActiveRow) settleOn(resolvedActive.key, resolvedActive.items.list.getOrNull(resolvedIndex)?.key)
            if (!focusState.hasSavedFocus && !hadActiveRow) {
                pendingRowFocusKey.value = resolvedActive.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
            }
        }
        if (!restoredFromSavedState.value) restoredFromSavedState.value = true
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) return@LaunchedEffect
        if (targetIndex > 0 || targetOffset > 0) verticalRowListState.scrollToItem(targetIndex, targetOffset)
    }

    val activeRow by remember(carouselRows, rowByKey) {
        derivedStateOf { activeRowKey.value?.let { rowByKey[it] } }
    }
    val activeRowIndexState = remember(rowIndexByKey) {
        derivedStateOf { activeRowKey.value?.let { rowIndexByKey[it] } ?: 0 }
    }
    val clampedActiveItemIndex by remember(activeRow) {
        derivedStateOf {
            activeRow?.let { activeItemIndex.intValue.coerceIn(0, (it.items.size - 1).coerceAtLeast(0)) } ?: 0
        }
    }
    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = (focusedItemByRow[row.key] ?: 0).coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (activeItemIndex.intValue != clampedIndex) activeItemIndex.intValue = clampedIndex
        focusedItemByRow[row.key] = clampedIndex
    }
    val activeItemKey by remember(activeRow, clampedActiveItemIndex) {
        derivedStateOf {
            val row = activeRow ?: return@derivedStateOf null
            row.items.getOrNull(clampedActiveItemIndex)?.key ?: row.items.firstOrNull()?.key
        }
    }

    // Settle: after the focus debounce AND the vertical scroll-in (which can outlast it), the
    // hero follows the focused card and the item's enrichment/prefetch starts (nt2: never
    // mid-scroll, which janked).
    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveIndex by rememberUpdatedState(clampedActiveItemIndex)
    // Keyed on the item id too: a placeholder turning into real data keeps its item key, and the
    // real item still needs its settle (enrichment, hero).
    val activeItemId = activeRow?.items?.getOrNull(clampedActiveItemIndex)?.metaPreview?.id
    LaunchedEffect(activeItemKey, activeItemId, verticalRowListState) {
        val targetKey = activeItemKey ?: return@LaunchedEffect
        val settleDelayMs = heroFocusSettleDelayMs.longValue
        delay(settleDelayMs)
        snapshotFlow { verticalRowListState.isScrollInProgress }.first { !it }
        if (System.currentTimeMillis() - lastHeroNavigationAtMs.longValue < settleDelayMs) return@LaunchedEffect
        val row = latestActiveRow ?: return@LaunchedEffect
        val item = row.items.getOrNull(latestActiveIndex) ?: row.items.firstOrNull() ?: return@LaunchedEffect
        if (item.key != targetKey) return@LaunchedEffect
        settleOn(row.key, item.key)
        item.metaPreview?.let(onItemFocus)
    }

    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    val latestRowIndexByKey = rememberUpdatedState(rowIndexByKey)
    DisposableEffect(Unit) {
        onDispose {
            // Only save when Home actually had a focused row; never overwrite with a bar-only state.
            val focusedRowKey = latestActiveRow?.key ?: return@onDispose
            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowKey,
                latestCarouselRows.associate { row ->
                    row.key to (row.items.list.getOrNull(focusedItemByRow[row.key] ?: 0)?.key ?: "")
                },
                latestCarouselRows.associate { row ->
                    row.key to (rowListStates[row.key]?.firstVisibleItemIndex ?: (focusedItemByRow[row.key] ?: 0))
                },
                latestRowIndexByKey.value.map[focusedRowKey] ?: -1,
                activeItemIndex.intValue
            )
        }
    }

    // ---- Hero: settled item + enrichment ----
    val settledRow = settledRowKey.value?.let { rowByKey[it] } ?: activeRow
    val settledItem = remember(settledRow, settledItemKey.value) {
        settledRow?.items?.list?.firstOrNull { it.key == settledItemKey.value } ?: settledRow?.items?.firstOrNull()
    }
    val settledId = settledItem?.metaPreview?.id
    val enrichedItem = settledId?.let { enrichedPreviews[it] }
    val heroPreview = settledItem?.heroPreview?.withEnrichment(enrichedItem)
    // With enrichment on, addon data is not shown first and then swapped: the hero text waits
    // for the enriched copy (or its failure), and the backdrop holds the previous image.
    val enrichmentPending = settledId != null && (
        enrichingItemId == settledId ||
            (uiState.heroEnrichmentEnabled && enrichedItem == null && settledId !in failedEnrichmentIds)
        )
    val rowFallbackBackdrop = remember(settledRow) {
        settledRow?.items?.list?.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
        }
    }
    // A settled placeholder card has only placeholder art: hold the previous backdrop.
    val candidateBackdrop = firstNonBlank(heroBackdropOf(heroPreview), rowFallbackBackdrop)
    var displayedBackdrop by remember { mutableStateOf(HeroBackdropState.lastDisplayedUrl ?: candidateBackdrop) }
    if (!enrichmentPending && candidateBackdrop != null && candidateBackdrop != displayedBackdrop) {
        displayedBackdrop = candidateBackdrop
    }
    val accentProvider = rememberAmbientAccentProvider(displayedBackdrop)

    // ---- Hero media: catalog trailer or collection folder hero video, behind the hero ----
    val scrollStopped = !verticalRowListState.isScrollInProgress
    val folderSelection = focusedCatalogSelection.value?.payload as? ModernPayload.CollectionFolder
    val folderVideoUrl = folderSelection?.heroVideoUrl?.takeIf { it.isNotBlank() }
    val folderVideoKey = remember(focusedCatalogSelection.value?.focusKey, folderVideoUrl, focusedHeroMediaNonce.intValue) {
        val focusKey = focusedCatalogSelection.value?.focusKey
        if (focusKey != null && folderVideoUrl != null) "$focusKey::${focusedHeroMediaNonce.intValue}::$folderVideoUrl" else null
    }
    val playFolderVideo = scrollStopped && !topBarFocused && folderVideoKey != null &&
        endedCollectionHeroVideoPlaybackKey != folderVideoKey
    val trailerItemId = focusedCatalogSelection.value
        ?.takeIf { it.focusKey == trailerFocusKey.value }
        ?.let { (it.payload as? ModernPayload.Catalog)?.itemId }
    val trailerUrl = trailerItemId?.let { trailerPreviewUrls[it] }
    val playTrailer = scrollStopped && autoplayEnabled && !topBarFocused && !trailerUrl.isNullOrBlank()
    val heroMedia = HeroMediaState(
        url = if (playFolderVideo) folderVideoUrl else trailerUrl,
        audioUrl = if (playFolderVideo) null else trailerItemId?.let { trailerPreviewAudioUrls[it] },
        playbackKey = if (playFolderVideo) folderVideoKey else trailerUrl,
        muted = uiState.focusedPosterBackdropTrailerMuted,
        play = playFolderVideo || playTrailer
    )
    val heroMediaState = rememberUpdatedState(heroMedia)

    // ---- Geometry: row band anchor + bottom-anchored hero (Marquee spec §Screens "Home") ----
    val home = NuvioTheme.marquee.home
    val density = LocalDensity.current
    // Frame rule (BC-D1): real window pixels through the current (scaled) density.
    val screenHeight = with(density) { LocalContext.current.resources.displayMetrics.heightPixels.toDp() }
    val bandBottomInset = home.focusedCardBottomInset
    val rowTitleHeight = with(density) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    fun rowHeight(row: HeroCarouselRow?) = rowTitleHeight + home.rowTitleGap +
        marqueeBandCardHeight(row, useLandscapePosters, continueWatchingPoster, home)
    val settledRowHeight = remember(settledRow, useLandscapePosters, continueWatchingPoster, rowTitleHeight) { rowHeight(settledRow) }
    val firstRow = carouselRows.list.firstOrNull()
    val topPadding = remember(firstRow, useLandscapePosters, continueWatchingPoster, screenHeight) {
        (screenHeight - bandBottomInset - rowHeight(firstRow)).coerceAtLeast(0.dp)
    }
    val heroBottom = screenHeight - bandBottomInset - settledRowHeight - home.heroAboveRow

    val verticalRowBringIntoViewSpec = rememberMarqueeBandBringIntoViewSpec(verticalRowListState, bandBottomInset)

    // P32: Details opens on the CLICKED title's art; the hero shows the debounced settled item.
    val latestEnrichedPreviews by rememberUpdatedState(enrichedPreviews)
    val navigateToDetailWithArt = remember(onNavigateToDetail) {
        { itemId: String, itemType: String, addonBaseUrl: String ->
            HeroBackdropState.updateForTitle(
                itemId, itemType, clickedTitleBackdrop(latestCarouselRows, itemId, latestEnrichedPreviews)
            )
            onNavigateToDetail(itemId, itemType, addonBaseUrl)
        }
    }
    // CS3 Home cards never open Detail (their ids are not Stremio meta ids);
    // they resolve to a Stream-screen play target instead.
    val navigateToDetailOrCloudStream = remember(navigateToDetailWithArt, onCloudStreamCardClick) {
        { itemId: String, itemType: String, addonBaseUrl: String ->
            if (itemId.startsWith(CloudStreamStreamSource.CS3_VIDEO_ID_PREFIX)) {
                onCloudStreamCardClick(itemId, itemType)
            } else {
                navigateToDetailWithArt(itemId, itemType, addonBaseUrl)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeBackdrop(
            backdropUrl = displayedBackdrop,
            media = { heroMediaState.value },
            drawTopScrim = topLine != null,
            onMediaEnded = {
                if (playFolderVideo) {
                    endedCollectionHeroVideoPlaybackKey = folderVideoKey
                } else {
                    trailerFocusKey.value = null
                }
            }
        )

        topLine?.invoke()

        if (heroPreview != null && !enrichmentPending) {
            MarqueeHeroBlock(
                content = MarqueeHeroContent(
                    preview = heroPreview,
                    // No row label: the row title right above already names it (Arshad
                    // 2026-10-22); each item shows its own type eyebrow, if any.
                    eyebrow = null,
                    progressFraction = settledItem.progressFraction
                ),
                accent = accentProvider,
                showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = (screenHeight - heroBottom).coerceAtLeast(0.dp))
                    .heightIn(max = (heroBottom - home.heroMinTop).coerceAtLeast(0.dp))
            )
        }

        MarqueeHomeRowsList(
            carouselRows = carouselRows,
            verticalRowListState = verticalRowListState,
            isVerticalRowsScrollingState = isVerticalRowsScrollingState,
            focusedItemByRow = remember { StableRef<MutableMap<String, Int>>(focusedItemByRow) },
            rowListStates = remember { StableRef<MutableMap<String, LazyListState>>(rowListStates) },
            loadMoreRequestedTotals = remember { StableRef<MutableMap<String, Int>>(loadMoreRequestedTotals) },
            focusState = focusState,
            focus = focus,
            activeRowIndex = activeRowIndexState,
            contentFocusRequester = LocalContentFocusRequester.current,
            topPadding = topPadding,
            bottomPadding = bandBottomInset,
            verticalRowBringIntoViewSpec = verticalRowBringIntoViewSpec,
            useLandscapePosters = useLandscapePosters,
            continueWatchingCardStyle = uiState.continueWatchingCardStyle,
            blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            accent = accentProvider,
            isCatalogItemWatched = isCatalogItemWatched,
            onCatalogItemLongPress = onCatalogItemLongPress,
            onPreloadAdjacentItem = onPreloadAdjacentItem,
            onNavigateToDetail = navigateToDetailOrCloudStream,
            onNavigateToFolderDetail = onNavigateToFolderDetail,
            onLoadMoreCatalog = onLoadMoreCatalog,
            onContinueWatchingClick = onContinueWatchingClick,
            onContinueWatchingOptions = remember { { item: ContinueWatchingItem -> optionsItem.value = item } },
            onRequestLazyCatalogLoad = onRequestLazyCatalogLoad,
            onRowItemFocused = onRowItemFocusedCallback
        )
    }

    val selectedOptionsItem = optionsItem.value
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem.value = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) null
                else lastFocusedContinueWatchingIndex.intValue
                    .coerceAtMost(uiState.continueWatchingItems.size - 2)
                    .coerceAtLeast(0)
                pendingRowFocusKey.value = if (targetIndex != null) MODERN_CONTINUE_WATCHING_ROW_KEY else null
                pendingRowFocusIndex.value = targetIndex
                pendingRowFocusNonce.intValue++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem.value = null
            },
            onDetails = {
                navigateToDetailWithArt(selectedOptionsItem.contentId(), selectedOptionsItem.contentType(), "")
                optionsItem.value = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem.value = null
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem.value = null
            },
            onMarkWatched = {
                // Spec 62 H4: focus stays at the same index through the active row's remembered
                // index, no pending target: whichever card lands there takes focus (a Next Up
                // sorts to the front, so it is that card only when the marked one was first),
                // clamped to the new last card when the last one leaves.
                onMarkContinueWatchingWatched(selectedOptionsItem)
                optionsItem.value = null
            }
        )
    }
}
