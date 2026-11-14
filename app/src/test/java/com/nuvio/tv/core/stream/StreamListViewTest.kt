package com.nuvio.tv.core.stream

import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.ReleaseResolution.P1080 as ReleaseResolutionP1080
import com.nuvio.tv.domain.model.ReleaseResolution.P2160 as ReleaseResolutionP2160
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 51 P4: the hidden set and the one derivation every stream list uses (Spec 58 F3: with the resolution limit). */
class StreamListViewTest {

    private val manual40 = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
    private val noTransport: () -> NetworkTransport = { error("the link is read only under NETWORK_TEST") }

    /** Over a 60-minute runtime: 60 GB = 133 Mbit/s (over 40), 6 GB = 13 (under). */
    private fun stream(
        name: String,
        addon: String = "A",
        gb: Long = 6,
        url: String? = "https://host/$name.mkv",
        infoHash: String? = null,
        bingeGroup: String? = null
    ) = Stream(
        name = name, title = null, description = null, url = url, ytId = null, infoHash = infoHash,
        fileIdx = null, externalUrl = null,
        behaviorHints = bingeGroup?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = it, countryWhitelist = null, proxyHeaders = null)
        },
        addonName = addon, addonLogo = null,
        streamData = AioStreamData(size = gb * 1_000_000_000L, filename = null, cached = null, parsedFile = null)
    )

    private fun hidden(
        streams: List<Stream>,
        limit: StreamLimitInputs? = manual40,
        runtimeMinutes: Int? = 60,
        playingKey: String? = null,
        exemptBingeGroup: String? = null
    ) = hiddenStreamVerdicts(streams, limit, noTransport, runtimeMinutes, episode = false, playingKey, exemptBingeGroup)

    private val big = stream("big", gb = 60)
    private val small = stream("small")

    @Test
    fun `nothing is hidden while the limit is unknown or off`() {
        assertTrue(hidden(listOf(big), limit = null).isEmpty())
        assertTrue(hidden(listOf(big), limit = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.OFF))).isEmpty())
    }

    @Test
    fun `a stream over the limit is hidden by its limit key with its verdict`() {
        val verdicts = hidden(listOf(big, small))
        assertEquals(setOf(big.limitKey()), verdicts.keys)
        assertEquals(40, verdicts.getValue(big.limitKey()).bitrate?.figureMbps)
        assertEquals(verdicts.keys, hiddenStreamKeys(listOf(big, small), manual40, noTransport, 60, episode = false))
    }

    @Test
    fun `a late runtime puts a size-only stream over the limit, a stream with its own length is hidden before it`() {
        assertTrue(hidden(listOf(big), runtimeMinutes = null).isEmpty())
        assertEquals(setOf(big.limitKey()), hidden(listOf(big), runtimeMinutes = 60).keys)
        // 30 GB over 47 minutes of file = 85 Mbit/s with no runtime known (D8: the gate still hits).
        val thirty = stream("len", gb = 30)
        val withLength = thirty.copy(streamData = thirty.streamData!!.copy(durationMs = 2_820_000L))
        assertEquals(setOf(withLength.limitKey()), hidden(listOf(withLength), runtimeMinutes = null).keys)
    }

    @Test
    fun `the playing stream is never hidden`() {
        assertTrue(hidden(listOf(big), playingKey = big.limitKey()).isEmpty())
    }

    @Test
    fun `an exempt binge group's streams stay shown, another group's do not`() {
        val inGroup = stream("in", gb = 60, bingeGroup = "g1")
        val otherGroup = stream("other", gb = 60, bingeGroup = "g2")
        assertEquals(setOf(otherGroup.limitKey()), hidden(listOf(inGroup, otherGroup), exemptBingeGroup = "g1").keys)
    }

    @Test
    fun `the link is read once under the network test, and not at all with no table`() {
        var reads = 0
        val link = { reads++; NetworkTransport.WIFI }
        val tablesNone = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.NETWORK_TEST))
        assertTrue(hiddenStreamVerdicts(listOf(big, small), tablesNone, link, 60, episode = false).isEmpty())
        assertEquals(0, reads)

        // real-debrid.com's fastest row 40 Mbit/s: the 133 Mbit/s stream on it is hidden.
        val table = ServiceTable(NetworkTransport.WIFI, testedAtMs = 1L, http2 = true, rows = listOf(NetworkRow(4, 16, 40.0)))
        val tested = StreamLimitInputs(
            StreamLimitSettings(StreamLimitMode.NETWORK_TEST),
            ServiceNetworkTables(tables = mapOf("real-debrid.com" to table))
        )
        val onRd = stream("rd", gb = 60, url = "https://download.real-debrid.com/d/rd.mkv")
        assertEquals(setOf(onRd.limitKey()), hiddenStreamVerdicts(listOf(onRd, small), tested, link, 60, episode = false).keys)
        assertEquals(1, reads)
    }

    @Test
    fun `an untested domain's row is hidden by its debrid service's table from the same list`() {
        // Spec 59 N-10: the addon's playback domain aliases to real-debrid.com (40 Mbit/s, figure 32).
        val table = ServiceTable(NetworkTransport.WIFI, testedAtMs = 1L, http2 = true, rows = listOf(NetworkRow(4, 16, 40.0)))
        val tested = StreamLimitInputs(
            StreamLimitSettings(StreamLimitMode.NETWORK_TEST),
            ServiceNetworkTables(tables = mapOf("real-debrid.com" to table), aliases = mapOf("example.net" to "real-debrid.com"))
        )
        fun on(name: String, url: String, gb: Long) = stream(name, gb = gb, url = url).let {
            it.copy(streamData = it.streamData!!.copy(serviceId = "realdebrid"))
        }
        val viaAddon = on("addon", "https://aio.example.net/p/1.mkv", gb = 6)
        val storz = on("storz", "https://stremthru.example.xyz/torz/2.mkv", gb = 60)
        val link = { NetworkTransport.WIFI }
        assertEquals(setOf(storz.limitKey()), hiddenStreamVerdicts(listOf(viaAddon, storz), tested, link, 60, episode = false).keys)
        // The mapping is built before the playing / exempt skips: the anchor row playing (or exempt) still lends its table.
        assertEquals(
            setOf(storz.limitKey()),
            hiddenStreamVerdicts(listOf(viaAddon, storz), tested, link, 60, episode = false, playingKey = viaAddon.limitKey()).keys
        )
        val exemptAnchor = viaAddon.copy(
            behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = "g", countryWhitelist = null, proxyHeaders = null)
        )
        assertEquals(
            setOf(storz.limitKey()),
            hiddenStreamVerdicts(listOf(exemptAnchor, storz), tested, link, 60, episode = false, exemptBingeGroup = "g").keys
        )
        // Alone, nothing in its list reaches a table: it shows (Spec 51 D5).
        assertTrue(hiddenStreamVerdicts(listOf(storz), tested, link, 60, episode = false).isEmpty())
    }

    @Test
    fun `limit key separates two services' copies of one torrent file`() {
        val rd = stream("f", url = "https://real-debrid.com/d/1/f.mkv", infoHash = "abc")
        val pm = stream("f", url = "https://premiumize.me/d/1/f.mkv", infoHash = "abc")
        assertEquals(rd.badgeMergeKey(), pm.badgeMergeKey())
        assertNotEquals(rd.limitKey(), pm.limitKey())
    }

    // -- deriveStreamList --

    private fun derive(all: List<Stream>, selection: StreamListSelection, chipAddons: List<String> = emptyList()) =
        deriveStreamList(all, selection, hidden(all), chipAddons)

    @Test
    fun `with nothing hidden every view lists its streams and counts them`() {
        val b = stream("b", addon = "B")
        val view = derive(listOf(small, b), StreamListSelection.All)
        assertEquals(StreamListSelection.All, view.selection)
        assertEquals(listOf(small, b), view.streams)
        assertFalse(view.listsHidden)
        assertEquals(2, view.allCount)
        assertEquals(mapOf("A" to 1, "B" to 1), view.addonCounts)
        assertEquals(0, view.hiddenCount)
        assertEquals(2, view.total)
        assertEquals(listOf(b), derive(listOf(small, b), StreamListSelection.Addon("B")).streams)
    }

    @Test
    fun `shown views drop the hidden streams and the chips count only what they list`() {
        val bBig = stream("bbig", addon = "B", gb = 60)
        val b = stream("b", addon = "B")
        val view = derive(listOf(big, small, bBig, b), StreamListSelection.All)
        assertEquals(listOf(small, b), view.streams)
        assertEquals(2, view.allCount)
        assertEquals(mapOf("A" to 1, "B" to 1), view.addonCounts)
        assertEquals(2, view.hiddenCount)
        assertEquals(4, view.total)

        val hiddenView = derive(listOf(big, small, bBig, b), StreamListSelection.Hidden)
        assertEquals(StreamListSelection.Hidden, hiddenView.selection)
        assertEquals(listOf(big, bBig), hiddenView.streams)
        assertTrue(hiddenView.listsHidden)
    }

    @Test
    fun `hidden with nothing hidden and an addon gone from the chip row read as All`() {
        assertEquals(StreamListSelection.All, derive(listOf(small), StreamListSelection.Hidden).selection)
        val gone = derive(listOf(small), StreamListSelection.Addon("Gone"), chipAddons = listOf("A"))
        assertEquals(StreamListSelection.All, gone.selection)
        assertEquals(listOf(small), gone.streams)
    }

    @Test
    fun `a stored pick is kept while its chip is in the row, else it drops to All`() {
        assertEquals(StreamListSelection.Addon("A"), StreamListSelection.Addon("A").keptIn(listOf("A", "B")))
        assertEquals(StreamListSelection.All, StreamListSelection.Addon("Gone").keptIn(listOf("A")))
        assertEquals(StreamListSelection.Hidden, StreamListSelection.Hidden.keptIn(emptyList()))
        assertEquals(StreamListSelection.All, StreamListSelection.All.keptIn(emptyList()))
    }

    @Test
    fun `an addon still loading keeps its chip selected over an empty view`() {
        val loading = derive(listOf(small), StreamListSelection.Addon("Slow"), chipAddons = listOf("A", "Slow"))
        assertEquals(StreamListSelection.Addon("Slow"), loading.selection)
        assertTrue(loading.streams.isEmpty())
        assertFalse(loading.listsHidden)
    }

    @Test
    fun `the chip row lists addons with streams, then the other source chips once`() {
        val chips = listOf(
            SourceChipItem("B", SourceChipStatus.SUCCESS),
            SourceChipItem("Slow", SourceChipStatus.LOADING),
            SourceChipItem("A", SourceChipStatus.SUCCESS)
        )
        assertEquals(listOf("A", "B", "Slow"), sourceChipAddonNames(listOf("A", "B"), chips))
    }

    @Test
    fun `two addons renamed alike give one chip, never a duplicate row key`() {
        // AddonRepositoryImpl.applyDisplayNames numbers only unrenamed duplicates.
        assertEquals(listOf("Mine", "B"), sourceChipAddonNames(listOf("Mine", "B", "Mine"), emptyList()))
    }

    @Test
    fun `the limit names the lowest figure among the hidden streams, with its service and test date`() {
        val rd = StreamLimitVerdict(150.0, figureMbps = 133, source = NetworkTestSource("real-debrid.com", 1L, 166))
        val pm = StreamLimitVerdict(70.0, figureMbps = 60, source = NetworkTestSource("premiumize.me", 2L, 75))
        assertEquals(pm, StreamListView(hiddenVerdicts = mapOf("a" to HiddenStream(rd), "b" to HiddenStream(pm), "c" to HiddenStream(rd))).limitVerdict)
    }

    @Test
    fun `on a tie the first hidden stream's verdict names the limit`() {
        val first = StreamLimitVerdict(70.0, figureMbps = 60, source = NetworkTestSource("premiumize.me", 1L, 75))
        val second = StreamLimitVerdict(70.0, figureMbps = 60, source = NetworkTestSource("torbox.app", 2L, 75))
        assertEquals("premiumize.me", StreamListView(hiddenVerdicts = mapOf("a" to HiddenStream(first), "b" to HiddenStream(second))).limitVerdict?.source?.serviceKey)
    }

    @Test
    fun `nothing hidden names no limit`() {
        assertEquals(null, StreamListView().limitVerdict)
    }

    @Test
    fun `a view with only hidden streams lists them under its own chip`() {
        val b = stream("b", addon = "B")
        val bothHidden = derive(listOf(big), StreamListSelection.All)
        assertEquals(listOf(big), bothHidden.streams)
        assertTrue(bothHidden.listsHidden)
        assertEquals(1, bothHidden.allCount)

        // Addon A has only a hidden stream: its view lists it, its chip counts it; All counts B.
        val addonA = derive(listOf(big, b), StreamListSelection.Addon("A"))
        assertEquals(StreamListSelection.Addon("A"), addonA.selection)
        assertEquals(listOf(big), addonA.streams)
        assertTrue(addonA.listsHidden)
        assertEquals(mapOf("A" to 1, "B" to 1), addonA.addonCounts)
        assertEquals(1, addonA.allCount)
    }

    @Test
    fun `a second addon's shown streams fill an All that listed only hidden ones`() {
        assertTrue(derive(listOf(big), StreamListSelection.All).listsHidden)
        val b = stream("b", addon = "B")
        val filled = derive(listOf(big, b), StreamListSelection.All)
        assertEquals(StreamListSelection.All, filled.selection)
        assertEquals(listOf(b), filled.streams)
        assertFalse(filled.listsHidden)
    }

    @Test
    fun `the playing stream and an exempt group stay in the shown list`() {
        val inGroup = stream("in", gb = 60, bingeGroup = "g1")
        val all = listOf(big, inGroup, small)
        val view = deriveStreamList(
            all, StreamListSelection.All, hidden(all, playingKey = big.limitKey(), exemptBingeGroup = "g1"), emptyList()
        )
        assertEquals(all, view.streams)
        assertEquals(0, view.hiddenCount)
    }

    // -- Spec 58 F3: the resolution limit --

    private val upTo1080 = ResolutionLimit(ResolutionLimitMode.UP_TO_1080P)

    @Test
    fun `the resolution limit hides a known resolution over its cap, with or without the bitrate limit`() {
        val uhdSmall = stream("Film.2160p.WEB", gb = 6)
        val uhdBig = stream("Film.2160p.REMUX", gb = 60)
        val fhdBig = stream("Film.1080p.REMUX", gb = 60)
        val plain = stream("Film", gb = 6)
        val both = hidden(listOf(uhdSmall, uhdBig, fhdBig, plain), limit = manual40.copy(resolution = upTo1080))
        assertEquals(HiddenStream(resolution = ResolutionVerdict(ReleaseResolutionP1080, fromTv = false)), both[uhdSmall.limitKey()])
        assertTrue(both.getValue(uhdBig.limitKey()).let { it.bitrate != null && it.resolution != null })
        assertTrue(both.getValue(fhdBig.limitKey()).let { it.bitrate != null && it.resolution == null })
        assertFalse(plain.limitKey() in both)

        // Bitrate limit off: only the resolution limit reads, and never the link.
        val resolutionOnly = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.OFF), resolution = upTo1080)
        assertEquals(setOf(uhdSmall.limitKey(), uhdBig.limitKey()), hidden(listOf(uhdSmall, uhdBig, fhdBig), limit = resolutionOnly).keys)
        val networkNoTables = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.NETWORK_TEST), resolution = upTo1080)
        assertEquals(setOf(uhdSmall.limitKey()), hidden(listOf(uhdSmall, fhdBig), limit = networkNoTables).keys)
    }

    @Test
    fun `a 4K TV's resolution limit hides nothing`() {
        val uhd = stream("Film.2160p.WEB")
        val fourK = StreamLimitInputs(
            StreamLimitSettings(StreamLimitMode.OFF),
            resolution = ResolutionLimit(ResolutionLimitMode.THIS_TV, tvMax = ReleaseResolutionP2160)
        )
        assertTrue(hidden(listOf(uhd), limit = fourK).isEmpty())
    }

    // Spec 65 P6: the show's preferred source leads the shown rows of every view; never the hidden ones.
    @Test
    fun `the preferred source's matches lead each view that shows them`() {
        val a1 = stream("Show.S01E03.2160p.WEB-FLUX", addon = "A")
        val a2 = stream("Show.S01E03.1080p.WEB-NTb", addon = "A")
        val b1 = stream("Show.S01E03.1080p.WEB-NTb", addon = "B", url = "https://host/b1.mkv")
        val b2 = stream("Show.S01E03.1080p.BluRay-DON", addon = "B", url = "https://host/b2.mkv")
        val all = listOf(a1, a2, b1, b2)
        val preferB = PreferredSource(addonName = "B", resolution = ReleaseResolutionP1080)
        fun view(selection: StreamListSelection, hidden: Map<String, HiddenStream> = emptyMap(), preferred: PreferredSource? = preferB) =
            deriveStreamList(all, selection, hidden, chipAddons = listOf("A", "B"), preferred = preferred)

        val allView = view(StreamListSelection.All)
        assertEquals(listOf(b1, b2, a1, a2), allView.streams)
        assertEquals(2, allView.preferredCount)
        // An addon's view counts its own leading matches.
        assertEquals(2, view(StreamListSelection.Addon("B")).preferredCount)
        val addonA = view(StreamListSelection.Addon("A"))
        assertEquals(listOf(a1, a2), addonA.streams)
        assertEquals(0, addonA.preferredCount)
        // No preference: the list as it came.
        assertEquals(all, view(StreamListSelection.All, preferred = null).streams)
        // A hidden match is not moved, marked or counted; the hidden view keeps its order.
        val hideB1 = mapOf(b1.limitKey() to HiddenStream(resolution = ResolutionVerdict(ReleaseResolutionP1080, fromTv = false)))
        val withHidden = view(StreamListSelection.All, hideB1)
        assertEquals(listOf(b2, a1, a2), withHidden.streams)
        assertEquals(1, withHidden.preferredCount)
        val hiddenView = view(StreamListSelection.Hidden, hideB1)
        assertEquals(listOf(b1), hiddenView.streams)
        assertEquals(0, hiddenView.preferredCount)
    }

    // The filters sheet's values stay those of the whole view; the filter narrows before the preference orders.
    @Test
    fun `a filter narrows the rows the preferred source then leads`() {
        val uhd = stream("Show.S01E03.2160p.WEB-FLUX")
        val fhd = stream("Show.S01E03.1080p.WEB-NTb")
        val all = listOf(uhd, fhd)
        val prefer = PreferredSource(addonName = "A", resolution = ReleaseResolutionP1080)
        val only4k = FacetFilter.NONE.toggled(FacetValue(FacetGroup.RESOLUTION, "P2160"))
        val filtered = deriveStreamList(all, StreamListSelection.All, emptyMap(), listOf("A"), filter = only4k, preferred = prefer)
        assertEquals(listOf(uhd), filtered.streams)
        assertEquals(0, filtered.preferredCount)
        assertEquals(all, filtered.facetBase)
    }

    @Test
    fun `the chip and line verdicts read each reason from the hidden set`() {
        val bitrate = StreamLimitVerdict(70.0, figureMbps = 60, source = null)
        val res = ResolutionVerdict(ReleaseResolutionP1080, fromTv = true)
        val view = StreamListView(hiddenVerdicts = mapOf("a" to HiddenStream(resolution = res), "b" to HiddenStream(bitrate)))
        assertEquals(bitrate, view.limitVerdict)
        assertEquals(res, view.resolutionVerdict)
        val resOnly = StreamListView(hiddenVerdicts = mapOf("a" to HiddenStream(resolution = res)))
        assertEquals(null, resOnly.limitVerdict)
        assertEquals(res, resOnly.resolutionVerdict)
    }
}
