package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** L5-12: lock-state pulls and local PIN writes on the persisted pin-enabled map. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileLockStateDataStoreTest {

    @Test
    fun `a pull that read the server before a local PIN write cannot clear that PIN`() = runTest {
        val store = ProfileLockStateDataStore(TestPreferencesDataStore())
        val serverRead = CompletableDeferred<Unit>()
        val releasePull = CompletableDeferred<Unit>()
        launch {
            store.refreshPinEnabled {
                serverRead.complete(Unit)
                releasePull.await()
                Result.success(mapOf(1 to false))
            }
        }
        serverRead.await()

        launch { store.setPinEnabled(1, true) }
        runCurrent()
        releasePull.complete(Unit)
        advanceUntilIdle()

        assertEquals(mapOf(1 to true), store.pinEnabled.first())
    }

    @Test
    fun `a successful pull replaces the map and a failed pull keeps it`() = runTest {
        val store = ProfileLockStateDataStore(TestPreferencesDataStore())
        store.setPinEnabled(1, true)

        store.refreshPinEnabled { Result.success(mapOf(2 to true)) }
        store.refreshPinEnabled { Result.failure(IllegalStateException("offline")) }

        assertEquals(mapOf(2 to true), store.pinEnabled.first())
    }

    @Test
    fun `ask-for-PIN-again defaults to 15 min, round-trips, and sign-out resets it`() = runTest {
        val store = ProfileLockStateDataStore(TestPreferencesDataStore())
        assertEquals(15, store.relockAfterMinutes.first())

        store.setRelockAfterMinutes(45)
        assertEquals(45, store.relockAfterMinutes.first())
        store.setRelockAfterMinutes(0)
        assertEquals(0, store.relockAfterMinutes.first())

        store.clearAll()
        assertEquals(15, store.relockAfterMinutes.first())
    }

    @Test
    fun `a stored ask-for-PIN-again value the picker does not offer reads as 15 min`() = runTest {
        listOf(7, -1).forEach { junk ->
            val store = ProfileLockStateDataStore(
                TestPreferencesDataStore(preferencesOf(intPreferencesKey("relock_after_minutes") to junk))
            )
            assertEquals("$junk", 15, store.relockAfterMinutes.first())
        }
    }
}
