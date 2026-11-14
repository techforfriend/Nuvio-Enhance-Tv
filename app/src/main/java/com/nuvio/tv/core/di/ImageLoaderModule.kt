package com.nuvio.tv.core.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Singleton

/**
 * The single tuned Coil loader (W3.1). Builder relocated verbatim from
 * NuvioApplication.newImageLoader — components, caches, and every tuning
 * comment are unchanged; only construction moved.
 *
 * Wiring contract: NuvioApplication implements SingletonImageLoader.Factory
 * and returns THIS instance, so injection sites and AsyncImage's singleton
 * path resolve the same loader. That injected handle is the seam memory-M2
 * trims through (derisk S1) — do not build ad-hoc loaders for new surfaces.
 */
/** Share of the image memory cache kept while the full-screen player is up (Spec 61 Q5). */
private const val PLAYBACK_IMAGE_CACHE_DIVISOR = 4

/**
 * Spec 61 Q5 (savantguarded NuvioCPlayerMemory.onPlayerOpened): gives most of the image memory
 * cache back when a stream starts, so the browse screens' posters do not sit in the heap next to
 * a 4K decode. Least-recently-used entries go first, so the art the player itself just loaded
 * stays; the cap is unchanged, and the disk cache refills the rest on the way back.
 */
internal fun trimImageMemoryCacheForPlayback(context: Context) {
    runCatching {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        cache.trimToSize(cache.maxSize / PLAYBACK_IMAGE_CACHE_DIVISOR)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @Image imageOkHttpClient: OkHttpClient,
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // Size-aware TMDB downscaler: the Bingecat catalogue serves original-size
                // TMDB image URLs (multi-MB, 600-1200ms to decode). Rewrite them to a bucket
                // matching the resolved display target. Only touches image.tmdb.org
                // /t/p/original/ at poster-sized targets (<=780px); screen-sized backdrops
                // (>780px) and unsized requests keep original. Sizes off the resolved target,
                // so it tracks the card size AND the UI-scale feature automatically.
                add(coil3.map.Mapper<String, String> { data, options ->
                    val w = (options.size.width as? coil3.size.Dimension.Pixels)?.px
                    if (data.startsWith("https://image.tmdb.org/t/p/original/") && w != null && w in 1..780) {
                        val bucket = when {
                            w <= 342 -> "w342"
                            w <= 500 -> "w500"
                            else -> "w780"
                        }
                        data.replace("/t/p/original/", "/t/p/$bucket/")
                    } else {
                        null
                    }
                })
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // SVG art: first-party res/raw SVGs decode through Coil (the player icons
                // loaded via rememberRawSvgPainter). W1.10 dropped the decoder on the premise
                // that no source serves SVG art — falsified on-device 2026-08-29 (blank
                // action icons and the IMDb logo, which is text since Spec 49 D17); restored
                // in ke7.
                add(coil3.svg.SvgDecoder.Factory())
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        // W1.10 (2026-08-28, Arshad): no cacheStrategy — Coil's DEFAULT serves
                        // a disk entry forever until size-eviction, zero revalidation traffic.
                        // Art frozen at first fetch is accepted (incl. btttr.cc rating posters).
                    )
                )
            }
            .memoryCache {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.15 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.20 for decent image caching.
                // Normal devices (>3GB): use 0.25 for snappy image loading.
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    // D13. nt21 raised this cap because 500 MB was cycling (a post-wipe
                    // audit refilled ~179 MB in under a day against a ~208k-title shared
                    // Emby library). Raising it was right; using an ABSOLUTE value was
                    // not: maxSizeBytes and maxSizePercent are mutually exclusive in
                    // Coil's DiskCache.Builder -- setting one zeroes the other -- so the
                    // 8 GiB constant silently disabled the library's own free-space
                    // proportional sizing and replaced it with a device-blind number.
                    //
                    // Percent plus clamp restores it. On a 22 GiB-free box this computes
                    // to the 2 GiB ceiling; 1 GiB at 10 GiB free; the floor binds below
                    // ~2.5 GiB free. Two properties worth knowing: Coil reads FREE space,
                    // not total, so the cap recomputes at every app start and drifts down
                    // as the disk fills; and the floor is unconditional, applied by
                    // coerceIn even when the space is not there. 256 MB was chosen over
                    // 512 for that reason -- it sits below nt21's measured 500 MB cycling
                    // threshold deliberately, trading re-downloads against consuming a
                    // quarter of a nearly-full disk.
                    //
                    // The 2 GiB ceiling is inferred, not measured: with one test device
                    // there is no steady-state working-set reference. Build 1's
                    // CACHE_SIZES readout is what converts it. Watch item unchanged:
                    // disk-cache journal replay at high entry counts is a possible
                    // cold-start contributor (task 2.13).
                    .maxSizePercent(0.10)
                    .minimumMaxSizeBytes(256L * 1024 * 1024)
                    .maximumMaxSizeBytes(2L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            // allowRgb565 removed: inert on the hardware decode path (API 26+), and
            // actively harmful on the software paths (blur inputs decode at 16-bit
            // before processing, baking in quantisation).
            .bitmapFactoryMaxParallelism(4)
            .build()
    }
}
