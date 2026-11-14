package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.MetaPreview

@Immutable
data class HomeUiState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val upcomingItems: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val layoutPreferencesReady: Boolean = false,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val modernLandscapePostersEnabled: Boolean = false,
    val homeImdbRatingsVisibility: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroSectionEnabled: Boolean = true,
    val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState(),
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val movieWatchedStatus: Map<String, Boolean> = emptyMap(),
    val hideUnreleasedContent: Boolean = false,
    val showFullReleaseDate: Boolean = true,
    val blurUnwatchedEpisodes: Boolean = false,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    val heroEnrichmentEnabled: Boolean = false,
    val startupAuthNotice: StartupAuthNotice? = null,
    val homeRows: List<HomeRow> = emptyList()
)

@Immutable
sealed class HomeRow {
    @Immutable
    data class Catalog(val row: CatalogRow) : HomeRow()

    @Immutable
    data class CollectionRow(val collection: Collection) : HomeRow()

    /**
     * Placeholder for a catalog row whose data hasn't been fetched yet.
     * Rendered as a shimmer/skeleton row until the user scrolls near it
     * and the actual catalog data is loaded on demand.
     */
    @Immutable
    data class PlaceholderCatalog(
        val catalogKey: String,
        val stableCatalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    ) : HomeRow()
}

sealed class HomeEvent {
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : HomeEvent()
    data class OnRemoveContinueWatching(
        val contentId: String,
        val season: Int? = null,
        val episode: Int? = null,
        val isNextUp: Boolean = false
    ) : HomeEvent()
    data class OnMarkContinueWatchingWatched(val item: ContinueWatchingItem) : HomeEvent()
    data object OnRetry : HomeEvent()
}

fun homeItemStatusKey(itemId: String, itemType: String): String {
    return "${itemType.lowercase()}|$itemId"
}
