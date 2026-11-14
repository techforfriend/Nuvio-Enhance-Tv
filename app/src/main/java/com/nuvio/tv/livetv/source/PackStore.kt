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

@Serializable
internal data class PackFile(val version: Int = 1, val channels: List<PackChannel> = emptyList())

/**
 * The channel pack as last downloaded (`filesDir/livetv/pack-channels.json`, Spec 73 S2b): every
 * kept channel and stream with its monitor state, including those Live TV does not list yet (D1),
 * for the scan (A2) to work from. Not held in memory: read when needed. Written atomically
 * ([JsonFile]); callers serialise access (the manager's source lock).
 */
@Singleton
class PackStore internal constructor(dir: File, private val io: CoroutineDispatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "livetv"), Dispatchers.IO)

    private val target = File(dir, FILE_NAME)
    private val file = JsonFile(target, PackFile.serializer())

    /** The stored channels; empty when none or the file is unreadable. */
    suspend fun read(): List<PackChannel> = withContext(io) { file.read()?.channels.orEmpty() }

    /** Throws IOException when the write fails (the previous file stays). */
    suspend fun save(channels: List<PackChannel>) = withContext(io) { file.write(PackFile(channels = channels)) }

    /** Deletes the file; throws IOException when it stays. */
    suspend fun clear() = withContext(io) {
        if (target.exists() && !target.delete()) throw IOException("Could not delete the pack file")
    }

    private companion object {
        const val FILE_NAME = "pack-channels.json"
    }
}
