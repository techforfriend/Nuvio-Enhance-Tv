package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.data.ChannelMeta
import com.nuvio.tv.livetv.data.IptvMetadata
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 34 L7a screen rules: Q6 numbering, filters and counts, guide refs, rows over time. */
class LiveTvModelTest {

    private fun ch(
        source: String,
        key: String,
        chno: String? = null,
        groups: List<String> = emptyList(),
        country: String? = null,
        lostAtMs: Long? = null
    ) = Channel(
        key = key,
        sourceId = source,
        name = key,
        country = country,
        groups = groups,
        channelNumber = chno,
        streams = listOf(LiveStream("https://example.org/$key.m3u8")),
        lostAtMs = lostAtMs
    )

    private fun src(id: String, kind: SourceKind = SourceKind.M3U, country: String? = null) =
        LiveSource(id = id, kind = kind, name = id, url = "https://example.org/$id", addedAtMs = 0, country = country)

    private val sourceIds = listOf("b", "a", "preset")

    @Test
    fun `numbers run preset first then source order, tvg-chno wins, and ignore hiding and loss`() {
        val all = listOf(
            ch("a", "a1"), ch("b", "b1"), ch("preset", "p1"), ch("preset", "p2"),
            ch("b", "b2", chno = "101"), ch("a", "a2", lostAtMs = 5)
        )
        val lineup = LiveTvModel.lineup(all, sourceIds, meta = null, hidden = setOf("b1"))
        assertEquals(
            mapOf("p1" to "1", "p2" to "2", "b1" to "3", "b2" to "101", "a1" to "5", "a2" to "6"),
            lineup.numbers
        )
        // Hidden b1 and lost a2 are not listable; order follows the numbering.
        assertEquals(listOf("p1", "p2", "b2", "a1"), lineup.channels.map { it.key })
        assertEquals(4, LiveTvModel.filtered(lineup, LiveTvFilter.ALL, UserLists.EMPTY).size)
    }

    @Test
    fun `nsfw channels from metadata are dropped`() {
        val meta = IptvMetadata(
            fetchedAtMs = 0,
            channels = mapOf(ChannelIdentity.normalizeTvgId("Adult.us")!! to ChannelMeta(name = "Adult", country = "us", nsfw = true))
        )
        val all = listOf(ch("a", "a1").copy(tvgId = "Adult.us"), ch("a", "a2"))
        val lineup = LiveTvModel.lineup(all, sourceIds, meta, emptySet())
        assertEquals(listOf("a2"), lineup.channels.map { it.key })
        // Not even Favorites or Recents list an NSFW channel.
        assertEquals(listOf("a2"), lineup.withLost.map { it.key })
    }

    private fun tagged(
        key: String,
        country: String? = null,
        groups: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        languages: List<String> = emptyList()
    ) = ch("a", key, groups = groups, country = country).copy(categories = categories, languages = languages)

    /** Canada: news (eng), news (fra), music; India: news (eng, hin), sports; one without country or category. */
    private val browseChannels = listOf(
        tagged("ca1", "ca", categories = listOf("news"), languages = listOf("eng")),
        tagged("ca2", "ca", groups = listOf("News"), languages = listOf("fra")),
        tagged("ca3", "ca", groups = listOf("Music", "Kids")),
        tagged("in1", "in", categories = listOf("news"), languages = listOf("eng", "hin")),
        tagged("in2", "in", groups = listOf("Sports")),
        tagged("x1", groups = listOf("Undefined"))
    )

    @Test
    fun `channel tags fold blank and Undefined into Other, missing country into No country`() {
        val lineup = LiveTvModel.lineup(browseChannels, listOf("a"), meta = null, hidden = emptySet())
        assertEquals(ChannelTags("ca", setOf("news"), setOf("eng")), lineup.tags["ca1"])
        // A playlist group "News" is the same category as iptv-org's `news`.
        assertEquals(setOf("news"), lineup.tags.getValue("ca2").categories)
        assertEquals(setOf("music", "kids"), lineup.tags.getValue("ca3").categories)
        assertEquals(ChannelTags(LiveTvFilter.NO_COUNTRY, setOf(LiveTvFilter.OTHER), emptySet()), lineup.tags["x1"])
        assertEquals("News", lineup.categoryLabels["news"])
        assertEquals("Music", lineup.categoryLabels["music"])
        // Rows word categories the same way: an "Undefined" group shows no category.
        assertNull(LiveRow(lineup.channel("x1")!!, "6", null, null, false, null).category)
    }

    @Test
    fun `browse combines the pickers, ALL lists everything`() {
        val lineup = LiveTvModel.lineup(browseChannels, listOf("a"), meta = null, hidden = emptySet())
        fun keys(f: LiveTvFilter) = LiveTvModel.filtered(lineup, f, UserLists.EMPTY).map { it.key }
        assertEquals(6, keys(LiveTvFilter.ALL).size)
        assertEquals(listOf("ca1", "ca2"), keys(LiveTvFilter.Browse(country = "ca", category = "news")))
        assertEquals(listOf("ca1"), keys(LiveTvFilter.Browse(country = "ca", category = "news", language = "eng")))
        assertEquals(listOf("ca1", "in1"), keys(LiveTvFilter.Browse(language = "eng")))
        assertEquals(listOf("x1"), keys(LiveTvFilter.Browse(country = LiveTvFilter.NO_COUNTRY)))
        assertEquals(listOf("x1"), keys(LiveTvFilter.Browse(category = LiveTvFilter.OTHER)))
        assertEquals(emptyList<String>(), keys(LiveTvFilter.Browse(country = "ca", category = "sports")))
    }

    @Test
    fun `pickers count under the other pickers, pin the profile's values, list the fold value last`() {
        val lineup = LiveTvModel.lineup(browseChannels, listOf("a"), meta = null, hidden = emptySet())
        val user = LiveTvModel.userLists(lineup, favorites = listOf("in2", "x1"), recents = listOf("ca1"))
        val p = LiveTvModel.pickers(lineup, LiveTvFilter.Browse(country = "ca", category = "news"), user)

        // Country ignores its own value: news channels in every country (ca1, ca2, in1).
        assertEquals(listOf(PickerValue("in", 1), PickerValue("ca", 2)), p.country.pinned)
        assertEquals(listOf(PickerValue(null, 3)), p.country.values)
        // Category inside Canada: news 2, kids 1, music 1 (ties by key); in2's sports is not in Canada.
        assertEquals(listOf(PickerValue("news", 2)), p.category.pinned)
        assertEquals(
            listOf(PickerValue(null, 3), PickerValue("kids", 1), PickerValue("music", 1)),
            p.category.values
        )
        // Language inside Canada + news: All counts both; eng is pinned (ca1, a recent).
        assertEquals(listOf(PickerValue("eng", 1)), p.language.pinned)
        assertEquals(listOf(PickerValue(null, 2), PickerValue("fra", 1)), p.language.values)

        // Nothing selected: No country and Other come last, never pinned (x1 is a favorite).
        val all = LiveTvModel.pickers(lineup, LiveTvFilter.ALL, UserLists.EMPTY)
        assertEquals(
            listOf(null, "ca", "in", LiveTvFilter.NO_COUNTRY),
            all.country.values.map { it.key }
        )
        assertEquals(LiveTvFilter.OTHER, all.category.values.last().key)
        val pinnedAll = LiveTvModel.pickers(lineup, LiveTvFilter.ALL, user)
        assertTrue(pinnedAll.country.pinned.none { it.key == LiveTvFilter.NO_COUNTRY })
        assertTrue(pinnedAll.category.pinned.none { it.key == LiveTvFilter.OTHER })
        // A pinned value is not listed again below.
        assertTrue(pinnedAll.country.values.none { v -> pinnedAll.country.pinned.any { it.key == v.key } })
    }

    @Test
    fun `display names drop bracketed labels and quality tags only`() {
        val m = LiveTvModel
        assertEquals("CBS News 24/7", m.displayName("CBS News 24/7 (720p) [Geo-blocked]"))
        assertEquals("ABC News Live 1", m.displayName("ABC News Live 1 (1080i) [Not 24/7]"))
        assertEquals("Star Sports", m.displayName("Star Sports (HD)"))
        assertEquals("His Light (LLBN)", m.displayName("His Light (LLBN) (720p)"))
        assertEquals("Film Club (2025)", m.displayName("Film Club (2025)"))
        assertEquals("[Not 24/7]", m.displayName("[Not 24/7]"))
    }

    @Test
    fun `language names, including ISO 639-3 codes the JVM names only via two letters`() {
        assertEquals("English", LiveTvModel.languageName("eng"))
        assertEquals("Bengali", LiveTvModel.languageName("ben")) // CLDR's "Bangla", pinned to the mockup's word
        assertEquals("French", LiveTvModel.languageName("fra"))
        assertEquals("Chinese", LiveTvModel.languageName("zho"))
        assertEquals("qqq", LiveTvModel.languageName("qqq"))
    }

    @Test
    fun `favorites in number order and recents newest first keep lost channels, drop hidden and pruned ones`() {
        val all = listOf(
            ch("a", "a1"), ch("a", "a2", lostAtMs = 5), ch("a", "a3"), ch("a", "a4"), ch("preset", "p1")
        )
        val lineup = LiveTvModel.lineup(all, listOf("a"), meta = null, hidden = setOf("a4"))
        assertEquals(listOf("p1", "a1", "a3"), lineup.channels.map { it.key })
        assertEquals(listOf("p1", "a1", "a2", "a3"), lineup.withLost.map { it.key })
        assertEquals("a2", lineup.channel("a2")?.key)

        // "gone" was pruned from the cache, a4 is hidden: both drop out.
        val user = LiveTvModel.userLists(lineup, favorites = listOf("a3", "a2", "p1", "a4", "gone"), recents = listOf("a2", "gone", "a1", "a4"))
        assertEquals(listOf("p1", "a2", "a3"), user.favorites.map { it.key })
        assertEquals(listOf("a2", "a1"), user.recents.map { it.key })
        assertEquals(setOf("p1", "a2", "a3"), user.favoriteKeys)

        assertEquals(listOf("p1", "a2", "a3"), LiveTvModel.filtered(lineup, LiveTvFilter.Favorites, user).map { it.key })
        assertEquals(listOf("a2", "a1"), LiveTvModel.filtered(lineup, LiveTvFilter.Recents, user).map { it.key })
        // Other filters never list a lost channel.
        assertFalse(LiveTvModel.filtered(lineup, LiveTvFilter.ALL, user).any { it.key == "a2" })
        assertEquals(mapOf(LiveTvFilter.Favorites to 3, LiveTvFilter.Recents to 2), LiveTvModel.counts(user))

        val rows = LiveTvModel.rows(user.favorites, lineup.numbers, emptyMap(), emptyMap(), 0L, user.favoriteKeys)
        assertTrue(rows.all { it.favorite })
        assertEquals(listOf(false, true, false), rows.map { it.lost })
    }

    @Test
    fun `filter ids round trip, retired pill ids open as picker values, and the opening filter follows Q1`() {
        val filters = listOf(
            LiveTvFilter.Favorites, LiveTvFilter.Recents, LiveTvFilter.ALL,
            LiveTvFilter.Browse(country = "ca"),
            LiveTvFilter.Browse(country = LiveTvFilter.NO_COUNTRY, category = "movies|series; x", language = "eng"),
            LiveTvFilter.Browse(category = LiveTvFilter.OTHER)
        )
        filters.forEach { assertEquals(it, LiveTvFilter.fromId(it.id)) }
        assertEquals(listOf("favorites", "recents", "all", "browse:ca||"), filters.take(4).map { it.id })
        // L8-D3 ids saved by the fixed pills keep working.
        assertEquals(LiveTvFilter.Browse(category = "sports"), LiveTvFilter.fromId("sports"))
        assertEquals(LiveTvFilter.Browse(language = "eng"), LiveTvFilter.fromId("english"))
        assertEquals(LiveTvFilter.Browse(language = "ben"), LiveTvFilter.fromId("bengali"))
        assertEquals(LiveTvFilter.Browse(country = "ca"), LiveTvFilter.fromId("canada"))
        assertNull(LiveTvFilter.fromId("nope"))
        assertNull(LiveTvFilter.fromId("browse:ca|news"))
        assertNull(LiveTvFilter.fromId("browse:%zz||"))
        assertEquals(LiveTvFilter.Browse(category = "sports"), LiveTvFilter.opening("sports", hasFavorites = true))
        assertEquals(LiveTvFilter.Favorites, LiveTvFilter.opening(null, hasFavorites = true))
        assertEquals(LiveTvFilter.ALL, LiveTvFilter.opening(null, hasFavorites = false))
        // An id from a later or older build falls back like none saved.
        assertEquals(LiveTvFilter.Favorites, LiveTvFilter.opening("nope", hasFavorites = true))
    }

    private val hour = 3_600_000L
    private val snapshot = EpgSnapshot(
        channels = mapOf("g1" to EpgChannel("g1", listOf("x1"), null)),
        programmes = mapOf(
            "g1" to listOf(
                EpgProgramme(0, hour, "First", live = true),
                EpgProgramme(hour, 2 * hour, "Second")
            )
        ),
        windowStartMs = 0,
        windowEndMs = 6 * hour,
        complete = true
    )

    @Test
    fun `guide refs use only cached xmltv sources, overrides win`() {
        val channels = listOf(ch("a", "x1").copy(tvgId = "g1"), ch("a", "x2"))
        val guideSources = LiveTvModel.guideCountries(listOf(src("a"), src("guide", SourceKind.XMLTV, country = "in")))
        assertEquals(listOf("guide" to "in"), guideSources)
        val refs = LiveTvModel.guideRefs(channels, guideSources, mapOf("guide" to snapshot), emptyMap())
        assertEquals(EpgRef("guide", "g1"), refs["x1"])
        assertNull(refs["x2"])
        val noGuide = LiveTvModel.guideRefs(channels, guideSources, mapOf("guide" to snapshot), mapOf("x1" to EpgOverride(null)))
        assertNull(noGuide["x1"])
        assertTrue(LiveTvModel.guideRefs(channels, guideSources, emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `rows re-derive now and next with time, live badge, missing guide`() {
        val channels = listOf(ch("a", "x1"), ch("a", "x2"), ch("a", "x3"))
        val refs = mapOf("x1" to EpgRef("guide", "g1"), "x3" to EpgRef("gone", "g1"))
        val numbers = mapOf("x1" to "1", "x2" to "2", "x3" to "3")
        val early = LiveTvModel.rows(channels, numbers, refs, mapOf("guide" to snapshot), nowMs = hour / 2)
        assertEquals("First", early[0].now?.title)
        assertEquals(0.5f, early[0].progress!!, 0.001f)
        assertNull(early[1].progress)
        assertEquals("Second", early[0].next?.title)
        assertTrue(early[0].live)
        assertTrue(early[0].hasGuide)
        assertFalse(early[1].hasGuide)
        assertFalse(early[2].hasGuide)
        val later = LiveTvModel.rows(channels, numbers, refs, mapOf("guide" to snapshot), nowMs = hour + 1)
        assertEquals("Second", later[0].now?.title)
        assertFalse(later[0].live)
        assertEquals("1", later[0].number)
    }

    private fun row(key: String) = LiveRow(ch("a", key), key, null, null, hasGuide = false, progress = null)

    @Test
    fun `a schedule opens only on a guided, listed row with something now or next`() {
        val p = EpgProgramme(0, 1, "P")
        val base = row("s")
        assertFalse(base.hasSchedule)
        assertFalse(base.copy(hasGuide = true).hasSchedule) // a guide with nothing now or next
        assertTrue(base.copy(hasGuide = true, now = p).hasSchedule)
        assertTrue(base.copy(hasGuide = true, next = p).hasSchedule)
        assertFalse(base.copy(hasGuide = false, now = p).hasSchedule)
        val lost = base.copy(channel = base.channel.copy(lostAtMs = 1L), hasGuide = true, now = p)
        assertFalse(lost.hasSchedule)
    }

    private fun zap(rows: List<LiveRow>, key: String?, step: Int, skip: Set<String> = emptySet()) =
        LiveTvModel.zapRow(rows, key, step) { it.key in skip }?.channel?.key

    @Test
    fun `zap steps both ways and wraps`() {
        val rows = listOf(row("x"), row("y"), row("z"))
        assertEquals("y", zap(rows, "x", +1))
        assertEquals("x", zap(rows, "z", +1))
        assertEquals("x", zap(rows, "y", -1))
        assertEquals("z", zap(rows, "x", -1))
    }

    @Test
    fun `zap from an unlisted key starts at the near end`() {
        val rows = listOf(row("x"), row("y"), row("z"))
        assertEquals("x", zap(rows, "gone", +1))
        assertEquals("z", zap(rows, null, -1))
        assertEquals("y", zap(rows, "gone", +1, skip = setOf("x")))
        assertEquals("x", zap(rows, "gone", -1, skip = setOf("z", "y")))
    }

    @Test
    fun `zap passes over skipped channels, wrapping`() {
        val rows = listOf(row("w"), row("x"), row("y"), row("z"))
        assertEquals("z", zap(rows, "w", +1, skip = setOf("x", "y")))
        assertEquals("x", zap(rows, "y", +1, skip = setOf("z", "w")))
        assertEquals("y", zap(rows, "w", -1, skip = setOf("z")))
    }

    @Test
    fun `zap is null when nothing else qualifies`() {
        val rows = listOf(row("x"), row("y"), row("z"))
        assertNull(zap(rows, "x", +1, skip = setOf("y", "z")))
        // The current channel is never the answer, even when it is not skipped.
        assertNull(zap(rows, "x", -1, skip = setOf("y", "z")))
        assertNull(zap(rows, "gone", +1, skip = setOf("x", "y", "z")))
        assertNull(zap(listOf(row("x")), "x", +1))
        assertNull(zap(emptyList(), "x", +1))
        assertNull(zap(emptyList(), null, -1))
    }

    @Test
    fun `facts word iptv-org categories and countries, keep playlist groups`() {
        val enriched = ch("a", "k", groups = listOf("Bangla News"), country = "in").copy(categories = listOf("news"))
        assertEquals(listOf("News", "India"), LiveTvModel.facts(LiveRow(enriched, "1", null, null, false, null)))
        val grouped = ch("a", "g", groups = listOf("Bangla News"), country = "uk")
        assertEquals(listOf("Bangla News", "United Kingdom"), LiveTvModel.facts(LiveRow(grouped, "2", null, null, false, null)))
        assertEquals(emptyList<String>(), LiveTvModel.facts(row("bare")))
        assertEquals("ZZ9", LiveTvModel.countryName("zz9"))
    }

    @Test
    fun `monograms take word initials, else the word's first letters`() {
        assertEquals("AJE", LiveTvModel.monogram("Al Jazeera English"))
        assertEquals("NDT", LiveTvModel.monogram("NDTV 24x7"))
        assertEquals("SJ", LiveTvModel.monogram("Star Jalsha"))
        assertEquals("WIO", LiveTvModel.monogram("wion"))
        assertEquals("24", LiveTvModel.monogram(" 24x7 "))
    }
}
