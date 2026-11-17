package com.nuvio.tv.livetv.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Spec 34 D7: mime from the URL, else one GET for extension-less URLs (not Xtream live, Spec 78 D3); headers normalized. */
class LiveMediaSourcesTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(LiveMediaSources.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LiveMediaSources.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(path: String, headers: Map<String, String> = emptyMap()) =
        LiveMediaSources.normalize(LiveStream(server.url(path).toString(), headers))

    @Test
    fun `rtsp keeps its URL and needs no request, other non-http schemes are named (Spec 90 L4)`() = runTest {
        // A .ts path would type as MPEG-TS without the rtsp guard.
        val rtsp = LiveMediaSources.normalize(LiveStream("rtsp://user:pw@cam.example:554/ch1.ts", mapOf("Referer" to "x")))
        assertEquals("rtsp://user:pw@cam.example:554/ch1.ts", rtsp.url)
        assertEquals(emptyMap<String, String>(), rtsp.headers)
        assertNull(LiveMediaSources.resolveMimeType(rtsp, client))

        assertNull(LiveMediaSources.unsupportedScheme("http://x/a.ts"))
        assertNull(LiveMediaSources.unsupportedScheme(" HTTPS://x/a.m3u8"))
        assertNull(LiveMediaSources.unsupportedScheme("rtsp://x/live"))
        assertNull(LiveMediaSources.unsupportedScheme("not a url"))
        assertEquals("rtmp", LiveMediaSources.unsupportedScheme("rtmp://x/live/stream"))
        assertEquals("udp", LiveMediaSources.unsupportedScheme("UDP://@239.0.0.1:1234"))
        assertEquals("srt", LiveMediaSources.unsupportedScheme("srt://x:9000"))
        assertNull(LiveMediaSources.unsupportedScheme("host.example/u/p/live?ref=http://x"))
    }

    @Test
    fun `an extension decides without a request`() = runTest {
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/live/index.m3u8"), client))
        assertEquals(MimeTypes.APPLICATION_MPD, LiveMediaSources.resolveMimeType(request("/live/manifest.mpd"), client))
        assertEquals(MimeTypes.VIDEO_MP2T, LiveMediaSources.resolveMimeType(request("/live/1234.ts"), client))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an extension-less playlist is sniffed from its first bytes`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/play/42"), client))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a TS body is progressive whatever its content type says (Spec 90 L2)`() = runTest {
        val ts = ByteArray(400).also { it[0] = 0x47; it[188] = 0x47 }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(okio.Buffer().write(ts)))
        assertNull(LiveMediaSources.resolveMimeType(request("/play/44"), client))
    }

    @Test
    fun `an HLS content type decides before the playlist text`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("x"))
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/play/43"), client))
    }

    @Test
    fun `an extension-less Xtream live url plays as progressive without a request`() = runTest {
        // Answers a regressed sniff at once (as HLS), so it fails fast instead of on the read timeout.
        repeat(2) { server.enqueue(MockResponse().setBody("#EXTM3U")) }
        assertNull(LiveMediaSources.resolveMimeType(request("/user1/pass1/1003"), client))
        assertNull(LiveMediaSources.resolveMimeType(request("/live/user1/pass1/1004"), client))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `path tokens still type an Xtream-shaped url before the shortcut`() = runTest {
        // Non-Xtream HLS of the same shape keeps its old inference (Spec 78 D3 red-team r1).
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/hls/chan/1005"), client))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a near-Xtream url with a query is still sniffed`() = runTest {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/user1/pass1/1003?token=x"), client))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `raw transport stream bytes and failures read as progressive`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("G\u0040\u0000\u0010 payload"))
        assertNull(LiveMediaSources.resolveMimeType(request("/stream/7"), client))
        server.enqueue(MockResponse().setResponseCode(404).setBody("#EXTM3U"))
        assertNull(LiveMediaSources.resolveMimeType(request("/stream/8"), client))
    }

    @Test
    fun `stream headers go out on the sniff with the default user agent unless set`() = runTest {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        LiveMediaSources.resolveMimeType(request("/a", mapOf("Referer" to "https://example.org/")), client)
        val first = server.takeRequest()
        assertEquals("https://example.org/", first.getHeader("Referer"))
        assertTrue(first.getHeader("User-Agent").orEmpty().startsWith("Mozilla/5.0"))

        server.enqueue(MockResponse().setBody("#EXTM3U"))
        LiveMediaSources.resolveMimeType(request("/b", mapOf("User-Agent" to "VLC/3.0")), client)
        assertEquals(listOf("VLC/3.0"), server.takeRequest().headers.values("User-Agent"))
    }

    @Test
    fun `the live client keeps the D7 timeouts and no call timeout`() {
        val live = LiveMediaSources.client(emptyMap())
        assertEquals(4_000, live.connectTimeoutMillis)
        assertEquals(8_000, live.readTimeoutMillis)
        assertEquals(0, live.callTimeoutMillis)
    }

    @Test
    fun `a redirect to an m3u8 path decides when the body does not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/live/index.m3u8"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("\n\n"))
        assertEquals(MimeTypes.APPLICATION_M3U8, LiveMediaSources.resolveMimeType(request("/go/1"), client))
    }

    @Test
    fun `user info becomes basic auth and leaves the url`() {
        val normalized = LiveMediaSources.normalize(LiveStream("http://me:pw@host.example/live/1.ts"))
        assertEquals("http://host.example/live/1.ts", normalized.url)
        assertEquals("Basic bWU6cHc=", normalized.headers["Authorization"])
    }
}
