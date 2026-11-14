package com.nuvio.tv.livetv.source

import android.content.Context
import com.nuvio.tv.livetv.data.JsonFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One stream's scan from this TV (Spec 73 S3a): [ok] = it answered as a stream; [atMs] wall clock. */
@Serializable
data class PackScanResult(val ok: Boolean, val atMs: Long)

/**
 * The last scan (Spec 73 D4/D5, 60/D11: persists across restarts). [countries] are the app's
 * country codes, null standing for "No country listed". [endedMs] is null while the scan runs (or
 * when the process died during it). Counts are channels of [countries]: [checked] of [channels]
 * were scanned, [playing] of them have a stream that answered, [hidden] none; [added] were not in
 * Live TV before this scan and are now, [addedNames] the first of them by name (the result panel's
 * "Among them").
 */
@Serializable
data class PackScanRecord(
    val countries: List<String?>,
    val startedMs: Long,
    val endedMs: Long? = null,
    val cancelled: Boolean = false,
    val channels: Int,
    val checked: Int = 0,
    val playing: Int = 0,
    val hidden: Int = 0,
    val added: Int = 0,
    val addedNames: List<String> = emptyList()
)

/** What the scan store holds: results by stream key ([ChannelPack.scanKey]) and the last scan. */
@Serializable
data class PackScans(
    val version: Int = 1,
    val results: Map<String, PackScanResult> = emptyMap(),
    val last: PackScanRecord? = null
)

/**
 * The channel pack's scan results (`filesDir/livetv/pack-scan.json`, Spec 73 S3a): apart from
 * [com.nuvio.tv.livetv.player.StreamHealthStore], whose 2,000-record cap a scan would overrun.
 * Not held in memory; written atomically ([JsonFile]), so a read outside the lock sees a whole
 * file; writers serialise on the manager's source lock.
 */
@Singleton
class PackScanStore internal constructor(dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val target = File(dir, FILE_NAME)
    private val file = JsonFile(target, PackScans.serializer())

    /** The stored scans; empty when none or the file is unreadable. */
    suspend fun read(): PackScans = withContext(io) { file.read() ?: PackScans() }

    /** Throws IOException when the write fails (the previous file stays). */
    suspend fun save(scans: PackScans) = withContext(io) { file.write(scans) }

    /** Deletes the file; throws IOException when it stays. */
    suspend fun clear() = withContext(io) {
        if (target.exists() && !target.delete()) throw IOException("Could not delete the scan file")
    }

    private companion object {
        const val FILE_NAME = "pack-scan.json"
    }
}
