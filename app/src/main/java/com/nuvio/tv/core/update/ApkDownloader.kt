package com.nuvio.tv.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.nuvio.tv.core.di.Validated
import com.nuvio.tv.core.network.executeCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.HashingSink
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Under cacheDir, so the FileProvider's `cache-path "."` serves it to the installer. */
internal const val UPDATES_DIR = "updates"

/** Log tag of the download action (this class and UpdateController's download path). */
internal const val UPDATE_DOWNLOAD_TAG = "UpdateDownload"

/** What [ApkDownloader.download] ended with. */
sealed interface DownloadResult {
    /** The release's APK, checked; ready for the installer. */
    data class Verified(val apk: File) : DownloadResult

    /**
     * The whole file arrived but it is not what the release promised (digest) or not this app's
     * next build (archive): downloading it again cannot help.
     */
    data object Rejected : DownloadResult

    /** No complete file (HTTP status, network, size, storage); trying again may work. */
    data object Failed : DownloadResult
}

/** What an APK file declares about itself; null from the reader when it does not parse. */
data class ApkIdentity(val packageName: String, val versionCode: Long)

/**
 * The validated client (TMDB's TLS rules) for an APK download (Spec 52 P8), never the @Download client
 * (it also accepts certificates the user trusted for a self-hosted addon, Spec 68 X7). newBuilder() would copy the 30 s callTimeout (spans the body) and the HTTP
 * cache; redirects stay on https (GitHub answers with one to its asset host).
 */
internal fun apkDownloadClient(validated: OkHttpClient): OkHttpClient = validated.newBuilder()
    .cache(null)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()

/**
 * Downloads a release's APK (Spec 52 P8): streams to a fixed name with SHA-256 on the fly, stops
 * past the size GitHub reported, then size, digest and the archive's own package name and
 * versionCode must match before the file is handed out. Any other end deletes what was written; a cancel
 * landing during the rename or the archive check (neither stops for it) can leave the verified file, which
 * the caller deletes (UpdateController's cancel path).
 * The URL is the fresh offer's, https by [releaseOffer]'s rule.
 */
@Singleton
class ApkDownloader internal constructor(
    private val client: OkHttpClient,
    private val dir: File,
    private val packageName: String,
    private val readArchive: (File) -> ApkIdentity?
) {
    @Inject constructor(@Validated validated: OkHttpClient, @ApplicationContext context: Context) : this(
        apkDownloadClient(validated),
        File(context.cacheDir, UPDATES_DIR),
        context.packageName,
        { apk -> readApkIdentity(context.packageManager, apk) }
    )

    /** Deletes every file in the updates folder (app start, and before each download). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** [onProgress] gets the bytes written so far, on the download's thread. */
    suspend fun download(release: KnownRelease, onProgress: (bytes: Long) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            clear()
            dir.mkdirs()
            val name = "update-${release.versionCode}-${release.abi}"
            val part = File(dir, "$name.apk.part")
            val apk = File(dir, "$name.apk")
            var verified = false
            try {
                val sha256 = fetch(release, part, onProgress) ?: return@withContext DownloadResult.Failed
                if (sha256 != release.sha256) return@withContext rejected("digest")
                // Renamed before the archive check: the platform parser is never handed a `.part` name.
                if (!part.renameTo(apk)) return@withContext failed("rename")
                val identity = readArchive(apk)
                if (identity == null || identity.packageName != packageName ||
                    identity.versionCode != release.versionCode.toLong()
                ) {
                    return@withContext rejected("archive")
                }
                verified = true
                DownloadResult.Verified(apk)
            } catch (e: IOException) {
                failed(e.javaClass.simpleName)
            } finally {
                if (!verified) {
                    part.delete()
                    apk.delete()
                }
            }
        }

    /** Streams the asset into [part]: its SHA-256 (lowercase hex) when exactly the reported size arrived. */
    private suspend fun fetch(release: KnownRelease, part: File, onProgress: (Long) -> Unit): String? =
        client.newCall(Request.Builder().url(release.downloadUrl).build()).executeCancellable { response ->
            if (!response.isSuccessful) return@executeCancellable notFetched("HTTP ${response.code}")
            if (response.body.contentLength() > release.sizeBytes) return@executeCancellable notFetched("declared size")
            HashingSink.sha256(part.sink()).use { sink ->
                val source = response.body.source()
                val buffer = Buffer()
                var bytes = 0L
                while (true) {
                    val read = source.read(buffer, READ_BYTES)
                    if (read == -1L) break
                    bytes += read
                    if (bytes > release.sizeBytes) return@executeCancellable notFetched("past size")
                    sink.write(buffer, read)
                    onProgress(bytes)
                }
                if (bytes != release.sizeBytes) return@executeCancellable notFetched("short")
                sink.hash.hex()
            }
        }

    private fun notFetched(reason: String): String? {
        Log.w(UPDATE_DOWNLOAD_TAG, "download failed: $reason")
        return null
    }

    private fun failed(reason: String): DownloadResult {
        Log.w(UPDATE_DOWNLOAD_TAG, "download failed: $reason")
        return DownloadResult.Failed
    }

    private fun rejected(reason: String): DownloadResult {
        Log.w(UPDATE_DOWNLOAD_TAG, "download rejected: $reason")
        return DownloadResult.Rejected
    }

    private companion object {
        const val READ_BYTES = 64L * 1024
    }
}

@Suppress("DEPRECATION") // the PackageInfoFlags overload is API 33+; flags 0 reads the same either way
private fun readApkIdentity(packageManager: PackageManager, apk: File): ApkIdentity? =
    packageManager.getPackageArchiveInfo(apk.path, 0)?.let { info ->
        ApkIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info))
    }
