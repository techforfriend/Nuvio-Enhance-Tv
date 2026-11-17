package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L4-31/52/67: Home and FolderDetail read their 8 layout prefs through one flow builder
 * and write them through one applier. Each boolean pref is set true alone (one-hot), so a
 * crossed boolean shows up as the wrong field being true; the enums carry values that
 * differ from the HomeUiState defaults (FR-10). The poster card size prefs left in wave C
 * S6d and the hero section in Spec 31 S2 (both retired).
 */
class LayoutUiPrefsTest {

    private val base = LayoutUiPrefs(
        catalogAddonNameEnabled = false,
        catalogTypeSuffixEnabled = false,
        hideUnreleasedContent = false,
        showFullReleaseDate = false,
        modernLandscapePostersEnabled = false,
        homeImdbRatingsVisibility = HomeImdbRatingsVisibility.HIDE_ALL,
        trailerAutoplayMode = TrailerAutoplayMode.CARD,
        focusedPosterBackdropTrailerMuted = false
    )

    /** Each of the 6 boolean prefs, set true alone on [base]. */
    private val oneHot: List<Pair<String, LayoutUiPrefs>> = listOf(
        "catalogAddonNameEnabled" to base.copy(catalogAddonNameEnabled = true),
        "catalogTypeSuffixEnabled" to base.copy(catalogTypeSuffixEnabled = true),
        "hideUnreleasedContent" to base.copy(hideUnreleasedContent = true),
        "showFullReleaseDate" to base.copy(showFullReleaseDate = true),
        "modernLandscapePostersEnabled" to base.copy(modernLandscapePostersEnabled = true),
        "focusedPosterBackdropTrailerMuted" to base.copy(focusedPosterBackdropTrailerMuted = true)
    )

    @Test
    fun `flow builder maps each boolean pref to its own field`() = runTest {
        for ((name, prefs) in oneHot) {
            assertEquals(name, prefs, layoutPrefsStoreEmitting(prefs).layoutUiPrefsFlow().first())
        }
    }

    @Test
    fun `flow builder maps the enum prefs`() = runTest {
        assertEquals(base, layoutPrefsStoreEmitting(base).layoutUiPrefsFlow().first())
    }

    @Test
    fun `applier writes each boolean display pref to its own field`() {
        for ((name, prefs) in oneHot) {
            val state = HomeUiState().withLayoutPrefs(prefs)

            val trueFields = displayBooleans(state).filterValues { it }.keys
            assertEquals(name, setOf(name), trueFields)
        }
    }

    @Test
    fun `applier writes the enum prefs and leaves the hero section alone`() {
        val state = HomeUiState().withLayoutPrefs(base)

        assertEquals(HomeImdbRatingsVisibility.HIDE_ALL, state.homeImdbRatingsVisibility)
        assertEquals(TrailerAutoplayMode.CARD, state.trailerAutoplayMode)
        // Hero visibility is not a pref (Home keeps the default, FolderDetail forces it off).
        assertEquals(HomeUiState().heroSectionEnabled, state.heroSectionEnabled)
    }

    @Test
    fun `catalog row title matches the pre-consolidation format`() {
        assertEquals("Popular - Movie", catalogRowTitle("popular", "movie", true, "Movie", "Series"))
        assertEquals("Popular - Series", catalogRowTitle("popular", "series", true, "Movie", "Series"))
        // Blank localized label falls back to the capitalized api type.
        assertEquals("Popular - Movie", catalogRowTitle("popular", "movie", true, "", ""))
        assertEquals("Popular - Anime", catalogRowTitle("popular", "anime", true, "Movie", "Series"))
        assertEquals("Popular", catalogRowTitle("popular", "movie", false, "Movie", "Series"))
    }

    /** The 6 boolean display prefs the applier writes, by LayoutUiPrefs field name. */
    private fun displayBooleans(state: HomeUiState): Map<String, Boolean> = mapOf(
        "catalogAddonNameEnabled" to state.catalogAddonNameEnabled,
        "catalogTypeSuffixEnabled" to state.catalogTypeSuffixEnabled,
        "hideUnreleasedContent" to state.hideUnreleasedContent,
        "showFullReleaseDate" to state.showFullReleaseDate,
        "modernLandscapePostersEnabled" to state.modernLandscapePostersEnabled,
        "focusedPosterBackdropTrailerMuted" to state.focusedPosterBackdropTrailerMuted
    )
}
