@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.LocalFastScrollActive
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.screens.home.rememberMarqueeBandBringIntoViewSpec
import com.nuvio.tv.ui.screens.search.DiscoverRowTitle
import com.nuvio.tv.ui.screens.search.MarqueeResultsHero
import com.nuvio.tv.ui.screens.search.SearchEvent
import com.nuvio.tv.ui.screens.search.SearchViewModel
import com.nuvio.tv.ui.screens.search.catalogGridFastScroll
import com.nuvio.tv.ui.screens.search.catalogGridItems
import com.nuvio.tv.ui.screens.search.GridBandSkeleton
import com.nuvio.tv.ui.screens.search.gridBandGeometry
import com.nuvio.tv.ui.screens.search.rememberCatalogGridBand
import com.nuvio.tv.ui.screens.search.rememberResultsHeroState
import com.nuvio.tv.ui.screens.search.rememberWatchedPredicate
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedContentType

/** The Search row a See All grid shows: the row with the route's legacy key. */
internal fun seeAllRow(rows: List<CatalogRow>, addonId: String, type: String, catalogId: String): CatalogRow? {
    val key = catalogRowLegacyKey(addonId, type, catalogId)
    return rows.find { it.legacyKey() == key }
}

// The band lead is the list's item 0; grid row 0 follows.
private const val SEE_ALL_FIRST_GRID_ITEM = 1

/**
 * See All for a Search catalog row (Spec 28 stage 5): the breadcrumb (Search › query › row), the
 * focused title's hero, the row title ("<type> · from <addon>") and the row as a grid band
 * ([rememberCatalogGridBand]: paging, loading tile, Back-from-Details restore). Shares the
 * SearchViewModel of the Search entry it was opened from (same instance), so its pages also show
 * in Search's row and Back lands on the See All card (F-13).
 */
@Composable
fun CatalogSeeAllScreen(
    catalogId: String,
    addonId: String,
    type: String,
    searchViewModel: SearchViewModel,
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val searchUiState by searchViewModel.uiState.collectAsState()
    val isItemWatched = rememberWatchedPredicate(searchViewModel.watchedMovieIds, searchViewModel.watchedSeriesIds)

    BackHandler { onBackPress() }

    val catalogKey = catalogRowLegacyKey(addonId, type, catalogId)
    val catalogRow = seeAllRow(searchUiState.catalogRows, addonId, type, catalogId)
    val hasItems = catalogRow?.items?.isNotEmpty() == true
    val loading = catalogRow == null || catalogRow.isLoading

    val listState = rememberLazyListState()
    val band = rememberCatalogGridBand(
        row = catalogRow,
        rowKey = catalogKey,
        loading = loading,
        landscapeSetting = searchUiState.landscapePostersEnabled,
        listState = listState,
        firstGridItem = SEE_ALL_FIRST_GRID_ITEM,
        active = hasItems,
        // No header: Back from the grid leaves See All.
        headerFocusRequester = null,
        isItemWatched = isItemWatched,
        onLoadMore = {
            catalogRow?.let { searchViewModel.onEvent(SearchEvent.LoadMoreCatalog(it.catalogId, it.addonId, it.apiType)) }
        },
        onPrefetch = { searchViewModel.prefetchMetaOnFocus(it.id, it.apiType) },
        onLongPress = searchViewModel.posterOptions::show,
        onOpenDetail = { meta, addonBaseUrl ->
            HeroBackdropState.updateForTitle(meta.id, meta.apiType, meta.backdropUrl)
            onNavigateToDetail(meta.id, meta.apiType, addonBaseUrl)
        }
    )

    val marquee = NuvioTheme.marquee
    val form = band.grid.form
    // Pushed (no top bar), no tab row: the list clips under the breadcrumb.
    val geometry = gridBandGeometry(listTop = marquee.topBar.contentTop, form = form, tabRow = false)
    val bandSpec = rememberMarqueeBandBringIntoViewSpec(listState, geometry.bandBottomInset)
    val anchorInsetPx = with(LocalDensity.current) { geometry.bandBottomInset.roundToPx() }
    val hero = rememberResultsHeroState(settledItem = band.settledItem.value)
    val rowTitle = catalogRow?.catalogName ?: stringResource(R.string.catalog_see_all_title_fallback)
    val breadcrumb = listOfNotNull(
        stringResource(R.string.nav_search),
        searchUiState.submittedQuery.trim().takeIf { it.isNotEmpty() },
        rowTitle
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // hasItems smart-casts catalogRow to non-null here.
            hasItems -> {
                MarqueeResultsHero(
                    hero = hero,
                    heroBottomInset = geometry.heroBottomInset,
                    heroMaxHeight = geometry.heroMaxHeight,
                    // No top bar: the breadcrumb sits on the art.
                    drawTopScrim = true
                )
                DiscoverRowTitle(
                    title = rowTitle,
                    subtitle = listOfNotNull(
                        localizedContentType(catalogRow.apiType).takeIf { it.isNotEmpty() },
                        catalogRow.addonName
                            .takeIf { searchUiState.catalogAddonNameEnabled && it.isNotBlank() }
                            ?.let { stringResource(R.string.catalog_see_all_from, it) }
                    ).joinToString(FACT_SEPARATOR),
                    modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = geometry.rowTitleTop)
                )
            }
            loading -> GridBandSkeleton(form)
            else -> MarqueeMessage(
                eyebrow = null,
                title = stringResource(R.string.catalog_see_all_empty_title),
                body = AnnotatedString(stringResource(R.string.catalog_see_all_empty_subtitle))
            )
        }
        MarqueeBreadcrumb(breadcrumb)
        if (hasItems) {
            // Spec 96 D16: the grid's cards opt into the trailer preview (the host's In the card mode decides).
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides bandSpec,
                LocalFastScrollActive provides band.isFastScrolling,
                LocalTrailerPreviewEnabled provides true
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = geometry.listTop)
                        .catalogGridFastScroll(band, anchorInsetPx),
                    state = listState,
                    // The last grid row can still reach the band anchor.
                    contentPadding = PaddingValues(bottom = geometry.bandBottomInset),
                    verticalArrangement = Arrangement.spacedBy(geometry.itemGap)
                ) {
                    item(key = "see_all_band_lead") { Spacer(modifier = Modifier.height(geometry.bandLeadHeight)) }
                    catalogGridItems(band, hero.accent)
                }
            }
        }
    }

    val posterOptionsState by searchViewModel.posterOptions.state.collectAsState()
    PosterOptionsHost(
        state = posterOptionsState,
        controller = searchViewModel.posterOptions,
        onNavigateToDetail = { id, itemType, addonBaseUrl ->
            band.onLeavingForDetail()
            onNavigateToDetail(id, itemType, addonBaseUrl)
        }
    )
}
