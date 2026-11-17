package com.nuvio.tv.core.network.dns

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PrivateDnsSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { tmp.root.resolve("private_dns.preferences_pb") }

    @Test
    fun `off by default, then the saved choice`() = runBlocking {
        val dataStore = store()
        val settings = PrivateDnsSettings(dataStore)
        assertEquals(PrivateDnsProvider.OFF, settings.providerBlocking())
        settings.set(PrivateDnsProvider.QUAD9)
        assertEquals(PrivateDnsProvider.QUAD9, settings.providerBlocking())
        assertEquals("persisted, not only kept in memory", PrivateDnsProvider.QUAD9, PrivateDnsSettings(dataStore).providerBlocking())
    }

    @Test
    fun `a read that never answers is unreadable within the wait and never throws`() {
        val dataStore = mockk<DataStore<Preferences>> { every { data } returns flow { awaitCancellation() } }
        val started = System.nanoTime()
        assertNull(PrivateDnsSettings(dataStore).providerBlocking())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < PrivateDnsSettings.FIRST_READ_WAIT_MS + 500)
    }

    @Test
    fun `a failing read is unreadable, not off, and not kept`() {
        var fail = true
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flow {
                if (fail) throw IOException("disk")
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
        }
        val settings = PrivateDnsSettings(dataStore)
        assertNull(settings.providerBlocking())
        fail = false
        assertEquals(PrivateDnsProvider.OFF, settings.providerBlocking())
    }

    @Test
    fun `an unknown stored key reads as off`() {
        assertEquals(PrivateDnsProvider.OFF, PrivateDnsProvider.fromKey("custom"))
        assertEquals(PrivateDnsProvider.GOOGLE, PrivateDnsProvider.fromKey("google"))
    }
}
