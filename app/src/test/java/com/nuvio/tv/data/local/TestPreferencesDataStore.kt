package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Shared in-memory DataStore<Preferences> fake for the data/local store tests. */
open class TestPreferencesDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    val value: Preferences
        get() = state.value

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        return mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}

/**
 * Suspends before each read and each write, so changes and profile switches can interleave the
 * way they do on the device.
 */
class YieldingPreferencesDataStore : TestPreferencesDataStore() {
    override val data: Flow<Preferences> = flow {
        yield()
        emitAll(super.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        yield()
        return super.updateData(transform)
    }
}
