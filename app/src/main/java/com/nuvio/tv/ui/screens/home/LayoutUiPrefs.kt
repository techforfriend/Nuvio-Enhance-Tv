package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The layout preferences the Marquee home surfaces render with, read as one value.
 * Home collects [layoutUiPrefsFlow] live; FolderDetail reads it once per folder load.
 * [withLayoutPrefs] is the one place the display prefs are written into a [HomeUiState].
 */
data class LayoutUiPrefs(
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val homeImdbRatingsVisibility: HomeImdbRatingsVisibility,
    val trailerAutoplayMode: TrailerAutoplayMode,
    val focusedPosterBackdropTrailerMuted: Boolean
)

// Typed groups only because combine() takes at most five typed flows; each group is
// built from real values (no placeholder-then-copy).
private data class CatalogDisplayPrefs(
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean
)

private data class HomeMediaPrefs(
    val landscapePosters: Boolean,
    val trailerMode: TrailerAutoplayMode,
    val trailerMuted: Boolean
)

internal fun LayoutPreferenceDataStore.layoutUiPrefsFlow(): Flow<LayoutUiPrefs> {
    val catalogDisplay = combine(
        catalogAddonNameEnabled,
        catalogTypeSuffixEnabled,
        hideUnreleasedContent
    ) { addonName, typeSuffix, hideUnreleased ->
        CatalogDisplayPrefs(addonName, typeSuffix, hideUnreleased)
    }
    val media = combine(
        modernLandscapePostersEnabled,
        trailerAutoplayMode,
        focusedPosterBackdropTrailerMuted
    ) { landscapePosters, trailerMode, trailerMuted ->
        HomeMediaPrefs(landscapePosters, trailerMode, trailerMuted)
    }
    return combine(
        catalogDisplay,
        media,
        homeImdbRatingsVisibility,
        showFullReleaseDate
    ) { catalog, mediaPrefs, imdbRatingsVisibility, fullReleaseDate ->
        LayoutUiPrefs(
            catalogAddonNameEnabled = catalog.catalogAddonNameEnabled,
            catalogTypeSuffixEnabled = catalog.catalogTypeSuffixEnabled,
            hideUnreleasedContent = catalog.hideUnreleasedContent,
            showFullReleaseDate = fullReleaseDate,
            modernLandscapePostersEnabled = mediaPrefs.landscapePosters,
            homeImdbRatingsVisibility = imdbRatingsVisibility,
            trailerAutoplayMode = mediaPrefs.trailerMode,
            focusedPosterBackdropTrailerMuted = mediaPrefs.trailerMuted
        )
    }
}

/**
 * Writes the display prefs into this state. [HomeUiState.heroSectionEnabled] is not a pref: Home
 * keeps its default (on); FolderDetail and the TMDB browse page set it off.
 */
internal fun HomeUiState.withLayoutPrefs(prefs: LayoutUiPrefs): HomeUiState = copy(
    catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
    catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
    hideUnreleasedContent = prefs.hideUnreleasedContent,
    showFullReleaseDate = prefs.showFullReleaseDate,
    modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
    homeImdbRatingsVisibility = prefs.homeImdbRatingsVisibility,
    trailerAutoplayMode = prefs.trailerAutoplayMode,
    focusedPosterBackdropTrailerMuted = prefs.focusedPosterBackdropTrailerMuted
)
