package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.access.AccessHeaderInterceptor
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {

    // Warm de-duplication. Since Patch B the warm fires twice per play: once
    // from PrefetchSelectionSupplier when the prefetch resolves, and again
    // from StreamScreen at the press. Each fires a PAIR, so a prefetched play
    // sent four bytes=0-262143 requests where two were useful (nt4 capture,
    // ids 1-2 then 3-4, all to the same URL). Focus-dwell prefetches (details
    // episode, CW card) rank without warming (upstream-sync T30), so for those
    // the press warm is the only one.
    //
    // The press-time call is not redundant in general -- it is the only warm
    // on a path that never prefetched, and after the pool's three-minute idle
    // timeout the connections the prefetch warmed are gone. So this suppresses
    // only a repeat of the SAME url inside a window comfortably shorter than
    // that idle timeout. Benign race: a duplicate warm under contention costs
    // one pooled round trip.
    @Volatile
    private var lastWarmUrl: String? = null

    @Volatile
    private var lastWarmAtMs: Long = 0L

    private const val WARM_DEDUP_WINDOW_MS = 60_000L

    /**
     * In-memory cookie jar for the playback clients (every one built from [cookieClientBuilder]), so cookies a
     * stream host sets are sent back on later requests to that host, on whichever path plays it (Spec 91 K2).
     * Process-wide: a host's cookies carry over to the next player instance. Keyed by exact host.
     */
    internal val playerCookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = store[url.host] ?: return emptyList()
            synchronized(hostCookies) {
                // matches(): Secure cookies only over https, Path honoured (the store is keyed by host).
                return hostCookies.filter { cookie ->
                    cookie.expiresAt > System.currentTimeMillis() && cookie.matches(url)
                }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostCookies = store.getOrPut(url.host) { mutableListOf() }
            synchronized(hostCookies) {
                cookies.forEach { newCookie ->
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }
    }
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * The one builder for clients that fetch stream bytes (data source, chunk session, prewarm): [playbackHttpClient]
     * with [playerCookieJar], so no path misses a host's redirect cookie (Spec 92 W1: the prewarm did, and 403'd).
     */
    internal fun cookieClientBuilder(): OkHttpClient.Builder = playbackHttpClient.newBuilder().cookieJar(playerCookieJar)

    /**
     * Fallback OkHttpClient equipped with trust-all SSL configuration for self-signed
     * or untrusted local media servers (e.g. self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers. Spec 97 A3: carries a saved site's secret
     * headers (network interceptor, inherited by every newBuilder() derivative); the trust-all
     * retry re-runs the application-level request, which never carries them.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            // N6 V2: attached HERE because OkHttpClient.Builder(client) copies
            // eventListenerFactory, so every newBuilder() derivative inherits
            // it -- the prewarm client, createHttpDataSourceFactory's client
            // and PlayerMediaSourceFactory's chunk-session client -- and
            // applyNetworkOptimizations sets no listener, so nothing
            // overwrites it. One attachment covers all three startup opens.
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(AccessHeaderInterceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    // Fallback to trust-all client if standard system SSL fails (e.g. self-signed local server)
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * S1g: warm the playback connection at press.
     *
     * Measured 23 Jul 2026 (OPEN_SPLIT, nt14): the probe inside
     * ParallelRangeDataSource.open() spends ~1,027 ms on TCP+TLS+headers to the
     * CDN, and it fires ~0.7 s after the stream URL is already known -- the
     * network sits idle in between while the player is built. This issues a
     * one-byte ranged GET as soon as the URL resolves. Its body completes, so
     * OkHttp returns the connection to the shared pool, and the probe reuses it
     * instead of handshaking again.
     *
     * Deliberately fire-and-forget: nothing awaits it, every failure is
     * swallowed, and a pool miss simply leaves the probe to handshake exactly as
     * before. Correction (26 Jul capture): this ADDS one tiny request per press
     * -- the old "close to request-neutral" claim was false. The payoff is that
     * a fully drained bytes=0-0 body returns its connection to the shared pool.
     *
     * S1m: TWO warms are fired. Startup runs two concurrent cold opens after
     * the probe (the tail continuation and chunk 0's own download); h1 cannot
     * multiplex, and a single pooled socket can be claimed by only one of them
     * -- the loser paid the full cold connect (median 849 ms of non-transport
     * residual, 26 Jul capture). Two pooled sockets cover the probe plus the
     * first claimant, and the drained probe connection re-enters the pool for
     * the other.
     */
    fun prewarmPlaybackConnection(url: String?, headers: Map<String, String>?) {
        val target = url?.trim().orEmpty()
        if (!target.startsWith("http://", ignoreCase = true) &&
            !target.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val warmSuppressed = target == lastWarmUrl &&
            (nowMs - lastWarmAtMs) in 0 until WARM_DEDUP_WINDOW_MS
        if (warmSuppressed) {
            android.util.Log.i("NuvioNet", "PREWARM skipped: same url warmed ${nowMs - lastWarmAtMs}ms ago")
            return
        }
        lastWarmUrl = target
        lastWarmAtMs = nowMs
        // B-2: fire the tail CONCURRENTLY with the head using an open-ended
        // suffix range, so it no longer waits for the head body to drain
        // (which the 15 Aug capture priced at 88-500 ms too late for the
        // reopen consult). Self-sufficient: its own Content-Range yields both
        // window start and total. A non-206 / opaque response is a no-op and
        // the head-triggered fallback tail below still runs.
        enqueueConcurrentSuffixTail(target, headers)
        val request = try {
            val builder = okhttp3.Request.Builder().url(target)
            headers?.forEach { (name, value) -> builder.header(name, value) }
            // Set last so a caller-supplied Range can never widen the warm-up.
            //
            // Patch B (26 Jul capture): was bytes=0-0. A one-byte body opens a
            // socket whose congestion window is still at its initial value, and
            // the capture priced that precisely -- the bounded probe's 256 KB
            // bootstrap read took 718 ms over a connection warmed with one byte
            // and 40 ms over a connection that had already carried an 8 MB
            // chunk. Connection MATURITY matters as much as connection
            // existence. Warming with exactly BOOTSTRAP_READ_BYTES grows the
            // window and requests precisely the bytes the probe's bootstrap
            // read wants next, so the CDN edge has them hot.
            // Identity, as Media3's OkHttpDataSource asks for the chunks: the stored bytes must be
            // the file's own (P12).
            builder.header("Range", "bytes=0-262143").header("Accept-Encoding", "identity").build()
        } catch (_: Exception) {
            return
        }
        try {
            // S1m enqueued two identical head warms so OkHttp opened two
            // sockets. nt8: the first warm's body is no longer discarded --
            // it is exactly the probe's bootstrap window, and its 206
            // Content-Range reveals the total length, so both are captured
            // into PrefetchWindowStore for the press to consume (probe
            // skipped entirely). The second socket is then warmed with the
            // LAST 1 MiB -- the Matroska Cues the extractor reads next --
            // instead of a duplicate head, killing the tail continuation
            // leg too. A failure or a non-206 falls back to a duplicate
            // head warm: the pre-nt8 two-socket behaviour, store untouched.
            prewarmHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // No-op by design: the probe will handshake as it does today.
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val window = PrewarmWindowReader.read(response, maxBytes = 262_144L, start = 0L) {
                        logPrewarmRejection("head", it)
                    }
                    if (window != null) {
                        PrefetchWindowStore.putHead(
                            ParallelRangeDataSource.BootstrapCacheEntry(
                                requestUri = android.net.Uri.parse(target),
                                startPosition = 0L,
                                resolvedUri = android.net.Uri.parse(response.request.url.toString()),
                                openLength = window.total,
                                totalFileLength = window.total,
                                bootstrapData = window.bytes,
                                bootstrapSize = window.bytes.size,
                                createdAtUptimeMs = android.os.SystemClock.uptimeMillis()
                            )
                        )
                        enqueueTailPrewarm(target, headers, window.total)
                    } else {
                        // Range-hostile or failed head: keep the second socket.
                        try {
                            prewarmHttpClient.newCall(request).enqueue(discardingWarmCallback)
                        } catch (_: Exception) {
                        }
                    }
                }
            })
        } catch (_: Exception) {
            // Dispatcher rejection or any other failure: nothing to do.
        }
    }

    /** P12: a fixed label per rejected prewarm window (no URL or header text). */
    private fun logPrewarmRejection(window: String, reason: PrewarmWindowReader.Rejection) {
        android.util.Log.i("NuvioNet", "PREWARM $window window rejected: $reason")
    }

    /**
     * nt8: the pre-nt8 warm behaviour -- drain and discard, socket to pool. P12: drains at most
     * the requested 256 KiB of a 206; anything else (a Range-hostile 200 is the usual reason this
     * fires) is closed unread instead of being buffered whole.
     */
    private val discardingWarmCallback = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            // No-op by design.
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.use { resp ->
                if (resp.code == 206) runCatching { resp.body.source().skip(262_144L) }
            }
        }
    }

    /**
     * B-2: request the last TAIL_WINDOW_BYTES via an open-ended suffix range
     * (Range: bytes=-N), concurrently with the head warm and without needing
     * the file total in advance. The 206's Content-Range gives the window
     * start and the total, so the stored entry is complete. A non-206 or an
     * opaque Content-Range leaves the store untouched, and the head-triggered
     * enqueueTailPrewarm then serves as the fallback.
     */
    private fun enqueueConcurrentSuffixTail(target: String, headers: Map<String, String>?) {
        val tailWindow = PrefetchWindowStore.TAIL_WINDOW_BYTES
        val suffixRequest = try {
            val builder = okhttp3.Request.Builder().url(target)
            headers?.forEach { (name, value) -> builder.header(name, value) }
            builder.header("Range", "bytes=-$tailWindow").header("Accept-Encoding", "identity").build()
        } catch (_: Exception) {
            return
        }
        try {
            prewarmHttpClient.newCall(suffixRequest).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // No-op: the head-triggered fallback tail still runs.
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val window = PrewarmWindowReader.read(response, maxBytes = tailWindow, start = null) {
                        logPrewarmRejection("suffix-tail", it)
                    } ?: return
                    // Accept only a full-size suffix so the stored window matches
                    // what peekTail/materialise expect (a file smaller than the
                    // window is left to the head-triggered path, which skips it).
                    if (window.bytes.size.toLong() != tailWindow) return
                    PrefetchWindowStore.putTail(
                        ParallelRangeDataSource.BootstrapCacheEntry(
                            requestUri = android.net.Uri.parse(target),
                            startPosition = window.start,
                            resolvedUri = android.net.Uri.parse(response.request.url.toString()),
                            openLength = tailWindow,
                            totalFileLength = window.total,
                            bootstrapData = window.bytes,
                            bootstrapSize = window.bytes.size,
                            createdAtUptimeMs = android.os.SystemClock.uptimeMillis()
                        )
                    )
                }
            })
        } catch (_: Exception) {
        }
    }

    /**
     * nt8: warm the second socket with the extractor's NEXT read -- the
     * tail window -- and capture it. Rejects short bodies (the window must
     * be exact for position arithmetic) and skips files small enough that
     * the head window already covers them.
     */
    private fun enqueueTailPrewarm(target: String, headers: Map<String, String>?, total: Long) {
        // B-2: skip when the concurrent suffix-range tail already stored a
        // window for this URI, so a suffix-honouring source warms the tail
        // once, not twice. When the concurrent tail was range-rejected this is
        // false and the head-triggered path runs exactly as before.
        if (PrefetchWindowStore.hasFreshTail(android.net.Uri.parse(target))) return
        val tailWindow = PrefetchWindowStore.TAIL_WINDOW_BYTES
        val tailStart = total - tailWindow
        if (tailStart <= 262144L) return
        val tailRequest = try {
            val builder = okhttp3.Request.Builder().url(target)
            headers?.forEach { (name, value) -> builder.header(name, value) }
            builder.header("Range", "bytes=$tailStart-${total - 1}").header("Accept-Encoding", "identity").build()
        } catch (_: Exception) {
            return
        }
        try {
            prewarmHttpClient.newCall(tailRequest).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // No-op by design.
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    // Exact window at tailStart of the same total (tailStart = total - window).
                    val window = PrewarmWindowReader.read(
                        response, maxBytes = tailWindow, start = tailStart, expectedTotal = total
                    ) { logPrewarmRejection("tail", it) } ?: return
                    PrefetchWindowStore.putTail(
                        ParallelRangeDataSource.BootstrapCacheEntry(
                            requestUri = android.net.Uri.parse(target),
                            startPosition = tailStart,
                            resolvedUri = android.net.Uri.parse(response.request.url.toString()),
                            openLength = tailWindow,
                            totalFileLength = total,
                            bootstrapData = window.bytes,
                            bootstrapSize = window.bytes.size,
                            createdAtUptimeMs = android.os.SystemClock.uptimeMillis()
                        )
                    )
                }
            })
        } catch (_: Exception) {
        }
    }

    /**
     * S1g: shares [NuvioExoPlayerPerformanceHelper.sharedConnectionPool] with the
     * playback data sources, which is the whole point -- a connection warmed here
     * must be the one the probe later picks up. History: proven FALSE on the
     * live path in the 26 Jul capture (disjoint POOL_IDs) because the pool was
     * a swapped var and this lazy captured the pre-swap instance. The pool is
     * now a fixed singleton val, so every applyNetworkOptimizations client
     * shares it by construction; POOL_ID stays as the standing verification.
     */
    internal val prewarmHttpClient: OkHttpClient by lazy {
        cookieClientBuilder()
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
            .also { logPoolIdentity("prewarm", it) }
    }

    /**
     * S1g's pool-sharing claim is only true if the prewarm client and the
     * client the probe uses hold the SAME ConnectionPool instance. The 26 Jul
     * capture decided it: the pool was then a reassigned var and the two
     * integers differed (44918017 vs 158407223). The var is now a fixed
     * singleton val, so matching ids are the EXPECTED steady state; a
     * mismatch here means the invariant regressed.
     */
    private fun logPoolIdentity(label: String, client: OkHttpClient) {
        val poolId = System.identityHashCode(client.connectionPool)
        val protos = client.protocols.joinToString(",")
        android.util.Log.i(
            "NuvioNet",
            "POOL_ID client=$label pool=$poolId protocols=$protos " +
                "maxIdle=${NuvioExoPlayerPerformanceHelper.NUVIO_SHARED_POOL_MAX_IDLE} " +
                "demand=${NuvioExoPlayerPerformanceHelper.connectionPoolSize}"
        )
    }

    fun createHttpClient(defaultHeaders: Map<String, String> = emptyMap()): OkHttpClient {
        val builder = cookieClientBuilder()
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
            .also { logPoolIdentity("datasource", it) }
    }

    @UnstableApi
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val client = createHttpClient(defaultHeaders)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(PlayerMediaSourceFactory.defaultUserAgentUnlessSet(defaultHeaders))
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
    }
}
