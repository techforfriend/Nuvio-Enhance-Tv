package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.withJwtRefreshRetry
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItem
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItemEvent
import com.nuvio.tv.data.repository.WatchedArtworkHydrator
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchedItemsSyncService"
private const val WATCHED_ITEMS_PAGE_SIZE = 900
private const val WATCHED_ITEMS_DELTA_PAGE_SIZE = 900
private const val WATCHED_ITEM_EVENT_UPSERT = "upsert"
private const val WATCHED_ITEM_EVENT_DELETE = "delete"

data class WatchedItemsRemoteSyncResult(
    val upsertedItems: Int,
    val deletedItems: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

@Singleton
class WatchedItemsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity,
    private val pushHealth: SyncPushHealth,
    private val artworkHydrator: WatchedArtworkHydrator
) {
    private val deltaSyncMutex = Mutex()
    private val watermarkGate = PushWatermarkGate()

    /** A watched-items push for [profileId] was skipped (e.g. during a remote apply): see [PushWatermarkGate]. */
    fun markPushSkipped(profileId: Int) = watermarkGate.markUnconfirmed(profileId)

    /** False until a full push or a clean snapshot merge confirmed [profileId] in this process. */
    fun isPushConfirmed(profileId: Int): Boolean = watermarkGate.isConfirmed(profileId)

    private suspend fun fetchDeltaCursor(profileId: Int): Long {
        Log.d(TAG, "fetchDeltaCursor: requesting cursor for profile $profileId")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        return authManager.withJwtRefreshRetry {
            postgrest.rpc("sync_get_watched_items_delta_cursor", params).decodeAs<Long>()
        }.also { cursor ->
            Log.d(TAG, "fetchDeltaCursor: cursor=$cursor for profile $profileId")
        }
    }

    private suspend fun pullDeltaPage(profileId: Int, cursor: Long): List<SupabaseWatchedItemEvent> {
        Log.d(TAG, "pullDeltaPage: requesting events after cursor $cursor for profile $profileId limit=$WATCHED_ITEMS_DELTA_PAGE_SIZE")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_since_event_id", cursor)
            put("p_limit", WATCHED_ITEMS_DELTA_PAGE_SIZE)
        }
        return authManager.withJwtRefreshRetry {
            postgrest.rpc("sync_pull_watched_items_delta", params).decodeList<SupabaseWatchedItemEvent>()
        }.also { events ->
            val firstEvent = events.firstOrNull()?.eventId
            val lastEvent = events.lastOrNull()?.eventId
            val upserts = events.count { it.operation.equals(WATCHED_ITEM_EVENT_UPSERT, ignoreCase = true) }
            val deletes = events.count { it.operation.equals(WATCHED_ITEM_EVENT_DELETE, ignoreCase = true) }
            Log.d(TAG, "pullDeltaPage: received ${events.size} events for profile $profileId first=$firstEvent last=$lastEvent upserts=$upserts deletes=$deletes")
        }
    }

    /** Full push of [profileId]'s watched items; stamps its deletion-protection watermark ([pushFullSnapshot]). */
    suspend fun pushToRemote(profileId: Int = profileManager.activeProfileId.value): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            pushFullSnapshot(
                profileId = profileId,
                gate = watermarkGate,
                readLocal = { watchedItemsPreferences.getAllItems(profileId) },
                push = { items ->
                    Log.d(TAG, "pushToRemote: ${items.size} watched items to push for profile $profileId")
                    sendItems(items, profileId)
                },
                persistWatermark = { watchedItemsPreferences.setLastSuccessfulPushMs(it, profileId) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched items to remote", e)
            Result.failure(e)
        }
    }.also { pushHealth.record(SyncDomain.WATCHED_ITEMS, profileId, it) }

    /**
     * Incremental batch push; on success it advances the profile's watermark while the profile
     * is confirmed ([PushWatermarkGate]).
     */
    suspend fun pushItemsToRemote(
        items: Collection<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Before the gate: an empty batch pushes nothing, so it must not stamp.
        if (items.isEmpty()) return@withContext Result.success(Unit)
        try {
            pushIncremental(
                profileId = profileId,
                gate = watermarkGate,
                push = { sendItems(items, profileId) },
                persistWatermark = { watchedItemsPreferences.setLastSuccessfulPushMs(it, profileId) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched item batch to remote", e)
            Result.failure(e)
        }
    }.also { pushHealth.record(SyncDomain.WATCHED_ITEMS, profileId, it) }

    private suspend fun sendItems(items: Collection<WatchedItem>, profileId: Int): Result<Unit> {
        if (items.isEmpty()) return Result.success(Unit)
        Log.d(TAG, "pushItemsToRemote: ${items.size} watched items to push")
        val params = buildJsonObject {
            put("p_items", buildJsonArray {
                items.forEach { item ->
                    addJsonObject {
                        put("content_id", item.contentId)
                        put("content_type", item.contentType)
                        put("title", item.title)
                        if (item.season != null) put("season", item.season)
                        else put("season", JsonPrimitive(null as Int?))
                        if (item.episode != null) put("episode", item.episode)
                        else put("episode", JsonPrimitive(null as Int?))
                        put("watched_at", item.watchedAt)
                    }
                }
            })
            put("p_profile_id", profileId)
            putSyncOriginClientId(syncClientIdentity)
        }
        authManager.withJwtRefreshRetry {
            postgrest.rpc("sync_push_watched_items", params)
        }

        Log.d(TAG, "Pushed ${items.size} watched items to remote for profile $profileId")
        return Result.success(Unit)
    }

    suspend fun pullFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<List<WatchedItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "pullFromRemote: starting full watched items snapshot for profile $profileId")
            val allItems = mutableListOf<WatchedItem>()
            var page = 1

            while (true) {
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_page", page)
                    put("p_page_size", WATCHED_ITEMS_PAGE_SIZE)
                }
                val response = authManager.withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_watched_items", params)
                }
                val remote = response.decodeList<SupabaseWatchedItem>()

                Log.d(TAG, "pullFromRemote: page $page fetched ${remote.size} watched items for profile $profileId")

                allItems.addAll(remote.map { entry ->
                    WatchedItem(
                        contentId = entry.contentId,
                        contentType = entry.contentType,
                        title = entry.title,
                        season = entry.season,
                        episode = entry.episode,
                        watchedAt = entry.watchedAt
                    )
                })

                if (remote.size < WATCHED_ITEMS_PAGE_SIZE) break
                page++
            }

            Log.d(TAG, "pullFromRemote: fetched ${allItems.size} total watched items from Supabase for profile $profileId")
            Result.success(allItems)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items from remote", e)
            Result.failure(e)
        }
    }

    suspend fun syncDeltaFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchedItemsRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncDeltaFromRemoteLocked(profileId)
        }
    }.also { if (it.isSuccess) artworkHydrator.request(profileId) }

    suspend fun syncSnapshotFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchedItemsRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncSnapshotFromRemoteLocked(profileId)
        }
    }.also { if (it.isSuccess) artworkHydrator.request(profileId) }

    private suspend fun syncSnapshotFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            // Read before any remote fetch: see readPushWatermark.
            val pushWatermarkMs = readPushWatermark(profileId)
            val cursorBeforeSnapshot = try {
                fetchDeltaCursor(profileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "syncSnapshotFromRemote: delta cursor unavailable, applying snapshot without initialized cursor for profile $profileId", e)
                null
            }
            val result = pullSnapshotFromRemote(
                profileId,
                resetDeltaState = cursorBeforeSnapshot == null,
                pushWatermarkMs = pushWatermarkMs
            )
            if (cursorBeforeSnapshot != null) {
                watchedItemsPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items snapshot from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            val pushWatermarkMs = readPushWatermark(profileId)
            val deltaInitialized = watchedItemsPreferences.isDeltaInitialized(profileId)
            val deltaCursor = watchedItemsPreferences.getDeltaCursor(profileId)
            val localCount = watchedItemsPreferences.getAllItems(profileId).size
            Log.d(
                TAG,
                "syncDeltaFromRemote: start profile=$profileId localCount=$localCount deltaInitialized=$deltaInitialized cursor=$deltaCursor lastPush=$pushWatermarkMs"
            )
            if (!deltaInitialized) {
                Log.d(TAG, "syncDeltaFromRemote: delta not initialized, taking one full snapshot for profile $profileId")
                val cursorBeforeSnapshot = try {
                    fetchDeltaCursor(profileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: delta cursor unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true, pushWatermarkMs = pushWatermarkMs)
                    return Result.success(fallbackResult)
                }
                val snapshotResult = pullSnapshotFromRemote(profileId, resetDeltaState = false, pushWatermarkMs = pushWatermarkMs)
                watchedItemsPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
                Log.d(TAG, "syncDeltaFromRemote: initialized cursor $cursorBeforeSnapshot after watched items snapshot for profile $profileId")
                return Result.success(snapshotResult)
            }

            var cursor = deltaCursor
            var totalUpserts = 0
            var totalDeletes = 0
            var page = 1

            while (true) {
                Log.d(TAG, "syncDeltaFromRemote: pulling delta page $page from cursor $cursor for profile $profileId")
                val events = try {
                    pullDeltaPage(profileId, cursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: watched items delta pull unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true, pushWatermarkMs = pushWatermarkMs)
                    return Result.success(fallbackResult)
                }
                if (events.isEmpty()) {
                    Log.d(TAG, "syncDeltaFromRemote: no watched item delta events for profile $profileId at cursor $cursor")
                    break
                }

                val upserts = events
                    .filter { it.operation.equals(WATCHED_ITEM_EVENT_UPSERT, ignoreCase = true) }
                    .map { event ->
                        WatchedItem(
                            contentId = event.contentId,
                            contentType = event.contentType,
                            title = event.title,
                            season = event.season,
                            episode = event.episode,
                            watchedAt = event.watchedAt
                        )
                    }
                val deletes = events
                    .filter { it.operation.equals(WATCHED_ITEM_EVENT_DELETE, ignoreCase = true) }
                    .map { event ->
                        Triple(event.contentId, event.season, event.episode)
                    }

                watchedItemsPreferences.applyRemoteChanges(upserts, deletes, profileId)
                cursor = maxOf(cursor, events.maxOf { it.eventId })
                watchedItemsPreferences.setDeltaState(cursor, initialized = true, profileId = profileId)
                totalUpserts += upserts.size
                totalDeletes += deletes.size
                Log.d(TAG, "syncDeltaFromRemote: applied page $page for profile $profileId newCursor=$cursor pageUpserts=${upserts.size} pageDeletes=${deletes.size}")

                if (events.size < WATCHED_ITEMS_DELTA_PAGE_SIZE) break
                page++
            }

            val finalLocalCount = watchedItemsPreferences.getAllItems(profileId).size
            Log.d(TAG, "syncDeltaFromRemote: finished profile=$profileId appliedUpserts=$totalUpserts appliedDeletes=$totalDeletes cursor=$cursor finalLocalCount=$finalLocalCount")
            Result.success(
                WatchedItemsRemoteSyncResult(
                    upsertedItems = totalUpserts,
                    deletedItems = totalDeletes,
                    usedSnapshot = false,
                    preservedLocalItems = false
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items delta from remote", e)
            Result.failure(e)
        }
    }

    /**
     * The profile's persisted push watermark, read once per sync BEFORE the remote fetch:
     * a full push completing mid-pull then cannot raise the threshold above items the
     * already-fetched remote snapshot predates (lower is always the safe direction).
     */
    private suspend fun readPushWatermark(profileId: Int): Long =
        watchedItemsPreferences.getLastSuccessfulPushMs(profileId)

    private suspend fun pullSnapshotFromRemote(
        profileId: Int,
        resetDeltaState: Boolean,
        pushWatermarkMs: Long
    ): WatchedItemsRemoteSyncResult {
        val remoteWatchedItems = pullFromRemote(profileId).getOrElse { throw it }
        Log.d(TAG, "pullSnapshotFromRemote: snapshot returned ${remoteWatchedItems.size} watched items for profile $profileId")
        val hadUnsyncedItems = confirmIfNothingPreserved(
            profileId,
            watermarkGate,
            remoteIsEmpty = remoteWatchedItems.isEmpty()
        ) {
            watchedItemsPreferences.replaceWithRemoteItems(
                remoteWatchedItems,
                lastSuccessfulPushMs = pushWatermarkMs,
                profileId = profileId
            )
        }
        if (resetDeltaState) {
            watchedItemsPreferences.setDeltaState(0L, initialized = false, profileId = profileId)
        }
        val finalLocalCount = watchedItemsPreferences.getAllItems(profileId).size
        Log.d(TAG, "pullSnapshotFromRemote: applied ${remoteWatchedItems.size} snapshot items for profile $profileId finalLocalCount=$finalLocalCount preservedLocal=$hadUnsyncedItems resetDeltaState=$resetDeltaState")
        return WatchedItemsRemoteSyncResult(
            upsertedItems = remoteWatchedItems.size,
            deletedItems = 0,
            usedSnapshot = true,
            preservedLocalItems = hadUnsyncedItems
        )
    }

    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", buildJsonArray {
                    addJsonObject {
                        put("content_id", contentId)
                        if (season != null) put("season", season)
                        if (episode != null) put("episode", episode)
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watched_items", params)
            }

            Log.d(TAG, "Deleted watched item from remote: $contentId s=$season e=$episode for profile $profileId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watched item from remote", e)
            Result.failure(e)
        }
    }.also { pushHealth.record(SyncDomain.WATCHED_ITEMS, profileId, it) }

    suspend fun deleteFromRemoteBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (episodes.isEmpty()) return@withContext Result.success(Unit)

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", buildJsonArray {
                    episodes.forEach { (season, episode) ->
                        addJsonObject {
                            put("content_id", contentId)
                            put("season", season)
                            put("episode", episode)
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            authManager.withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watched_items", params)
            }

            Log.d(TAG, "Batch deleted ${episodes.size} watched items from remote for $contentId profile $profileId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch delete watched items from remote", e)
            Result.failure(e)
        }
    }.also { pushHealth.record(SyncDomain.WATCHED_ITEMS, profileId, it) }
}
