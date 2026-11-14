package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent searches: prefix collapse, cap, and saves that overlap (Spec 21 B1). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryDataStoreTest {

    private val profiles = mapOf(1 to YieldingPreferencesDataStore(), 2 to YieldingPreferencesDataStore())
    private val activeProfileIdFlow = MutableStateFlow(1)
    private val store = SearchHistoryDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), SearchHistoryDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeProfileIdFlow }
    )

    @Test
    fun `newest first, prefixes and case duplicates collapse, capped`() = runTest {
        listOf("f", "fr", "Frieren", "dune", "FRIEREN").forEach { store.saveRecentSearch(it, maxItems = 3) }

        assertEquals(listOf("FRIEREN", "dune"), store.recentSearches.first())

        listOf("a1", "a2", "b").forEach { store.saveRecentSearch(it, maxItems = 3) }
        assertEquals(listOf("b", "a2", "a1"), store.recentSearches.first())
    }

    @Test
    fun `two overlapping saves of different queries both persist`() = runTest {
        launch { store.saveRecentSearch("dune") }
        launch { store.saveRecentSearch("alien") }
        advanceUntilIdle()

        assertEquals(setOf("dune", "alien"), store.recentSearches.first().toSet())
    }

    @Test
    fun `a profile switch during a save leaves it in the profile it started on`() = runTest {
        store.saveRecentSearch("dune")

        launch { store.saveRecentSearch("alien") }
        launch { activeProfileIdFlow.value = 2 }
        advanceUntilIdle()

        // The old save read the list through the active-profile flow and chose the store after
        // it: a switch during the read wrote [alien] into profile 2.
        assertEquals(emptyList<String>(), store.recentSearches.first())
        activeProfileIdFlow.value = 1
        assertEquals(listOf("alien", "dune"), store.recentSearches.first())
    }
}
