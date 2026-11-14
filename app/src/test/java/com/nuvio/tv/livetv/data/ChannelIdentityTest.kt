package com.nuvio.tv.livetv.data

import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uParser
import com.nuvio.tv.livetv.source.LiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIdentityTest {

    @Test
    fun `tvg-id normalisation drops the feed and case`() {
        assertEquals("starjalsha.in", ChannelIdentity.normalizeTvgId(" StarJalsha.in@SD "))
        assertEquals("starjalsha.in", ChannelIdentity.normalizeTvgId("STARJALSHA.IN"))
        assertNull(ChannelIdentity.normalizeTvgId("  "))
        assertNull(ChannelIdentity.normalizeTvgId("@HD"))
        assertNull(ChannelIdentity.normalizeTvgId(null))
    }

    @Test
    fun `tvg-id keys put the stem joined to its feed before the plain id`() {
        assertEquals(listOf("9gemsydney.au", "9gem.au"), ChannelIdentity.tvgIdKeys("9Gem.au@Sydney"))
        assertEquals(listOf("bbconelondon.uk", "bbcone.uk"), ChannelIdentity.tvgIdKeys(" BBCOne.uk@London "))
        // No feed, no country tag, or a feed that is not plain letters/digits: the plain id only.
        assertEquals(listOf("bbcone.uk"), ChannelIdentity.tvgIdKeys("BBCOne.uk"))
        assertEquals(listOf("bbcone"), ChannelIdentity.tvgIdKeys("BBCOne@HD"))
        assertEquals(listOf("bbcone.uk"), ChannelIdentity.tvgIdKeys("BBCOne.uk@"))
        assertEquals(listOf("bbcone.uk"), ChannelIdentity.tvgIdKeys("BBCOne.uk@East HD"))
        assertEquals(listOf("bbcone.uk"), ChannelIdentity.tvgIdKeys("BBCOne.uk@" + "a".repeat(33)))
        assertEquals(emptyList<String>(), ChannelIdentity.tvgIdKeys("@HD"))
        assertEquals(emptyList<String>(), ChannelIdentity.tvgIdKeys(null))
    }

    @Test
    fun `loose ids keep the country and drop dots, quality words and a digit after the country`() {
        // epgshare01 ids as served 2026-11-11 against iptv-org's.
        assertEquals("bbctwo.uk", ChannelIdentity.looseId("BBC.Two.HD.uk"))
        assertEquals("bbctwo.uk", ChannelIdentity.looseId("BBCTwo.uk"))
        assertEquals("cnn.us", ChannelIdentity.looseId("CNN.HD.us2"))
        assertEquals("hbo.us", ChannelIdentity.looseId("HBO.HD.us2"))
        // A bracketed word is a feed, not noise: it stays in the key.
        assertEquals("hbopacific.us", ChannelIdentity.looseId("HBO.(Pacific).us2"))
        assertEquals("hbopacific.us", ChannelIdentity.looseId("HBO.[Pacific].us2"))
        assertEquals("hbopacific.us", ChannelIdentity.looseId("HBO.{Pacific}.us2"))
        assertEquals("hbopacific.us", ChannelIdentity.looseId("HBO.(Pacific.us2"))
        assertEquals("elevensports1.pl", ChannelIdentity.looseId("Eleven.Sports.1.4K.pl"))
        assertEquals("canalplusdomo.pl", ChannelIdentity.looseId("CANAL+.DOMO.pl"))
        assertEquals("ntv.bd", ChannelIdentity.looseId("NTV.bd@SD"))
        // No country tag, an empty stem, or two digits: no loose id.
        assertNull(ChannelIdentity.looseId("577395"))
        assertNull(ChannelIdentity.looseId("mjh-abc-nsw"))
        assertNull(ChannelIdentity.looseId("HD.uk"))
        assertNull(ChannelIdentity.looseId("CNN.us22"))
        assertNull(ChannelIdentity.looseId(null))
    }

    @Test
    fun `the country suffix strip also takes a digit after the country`() {
        assertEquals("CNN.HD", ChannelIdentity.stripCountrySuffix("CNN.HD.us2"))
        assertEquals("Star Jalsha HD", ChannelIdentity.stripCountrySuffix("Star Jalsha HD.in"))
        assertEquals("Sport.tv22", ChannelIdentity.stripCountrySuffix("Sport.tv22"))
    }

    @Test
    fun `Pluto ids come from jmp2 uk redirector URLs only, lower case, deduplicated`() {
        val ch = Channel(
            key = "k", sourceId = "s", name = "n",
            streams = listOf(
                "https://jmp2.uk/plu-62BA60F059624E000781C436.m3u8",
                "http://jmp2.uk/plu-62ba60f059624e000781c436",
                "https://jmp2.uk/plu-5a6b92f6e22a617379789618?x=1",
                "https://jmp2.uk/stvp-5a6b92f6e22a617379789618.m3u8",
                "https://jmp2.uk/plu-62ba60f059624e000781c4361.m3u8",
                "https://evil.example/jmp2.uk/plu-62ba60f059624e000781c436.m3u8"
            ).map { LiveStream(it) }
        )
        assertEquals(listOf("62ba60f059624e000781c436", "5a6b92f6e22a617379789618"), ChannelIdentity.providerIds(ch))
        assertEquals(setOf(GuideService.PLUTO), ChannelIdentity.services(ch))
    }

    @Test
    fun `Plex and Samsung TV Plus ids come from their jmp2 uk shapes only`() {
        val plex = "6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d743"
        // Real pool URLs (channels 0957965), then near misses that must not count.
        val good = mapOf(
            "https://jmp2.uk/plex-$plex.m3u8" to (GuideService.PLEX to plex),
            "https://jmp2.uk/plex-${plex.uppercase()}" to (GuideService.PLEX to plex),
            "https://jmp2.uk/stvp-IN4000600Q" to (GuideService.SAMSUNG to "in4000600q"),
            "https://jmp2.uk/sam-USBA300024TN.m3u8" to (GuideService.SAMSUNG to "usba300024tn"),
            "http://jmp2.uk/stvp-GB2300002AI?x=1" to (GuideService.SAMSUNG to "gb2300002ai")
        )
        good.forEach { (url, want) -> assertEquals(url, want, ChannelIdentity.providerId(url)) }
        listOf(
            "https://jmp2.uk/plex-6a1610bebdf296985fd95603.m3u8", // one half only
            "https://jmp2.uk/plex-6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d74z.m3u8", // not hex
            "https://jmp2.uk/plex-$plex-x.m3u8", // runs on in the segment
            "https://jmp2.uk/stvp-IN40.m3u8", // too short
            "https://jmp2.uk/stvp-IN4000600Q4000600Q4.m3u8", // too long
            "https://jmp2.uk/stvp-4N4000600Q", // starts with a digit
            "https://jmp2.uk/samx-USBA300024TN.m3u8",
            "https://evil.example/jmp2.uk/stvp-IN4000600Q",
            "https://jmp2.uk.evil.example/stvp-IN4000600Q",
            "https://example.org/a?u=https://jmp2.uk/stvp-IN4000600Q"
        ).forEach { assertNull(it, ChannelIdentity.providerId(it)) }
        val ch = Channel(
            key = "k", sourceId = "s", name = "n",
            streams = listOf("https://jmp2.uk/stvp-IN4000600Q", "https://jmp2.uk/plex-$plex.m3u8").map { LiveStream(it) }
        )
        assertEquals(listOf("in4000600q", plex), ChannelIdentity.providerIds(ch))
        assertEquals(setOf(GuideService.SAMSUNG, GuideService.PLEX), ChannelIdentity.services(ch))
    }

    @Test
    fun `name normalisation follows the measured match rules`() {
        val rows = listOf(
            "Star Jalsha HD" to "starjalsha",
            "STAR JALSHA" to "starjalsha",
            "Aakaash Aath (576p)" to "aakaashaath",
            "R. Bangla" to "rbangla",
            "SONY SPORTS TEN 2 HD" to "sonysportsten2",
            "Travel XP [Geo-blocked] {x}" to "travelxp",
            "AT&T SportsNet" to "atandtsportsnet",
            "Colors+ 4K" to "colorsplus",
            "\uFF2E\uFF34\uFF36" to "ntv", // full-width NTV folds by NFKC
            "স্টার জলসা" to "স্টারজলসা" // Bengali letters and vowel signs kept, space removed
        )
        for ((raw, want) in rows) assertEquals(raw, want, ChannelIdentity.normalizeName(raw))
    }

    @Test
    fun `country comes from an iptv-org style tvg-id only`() {
        assertEquals("in", ChannelIdentity.countryOfTvgId("StarJalsha.in@SD"))
        assertEquals("uk", ChannelIdentity.countryOfTvgId("ATNBanglaUK.uk"))
        assertNull(ChannelIdentity.countryOfTvgId("542998"))
        assertNull(ChannelIdentity.countryOfTvgId("Foo.usa"))
        assertNull(ChannelIdentity.countryOfTvgId(null))
    }

    @Test
    fun `feeds of one tvg-id become one channel with ordered, de-duplicated streams`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="DWEnglish.de@SD" group-title="News",DW English (480p)
            https://example.org/dw-sd.m3u8
            #EXTINF:-1 tvg-id="DWEnglish.de@HD" tvg-name="DW",DW English (1080p)
            https://example.org/dw-hd.m3u8
            #EXTINF:-1 tvg-id="dwenglish.de",DW English again
            https://example.org/dw-sd.m3u8
            #EXTINF:-1 tvg-name="No id",No id
            https://example.org/noid.m3u8|Referer=https%3A%2F%2Fr.example%2F
            """.trimIndent()
        )
        val channels = ChannelIdentity.channelsFromM3u("src1", p)
        assertEquals(2, channels.size)
        val dw = channels[0]
        assertEquals("src1#id:dwenglish.de", dw.key)
        assertEquals("DW English (480p)", dw.name)
        assertEquals("de", dw.country)
        assertEquals(listOf("News"), dw.groups)
        assertTrue(dw.altNames.isEmpty()) // the first item names the channel; it had no tvg-name
        assertEquals(listOf("https://example.org/dw-sd.m3u8", "https://example.org/dw-hd.m3u8"), dw.streams.map { it.url })
        val noId = channels[1]
        assertEquals("src1#url:https://example.org/noid.m3u8", noId.key)
        assertNull(noId.country)
        assertTrue(noId.altNames.isEmpty()) // tvg-name equal to the title is not an extra name
        assertEquals(mapOf("Referer" to "https://r.example/"), noId.streams.single().headers)
    }

    @Test
    fun `keys are scoped by source and stable across parses`() {
        val p = M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in\",A\nhttps://example.org/a.m3u8\n")
        val a1 = ChannelIdentity.channelsFromM3u("s1", p).single().key
        assertEquals(a1, ChannelIdentity.channelsFromM3u("s1", M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in@HD\",A\nhttps://other.example/a.m3u8\n")).single().key)
        assertNotEquals(a1, ChannelIdentity.channelsFromM3u("s2", p).single().key)
    }

    @Test
    fun `tvg-name different from the title becomes an alt name`() {
        val c = ChannelIdentity.channelsFromM3u(
            "s", M3uParser.parse("#EXTINF:-1 tvg-id=\"A.in\" tvg-name=\"Star Jalsha\",SJ (1080p)\nhttps://example.org/a.m3u8\n")
        ).single()
        assertEquals(listOf("Star Jalsha"), c.altNames)
    }

    @Test
    fun `playlist data reaches the channel and its streams, empty maps as null`() {
        val p = M3uParser.parse(
            """
            #EXTINF:-1 tvg-id="A.in" catchup="xc" catchup-days="7",A
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=0123:4567
            #EXTVLCOPT:network-caching=1000
            https://example.org/a.mpd
            #EXTINF:-1 tvg-id="A.in" catchup="default",A HD
            https://example.org/a-hd.m3u8
            #EXTINF:-1,Plain
            https://example.org/plain.m3u8
            """.trimIndent()
        )
        val (a, plain) = ChannelIdentity.channelsFromM3u("s", p)
        assertEquals(mapOf("catchup" to "xc", "catchup-days" to "7"), a.attrs) // the first item's
        val (wv, hd) = a.streams
        assertTrue(wv.drm)
        assertEquals(
            mapOf("inputstream.adaptive.license_type" to "org.w3.clearkey", "inputstream.adaptive.license_key" to "0123:4567"),
            wv.kodiProps
        )
        assertEquals(mapOf("vlc:network-caching" to "1000"), wv.tags)
        assertFalse(hd.drm)
        assertNull(hd.kodiProps)
        assertNull(hd.tags)
        assertNull(plain.attrs)
    }

    @Test
    fun `channel cache round-trips the new fields and reads caches from before them`() {
        val plain = ChannelIdentity.channelsFromM3u("s", M3uParser.parse("#EXTINF:-1,Plain\nhttps://example.org/p.m3u8\n")).single()
        val plainJson = localStoreJson.encodeToString(Channel.serializer(), plain)
        for (absent in listOf("attrs", "kodiProps", "tags")) assertFalse(plainJson, "\"$absent\"" in plainJson)

        val full = plain.copy(
            attrs = mapOf("catchup" to "xc"),
            streams = listOf(LiveStream("https://example.org/p.mpd", drm = true, kodiProps = mapOf("k" to "v"), tags = mapOf("exthttp" to "x")))
        )
        assertEquals(full, localStoreJson.decodeFromString(Channel.serializer(), localStoreJson.encodeToString(Channel.serializer(), full)))

        // A cache written before Spec 74 S1.
        val old = """{"key":"s#url:u","sourceId":"s","name":"Old","streams":[{"url":"u","headers":{"Referer":"r"},"drm":true}]}"""
        val decoded = localStoreJson.decodeFromString(Channel.serializer(), old)
        assertNull(decoded.attrs)
        assertEquals(LiveStream("u", mapOf("Referer" to "r"), drm = true), decoded.streams.single())

        // A sources.json record written before Spec 74 S1, and the new field round-tripping.
        val source = localStoreJson.decodeFromString(
            LiveSource.serializer(), """{"id":"s","kind":"m3u","name":"N","url":"https://x/l.m3u","addedAtMs":1}"""
        )
        assertNull(source.playlistAttrs)
        assertFalse("playlistAttrs" in localStoreJson.encodeToString(LiveSource.serializer(), source))
        val withAttrs = source.copy(playlistAttrs = mapOf("catchup" to "shift"))
        assertEquals(withAttrs, localStoreJson.decodeFromString(LiveSource.serializer(), localStoreJson.encodeToString(LiveSource.serializer(), withAttrs)))
    }

    @Test
    fun `stremio keys keep colliding ids apart by name`() {
        assertNotEquals(
            ChannelIdentity.stremioKey("rtve", "tve:RNE.TV", "Radio Nacional"),
            ChannelIdentity.stremioKey("rtve", "tve:RNE.TV", "Radio 3")
        )
    }

    @Test
    fun `real iptv-org ben playlist gives one channel per tvg-id`() {
        val p = javaClass.getResourceAsStream("/livetv/iptv-org-ben-trimmed.m3u")!!
            .bufferedReader().use { M3uParser.parse(it) }
        val channels = ChannelIdentity.channelsFromM3u("iptv-org-ben", p)
        assertEquals(p.entries.mapNotNull { ChannelIdentity.normalizeTvgId(it.tvgId) }.toSet().size, channels.size)
        assertEquals("in", channels.first().country)
        assertEquals(channels.size, channels.map { it.key }.toSet().size)
    }
}
