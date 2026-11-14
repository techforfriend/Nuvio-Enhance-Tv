package com.nuvio.tv.core.network.tls

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.loggedResetCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonCertPinStore"
private const val STORE_NAME = "addon_cert_pins"

/**
 * Spec 68 X7 P4: the device's trusted addon certificates. One device-local file, never synced
 * (D8; `allowBackup=false`). It lives in `files/addon_tls/`, outside `files/datastore/`, so the
 * sign-out sweep of profile datastores never deletes it under this live store; sign-out empties
 * it through [clear] instead.
 *
 * The TLS checks read [pinsBlocking] on OkHttp's connection threads, and only after the platform
 * check failed, so a host with a valid public certificate never waits on disk.
 */
@Singleton
class AddonCertPinStore internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(corruptionHandler = loggedResetCorruptionHandler(STORE_NAME)) {
            File(context.filesDir, "addon_tls/$STORE_NAME.preferences_pb")
        }
    )

    private val key = stringPreferencesKey("pins_json")
    private val serializer = ListSerializer(AddonCertPin.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Null until the first successful read; a failed read is never kept, so the next call reads again. */
    @Volatile
    private var snapshot: AddonCertPins? = null

    /**
     * The pins, read from disk on first use (blocking). Throws when the read fails; the callers
     * treat that as "not pinned", so a disk failure fails closed and the next connection retries.
     */
    internal fun pinsBlocking(): AddonCertPins = snapshot ?: runBlocking { current() }

    internal suspend fun current(): AddonCertPins = snapshot ?: mutex.withLock { snapshot ?: load() }

    /**
     * Stores [pin] by D9's rule ([withTrusted]). The snapshot is current when this returns.
     * True when a pin was removed (a renewal), so callers can drop connections it vouched for.
     */
    internal suspend fun put(pin: AddonCertPin): Boolean = update { pins -> pins.withTrusted(pin) }

    /** Drops every pin [drop] matches; true when one was removed. */
    internal suspend fun removeWhere(drop: (AddonCertPin) -> Boolean): Boolean = update { pins -> pins.filterNot(drop) }

    /** Sign-out: the next account starts with no trusted addon certificates. */
    internal suspend fun clear(): Boolean = update { emptyList() }

    /** True when [transform] removed (or replaced) a stored pin. */
    private suspend fun update(transform: (List<AddonCertPin>) -> List<AddonCertPin>): Boolean =
        mutex.withLock {
            var removed = false
            val updated = dataStore.edit { prefs ->
                val before = decode(prefs[key])
                val next = transform(before)
                removed = before.any { it !in next }
                if (next.isEmpty()) prefs.remove(key) else prefs[key] = json.encodeToString(serializer, next)
            }
            snapshot = AddonCertPins(decode(updated[key]))
            removed
        }

    private suspend fun load(): AddonCertPins =
        AddonCertPins(decode(dataStore.data.first()[key])).also { snapshot = it }

    /** A value that does not decode reads as no pins (fails closed: those hosts ask again). */
    private fun decode(raw: String?): List<AddonCertPin> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: IllegalArgumentException) { // SerializationException included
            Log.w(TAG, "Pins did not decode (${e.javaClass.simpleName}); reading as none")
            emptyList()
        }
    }
}
