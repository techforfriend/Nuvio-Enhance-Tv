package com.nuvio.tv.livetv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Spec 34 L8-D1/D2: per-profile favorites, recents, last filter and last view (Spec 67 D7); caps, junk, profile switch. */
class LiveTvUserStoreTest {

    private val activeProfile = MutableStateFlow(1)
    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private val factory = mockk<ProfileDataStoreFactory>().also { f ->
        every { f.get(any(), "live_tv") } answers { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
    }
    private val profiles = mockk<ProfileManager>().also { every { it.activeProfileId } returns activeProfile }
    private val store = LiveTvUserStore(factory, profiles)

    @Test
    fun `favorites, recents and the last filter round trip`() = runTest {
        assertEquals(LiveTvUserState(), store.state.first())
        assertTrue(store.toggleFavorite("a"))
        store.toggleFavorite("b")
        store.toggleFavorite("b") // a second toggle removes it
        store.recordWatched("x", 1)
        store.recordWatched("y", 2)
        store.recordWatched("x", 3)
        store.setLastFilter("sports")
        assertTrue(store.setLastView("guide"))
        val state = store.state.first()
        assertEquals(listOf("a"), state.favorites)
        assertEquals(listOf(RecentChannel("x", 3), RecentChannel("y", 2)), state.recents)
        assertEquals("sports", state.lastFilter)
        assertEquals("guide", state.lastView)
    }

    @Test
    fun `each profile has its own state`() = runTest {
        store.toggleFavorite("a")
        activeProfile.value = 2
        assertEquals(emptyList<String>(), store.state.first().favorites)
        store.toggleFavorite("b")
        activeProfile.value = 1
        assertEquals(listOf("a"), store.state.first().favorites)
    }

    @Test
    fun `caps drop the oldest favorite and the oldest recent`() {
        val full = (1..LiveTvUserStore.MAX_FAVORITES).map { "k$it" }
        val added = LiveTvUserStore.withFavorite(full, "new", true)
        assertEquals(LiveTvUserStore.MAX_FAVORITES, added.size)
        assertEquals("k2", added.first())
        assertEquals("new", added.last())

        var recents = emptyList<RecentChannel>()
        (1..LiveTvUserStore.MAX_RECENTS + 5).forEach { recents = LiveTvUserStore.withRecent(recents, "r$it", it.toLong()) }
        assertEquals(LiveTvUserStore.MAX_RECENTS, recents.size)
        assertEquals("r25", recents.first().key)
    }

    @Test
    fun `junk, repeats and blanks read as clean lists`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("favorites") to """["a","","a","b"]""",
            stringPreferencesKey("recents") to "{not json"
        )
        val state = LiveTvUserStore.decode(prefs)
        assertEquals(listOf("a", "b"), state.favorites)
        assertEquals(emptyList<RecentChannel>(), state.recents)
        assertNull(state.lastFilter)
        assertEquals(
            listOf(RecentChannel("a", 2)),
            LiveTvUserStore.decodeRecents("""[{"key":"a","atMs":2},{"key":"a","atMs":1},{"key":" ","atMs":3}]""")
        )
        assertEquals(emptyList<String>(), LiveTvUserStore.decodeFavorites("""{"a":1}"""))
    }

    @Test
    fun `a failed write reports false instead of throwing`() = runTest {
        val broken = mockk<DataStore<Preferences>>()
        coEvery { broken.updateData(any()) } throws IOException("disk full")
        stores[1] = broken
        assertFalse(store.toggleFavorite("a"))
    }

    @Test
    fun `an unreadable file reads as empty state`() = runTest {
        val broken = mockk<DataStore<Preferences>>()
        every { broken.data } returns flow { throw IOException("unreadable") }
        stores[1] = broken
        assertEquals(LiveTvUserState(), store.state.first())
    }

    @Test
    fun `live tv switch defaults on, is per profile, and the active flow follows a switch (D17)`() = runTest {
        assertTrue(store.enabledFor(3).first())
        assertTrue(store.setEnabled(3, false))
        assertFalse(store.enabledFor(3).first())
        assertTrue(store.enabledFor(1).first())
        assertTrue(store.activeEnabled.first())
        activeProfile.value = 3
        assertFalse(store.activeEnabled.first())
        assertTrue(store.setEnabled(3, true))
        assertTrue(store.activeEnabled.first())
    }

    @Test
    fun `live tv switch write failure reports false, an unreadable file reads as on`() = runTest {
        val broken = mockk<DataStore<Preferences>>()
        coEvery { broken.updateData(any()) } throws IOException("disk full")
        every { broken.data } returns flow { throw IOException("unreadable") }
        stores[2] = broken
        assertFalse(store.setEnabled(2, false))
        assertTrue(store.enabledFor(2).first())
    }

    @Test
    fun `adding an existing favorite changes nothing, removing a missing one too`() {
        assertEquals(listOf("a", "b"), LiveTvUserStore.withFavorite(listOf("a", "b"), "a", true))
        assertEquals(listOf("a"), LiveTvUserStore.withFavorite(listOf("a"), "z", false))
    }
}
