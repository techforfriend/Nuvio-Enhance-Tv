package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.tls.AddonCertPins
import com.nuvio.tv.core.network.tls.AddonTlsTrust
import com.nuvio.tv.core.network.tls.platformTrustManager
import com.nuvio.tv.data.remote.api.AddonApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * W2.1 single-home contracts. The shared interceptor helpers are the facts
 * every DI client composes, so their behavior is pinned here against real
 * HTTP round-trips. The @Image/@BackendAuth/@Download providers are
 * context-free and are pinned directly; @Api's TLS and cache directory are
 * pinned through a mocked Context (Spec 68 X7). The @Validated provider stays
 * provider-only
 * (@Validated's 30s cap is the same one-line shape; a Robolectric pin can ride
 * if this file ever gains Robolectric).
 */
class OkHttpClientArchitectureTest {

    private lateinit var server: MockWebServer

    @get:Rule
    val tmp = TemporaryFolder()

    private val addonTls = AddonTlsTrust(platformTrustManager()) { AddonCertPins.EMPTY }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(client: OkHttpClient) {
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body }
    }

    @Test
    fun `identity headers are injected on api-shaped requests`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(identityHeadersInterceptor())
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        get(client)
        val recorded = server.takeRequest()
        assertTrue(
            "User-Agent must carry the app identity",
            recorded.getHeader("User-Agent")!!.startsWith("Nuvio/")
        )
        assertTrue(
            "Accept-Language must be present for addon localization",
            !recorded.getHeader("Accept-Language").isNullOrBlank()
        )
    }

    @Test
    fun `stream requests keep a caller User-Agent through retrofit and the identity interceptor`() = runBlocking {
        // X1: AIOStreams sends streamData only to a UA starting with "AIO". The stream fetch
        // passes that UA as a @Header; identity must not overwrite it, and a null @Header
        // (every other addon) must still reach the wire as the app UA.
        assertTrue(aioStreamDataUserAgent().startsWith("AIO"))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(identityHeadersInterceptor()).build())
            .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
            .build()
            .create(AddonApi::class.java)
        server.enqueue(MockResponse().setBody("{\"streams\":[]}"))
        server.enqueue(MockResponse().setBody("{\"streams\":[]}"))

        api.getStreams(server.url("/aio/stream/movie/tt1.json").toString(), aioStreamDataUserAgent())
        api.getStreams(server.url("/other/stream/movie/tt1.json").toString(), null)

        val aio = server.takeRequest()
        assertEquals(aioStreamDataUserAgent(), aio.getHeader("User-Agent"))
        assertEquals("exactly one User-Agent line", 1, aio.headers.values("User-Agent").size)
        assertTrue(!aio.getHeader("Accept-Language").isNullOrBlank())
        assertEquals(appUserAgent(), server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `error responses are marked no-store, successes are untouched`() {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(noStoreOnErrorNetworkInterceptor())
            .build()

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        client.newCall(Request.Builder().url(server.url("/error")).build()).execute().use {
            assertEquals("no-store", it.header("Cache-Control"))
        }

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(Request.Builder().url(server.url("/ok")).build()).execute().use {
            assertNull(it.header("Cache-Control"))
        }
    }

    @Test
    fun `logging is HEADERS in debug and NONE in release`() {
        // W2.4: release logs nothing (addon config keys ride URLs; the
        // logging-interceptor 4.12 artifact has no redactQueryParams); debug
        // is capped at HEADERS. The level decision is a pure function so both
        // legs are pinned here — the unit gate only ever runs the debug
        // variant, so the live wiring is additionally covered by the W2.4
        // audit (zero Level.BODY anywhere, zero URL logging in release).
        assertEquals(HttpLoggingInterceptor.Level.HEADERS, httpLogLevel(isDebug = true))
        assertEquals(HttpLoggingInterceptor.Level.NONE, httpLogLevel(isDebug = false))
    }

    @Test
    fun `image client keeps the coil-appropriate shape`() {
        val client = NetworkModule.provideImageOkHttpClient()
        assertEquals(12_000, client.callTimeoutMillis)
        assertEquals(4_000, client.connectTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertEquals(32, client.dispatcher.maxRequests)
        assertEquals(16, client.dispatcher.maxRequestsPerHost)
        // Coil 3.3.0 never reads an OkHttp cache; one here would be dead weight.
        assertNull(client.cache)
    }

    @Test
    fun `download client keeps the streaming shape`() {
        val client = NetworkModule.provideDownloadOkHttpClient(addonTls)
        // No callTimeout — downloads span body reads; a total bound here would
        // kill large files (consumers time-bound themselves).
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(30_000, client.connectTimeoutMillis)
        // No cache and no guard: binary payloads must not hit the JSON gate
        // or the 2MB response cap.
        assertNull(client.cache)
    }

    @Test
    fun `addon clients use the validated addon TLS, never trust-all`() {
        // Spec 68 X7: @Api and @Download validate certificates (platform, or a pin the user
        // trusted for that host); no client may go back to accepting any certificate.
        val context = mockk<Context> { every { cacheDir } returns tmp.root }
        val api = NetworkModule.provideApiOkHttpClient(context, addonTls, AddonHostGuard())
        val download = NetworkModule.provideDownloadOkHttpClient(addonTls)
        val unverifiedSession = mockk<SSLSession> {
            every { peerCertificates } throws SSLPeerUnverifiedException("no peer certificate")
        }
        for (client in listOf(api, download)) {
            assertSame(addonTls.trustManager, client.x509TrustManager)
            assertSame(addonTls.hostnameVerifier, client.hostnameVerifier)
            assertFalse(client.hostnameVerifier.verify("addon.example", unverifiedSession))
            assertTrue(client.x509TrustManager!!.acceptedIssuers.isNotEmpty())
        }
        // The trust-all era's cache (http_cache) is not reused.
        assertEquals(ADDON_HTTP_CACHE_DIR, api.cache!!.directory.name)
    }

    @Test
    fun `bounded request-response clients keep the whole-call cap`() {
        // W2.1 parity pins: connect/read timeouts do NOT cover the DNS phase,
        // so every remote request/response client carries a callTimeout
        // (ke13 DNS-outage finding — a dropped cap recreates unbounded stalls).
        assertEquals(30_000, NetworkModule.provideBackendAuthHttpClient().callTimeoutMillis)
    }

    @Test
    fun `backend auth client validates TLS certificates and hostnames`() {
        // L10-21: password sign-in, token refresh and TV login ride this client
        // on every backend; it must never inherit @Api's trust-all TLS.
        val client = NetworkModule.provideBackendAuthHttpClient()
        val unverifiedSession = mockk<SSLSession> {
            every { peerCertificates } throws SSLPeerUnverifiedException("no peer certificate")
        }
        assertFalse(
            "hostname verifier must reject a session without a verified peer",
            client.hostnameVerifier.verify("backend.example", unverifiedSession)
        )
        assertTrue(
            "trust manager must carry real CA issuers, not trust-all's empty set",
            client.x509TrustManager!!.acceptedIssuers.isNotEmpty()
        )
    }

    @Test
    fun `retrofit base urls gain a trailing slash and blanks fall back`() {
        assertEquals("https://api.example/v1/", baseUrlOr("https://api.example/v1", "http://localhost/"))
        assertEquals("https://api.example/v1/", baseUrlOr("https://api.example/v1/", "http://localhost/"))
        assertEquals("http://localhost/", baseUrlOr("", "http://localhost/"))
        assertEquals("http://localhost/", baseUrlOr("   ", "http://localhost/"))
    }
}
