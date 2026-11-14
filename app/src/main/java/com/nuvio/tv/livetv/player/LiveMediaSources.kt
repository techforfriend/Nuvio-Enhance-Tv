package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nuvio.tv.core.player.PlayerMediaSourceFactory
import com.nuvio.tv.core.player.PlayerPlaybackNetworking
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.XtreamUrls
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Builds Media3 sources for Live TV streams (Spec 34 D7, L6a) on the shared playback stack
 * ([PlayerPlaybackNetworking] client, [PlayerMediaSourceFactory] header and mime helpers) with the
 * live overrides: connect 4 s / read 8 s, [LiveLoadErrorHandlingPolicy], and none of the VOD
 * extras (no parallel ranges, MP4 session, prewarm or per-open logging: ranged requests against
 * an endless stream are wrong, and a playlist refresh every few seconds would flood the log).
 */
@OptIn(UnstableApi::class)
internal object LiveMediaSources {

    const val CONNECT_TIMEOUT_MS = 4_000L
    const val READ_TIMEOUT_MS = 8_000L
    private const val SNIFF_BYTES = 512L

    /** A stream ready to play: URL without `user:pass@`, sanitized headers (Basic auth added). */
    data class LiveRequest(val url: String, val headers: Map<String, String>)

    fun normalize(stream: LiveStream): LiveRequest =
        PlayerMediaSourceFactory.normalizePlaybackRequest(stream.url, stream.headers)
            .let { LiveRequest(it.url, it.headers) }

    /**
     * The shared playback client with the live timeouts (call timeout stays 0: streams never end)
     * and without the VOD per-call `PlaybackConnectionEvents` log (a playlist refresh every few
     * seconds would flood it).
     */
    fun client(headers: Map<String, String>): OkHttpClient =
        PlayerPlaybackNetworking.createHttpClient(headers).newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .eventListener(EventListener.NONE)
            .build()

    /**
     * The stream's mime type from its URL, else (extension-less URLs, D7) from one GET: an HLS or
     * DASH Content-Type, else the first [SNIFF_BYTES] (`#EXTM3U`, MPD), else the redirect target's
     * extension. Null means progressive (TsExtractor and friends sniff the bytes themselves); a
     * failed sniff is null too, and the player's own load then reports the real error. An
     * extension-less Xtream live URL the URL itself does not type is MPEG-TS without the sniff
     * (Spec 78 D3): no extra connection for a panel to count against the account.
     */
    suspend fun resolveMimeType(request: LiveRequest, client: OkHttpClient): String? =
        PlayerMediaSourceFactory.inferMimeType(request.url, null)
            ?: if (XtreamUrls.isBareLive(request.url)) null else sniffMimeType(request, client)

    private suspend fun sniffMimeType(request: LiveRequest, client: OkHttpClient): String? {
        val httpRequest = try {
            Request.Builder().url(request.url).apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                PlayerMediaSourceFactory.defaultUserAgentUnlessSet(request.headers)?.let { header("User-Agent", it) }
            }.build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val call = client.newCall(httpRequest)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val mime = try {
                        response.use(::mimeFromResponse)
                    } catch (_: IOException) {
                        null
                    }
                    continuation.resume(mime)
                }
            })
        }
    }

    private fun mimeFromResponse(response: Response): String? {
        if (!response.isSuccessful) return null
        adaptive(PlayerMediaSourceFactory.normalizeMimeType(response.header("Content-Type")))?.let { return it }
        // peekBody waits for SNIFF_BYTES or the end of the body, so a short first read cannot hide them.
        val head = response.peekBody(SNIFF_BYTES).string()
        PlayerMediaSourceFactory.sniffManifestMimeType(head)?.let { return it }
        return adaptive(PlayerMediaSourceFactory.inferMimeType(response.request.url.toString(), null))
    }

    /** Only the adaptive answers matter here: anything else plays as progressive anyway. */
    private fun adaptive(mime: String?): String? =
        mime?.takeIf { it == MimeTypes.APPLICATION_M3U8 || it == MimeTypes.APPLICATION_MPD }

    /**
     * Extractors for live progressive streams (Spec 61 Q1). The flags only reach TsExtractor, so
     * other sniffed containers are unaffected. Non-IDR keyframes (H.264 only): every non-IDR
     * I-slice counts as a sync sample, instead of only one after a TS random-access indicator,
     * so feeds with rare IDRs and no RAI can start without waiting for an IDR (as the GPL forks
     * Spec 61 cites do); an open-GOP start may show a few frames of artefacts. Splice info: no
     * SCTE-35 use here, as on VOD. HDMV DTS stays off (VOD only): outside Blu-ray TS, stream
     * type 0x82 is not DTS. PMT mode stays DefaultExtractorsFactory's default, MODE_SINGLE_PMT.
     */
    private fun progressiveExtractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        )

    /** Media3's UUID for a `LiveDrm` key system; null for one Media3 has none for (WisePlay, unknown ids). */
    fun uuidOf(keySystem: String): UUID? = when (keySystem) {
        LiveDrm.WIDEVINE -> C.WIDEVINE_UUID
        LiveDrm.CLEARKEY -> C.CLEARKEY_UUID
        LiveDrm.PLAYREADY -> C.PLAYREADY_UUID
        else -> null
    }

    /** Key systems this TV said yes to: a yes does not change while the app runs (a no is asked again). */
    private val supportedSchemes = ConcurrentHashMap<UUID, Boolean>()

    /**
     * Whether this TV can open a [drm] session (Spec 74 D5 "this TV has no X DRM" otherwise), as
     * Media3 opens it (its own UUID adjustment for old ClearKey). A yes is asked once per key system.
     */
    fun supported(drm: LiveDrm): Boolean {
        val uuid = uuidOf(drm.keySystem) ?: return false
        // Only a yes is kept: a no (or a failed check) is asked again on the next load.
        if (supportedSchemes[uuid] == true) return true
        val supported = runCatching { FrameworkMediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)
        if (supported) supportedSchemes[uuid] = true
        return supported
    }

    /** Where a stream's licence comes from (Spec 74 S2b); toString redacted (keys, tokens: KT-SEC-02). */
    internal sealed interface LicenceSource {
        /** The playlist's ClearKey keys, answered locally as a W3C licence. */
        class Local(val license: String) : LicenceSource {
            override fun toString(): String = "Local"
        }

        /**
         * A licence server: [url] null = the manifest's, else forced over it (Kodi: the playlist's
         * overrides). [streamHeaders] travel as defaults (minus any name [licenseHeaders] sets,
         * compared case-insensitively), [licenseHeaders] as key request properties on top.
         * [userAgent] is the app's default, null when either set carries one (never two UAs).
         */
        class Server(val url: String?, val streamHeaders: Map<String, String>, val licenseHeaders: Map<String, String>) : LicenceSource {
            val userAgent: String? get() = PlayerMediaSourceFactory.defaultUserAgentUnlessSet(streamHeaders + licenseHeaders)

            override fun toString(): String =
                "Server(url=${if (url == null) "manifest" else "set"}, headers=${streamHeaders.size}+${licenseHeaders.size})"
        }
    }

    /**
     * The [LicenceSource] for [drm] (pure; table-tested). The stream's Authorization header (its URL's
     * `user:pass@`) never goes to a licence server: it is for the stream's host only.
     */
    internal fun licenceSource(drm: LiveDrm, streamHeaders: Map<String, String>): LicenceSource {
        if (drm.clearKeys.isNotEmpty()) return LicenceSource.Local(drm.clearKeyLicense())
        val overridden = drm.licenseHeaders.keys.map { it.lowercase() }.toSet() + "authorization"
        return LicenceSource.Server(drm.licenseUrl, streamHeaders.filterKeys { it.lowercase() !in overridden }, drm.licenseHeaders)
    }

    /**
     * The session manager for a licensed stream (Spec 74 S2b) from its [licenceSource]. The licence
     * request uses its own client (the live timeouts) without the stream's Authorization interceptor,
     * and the live fail-fast policy: a licence 4xx hands over to the ladder at once instead of three
     * retries. [drm] must be [supported].
     */
    fun drmSessionManager(drm: LiveDrm, request: LiveRequest): DrmSessionManager {
        val uuid = requireNotNull(uuidOf(drm.keySystem))
        val callback: MediaDrmCallback = when (val source = licenceSource(drm, request.headers)) {
            is LicenceSource.Local -> LocalMediaDrmCallback(source.license.toByteArray(Charsets.UTF_8))
            is LicenceSource.Server -> HttpMediaDrmCallback(
                source.url,
                source.url != null,
                OkHttpDataSource.Factory(client(emptyMap())).apply {
                    setDefaultRequestProperties(source.streamHeaders)
                    setUserAgent(source.userAgent)
                }
            ).apply { source.licenseHeaders.forEach { (name, value) -> setKeyRequestProperty(name, value) } }
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            // progressive = true: the dead-link set is fatal for every load, the licence request included.
            .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = true))
            .build(callback)
    }

    private fun dataSourceFactory(headers: Map<String, String>, client: OkHttpClient) = OkHttpDataSource.Factory(client).apply {
        setDefaultRequestProperties(headers)
        setUserAgent(PlayerMediaSourceFactory.defaultUserAgentUnlessSet(headers))
    }

    /**
     * HLS and DASH by [mimeType]; anything else progressive. [mediaId] is the channel key. [drm]
     * (a licensed stream, Spec 74 S2b) gives every factory its session manager.
     */
    fun create(
        request: LiveRequest,
        mimeType: String?,
        client: OkHttpClient,
        mediaId: String,
        drm: DrmSessionManager? = null
    ): MediaSource {
        val dataSourceFactory = dataSourceFactory(request.headers, client)
        val mediaItem = MediaItem.Builder()
            .setUri(request.url)
            .setMediaId(mediaId)
            .apply { mimeType?.let(::setMimeType) }
            .build()
        return when (mimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                .apply { drm?.let { manager -> setDrmSessionManagerProvider { manager } } }
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = false))
                .createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
                .apply { drm?.let { manager -> setDrmSessionManagerProvider { manager } } }
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = false))
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory, progressiveExtractors())
                .apply { drm?.let { manager -> setDrmSessionManagerProvider { manager } } }
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = true))
                .createMediaSource(mediaItem)
        }
    }
}
