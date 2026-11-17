package com.nuvio.tv.core.network.dns

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.loggedResetCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PrivateDnsSettings"
private const val STORE_NAME = "private_dns"

/**
 * Spec 97 X5 N4: the Private DNS choice, device-local, never synced. It lives in
 * `files/network_privacy/` (outside `files/datastore/`, so the sign-out sweep never deletes it
 * under this live store) and is kept on sign-out. A corrupt file resets to Off (visible in the
 * picker).
 *
 * Lookups read [providerBlocking] on OkHttp threads: the first read is single-flight and waits
 * at most [FIRST_READ_WAIT_MS]; a read that fails or times out is not kept and reads as null
 * (unreadable), which [PrivateDnsResolver] turns into a failed lookup (fail closed), so no lookup
 * reaches the network's DNS before the setting is known.
 */
@Singleton
class PrivateDnsSettings internal constructor(private val dataStore: DataStore<Preferences>) {

    /** The app's store; it is also [AppDns]'s setting source, set before any request can need it. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(corruptionHandler = loggedResetCorruptionHandler(STORE_NAME)) {
            File(context.filesDir, "network_privacy/$STORE_NAME.preferences_pb")
        }
    ) {
        source = ::providerBlocking
    }

    private val key = stringPreferencesKey("provider")
    private val lock = ReentrantLock()

    /** Null until a read succeeds; then kept, and updated by [set] (a read never overwrites a set). */
    private val cached = AtomicReference<PrivateDnsProvider?>(null)

    /** The choice, for the settings row (S6). */
    val provider: Flow<PrivateDnsProvider> = dataStore.data.map { PrivateDnsProvider.fromKey(it[key]) }

    /** The choice, or null when it can't be read just now. Never throws. */
    fun providerBlocking(): PrivateDnsProvider? {
        cached.get()?.let { return it }
        // One budget for the lock wait and the read together (N4: at most 2 s per lookup).
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FIRST_READ_WAIT_MS)
        try {
            if (!lock.tryLock(FIRST_READ_WAIT_MS, TimeUnit.MILLISECONDS)) return null
            try {
                cached.get()?.let { return it }
                val leftMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                if (leftMs <= 0) return null
                val read = runBlocking { withTimeout(leftMs) { dataStore.data.first() } }
                cached.compareAndSet(null, PrivateDnsProvider.fromKey(read[key]))
                return cached.get()
            } finally {
                lock.unlock()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "Setting read failed: ${t.javaClass.simpleName}")
        }
        return null
    }

    /** Saves the choice; lookups use it from the next one. */
    suspend fun set(provider: PrivateDnsProvider) {
        dataStore.edit { it[key] = provider.key }
        cached.set(provider)
    }

    companion object {
        /** Process-wide source [AppDns] reads; set by the app's store. Null = no store (Off). */
        @Volatile
        internal var source: (() -> PrivateDnsProvider?)? = null

        const val FIRST_READ_WAIT_MS = 2_000L
    }
}
