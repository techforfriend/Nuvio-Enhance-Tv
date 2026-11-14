package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class EpgMatcherTest {

    private fun channel(
        key: String,
        name: String,
        tvgId: String? = null,
        altNames: List<String> = emptyList(),
        country: String? = ChannelIdentity.countryOfTvgId(tvgId)
    ) =
        Channel(
            key = key,
            sourceId = "s",
            name = name,
            tvgId = tvgId,
            country = country,
            altNames = altNames,
            streams = listOf(LiveStream("https://example.org/$key.m3u8"))
        )

    private fun source(id: String, country: String?, vararg chans: Pair<String, List<String>>, withProgrammes: Set<String> = emptySet()) =
        EpgSource(
            id,
            country,
            EpgSnapshot(
                channels = chans.associate { (cid, names) -> cid to EpgChannel(cid, names, null) },
                programmes = withProgrammes.associateWith { listOf(EpgProgramme(0, 1, "p")) },
                windowStartMs = 0,
                windowEndMs = 1,
                complete = true
            )
        )

    // free-epg.de style: id doubles as a name, several ids per channel.
    private val freeEpgIn = source(
        "free-epg-in", "in",
        "StarJalsha.in" to listOf("Star Jalsha"),
        "NTV.in" to listOf("NTV")
    )

    // epg.pw style: numeric ids, upper-case names.
    private val epgPwIn = source(
        "epgpw-in", "in",
        "542998" to listOf("STAR JALSHA"),
        "404001" to listOf("ZEE BANGLA HD")
    )

    @Test
    fun `normalised tvg-id matches before names, in any country`() {
        // The name alone would pick epg.pw's 542998; the tvg-id step must win first.
        val ch = channel("k1", "Star Jalsha", tvgId = "StarJalsha.in@SD")
        val m = EpgMatcher.match(listOf(ch), listOf(epgPwIn, freeEpgIn), emptyMap())
        assertEquals(EpgRef("free-epg-in", "StarJalsha.in"), m["k1"])
        // A tvg-id hit is not country-gated: an EPG file of another (or no) country still matches.
        val foreign = source("other", null, "StarJalsha.in" to listOf("x"))
        assertEquals(EpgRef("other", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(foreign), emptyMap())["k1"])
    }

    @Test
    fun `a feed-joined id wins over the plain id, in any guide order`() {
        // open-epg keys regional feeds as stem + feed (`BBCOneLondon.uk`), iptv-org as `BBCOne.uk@London`.
        val plain = source("a", "uk", "BBCOne.uk" to listOf("BBC One"))
        val regional = source("b", "uk", "BBCOneLondon.uk" to listOf("BBC One London"))
        val ch = channel("b1", "BBC One London", tvgId = "BBCOne.uk@London")
        assertEquals(EpgRef("b", "BBCOneLondon.uk"), EpgMatcher.match(listOf(ch), listOf(plain, regional), emptyMap())["b1"])
        // Only the plain id there: it still matches.
        assertEquals(EpgRef("a", "BBCOne.uk"), EpgMatcher.match(listOf(ch), listOf(plain), emptyMap())["b1"])
    }

    @Test
    fun `a loose id matches epgshare01 ids in any guide, after the same key's exact id`() {
        // A guide of no country (a user-added URL) never reaches the name step; the loose id carries it.
        val es = source("es", null, "BBC.Two.HD.uk" to listOf("BBC Two HD"), "CNN.HD.us2" to listOf("CNN HD"))
        val two = channel("t", "BBC Two Ⓖ", tvgId = "BBCTwo.uk")
        val cnn = channel("c", "CNN", tvgId = "CNN.us@East")
        val m = EpgMatcher.match(listOf(two, cnn), listOf(es), emptyMap())
        assertEquals(EpgRef("es", "BBC.Two.HD.uk"), m["t"])
        assertEquals(EpgRef("es", "CNN.HD.us2"), m["c"])
        // The same key's exact id in a later guide beats its loose id in an earlier one.
        val exact = source("oe", "uk", "BBCTwo.uk" to listOf("BBC Two"))
        assertEquals(EpgRef("oe", "BBCTwo.uk"), EpgMatcher.match(listOf(two), listOf(es, exact), emptyMap())["t"])
        // A live loose hit beats a stale exact one.
        val liveEs = source("es", null, "BBC.Two.HD.uk" to listOf("BBC Two HD"), withProgrammes = setOf("BBC.Two.HD.uk"))
        assertEquals(EpgRef("es", "BBC.Two.HD.uk"), EpgMatcher.match(listOf(two), listOf(exact, liveEs), emptyMap())["t"])
    }

    @Test
    fun `bracketed feeds keep their own loose ids`() {
        val es = source(
            "es", null,
            "HBO.(Pacific).us2" to listOf("HBO Pacific"),
            "HBO.HD.us2" to listOf("HBO HD"),
            withProgrammes = setOf("HBO.(Pacific).us2", "HBO.HD.us2")
        )
        val east = channel("e", "HBO", tvgId = "HBO.us@East")
        val pacific = channel("p", "HBO", tvgId = "HBO.us@Pacific")
        val m = EpgMatcher.match(listOf(east, pacific), listOf(es), emptyMap())
        assertEquals(EpgRef("es", "HBO.HD.us2"), m["e"])
        assertEquals(EpgRef("es", "HBO.(Pacific).us2"), m["p"])
    }

    @Test
    fun `a feed's loose id beats the plain id's exact hit in an earlier guide`() {
        val oe = source("oe", "uk", "Dave.uk" to listOf("Dave"), withProgrammes = setOf("Dave.uk"))
        val es = source("es", "uk", "Dave.+1.uk" to listOf("Dave +1"), withProgrammes = setOf("Dave.+1.uk"))
        val ch = channel("d", "Dave +1", tvgId = "Dave.uk@Plus1")
        assertEquals(EpgRef("es", "Dave.+1.uk"), EpgMatcher.match(listOf(ch), listOf(oe, es), emptyMap())["d"])
    }

    @Test
    fun `a loose id hit comes before a name hit in an earlier guide`() {
        val byName = source("pw", "uk", "123" to listOf("BBC Two"), withProgrammes = setOf("123"))
        val es = source("es", null, "BBC.Two.HD.uk" to listOf("BBC Two HD"), withProgrammes = setOf("BBC.Two.HD.uk"))
        val ch = channel("t", "BBC Two", tvgId = "BBCTwo.uk")
        assertEquals(EpgRef("es", "BBC.Two.HD.uk"), EpgMatcher.match(listOf(ch), listOf(byName, es), emptyMap())["t"])
    }

    @Test
    fun `a guide id loses its country suffix once, not a second dotted word`() {
        val au = source("au", "au", "ABC.TV.au" to emptyList())
        val m = EpgMatcher.match(
            listOf(channel("a", "ABC", tvgId = "X.au"), channel("b", "ABC TV", tvgId = "Y.au")), listOf(au), emptyMap()
        )
        assertNull(m["a"])
        assertEquals(EpgRef("au", "ABC.TV.au"), m["b"])
    }

    @Test
    fun `a loose id never crosses countries`() {
        val es = source("es", null, "NTV.HD.in" to listOf("NTV HD"))
        assertNull(EpgMatcher.match(listOf(channel("n", "NTV ⓨ", tvgId = "NTV.bd@SD")), listOf(es), emptyMap())["n"])
    }

    @Test
    fun `a digit after a guide id's country leaves the name step its stem`() {
        // epgshare01 CA2 / US2 ids, guide country from the file name.
        val ca2 = source("es", "ca", "Global.Toronto.ca2" to emptyList())
        val ch = channel("g", "Global Toronto", tvgId = "CIIIDT.ca")
        assertEquals(EpgRef("es", "Global.Toronto.ca2"), EpgMatcher.match(listOf(ch), listOf(ca2), emptyMap())["g"])
    }

    @Test
    fun `a stale tvg-id hit does not shadow a live name hit in another source`() {
        // free-epg.de first: its id hit has no programmes; epg.pw's name hit does.
        val stale = source("free-epg-in", "in", "StarJalsha.in" to listOf("Star Jalsha"))
        val live = source("epgpw-in", "in", "542998" to listOf("STAR JALSHA"), withProgrammes = setOf("542998"))
        val ch = channel("j", "Star Jalsha", tvgId = "StarJalsha.in@SD")
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(ch), listOf(stale, live), emptyMap())["j"])
        // With no live hit anywhere, the first hit in chain order still gives a (stale) guide.
        val liveless = source("epgpw-in", "in", "542998" to listOf("STAR JALSHA"))
        assertEquals(EpgRef("free-epg-in", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(stale, liveless), emptyMap())["j"])
    }

    @Test
    fun `country codes compare case-insensitively on both sides`() {
        val upperSource = source("epgpw-in", "IN", "542998" to listOf("STAR JALSHA"))
        val upperChannel = channel("j", "Star Jalsha", country = "IN")
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(upperChannel), listOf(upperSource), emptyMap())["j"])
    }

    @Test
    fun `title wins over alt names, a name normalising to empty is skipped`() {
        val ch = channel("t", "Zee Bangla", tvgId = "X.in", altNames = listOf("Star Jalsha"))
        assertEquals(EpgRef("epgpw-in", "404001"), EpgMatcher.match(listOf(ch), listOf(epgPwIn), emptyMap())["t"])
        val empty = channel("e", "(HD) [x]", tvgId = "Y.in", altNames = listOf("Star Jalsha"))
        assertEquals(EpgRef("epgpw-in", "542998"), EpgMatcher.match(listOf(empty), listOf(epgPwIn), emptyMap())["e"])
    }

    @Test
    fun `Bengali display names match through the index`() {
        val bn = source("bn", "in", "1" to listOf("স্টার জলসা"))
        val ch = channel("b", "স্টার জলসা HD", tvgId = "Z.in")
        assertEquals(EpgRef("bn", "1"), EpgMatcher.match(listOf(ch), listOf(bn), emptyMap())["b"])
    }

    @Test
    fun `name match is accepted only in the channel's own country`() {
        val jalsha = channel("j", "Star Jalsha HD", tvgId = "StarJalshaHD.in@HD")
        val ntvBd = channel("n", "NTV", tvgId = "NTV.bd@SD")
        val m = EpgMatcher.match(listOf(jalsha, ntvBd), listOf(epgPwIn, freeEpgIn), emptyMap())
        assertEquals(EpgRef("epgpw-in", "542998"), m["j"])
        assertNull(m["n"]) // NTV.bd must not get India's NTV
    }

    @Test
    fun `a channel without a country never name-matches`() {
        val m = EpgMatcher.match(listOf(channel("x", "Star Jalsha")), listOf(epgPwIn), emptyMap())
        assertNull(m["x"])
    }

    @Test
    fun `alt names are tried after the title`() {
        val ch = channel("z", "Zee Bangla Cinema?", tvgId = "ZeeBanglaX.in", altNames = listOf("Zee Bangla"))
        assertEquals(EpgRef("epgpw-in", "404001"), EpgMatcher.match(listOf(ch), listOf(epgPwIn), emptyMap())["z"])
    }

    @Test
    fun `overrides win, and a null override means no guide`() {
        val a = channel("a", "Star Jalsha", tvgId = "StarJalsha.in")
        val b = channel("b", "Zee Bangla", tvgId = "ZeeBangla.in")
        val overrides = mapOf(
            "a" to EpgOverride(EpgRef("epgpw-in", "404001")),
            "b" to EpgOverride(null)
        )
        val m = EpgMatcher.match(listOf(a, b), listOf(epgPwIn, freeEpgIn), overrides)
        assertEquals(EpgRef("epgpw-in", "404001"), m["a"])
        assertNull(m["b"])
    }

    @Test
    fun `among ids sharing a name the first with programmes wins`() {
        val dup = source(
            "dup", "in",
            "StarJalsha.in" to listOf("Star Jalsha"),
            "Star Jalsha HD.in" to listOf("Star Jalsha HD"),
            withProgrammes = setOf("Star Jalsha HD.in")
        )
        val ch = channel("j", "Star Jalsha", tvgId = "Jalsha.in")
        assertEquals(EpgRef("dup", "Star Jalsha HD.in"), EpgMatcher.match(listOf(ch), listOf(dup), emptyMap())["j"])
        val none = source("dup", "in", "StarJalsha.in" to listOf("Star Jalsha"), "Star Jalsha HD.in" to listOf("Star Jalsha HD"))
        assertEquals(EpgRef("dup", "StarJalsha.in"), EpgMatcher.match(listOf(ch), listOf(none), emptyMap())["j"])
    }

    @Test
    fun `a Pluto stream id matches the Pluto guide's channel id before tvg-id and name, in any country`() {
        // i.mjh.nz keys Pluto channels by the id in the jmp2.uk redirector URL; real ids from the
        // Worldwide 2026 playlist (CBS News 24/7 has several feeds; the first listed in the guide wins).
        val pluto = source(
            "pluto", null,
            "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"),
            "5a6b92f6e22a617379789618" to listOf("CBS News"),
            withProgrammes = setOf("5a6b92f6e22a617379789618")
        )
        val ch = channel("cbs", "CBS News 24/7 (720p)", tvgId = "CBSNews247.us@SD").copy(
            streams = listOf(
                LiveStream("https://example.org/cbs.m3u8"),
                LiveStream("https://jmp2.uk/plu-6350FDD266E9EA0007BEDEC5.m3u8"),
                LiveStream("https://jmp2.uk/plu-5a6b92f6e22a617379789618.m3u8")
            )
        )
        // The live pass prefers the stream whose guide id has programmes.
        assertEquals(EpgRef("pluto", "5a6b92f6e22a617379789618"), EpgMatcher.match(listOf(ch), listOf(pluto), emptyMap())["cbs"])
        // Without programmes anywhere, the first Pluto stream's id wins, ahead of a tvg-id hit elsewhere.
        val plain = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val byTvgId = source("other", "us", "CBSNews247.us" to listOf("CBS News 24/7"))
        assertEquals(EpgRef("pluto", "6350fdd266e9ea0007bedec5"), EpgMatcher.match(listOf(ch), listOf(byTvgId, plain), emptyMap())["cbs"])
    }

    @Test
    fun `a stale Pluto hit does not shadow a live tvg-id hit, and a live Pluto hit beats a live tvg-id hit`() {
        val ch = channel("c", "CBS News 24/7", tvgId = "CBSNews247.us@SD").copy(
            streams = listOf(LiveStream("https://jmp2.uk/plu-6350fdd266e9ea0007bedec5.m3u8"))
        )
        val stalePluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val liveById = source("other", "us", "CBSNews247.us" to listOf("CBS News 24/7"), withProgrammes = setOf("CBSNews247.us"))
        assertEquals(EpgRef("other", "CBSNews247.us"), EpgMatcher.match(listOf(ch), listOf(stalePluto, liveById), emptyMap())["c"])
        val livePluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"), withProgrammes = setOf("6350fdd266e9ea0007bedec5"))
        assertEquals(EpgRef("pluto", "6350fdd266e9ea0007bedec5"), EpgMatcher.match(listOf(ch), listOf(liveById, livePluto), emptyMap())["c"])
    }

    @Test
    fun `Plex and Samsung TV Plus stream ids match their guides' channel ids, any case`() {
        // i.mjh.nz ids as served 2026-11-10: Plex lower-case hex pairs, Samsung upper-case codes.
        val plex = source("plex", null, "6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d743" to listOf("CW Forever"))
        val samsung = source("samsung", null, "IN4000600Q" to listOf("Bollygold"))
        val cw = channel("cw", "CW Forever").copy(
            streams = listOf(LiveStream("https://jmp2.uk/plex-6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d743.m3u8"))
        )
        val bolly = channel("b", "Bollygold (1080p)", tvgId = "Bollygold.in@HD").copy(
            streams = listOf(LiveStream("https://jmp2.uk/stvp-IN4000600Q"))
        )
        val got = EpgMatcher.match(listOf(cw, bolly), listOf(plex, samsung), emptyMap())
        assertEquals(EpgRef("plex", "6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d743"), got["cw"])
        assertEquals(EpgRef("samsung", "IN4000600Q"), got["b"])
    }

    @Test
    fun `a non-Pluto URL holding a Pluto-like id does not match`() {
        val pluto = source("pluto", null, "6350fdd266e9ea0007bedec5" to listOf("CBS News 24/7"))
        val ch = channel("x", "Something").copy(
            streams = listOf(LiveStream("https://example.org/plu-6350fdd266e9ea0007bedec5.m3u8"))
        )
        assertNull(EpgMatcher.match(listOf(ch), listOf(pluto), emptyMap())["x"])
    }

    @Test
    fun `real epg pw fixture matches an iptv-org named channel by country-gated name`() {
        val at = Instant.parse("2026-10-19T02:15:00Z").toEpochMilli()
        val snapshot = javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!
            .use { XmltvNowNextParser(newParser = { KXmlParser() }).parse(it, at) }
        val ch = channel("sj", "Star Jalsha (1080p)", tvgId = "StarJalsha.in@SD")
        val ref = EpgMatcher.match(listOf(ch), listOf(EpgSource("epgpw-in", "in", snapshot)), emptyMap())["sj"]
        assertEquals(EpgRef("epgpw-in", "542998"), ref)
        assertEquals("Bigg Boss Bangla", snapshot.nowNext(ref!!.epgChannelId, at).first?.title)
    }
}
