package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/** Layout prefs with every flag off, for tests that only need [layoutUiPrefsFlow] to emit. */
internal val TEST_LAYOUT_PREFS = LayoutUiPrefs(
    catalogAddonNameEnabled = false,
    catalogTypeSuffixEnabled = false,
    hideUnreleasedContent = false,
    showFullReleaseDate = false,
    modernLandscapePostersEnabled = false,
    homeImdbRatingsVisibility = HomeImdbRatingsVisibility.HIDE_ALL,
    trailerAutoplayMode = TrailerAutoplayMode.OFF,
    focusedPosterBackdropTrailerMuted = false
)

/** A store whose 8 layout prefs emit [prefs] once; every other member is relaxed. */
internal fun layoutPrefsStoreEmitting(prefs: LayoutUiPrefs = TEST_LAYOUT_PREFS): LayoutPreferenceDataStore =
    mockk<LayoutPreferenceDataStore>(relaxed = true) {
        every { catalogAddonNameEnabled } returns flowOf(prefs.catalogAddonNameEnabled)
        every { catalogTypeSuffixEnabled } returns flowOf(prefs.catalogTypeSuffixEnabled)
        every { hideUnreleasedContent } returns flowOf(prefs.hideUnreleasedContent)
        every { showFullReleaseDate } returns flowOf(prefs.showFullReleaseDate)
        every { modernLandscapePostersEnabled } returns flowOf(prefs.modernLandscapePostersEnabled)
        every { homeImdbRatingsVisibility } returns flowOf(prefs.homeImdbRatingsVisibility)
        every { trailerAutoplayMode } returns flowOf(prefs.trailerAutoplayMode)
        every { focusedPosterBackdropTrailerMuted } returns flowOf(prefs.focusedPosterBackdropTrailerMuted)
    }
