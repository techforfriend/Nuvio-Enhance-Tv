package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.HeaderGuides
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Spec 34 L7e Sources route rules: row facts, health precedence, hidden list, refresh age. */
class LiveTvSourcesModelTest {

    private fun ch(source: String, key: String, lostAtMs: Long? = null) = Channel(
        key = key,
        sourceId = source,
        name = key,
        streams = listOf(LiveStream("https://example.org/$key.m3u8")),
        lostAtMs = lostAtMs
    )

    private fun src(
        id: String,
        kind: SourceKind = SourceKind.M3U,
        count: Int = 0,
        refreshed: Long? = 1L,
        error: String? = null
    ) = LiveSource(id, kind, "name-$id", "https://example.org/$id", 0L, refreshed, error, count)

    @Test
    fun `the pack row counts no lost channels, scan-hidden ones being expected (D15 Q5)`() {
        val sources = listOf(src(ChannelPack.SOURCE_ID, SourceKind.PACK, count = 2))
        val channels = listOf(ch(ChannelPack.SOURCE_ID, "a"), ch(ChannelPack.SOURCE_ID, "hidden", lostAtMs = 5))
        assertEquals(SourceHealth.Ok, LiveTvSourcesModel.rows(sources, channels, emptyMap(), emptySet()).single().health)
    }

    @Test
    fun `rows carry counts, lost channels and the preset's names`() {
        val preset = LiveSourceManager.PRESET_ID
        val sources = listOf(src(preset, count = 2), src("p", count = 3), src("g", SourceKind.XMLTV, count = 41), src("g2", SourceKind.XMLTV))
        val channels = listOf(
            ch(preset, "DW"),
            ch(preset, "NHK"),
            ch(preset, "Gone", lostAtMs = 5),
            ch("p", "a"),
            ch("p", "b", lostAtMs = 5),
            ch("p", "c", lostAtMs = 6)
        )
        val guide = EpgSnapshot(mapOf("x" to EpgChannel("x", listOf("X"), null), "y" to EpgChannel("y", listOf("Y"), null)), emptyMap(), 0, 1, true)
        val rows = LiveTvSourcesModel.rows(sources, channels, mapOf("g" to guide), setOf("g2"))

        assertEquals(listOf("DW", "NHK"), rows[0].preview)
        assertEquals(SourceHealth.Lost(1), rows[0].health)
        assertEquals(3, rows[1].listed)
        assertNull(rows[1].matched)
        assertEquals(SourceHealth.Lost(2), rows[1].health)
        assertEquals(listOf<String>(), rows[1].preview)
        assertEquals(2, rows[2].listed)
        assertEquals(41, rows[2].matched)
        assertEquals(SourceHealth.Ok, rows[2].health)
        // An uncached guide has no file count yet; this one is refreshing.
        assertNull(rows[3].listed)
        assertEquals(SourceHealth.Refreshing, rows[3].health)
    }

    private fun pluto(source: String, key: String, lostAtMs: Long? = null) = Channel(
        key = key,
        sourceId = source,
        name = key,
        streams = listOf(LiveStream("https://example.org/$key.m3u8"), LiveStream("https://jmp2.uk/plu-5ee92e72fb286e0007285fec.m3u8")),
        lostAtMs = lostAtMs
    )

    private fun from(source: String, key: String, country: String?, lostAtMs: Long? = null, url: String = "https://example.org/$key.m3u8") =
        Channel(key = key, sourceId = source, name = key, streams = listOf(LiveStream(url)), country = country, lostAtMs = lostAtMs)

    private fun preset(key: String) = LiveSourceManager.GUIDE_PRESETS.single { it.key == key }

    @Test
    fun `a service preset is suggested while listed channels carry its stream ids and no source has it`() {
        val m = LiveTvSourcesModel
        val sources = listOf(src("p"), src("q"))
        val one = listOf(pluto("p", "a"), pluto("p", "b"), pluto("p", "gone", lostAtMs = 5), ch("q", "c"))
        assertEquals(listOf(PresetSuggestion(preset("pluto"), 2, "name-p")), m.presetSuggestions(sources, one))
        // Held by several sources: no single name.
        assertEquals(listOf(PresetSuggestion(preset("pluto"), 3, null)), m.presetSuggestions(sources, one + pluto("q", "d")))
        // No Pluto stream, or only lost ones: nothing to suggest.
        assertEquals(emptyList<PresetSuggestion>(), m.presetSuggestions(sources, listOf(ch("p", "a"), pluto("p", "gone", lostAtMs = 5))))
        // Any source with the guide's URL (the preset or a hand-added copy) ends the suggestion.
        val added = LiveSource("mine", SourceKind.XMLTV, "Mine", LiveSourceManager.PLUTO_GUIDE.url, 0L)
        assertEquals(emptyList<PresetSuggestion>(), m.presetSuggestions(sources + added, one))
        // Plex and Samsung TV Plus by their own URL shapes.
        val plex = from("p", "x", null, url = "https://jmp2.uk/plex-6a1610bebdf296985fd95603-66635fc0ca7b22f4c3a2d743.m3u8")
        val samsung = from("p", "y", "in", url = "https://jmp2.uk/sam-USBA300024TN.m3u8")
        assertEquals(
            listOf("plex", "samsung", "in"),
            m.presetSuggestions(sources, listOf(plex, samsung)).map { it.preset.key }
        )
    }

    @Test
    fun `a country preset is suggested while listed channels come from it and none of its guides is added`() {
        val m = LiveTvSourcesModel
        val sources = listOf(src("p"), src("q"))
        val one = listOf(from("p", "a", "in"), from("p", "b", "IN"), from("p", "gone", "in", lostAtMs = 5), from("q", "c", "de"))
        assertEquals(listOf(PresetSuggestion(preset("in"), 2, "name-p")), m.presetSuggestions(sources, one))
        assertEquals(listOf(PresetSuggestion(preset("in"), 3, null)), m.presetSuggestions(sources, one + from("q", "d", "in")))
        assertEquals(emptyList<PresetSuggestion>(), m.presetSuggestions(sources, listOf(from("p", "x", null))))
        // Any of a preset's URLs on any source ends it (Australia has two).
        for (key in listOf("in", "au")) {
            val channels = listOf(from("p", "a", key))
            for (guide in preset(key).guides) {
                val added = LiveSource("mine", SourceKind.XMLTV, "Mine", guide.url, 0L)
                assertEquals(emptyList<PresetSuggestion>(), m.presetSuggestions(sources + added, channels))
            }
        }
        // The iptv-org code for the United Kingdom is uk; gb fits nothing.
        assertEquals(listOf("uk"), m.presetSuggestions(sources, listOf(from("p", "a", "uk"), from("p", "b", "gb"))).map { it.preset.key })
    }

    @Test
    fun `preset suggestions run most channels first, preset order on ties`() {
        val channels = listOf("nz", "ca", "ca", "uk", "uk", "uk", "au").mapIndexed { i, c -> from("p", "k$i", c) } +
            pluto("p", "x") + pluto("p", "y") + pluto("p", "z")
        val got = LiveTvSourcesModel.presetSuggestions(listOf(src("p")), channels).map { it.preset.key to it.channels }
        assertEquals(listOf("pluto" to 3, "uk" to 3, "ca" to 2, "au" to 1, "nz" to 1), got)
        assertTrue(got.size > LiveTvSourcesModel.FOLD_PRESETS)
    }

    @Test
    fun `every preset fits exactly one service or one country, and no two hold one URL`() {
        val presets = LiveSourceManager.GUIDE_PRESETS
        assertEquals(presets.size, presets.map { it.key }.toSet().size)
        val urls = presets.flatMap { p -> p.guides.map { it.url } }
        assertEquals(urls.size, urls.toSet().size)
        presets.forEach { p ->
            assertTrue(p.key, p.guides.isNotEmpty() && p.guides.all { it.kind == SourceKind.XMLTV && it.url.startsWith("https://") })
            // A country preset's guides carry its country for name matching; a service's none.
            assertTrue(p.key, p.guides.all { it.country == p.country })
        }
    }

    @Test
    fun `health precedence`() {
        val m = LiveTvSourcesModel
        assertEquals(SourceHealth.Refreshing, m.health(src("a", error = "HTTP 500"), lost = 2, refreshing = true))
        assertEquals(SourceHealth.Partial, m.health(src("a", error = LiveSourceManager.GUIDE_INCOMPLETE), 0, false))
        assertEquals(SourceHealth.Failed, m.health(src("a", error = "HTTP 500"), lost = 2, refreshing = false))
        assertEquals(SourceHealth.Waiting, m.health(src("a", refreshed = null), 0, false))
        assertEquals(SourceHealth.Failed, m.health(src("a", refreshed = null, error = "HTTP 404"), 0, false))
        assertEquals(SourceHealth.Lost(2), m.health(src("a"), 2, false))
        assertEquals(SourceHealth.Ok, m.health(src("a"), 0, false))
    }

    @Test
    fun `hidden lists existing channels by name with their source`() {
        val rows = LiveTvSourcesModel.hidden(
            keys = setOf("zee", "Aaj", "vanished"),
            channels = listOf(ch("p", "zee"), ch("p", "Aaj"), ch("p", "shown")),
            sources = listOf(src("p"))
        )
        assertEquals(listOf("Aaj", "zee"), rows.map { it.key })
        assertEquals("name-p", rows.first().sourceName)
        assertEquals(emptyList<HiddenRow>(), LiveTvSourcesModel.hidden(emptySet(), listOf(ch("p", "a")), emptyList()))
    }

    @Test
    fun `refresh age`() {
        val zone: ZoneId = ZoneOffset.UTC
        fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val now = at(19, 8, 0)
        val m = LiveTvSourcesModel
        assertEquals(RefreshAge.Never, m.refreshAge(null, now, zone))
        assertEquals(RefreshAge.JustNow, m.refreshAge(now - 59_000, now, zone))
        assertEquals(RefreshAge.Minutes(59), m.refreshAge(at(19, 7, 1), now, zone))
        assertEquals(RefreshAge.Today(at(19, 6, 10)), m.refreshAge(at(19, 6, 10), now, zone))
        assertEquals(RefreshAge.Days(1), m.refreshAge(at(18, 23, 50), now, zone))
        assertEquals(RefreshAge.Days(3), m.refreshAge(at(16, 9, 0), now, zone))
    }

    @Test
    fun `header guides are suggested until one is added, then stay on offer, largest country first`() {
        fun g(stem: String) = "https://epgshare01.online/epgshare01/epg_ripper_$stem.xml.gz"
        fun c(key: String, country: String?, lostAtMs: Long? = null) =
            Channel(key = key, sourceId = "p", name = key, streams = emptyList(), country = country, lostAtMs = lostAtMs)
        val playlist = src("p").copy(headerGuides = HeaderGuides(listOf(g("UK1"), g("US2"), g("IT1"), g("DE1"), g("PLEX1")), byCountry = true))
        val channels = listOf(c("a", "us"), c("b", "us"), c("c", "it"), c("d", "it"), c("e", "IT"), c("f", "uk"),
            c("g", null), c("h", "uk", lostAtMs = 5))
        val m = LiveTvSourcesModel

        val s = m.headerSuggestions(listOf(playlist, src("q")), channels).single()
        assertEquals(listOf(g("IT1"), g("US2"), g("UK1"), g("DE1"), g("PLEX1")), s.guides.map { it.url })
        assertEquals(listOf(3, 2, 1, 0, 0), s.guides.map { it.channels })
        assertEquals(HeaderGuide(g("UK1"), "uk", 1, "epgshare01 United Kingdom"), s.guides[2])
        assertEquals(HeaderGuide(g("PLEX1"), null, 0, "epgshare01.online"), s.guides[4])
        assertEquals(7, s.channels)
        assertEquals(3, s.countries)
        assertEquals(listOf("epgshare01.online"), s.hosts)
        assertTrue(s.shown)
        assertFalse(s.addsAll)

        // One header guide held: the row hides; the rest stay on offer for Choose guides.
        val held = LiveSource("g1", SourceKind.XMLTV, "Mine", g("IT1"), 0L)
        val after = m.headerSuggestions(listOf(playlist, held), channels).single()
        assertFalse(after.shown)
        assertEquals(4, after.guides.size)
        // All held: nothing.
        val all = listOf(g("UK1"), g("US2"), g("DE1"), g("PLEX1")).mapIndexed { i, u -> LiveSource("h$i", SourceKind.XMLTV, "x", u, 0L) }
        assertTrue(m.headerSuggestions(listOf(playlist, held) + all, channels).isEmpty())
    }

    @Test
    fun `a few header guides add all at once, named by host when no country reads`() {
        val playlist = src("p").copy(headerGuides = HeaderGuides(listOf("https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml"), byCountry = false))
        val s = LiveTvSourcesModel.headerSuggestions(listOf(playlist), emptyList()).single()
        assertTrue(s.addsAll)
        assertEquals("raw.githubusercontent.com", s.guides.single().name)
        assertEquals("i.mjh.nz", LiveTvSourcesModel.guideName("i.mjh.nz", null))
        assertEquals("i.mjh.nz Australia", LiveTvSourcesModel.guideName("i.mjh.nz", "au"))
        assertEquals("epgshare01 India", LiveTvSourcesModel.guideName("www.epgshare01.online", "in"))
    }
}
