package com.nuvio.tv.livetv.data

import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.decodeStringList
import com.nuvio.tv.data.local.encodeStringList
import com.nuvio.tv.data.local.localStoreJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A channel the profile watched (Recents, Spec 34 L8-D2): its key and when it entered Recents. */
@Serializable
data class RecentChannel(val key: String, val atMs: Long)

/**
 * One profile's Live TV state (Spec 34 L8-D1): [favorites] in add order, [recents] newest first
 * (its head is the last-watched channel, Q1), [lastFilter] the id of the filter last selected,
 * [lastView] the id of the view last picked (Spec 67 D7 Q1: List, Guide or Compact).
 */
data class LiveTvUserState(
    val favorites: List<String> = emptyList(),
    val recents: List<RecentChannel> = emptyList(),
    val lastFilter: String? = null,
    val lastView: String? = null
) {
    val recentKeys: List<String> get() = recents.map { it.key }
}

/**
 * Favorites, Recents, the last filter and the last view, per profile (Spec 34 L8-D1): a DataStore of its own
 * through [ProfileDataStoreFactory], so toggling a favorite never rewrites the channel cache
 * (D11) and profile deletion and sign-out sweep it with the other profile files. Bounded:
 * [MAX_FAVORITES] (oldest dropped) and [MAX_RECENTS]. Writes report failure (a full disk) instead
 * of throwing. Also each profile's Live TV switch (D17, L9): [enabledFor] / [setEnabled], written
 * only by the primary from Manage profiles (the store does not check who calls).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvUserStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    /** The active profile's state; follows a profile switch. An unreadable file reads as empty. */
    val state: Flow<LiveTvUserState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        prefs(profileId).map(::decode)
    }

    /** Whether the active profile has Live TV (D17); follows a profile switch. */
    val activeEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest(::enabledFor)

    /** Profile [profileId]'s Live TV switch (D17): on unless turned off; an unreadable file reads as on. */
    fun enabledFor(profileId: Int): Flow<Boolean> =
        prefs(profileId).map { it[ENABLED] ?: true }.distinctUntilChanged()

    /** Sets profile [profileId]'s Live TV switch; false when the file cannot be written. */
    suspend fun setEnabled(profileId: Int, enabled: Boolean): Boolean = write(profileId) { prefs ->
        prefs[ENABLED] = enabled
    }

    private fun prefs(profileId: Int): Flow<Preferences> = factory.get(profileId, FEATURE).data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /**
     * Adds channel [key] to Favorites, or removes it when it is one; false when the file cannot be
     * written. Decided inside the edit, so two quick toggles never both add.
     */
    suspend fun toggleFavorite(key: String): Boolean = write { prefs ->
        val favorites = decodeFavorites(prefs[FAVORITES])
        prefs[FAVORITES] = encodeStringList(withFavorite(favorites, key, key !in favorites))
    }

    /** Channel [key] played long enough to count as watched at [atMs] (L8-D2): Recents head. */
    suspend fun recordWatched(key: String, atMs: Long): Boolean = write { prefs ->
        val updated = withRecent(decodeRecents(prefs[RECENTS]), key, atMs)
        prefs[RECENTS] = localStoreJson.encodeToString(RECENTS_SERIALIZER, updated)
    }

    suspend fun setLastFilter(id: String): Boolean = write { prefs -> prefs[LAST_FILTER] = id }

    suspend fun setLastView(id: String): Boolean = write { prefs -> prefs[LAST_VIEW] = id }

    private suspend fun write(
        profileId: Int = profileManager.activeProfileId.value,
        block: (MutablePreferences) -> Unit
    ): Boolean = try {
        factory.get(profileId, FEATURE).edit(block)
        true
    } catch (e: IOException) {
        Log.w(TAG, "Live TV user state not saved: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "LiveTvUserStore"
        private const val FEATURE = "live_tv"
        const val MAX_FAVORITES = 500
        const val MAX_RECENTS = 20

        private val FAVORITES = stringPreferencesKey("favorites")
        private val RECENTS = stringPreferencesKey("recents")
        private val LAST_FILTER = stringPreferencesKey("last_filter")
        private val LAST_VIEW = stringPreferencesKey("last_view")
        private val ENABLED = booleanPreferencesKey("enabled")
        private val RECENTS_SERIALIZER = ListSerializer(RecentChannel.serializer())

        internal fun decode(prefs: Preferences): LiveTvUserState = LiveTvUserState(
            favorites = decodeFavorites(prefs[FAVORITES]),
            recents = decodeRecents(prefs[RECENTS]),
            lastFilter = prefs[LAST_FILTER],
            lastView = prefs[LAST_VIEW]
        )

        /** Junk or a missing value reads as empty; repeats and blanks dropped, the cap held. */
        internal fun decodeFavorites(raw: String?): List<String> =
            raw?.let(::decodeStringList).orEmpty().filter { it.isNotBlank() }.distinct().takeLast(MAX_FAVORITES)

        internal fun decodeRecents(raw: String?): List<RecentChannel> {
            if (raw.isNullOrBlank()) return emptyList()
            val decoded = try {
                localStoreJson.decodeFromString(RECENTS_SERIALIZER, raw)
            } catch (_: IllegalArgumentException) {
                // SerializationException included (it extends IllegalArgumentException).
                emptyList()
            }
            return decoded.filter { it.key.isNotBlank() }.distinctBy { it.key }.take(MAX_RECENTS)
        }

        /** [favorites] with [key] added last (oldest dropped past the cap) or removed. */
        internal fun withFavorite(favorites: List<String>, key: String, favorite: Boolean): List<String> = when {
            !favorite -> favorites - key
            key in favorites -> favorites
            else -> (favorites + key).takeLast(MAX_FAVORITES)
        }

        /** [recents] with [key] moved (or added) to the front at [atMs], at most [MAX_RECENTS]. */
        internal fun withRecent(recents: List<RecentChannel>, key: String, atMs: Long): List<RecentChannel> =
            (listOf(RecentChannel(key, atMs)) + recents.filter { it.key != key }).take(MAX_RECENTS)
    }
}
