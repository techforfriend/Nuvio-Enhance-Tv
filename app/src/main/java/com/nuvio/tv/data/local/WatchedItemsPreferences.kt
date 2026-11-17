package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A title's art as stored on its watched items (Spec 118 R1). */
internal data class WatchedArt(val poster: String?, val backdrop: String?)

internal fun WatchedItem.hasArt(): Boolean = poster != null || backdrop != null

/** Per title, the newest poster and the newest backdrop its items carry (either may be null). */
internal fun watchedArtByContent(items: Iterable<WatchedItem>): Map<String, WatchedArt> =
    items.filter { it.hasArt() }
        .groupBy { it.contentId }
        .mapValues { (_, group) ->
            val newestFirst = group.sortedByDescending { it.watchedAt }
            WatchedArt(newestFirst.firstNotNullOfOrNull { it.poster }, newestFirst.firstNotNullOfOrNull { it.backdrop })
        }

/**
 * [this] with each art field it lacks taken from its title's art in [art]. Synced and newly marked
 * items come without art (the sync model has no art field), so every write keeps what was already
 * hydrated; a field the item has is never replaced.
 */
internal fun WatchedItem.withArtFrom(art: Map<String, WatchedArt>): WatchedItem {
    if (poster != null && backdrop != null) return this
    val known = art[contentId] ?: return this
    return copy(poster = poster ?: known.poster, backdrop = backdrop ?: known.backdrop)
}

@Singleton
class WatchedItemsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_items_preferences"
        private const val TAG = "WatchedItemsPrefs"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val watchedItemsKey = stringSetPreferencesKey("watched_items")
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_watched_push_ms")
    private val deltaCursorKey = longPreferencesKey("watched_items_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watched_items_delta_initialized")

    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    suspend fun setLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[lastSuccessfulPushMsKey] = timestampMs
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val prefs = store(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    internal val allItems: Flow<List<WatchedItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val raw = preferences[watchedItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                decodeWatchedItem(json)
            }
        }.flowOn(Dispatchers.Default)
    }

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return allItems.map { items ->
            items.any { item ->
                item.contentId == contentId &&
                    item.season == season &&
                    item.episode == episode
            }
        }
    }

    fun getWatchedEpisodesForContent(contentId: String): Flow<Set<Pair<Int, Int>>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }
    }

    fun getWatchedEpisodesWithTimestamps(contentId: String): Flow<Map<Pair<Int, Int>, Long>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associate { (it.season!! to it.episode!!) to it.watchedAt }
        }
    }

    suspend fun markAsWatched(
        item: WatchedItem,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val itemKey = watchedItemKey(item)
            val art = artFor(current, setOf(item.contentId))
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) == itemKey
            }
            preferences[watchedItemsKey] = filtered.toSet() + encodeWatchedItem(item.withArtFrom(art))
        }
    }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val newKeys = items.map { watchedItemKey(it) }.toSet()
            val art = artFor(current, items.mapTo(HashSet()) { it.contentId })
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) in newKeys
            }
            preferences[watchedItemsKey] = filtered.toSet() + items.map { encodeWatchedItem(it.withArtFrom(art)) }
        }
    }

    suspend fun unmarkAsWatched(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            // null season/episode = whole-content removal (series-level AND per-episode
            // entries), mirroring WatchProgressPreferences.removeProgress's null-null
            // semantics — otherwise a series unmark leaves episode flags behind that can
            // re-flip the watched badge on the next pipeline run.
            val filtered = if (season == null && episode == null) {
                current.filterNot { json ->
                    extractWatchedItemKey(json).substringBefore('|') == contentId
                }
            } else {
                val removeKey = buildWatchedKey(contentId, season, episode)
                current.filterNot { json -> extractWatchedItemKey(json) == removeKey }
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        val removeKeys = episodes.map { (s, e) -> buildWatchedKey(contentId, s, e) }.toSet()
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) in removeKeys
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(profileId: Int = profileManager.activeProfileId.value): List<WatchedItem> {
        val preferences = store(profileId).data.first()
        return (preferences[watchedItemsKey] ?: emptySet()).mapNotNull { raw ->
            decodeWatchedItem(raw)
        }
    }

    suspend fun applyRemoteChanges(
        upserts: List<WatchedItem>,
        deletes: List<Triple<String, Int?, Int?>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return
        }
        var beforeCount = 0
        var afterCount = 0
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            beforeCount = current.size
            val itemsByKey = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            val currentItems = current.mapNotNull { json ->
                decodeWatchedItem(json)
            }
            currentItems.forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item
            }
            deletes.forEach { key ->
                itemsByKey.remove(key)
            }
            val art = watchedArtByContent(currentItems)
            upserts.forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item.withArtFrom(art)
            }
            preferences[watchedItemsKey] = itemsByKey.values
                .map(::encodeWatchedItem)
                .toSet()
            afterCount = itemsByKey.size
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size}")
    }

    suspend fun replaceWithRemoteItems(
        remoteItems: List<WatchedItem>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        var preservedLocalItems = false
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId current=${current.size} remote=${remoteItems.size} lastPush=$lastSuccessfulPushMs")
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteItems: remote list empty while local has ${current.size} entries; preserving local watched items")
                return@edit
            }
            val localItems = current.mapNotNull { json ->
                decodeWatchedItem(json)
            }
            val art = watchedArtByContent(localItems)
            val deduped = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            remoteItems.forEach { item ->
                deduped[Triple(item.contentId, item.season, item.episode)] = item.withArtFrom(art)
            }
            // Preserve local items marked as watched after the profile's push watermark:
            // they may not have reached remote yet, so their absence doesn't mean deletion
            // on another device. A watermark of 0 (never stamped) protects every local item
            // (L9-10): the old `> 0L` guard mirrored remote instead, deleting any item whose
            // incremental push was skipped or failed, and since a full push only runs when
            // something was preserved, the watermark could never get stamped.
            localItems.forEach { localItem ->
                val key = Triple(localItem.contentId, localItem.season, localItem.episode)
                if (key !in deduped && localItem.watchedAt > lastSuccessfulPushMs) {
                    deduped[key] = localItem
                    preservedLocalItems = true
                    Log.d(TAG, "replaceWithRemoteItems: preserved local item ${localItem.contentId} s${localItem.season}e${localItem.episode} (watchedAt=${localItem.watchedAt} > lastPush=$lastSuccessfulPushMs)")
                }
            }
            preferences[watchedItemsKey] = deduped.values
                .map(::encodeWatchedItem)
                .toSet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId stored=${deduped.size} preservedLocal=$preservedLocalItems")
        }
        return preservedLocalItems
    }

    /**
     * Fills the missing poster/backdrop of every item of each title in [art] (by contentId) in
     * [profileId], in one edit (Spec 118 R1). A field an item has is kept, and an item removed
     * meanwhile stays removed.
     */
    internal suspend fun mergeArtwork(art: Map<String, WatchedArt>, profileId: Int) {
        val usable = art.filterValues { it.poster != null || it.backdrop != null }
        if (usable.isEmpty()) return
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: return@edit
            preferences[watchedItemsKey] = current.mapTo(HashSet(current.size)) { json ->
                if (extractWatchedItemKey(json).substringBefore('|') !in usable) return@mapTo json
                val item = decodeWatchedItem(json) ?: return@mapTo json
                val filled = item.withArtFrom(usable)
                if (filled == item) json else encodeWatchedItem(filled)
            }
        }
    }

    /** The art of [contentIds]' stored items, decoding only those titles' entries. */
    private fun artFor(current: Set<String>, contentIds: Set<String>): Map<String, WatchedArt> =
        watchedArtByContent(
            current.filter { extractWatchedItemKey(it).substringBefore('|') in contentIds }
                .mapNotNull(::decodeWatchedItem)
        )

    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            preferences.remove(watchedItemsKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }

    private fun decodeWatchedItem(json: String): WatchedItem? =
        runCatching { localStoreJson.decodeFromString(WatchedItem.serializer(), json) }.getOrNull()

    private fun encodeWatchedItem(item: WatchedItem): String =
        localStoreJson.encodeToString(WatchedItem.serializer(), item)

    private fun watchedItemKey(item: WatchedItem): String =
        buildWatchedKey(item.contentId, item.season, item.episode)

    private fun buildWatchedKey(contentId: String, season: Int?, episode: Int?): String =
        "$contentId|${season ?: "_"}|${episode ?: "_"}"

    /**
     * Extracts a composite key from a raw JSON string without full deserialization.
     * Looks for "contentId", "season", "episode" fields via simple string search.
     * Falls back to a full decode only if the fast path fails.
     */
    private fun extractWatchedItemKey(json: String): String {
        val contentId = extractJsonStringField(json, "contentId")
        val season = extractJsonIntField(json, "season")
        val episode = extractJsonIntField(json, "episode")
        if (contentId != null) {
            return buildWatchedKey(contentId, season, episode)
        }
        // Fallback: full deserialization (should rarely happen with well-formed JSON)
        val item = decodeWatchedItem(json)
            ?: return json // use raw json as unique key for malformed entries
        return watchedItemKey(item)
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val marker = "\"$field\""
        val fieldIdx = json.indexOf(marker)
        if (fieldIdx < 0) return null
        val colonIdx = json.indexOf(':', fieldIdx + marker.length)
        if (colonIdx < 0) return null
        val openQuote = json.indexOf('"', colonIdx + 1)
        if (openQuote < 0) return null
        val closeQuote = json.indexOf('"', openQuote + 1)
        if (closeQuote < 0) return null
        return json.substring(openQuote + 1, closeQuote)
    }

    private fun extractJsonIntField(json: String, field: String): Int? {
        val marker = "\"$field\""
        val fieldIdx = json.indexOf(marker)
        if (fieldIdx < 0) return null
        val colonIdx = json.indexOf(':', fieldIdx + marker.length)
        if (colonIdx < 0) return null
        // Skip whitespace after colon
        var i = colonIdx + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        // Handle null
        if (json.startsWith("null", i)) return null
        // Parse integer
        val start = i
        if (json[i] == '-') i++
        while (i < json.length && json[i].isDigit()) i++
        if (i == start) return null
        return json.substring(start, i).toIntOrNull()
    }
}
