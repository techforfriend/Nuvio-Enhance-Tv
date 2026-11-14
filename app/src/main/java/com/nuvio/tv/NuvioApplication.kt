package com.nuvio.tv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageLoader

import com.nuvio.tv.core.diagnostics.LastExitReason
import com.nuvio.tv.core.runtime.LegacyFeatureDataCleanup
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    // No direct reads; injected so its init (auth-state collector driving the startup pull)
    // starts at process start rather than at first Activity injection.
    @Inject lateinit var startupSyncService: StartupSyncService
    // No direct reads left; injected so the store's init (per-profile settings migrations)
    // keeps starting at process start, as it did while the Gate 0 probe read it here.
    @Inject lateinit var playerSettingsDataStore: PlayerSettingsDataStore
    @Inject lateinit var legacyFeatureDataCleanup: LegacyFeatureDataCleanup

    /**
     * The tuned singleton loader, built in ImageLoaderModule (W3.1) on the W2.1
     * @Image client. Returned from newImageLoader so AsyncImage's singleton path
     * and injection resolve the same instance; M2's playback diet trims through
     * this handle.
     */
    @Inject lateinit var appImageLoader: ImageLoader

    companion object {
        private const val CACHE_TAG = "NuvioCache"
        private const val IMAGE_CACHE_DIR = "image_cache"
        private const val HTTP_CACHE_VALIDATED_DIR = "http_cache_validated"

        /** Long enough that the walk cannot contend with cold start. */
        private const val CACHE_READOUT_DELAY_MS = 15_000L

        /** Keeps the legacy-data sweep (X15) off cold start and after the cache readout. */
        private const val LEGACY_CLEANUP_DELAY_MS = 20_000L
    }

    override fun onCreate() {
        super.onCreate()
        // CloudStream: restore the plugin registry early (SharedPreferences read
        // + Dex runtime context) so stream resolution can use installed
        // providers. Plugin loading itself stays lazy — first stream/search call.
        // Uses filesDir/cloudstream/packages and prefs nuvio_cloudstream, which
        // the X15 legacy sweep below does NOT touch (it deletes cs_extensions /
        // plugin_code / cs3_plugin_preferences).
        com.nuvio.tv.core.cloudstream.CloudStreamStorage.initialize(this)
        com.nuvio.tv.core.cloudstream.CloudStreamRepository.initialize()
        // C-1: hydrate the AFR fps cache from disk (background load) so cold
        // rewatches take the preflight hit path (switch before prepare).
        com.nuvio.tv.core.player.AfrFpsCache.initFrameRateCachePersistence(this)
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences(LocaleCache.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LocaleCache.PREFS_KEY_TAG, null)
        LocaleCache.localeTag = tag ?: ""

        // Build 1 instrument: the only way to answer whether OkHttp's 50 MB
        // addon cache (ADDON_HTTP_CACHE_DIR) holds anything, and the only way to see Coil's COMPUTED
        // disk cap (a percent-based cap is otherwise invisible). Deliberately
        // delayed and on IO: walking thousands of cache entries must not land
        // on the cold-start path this instrument exists to help optimise.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            delay(CACHE_READOUT_DELAY_MS)
            logCacheSizes()
        }

        // Spec 70 R1: log why the previous process ended (crash / ANR / foreground memory kill).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            LastExitReason.logLastExit(this@NuvioApplication)
        }

        // X15: sweep leftovers of cut features (plugin runtime files, stale debrid/MDBList
        // keys). Idempotent by existence, so it simply runs every process start; logs one line.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            delay(LEGACY_CLEANUP_DELAY_MS)
            legacyFeatureDataCleanup.run()
        }
    }

    private fun dirBytes(name: String): Long {
        val d = java.io.File(cacheDir, name)
        if (!d.isDirectory) return -1L
        return try {
            d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            -2L
        }
    }

    private fun logCacheSizes() {
        try {
            val mib = 1024L * 1024L
            val am = getSystemService(android.app.ActivityManager::class.java)
            val loader = imageLoader
            val disk = loader.diskCache
            val mem = loader.memoryCache
            // Locals, not nested literals inside a string template: an escaped
            // quote inside a template is a syntax error, and hoisting removes
            // the whole class of hazard.
            val imageMiB = dirBytes(IMAGE_CACHE_DIR) / mib
            val httpMiB = dirBytes(com.nuvio.tv.core.di.ADDON_HTTP_CACHE_DIR) / mib
            val httpValidatedMiB = dirBytes(HTTP_CACHE_VALIDATED_DIR) / mib
            val diskSizeMiB = (disk?.size ?: -1L) / mib
            val diskMaxMiB = (disk?.maxSize ?: -1L) / mib
            val memSizeMiB = (mem?.size ?: -1L) / mib
            val memMaxMiB = (mem?.maxSize ?: -1L) / mib
            val freeMiB = cacheDir.usableSpace / mib
            android.util.Log.i(
                CACHE_TAG,
                "CACHE_SIZES image=${imageMiB}MiB http=${httpMiB}MiB " +
                    "httpValidated=${httpValidatedMiB}MiB " +
                    "coilDisk=${diskSizeMiB}MiB coilDiskMax=${diskMaxMiB}MiB " +
                    "coilMem=${memSizeMiB}MiB coilMemMax=${memMaxMiB}MiB " +
                    "lowRam=${am?.isLowRamDevice} memClass=${am?.memoryClass}MiB " +
                    "largeMemClass=${am?.largeMemoryClass}MiB freeMiB=$freeMiB"
            )
        } catch (e: Exception) {
            android.util.Log.w(CACHE_TAG, "CACHE_SIZES failed: ${e.message}")
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        // Relocated to ImageLoaderModule (W3.1); same instance for the singleton path
        // and injection sites.
        return appImageLoader
    }
}
