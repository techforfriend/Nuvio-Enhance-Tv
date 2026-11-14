package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFallbackTest {

    @Test
    fun `removing the active profile falls back to the primary and records it (Spec 64 D14)`() {
        val prefs = mutablePreferencesOf(ACTIVE_PROFILE_ID_KEY to 3)
        prefs.fallBackIfActiveRemoved(listOf(1, 2))
        assertEquals(1, prefs[ACTIVE_PROFILE_ID_KEY])
        assertTrue(prefs[ACTIVE_PROFILE_FELL_BACK_KEY] == true)
    }

    @Test
    fun `removing another profile changes nothing`() {
        val prefs = mutablePreferencesOf(ACTIVE_PROFILE_ID_KEY to 2)
        prefs.fallBackIfActiveRemoved(listOf(1, 2))
        assertEquals(2, prefs[ACTIVE_PROFILE_ID_KEY])
        assertNull(prefs[ACTIVE_PROFILE_FELL_BACK_KEY])
    }

    @Test
    fun `an unset active id is the primary and never falls back while it exists`() {
        val prefs = mutablePreferencesOf()
        prefs.fallBackIfActiveRemoved(listOf(1))
        assertNull(prefs[ACTIVE_PROFILE_FELL_BACK_KEY])
    }
}
