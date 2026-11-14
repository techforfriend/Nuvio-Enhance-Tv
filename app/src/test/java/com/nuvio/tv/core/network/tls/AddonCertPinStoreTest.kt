package com.nuvio.tv.core.network.tls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Spec 68 X7 P4: the device pin store. */
class AddonCertPinStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileStore(): Pair<AddonCertPinStore, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create { File(tmp.root, "addon_tls/pins.preferences_pb") }
        return AddonCertPinStore(dataStore) to dataStore
    }

    @Test
    fun `a stored pin is read back, keyed by lower-case host and port`() = runBlocking {
        val (store, _) = fileStore()
        store.put(AddonCertPin("NAS.lan", 8443, "AA", 1L))
        store.put(AddonCertPin("nas.lan", 9443, "BB", 2L))
        val pins = store.pinsBlocking()
        assertTrue(pins.isPinnedForHost("nas.lan", "AA"))
        assertTrue(pins.isPinnedForHost("nas.lan", "BB"))
        assertEquals(2, pins.pins.size)
    }

    @Test
    fun `a pin for the same host and port replaces the old one`() = runBlocking {
        val (store, _) = fileStore()
        store.put(AddonCertPin("nas.lan", 8443, "AA", 1L))
        store.put(AddonCertPin("nas.lan", 8443, "CC", 3L))
        val pins = store.pinsBlocking()
        assertFalse(pins.containsFingerprint("AA"))
        assertTrue(pins.containsFingerprint("CC"))
    }

    @Test
    fun `D9 - two services on one host each keep their pin`() = runBlocking {
        val (store, _) = fileStore()
        assertFalse(store.put(AddonCertPin("nas.lan", 8443, "XX", 1L)))
        assertFalse(store.put(AddonCertPin("nas.lan", 9443, "YY", 2L)))
        val pins = store.pinsBlocking()
        assertTrue(pins.isPinnedForHost("nas.lan", "XX"))
        assertTrue(pins.isPinnedForHost("nas.lan", "YY"))
    }

    @Test
    fun `D9 - a renewal replaces the port's pin and drops the shared old pin, nothing else`() = runBlocking {
        // Seeded (P3: user actions cannot store one certificate twice): the old certificate on two
        // ports, a third service on this host, the same fingerprint on another host.
        val seededStore = fileStoreWith(
            listOf(
                AddonCertPin("nas.lan", 8443, "OLD", 1L),
                AddonCertPin("nas.lan", 9443, "OLD", 1L),
                AddonCertPin("nas.lan", 7443, "OTHER", 1L),
                AddonCertPin("box.lan", 443, "OLD", 1L)
            )
        )
        assertTrue(seededStore.put(AddonCertPin("nas.lan", 8443, "NEW", 2L)))
        val pins = seededStore.pinsBlocking().pins.map { "${it.host}:${it.port}=${it.sha256}" }.toSet()
        assertEquals(setOf("nas.lan:8443=NEW", "nas.lan:7443=OTHER", "box.lan:443=OLD"), pins)
    }

    @Test
    fun `trusting the fingerprint a port already holds changes nothing`() = runBlocking {
        val (store, _) = fileStore()
        store.put(AddonCertPin("nas.lan", 8443, "AA", 1L))
        assertFalse(store.put(AddonCertPin("nas.lan", 8443, "AA", 5L)))
        assertEquals(1L, store.pinsBlocking().pins.single().trustedAtMs)
    }

    private suspend fun fileStoreWith(pins: List<AddonCertPin>): AddonCertPinStore {
        val dataStore = PreferenceDataStoreFactory.create { File(tmp.root, "seeded/pins.preferences_pb") }
        dataStore.edit {
            it[stringPreferencesKey("pins_json")] = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AddonCertPin.serializer()), pins
            )
        }
        return AddonCertPinStore(dataStore)
    }

    @Test
    fun `clear empties the store`() = runBlocking {
        val (store, _) = fileStore()
        store.put(AddonCertPin("nas.lan", 8443, "AA", 1L))
        store.clear()
        assertTrue(store.pinsBlocking().pins.isEmpty())
    }

    @Test
    fun `an undecodable value reads as no pins`() = runBlocking {
        val (store, dataStore) = fileStore()
        dataStore.edit { it[stringPreferencesKey("pins_json")] = "{not json" }
        assertTrue(store.pinsBlocking().pins.isEmpty())
    }

    @Test
    fun `a failed read is not kept, the next read tries again`() {
        val good = mutablePreferencesOf(
            stringPreferencesKey("pins_json") to """[{"host":"nas.lan","port":8443,"sha256":"AA","trustedAtMs":1}]"""
        )
        var fail = true
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (fail) throw IOException("disk") else emit(good)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw UnsupportedOperationException()
        }
        val store = AddonCertPinStore(flaky)
        try {
            store.pinsBlocking()
            fail("expected the read failure to surface")
        } catch (e: IOException) {
            // expected: callers treat it as "not pinned"
        }
        fail = false
        assertTrue(store.pinsBlocking().isPinnedForHost("nas.lan", "AA"))
    }
}
