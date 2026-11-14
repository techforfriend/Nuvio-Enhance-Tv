package com.nuvio.tv.core.network.tls

import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Spec 68 X7 S1: P1 + P2 over real TLS (MockWebServer, keytool fixtures in `x7tls/`). Two fake
 * host names resolve to loopback, so "another host" is a different name, not a different port.
 */
class AddonTlsTrustTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    private fun leaf(name: String): X509Certificate = X7TlsFixtures.leaf(name)

    private fun server(fixture: String, http2: Boolean = false): MockWebServer =
        X7TlsFixtures.server(fixture, http2).also { servers += it }

    /** The JVM's default manager: none of the fixtures is trusted, like a self-signed addon. */
    private fun jvmPlatform(): X509TrustManager = platformTrustManager()

    private fun platformTrusting(fixture: String): X509TrustManager = X7TlsFixtures.platformTrusting(fixture)

    private fun pin(host: String, fixture: String, port: Int = 443) = X7TlsFixtures.pin(host, fixture, port)

    private fun client(trust: AddonTlsTrust) = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
        .hostnameVerifier(trust.hostnameVerifier)
        .dns(X7TlsFixtures.loopbackDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun url(host: String, server: MockWebServer): HttpUrl = X7TlsFixtures.url(host, server)

    private fun get(client: OkHttpClient, host: String, server: MockWebServer): Int =
        client.newCall(Request.Builder().url(url(host, server)).build()).execute().use { it.code }

    private fun session(vararg fixtures: String): SSLSession {
        val chain: Array<java.security.cert.Certificate> = fixtures.map { leaf(it) }.toTypedArray()
        return mockk { every { peerCertificates } returns chain }
    }

    private inline fun <reified T : IOException> assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: IOException) {
            assertTrue("expected ${T::class.simpleName}, got $e", e is T)
        }
    }

    @Test
    fun `an unpinned self-signed certificate is refused`() {
        val server = server("a")
        val trust = AddonTlsTrust(jvmPlatform()) { AddonCertPins.EMPTY }
        assertRefused<SSLHandshakeException> { get(client(trust), "addon-a.test", server) }
    }

    @Test
    fun `a certificate pinned for this host is accepted, on any port`() {
        val server = server("a")
        val pins = AddonCertPins(listOf(pin("addon-a.test", "a", port = 1)))
        assertEquals(200, get(client(AddonTlsTrust(jvmPlatform()) { pins }), "addon-a.test", server))
    }

    @Test
    fun `a pinned certificate never vouches for another host`() {
        val server = server("a")
        val pins = AddonCertPins(listOf(pin("addon-a.test", "a")))
        // P1 lets the handshake finish (the leaf is pinned somewhere); P2 refuses the wrong host.
        assertRefused<SSLPeerUnverifiedException> {
            get(client(AddonTlsTrust(jvmPlatform()) { pins }), "addon-b.test", server)
        }
    }

    @Test
    fun `a CA-valid certificate pinned elsewhere still works on its own name`() {
        val server = server("a")
        val pins = AddonCertPins(listOf(pin("addon-b.test", "a")))
        assertEquals(200, get(client(AddonTlsTrust(platformTrusting("a")) { pins }), "addon-a.test", server))
    }

    @Test
    fun `a CA-valid certificate is refused on a name it does not carry`() {
        val server = server("a")
        assertRefused<SSLPeerUnverifiedException> {
            get(client(AddonTlsTrust(platformTrusting("a")) { AddonCertPins.EMPTY }), "addon-b.test", server)
        }
    }

    @Test
    fun `a changed certificate is refused`() {
        val server = server("a2")
        val pins = AddonCertPins(listOf(pin("addon-a.test", "a")))
        assertRefused<SSLHandshakeException> { get(client(AddonTlsTrust(jvmPlatform()) { pins }), "addon-a.test", server) }
    }

    @Test
    fun `a pinned leaf is accepted past its expiry`() {
        val server = server("a-expired")
        val pins = AddonCertPins(listOf(pin("addon-a.test", "a-expired")))
        assertEquals(200, get(client(AddonTlsTrust(jvmPlatform()) { pins }), "addon-a.test", server))
    }

    @Test
    fun `a second host on an open HTTP2 connection gets its own check`() {
        // The leaf names both hosts but is pinned for addon-a only. With our verifier OkHttp does not
        // reuse addon-a's HTTP/2 connection for addon-b: addon-b gets its own handshake and P2.
        val server = server("ab", http2 = true)
        val trust = AddonTlsTrust(jvmPlatform()) { AddonCertPins(listOf(pin("addon-a.test", "ab"))) }
        val client = client(trust)
        client.newCall(Request.Builder().url(url("addon-a.test", server)).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(Protocol.HTTP_2, it.protocol)
        }
        assertRefused<SSLPeerUnverifiedException> { get(client, "addon-b.test", server) }
    }

    @Test
    fun `P2 alone refuses an unpinned untrusted chain and accepts it pinned for this host`() {
        // A resumed TLS session skips the trust manager (S0), so the verifier must decide alone.
        val unpinned = AddonTlsTrust(jvmPlatform()) { AddonCertPins.EMPTY }
        assertFalse(unpinned.hostnameVerifier.verify("addon-a.test", session("a")))
        val pinned = AddonTlsTrust(jvmPlatform()) { AddonCertPins(listOf(pin("addon-a.test", "a"))) }
        assertTrue(pinned.hostnameVerifier.verify("addon-a.test", session("a")))
        assertFalse(pinned.hostnameVerifier.verify("addon-b.test", session("a")))
    }

    @Test
    fun `a valid host never reads the pin store`() {
        val server = server("a")
        var reads = 0
        val trust = AddonTlsTrust(platformTrusting("a")) { reads++; AddonCertPins.EMPTY }
        assertEquals(200, get(client(trust), "addon-a.test", server))
        assertTrue(trust.hostnameVerifier.verify("addon-a.test", session("a")))
        assertEquals(0, reads)
    }

    @Test
    fun `a pin-store read failure fails closed`() {
        val server = server("a")
        val trust = AddonTlsTrust(jvmPlatform()) { throw IOException("disk") }
        assertRefused<SSLHandshakeException> { get(client(trust), "addon-a.test", server) }
    }

    @Test
    fun `the verifier returns false instead of throwing, Errors included`() {
        val trust = AddonTlsTrust(jvmPlatform()) { AddonCertPins.EMPTY }
        val broken = mockk<SSLSession> { every { peerCertificates } throws IllegalStateException("boom") }
        assertFalse(trust.hostnameVerifier.verify("addon-a.test", broken))
        val erroring = AddonTlsTrust(jvmPlatform()) { throw ExceptionInInitializerError("first DataStore use") }
        assertFalse(erroring.hostnameVerifier.verify("addon-a.test", session("a")))
    }

    @Test
    fun `a platform RuntimeException still lets a pinned leaf through`() {
        val throwing = mockk<X509TrustManager> {
            every { checkServerTrusted(any(), any()) } throws IllegalArgumentException("bad chain")
        }
        val pins = AddonCertPins(listOf(pin("addon-a.test", "a")))
        AddonTrustManager(throwing) { pins }.checkServerTrusted(arrayOf(leaf("a")), "GENERIC")
        assertTrue(AddonTrustPolicy(throwing) { pins }.isTrusted("addon-a.test", listOf(leaf("a"))))
    }

    @Test
    fun `only CertificateException leaves the trust manager`() {
        val throwing = mockk<X509TrustManager> {
            every { checkServerTrusted(any(), any()) } throws IllegalArgumentException("bad chain")
        }
        val manager = AddonTrustManager(throwing) { AddonCertPins.EMPTY }
        try {
            manager.checkServerTrusted(arrayOf(leaf("a")), "GENERIC")
            fail("expected CertificateException")
        } catch (e: CertificateException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun `pins match their host case-insensitively and only that host`() {
        val pins = AddonCertPins(listOf(AddonCertPin("addon-a.test", 8443, "AB", 0L)))
        assertTrue(pins.isPinnedForHost("Addon-A.test", "AB"))
        assertFalse(pins.isPinnedForHost("addon-b.test", "AB"))
        assertTrue(pins.containsFingerprint("AB"))
    }

}
