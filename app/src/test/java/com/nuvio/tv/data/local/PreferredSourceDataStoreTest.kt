package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.domain.model.ReleaseResolution
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

/** The per-profile store of a show's preferred source (Spec 65 P6). */
class PreferredSourceDataStoreTest {

    private val profiles = mutableMapOf<Int, TestPreferencesDataStore>(
        1 to TestPreferencesDataStore(),
        2 to TestPreferencesDataStore()
    )
    private val activeProfileIdFlow = MutableStateFlow(1)
    private var nowMs = 1_000L
    private val store = PreferredSourceDataStore(
        factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), PreferredSourceDataStore.FEATURE) } answers { profiles.getValue(firstArg()) }
        },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns activeProfileIdFlow },
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs++)
        }
    )

    private val ntb = PreferredSource("AIOStreams", ReleaseResolution.P1080, bingeGroup = "aio|1080p|NTb", releaseGroup = "ntb")
    private val flux = PreferredSource("Torrentio", ReleaseResolution.P2160, releaseGroup = "flux", languages = listOf("EN"))

    private fun key(contentId: String) = stringPreferencesKey("${PreferredSourceDataStore.KEY_PREFIX}$contentId")

    @Test
    fun `one preference per show, replaced by the next and removed by none`() = runTest {
        store.set("tt1", ntb)
        store.set("tt2", flux)
        assertEquals(ntb, store.get("tt1"))
        assertEquals(flux, store.get("tt2"))

        store.set("tt1", flux)
        assertEquals(flux, store.get("tt1"))

        store.set("tt1", null)
        assertNull(store.get("tt1"))
        assertNull(profiles.getValue(1).value[key("tt1")])
        assertEquals(flux, store.get("tt2"))
    }

    @Test
    fun `the stored value names its fields and its save time`() = runTest {
        store.set("tt1", ntb)
        assertEquals(
            """{"source":{"addonName":"AIOStreams","resolution":"P1080","bingeGroup":"aio|1080p|NTb","releaseGroup":"ntb","languages":[]},"at":1000}""",
            profiles.getValue(1).value[key("tt1")]
        )
    }

    @Test
    fun `a preference belongs to the profile that set it`() = runTest {
        store.set("tt1", ntb)
        activeProfileIdFlow.value = 2
        assertNull(store.get("tt1"))
        store.set("tt1", flux)
        activeProfileIdFlow.value = 1
        assertEquals(ntb, store.observe("tt1").first())
    }

    @Test
    fun `a value this build cannot read is none`() = runTest {
        suspend fun seed(raw: String) = profiles.getValue(1).updateData { prefs ->
            prefs.toMutablePreferences().apply { this[key("tt1")] = raw }.toPreferences()
        }
        seed("""{"source":{"addonName":"A","resolution":"P4320"},"at":5}""")
        assertNull(store.get("tt1"))
        seed("not json")
        assertNull(store.get("tt1"))
        seed("""{"source":{"addonName":"A","resolution":"P720","later":"field"},"at":5}""")
        assertEquals(PreferredSource("A", ReleaseResolution.P720), store.get("tt1"))
    }

    @Test
    fun `past the cap the oldest preference goes first`() = runTest {
        store.set("oldest", ntb)
        repeat(BoundedPrefs.DEFAULT_MAX_ENTRIES) { index -> store.set("show$index", flux) }

        assertNull(store.get("oldest"))
        assertEquals(flux, store.get("show0"))
        val kept = profiles.getValue(1).value.asMap().keys.count { it.name.startsWith(PreferredSourceDataStore.KEY_PREFIX) }
        assertTrue("kept=$kept", kept == BoundedPrefs.DEFAULT_MAX_ENTRIES)
    }
}
