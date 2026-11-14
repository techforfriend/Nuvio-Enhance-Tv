package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.ContentRatingCap
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Spec 64 K1a: the per-profile cap round-trips, clears, and fails closed on junk or a bad file. */
class ProfileRatingCapStoreTest {

    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private val factory = mockk<ProfileDataStoreFactory>().also { f ->
        every { f.get(any(), ProfileRatingCapStore.FEATURE) } answers { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
    }
    private val store = ProfileRatingCapStore(factory)

    @Test
    fun `a cap round-trips per profile and other profiles stay uncapped`() = runTest {
        assertNull(store.capFor(2).first())
        assertTrue(store.write(2, ContentRatingCap.OLDER_KIDS))
        assertEquals(ContentRatingCap.OLDER_KIDS, store.capFor(2).first())
        assertNull(store.capFor(1).first())
        assertNull(store.capFor(3).first())
    }

    @Test
    fun `every cap round-trips through its storage key`() = runTest {
        ContentRatingCap.entries.forEach { cap ->
            assertTrue(store.write(2, cap))
            assertEquals(cap, store.capFor(2).first())
        }
    }

    @Test
    fun `writing null removes the cap and leaves an empty file`() = runTest {
        assertTrue(store.write(2, ContentRatingCap.TEENS))
        assertTrue(store.write(2, null))
        assertNull(store.capFor(2).first())
        assertEquals(0, (stores.getValue(2) as TestPreferencesDataStore).value.asMap().size)
    }

    @Test
    fun `an unknown stored value reads as the strictest cap`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("cap") to "toddlers")
        assertEquals(ContentRatingCap.ALL_AGES, ProfileRatingCapStore.decode(prefs))
    }

    @Test
    fun `an unreadable file reads as the strictest cap and a failed write reports false`() = runTest {
        stores[4] = object : TestPreferencesDataStore() {
            override val data = flow<Preferences> { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        assertEquals(ContentRatingCap.ALL_AGES, store.capFor(4).first())
        assertFalse(store.write(4, null))
    }
}
