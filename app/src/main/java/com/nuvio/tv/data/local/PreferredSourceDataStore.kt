package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.stream.PreferredSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * A profile's preferred source per show (Spec 65 P6): content id -> [PreferredSource], one per
 * show, replaced by the next. Each value carries its save time so [BoundedPrefs] drops the oldest
 * past the cap. Stored locally only, not synced.
 */
@Singleton
class PreferredSourceDataStore internal constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val clock: Clock
) {
    @Inject
    constructor(factory: ProfileDataStoreFactory, profileManager: ProfileManager) :
        this(factory, profileManager, Clock.System)

    companion object {
        internal const val FEATURE = "preferred_source"
        internal const val KEY_PREFIX = "ps_"
        private const val TAG = "PreferredSource"
    }

    /** What is stored: [at] is the save time in epoch milliseconds. */
    @Serializable
    private data class Entry(val source: PreferredSource, val at: Long = 0L)

    private fun prefKey(contentId: String) = stringPreferencesKey("$KEY_PREFIX$contentId")

    /** The active profile's preference for [contentId], and every change to it. */
    fun observe(contentId: String): Flow<PreferredSource?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> decode(prefs[prefKey(contentId)])?.source }
    }.distinctUntilChanged()

    suspend fun get(contentId: String): PreferredSource? = observe(contentId).first()

    /** Prefers [source] for [contentId], in place of what was preferred; null stops preferring. */
    suspend fun set(contentId: String, source: PreferredSource?) {
        // On the profile active when it was asked for: a change never crosses a profile switch.
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            if (source == null) {
                prefs.remove(prefKey(contentId))
            } else {
                prefs[prefKey(contentId)] = localStoreJson.encodeToString(
                    Entry.serializer(),
                    Entry(source, clock.now().toEpochMilliseconds())
                )
                enforceEntryCap(prefs)
            }
        }
    }

    /** An entry that does not read (a resolution this build has no name for) is none. */
    private fun decode(raw: String?): Entry? {
        if (raw.isNullOrBlank()) return null
        return try {
            localStoreJson.decodeFromString(Entry.serializer(), raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException included. Never printed: the message quotes the entry.
            Log.w(TAG, "preferred source unreadable")
            null
        }
    }

    private fun enforceEntryCap(prefs: MutablePreferences) {
        val timestamps = prefs.asMap()
            .filterKeys { it.name.startsWith(KEY_PREFIX) }
            .entries.associate { (key, value) -> key.name to (decode(value as? String)?.at ?: 0L) }
        BoundedPrefs.planEviction(timestamps).forEach { keyName ->
            prefs.remove(stringPreferencesKey(keyName))
        }
    }
}
