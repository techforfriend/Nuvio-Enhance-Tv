package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.FacetGroup
import com.nuvio.tv.core.stream.FacetValue
import com.nuvio.tv.core.stream.SavedFilterEdit
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamPin
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-profile store of saved stream filters and pins (Spec 65 P2). */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedStreamFiltersDataStoreTest {

    private val stateKey = stringPreferencesKey("state")
    private val profiles = mutableMapOf<Int, TestPreferencesDataStore>(
        1 to YieldingPreferencesDataStore(),
        2 to YieldingPreferencesDataStore()
    )
    private val activeProfileIdFlow = MutableStateFlow(1)
    private val store = SavedStreamFiltersDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), SavedStreamFiltersDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeProfileIdFlow }
    )

    private val atmos = FacetFilter.NONE.toggled(FacetValue(FacetGroup.AUDIO, "ATMOS"))
    private val uhd = FacetFilter.NONE.toggled(FacetValue(FacetGroup.RESOLUTION, "P2160"))

    @Test
    fun `a saved filter is stored under its trimmed name with an id of its own, and can be pinned`() = runTest {
        store.save(atmos, "  Atmos ")
        store.save(uhd, "4K")
        val saved = store.state.first()
        assertEquals(listOf("Atmos", "4K"), saved.filters.map { it.name })
        assertEquals(2, saved.filters.map { it.id }.toSet().size)
        assertEquals(atmos, saved.filters.first().filter)

        val pin = StreamPin.Filter(saved.filters.first().id)
        store.togglePin(pin)
        store.togglePin(StreamPin.Addon("AIOStreams"))
        assertEquals(listOf(pin, StreamPin.Addon("AIOStreams")), store.state.first().pins)
        // The names on disk: a rename of a group or a pin kind would orphan what is stored.
        val raw = profiles.getValue(1).value[stateKey].orEmpty()
        assertTrue(raw, "\"AUDIO\":[\"ATMOS\"]" in raw)
        assertTrue(raw, "{\"type\":\"filter\",\"id\":\"${pin.id}\"}" in raw)
        assertTrue(raw, "{\"type\":\"addon\",\"name\":\"AIOStreams\"}" in raw)
        store.togglePin(pin)
        assertEquals(listOf(StreamPin.Addon("AIOStreams")), store.state.first().pins)
    }

    @Test
    fun `the same values saved twice, a blank name or no value on store nothing more`() = runTest {
        store.save(atmos, "Atmos")
        store.save(atmos, "Atmos again")
        store.save(uhd, "  ")
        store.save(FacetFilter.NONE, "Nothing")
        assertEquals(listOf("Atmos"), store.state.first().filters.map { it.name })
    }

    @Test
    fun `two overlapping changes both land`() = runTest {
        launch { store.save(atmos, "Atmos") }
        launch { store.togglePin(StreamPin.Addon("Torrentio")) }
        advanceUntilIdle()
        val saved = store.state.first()
        assertEquals(listOf("Atmos"), saved.filters.map { it.name })
        assertEquals(listOf(StreamPin.Addon("Torrentio")), saved.pins)
    }

    @Test
    fun `a profile switch during a change leaves it in the profile it started on`() = runTest {
        launch { store.togglePin(StreamPin.Addon("Torrentio")) }
        launch { activeProfileIdFlow.value = 2 }
        advanceUntilIdle()
        assertEquals(SavedStreamFilters.NONE, store.state.first())
        activeProfileIdFlow.value = 1
        assertEquals(listOf(StreamPin.Addon("Torrentio")), store.state.first().pins)
    }

    @Test
    fun `each profile keeps its own`() = runTest {
        store.save(atmos, "Atmos")
        activeProfileIdFlow.value = 2
        assertEquals(SavedStreamFilters.NONE, store.state.first())
        store.togglePin(StreamPin.Addon("Comet"))
        activeProfileIdFlow.value = 1
        val first = store.state.first()
        assertEquals(listOf("Atmos"), first.filters.map { it.name })
        assertTrue(first.pins.isEmpty())
    }

    @Test
    fun `a stored value that does not read is none, and the next change replaces it`() = runTest {
        listOf("not json", "[]", "\"text\"", "null", "{\"filters\":7,\"pins\":{}}").forEach { raw ->
            profiles[1] = TestPreferencesDataStore(preferencesOf(stateKey to raw))
            assertEquals(raw, SavedStreamFilters.NONE, store.state.first())
        }
        store.save(atmos, "Atmos")
        assertEquals(listOf("Atmos"), store.state.first().filters.map { it.name })
    }

    @Test
    fun `an entry that does not read is left out alone`() = runTest {
        val raw = "{\"filters\":[{\"id\":\"a\",\"name\":\"x\",\"values\":{\"SMELL\":[\"ROSE\"]}}," +
            "{\"id\":\"b\",\"name\":\"Atmos\",\"values\":{\"AUDIO\":[\"ATMOS\"]}},7]," +
            "\"pins\":[{\"type\":\"playlist\",\"id\":\"p\"},{\"type\":\"filter\",\"id\":\"b\"},{\"type\":\"addon\",\"name\":\"Comet\"}]}"
        profiles[1] = TestPreferencesDataStore(preferencesOf(stateKey to raw))
        val saved = store.state.first()
        assertEquals(listOf("Atmos"), saved.filters.map { it.name })
        assertEquals(listOf(StreamPin.Filter("b"), StreamPin.Addon("Comet")), saved.pins)
    }

    @Test
    fun `what was stored is cleaned as it is read`() = runTest {
        val raw = "{\"filters\":[{\"id\":\"a\",\"name\":\"Atmos\",\"values\":{\"AUDIO\":[\"ATMOS\"]},\"later\":1}]," +
            "\"pins\":[{\"type\":\"filter\",\"id\":\"gone\"},{\"type\":\"addon\",\"name\":\"Comet\"}]}"
        profiles[1] = TestPreferencesDataStore(preferencesOf(stateKey to raw))
        val saved = store.state.first()
        assertEquals(atmos, saved.filters.single().filter)
        assertEquals(listOf(StreamPin.Addon("Comet")), saved.pins)
    }

    @Test
    fun `the phone's edits are stored and returned, a deleted filter leaving with its pin`() = runTest {
        store.save(atmos, "Atmos")
        store.save(uhd, "4K")
        val (first, second) = store.state.first().filters.map { it.id }
        store.togglePin(StreamPin.Filter(first))
        store.togglePin(StreamPin.Filter(second))

        val stored = store.applyEdits(order = listOf(SavedFilterEdit(second, " Movie  night ")), deleted = setOf(first))
        assertEquals(listOf(second to "Movie night"), stored.filters.map { it.id to it.name })
        assertEquals(uhd, stored.filters.single().filter)
        assertEquals(listOf(StreamPin.Filter(second)), stored.pins)
        assertEquals(stored, store.state.first())
        // Edits that change nothing still answer with what is stored.
        assertEquals(stored, store.applyEdits(order = emptyList(), deleted = setOf("gone")))
    }

    @Test
    fun `a name saved on the TV is bounded like one from the phone`() = runTest {
        store.save(atmos, "x".repeat(200))
        assertEquals(SavedStreamFilters.MAX_NAME_LENGTH, store.state.first().filters.single().name.length)
    }
}
