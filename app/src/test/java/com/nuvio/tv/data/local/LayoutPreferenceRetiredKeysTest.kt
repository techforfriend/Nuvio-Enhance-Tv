package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U1c (Marquee Home), U4 (Search/Discover) and Spec 31 S1/S2/S5 retired settings: a stored retired
 * value still decodes safely, and the stored retired keys are never rewritten or removed (the
 * profile-settings sync carries them).
 */
class LayoutPreferenceRetiredKeysTest {
    private val cardStyleKey = stringPreferencesKey("continue_watching_card_style")
    private val retired = preferencesOf(
        stringPreferencesKey("focused_poster_backdrop_trailer_playback_target") to "EXPANDED_CARD",
        booleanPreferencesKey("focused_poster_backdrop_expand_enabled") to false,
        intPreferencesKey("focused_poster_backdrop_expand_delay_seconds") to 7,
        booleanPreferencesKey("modern_hero_full_screen_backdrop") to true,
        intPreferencesKey("poster_card_width_dp") to 140,
        intPreferencesKey("poster_card_corner_radius_dp") to 4,
        booleanPreferencesKey("poster_labels_enabled") to false,
        booleanPreferencesKey("hero_section_enabled") to false,
        booleanPreferencesKey("card_depth_enabled") to true,
        intPreferencesKey("card_depth_edge_strength") to 42,
        stringPreferencesKey("selected_layout") to "GRID",
        booleanPreferencesKey("has_chosen_layout") to true,
        cardStyleKey to "WIDE"
    )

    private fun layoutOver(store: TestPreferencesDataStore): LayoutPreferenceDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { profileManager.profiles } returns MutableStateFlow(emptyList())
        return LayoutPreferenceDataStore(factory, profileManager)
    }

    @Test
    fun `stored WIDE continue watching style reads as CARD`() = runTest {
        val layout = layoutOver(TestPreferencesDataStore(retired))

        assertEquals(ContinueWatchingCardStyle.CARD, layout.continueWatchingCardStyle.first())
    }

    @Test
    fun `live setters leave every retired key untouched`() = runTest {
        val store = TestPreferencesDataStore(retired)
        val layout = layoutOver(store)

        layout.setTrailerAutoplayMode(TrailerAutoplayMode.HERO)

        val after = store.value.asMap()
        for ((key, value) in retired.asMap()) {
            assertEquals(key.name, value, after[key])
        }
    }
}
