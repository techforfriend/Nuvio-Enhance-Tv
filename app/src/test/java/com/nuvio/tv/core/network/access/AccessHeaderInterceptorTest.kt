package com.nuvio.tv.core.network.access

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.tls.X7TlsFixtures
import com.nuvio.tv.core.player.PlayerPlaybackNetworking
import com.nuvio.tv.core.player.ServerResponseProbe
import android.content.Context
import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.tls.AddonCertPins
import com.nuvio.tv.core.network.tls.AddonTlsTrust
import com.nuvio.tv.core.network.tls.platformTrustManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Spec 97 A3/A8 over real TLS: two names (`addon-a.test`, `addon-b.test`) on one loopback server. */
class AccessHeaderInterceptorTest {

    private val servers = mutableListOf<MockWebServer>()

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        AccessHeaders.source = null
        servers.forEach { it.shutdown() }
    }

    private fun tlsServer(): MockWebServer {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(X7TlsFixtures.keyStore("ab"), "x7tls-test".toCharArray())
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return MockWebServer().apply {
            useHttps(context.socketFactory, false)
            start()
            servers += this
        }
    }

    private fun client(): OkHttpClient {
        val trust = X7TlsFixtures.platformTrusting("ab")
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .dns(X7TlsFixtures.loopbackDns)
            .sslSocketFactory(context.socketFactory, trust)
            .addNetworkInterceptor(AccessHeaderInterceptor)
            .build()
    }

    private fun save(vararg sites: AccessSite) {
        AccessHeaders.source = { sites.toList() }
    }

    private fun secret(host: String, port: Int) =
        AccessSite(host, port, listOf(AccessHeader("CF-Access-Client-Id", "id"), AccessHeader("CF-Access-Client-Secret", "s3cret")))

    private fun get(url: String, client: OkHttpClient = client(), extra: Pair<String, String>? = null) =
        client.newCall(Request.Builder().url(url).apply { extra?.let { header(it.first, it.second) } }.build()).execute()

    @Test
    fun `a saved https site gets its headers, replacing a same-named one`() {
        val server = tlsServer()
        server.enqueue(MockResponse().setBody("ok"))
        save(secret("addon-a.test", server.port))
        get("https://addon-a.test:${server.port}/x", extra = "CF-Access-Client-Secret" to "from-addon").close()
        val recorded = server.takeRequest()
        assertEquals("id", recorded.getHeader("CF-Access-Client-Id"))
        assertEquals(listOf("s3cret"), recorded.headers.values("CF-Access-Client-Secret"))
    }

    @Test
    fun `another host or port gets nothing`() {
        val server = tlsServer()
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        save(secret("addon-a.test", server.port + 1), secret("addon-c.test", server.port))
        get("https://addon-a.test:${server.port}/").close()
        get("https://addon-b.test:${server.port}/").close()
        repeat(2) { assertNull(server.takeRequest().getHeader("CF-Access-Client-Secret")) }
    }

    @Test
    fun `a redirect hop is matched by its own host`() {
        val server = tlsServer()
        val port = server.port
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://addon-b.test:$port/off"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://addon-a.test:$port/back"))
        server.enqueue(MockResponse().setBody("ok"))
        save(secret("addon-a.test", port))
        get("https://addon-a.test:$port/start").use { assertEquals(200, it.code) }
        assertEquals("s3cret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertNull("off the saved site", server.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertEquals("s3cret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `plain http never gets headers`() {
        val server = MockWebServer().apply { start(); servers += this }
        server.enqueue(MockResponse().setBody("ok"))
        save(secret(server.url("/").host, server.port))
        get(server.url("/").toString(), client = OkHttpClient.Builder().addNetworkInterceptor(AccessHeaderInterceptor).build()).close()
        assertNull(server.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `a saved site's response loses Set-Cookie and Vary tokens naming its headers`() {
        val server = tlsServer()
        server.enqueue(
            MockResponse().setBody("ok")
                .addHeader("Set-Cookie", "CF_Authorization=abc")
                .addHeader("Vary", "Accept-Encoding, cf-access-client-secret")
                .addHeader("Vary", "CF-Access-Client-Id")
        )
        save(secret("addon-a.test", server.port))
        get("https://addon-a.test:${server.port}/").use { response ->
            assertNull(response.header("Set-Cookie"))
            assertEquals(listOf("Accept-Encoding"), response.headers.values("Vary"))
        }
    }

    @Test
    fun `other hosts' responses are untouched`() {
        val server = tlsServer()
        server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "a=b").addHeader("Vary", "CF-Access-Client-Secret"))
        save(secret("addon-a.test", server.port))
        get("https://addon-b.test:${server.port}/").use { response ->
            assertEquals("a=b", response.header("Set-Cookie"))
            assertEquals("CF-Access-Client-Secret", response.header("Vary"))
        }
    }

    @Test
    fun `plain http never reads the store`() {
        val server = MockWebServer().apply { start(); servers += this }
        server.enqueue(MockResponse().setBody("ok"))
        var reads = 0
        AccessHeaders.source = { reads++; emptyList() }
        get(server.url("/").toString(), client = OkHttpClient.Builder().addNetworkInterceptor(AccessHeaderInterceptor).build()).close()
        assertEquals(0, reads)
    }

    @Test
    fun `neither the secret nor a cookie reaches the disk cache`() {
        val server = tlsServer()
        server.enqueue(
            MockResponse().setBody("cached-body")
                .addHeader("Cache-Control", "max-age=600")
                .addHeader("Set-Cookie", "CF_Authorization=cookie-token")
                .addHeader("Vary", "CF-Access-Client-Secret")
        )
        save(secret("addon-a.test", server.port))
        val cacheDir = tmp.newFolder("http-cache")
        val cached = client().newBuilder().cache(Cache(cacheDir, 1_000_000)).build()
        get("https://addon-a.test:${server.port}/c", client = cached).use { it.body.string() }
        get("https://addon-a.test:${server.port}/c", client = cached).use { assertNotNull("served from cache", it.cacheResponse) }
        val onDisk = cacheDir.walkTopDown().filter { it.isFile }.joinToString("") { it.readText(Charsets.ISO_8859_1) }
        assertTrue(onDisk.contains("cached-body"))
        assertFalse(onDisk.contains("s3cret"))
        assertFalse(onDisk.contains("cookie-token"))
    }

    @Test
    fun `a failing source sends the request without headers`() {
        val server = tlsServer()
        server.enqueue(MockResponse().setBody("ok"))
        AccessHeaders.source = { throw IllegalStateException("boom") }
        get("https://addon-a.test:${server.port}/").use { assertEquals(200, it.code) }
        assertNull(server.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `the validated clients carry the interceptor and the trust-all client never does`() {
        val context = mockk<Context> { every { cacheDir } returns tmp.root }
        val addonTls = AddonTlsTrust(platformTrustManager()) { AddonCertPins.EMPTY }
        listOf(
            NetworkModule.provideApiOkHttpClient(context, addonTls, AddonHostGuard()),
            NetworkModule.provideDownloadOkHttpClient(addonTls),
            NetworkModule.provideValidatedOkHttpClient(context),
            NetworkModule.provideImageOkHttpClient(),
            NetworkModule.provideBackendAuthHttpClient(),
            PlayerPlaybackNetworking.playbackHttpClient,
            PlayerPlaybackNetworking.createHttpClient(mapOf("Authorization" to "Basic x"))
        ).forEach { assertTrue(AccessHeaderInterceptor in it.networkInterceptors) }
        // A6: the probe client keeps the network interceptor but drops the trust-all retry.
        assertTrue(AccessHeaderInterceptor in ServerResponseProbe.client.networkInterceptors)
        assertTrue(ServerResponseProbe.client.interceptors.isEmpty())
        assertFalse(AccessHeaderInterceptor in PlayerPlaybackNetworking.trustAllPlaybackHttpClient.networkInterceptors)
    }
}
