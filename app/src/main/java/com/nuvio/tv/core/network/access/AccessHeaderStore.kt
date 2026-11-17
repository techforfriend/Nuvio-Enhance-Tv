package com.nuvio.tv.core.network.access

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val TAG = "AccessHeaderStore"

/** Whether a file is there: [UNKNOWN] when the check itself failed (D24 needs a definite absence). */
internal enum class FileState { ABSENT, PRESENT, UNKNOWN }

/**
 * `Os.stat` (API 21; `java.nio.file` needs API 26, minSdk is 24): only ENOENT is a definite
 * absence. `File.exists()` also reads false when the check fails, which D24 can't trust.
 */
internal fun statFile(file: File): FileState = try {
    Os.stat(file.path)
    FileState.PRESENT
} catch (e: ErrnoException) {
    if (e.errno == OsConstants.ENOENT) FileState.ABSENT else FileState.UNKNOWN
}

/** What a save did (Spec 97 A2, A5). */
enum class AccessSaveResult {
    SAVED,

    /** The saved list can't be read just now (D15): nothing was saved, so no saved site is lost. */
    UNREADABLE,

    /** A new site past [AccessSiteRules.MAX_SITES]. */
    TOO_MANY_SITES,

    /** The site fails [AccessSiteRules] (the phone server checks first; this is the store's own guard). */
    INVALID,

    /** Encrypting or writing failed; the saved list is unchanged. */
    FAILED
}

/**
 * Spec 97 X4 A2: the device's saved sites and their secret headers, encrypted at rest under an
 * Android Keystore key, in `files/network_privacy/` (outside `files/datastore/`: the sign-out sweep
 * never touches it, and it is kept on sign-out like any device setting). Never synced.
 *
 * Reads run on OkHttp threads for every request of every client with [AccessHeaderInterceptor], so
 * [snapshot] never throws and never stalls a request for long: only the first load blocks, at most
 * [FIRST_LOAD_WAIT_MS] (once that wait has run out, later callers stop waiting until it ends); a
 * retry runs on the first caller due one while every other caller goes on at once with no headers.
 *
 * No automatic recovery (D23): a read that fails, for whatever reason, means no headers and saves
 * refused (D15), retried at most every [RETRY_MS]; the file is never deleted or overwritten while
 * it can't be read. AOSP's Keystore raises its "permanent" key errors for transient faults too, so
 * no error is trusted to mean the file is lost (S2 red team cf8d1d3e, 11cc16ad). [removeAll] (the
 * D17 panel's "Remove saved sites") is the one way out of a list that never reads again.
 */
@Singleton
class AccessHeaderStore internal constructor(
    private val file: File,
    private val cipher: AccessHeaderCipher,
    private val nowMs: () -> Long,
    private val fileState: (File) -> FileState = ::statFile
) {
    /** The app's store; it is also every client's header source (A4), set before any request can need it. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, "network_privacy/access_headers.bin"),
        KeystoreAccessHeaderCipher(),
        SystemClock::elapsedRealtime
    ) {
        AccessHeaders.source = ::snapshot
    }

    private sealed interface State {
        data class Ready(val sites: List<AccessSite>) : State

        data class Unreadable(val lastTryMs: Long) : State
    }

    private val lock = ReentrantLock()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AccessSite.serializer())
    private val tempFile = File(file.parentFile, file.name + ".tmp")

    /** Null until the first load ends. */
    @Volatile
    private var state: State? = null

    /** Whether a saved file is on disk, as of the last successful load or write (D24). Under [lock]. */
    private var fileOnDisk = false

    /** True once a caller's first-load wait ran out: later callers don't wait while it is still stuck. */
    @Volatile
    private var firstLoadSlow = false

    /** The saved sites, or null while they can't be read (the panel's D17 state). Never throws. */
    fun sitesOrNull(): List<AccessSite>? {
        try {
            when (val current = state) {
                is State.Ready -> return current.sites
                null -> {
                    val locked = if (firstLoadSlow) lock.tryLock() else lock.tryLock(FIRST_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
                    if (locked) {
                        try {
                            if (state == null) load()
                        } finally {
                            lock.unlock()
                        }
                    } else {
                        firstLoadSlow = true
                    }
                }
                is State.Unreadable -> if (nowMs() - current.lastTryMs >= RETRY_MS && lock.tryLock()) {
                    try {
                        if (state === current) load()
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "Read skipped: ${t.javaClass.simpleName}")
        }
        return (state as? State.Ready)?.sites
    }

    /** The saved sites; none while they can't be read. Never throws. */
    fun snapshot(): List<AccessSite> = sitesOrNull().orEmpty()

    /** Saves [site], replacing the one with its host:port. Blocking: call off the main thread. */
    fun put(site: AccessSite): AccessSaveResult {
        if (!AccessSiteRules.isValid(site)) return AccessSaveResult.INVALID
        return write { sites ->
            val index = sites.indexOfFirst { it.matches(site.host, site.port) }
            when {
                index >= 0 -> sites.toMutableList().also { it[index] = site }
                sites.size >= AccessSiteRules.MAX_SITES -> null
                else -> sites + site
            }
        }
    }

    /** Removes the site at [host]:[port]. Blocking: call off the main thread. */
    fun remove(host: String, port: Int): AccessSaveResult = write { sites -> sites.filterNot { it.matches(host, port) } }

    /**
     * "Remove saved sites" (D23): deletes the file and the key, readable or not, and starts empty;
     * the next save makes a new key. Blocking: call off the main thread.
     */
    fun removeAll(): AccessSaveResult = lock.withLock {
        try {
            if (fileState(file) != FileState.ABSENT && !file.delete()) throw IOException("delete failed")
            tempFile.delete()
            fileOnDisk = false
            state = State.Ready(emptyList())
            // Best effort: with the file gone a leftover key holds nothing; the next save uses it or makes one (D24).
            try {
                cipher.deleteKey()
            } catch (t: Throwable) {
                Log.w(TAG, "Key not deleted: ${t.javaClass.simpleName}")
            }
            AccessSaveResult.SAVED
        } catch (t: Throwable) {
            Log.w(TAG, "Remove all failed: ${t.javaClass.simpleName}")
            AccessSaveResult.FAILED
        }
    }

    /** [transform] returns the new list, or null when a new site would pass the cap. */
    private fun write(transform: (List<AccessSite>) -> List<AccessSite>?): AccessSaveResult {
        lock.withLock {
            try {
                if (state !is State.Ready) load()
                val sites = (state as? State.Ready)?.sites ?: return AccessSaveResult.UNREADABLE
                val next = transform(sites) ?: return AccessSaveResult.TOO_MANY_SITES
                if (next != sites) persist(next)
                state = State.Ready(next)
                return AccessSaveResult.SAVED
            } catch (t: Throwable) {
                Log.w(TAG, "Save failed: ${t.javaClass.simpleName}")
                return AccessSaveResult.FAILED
            }
        }
    }

    /** Under [lock]. Sets [state]; never throws, never touches the file. */
    private fun load() {
        val previous = state
        state = try {
            State.Ready(readSites())
        } catch (t: Throwable) {
            if (previous !is State.Unreadable) Log.w(TAG, "Saved sites unreadable (${t.javaClass.simpleName})")
            State.Unreadable(nowMs())
        }
    }

    /** Under [lock]; sets [fileOnDisk]. A file whose presence can't be checked is unreadable. */
    private fun readSites(): List<AccessSite> {
        when (fileState(file)) {
            FileState.ABSENT -> {
                fileOnDisk = false
                return emptyList()
            }
            FileState.UNKNOWN -> throw IOException("file state unknown")
            FileState.PRESENT -> fileOnDisk = true
        }
        if (file.length() > MAX_FILE_BYTES) throw IOException("file too large")
        val plain = cipher.decrypt(file.readBytes())
        return json.decodeFromString(serializer, plain.toString(Charsets.UTF_8))
    }

    /**
     * Writes [sites] (an empty list deletes the file): a temp file, synced, then renamed over (the
     * same guarantee as AtomicFile, testable on the JVM). Only runs after a successful read; makes a
     * key only when no saved file is on disk, definitely (D24): a file whose presence can't be
     * checked fails the save like a missing key with a file present.
     */
    private fun persist(sites: List<AccessSite>) {
        if (sites.isEmpty()) {
            if (fileState(file) != FileState.ABSENT && !file.delete()) throw IOException("delete failed")
            fileOnDisk = false
            return
        }
        val plain = json.encodeToString(serializer, sites).toByteArray(Charsets.UTF_8)
        val blob = cipher.encrypt(plain, mayCreateKey = !fileOnDisk && fileState(file) == FileState.ABSENT)
        val dir = file.parentFile ?: throw IOException("no parent")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs failed")
        FileOutputStream(tempFile).use { out ->
            out.write(blob)
            out.fd.sync()
        }
        if (!tempFile.renameTo(file)) {
            tempFile.delete()
            throw IOException("rename failed")
        }
        fileOnDisk = true
    }

    internal companion object {
        const val FIRST_LOAD_WAIT_MS = 2_000L
        const val RETRY_MS = 30_000L

        /** 16 sites x 4 headers x 4 KB is ~270 KB of JSON. */
        const val MAX_FILE_BYTES = 1_000_000L
    }
}
