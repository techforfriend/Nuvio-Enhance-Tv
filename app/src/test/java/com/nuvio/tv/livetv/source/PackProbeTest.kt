package com.nuvio.tv.livetv.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** [PackProbe] against a local server (Spec 73 S3a): each probe class, the LAN guard, the caps. */
class PackProbeTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    return routes[request.path] ?: MockResponse().setResponseCode(404)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The local server is loopback: these probes treat every address as public. */
    private fun probe(timeoutMs: Long = PackProbe.PROBE_TIMEOUT_MS) =
        PackProbe(OkHttpClient(), isPublic = { true }, probeTimeoutMs = timeoutMs)

    private fun url(path: String) = server.url(path).toString()

    private fun run(path: String, probe: PackProbe = probe(), headers: Map<String, String>? = null) =
        runBlocking { probe.probe(PackStream(url(path), PackState.ALIVE, headers)) }

    private val tsBytes = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }

    private fun bytes(body: ByteArray) = MockResponse().setBody(Buffer().write(body))

    private fun media(vararg segments: String) =
        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + segments.joinToString("") { "#EXTINF:6,\n$it\n" }

    @Test
    fun `an HLS master leads to its first variant and the segment third from the end`() {
        routes["/master.m3u8"] = MockResponse().setBody(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nhigh.m3u8\n"
        )
        routes["/low/index.m3u8"] = MockResponse().setBody(media("s1.ts", "s2.ts", "s3.ts", "s4.ts"))
        routes["/low/s2.ts"] = bytes(tsBytes)
        assertTrue(run("/master.m3u8"))
        val segment = seen.single { it.path == "/low/s2.ts" }
        assertEquals("bytes=0-8191", segment.getHeader("Range"))
        assertTrue(seen.none { it.path == "/high.m3u8" })
    }

    @Test
    fun `row headers are sent, the player's User-Agent only when the row sets none`() {
        routes["/a.ts"] = bytes(tsBytes)
        assertTrue(run("/a.ts", headers = mapOf("Referer" to "https://site/", "User-Agent" to "Row UA")))
        val first = seen.removeAt(0)
        assertEquals("https://site/", first.getHeader("Referer"))
        assertEquals(listOf("Row UA"), first.headers.values("User-Agent"))
        assertTrue(run("/a.ts"))
        assertEquals(1, seen.single().headers.values("User-Agent").size)
    }

    @Test
    fun `an encrypted segment still answers, a text one does not`() {
        routes["/enc.m3u8"] = MockResponse().setBody(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n" +
                "#EXTINF:6,\ne.ts\n"
        )
        routes["/e.ts"] = bytes(Random(7).nextBytes(8192))
        assertTrue(run("/enc.m3u8"))

        routes["/page.m3u8"] = MockResponse().setBody(media("p.ts"))
        routes["/p.ts"] = MockResponse().setBody("<html><body>Access denied</body></html>")
        assertFalse(run("/page.m3u8"))
    }

    @Test
    fun `a DASH manifest answers, an error status, an empty playlist and a web page do not`() {
        routes["/a.mpd"] = MockResponse().setBody("<?xml version=\"1.0\"?><MPD><ContentProtection/></MPD>")
        assertTrue(run("/a.mpd"))
        routes["/f.m3u8"] = MockResponse().setResponseCode(403)
        assertFalse(run("/f.m3u8"))
        routes["/e.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST\n")
        assertFalse(run("/e.m3u8"))
        routes["/w"] = MockResponse().setHeader("Content-Type", "video/mp2t").setBody("<html>Not here</html>")
        assertFalse(run("/w"))
    }

    @Test
    fun `two masters then a media playlist answer`() {
        routes["/n0.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nn1.m3u8\n")
        routes["/n1.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nn2.m3u8\n")
        routes["/n2.m3u8"] = MockResponse().setBody(media("s.ts"))
        routes["/s.ts"] = bytes(tsBytes)
        assertTrue(run("/n0.m3u8"))
    }

    @Test
    fun `masters nested past the hop limit fail`() {
        routes["/m0.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nm1.m3u8\n")
        routes["/m1.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nm2.m3u8\n")
        routes["/m2.m3u8"] = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nm3.m3u8\n")
        assertFalse(run("/m0.m3u8"))
        assertTrue(seen.none { it.path == "/m3.m3u8" })
    }

    private fun at(host: String, path: String) = server.url(path).newBuilder().host(host).build().toString()

    @Test
    fun `a probe's time counts its requests, not its wait for the host's slot`() = runBlocking {
        routes["/a.ts"] = bytes(tsBytes).setBodyDelay(300, TimeUnit.MILLISECONDS)
        val slots = HostSlots(perHost = 1)
        val probe = probe(timeoutMs = 700)
        // The third waits ~600 ms for the others' slot, then needs ~300 ms itself: past its 700 ms only if
        // the wait counted (S3a r1).
        val answers = List(3) {
            async(Dispatchers.IO) { probe.probe(PackStream(url("/a.ts"), PackState.ALIVE), slots) }
        }.awaitAll()
        assertEquals(listOf(true, true, true), answers)
    }

    @Test
    fun `a host's requests run at most its slots at once`() = runBlocking {
        val arrived = Collections.synchronizedList(mutableListOf<Long>())
        routes["/a.ts"] = bytes(tsBytes).setBodyDelay(400, TimeUnit.MILLISECONDS)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived += System.nanoTime() / 1_000_000
                return routes.getValue(request.path!!)
            }
        }
        val slots = HostSlots(perHost = 1)
        val probe = probe()
        List(2) { async(Dispatchers.IO) { probe.probe(PackStream(url("/a.ts"), PackState.ALIVE), slots) } }.awaitAll()
        assertEquals(2, arrived.size)
        assertTrue("${arrived[1] - arrived[0]} ms apart", arrived[1] - arrived[0] >= 350)
    }

    @Test
    fun `a probe's requests share one budget`() = runBlocking {
        routes["/r"] = MockResponse().setResponseCode(302).setHeader("Location", "/a.ts")
            .setHeadersDelay(400, TimeUnit.MILLISECONDS)
        routes["/a.ts"] = bytes(tsBytes).setHeadersDelay(400, TimeUnit.MILLISECONDS)
        assertFalse(run("/r", probe(timeoutMs = 600))) // ~400 ms left for the second: it times out
        assertTrue(run("/r", probe(timeoutMs = 1_500)))
    }

    /** Until the server has seen [n] requests (a request in flight holds its slot). */
    private suspend fun awaitRequests(n: Int) = withTimeout(3_000) { while (seen.size < n) delay(10) }

    @Test
    fun `a probe starts only while idle, then runs on through a pause, and the wait is not probe time`() = runBlocking {
        routes["/r"] = MockResponse().setResponseCode(302).setHeader("Location", "/a.ts")
            .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        routes["/a.ts"] = bytes(tsBytes)
        val idle = MutableStateFlow(false)
        val slots = HostSlots(idle = idle)
        val answer = async(Dispatchers.IO) { probe(timeoutMs = 1_000).probe(PackStream(url("/r"), PackState.ALIVE), slots) }
        delay(1_200) // longer than the probe's budget
        assertTrue(seen.isEmpty())
        idle.value = true
        awaitRequests(1) // the redirect is under way (its slot held)
        idle.value = false // playback starts: the started probe's next hop goes on
        assertTrue(withTimeout(3_000) { answer.await() })
        assertEquals(listOf("/r", "/a.ts"), seen.map { it.path })
    }

    @Test
    fun `probes waiting to start hold no slot, so a started probe gets its host's`() = runBlocking {
        routes["/r"] = MockResponse().setResponseCode(302).setHeader("Location", "/a.ts")
            .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        routes["/a.ts"] = bytes(tsBytes)
        val idle = MutableStateFlow(true)
        val slots = HostSlots(perHost = 1, idle = idle)
        val probe = probe()
        val started = async(Dispatchers.IO) { probe.probe(PackStream(url("/r"), PackState.ALIVE), slots) }
        awaitRequests(1) // started: its first request holds the host's one slot
        idle.value = false
        val waiting = List(3) { async(Dispatchers.IO) { probe.probe(PackStream(url("/a.ts"), PackState.ALIVE), slots) } }
        assertTrue(withTimeout(3_000) { started.await() }) // its second hop got the host's one slot
        idle.value = true
        assertEquals(listOf(true, true, true), withTimeout(3_000) { waiting.awaitAll() })
    }

    @Test
    fun `each request holds its own host's slot, so a redirector's is free once it redirects`() = runBlocking {
        // Two host names for the one local server: the redirector and where it lands (D15 Q3, jmp2.uk).
        routes["/jump"] = MockResponse().setResponseCode(302).setHeader("Location", at("127.0.0.1", "/slow.ts"))
        routes["/slow.ts"] = bytes(tsBytes).setBodyDelay(3_000, TimeUnit.MILLISECONDS)
        routes["/fast.ts"] = bytes(tsBytes)
        val slots = HostSlots(perHost = 1)
        val probe = probe()
        val slow = async(Dispatchers.IO) { probe.probe(PackStream(at("localhost", "/jump"), PackState.ALIVE), slots) }
        delay(400) // redirected: the slow hop holds 127.0.0.1's slot, localhost's is free
        val started = System.nanoTime()
        assertTrue(probe.probe(PackStream(at("localhost", "/fast.ts"), PackState.ALIVE), slots))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertTrue(slow.await())
    }

    @Test
    fun `redirects are followed, to the limit`() {
        routes["/r"] = MockResponse().setResponseCode(302).setHeader("Location", "/a.ts")
        routes["/a.ts"] = bytes(tsBytes)
        assertTrue(run("/r"))
        for (i in 0..PackProbe.MAX_REDIRECTS) {
            routes["/loop$i"] = MockResponse().setResponseCode(302).setHeader("Location", "/loop${i + 1}")
        }
        routes["/loop${PackProbe.MAX_REDIRECTS + 1}"] = bytes(tsBytes)
        assertFalse(run("/loop0"))
    }

    @Test
    fun `a playlist body is read only to its cap`() {
        // The only URI line is cut by the cap: a partial line is dropped, never requested.
        val big = "#EXTM3U\n#EXTINF:6,\n" + "a".repeat(PackProbe.PLAYLIST_CAP) + ".ts\n"
        routes["/big.m3u8"] = MockResponse().setBody(big)
        assertFalse(run("/big.m3u8"))
        assertEquals(listOf("/big.m3u8"), seen.map { it.path })
    }

    @Test
    fun `a server that never answers fails within the probe timeout`() {
        routes["/slow.m3u8"] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val started = System.nanoTime()
        assertFalse(run("/slow.m3u8", probe(timeoutMs = 500)))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    /** Every address a connection was started to. */
    private val connects: MutableList<InetAddress> = Collections.synchronizedList(mutableListOf())
    private val recording = OkHttpClient.Builder().eventListener(object : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects += inetSocketAddress.address
        }
    }).build()

    @Test
    fun `a LAN host, by name, by any literal form or through a redirect, is never connected to`() {
        routes["/a.ts"] = bytes(tsBytes)
        val guarded = PackProbe(recording) // the real address check: loopback is not public
        // A name, the dotted, short and decimal IPv4 forms of loopback, IPv6 loopback, and a
        // digits-and-dots host that is no IPv4 form (refused, never looked up).
        for (host in listOf("localhost", "127.0.0.1", "127.1", "2130706433", "[::1]", "1.2.3.4.5")) {
            assertFalse(host, runBlocking { guarded.probe(PackStream("http://$host:${server.port}/a.ts", PackState.ALIVE)) })
        }
        assertEquals(emptyList<InetAddress>(), connects)

        // Loopback allowed (the test server), the private 10/8 block not: a redirect there, written
        // dotted or as one decimal number (167772161 = 10.0.0.1), is refused before connecting.
        val loopbackAllowed = PackProbe(recording, isPublic = { !it.isSiteLocalAddress })
        routes["/to-lan"] = MockResponse().setResponseCode(302).setHeader("Location", "http://10.1.2.3/a.ts")
        routes["/to-lan-decimal"] = MockResponse().setResponseCode(302).setHeader("Location", "http://167772161/a.ts")
        for (path in listOf("/to-lan", "/to-lan-decimal")) {
            assertFalse(path, runBlocking { loopbackAllowed.probe(PackStream(url(path), PackState.ALIVE)) })
        }
        assertEquals(listOf("/to-lan", "/to-lan-decimal"), seen.map { it.path })
        assertTrue("$connects", connects.all { it.isLoopbackAddress })
    }

    @Test
    fun `a header value the request builder refuses fails the probe`() {
        routes["/a.ts"] = bytes(tsBytes)
        assertFalse(run("/a.ts", headers = mapOf("Referer" to "bad\nvalue")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `public addresses`() {
        fun public(address: String) = PackProbe.publicAddress(InetAddress.getByName(address))
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888").forEach { assertTrue(it, public(it)) }
        listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.10", "169.254.1.1", "100.64.0.1", "0.0.0.0",
            "224.0.0.1", "240.0.0.1", "198.18.0.1", "::1", "fe80::1", "fd00::1", "fc00::1"
        ).forEach { assertFalse(it, public(it)) }
    }
}
