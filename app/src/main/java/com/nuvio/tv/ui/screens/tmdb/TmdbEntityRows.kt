package com.nuvio.tv.ui.screens.tmdb

import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityHeader
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import com.nuvio.tv.ui.screens.home.LayoutUiPrefs
import com.nuvio.tv.ui.util.isoDisplayNames
import java.util.Locale

/** The rows' addon id: no addon serves them; it only keys the rows and routes load-more back here. */
internal const val ENTITY_ROWS_ADDON_ID = "tmdb_entity"

/** "movie_popular": the row's catalog id, unique per rail of one entity. */
internal fun entityRailCatalogId(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType): String =
    "${mediaType.value}_${railType.value}"

/**
 * The studio / network rails as Home catalog rows for MarqueeHomeContent (Spec 29g; mockup
 * stages 8-9). [rowName] gives the title ("Popular · Movies"); the screen shows no type suffix
 * or addon name. Paging state is the rail's own, so MHC's load-more guard reads it.
 */
internal fun entityCatalogRows(
    rails: List<TmdbEntityRail>,
    rowName: (TmdbEntityMediaType, TmdbEntityRailType) -> String
): List<CatalogRow> = rails.map { rail ->
    CatalogRow(
        addonId = ENTITY_ROWS_ADDON_ID,
        addonName = "",
        addonBaseUrl = "",
        catalogId = entityRailCatalogId(rail.mediaType, rail.railType),
        catalogName = rowName(rail.mediaType, rail.railType),
        type = if (rail.mediaType == TmdbEntityMediaType.TV) ContentType.SERIES else ContentType.MOVIE,
        items = rail.items,
        isLoading = rail.isLoading,
        hasMore = rail.hasMore,
        currentPage = rail.currentPage,
        // MarqueeHomeRows only watches a row for load-more when it supports paging; hasMore gates each request.
        supportsSkip = true
    )
}

/** The rail behind a row's load-more request; null for a row this page did not build. */
internal fun TmdbEntityBrowseData.railForCatalog(catalogId: String, addonId: String): TmdbEntityRail? =
    if (addonId != ENTITY_ROWS_ADDON_ID) {
        null
    } else {
        rails.firstOrNull { entityRailCatalogId(it.mediaType, it.railType) == catalogId }
    }

/** Header subline (stage 7): headquarters, else the origin country's name; null when TMDB has neither. */
internal fun entityHeaderSubline(header: TmdbEntityHeader, locale: Locale = Locale.getDefault()): String? =
    header.secondaryLabel?.trim()?.takeIf { it.isNotEmpty() }
        ?: header.originCountry?.trim()?.takeIf { it.isNotEmpty() }?.let { isoDisplayNames(it, isCountry = true, locale) }

/** The user's layout prefs as this page renders them: titles carry no type or addon, and no trailers (no source). */
internal fun entityLayoutPrefs(prefs: LayoutUiPrefs): LayoutUiPrefs = prefs.copy(
    catalogAddonNameEnabled = false,
    catalogTypeSuffixEnabled = false,
    trailerAutoplayMode = TrailerAutoplayMode.OFF
)

/** The page's two modes: the header frame over a row peek (stage 7), or Home's rows (stages 8-9). */
internal enum class EntityPageMode { HEADER, ROWS }

/**
 * The entry mode known before layout: ROWS for a network (Spec 29 Q7) or a page with nothing to
 * read; null = the description's first layout decides (cut off = HEADER, Read more; else ROWS).
 */
internal fun entityEntryMode(header: TmdbEntityHeader): EntityPageMode? =
    if (header.kind == TmdbEntityKind.NETWORK || header.description.isNullOrBlank()) EntityPageMode.ROWS else null
