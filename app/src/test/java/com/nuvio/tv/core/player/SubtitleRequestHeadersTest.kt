package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 0.9.0 subtitle 403-header policy (T10 item 4): domain-scoped stream-header
 * forwarding, explicit per-subtitle header override, hop-by-hop exclusion, and
 * User-Agent/Accept defaults. Credential scope is by registrable domain (Spec 68 X1); the
 * real public suffix list needs an Android Context, so these tests use [fakeRegistrableDomain].
 */
class SubtitleRequestHeadersTest {

    private val defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) test"
    private val defaultAccept = "text/plain, text/vtt, application/x-subrip, */*"

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer debrid-token",
        "Cookie" to "session=1",
        "Proxy-Authorization" to "Basic x",
        "Referer" to "https://cdn.example.com/",
        "Origin" to "https://cdn.example.com",
        "User-Agent" to "NuvioPlayer/1.0",
        "Range" to "bytes=0-100",
        "Host" to "cdn.example.com",
        "Connection" to "keep-alive",
        "Transfer-Encoding" to "chunked"
    )

    /** Minimal public-suffix stand-in: longest matching suffix plus one label; null for IPs and bare suffixes. */
    private val fakeSuffixes = setOf("com", "org", "uk", "co.uk")
    private val fakeRegistrableDomain: (String) -> String? = { host ->
        val labels = host.lowercase().split('.')
        if (labels.all { label -> label.all(Char::isDigit) } || host.contains(':') || host in fakeSuffixes) {
            null
        } else {
            (1 until labels.size)
                .map { start -> labels.drop(start).joinToString(".") }
                .firstOrNull { suffix -> suffix in fakeSuffixes }
                ?.let { suffix -> labels[labels.size - suffix.count { it == '.' } - 2] + "." + suffix }
        }
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Test
    fun sameHost_forwardsAuthCookiesAndReferer_dropsHopByHop() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, sendCredentials = true,
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("Bearer debrid-token", headerValue(headers, "Authorization"))
        assertEquals("session=1", headerValue(headers, "Cookie"))
        assertEquals("Basic x", headerValue(headers, "Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", headerValue(headers, "Referer"))
        assertEquals("NuvioPlayer/1.0", headerValue(headers, "User-Agent"))
        assertNull(headerValue(headers, "Range"))
        assertNull(headerValue(headers, "Host"))
        assertNull(headerValue(headers, "Connection"))
        assertNull(headerValue(headers, "Transfer-Encoding"))
    }

    @Test
    fun subdomain_countsAsSameDomain() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders,
            sendCredentials = shouldSendStreamCredentials(
                "https://sub.cdn.example.com/a.srt", "https://cdn.example.com/v.mkv", fakeRegistrableDomain
            ),
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("Bearer debrid-token", headerValue(headers, "Authorization"))
    }

    @Test
    fun crossDomain_dropsAuth_keepsRefererOriginAndUa() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders,
            sendCredentials = shouldSendStreamCredentials(
                "https://dl.opensubtitles.org/a.srt", "https://cdn.example.com/v.mkv", fakeRegistrableDomain
            ),
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertNull(headerValue(headers, "Authorization"))
        assertNull(headerValue(headers, "Cookie"))
        assertNull(headerValue(headers, "Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", headerValue(headers, "Referer"))
        assertEquals("https://cdn.example.com", headerValue(headers, "Origin"))
        assertEquals("NuvioPlayer/1.0", headerValue(headers, "User-Agent"))
    }

    @Test
    fun explicitHeaders_overrideStreamHeaders_andHopByHopStillFiltered() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, sendCredentials = true,
            explicitHeaders = mapOf(
                "Authorization" to "addon-token",
                "X-Addon" to "1",
                "Range" to "bytes=0-50"
            ),
            defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("addon-token", headerValue(headers, "Authorization"))
        assertEquals("1", headerValue(headers, "X-Addon"))
        assertNull(headerValue(headers, "Range"))
    }

    @Test
    fun userAgent_defaultsOnlyWhenAbsent() {
        val noUa = streamHeaders - "User-Agent"
        val withDefault = buildSubtitleRequestHeaders(
            noUa, sendCredentials = true,
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals(defaultUa, headerValue(withDefault, "User-Agent"))

        val withStreamUa = buildSubtitleRequestHeaders(
            streamHeaders, sendCredentials = true,
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("NuvioPlayer/1.0", headerValue(withStreamUa, "User-Agent"))
    }

    @Test
    fun accept_defaultAddedOnlyWhenStreamHasNone() {
        assertEquals(defaultAccept, headerValue(buildSubtitleRequestHeaders(
            streamHeaders, sendCredentials = true,
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        ), "Accept"))
        // Stream already carries an Accept → preserved untouched.
        val withAccept = buildSubtitleRequestHeaders(
            streamHeaders + ("Accept" to "*/*"), sendCredentials = true,
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("*/*", headerValue(withAccept, "Accept"))
    }

    @Test
    fun explicitHeaders_overrideStreamHeaders_caseInsensitively() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders, sendCredentials = true,
            explicitHeaders = mapOf("user-agent" to "AddonUA/2.0"),
            defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertEquals("AddonUA/2.0", headerValue(headers, "User-Agent"))
        assertEquals(1, headers.keys.count { it.equals("user-agent", ignoreCase = true) })
    }

    @Test
    fun sharedMultiPartSuffix_dropsAllCredentials() {
        val headers = buildSubtitleRequestHeaders(
            streamHeaders,
            sendCredentials = shouldSendStreamCredentials(
                "https://subs.b.co.uk/a.srt", "https://cdn.a.co.uk/v.mkv", fakeRegistrableDomain
            ),
            explicitHeaders = null, defaultUserAgent = defaultUa, defaultAccept = defaultAccept
        )
        assertNull(headerValue(headers, "Authorization"))
        assertNull(headerValue(headers, "Cookie"))
        assertNull(headerValue(headers, "Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", headerValue(headers, "Referer"))
    }

    @Test
    fun sharesRegistrableDomain_edges() {
        fun same(a: String?, b: String?) = sharesRegistrableDomain(a, b, fakeRegistrableDomain)
        assertTrue(same("a.example.com", "example.com"))
        assertTrue(same("cdn.example.com", "sub.other.example.com"))
        assertTrue(same("x.a.co.uk", "a.co.uk"))
        assertFalse(same("a.co.uk", "b.co.uk"))
        assertFalse(same(null, "example.com"))
        assertFalse(same("", "example.com"))
        assertFalse(same("example.org", "example.com"))
        assertFalse(same("com", "example.com"))
        assertFalse(same("co.uk", "a.co.uk"))
        // IP literals: exact match only.
        assertTrue(same("192.168.1.10", "192.168.1.10"))
        assertFalse(same("192.168.1.10", "192.168.1.11"))
    }

    @Test
    fun shouldSendStreamCredentials_parsesLikeTheRequest() {
        fun send(sub: String?, stream: String?) = shouldSendStreamCredentials(sub, stream, fakeRegistrableDomain)
        assertTrue(send("https://cdn.example.com/a.srt", "https://cdn.example.com/v.mkv"))
        assertTrue(send("https://CDN.Example.com:8443/a.srt", "https://cdn.example.com/v.mkv"))
        // Dotless i (U+0131) folds to "I" under case-insensitive compare but is a different
        // punycode host to OkHttp.
        assertFalse(send("https://debr\u0131d.com/a.srt", "https://debrid.com/v.mkv"))
        // https stream, http subtitle: no credentials in cleartext.
        assertFalse(send("http://cdn.example.com/a.srt", "https://cdn.example.com/v.mkv"))
        assertTrue(send("https://cdn.example.com/a.srt", "http://cdn.example.com/v.mkv"))
        // IPv6 literals: exact only.
        assertTrue(send("http://[::1]:8080/a.srt", "http://[0:0::1]/v.mkv"))
        assertFalse(send("http://[::2]/a.srt", "http://[::1]/v.mkv"))
        // Non-http(s) or unparseable: never.
        assertFalse(send("file:///sdcard/a.srt", "https://cdn.example.com/v.mkv"))
        assertFalse(send("https://cdn.example.com/a.srt", null))
        assertFalse(send(null, "https://cdn.example.com/v.mkv"))
    }

    @Test
    fun sharesRegistrableDomain_failsClosed() {
        val pslUnavailable: (String) -> String? = { throw IllegalStateException("Unable to load") }
        val badHost: (String) -> String? = { throw IllegalArgumentException("unexpected host") }
        assertFalse(sharesRegistrableDomain("a.example.com", "example.com", pslUnavailable))
        assertFalse(sharesRegistrableDomain("a.example.com", "example.com", badHost))
        // Identical hosts never consult the list.
        assertTrue(sharesRegistrableDomain("cdn.example.com", "cdn.example.com", pslUnavailable))
    }

    @Test
    fun credentialFreeSubtitleUrl_routesOnlyForeignSubtitlesOfThisItem() {
        val stream = "https://cdn.example.com/v.mkv"
        val foreign = "https://dl.opensubtitles.org/a.srt?lang=en&nuvio_type=subtitle"
        val sameSite = "https://subs.example.com/a.srt?nuvio_type=subtitle"
        val forged = "https://evil.example/s.ass?nuvio_type=x&nuvio_type=subtitle"
        val bare = "https://evil.example/s.ass?nuvio_type=subtitle"
        val subtitles = setOf(foreign, sameSite, forged, bare)
        fun route(uri: String) = credentialFreeSubtitleUrl(uri, stream, subtitles, fakeRegistrableDomain)
        assertEquals("https://dl.opensubtitles.org/a.srt?lang=en", route(foreign))
        // An addon's own nuvio_type cannot hide a subtitle from the route, and survives it.
        assertEquals("https://evil.example/s.ass?nuvio_type=x", route(forged))
        assertEquals("https://evil.example/s.ass", route(bare))
        // Same site keeps the stream's source (and its credentials).
        assertNull(route(sameSite))
        // Not one of the item's subtitles (the stream, its segments): never rerouted.
        assertNull(route("https://other.org/v.mkv?nuvio_type=subtitle"))
    }

    @Test
    fun mpvSubtitleFallbackUrl_isTheCanonicalUrlJudged() {
        val stream = "https://cdn.example.com/v.mkv"
        fun fallback(url: String) = mpvSubtitleFallbackUrl(url, stream, fakeRegistrableDomain)
        assertEquals("https://cdn.example.com/a.srt", fallback("https://cdn.example.com/a.srt"))
        // OkHttp ends the authority at '\'; ffmpeg would connect to evil.example. mpv gets
        // OkHttp's form, whose host is the one judged.
        assertEquals(
            "https://cdn.example.com/@evil.example/a.srt",
            fallback("https://cdn.example.com\\@evil.example/a.srt")
        )
        assertNull(fallback("https://dl.opensubtitles.org/a.srt"))
    }
}
