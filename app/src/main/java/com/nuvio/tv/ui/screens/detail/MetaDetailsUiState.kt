package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.player.ParentalWarning
import com.nuvio.tv.core.profile.ActiveRatingLimit
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility

/** Key of the app's own library row in the detail long-press list picker. */
internal const val LOCAL_LIBRARY_LIST_KEY = "local"

enum class MoreLikeThisSource {
    TMDB
}

data class MetaDetailsUiState(
    val isLoading: Boolean = true,
    val meta: Meta? = null,
    val error: String? = null,
    val selectedSeason: Int = 1,
    val seasons: List<Int> = emptyList(),
    val episodesForSeason: List<Video> = emptyList(),
    val isInLibrary: Boolean = false,
    val nextToWatch: NextToWatch? = null,
    val episodeProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val trailerUrl: String? = null,
    val trailerAudioUrl: String? = null,
    val isTrailerPlaying: Boolean = false,
    val showTrailerControls: Boolean = false,
    val hideLogoDuringTrailer: Boolean = false,
    val isSharedTrailerOverlayVisible: Boolean = false,
    val isSharedTrailerLoading: Boolean = false,
    val sharedTrailerUrl: String? = null,
    val sharedTrailerAudioUrl: String? = null,
    val sharedTrailerErrorMessage: String? = null,
    val selectedSharedTrailer: MetaTrailer? = null,
    val trailerButtonEnabled: Boolean = false,
    val showListPicker: Boolean = false,
    val pickerMembership: Map<String, Boolean> = emptyMap(),
    val pickerPending: Boolean = false,
    val pickerError: String? = null,
    val defaultLibraryTogglePending: Boolean = false,
    val isMovieWatched: Boolean = false,
    val isMovieWatchedPending: Boolean = false,
    val watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    val episodeWatchedPendingKeys: Set<String> = emptySet(),
    val blurUnwatchedEpisodes: Boolean = false,
    val overallRatingsVisibility: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL,
    val detailImdbRatingsVisibility: DetailImdbRatingsVisibility = DetailImdbRatingsVisibility.SHOW_ALL,
    val showFullReleaseDate: Boolean = true,
    val moreLikeThis: List<MetaPreview> = emptyList(),
    val moreLikeThisSource: MoreLikeThisSource? = null,
    val collection: List<MetaPreview> = emptyList(),
    val collectionName: String? = null,
    val relatedWatchedStatus: Map<String, Boolean> = emptyMap(),
    val episodeImdbRatings: Map<Pair<Int, Int>, Double> = emptyMap(),
    val isEpisodeRatingsLoading: Boolean = false,
    val episodeRatingsError: String? = null,
    val userMessage: String? = null,
    val userMessageIsError: Boolean = false,
    val about: DetailAboutExtras = DetailAboutExtras(),
    /** Spec 64: the active profile's rating limit, read at each load start; null = no limit. */
    val ratingLimit: ActiveRatingLimit? = null,
    /** Spec 64 D13: set instead of [meta] when the title is outside [ratingLimit]. */
    val ratingBlock: DetailRatingBlock? = null
)

/** What the About sheet loads when it first opens (Spec 26g); [metaId] names the title it is for. */
data class DetailAboutExtras(
    val metaId: String? = null,
    /** The TMDB language's region order for the release country (Spec 26 R2). */
    val releaseRegions: List<String> = emptyList(),
    val parentalWarnings: List<ParentalWarning> = emptyList(),
    /** Lowercased genres a Discover catalog of the title's type lists (Spec 26 R3); empty with Discover off. */
    val discoverGenres: Set<String> = emptySet()
)

sealed class MetaDetailsEvent {
    data class OnSeasonSelected(val season: Int) : MetaDetailsEvent()
    data object OnToggleLibrary : MetaDetailsEvent()
    data object OnRetry : MetaDetailsEvent()
    data object OnUserInteraction : MetaDetailsEvent()
    data object OnPlayButtonFocused : MetaDetailsEvent()
    data object OnTrailerButtonClick : MetaDetailsEvent()
    data object OnTrailerEnded : MetaDetailsEvent()
    data class OnSharedTrailerSelected(val trailer: MetaTrailer) : MetaDetailsEvent()
    data object OnDismissSharedTrailer : MetaDetailsEvent()
    data object OnRetrySharedTrailer : MetaDetailsEvent()
    data object OnToggleMovieWatched : MetaDetailsEvent()
    data class OnToggleEpisodeWatched(val video: Video) : MetaDetailsEvent()
    data class OnMarkSeasonWatched(val season: Int) : MetaDetailsEvent()
    data class OnMarkSeasonUnwatched(val season: Int) : MetaDetailsEvent()
    data class OnMarkPreviousEpisodesWatched(val video: Video) : MetaDetailsEvent()
    data class OnMarkPreviousSeasonsWatched(val season: Int) : MetaDetailsEvent()
    data object OnLibraryLongPress : MetaDetailsEvent()
    data class OnPickerMembershipToggled(val listKey: String) : MetaDetailsEvent()
    data object OnPickerSave : MetaDetailsEvent()
    data object OnPickerDismiss : MetaDetailsEvent()
    data object OnClearMessage : MetaDetailsEvent()
    data object OnLifecyclePause : MetaDetailsEvent()
    data object OnLifecycleResume : MetaDetailsEvent()
    data object OnAboutOpened : MetaDetailsEvent()
    /** OK on a cast member or studio under a rating limit (Spec 64 D12): the toast, no page. */
    data object OnRatingLimitedLink : MetaDetailsEvent()
}
