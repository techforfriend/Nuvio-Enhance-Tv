package com.nuvio.tv.core.network.tls

import com.nuvio.tv.core.network.AddonHostGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.X509TrustManager

/** Spec 68 X7 P7: the certificate probe over real TLS (fixtures in `x7tls/`). */
class AddonCertProbeTest {

    private val servers = mutableListOf<MockWebServer>()
    private val guard = AddonHostGuard(nowMs = { 0L }, wallNowMs = { 0L })

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    private fun server(fixture: String) = X7TlsFixtures.server(fixture).also { servers += it }

    private fun probe(
        pins: AddonCertPins = AddonCertPins.EMPTY,
        platform: X509TrustManager = platformTrustManager()
    ) = AddonCertProbe(
        policy = AddonTrustPolicy(platform) { pins },
        pins = { pins },
        guard = guard,
        dns = X7TlsFixtures.loopbackDns,
        io = Dispatchers.IO,
        nowMs = System::currentTimeMillis
    )

    private fun addonUrl(host: String, server: MockWebServer) = "https://$host:${server.port}/secret-key/manifest.json"

    private fun untrusted(result: AddonCertProbeResult): AddonCertProbeResult.Untrusted {
        assertTrue("expected Untrusted, got $result", result is AddonCertProbeResult.Untrusted)
        return result as AddonCertProbeResult.Untrusted
    }

    @Test
    fun `a self-signed server is captured without a request, with its breaker open`() = runBlocking {
        val server = server("a")
        val key = "addon-a.test:${server.port}"
        repeat(AddonHostGuard.FAILURES_TO_OPEN) { guard.onCertificateFailure(key, probe = false) }
        assertEquals(AddonHostGuard.Admission.SKIP_CERTIFICATE, guard.admit(key))

        val result = untrusted(probe().probe(addonUrl("addon-a.test", server)))

        assertEquals(AddonCertProbeResult.Reason.SELF_SIGNED, result.reason)
        assertEquals(leafSha256(X7TlsFixtures.leaf("a")), result.sha256)
        assertEquals("addon-a.test", result.host)
        assertEquals(server.port, result.port)
        assertEquals("addon-a.test", result.shownName)
        assertNull(result.previousSha256)
        assertEquals(0, server.requestCount)
        assertEquals(setOf(key), guard.certificateFailures.value) // only Trust or a passing probe clears it
    }

    @Test
    fun `an expired leaf reads as expired`() = runBlocking {
        val server = server("a-expired")
        assertEquals(
            AddonCertProbeResult.Reason.EXPIRED,
            untrusted(probe().probe(addonUrl("addon-a.test", server))).reason
        )
    }

    @Test
    fun `a trusted chain on the wrong name reads as wrong name`() = runBlocking {
        val server = server("a")
        val result = untrusted(
            probe(platform = X7TlsFixtures.platformTrusting("a")).probe(addonUrl("addon-b.test", server))
        )
        assertEquals(AddonCertProbeResult.Reason.WRONG_NAME, result.reason)
        assertEquals("addon-a.test", result.shownName)
    }

    @Test
    fun `a certificate that passes now clears the flag`() = runBlocking {
        val server = server("a")
        val key = "addon-a.test:${server.port}"
        guard.onCertificateFailure(key, probe = false)
        val pins = AddonCertPins(listOf(X7TlsFixtures.pin("addon-a.test", "a", port = server.port)))
        assertEquals(AddonCertProbeResult.VerifiesNow, probe(pins).probe(addonUrl("addon-a.test", server)))
        assertTrue(guard.certificateFailures.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a changed certificate carries the port's old pin`() = runBlocking {
        val server = server("a2")
        val old = X7TlsFixtures.pin("addon-a.test", "a", port = server.port)
        val result = untrusted(probe(AddonCertPins(listOf(old))).probe(addonUrl("addon-a.test", server)))
        assertEquals(old.sha256, result.previousSha256)
        assertEquals(leafSha256(X7TlsFixtures.leaf("a2")), result.sha256)
    }

    @Test
    fun `an http address or an unreachable server gives no certificate and leaves the flag`() = runBlocking {
        val dead = MockWebServer().apply { start() }
        val port = dead.port
        dead.shutdown()
        val key = "addon-a.test:$port"
        guard.onCertificateFailure(key, probe = false)
        assertEquals(AddonCertProbeResult.NoCertificate, probe().probe("https://addon-a.test:$port/manifest.json"))
        assertEquals(AddonCertProbeResult.NoCertificate, probe().probe("http://addon-a.test/manifest.json"))
        assertEquals(setOf(key), guard.certificateFailures.value)
    }

    @Test
    fun `self-signed is told apart from an unknown issuer`() {
        assertTrue(isSelfSigned(X7TlsFixtures.leaf("a")))
        val issued = io.mockk.mockk<java.security.cert.X509Certificate> {
            io.mockk.every { subjectX500Principal } returns javax.security.auth.x500.X500Principal("CN=nas.lan")
            io.mockk.every { issuerX500Principal } returns javax.security.auth.x500.X500Principal("CN=Home CA")
        }
        assertFalse(isSelfSigned(issued))
        // Same name as its issuer, but signed by another key (a home CA named like the server).
        val namedLikeIssuer = io.mockk.mockk<java.security.cert.X509Certificate> {
            io.mockk.every { subjectX500Principal } returns javax.security.auth.x500.X500Principal("CN=nas.lan")
            io.mockk.every { issuerX500Principal } returns javax.security.auth.x500.X500Principal("CN=nas.lan")
            io.mockk.every { publicKey } returns X7TlsFixtures.leaf("a").publicKey
            io.mockk.every { verify(any<java.security.PublicKey>()) } throws java.security.SignatureException("other key")
        }
        assertFalse(isSelfSigned(namedLikeIssuer))
    }
}
