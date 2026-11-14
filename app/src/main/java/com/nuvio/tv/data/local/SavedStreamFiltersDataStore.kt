package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.SavedFilterEdit
import com.nuvio.tv.core.stream.SavedStreamFilter
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamPin
import com.nuvio.tv.core.stream.savedFilterName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A profile's saved stream filters and Sources-row pins (Spec 65 P2), one JSON value. Bounded by
 * [SavedStreamFilters.MAX_FILTERS] and [SavedStreamFilters.MAX_PINS]. Stored locally only, not
 * synced.
 */
@Singleton
class SavedStreamFiltersDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        internal const val FEATURE = "saved_stream_filters"
        private const val TAG = "SavedStreamFilters"
    }

    private val stateKey = stringPreferencesKey("state")

    /** The active profile's saved filters and pins. */
    val state: Flow<SavedStreamFilters> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> decode(prefs[stateKey]) }
    }.distinctUntilChanged()

    /**
     * Saves [filter]'s values under [name] as [savedFilterName] keeps it; nothing when that leaves no
     * name, the values are already saved, none is on, or the list is full.
     */
    suspend fun save(filter: FacetFilter, name: String) {
        val kept = savedFilterName(name) ?: return
        update { it.withSaved(UUID.randomUUID().toString(), kept, filter) }
    }

    /**
     * Applies the phone page's list ([SavedStreamFilters.edited]: order, names, [deleted]) and
     * returns what is stored after it.
     */
    suspend fun applyEdits(order: List<SavedFilterEdit>, deleted: Set<String>): SavedStreamFilters =
        update { it.edited(order, deleted) }

    /** Pins [pin] to the row, or unpins it. */
    suspend fun togglePin(pin: StreamPin) {
        update { it.withPinToggled(pin) }
    }

    /**
     * Read and write in one edit, on the profile active when the change was asked for (the
     * SearchHistoryDataStore lesson: overlapping changes both land, none crosses a profile switch).
     * Returns what is stored after it.
     */
    private suspend fun update(change: (SavedStreamFilters) -> SavedStreamFilters): SavedStreamFilters {
        val profileId = profileManager.activeProfileId.value
        var stored = SavedStreamFilters.NONE
        factory.get(profileId, FEATURE).edit { prefs ->
            val current = decode(prefs[stateKey])
            val next = change(current)
            if (next != current) prefs[stateKey] = localStoreJson.encodeToString(SavedStreamFilters.serializer(), next)
            stored = next
        }
        return stored
    }

    /**
     * The stored value, read entry by entry: a filter or pin that does not read (a filter group
     * or pin kind this build has no name for) is left out alone, the rest is kept. A value that
     * is no JSON object at all is none.
     */
    private fun decode(raw: String?): SavedStreamFilters {
        if (raw.isNullOrBlank()) return SavedStreamFilters.NONE
        val root = try {
            localStoreJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        if (root == null) {
            Log.w(TAG, "saved filters unreadable")
            return SavedStreamFilters.NONE
        }
        var dropped = 0
        fun <T> entries(key: String, serializer: KSerializer<T>): List<T> =
            (root[key] as? JsonArray).orEmpty().mapNotNull { element ->
                try {
                    localStoreJson.decodeFromJsonElement(serializer, element)
                } catch (e: IllegalArgumentException) {
                    // SerializationException included. Counted, never printed: it quotes the entry.
                    dropped += 1
                    null
                }
            }
        val read = SavedStreamFilters(
            filters = entries("filters", SavedStreamFilter.serializer()),
            pins = entries("pins", StreamPin.serializer())
        )
        if (dropped > 0) Log.w(TAG, "saved filters: $dropped unreadable entries left out")
        return read.normalized()
    }
}
