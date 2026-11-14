package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PlayerMediaSourceFactoryTest {

    @Test
    fun `default user agent only when the addon headers carry none`() {
        // Register A2 P4: OkHttpDataSource adds its user agent on top of the request properties.
        assertEquals(
            PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
            PlayerMediaSourceFactory.defaultUserAgentUnlessSet(mapOf("Referer" to "x"))
        )
        assertNull(PlayerMediaSourceFactory.defaultUserAgentUnlessSet(mapOf("user-agent" to "Addon/1")))
    }

    @Test
    fun `headers OkHttp would reject are dropped, valid ones kept`() {
        val bad = mapOf(
            "X Space" to "v", "X:Colon" to "v", "(paren)" to "v", "Ünï" to "v", "X-Utf" to "café",
            "X-Tab" to "a\tb", "X-Nl" to "a\nb", "X-Del" to "a\u007Fb", "Range" to "bytes=0-", "X-Empty" to " "
        )
        val good = mapOf("Cookie" to "a=1; b=2", "User-Agent" to "Mozilla/5.0 (X11)", "!#$%&'*+-.^_`|~09azAZ" to "~\"q\" {}")
        val kept = PlayerMediaSourceFactory.sanitizeHeaders(bad + good)
        assertEquals(good, kept)
        // What the rule guards against: OkHttp throws on these (a failed request). It accepts `:` and `(` in a
        // name, but they are not RFC 9110 tokens: `X:Colon: v` would reach the server as header `X`.
        val builder = okhttp3.Request.Builder().url("https://example.org/")
        kept.forEach { (k, v) -> builder.header(k, v) }
        builder.build()
        for ((k, v) in bad.filterKeys { it in setOf("X Space", "Ünï", "X-Utf", "X-Nl", "X-Del") }) {
            assertTrue(k, runCatching { okhttp3.Request.Builder().url("https://example.org/").header(k, v) }.isFailure)
        }
    }

    @Test
    fun `files exoplayer cannot open are recognised by extension from filename or url path`() {
        // Format-capability G2 (Blu-ray .m2ts/.mts) and Spec 85 G8 (.wmv/.asf): these start on mpv.
        val onMpv = PlayerMediaSourceFactory::startsOnMpvByName
        assertTrue(onMpv("https://cdn.example/f/Movie.Remux.M2TS?token=a.b", null))
        assertTrue(onMpv("https://debrid.example/dl/123", "00001.mts"))
        assertTrue(onMpv("https://debrid.example/dl/123", "Movie.m2ts"))
        assertFalse(onMpv("https://cdn.example/f/Movie.ts", null))
        assertFalse(onMpv("https://cdn.example/f/Movie.mkv?x=y.m2ts", null))
        assertFalse(onMpv("https://debrid.example/dl/123", null))
        assertTrue(onMpv("https://cdn.example/f/clip.mts#t=10", null))
        assertTrue(onMpv("https://debrid.example/dl/123", "Movie #2.m2ts"))
        assertTrue(onMpv("https://debrid.example/dl/123", "  Movie.M2TS "))
        // Either hint matches: an .m2ts URL path wins over an unrelated filename hint.
        assertTrue(onMpv("https://cdn.example/f/Movie.m2ts", "Movie.mkv"))
        assertFalse(onMpv("https://cdn.example/f/Movie.m2ts/", null))
        // WMV/ASF: no ASF extractor in Media3.
        assertTrue(onMpv("https://cdn.example/f/Old.Movie.WMV?sig=1", null))
        assertTrue(onMpv("https://debrid.example/dl/123", "Clip.asf"))
        assertFalse(onMpv("https://cdn.example/f/Movie.wma", null))
        assertFalse(onMpv("https://cdn.example/f/Movie.mkv?x=y.wmv", null))
    }

    @Test
    fun `inferMimeType prefers response content type for manifest urls without extension`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType uses content disposition filename when content type is missing`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Disposition" to "attachment; filename=manifest.mpd")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType ignores generic playlist path without manifest evidence`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/api/playlist/stream",
            filename = null
        )

        assertNull(mimeType)
    }

    @Test
    fun `inferMimeType recognizes explicit format query values`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?format=m3u8",
            filename = null
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `normalizeMimeType recognizes redirected matroska file responses`() {
        val mimeType = PlayerMediaSourceFactory.normalizeMimeType("video/x-matroska")

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType uses filename star content disposition for octet stream responses`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/extract?id=42",
            filename = null,
            responseHeaders = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Disposition" to "attachment; filename*=UTF-8''episode-04.mkv"
            )
        )

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType prefers URL extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/stream.m3u8",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType prefers filename extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = "movie.mpd",
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType recognizes playlist endpoints with numeric or token ids as HLS`() {
        val urls = listOf(
            "https://example.com/playlist/759755?token=mock_token_123&expires=1788170323&h=1&lang=it",
            "https://example.com/playlist/123456",
            "https://example.com/playlist/a1b2c3d4e5f6?h=1",
            "https://example.com/hls/759755",
            "https://example.com/manifest/abc123456",
            "https://example.com/master/stream99",
            "https://example.com/live/stream.m3u",
            "https://example.com/playback?protocol=hls"
        )

        for (url in urls) {
            val mimeType = PlayerMediaSourceFactory.inferMimeType(
                url = url,
                filename = null
            )
            assertEquals("Expected HLS mimeType for $url", MimeTypes.APPLICATION_M3U8, mimeType)
        }
    }

    @Test
    fun `normalizePlaybackRequest converts URL userinfo to authorization header`() {
        val userInfo = "test-user:test-pass"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/movies/title.mkv?download=1",
            headers = mapOf("Range" to "bytes=0-1")
        )

        assertEquals("https://webdav.example.org/movies/title.mkv?download=1", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
        assertFalse(request.headers.containsKey("Range"))
    }

    @Test
    fun `normalizePlaybackRequest strips URL userinfo without replacing explicit authorization`() {
        val explicitAuth = "explicit:value".basicAuthHeader()
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + "test-user:test-pass" + "@webdav.example.org/movies/title.mkv",
            headers = mapOf("Authorization" to explicitAuth)
        )

        assertEquals("https://webdav.example.org/movies/title.mkv", request.url)
        assertEquals(explicitAuth, request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest preserves encoded path query and fragment when stripping userinfo`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo.replace("@", "%40") + "@webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            headers = emptyMap()
        )

        assertEquals(
            "https://webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            request.url
        )
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest strips userinfo containing a literal at sign`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/files/title.mkv",
            headers = emptyMap()
        )

        assertEquals("https://webdav.example.org/files/title.mkv", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    private fun String.basicAuthHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))
}
