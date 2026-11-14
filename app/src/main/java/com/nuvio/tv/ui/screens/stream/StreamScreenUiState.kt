package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.core.stream.RecentFailures
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.ui.components.sources.LastPlayed

data class StreamScreenUiState(
    val isLoading: Boolean = true,
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val autoPlayDecided: Boolean = false,
    val directAutoPlayMessage: String? = null,
    val videoId: String = "",
    val contentType: String = "",
    val title: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    // Episode-specific fields
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val runtime: Int? = null,
    // Movie-specific fields
    val genres: String? = null,
    val year: String? = null,
    /** Plot under the title: the episode's overview for an episode, else the title's description. */
    val description: String? = null,
    val imdbRating: Float? = null,
    val addonStreams: List<AddonStreams> = emptyList(),
    val allStreams: List<Stream> = emptyList(),
    /** The user's own pick (Spec 51 M7); [listView] holds the effective one. */
    val listSelection: StreamListSelection = StreamListSelection.All,
    /** The filters sheet's values on (Spec 65 P1): view-only, cleared by a refresh (D18). */
    val facetFilter: FacetFilter = FacetFilter.NONE,
    /** [allStreams] under [listSelection], [facetFilter] and the stream list limit (Spec 51 P4). */
    val listView: StreamListView = StreamListView(),
    val availableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    val autoPlayStream: Stream? = null,
    val autoPlayPlaybackInfo: StreamPlaybackInfo? = null,
    val error: String? = null,
    /** The source last played for this title or episode (P10 "Last played" marker), if any. */
    val lastPlayed: LastPlayed? = null,
    /** The show's preferred source (Spec 65 P6); [listView] lists its matches first. */
    val preferredSource: PreferredSource? = null,
    /** The failure store's entries under a day old (Spec 75 S6): the rows' failure marks. */
    val recentFailures: RecentFailures = RecentFailures.EMPTY
) {
    /** The rows the list shows. */
    val filteredStreams: List<Stream> get() = listView.streams
}

sealed class StreamScreenEvent {
    data class OnListSelected(val selection: StreamListSelection) : StreamScreenEvent()
    data class OnFilterChanged(val filter: FacetFilter) : StreamScreenEvent()
    data object OnAutoPlayConsumed : StreamScreenEvent()
    data object OnRefresh : StreamScreenEvent()
    data object OnRetry : StreamScreenEvent()
    data object OnResume : StreamScreenEvent()
}
