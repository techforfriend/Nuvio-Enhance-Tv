package com.nuvio.tv.core.torrent

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the TorrServer binary lifecycle.
 * The binary is bundled in jniLibs/ as libtorrserver.so and installed
 * to nativeLibraryDir by the Android package manager.
 */
@Singleton
class TorrServerBinary @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TorrServerBinary"
        const val PORT = 8091
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 200L
        private const val STOP_GRACE_MS = 3_000L
    }

    // Written by start()/stop() on different IO threads.
    @Volatile
    private var process: Process? = null

    /**
     * The one client for the local TorrServer, shared with TorrServerApi (one connection pool).
     * No custom Dns: [baseUrl] is an IP literal, which OkHttp connects to without a lookup.
     */
    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Short timeouts for health checks and /shutdown; shares httpClient's pool.
    private val healthClient = httpClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    private val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")

    private val configDir: File
        get() = File(context.filesDir, "torrserver").also { it.mkdirs() }

    val isBinaryAvailable: Boolean
        get() = binaryFile.exists()

    fun isRunning(): Boolean {
        return try {
            val request = Request.Builder().url("$baseUrl/echo").build()
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning()) {
            Log.d(TAG, "TorrServer already running")
            return@withContext
        }

        // Kill any orphaned TorrServer process that may be holding the port
        // (e.g., from a previous app session that was force-killed).
        killOrphanedProcess()

        if (!isBinaryAvailable) {
            throw TorrentException(context.getString(com.nuvio.tv.R.string.torrent_error_binary_missing, binaryFile.absolutePath))
        }

        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true)
        }

        val pb = ProcessBuilder(
            binaryFile.absolutePath,
            "--port", PORT.toString(),
            "--path", configDir.absolutePath
        )
        pb.directory(configDir)
        pb.redirectErrorStream(true)

        Log.d(TAG, "Starting TorrServer on port $PORT from ${binaryFile.absolutePath}")
        val proc = pb.start()
        process = proc

        // Read stdout in daemon thread for debugging
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "[server] $line")
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            start()
        }

        // Wait for health check — also detect early process death so we
        // don't spin for 15s if the binary crashed on launch.
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.d(TAG, "TorrServer started successfully")
                return@withContext
            }
            // The local proc: a concurrent stop() may null the field mid-loop.
            if (!isProcessAlive(proc)) {
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                if (process === proc) process = null
                throw TorrentException(context.getString(com.nuvio.tv.R.string.torrent_error_process_died, exitCode))
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        throw TorrentException(context.getString(com.nuvio.tv.R.string.torrent_error_start_timeout, (STARTUP_TIMEOUT_MS / 1000).toInt()))
    }

    private suspend fun killOrphanedProcess() {
        try {
            // Try graceful shutdown in case an old instance is still responding
            val request = Request.Builder().url("$baseUrl/shutdown").build()
            healthClient.newCall(request).execute().close()
            delay(1000)
            Log.d(TAG, "Shut down orphaned TorrServer instance")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No orphan responding — nothing to do
        }
    }

    /**
     * Graceful /shutdown, then waits up to [STOP_GRACE_MS] for our process to exit and
     * force-kills it if it has not (also when the wait is cancelled).
     */
    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        val proc = process
        process = null
        try {
            val request = Request.Builder().url("$baseUrl/shutdown").build()
            healthClient.newCall(request).execute().close()
        } catch (_: Exception) {}

        if (proc != null) {
            try {
                val deadline = System.currentTimeMillis() + STOP_GRACE_MS
                while (isProcessAlive(proc) && System.currentTimeMillis() < deadline) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }
            } finally {
                // Spec 107: destroyForcibly is API 26 (NoSuchMethodError on Fire OS 6 / API 25); destroy() there.
                if (isProcessAlive(proc)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) proc.destroyForcibly() else proc.destroy()
                }
            }
        }
        Log.d(TAG, "TorrServer stopped")
    }

    private fun isProcessAlive(proc: Process?): Boolean {
        if (proc == null) return false
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: Exception) {
            false
        }
    }
}
