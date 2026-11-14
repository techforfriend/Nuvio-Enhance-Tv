package com.nuvio.tv.livetv.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    // Shape of an iptv-org playlist: tvg-id with an @feed suffix, no tvg-name, EXTVLCOPT headers.
    private val iptvOrgStyle = """
        #EXTM3U
        #EXTINF:-1 tvg-id="DWEnglish.de@SD" tvg-logo="https://i.imgur.com/A1xzjOI.png" group-title="News",DW English (1080p)
        https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8
        #EXTINF:-1 tvg-id="StarJalsha.in@SD" tvg-logo="https://example.org/jalsha.png" group-title="Entertainment;General",Star Jalsha
        #EXTVLCOPT:http-referrer=https://example.org/
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 11)
        http://example.org/live/jalsha/index.m3u8
    """.trimIndent()

    @Test
    fun `iptv-org style entries keep id, logo, groups, title and headers`() {
        val p = M3uParser.parse(iptvOrgStyle)
        assertEquals(2, p.entries.size)
        val dw = p.entries[0]
        assertEquals("DW English (1080p)", dw.title)
        assertEquals("DWEnglish.de@SD", dw.tvgId)
        assertNull(dw.tvgName)
        assertEquals(listOf("News"), dw.groups)
        assertEquals("https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", dw.url)
        assertTrue(dw.headers.isEmpty())
        val jalsha = p.entries[1]
        assertEquals(listOf("Entertainment", "General"), jalsha.groups)
        assertEquals(
            mapOf("Referer" to "https://example.org/", "User-Agent" to "Mozilla/5.0 (Linux; Android 11)"),
            jalsha.headers
        )
    }

    @Test
    fun `header EPG urls from x-tvg-url and url-tvg, comma lists split, duplicates dropped`() {
        val p = M3uParser.parse(
            "#EXTM3U x-tvg-url=\"https://a.example/epg.xml.gz,https://b.example/epg.xml\" url-tvg=\"https://a.example/epg.xml.gz\"\n"
        )
        assertEquals(listOf("https://a.example/epg.xml.gz", "https://b.example/epg.xml"), p.epgUrls)
    }

    @Test
    fun `BOM, CRLF, unquoted values, missing header and comma in a quoted value`() {
        val text = "\uFEFF#EXTINF:-1 tvg-id=Kolkata.in tvg-chno=105 group-title=\"News, Bengali\",Kolkata TV\r\n" +
            "https://example.org/kolkata.m3u8\r\n"
        val e = M3uParser.parse(text).entries.single()
        assertEquals("Kolkata TV", e.title)
        assertEquals("Kolkata.in", e.tvgId)
        assertEquals("105", e.channelNumber)
        assertEquals(listOf("News, Bengali"), e.groups)
        assertEquals("https://example.org/kolkata.m3u8", e.url)
    }

    @Test
    fun `title falls back to tvg-name, then host and last path segment`() {
        val p = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"France 24\",\nhttps://example.org/f24.m3u8\nhttps://example.org/bare.m3u8\n"
        )
        assertEquals("France 24", p.entries[0].title)
        assertEquals("example.org · bare.m3u8", p.entries[1].title)
    }

    @Test
    fun `an untitled Xtream item is named host and id, never its account`() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,\n${XtreamFixtures.LIVE_BARE}\n${XtreamFixtures.LIVE_M3U8}|User-Agent=x\n")
        assertEquals(listOf("xt.example · 1003", "xt.example · 1002.m3u8"), p.entries.map { it.title })
        assertEquals(XtreamFixtures.LIVE_BARE, p.entries[0].url)
    }

    @Test
    fun `fallback title drops user info, query and fragment`() {
        assertEquals("h.example · 7", M3uParser.fallbackTitle("http://me:pw@h.example:8080/live/7?token=s#f"))
        assertEquals("h.example · stream", M3uParser.fallbackTitle("rtmp://me:pw@h.example/app/stream"))
        assertEquals("h.example", M3uParser.fallbackTitle("http://me:pw@h.example/"))
        assertEquals("239.0.0.1", M3uParser.fallbackTitle("udp://239.0.0.1:1234"))
        assertEquals("239.0.0.1", M3uParser.fallbackTitle("udp://@239.0.0.1:1234"))
        assertEquals("=", M3uParser.fallbackTitle("="))
        // Q7 red team r1 (b19d76cf): no path but a '/' in the query or fragment; a trailing slash after the password.
        assertEquals("h.example", M3uParser.fallbackTitle("http://me:pw@h.example?user=u&pass=a/b"))
        assertEquals("h.example", M3uParser.fallbackTitle("http://h.example#tok/secret"))
        assertEquals("xt.example", M3uParser.fallbackTitle("http://xt.example/user1/pass1/"))
        // Hosts no URL parser accepts still lose their user info, never fall back to the whole URL.
        assertEquals("bad_host name · s", M3uParser.fallbackTitle("rtsp://me:pw@bad_host name/app/s?token=t"))
        assertEquals("h.example · [DEVICE_ID]", M3uParser.fallbackTitle("http://me:pw@h.example/live/[DEVICE_ID]"))
        assertEquals("[::1] · 7", M3uParser.fallbackTitle("http://[::1]:8080/live/7"))
        assertEquals("1003", M3uParser.fallbackTitle("user1/pass1/1003"))
        assertEquals("x", M3uParser.fallbackTitle("me:pw@x"))
        // Q7 red team r2 (00c4f735): backslashes, ';' params, hostless with no last segment, '@' in the path.
        assertEquals("xt.example · 1003", M3uParser.fallbackTitle("http://me:pw@xt.example\\user1\\pass1\\1003"))
        assertEquals("h · 1003", M3uParser.fallbackTitle("http://h/live/u/p/1003;jsessionid=SECRET"))
        assertEquals("-", M3uParser.fallbackTitle("http:///user/pass/"))
        assertEquals("-", M3uParser.fallbackTitle("user/pass/"))
        assertEquals("-", M3uParser.fallbackTitle("http://me:pw@"))
        assertEquals("h · 7", M3uParser.fallbackTitle("http://h/live/a@b/7"))
    }

    @Test
    fun `EXTGRP is used when group-title is absent`() {
        val e = M3uParser.parse("#EXTINF:-1,NHK World\n#EXTGRP:Japan\nhttps://example.org/nhk.m3u8\n").entries.single()
        assertEquals(listOf("Japan"), e.groups)
    }

    @Test
    fun `pipe suffix headers are split off the url and decoded`() {
        val e = M3uParser.parse(
            "#EXTINF:-1,Ch\nhttps://example.org/a.m3u8|User-Agent=VLC%2F3.0&referer=https%3A%2F%2Fsite.example%2F\n"
        ).entries.single()
        assertEquals("https://example.org/a.m3u8", e.url)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://site.example/"), e.headers)
    }

    @Test
    fun `KODIPROP binds to the next item, marks DRM and adds stream headers`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.stream_headers=Origin=https%3A%2F%2Fo.example
            #EXTINF:-1,Locked
            https://example.org/locked.mpd
            #EXTINF:-1,Open
            https://example.org/open.m3u8
            """.trimIndent()
        )
        assertTrue(p.entries[0].drm)
        assertEquals(mapOf("Origin" to "https://o.example"), p.entries[0].headers)
        assertFalse(p.entries[1].drm)
        assertTrue(p.entries[1].headers.isEmpty())
    }

    @Test
    fun `unknown directives are ignored, unmapped attributes kept, placeholders kept`() {
        val e = M3uParser.parse(
            "#EXTM3U\n#EXT-X-SOMETHING:1\n#EXTINF:-1 catchup=\"default\" foo=bar,Ch\n#PLAYLIST:x\nhttps://example.org/live.m3u8?device=[DEVICE_ID]\n"
        ).entries.single()
        assertEquals("https://example.org/live.m3u8?device=[DEVICE_ID]", e.url)
        assertEquals("Ch", e.title)
        assertEquals(mapOf("catchup" to "default", "foo" to "bar"), e.attrs)
        assertTrue(e.tags.isEmpty())
        assertTrue(e.kodiProps.isEmpty())
    }

    @Test
    fun `entry cap stops parsing`() {
        val text = buildString {
            repeat(10) { append("#EXTINF:-1,C$it\nhttps://example.org/$it.m3u8\n") }
        }
        assertEquals(3, M3uParser.parse(text, maxEntries = 3).entries.size)
    }

    @Test
    fun `Kodi 21 drm keys, license_key and manifest_headers`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1,Legacy
            #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|https://lic.example
            https://example.org/a.mpd
            #EXTINF:-1,Json
            #KODIPROP:inputstream.adaptive.drm={"com.widevine.alpha":{}}
            https://example.org/b.mpd
            #EXTINF:-1,Key
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example
            #KODIPROP:inputstream.adaptive.manifest_headers=Referer=https%3A%2F%2Fr.example%2F
            https://example.org/c.mpd
            """.trimIndent()
        )
        // license_key alone is HLS AES-128 key settings, not a licence (Spec 74 S1 bug fix).
        assertEquals(listOf(true, true, false), p.entries.map { it.drm })
        assertEquals(mapOf("Referer" to "https://r.example/"), p.entries[2].headers)
        assertEquals("https://lic.example", p.entries[2].kodiProps["inputstream.adaptive.license_key"])
    }

    @Test
    fun `an EXTINF without a URL does not leak its directives into the next item`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1 group-title="Lost",Orphan
            #EXTVLCOPT:http-referer=https://orphan.example/
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #EXTGRP:OrphanGroup
            #EXTINF:-1,Real
            https://example.org/real.m3u8
            """.trimIndent()
        )
        val e = p.entries.single()
        assertEquals("Real", e.title)
        assertTrue(e.headers.isEmpty())
        assertFalse(e.drm)
        assertTrue(e.groups.isEmpty())
    }

    @Test
    fun `EXTVLCOPT before EXTINF binds to that item`() {
        val e = M3uParser.parse(
            "#EXTVLCOPT:http-user-agent=UA/1\n#EXTVLCOPT:http-referer=https://r.example/\n#EXTINF:-1,Ch\nhttps://example.org/ch.m3u8\n"
        ).entries.single()
        assertEquals(mapOf("User-Agent" to "UA/1", "Referer" to "https://r.example/"), e.headers)
    }

    @Test
    fun `header EPG urls are capped`() {
        val urls = (1..M3uParser.MAX_EPG_URLS + 3).joinToString(",") { "https://e$it.example/epg.xml" }
        assertEquals(M3uParser.MAX_EPG_URLS, M3uParser.parse("#EXTM3U x-tvg-url=\"$urls\"\n").epgUrls.size)
    }

    @Test
    fun `real iptv-org ben playlist, trimmed`() {
        val p = javaClass.getResourceAsStream("/livetv/iptv-org-ben-trimmed.m3u")!!
            .bufferedReader().use { M3uParser.parse(it) }
        assertEquals(12, p.entries.size)
        assertTrue(p.epgUrls.isEmpty()) // iptv-org playlists carry no x-tvg-url (Spec 34 D5)
        val first = p.entries.first()
        assertEquals("Aakaash Aath (576p)", first.title)
        assertEquals("AakaashAath.in@SD", first.tvgId)
        assertEquals(listOf("Entertainment"), first.groups)
        assertEquals("http://tvsen5.aynascope.net/Wm9Lv2RjZGT6/index.m3u8", first.url)
        assertTrue(p.entries.all { it.tvgName == null && it.url.startsWith("http") && !it.drm })
    }

    @Test
    fun `old KODIPROP licence form is kept verbatim, keys lower-cased, last line wins`() {
        val e = M3uParser.parse(
            """
            #EXTINF:-1,Wv
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:InputStream.Adaptive.License_Type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://old.example/lic
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example/wv?t=a=b|Content-Type=application%2Foctet-stream|R{SSM}|
            #KODIPROP:inputstream.adaptive.server_certificate=Q2VydA==
            https://example.org/wv.mpd
            """.trimIndent()
        ).entries.single()
        assertTrue(e.drm)
        assertEquals(
            mapOf(
                "inputstream" to "inputstream.adaptive",
                "inputstream.adaptive.manifest_type" to "mpd",
                "inputstream.adaptive.license_type" to "com.widevine.alpha",
                "inputstream.adaptive.license_key" to "https://lic.example/wv?t=a=b|Content-Type=application%2Foctet-stream|R{SSM}|",
                "inputstream.adaptive.server_certificate" to "Q2VydA=="
            ),
            e.kodiProps
        )
        assertTrue(e.headers.isEmpty())
    }

    @Test
    fun `DRM means a key system other than none`() {
        fun drm(vararg props: String) = M3uParser.parse(
            props.joinToString("") { "#KODIPROP:inputstream.adaptive.$it\n" } + "#EXTINF:-1,C\nhttps://example.org/c.mpd\n"
        ).entries.single().drm
        assertTrue(drm("license_type=clearkey")) // OTT Navigator spelling
        assertTrue(drm("license_type=org.w3.clearkey", "license_key=0123:4567"))
        assertFalse(drm("license_type=none"))
        assertFalse(drm("license_type=NONE", "license_key=|User-Agent=x||"))
        assertFalse(drm("license_key=https://keys.example/k"))
        assertFalse(drm("license_type="))
        assertTrue(drm("drm_legacy=org.w3.clearkey|0123:4567"))
        assertTrue(drm("drm_legacy=com.widevine.alpha"))
        assertFalse(drm("drm_legacy=none|https://keys.example/k"))
        assertFalse(drm("drm_legacy=|x"))
        assertTrue(drm("drm={\"com.widevine.alpha\":{\"license\":{\"server_url\":\"https://lic.example\"}}}"))
        assertTrue(drm("drm={\"none\":{},\"org.w3.clearkey\":{}}"))
        assertFalse(drm("drm={\"none\":{\"unwrapper\":\"base64\"}}"))
        assertFalse(drm("drm={}"))
        assertFalse(drm("drm=not json"))
        assertFalse(drm("drm=[\"com.widevine.alpha\"]"))
        assertTrue(drm("license_type=none", "drm_legacy=com.widevine.alpha|https://lic.example"))
    }

    @Test
    fun `an unreadable drm value is kept as written`() {
        val deep = "{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        val e = M3uParser.parse("#KODIPROP:inputstream.adaptive.drm=$deep\n#EXTINF:-1,C\nhttps://example.org/c.mpd\n").entries.single()
        assertFalse(e.drm)
        assertEquals(deep, e.kodiProps["inputstream.adaptive.drm"])
    }

    @Test
    fun `EXTHTTP JSON headers apply, a bad line is kept as a tag`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1,Good
            #EXTHTTP:{"cookie":"a=1","user-agent":"UA/2","":"x","Empty":""}
            https://example.org/good.m3u8
            #EXTINF:-1,Bad
            #EXTHTTP:cookie=a
            https://example.org/bad.m3u8
            #EXTINF:-1,Mixed
            #EXTHTTP:{"Cookie":"b=2","X-Retry":3}
            https://example.org/mixed.m3u8
            #EXTINF:-1,Nested
            #EXTHTTP:{"Cookie":{"a":"b"}}
            https://example.org/nested.m3u8
            """.trimIndent()
        )
        val (good, bad, mixed, nested) = p.entries
        assertEquals(mapOf("cookie" to "a=1", "User-Agent" to "UA/2"), good.headers)
        assertEquals(mapOf("exthttp" to """{"cookie":"a=1","user-agent":"UA/2","":"x","Empty":""}"""), good.tags)
        assertTrue(bad.headers.isEmpty())
        assertEquals(mapOf("exthttp" to "cookie=a"), bad.tags)
        assertEquals(mapOf("Cookie" to "b=2"), mixed.headers)
        assertEquals(mapOf("exthttp" to """{"Cookie":"b=2","X-Retry":3}"""), mixed.tags)
        assertTrue(nested.headers.isEmpty())
        assertEquals(mapOf("exthttp" to """{"Cookie":{"a":"b"}}"""), nested.tags)
    }

    @Test
    fun `EXTVLCOPT http-origin is a header, other options are kept as vlc tags`() {
        val e = M3uParser.parse(
            "#EXTINF:-1,C\n#EXTVLCOPT:http-origin=https://o.example\n#EXTVLCOPT:network-caching=1000\n" +
                "#EXTVLCOPT:no-video\n#EXTVLCOPT:http-user-agent=\nhttps://example.org/c.m3u8\n"
        ).entries.single()
        assertEquals(mapOf("Origin" to "https://o.example"), e.headers)
        assertEquals(mapOf("vlc:network-caching" to "1000", "vlc:no-video" to ""), e.tags)
    }

    @Test
    fun `KODIPROP common_headers apply`() {
        val e = M3uParser.parse(
            "#KODIPROP:inputstream.adaptive.common_headers=User-Agent=UA%2F1&Cookie=c%3D1\n#EXTINF:-1,C\nhttps://example.org/c.mpd\n"
        ).entries.single()
        assertEquals(mapOf("User-Agent" to "UA/1", "Cookie" to "c=1"), e.headers)
        assertEquals("User-Agent=UA%2F1&Cookie=c%3D1", e.kodiProps["inputstream.adaptive.common_headers"])
    }

    @Test
    fun `header sources merge in a fixed order whatever the line order`() {
        // Later wins: EXTVLCOPT, EXTINF attribute, EXTHTTP, common_headers, stream/manifest headers, | suffix.
        val e = M3uParser.parse(
            """
            #KODIPROP:inputstream.adaptive.stream_headers=A5=stream&A6=stream
            #KODIPROP:inputstream.adaptive.common_headers=A4=common&A5=common&A6=common
            #EXTHTTP:{"A3":"http","A4":"http","A5":"http","A6":"http"}
            #EXTINF:-1 http-origin="attr" http-user-agent="UA attr",C
            #EXTVLCOPT:http-origin=vlc
            #EXTVLCOPT:http-user-agent=UA vlc
            #EXTVLCOPT:http-referrer=https://vlc.example/
            https://example.org/c.mpd|A6=pipe
            """.trimIndent()
        ).entries.single()
        assertEquals(
            mapOf(
                "Origin" to "attr", "User-Agent" to "UA attr", "Referer" to "https://vlc.example/",
                "A3" to "http", "A4" to "common", "A5" to "stream", "A6" to "pipe"
            ),
            e.headers
        )
        assertTrue(e.attrs.isEmpty()) // header attributes go to headers only
    }

    @Test
    fun `one header set by every source resolves by the merge order, names compared case-insensitively`() {
        val sources = listOf(
            "#EXTVLCOPT:http-origin=vlc\n",
            "#EXTINF:-1 http-origin=\"attr\",C\n",
            "#EXTHTTP:{\"origin\":\"http\"}\n",
            "#KODIPROP:inputstream.adaptive.common_headers=ORIGIN=common\n",
            "#KODIPROP:inputstream.adaptive.stream_headers=oRiGiN=stream\n"
        )
        val expected = listOf("vlc", "attr", "http", "common", "stream", "pipe")
        for (n in 1..sources.size + 1) {
            // Lines in reverse merge order, so file order cannot be what decides; the last step adds the | suffix.
            val text = sources.take(n).reversed().joinToString("") +
                (if (n == 1) "#EXTINF:-1,C\n" else "") +
                "https://example.org/c.mpd" + (if (n > sources.size) "|origin=pipe" else "") + "\n"
            val headers = M3uParser.parse(text).entries.single().headers
            assertEquals(text, listOf(expected[n - 1]), headers.filterKeys { it.equals("origin", ignoreCase = true) }.values.toList())
        }
    }

    @Test
    fun `a header name no source canonicalises merges case-insensitively, the winner's spelling kept`() {
        val e = M3uParser.parse(
            """
            #EXTHTTP:{"cookie":"http","x-token":"http"}
            #KODIPROP:inputstream.adaptive.common_headers=COOKIE=common
            #EXTINF:-1,C
            https://example.org/c.mpd|Cookie=pipe
            """.trimIndent()
        ).entries.single()
        assertEquals(mapOf("Cookie" to "pipe", "x-token" to "http"), e.headers)
    }

    @Test
    fun `a repeated KODIPROP header line replaces the earlier one, like its prop`() {
        val e = M3uParser.parse(
            """
            #KODIPROP:inputstream.adaptive.stream_headers=A=1&B=1
            #KODIPROP:inputstream.adaptive.common_headers=C=1
            #KODIPROP:inputstream.adaptive.stream_headers=A=2
            #KODIPROP:inputstream.adaptive.common_headers=D=2
            #EXTINF:-1,C
            https://example.org/c.mpd
            """.trimIndent()
        ).entries.single()
        assertEquals(mapOf("D" to "2", "A" to "2"), e.headers)
        assertEquals("A=2", e.kodiProps["inputstream.adaptive.stream_headers"])
    }

    @Test
    fun `the last line wins across EXTHTTP spellings and between stream and manifest headers`() {
        val e = M3uParser.parse(
            """
            #EXTHTTP:{"cookie":"a"}
            #EXTHTTP:{"Cookie":"b"}
            #EXTHTTP:{"cookie":"c"}
            #KODIPROP:inputstream.adaptive.stream_headers=X=s1
            #KODIPROP:inputstream.adaptive.manifest_headers=X=m1
            #KODIPROP:inputstream.adaptive.stream_headers=X=s2
            #EXTINF:-1,C
            https://example.org/c.mpd
            """.trimIndent()
        ).entries.single()
        assertEquals(mapOf("cookie" to "c", "X" to "s2"), e.headers)
    }

    @Test
    fun `empty keys and unreadable EXTHTTP lines are handled`() {
        val e = M3uParser.parse(
            "#EXTINF:-1,C\n#EXTVLCOPT:=x\n#KODIPROP:=x\n#KODIPROP:  \n#EXTHTTP:\nhttps://example.org/c.m3u8\n"
        ).entries.single()
        assertTrue(e.kodiProps.isEmpty())
        assertEquals(mapOf("exthttp" to ""), e.tags)
        val nul = M3uParser.parse("#EXTINF:-1,C\n#EXTHTTP:{\"Cookie\":\"a\u0000b\"}\nhttps://example.org/c.m3u8\n").entries.single()
        assertTrue(nul.headers.isEmpty())
        assertEquals(mapOf("exthttp" to "{\"Cookie\":\"a\u0000b\"}"), nul.tags)
    }

    @Test(timeout = 20_000)
    fun `a flooded item parses in linear time`() {
        // 50k: a per-line map copy (the r1 quadratic bug) still blows the timeout, and the flood fits the
        // default 512 MB test heap alongside the full suite (200k ran out of memory there).
        val n = 50_000
        val text = buildString {
            append("#EXTINF:-1,C\n")
            repeat(n) {
                append("#KODIPROP:k$it=v\n#EXTVLCOPT:o$it=v\n#EXTHTTP:{\"H$it\":\"v\"}\n")
                append("#EXTVLCOPT:http-user-agent=UA$it\n#KODIPROP:inputstream.adaptive.stream_headers=S=$it\n")
            }
            append("https://example.org/c.mpd\n")
        }
        val e = M3uParser.parse(text).entries.single()
        assertEquals(n + 1, e.kodiProps.size) // + stream_headers itself, last line wins
        assertEquals(n, e.tags.size)
        assertEquals(n + 2, e.headers.size) // H*, User-Agent, S
        assertEquals("UA${n - 1}", e.headers["User-Agent"])
        assertEquals("${n - 1}", e.headers["S"])
    }

    @Test
    fun `catchup attributes stay on the entry, EXTM3U attributes on the playlist`() {
        val p = M3uParser.parse(
            """
            #EXTM3U url-tvg="https://e.example/epg.xml" catchup="shift" catchup-days="3" tvg-shift="-1"
            #EXTINF:-1 tvg-id="a" catchup="default" catchup-source="https://a.example/{utc}-{lutc}" catchup-days="7" catchup-correction="0.5" timeshift="2" radio="true",A
            https://example.org/a.m3u8
            #EXTM3U x-tvg-url="https://f.example/epg.xml" tvg-shift="+2"
            """.trimIndent()
        )
        assertEquals(listOf("https://e.example/epg.xml", "https://f.example/epg.xml"), p.epgUrls)
        assertEquals(mapOf("catchup" to "shift", "catchup-days" to "3", "tvg-shift" to "+2"), p.attrs)
        assertEquals(
            mapOf(
                "catchup" to "default", "catchup-source" to "https://a.example/{utc}-{lutc}", "catchup-days" to "7",
                "catchup-correction" to "0.5", "timeshift" to "2", "radio" to "true"
            ),
            p.entries.single().attrs
        )
    }

    @Test
    fun `Xtream m3u_plus keeps every live URL form with its catchup attributes and skips movies and series`() {
        val p = M3uParser.parse(XtreamFixtures.M3U_PLUS)
        assertEquals(listOf(XtreamFixtures.GUIDE), p.epgUrls)
        assertEquals(
            listOf(XtreamFixtures.LIVE_TS, XtreamFixtures.LIVE_M3U8, XtreamFixtures.LIVE_BARE, XtreamFixtures.LIVE_BARE_LIVE_PREFIX),
            p.entries.map { it.url }
        )
        val bbc = p.entries[0]
        assertEquals("bbc1.uk", bbc.tvgId)
        assertEquals(listOf("UK | General"), bbc.groups)
        assertEquals(mapOf("catchup" to "xc", "catchup-days" to "7"), bbc.attrs)
        assertFalse(bbc.drm)
        assertEquals(mapOf("catchup" to "xc", "catchup-days" to "3"), p.entries[1].attrs)
        assertTrue(p.entries.drop(2).all { it.attrs.isEmpty() })
        assertNull(p.entries[3].tvgId)
    }

    @Test
    fun `skipped Xtream VOD items do not count toward the entry cap and drop their directives`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Movie
            #EXTVLCOPT:http-user-agent=Movie/1.0
            ${XtreamFixtures.MOVIE}|Referer=https://movie.example/
            #EXTINF:-1,Episode
            ${XtreamFixtures.SERIES}
            #EXTINF:-1,Live
            ${XtreamFixtures.LIVE_TS}
            """.trimIndent(),
            maxEntries = 1
        )
        val live = p.entries.single()
        assertEquals("Live", live.title)
        assertTrue(live.headers.isEmpty())

        // No #EXTINF between a skipped item and the next URL: only the skip's reset keeps its directives off it.
        val bare = M3uParser.parse(
            """
            #EXTINF:-1,Movie
            #EXTVLCOPT:http-user-agent=Movie/1.0
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            ${XtreamFixtures.MOVIE}
            ${XtreamFixtures.LIVE_TS}
            """.trimIndent()
        ).entries.single()
        assertEquals("xt.example · 1001.ts", bare.title)
        assertTrue(bare.headers.isEmpty())
        assertFalse(bare.drm)
    }

    @Test
    fun `garbage input yields no crash`() {
        val p = M3uParser.parse("#EXTINF\n#EXTINF:-1 tvg-id=\"unterminated,Title\n=\n")
        assertEquals(1, p.entries.size)
        assertEquals("=", p.entries.single().url)
    }
}
