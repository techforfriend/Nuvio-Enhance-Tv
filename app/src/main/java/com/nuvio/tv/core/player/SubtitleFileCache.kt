package com.nuvio.tv.core.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.di.Download
import com.nuvio.tv.core.network.executeCancellable
import com.nuvio.tv.core.network.tls.isAddonCertificateFailure
import com.nuvio.tv.core.util.safeHost
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okio.Buffer

/**
 * Downloads subtitle files from remote URLs to local cache and provides
 * content:// URIs via FileProvider so external players can read them.
 */
@Singleton
class SubtitleFileCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Download private val okHttpClient: OkHttpClient
) {
    private val cacheDir: File
        get() = File(context.cacheDir, SUBTITLE_CACHE_DIR).also { it.mkdirs() }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with
     * content:// URIs instead of HTTP URLs.
     *
     * Subtitles that fail to download are silently skipped (not included in result).
     * Returns null if no subtitles could be downloaded.
     */
    suspend fun cacheSubtitles(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        if (subtitles.isEmpty()) return null

        // Clean old cached subtitles before downloading new ones
        withContext(Dispatchers.IO) { clearCache() }

        val cached = subtitles.mapIndexedNotNull { index, input ->
            try {
                // Index-suffixed filename: addons routinely return several tracks with
                // identical lang+name (multiple OpenSubtitles releases); without it each
                // download overwrites the previous file and every track serves one content.
                val localUri = downloadToCache(input, index)
                if (localUri != null) {
                    input.copy(url = localUri.toString())
                } else {
                    Log.w(TAG, "Failed to download subtitle: ${input.name}")
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error caching subtitle: ${input.name}", e)
                null
            }
        }

        return cached.ifEmpty { null }
    }

    /**
     * Downloads a single subtitle file and returns a content:// URI.
     */
    private suspend fun downloadToCache(input: SubtitleInput, cacheIndex: Int): Uri? = withContext(Dispatchers.IO) {
        val extension = guessExtension(input.url)
        val filename = sanitizeFilename("${input.lang}_${cacheIndex}_${input.name}.$extension")
        val file = File(cacheDir, filename)

        val request = Request.Builder()
            .url(input.url)
            .build()

        // Logs carry the host only: addon subtitle URLs can hold config or credentials in path
        // and query (e.g. AIOStreams), so the full URL never reaches logcat (Register A2 P16).
        try {
            val written = okHttpClient.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} downloading subtitle from host=${input.url.safeHost()}")
                    return@executeCancellable false
                }

                val bodyBytes = cappedBytes(response.body)
                val normalizedText = SubtitleCharsetDetector.decode(bodyBytes, languageHint = input.lang)
                val sanitizedText = com.nuvio.tv.core.player.SubtitleMojibakeSanitizer.sanitize(normalizedText).toString()
                file.writeText(sanitizedText, Charsets.UTF_8)
                true
            }
            if (!written) return@withContext null

            // Return content:// URI via FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: CancellationException) {
            // The partial file is removed by the next cacheSubtitles' clearCache.
            throw e
        } catch (e: Exception) {
            if (e.isAddonCertificateFailure()) {
                // Spec 68 P9: the @Download client refused the host's certificate (trustable only for addon hosts until X8).
                Log.w(TAG, "Subtitle host certificate not trusted host=${input.url.safeHost()}")
            } else {
                Log.w(TAG, "Failed to download subtitle file from host=${input.url.safeHost()}", e)
            }
            file.delete()
            null
        }
    }

    /**
     * Reads the subtitle body capped at [MAX_SUBTITLE_BYTES]: the URL is
     * addon-supplied and untrusted (W2.2 class), and the old
     * `response.body.bytes()` heap-buffered whatever the host served.
     */
    private fun cappedBytes(body: okhttp3.ResponseBody): ByteArray {
        val buffer = Buffer()
        body.source().use { source ->
            while (!source.exhausted()) {
                source.read(buffer, 64 * 1024)
                if (buffer.size > MAX_SUBTITLE_BYTES) {
                    throw IOException("Subtitle exceeds ${MAX_SUBTITLE_BYTES / 1_000_000}MB cap")
                }
            }
        }
        return buffer.readByteArray()
    }

    /**
     * Removes all cached subtitle files.
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear subtitle cache", e)
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(100) // Limit filename length
    }

    companion object {
        private const val TAG = "SubtitleFileCache"
        private const val SUBTITLE_CACHE_DIR = "subtitles"

        /** Subtitles are text; anything past 10MB from an addon URL is abuse. */
        internal const val MAX_SUBTITLE_BYTES = 10_000_000L
    }
}
