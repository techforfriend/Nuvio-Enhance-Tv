package com.nuvio.tv.livetv.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SourceFetcherTest {

    private lateinit var server: MockWebServer
    private val fetcher = SourceFetcher(OkHttpClient(), UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/source").toString()

    private fun gzip(text: String): Buffer {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return Buffer().write(out.toByteArray())
    }

    @Test
    fun `detects each kind from the head, never the URL`() {
        assertEquals(SourceKind.M3U, SourceFetcher.detectKind("\uFEFF#EXTM3U x-tvg-url=\"a\"\n"))
        assertEquals(SourceKind.M3U, SourceFetcher.detectKind("\n#EXTINF:-1,Name\nhttp://x/a.m3u8"))
        assertEquals(SourceKind.XMLTV, SourceFetcher.detectKind("<?xml version=\"1.0\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv generator-info-name=\"x\">"))
        assertEquals(SourceKind.XMLTV, SourceFetcher.detectKind("<tv>"))
        assertEquals(SourceKind.STREMIO, SourceFetcher.detectKind("  {\"id\":\"org.x\",\"types\":[\"tv\"]}"))
        assertNull(SourceFetcher.detectKind("<!DOCTYPE html><html><body>Not found</body></html>"))
        assertNull(SourceFetcher.detectKind("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash\">"))
        assertNull(SourceFetcher.detectKind("G@\u0000\u0010"))
    }

    @Test
    fun `fetches a playlist with its kind`() = runTest {
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,A\nhttp://x/a.m3u8\n"))
        val (kind, text) = fetcher.fetch(url()) { kind, body -> kind to body.reader().readText() }
        assertEquals(SourceKind.M3U, kind)
        assertTrue(text.startsWith("#EXTM3U"))
    }

    @Test
    fun `gzip is detected by magic bytes and inflated whatever the headers say`() = runTest {
        // free-epg.de shape: a .gz body with no gzip Content-Encoding the client would undo.
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(gzip("<tv>\n</tv>")))
        val (kind, text) = fetcher.fetch(url()) { kind, body -> kind to body.reader().readText() }
        assertEquals(SourceKind.XMLTV, kind)
        assertEquals("<tv>\n</tv>", text)
    }

    @Test
    fun `a non-2xx status fails with the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("#EXTM3U\n"))
        try {
            fetcher.fetch(url()) { _, _ -> Unit }
            fail("expected a failure")
        } catch (e: SourceFetchException) {
            assertEquals("HTTP 404", e.message)
        }
    }

    @Test
    fun `an address the client cannot request fails as such`() = runTest {
        try {
            fetcher.fetch("ftp://example.org/list.m3u") { _, _ -> Unit }
            fail("expected a failure")
        } catch (e: SourceFetchException) {
            assertEquals("Not a web address", e.message)
        }
    }

    @Test
    fun `the cap counts inflated bytes`() {
        // ~100 bytes compressed, 10 000 inflated: only an inflated count trips a 5 000-byte cap.
        val body = gzip("#EXTM3U\n" + "#".repeat(10_000)).readByteArray()
        assertTrue(body.size < 5_000)
        val (kind, stream) = SourceFetcher.sniff(ByteArrayInputStream(body), maxTextBytes = 5_000)
        assertEquals(SourceKind.M3U, kind)
        try {
            stream.readBytes()
            fail("expected the cap")
        } catch (e: SourceFetchException) {
            assertTrue(e.message!!.startsWith("Larger than"))
        }
        val (_, roomy) = SourceFetcher.sniff(ByteArrayInputStream(body), maxTextBytes = 20_000)
        assertEquals(10_008, roomy.readBytes().size)
    }

    @Test
    fun `a probe HEADs the URL and reports status and size`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", 1234))
        assertEquals(SourceFetcher.Probe(200, 1234), fetcher.probe(url()))
        assertEquals("HEAD", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, fetcher.probe(url()).code)
    }

    @Test
    fun `a refused certificate reads as a fixed reason, fetch and probe alike`() = runTest {
        val refusing = OkHttpClient.Builder().addInterceptor {
            throw javax.net.ssl.SSLHandshakeException("handshake").apply {
                initCause(java.security.cert.CertificateException("self-signed"))
            }
        }.build()
        val certFetcher = SourceFetcher(refusing, UnconfinedTestDispatcher())
        val fetchFailure = runCatching { certFetcher.fetch(url()) { _, _ -> Unit } }.exceptionOrNull()
        assertEquals(SourceFetcher.CERTIFICATE_REFUSED, (fetchFailure as SourceFetchException).message)
        val probeFailure = runCatching { certFetcher.probe(url()) }.exceptionOrNull()
        assertEquals(SourceFetcher.CERTIFICATE_REFUSED, (probeFailure as SourceFetchException).message)
    }
}
