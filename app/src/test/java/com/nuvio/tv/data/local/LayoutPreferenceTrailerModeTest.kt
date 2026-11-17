package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 96 D7: the three-way Autoplay trailer mode over the old switch + the card flag (no migration); D12's delay. */
class LayoutPreferenceTrailerModeTest {
    private val enabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val cardKey = booleanPreferencesKey("trailer_card_preview")
    private val mutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    private val delayKey = intPreferencesKey("trailer_card_preview_delay_seconds")

    private fun layoutOver(store: TestPreferencesDataStore): LayoutPreferenceDataStore {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        every { profileManager.profiles } returns MutableStateFlow(emptyList())
        return LayoutPreferenceDataStore(factory, profileManager)
    }

    @Test
    fun `nothing stored reads Off`() = runTest {
        assertEquals(TrailerAutoplayMode.OFF, layoutOver(TestPreferencesDataStore(preferencesOf())).trailerAutoplayMode.first())
    }

    @Test
    fun `an install with the old switch on reads Behind hero`() = runTest {
        val layout = layoutOver(TestPreferencesDataStore(preferencesOf(enabledKey to true)))

        assertEquals(TrailerAutoplayMode.HERO, layout.trailerAutoplayMode.first())
    }

    @Test
    fun `the card flag alone stays Off`() = runTest {
        val layout = layoutOver(TestPreferencesDataStore(preferencesOf(enabledKey to false, cardKey to true)))

        assertEquals(TrailerAutoplayMode.OFF, layout.trailerAutoplayMode.first())
    }

    @Test
    fun `every mode round-trips from every mode`() = runTest {
        val layout = layoutOver(TestPreferencesDataStore(preferencesOf()))
        for (from in TrailerAutoplayMode.entries) {
            for (to in TrailerAutoplayMode.entries) {
                layout.setTrailerAutoplayMode(from)
                layout.setTrailerAutoplayMode(to)
                assertEquals("$from -> $to", to, layout.trailerAutoplayMode.first())
            }
        }
    }

    @Test
    fun `In the card keeps the old switch on so older builds still read a trailer mode`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf())
        layoutOver(store).setTrailerAutoplayMode(TrailerAutoplayMode.CARD)

        assertTrue(store.value[enabledKey] == true)
        assertTrue(store.value[cardKey] == true)
    }

    @Test
    fun `Off resets mute like the old switch, the other modes leave it`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf(mutedKey to false))
        val layout = layoutOver(store)

        layout.setTrailerAutoplayMode(TrailerAutoplayMode.CARD)
        assertFalse(store.value[mutedKey] == true)
        layout.setTrailerAutoplayMode(TrailerAutoplayMode.HERO)
        assertFalse(store.value[mutedKey] == true)
        layout.setTrailerAutoplayMode(TrailerAutoplayMode.OFF)
        assertTrue(store.value[mutedKey] == true)
    }

    @Test
    fun `nothing stored reads the 3 s preview delay`() = runTest {
        assertEquals(3, layoutOver(TestPreferencesDataStore(preferencesOf())).trailerCardPreviewDelaySeconds.first())
    }

    @Test
    fun `a synced delay out of range reads clamped to 1-10 s`() = runTest {
        assertEquals(10, layoutOver(TestPreferencesDataStore(preferencesOf(delayKey to 99))).trailerCardPreviewDelaySeconds.first())
        assertEquals(1, layoutOver(TestPreferencesDataStore(preferencesOf(delayKey to 0))).trailerCardPreviewDelaySeconds.first())
    }

    @Test
    fun `the delay setter stores within 1-10 s and changing the mode keeps it`() = runTest {
        val store = TestPreferencesDataStore(preferencesOf())
        val layout = layoutOver(store)
        layout.setTrailerCardPreviewDelaySeconds(5)
        layout.setTrailerAutoplayMode(TrailerAutoplayMode.OFF)
        assertEquals(5, layout.trailerCardPreviewDelaySeconds.first())
        layout.setTrailerCardPreviewDelaySeconds(42)
        assertEquals(10, store.value[delayKey])
    }
}
