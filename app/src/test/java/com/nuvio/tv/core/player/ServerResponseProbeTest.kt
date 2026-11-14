package com.nuvio.tv.core.player

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Spec 75 D1.1: the bounded look at what a server sent instead of video. */
class ServerResponseProbeTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun judge(
        body: String,
        contentType: String? = null,
        status: Int = 200,
        total: Long? = null,
        redirected: Boolean = false,
        path: String = "/dl/file.mkv"
    ) = ServerResponseProbe.judge(status, contentType, total, body.toByteArray(), redirected, "cdn.example", path)

    @Test
    fun `an html page is a web page with its title as plain text`() {
        val finding = judge(
            "<!DOCTYPE html><html><head><title> Fair use\n limit &amp; reached </title></head></html>",
            "text/html; charset=utf-8",
            total = 14_000
        )!!
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, finding.kind)
        assertEquals("Fair use limit & reached", finding.title)
        assertEquals("text/html", finding.contentType)
        assertEquals(14_000L, finding.sizeBytes)
    }

    @Test
    fun `an html body sent as octet-stream is still a web page`() {
        val finding = judge("  <html><body>Error</body></html>", "application/octet-stream", total = 40)!!
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, finding.kind)
        assertNull(finding.title)
    }

    @Test
    fun `media starts learn nothing, even with a wrong type`() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3)
        assertNull(ServerResponseProbe.judge(200, "text/html", 6_000_000_000, mkv, false, "h", "/f"))
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()
        assertNull(ServerResponseProbe.judge(206, null, 6_000_000_000, mp4, false, "h", "/f"))
        assertTrue(ServerResponseProbe.looksLikeMedia("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
    }

    @Test
    fun `text, a tiny file and an http error are named`() {
        assertEquals(ServerResponseFinding.Kind.TEXT, judge("quota exceeded", "text/plain", total = 14)!!.kind)
        assertEquals(ServerResponseFinding.Kind.TEXT, judge("{\"error\":\"expired\"}", "application/json")!!.kind)
        val tiny = ServerResponseProbe.judge(200, "video/x-matroska", 2_048, ByteArray(2_048) { 7 }, false, "h", "/f")!!
        assertEquals(ServerResponseFinding.Kind.TINY_FILE, tiny.kind)
        assertEquals(2_048L, tiny.sizeBytes)
        assertEquals(ServerResponseFinding.Kind.HTTP_ERROR, judge("gone", "text/plain", status = 410)!!.kind)
    }

    @Test
    fun `a large binary of unknown kind learns nothing`() {
        assertNull(ServerResponseProbe.judge(206, "application/octet-stream", 6_000_000_000, ByteArray(4_096) { 0 }, false, "h", "/f"))
        assertNull(ServerResponseProbe.judge(206, null, null, ByteArray(4_096) { 0 }, false, "h", "/f"))
    }

    @Test
    fun `a redirect onto a sign-in path is a login page`() {
        val finding = judge("<html><title>Sign in</title></html>", "text/html", redirected = true, path = "/users/sign_in")!!
        assertEquals(ServerResponseFinding.Kind.LOGIN_PAGE, finding.kind)
        assertEquals("cdn.example/users/sign_in", finding.loginHostPath)
        // Not redirected: the URL was the page itself, judged by its body.
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, judge("<html></html>", "text/html", path = "/login")!!.kind)
        // A word inside a segment is no sign-in path.
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, judge("<html></html>", "text/html", redirected = true, path = "/blogin/x")!!.kind)
    }

    @Test
    fun `titles are cleaned of markup, controls, bidi overrides and cut long`() {
        assertEquals("a b", ServerResponseProbe.cleanTitle("a<b>\u0000\u202E</b> \u200B b"))
        assertEquals("'x' <y>", ServerResponseProbe.cleanTitle("&#39;x&#x27; &lt;y&gt;"))
        assertNull(ServerResponseProbe.cleanTitle(" <i></i> \u202D "))
        val long = ServerResponseProbe.cleanTitle("x".repeat(500))!!
        assertEquals(80, long.length)
        assertTrue(long.endsWith("\u2026"))
        assertEquals("\uD83D\uDE00", ServerResponseProbe.cleanTitle("&#128512;"))
    }

    @Test
    fun `the declared charset decodes the title`() {
        val body = "<title>Caf\u00e9</title>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Caf\u00e9", ServerResponseProbe.pageTitle(body, "text/html; charset=ISO-8859-1"))
        assertEquals("Caf\u00e9", ServerResponseProbe.pageTitle("<title>Caf\u00e9</title>".toByteArray(), "text/html; charset=bogus-x"))
    }

    @Test
    fun `the whole size comes from the range total, the length or a short body`() {
        assertEquals(6_000L, ServerResponseProbe.totalBytes(206, "bytes 0-65535/6000", null, 65_536, false))
        assertEquals(14_000L, ServerResponseProbe.totalBytes(200, null, 14_000, 14_000, true))
        assertEquals(900L, ServerResponseProbe.totalBytes(200, null, null, 900, true))
        assertNull(ServerResponseProbe.totalBytes(206, "bytes 0-65535/*", null, 65_536, false))
    }

    @Test
    fun `the probe asks for the first 64 KB and sizes the page from its length`() = runBlocking {
        val page = "<html><head><title>Fair use limit reached</title></head><body>" + "x".repeat(200_000) + "</body></html>"
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(page))
        server.start()
        val finding = ServerResponseProbe.probe(server.url("/dl/file.mkv").toString(), mapOf("X-Addon" to "1"))!!
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, finding.kind)
        assertEquals("Fair use limit reached", finding.title)
        assertEquals(page.length.toLong(), finding.sizeBytes)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("bytes=0-65535", request.getHeader("Range"))
        assertEquals("1", request.getHeader("X-Addon"))
    }

    @Test
    fun `a redirect to another host gets what the player sends there, never the Authorization`() = runBlocking {
        // The player follows the same chain on the same OkHttp client, which keeps the stream's
        // headers and drops Authorization when the host changes; the probe adds nothing to that
        // (no re-adding interceptor, unlike the player's own WebDAV client).
        val other = MockWebServer()
        other.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("quota"))
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/cdn/file.mkv").toString()))
            server.start()
            ServerResponseProbe.probe(
                server.url("/dl/file.mkv").toString(),
                mapOf("Authorization" to "Bearer secret", "X-Addon" to "1")
            )
            assertEquals("Bearer secret", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val hop = other.takeRequest(1, TimeUnit.SECONDS)!!
            assertNull(hop.getHeader("Authorization"))
            assertEquals("1", hop.getHeader("X-Addon"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `the probe follows a redirect to a sign-in page`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/account/login?next=x"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html></html>"))
        server.start()
        val finding = ServerResponseProbe.probe(server.url("/dl/file.mkv").toString(), emptyMap())!!
        assertEquals(ServerResponseFinding.Kind.LOGIN_PAGE, finding.kind)
        assertEquals("${server.hostName}/account/login", finding.loginHostPath)
    }

    @Test
    fun `a server that stalls past the cap gives up in about three seconds`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(ByteArray(10))).setBodyDelay(10, TimeUnit.SECONDS)
        )
        server.start()
        val started = System.nanoTime()
        val finding = ServerResponseProbe.probe(server.url("/slow").toString(), emptyMap())
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull(finding)
        assertTrue("took $tookMs ms", tookMs < 5_000)
    }

    @Test
    fun `a dropped connection or a non-http url learns nothing`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.start()
        assertNull(ServerResponseProbe.probe(server.url("/x").toString(), emptyMap()))
        assertNull(ServerResponseProbe.probe("magnet:?xt=urn:btih:abc", emptyMap()))
    }

    @Test
    fun `short text starting with G is text, not a transport stream`() {
        assertEquals(ServerResponseFinding.Kind.TEXT, judge("Gone", "text/plain", total = 4)!!.kind)
        assertEquals(ServerResponseFinding.Kind.TEXT, judge("Generating link, retry", null, total = 22)!!.kind)
        val ts = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
        assertTrue(ServerResponseProbe.looksLikeMedia(ts))
    }

    @Test
    fun `media on a signed auth path is no sign-in page`() {
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3)
        assertNull(ServerResponseProbe.judge(206, "video/x-matroska", 6_000_000_000, mkv, true, "cdn", "/auth/tok/f.mkv"))
    }

    @Test
    fun `playlists with a BOM or as a DASH type are media`() {
        assertNull(judge("\uFEFF  #EXTM3U\n#EXT-X-VERSION:3", "text/plain"))
        assertNull(judge("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash\">", "text/xml"))
        // A small typed body would otherwise be a tiny file: the type alone marks a playlist.
        assertNull(judge("{\"x\":1}", "application/dash+xml", total = 7))
        assertNull(judge("{\"x\":1}", "application/vnd.apple.mpegurl", total = 7))
    }

    @Test
    fun `entities decode once, marks are capped and emoji joiners kept`() {
        assertEquals("&lt; &#38;", ServerResponseProbe.cleanTitle("&amp;lt; &amp;#38;"))
        assertEquals("a\u0301\u0302b", ServerResponseProbe.cleanTitle("a\u0301\u0302\u0303\u0304b"))
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBB", ServerResponseProbe.cleanTitle("\uD83D\uDC69\u200D\uD83D\uDCBB"))
        assertEquals("x", ServerResponseProbe.cleanTitle("x <unclosed tag and more"))
    }

    @Test
    fun `a hostile body is scanned in linear time`() {
        val hostile = ("<title" + "<".repeat(10)).repeat(5_000).toByteArray()
        val started = System.nanoTime()
        ServerResponseProbe.pageTitle(hostile, "text/html")
        ServerResponseProbe.cleanTitle("<".repeat(64 * 1024))
        ServerResponseProbe.cleanTitle("&".repeat(64 * 1024))
        ServerResponseProbe.cleanTitle("&a".repeat(32 * 1024))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 1_000)
    }

    @Test
    fun `cancelling the caller ends the probe at once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x").setHeadersDelay(10, TimeUnit.SECONDS))
        server.start()
        val started = System.nanoTime()
        val probe = async(kotlinx.coroutines.Dispatchers.IO) { ServerResponseProbe.probe(server.url("/slow").toString(), emptyMap()) }
        delay(200)
        probe.cancel()
        probe.join()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 1_500)
        // The call itself was cancelled, not just abandoned (it would run on until its headers arrive).
        delay(300)
        assertEquals(0, ServerResponseProbe.client.dispatcher.runningCallsCount())
    }

    @Test
    fun `a server ignoring the range answers 200 and the probe still stops at the cap`() = runBlocking {
        // 8 MiB at 256 KiB per 100 ms: reading (or draining) it all would take over 3 s.
        val film = ByteArray(8 * 1024 * 1024)
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(film)).throttleBody(256L * 1024, 100, TimeUnit.MILLISECONDS)
        )
        server.start()
        val started = System.nanoTime()
        assertNull(ServerResponseProbe.probe(server.url("/film.mkv").toString(), emptyMap()))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 1_500)
    }

    @Test
    fun `the probe client has no app interceptor and the 3 s call cap`() {
        assertTrue(ServerResponseProbe.client.interceptors.isEmpty())
        assertEquals(3_000, ServerResponseProbe.client.callTimeoutMillis)
    }

    @Test
    fun `the charset parameter is read in any case and spacing`() {
        assertEquals("ISO-8859-1", ServerResponseProbe.declaredCharset("text/html; Charset = \"ISO-8859-1\""))
        assertEquals("utf-8", ServerResponseProbe.declaredCharset("text/html;CHARSET=utf-8"))
        assertNull(ServerResponseProbe.declaredCharset("text/html"))
        assertNull(ServerResponseProbe.declaredCharset("text/html; charset"))
    }

    @Test
    fun `joiners do not reset the combining-mark cap`() {
        val title = ServerResponseProbe.cleanTitle("a" + "\u200D\u0301\u0301".repeat(20))!!
        assertEquals(2, title.count { it == '\u0301' })
    }

    @Test
    fun `sign-in paths by segment, file name and auth`() {
        fun login(path: String) = judge("<html></html>", "text/html", redirected = true, path = path)?.kind
        assertEquals(ServerResponseFinding.Kind.LOGIN_PAGE, login("/login.php"))
        assertEquals(ServerResponseFinding.Kind.LOGIN_PAGE, login("/auth/start"))
        assertEquals(ServerResponseFinding.Kind.LOGIN_PAGE, login("/Sign-In"))
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, login("/authors/x"))
        assertEquals(ServerResponseFinding.Kind.WEB_PAGE, login("/loginhelp"))
    }

    @Test
    fun `round 3 minors - entity window, m2ts, structured text types, servlet sign-in, bad input`() {
        // An 8-character entity name decodes; a 9-character one is left as text.
        assertEquals("\uD83D\uDE00", ServerResponseProbe.cleanTitle("&#0128512;"))
        assertEquals("&#00128512;", ServerResponseProbe.cleanTitle("&#00128512;"))
        assertEquals("A \u2013 B \u00BB C\u2026", ServerResponseProbe.cleanTitle("A &ndash; B &raquo; C&hellip;"))
        val m2ts = ByteArray(400).also { it[4] = 0x47; it[196] = 0x47 }
        assertTrue(ServerResponseProbe.looksLikeMedia(m2ts))
        assertEquals(ServerResponseFinding.Kind.TEXT, judge("{}", "application/problem+json", total = 2)!!.kind)
        assertEquals(
            ServerResponseFinding.Kind.LOGIN_PAGE,
            judge("<html></html>", "text/html", redirected = true, path = "/login;jsessionid=AB12")!!.kind
        )
    }

    @Test
    fun `a url or header OkHttp rejects learns nothing`() = runBlocking {
        assertNull(ServerResponseProbe.probe("http://", emptyMap()))
        server.start()
        assertNull(ServerResponseProbe.probe(server.url("/x").toString(), mapOf("X-Bad" to "line\nbreak")))
    }
}
