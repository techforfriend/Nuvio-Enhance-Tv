package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.livetv.parse.EpgSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class GuideCache(val version: Int = 1, val snapshot: EpgSnapshot)

/**
 * Parsed guides by guide source id (Spec 34 L5b, D14): each source's snapshot in memory and in
 * `filesDir/livetv/epg-<id>.json`, so a cold start has now/next and the day schedule without
 * downloading or re-parsing the XMLTV file. A snapshot holds one window, today and tomorrow
 * (Spec 67 G1c, every guide channel: D2); the owner re-fetches after local midnight and before
 * the window runs out (`LiveSourceManager.isStale`).
 */
@Singleton
class GuideRepository internal constructor(private val dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val mutex = Mutex()
    private val _guides = MutableStateFlow<Map<String, EpgSnapshot>>(emptyMap())
    val guides: StateFlow<Map<String, EpgSnapshot>> = _guides.asStateFlow()

    /** Reads the cached snapshots of [sourceIds] not yet in memory; a missing or unreadable cache is skipped. */
    suspend fun load(sourceIds: Collection<String>) = withContext(io) {
        mutex.withLock {
            val missing = sourceIds.filter { it !in _guides.value }
            if (missing.isEmpty()) return@withLock
            val read = missing.mapNotNull { id -> fileOf(id)?.read()?.let { id to it.snapshot } }
            if (read.isNotEmpty()) _guides.value = _guides.value + read
        }
    }

    /** Persists then publishes [snapshot] as [sourceId]'s guide; IOException leaves both as they were. */
    suspend fun put(sourceId: String, snapshot: EpgSnapshot) = withContext(io) {
        mutex.withLock {
            val file = fileOf(sourceId) ?: throw IOException("Unusable guide id")
            file.write(GuideCache(snapshot = snapshot))
            _guides.value = _guides.value + (sourceId to snapshot)
        }
    }

    suspend fun remove(sourceId: String) = withContext(io) {
        mutex.withLock {
            File(dir, "epg-$sourceId.json").takeIf { SAFE_ID.matches(sourceId) }?.delete()
            _guides.value = _guides.value - sourceId
        }
    }

    // Ids are app-made (UUIDs, preset constants); the check keeps a tampered list inside dir.
    private fun fileOf(sourceId: String): JsonFile<GuideCache>? =
        if (SAFE_ID.matches(sourceId)) JsonFile(File(dir, "epg-$sourceId.json"), GuideCache.serializer()) else null

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
