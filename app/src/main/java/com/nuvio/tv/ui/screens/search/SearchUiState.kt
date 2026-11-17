package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholderItemId
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.catalogCarouselItem
import com.nuvio.tv.ui.screens.home.catalogRowTitleSuffix
import com.nuvio.tv.ui.util.asStable

internal const val MIN_SEARCH_QUERY_LENGTH = 2

internal fun submittedSearchQuery(rawQuery: String): String =
    rawQuery.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

/** Discover inside Search (stage 3): "Show in Search" and an idle field. Null = the setting is not loaded yet. */
internal fun shouldShowDiscoverInSearch(
    discoverLocation: DiscoverLocation?,
    query: String,
    submittedQuery: String
): Boolean =
    discoverLocation == DiscoverLocation.IN_SEARCH &&
        query.trim().length < MIN_SEARCH_QUERY_LENGTH &&
        submittedQuery.isBlank()

/** What Search shows under its field row (approved sandbox `u4-search-discover-marquee` stages 1-8). */
internal enum class SearchBody { START, LOADING, ERROR, NO_RESULTS, RESULTS }

/**
 * Search's body state. Before a query (or in Discover mode): the start message (recent searches
 * are a panel under the field, Spec 38 stage 9). While waiting (debounce or the first responses)
 * with nothing to show: the skeleton; existing results stay up instead, so typing does not flicker. [errorWithoutRows]: an error and
 * no catalog rows at all; [hasResults]: a row with items.
 */
internal fun searchBody(
    isDiscoverMode: Boolean,
    submittedQuery: String,
    hasPendingUnsubmittedQuery: Boolean,
    isSearching: Boolean,
    errorWithoutRows: Boolean,
    hasResults: Boolean
): SearchBody {
    val idle = isDiscoverMode ||
        (submittedQuery.trim().length < MIN_SEARCH_QUERY_LENGTH && !hasPendingUnsubmittedQuery)
    return when {
        idle -> SearchBody.START
        hasResults -> SearchBody.RESULTS
        hasPendingUnsubmittedQuery || isSearching -> SearchBody.LOADING
        errorWithoutRows -> SearchBody.ERROR
        else -> SearchBody.NO_RESULTS
    }
}

/** A search result row ends in a See All card once it holds a full page of real items. */
internal fun searchRowShowsSeeAll(row: CatalogRow): Boolean =
    !(row.isLoading && row.items.firstOrNull()?.id.isPlaceholderItemId()) && row.items.size >= 15

/**
 * One result row on Search's Marquee band (sandbox `u4-search-discover-marquee` stages 5, 6):
 * [row] for `MarqueeRowSection` (its `titleSuffix` = the small addon name after the title, null when
 * the addon-name setting is off), [source] for See All and focus restore.
 */
@Immutable
internal data class SearchResultRow(
    val row: HeroCarouselRow,
    val source: CatalogRow,
    val showSeeAll: Boolean
)

/**
 * Search's result rows as Marquee rows (each in its Card shape, decided at the band): row key = [stableKey], card key = Home's
 * [stableItemKey] (by index: a repeated id gets its own key, and a focused placeholder card keeps
 * its key, so its focus, when the real results replace it). Titles keep the as-built text: the
 * capitalised catalog name, " - <type>" when [showTypeSuffix] ([typeLabel] localises the row's
 * raw or api type), the addon name as the suffix when [showAddonName]. Rows never load more here.
 * A row whose source equals its entry in [previous] (built with the same settings) is reused:
 * results arrive one catalog at a time, and only the changed rows convert again.
 */
internal fun searchCarouselRows(
    rows: List<CatalogRow>,
    showAddonName: Boolean,
    showTypeSuffix: Boolean,
    typeLabel: (String) -> String,
    previous: Map<String, SearchResultRow> = emptyMap()
): List<SearchResultRow> = rows.mapIndexed { index, source ->
    val rowKey = source.stableKey()
    previous[rowKey]?.takeIf { it.source == source }?.let { cached ->
        return@mapIndexed if (cached.row.globalRowIndex == index) cached
        else cached.copy(row = cached.row.copy(globalRowIndex = index))
    }
    val name = source.catalogName.replaceFirstChar { it.uppercase() }
    val type = typeLabel(source.rawType.ifBlank { source.apiType })
    val title = if (showTypeSuffix && name.isNotBlank() && type.isNotEmpty()) "$name - $type" else name
    val items = source.items.mapIndexed { itemIndex, item ->
        catalogCarouselItem(item, rowKey, source.addonBaseUrl, useLandscapePosters = false, occurrence = 0)
            .copy(key = source.stableItemKey(itemIndex))
    }
    SearchResultRow(
        row = HeroCarouselRow(
            key = rowKey,
            title = title,
            // A nameless row (a title-less catalog) has nothing to suffix.
            titleSuffix = catalogRowTitleSuffix(source.addonName, showAddonName && name.isNotBlank()),
            globalRowIndex = index,
            items = items.asStable(),
            catalogId = source.catalogId,
            addonId = source.addonId,
            apiType = source.apiType,
            isLoading = source.isLoading
        ),
        source = source,
        showSeeAll = searchRowShowsSeeAll(source)
    )
}

/**
 * Spec 40 D3 (sandbox `u40-search-on-enter` stage 3): a search-on-Enter catalog a live run left
 * out, shown as one quiet line: [catalogName] (capitalised as the row titles; blank for a
 * title-less catalog), [addonName], and the key that runs it.
 */
@Immutable
data class SearchOnEnterWait(val catalogName: String, val addonName: String) {
    /** The line's title: the catalog, or the addon for a title-less catalog. */
    val title: String get() = catalogName.ifBlank { addonName }
}

/**
 * The waiting lines for [targets], in their order: one per addon and catalog name, so an addon's
 * same-named movie and series catalogs share one line.
 */
internal fun searchOnEnterWaits(targets: List<Pair<Addon, CatalogDescriptor>>): List<SearchOnEnterWait> =
    targets.map { (addon, catalog) ->
        SearchOnEnterWait(catalog.name.trim().replaceFirstChar { it.uppercase() }, addon.displayName)
    }.distinct()

/**
 * F-4: the result row Down from the field row returns to, or -1 for the first-result entry. Only a
 * card focused under the CURRENT submitted query counts: row keys are per catalog, so the same row
 * under a new query holds different titles.
 */
internal fun searchCardRestoreRowIndex(
    lastCardRowKey: String?,
    lastCardQuery: String?,
    submittedQuery: String,
    rowKeys: List<String>
): Int {
    if (lastCardRowKey == null || lastCardQuery == null) return -1
    if (lastCardQuery != submittedQuery.trim()) return -1
    return rowKeys.indexOf(lastCardRowKey)
}

/**
 * Keyboard completions: titles starting with the query first, otherwise the order [names] carries:
 * the addon's own (relevance) order, after [rankByTitleMatch] put an exact title first (T31, P41:
 * NM `c57dfe343`; an A-Z tie-break put "Jurassic City" before "Jurassic Park" while the keyboard
 * shows only 3-4).
 */
internal fun rankSuggestions(names: Collection<String>, query: String, limit: Int): List<String> {
    val queryLower = query.lowercase()
    // sortedByDescending is stable: equal ranks keep the addon's order.
    return names.sortedByDescending { it.lowercase().startsWith(queryLower) }.take(limit)
}

/**
 * Spec 95 D6: a search row's first page with the exact title match (trimmed, case-insensitive)
 * first, then titles starting with the query, then the rest. Stable, so each tier keeps the
 * addon's own order. Later pages are appended unranked, so cards on screen never move.
 */
internal fun rankByTitleMatch(items: List<MetaPreview>, query: String): List<MetaPreview> {
    val queryLower = query.trim().lowercase()
    if (queryLower.isEmpty()) return items
    return items.sortedBy { item ->
        val name = item.name.trim().lowercase()
        when {
            name == queryLower -> 0
            name.startsWith(queryLower) -> 1
            else -> 2
        }
    }
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val installedAddons: List<Addon> = emptyList(),
    /** Null until the setting loads: Search shows neither Discover nor the start message before it. */
    val discoverLocation: DiscoverLocation? = null,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    /** The Card shape setting: Landscape = true overrides every card, else each card its addon's shape (Spec 41 D1/D2). */
    val landscapePostersEnabled: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** Spec 40: catalogs the current live run left out until a submit; empty after a submit. */
    val searchOnEnterWaits: List<SearchOnEnterWait> = emptyList()
)
