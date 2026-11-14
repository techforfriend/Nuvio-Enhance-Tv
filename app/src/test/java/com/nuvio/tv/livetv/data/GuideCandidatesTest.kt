package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCandidatesTest {

    private fun channel(name: String, tvgId: String? = null, country: String? = null, altNames: List<String> = emptyList()) =
        Channel(
            key = "k",
            sourceId = "s",
            name = name,
            tvgId = tvgId,
            country = country,
            altNames = altNames,
            streams = listOf(LiveStream("https://example.org/k.m3u8"))
        )

    private fun source(
        id: String,
        country: String?,
        vararg chans: Pair<String, List<String>>,
        live: Map<String, String> = emptyMap()
    ) = EpgSource(
        id,
        country,
        EpgSnapshot(
            channels = chans.associate { (cid, names) -> cid to EpgChannel(cid, names, null) },
            programmes = live.mapValues { (_, title) -> listOf(EpgProgramme(0, 100, title)) },
            windowStartMs = 0,
            windowEndMs = 100,
            complete = true
        )
    )

    private fun ids(list: List<GuideCandidate>) = list.map { it.ref.epgChannelId }

    @Test
    fun `exact names first, then contained, then shared words, unrelated channels never listed`() {
        val guide = source(
            "g", "in",
            "1" to listOf("Al Jazeera Arabic"),
            "2" to listOf("Al Jazeera"),
            "3" to listOf("AL JAZEERA ENGLISH HD"),
            "4" to listOf("Zee Bangla"),
            "5" to listOf("Kolkata TV")
        )
        val out = GuideCandidates.rank(channel("Al Jazeera English"), listOf(guide), current = null, nowMs = 50)
        assertEquals(listOf("3", "2", "1"), ids(out))
    }

    @Test
    fun `tvg-id equality is exact, filler words alone do not match`() {
        val guide = source("g", null, "AlJazeera.qa" to listOf("AJ Mubasher"), "x" to listOf("Zee TV"))
        val out = GuideCandidates.rank(channel("Kolkata TV", tvgId = "AlJazeera.qa"), listOf(guide), null, 50)
        assertEquals(listOf("AlJazeera.qa"), ids(out))
    }

    @Test
    fun `an id the matcher accepts by feed or loose form is exact`() {
        // Without the id hit, "Two Gold" (contains "twog") and "BBC One X" (contains "bbcone") would lead.
        val guide = source(
            "g", null,
            "BBC.Two.HD.uk" to listOf("BBC 2"),
            "BBCOneLondon.uk" to listOf("London 1"),
            "x" to listOf("Two Gold"),
            "y" to listOf("BBC One X")
        )
        val two = GuideCandidates.rank(channel("Two Ⓖ", tvgId = "BBCTwo.uk"), listOf(guide), null, 50)
        assertEquals("BBC.Two.HD.uk", ids(two).first())
        val one = GuideCandidates.rank(channel("BBC One", tvgId = "BBCOne.uk@London"), listOf(guide), null, 50)
        assertEquals("BBCOneLondon.uk", ids(one).first())
    }

    @Test
    fun `alt names count, and the id stem is a name`() {
        val guide = source("g", "in", "Star Jalsha HD.in" to emptyList())
        val out = GuideCandidates.rank(channel("Jalsha", altNames = listOf("Star Jalsha")), listOf(guide), null, 50)
        assertEquals(listOf("Star Jalsha HD.in"), ids(out))
        // No display name: the id is the label.
        assertEquals("Star Jalsha HD.in", out.single().name)
    }

    @Test
    fun `ties go to the channel's country, then a guide with programmes, then guide order`() {
        val uk = source("uk", "uk", "a" to listOf("BBC News"))
        val inStale = source("in1", "in", "b" to listOf("BBC News"))
        val inLive = source("in2", "in", "c" to listOf("BBC News"), live = mapOf("c" to "Newsday"))
        val out = GuideCandidates.rank(channel("BBC News", country = "in"), listOf(uk, inStale, inLive), null, 50)
        assertEquals(listOf("c", "b", "a"), ids(out))
        assertEquals("Newsday", out.first().now?.title)
        assertNull(out.last().now)
    }

    @Test
    fun `a name listed several times in one guide shows once, the live one`() {
        val guide = source(
            "free", "in",
            "StarJalsha.in" to listOf("Star Jalsha"),
            "StarJalsha2.in" to listOf("Star Jalsha"),
            live = mapOf("StarJalsha2.in" to "Serial")
        )
        val out = GuideCandidates.rank(channel("Star Jalsha"), listOf(guide), null, 50)
        assertEquals(listOf("StarJalsha2.in"), ids(out))
        // The current guide wins its name's dedupe: no second "Star Jalsha" row.
        val withCurrent = GuideCandidates.rank(channel("Star Jalsha"), listOf(guide), EpgRef("free", "StarJalsha.in"), 50)
        assertEquals(listOf("StarJalsha.in"), ids(withCurrent))
    }

    @Test
    fun `the current guide is always listed, first when it misses the cut`() {
        val guide = source(
            "g", null,
            "1" to listOf("News One"),
            "2" to listOf("News Two"),
            "3" to listOf("Weather")
        )
        val current = EpgRef("g", "3")
        val out = GuideCandidates.rank(channel("News"), listOf(guide), current, 50, limit = 2)
        assertEquals(listOf("3", "1"), ids(out))
        // Already in the cut: ranked as any other, winning only a tie.
        val inCut = GuideCandidates.rank(channel("News"), listOf(guide), EpgRef("g", "2"), 50, limit = 2)
        assertEquals(listOf("2", "1"), ids(inCut))
        val exactFirst = GuideCandidates.rank(channel("News One"), listOf(guide), EpgRef("g", "2"), 50, limit = 2)
        assertEquals(listOf("1", "2"), ids(exactFirst))
        // A current guide no longer cached is not invented.
        val gone = GuideCandidates.rank(channel("News"), listOf(guide), EpgRef("old", "9"), 50, limit = 2)
        assertEquals(listOf("1", "2"), ids(gone))
    }

    @Test
    fun `no guides, no candidates`() {
        assertTrue(GuideCandidates.rank(channel("DW"), emptyList(), null, 50).isEmpty())
    }
}
