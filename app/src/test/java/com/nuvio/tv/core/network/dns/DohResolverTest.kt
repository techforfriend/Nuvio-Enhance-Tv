package com.nuvio.tv.core.network.dns

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** Spec 97 N2 against okhttp-dnsoverhttps 5.3.2 itself, on a fake RFC 8484 server. */
class DohResolverTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // N1: the provider's host is unresolvable, so a lookup works only through the bootstrap IPs.
    private fun resolver() = dohResolver(
        privateDnsHttpClient(),
        "http://doh.invalid:${server.port}/dns-query",
        listOf(InetAddress.getLoopbackAddress())
    )

    /** A DNS response for one A question: [rcode] 0 with [address], else no answer. */
    private fun dnsMessage(host: String, rcode: Int, address: ByteArray? = null): Buffer = Buffer().apply {
        writeShort(0)
        writeShort(0x8180 or rcode)
        writeShort(1)
        writeShort(if (address != null) 1 else 0)
        writeShort(0)
        writeShort(0)
        host.split('.').forEach { label -> writeByte(label.length); writeUtf8(label) }
        writeByte(0)
        writeShort(1)
        writeShort(1)
        if (address != null) {
            writeShort(0xC00C)
            writeShort(1)
            writeShort(1)
            writeInt(60)
            writeShort(4)
            write(address)
        }
    }

    private fun answer(body: Buffer, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/dns-message").setBody(body)

    private fun assertFails(host: String) {
        try {
            resolver().lookup(host)
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun `one A query by GET, answered`() {
        server.enqueue(answer(dnsMessage("api.example.com", 0, byteArrayOf(93, 1, 2, 3))))
        assertEquals(listOf(InetAddress.getByName("93.1.2.3")), resolver().lookup("api.example.com"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.requestUrl!!.queryParameter("dns") != null)
        assertEquals("A only: no AAAA query", 1, server.requestCount)
    }

    @Test
    fun `nxdomain, servfail, an empty answer and an http error all fail`() {
        server.enqueue(answer(dnsMessage("a.example.com", 3)))
        assertFails("a.example.com")
        server.enqueue(answer(dnsMessage("a.example.com", 2)))
        assertFails("a.example.com")
        server.enqueue(answer(dnsMessage("a.example.com", 0)))
        assertFails("a.example.com")
        server.enqueue(MockResponse().setResponseCode(500))
        assertFails("a.example.com")
    }

    @Test
    fun `a provider that never answers fails within the call budget`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertFails("slow.example.com")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 6_000)
    }

    @Test
    fun `a malformed answer and a dead provider fail`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/dns-message").setBody("not a dns message"))
        assertFails("a.example.com")
        server.shutdown()
        assertFails("a.example.com")
    }

    @Test
    fun `the DoH client keeps read below call and its own dispatcher`() {
        val client = privateDnsHttpClient()
        assertEquals(3_000, client.connectTimeoutMillis)
        assertEquals(2_000, client.readTimeoutMillis)
        assertEquals(5_000, client.callTimeoutMillis)
        assertEquals(64, client.dispatcher.maxRequests)
        assertEquals(64, client.dispatcher.maxRequestsPerHost)
    }
}
