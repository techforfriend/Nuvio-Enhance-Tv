package com.nuvio.tv.core.player

import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import com.nuvio.tv.core.stream.StreamLimitInputs
import com.nuvio.tv.core.stream.StreamLimitMode
import com.nuvio.tv.core.stream.StreamLimitSettings
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.FacetGroup
import com.nuvio.tv.core.stream.FacetValue
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.hiddenStreamKeys
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 51 L2b: the player's playing stream, binge exemption, lists and failover under the limit. */
class PlayerStreamLimitTest {

    private val manual40 = StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
    private val noTransport: () -> NetworkTransport = { error("the link is read only under NETWORK_TEST") }

    /** Over a 60-minute runtime: 60 GB = 133 Mbit/s (over 40), 6 GB = 13 (under). */
    private fun stream(
        name: String,
        gb: Long = 6,
        bingeGroup: String? = null,
        addon: String = "A",
        url: String = "https://host/$name.mkv",
        infoHash: String? = null
    ) = Stream(
        name = name, title = null, description = null, url = url, ytId = null,
        infoHash = infoHash, fileIdx = null, externalUrl = null,
        behaviorHints = bingeGroup?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = it, countryWhitelist = null, proxyHeaders = null)
        },
        addonName = addon, addonLogo = null,
        streamData = AioStreamData(size = gb * 1_000_000_000L, filename = null, cached = null, parsedFile = null)
    )

    /** The player state while [playing] plays, Prefer Binge Group on unless said otherwise. */
    private fun stateFor(playing: Stream, preferBingeGroup: Boolean = true, sourceList: List<Stream> = emptyList()) =
        PlayerUiState(
            currentStreamUrl = playing.url,
            currentStreamAddonName = playing.addonName,
            currentStreamInfoHash = playing.infoHash,
            runtimeMinutes = 60,
            streamAutoPlayPreferBingeGroupForNextEpisode = preferBingeGroup,
            sourceAllStreams = sourceList
        )

    private fun playingIn(streams: List<Stream>, state: PlayerUiState) =
        playingStreamLimit(streams, state, manual40, noTransport, episode = false)

    // -- the playing stream and the binge exemption (r1, R4-4) --

    @Test
    fun `an over-limit playing stream exempts its binge group only with Prefer Binge Group on`() {
        val big = stream("big", gb = 60, bingeGroup = "g1")
        assertEquals(
            PlayingStreamLimit(key = big.limitKey(), overLimit = true, exemptBingeGroup = "g1"),
            playingIn(listOf(big), stateFor(big))
        )
        assertNull(playingIn(listOf(big), stateFor(big, preferBingeGroup = false)).exemptBingeGroup)
    }

    @Test
    fun `a playing row from an untested domain is judged within its list`() {
        // Spec 59 N-10: the list's other row reaches real-debrid.com (40 Mbit/s, figure 32) through the alias.
        val table = ServiceTable(NetworkTransport.WIFI, testedAtMs = 1L, http2 = true, rows = listOf(NetworkRow(4, 16, 40.0)))
        val tested = StreamLimitInputs(
            StreamLimitSettings(StreamLimitMode.NETWORK_TEST),
            ServiceNetworkTables(tables = mapOf("real-debrid.com" to table), aliases = mapOf("example.net" to "real-debrid.com"))
        )
        fun on(name: String, url: String, gb: Long) = stream(name, gb = gb, bingeGroup = name, url = url).let {
            it.copy(streamData = it.streamData!!.copy(serviceId = "realdebrid"))
        }
        val viaAddon = on("addon", "https://aio.example.net/p/1.mkv", gb = 6)
        val storz = on("storz", "https://stremthru.example.xyz/torz/2.mkv", gb = 60)
        val playing = playingStreamLimit(listOf(viaAddon, storz), stateFor(storz), tested, { NetworkTransport.WIFI }, episode = false)
        assertEquals(PlayingStreamLimit(key = storz.limitKey(), overLimit = true, exemptBingeGroup = "storz"), playing)
    }

    @Test
    fun `a playing stream within the limit exempts nothing`() {
        val small = stream("small", bingeGroup = "g1")
        assertEquals(PlayingStreamLimit(key = small.limitKey(), overLimit = false), playingIn(listOf(small), stateFor(small)))
    }

    @Test
    fun `a playing stream not in the list is no key and no exemption`() {
        val big = stream("big", gb = 60, bingeGroup = "g1")
        assertEquals(PlayingStreamLimit(), playingIn(listOf(stream("other")), stateFor(big)))
        assertEquals(PlayingStreamLimit(), playingIn(emptyList(), stateFor(big)))
    }

    @Test
    fun `of two services' copies of one torrent file, the playing key is the copy that plays`() {
        val rd = stream("f", gb = 60, url = "https://real-debrid.com/d/f.mkv", infoHash = "abc")
        val pm = stream("f", gb = 60, url = "https://premiumize.me/d/f.mkv", infoHash = "abc")
        val list = listOf(rd, pm)
        val playing = playingIn(list, stateFor(pm, sourceList = list))
        assertEquals(pm.limitKey(), playing.key)
        val hidden = sourceListHiddenVerdicts(stateFor(pm, sourceList = list), playing, manual40, noTransport, episode = false)
        assertEquals(setOf(rd.limitKey()), hidden.keys)
    }

    @Test
    fun `the exemption lets select take the next episode's same-group stream, within the limit it is hidden`() {
        val nextSame = stream("next-g1", gb = 60, bingeGroup = "g1")
        val nextOther = stream("next-g2", gb = 60, bingeGroup = "g2")
        val nextSmall = stream("next-small", bingeGroup = "g2")
        val candidates = listOf(nextSame, nextOther, nextSmall)
        fun pick(exempt: String?): Stream? {
            val hidden = hiddenStreamKeys(candidates, manual40, noTransport, 60, episode = true, exemptBingeGroup = exempt)
            return AutoPlaySelection.select(candidates, inputs(preferredBingeGroup = "g1", hiddenKeys = hidden))
        }
        val over = stream("now", gb = 60, bingeGroup = "g1")
        assertEquals(nextSame, pick(playingIn(listOf(over), stateFor(over)).exemptBingeGroup))
        val within = stream("now", bingeGroup = "g1")
        assertEquals(nextSmall, pick(playingIn(listOf(within), stateFor(within)).exemptBingeGroup))
    }

    private fun inputs(preferredBingeGroup: String?, hiddenKeys: Set<String>) = AutoPlaySelection.Inputs(
        mode = StreamAutoPlayMode.FIRST_STREAM,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("A"),
        selectedAddons = emptySet(),
        preferredBingeGroup = preferredBingeGroup,
        hiddenKeys = hiddenKeys
    )

    // -- the episode-streams list --

    @Test
    fun `the playing episode's own list never hides its playing stream, another episode's list does`() {
        val big = stream("big", gb = 60)
        val small = stream("small")
        val base = stateFor(big, preferBingeGroup = false).copy(
            currentVideoId = "tt1:1:2",
            episodeAllStreams = listOf(big, small),
            episodeStreamsRuntimeMinutes = 60
        )
        val ownList = base.copy(episodeStreamsForVideoId = "tt1:1:2")
        assertTrue(episodeListHiddenVerdicts(ownList, PlayingStreamLimit(), manual40, noTransport).isEmpty())
        val nextList = base.copy(episodeStreamsForVideoId = "tt1:1:3")
        assertEquals(setOf(big.limitKey()), episodeListHiddenVerdicts(nextList, PlayingStreamLimit(), manual40, noTransport).keys)
    }

    @Test
    fun `a picked addon whose chip leaves the row drops to All, one still listed stays`() {
        val a = stream("a", addon = "A")
        val state = PlayerUiState(
            sourceAllStreams = listOf(a),
            sourceAvailableAddons = listOf("A"),
            sourceChips = listOf(SourceChipItem("A", SourceChipStatus.SUCCESS), SourceChipItem("X", SourceChipStatus.ERROR)),
            sourceListSelection = StreamListSelection.Addon("X")
        ).withSourceList(emptyMap())
        val dropped = state.withSourceChips(listOf(SourceChipItem("A", SourceChipStatus.SUCCESS)))
        assertEquals(StreamListSelection.All, dropped.sourceListSelection)
        assertEquals(listOf(a), dropped.sourceListView.streams)
        val kept = state.copy(sourceListSelection = StreamListSelection.Addon("A")).withSourceChips(emptyList())
        assertEquals(StreamListSelection.Addon("A"), kept.sourceListSelection)
    }

    @Test
    fun `each player list narrows under its own filter only (Spec 65 P1)`() {
        val uhd = stream("Film.2012.2160p.WEB-DL.HEVC-GRP")
        val hd = stream("Film.2012.1080p.WEB-DL.H.264-GRP")
        val only4k = FacetFilter.NONE.toggled(FacetValue(FacetGroup.RESOLUTION, "P2160"))
        val lists = PlayerUiState(sourceAllStreams = listOf(uhd, hd), episodeAllStreams = listOf(uhd, hd))
        val source = lists.copy(sourceFacetFilter = only4k).withSourceList(emptyMap()).withEpisodeList(emptyMap())
        assertEquals(listOf(uhd), source.sourceListView.streams)
        assertEquals(only4k, source.sourceListView.filter)
        assertEquals(listOf(uhd, hd), source.episodeListView.streams)
        val episode = lists.copy(episodeFacetFilter = only4k).withSourceList(emptyMap()).withEpisodeList(emptyMap())
        assertEquals(listOf(uhd), episode.episodeListView.streams)
        assertEquals(listOf(uhd, hd), episode.sourceListView.streams)
    }

    // -- dead-source failover (R4-1, R5-8) --

    @Test
    fun `failover skips hidden streams and gives up when every remaining one is hidden`() {
        val dead = stream("dead")
        val big = stream("big", gb = 60)
        val small = stream("small")
        val state = stateFor(dead, sourceList = listOf(dead, big, small))
        val hidden = sourceListHiddenVerdicts(state, playingIn(state.sourceAllStreams, state), manual40, noTransport, false).keys
        assertEquals(small, nextLiveSource(state.sourceAllStreams, startIdx = 1, deadUrls = emptySet(), hiddenKeys = hidden, p2pEnabled = false))
        assertNull(nextLiveSource(listOf(dead, big), 1, emptySet(), hidden, p2pEnabled = false))
    }

    @Test
    fun `after switching away from an over-limit stream, failover does not pick it`() {
        // The user played "big" (over the limit, from the hidden view), then switched to "b", which died.
        val b = stream("b")
        val big = stream("big", gb = 60)
        val c = stream("c")
        val list = listOf(b, big, c)
        val deadB = setOf(b.url!!)
        // As the failover computes it: the playing identity as of now (b) makes big hidden again.
        val now = stateFor(b, sourceList = list)
        val hiddenNow = sourceListHiddenVerdicts(now, playingIn(list, now), manual40, noTransport, false).keys
        assertTrue(big.limitKey() in hiddenNow)
        assertEquals(c, nextLiveSource(list, startIdx = 1, deadUrls = deadB, hiddenKeys = hiddenNow, p2pEnabled = false))
        // A set kept from while big played would have let it through: why the failover never stores one.
        val stale = stateFor(big, sourceList = list)
        val hiddenStale = sourceListHiddenVerdicts(stale, playingIn(list, stale), manual40, noTransport, false).keys
        assertEquals(big, nextLiveSource(list, 1, deadB, hiddenStale, p2pEnabled = false))
    }

    // -- re-derive triggers (M3, R4-1); the collector itself has no controller harness --

    @Test
    fun `the trigger key moves with the runtime and the playing identity, not with other state`() {
        val base = PlayerUiState(currentStreamUrl = "https://host/a.mkv", currentStreamAddonName = "A", runtimeMinutes = 45)
        val key = streamLimitDeriveKey(base)
        assertNotEquals(key, streamLimitDeriveKey(base.copy(runtimeMinutes = 50)))
        assertNotEquals(key, streamLimitDeriveKey(base.copy(currentStreamUrl = "https://host/b.mkv")))
        assertNotEquals(key, streamLimitDeriveKey(base.copy(currentStreamAddonName = "B")))
        assertNotEquals(key, streamLimitDeriveKey(base.copy(currentStreamInfoHash = "abc", currentStreamFileIdx = 2)))
        assertNotEquals(key, streamLimitDeriveKey(base.copy(currentVideoId = "tt1:1:2")))
        assertNotEquals(key, streamLimitDeriveKey(base.copy(streamAutoPlayPreferBingeGroupForNextEpisode = true)))
        assertEquals(key, streamLimitDeriveKey(base.copy(isBuffering = false, showSourcesPanel = true)))
    }
    // -- Spec 65 P6: the show's preferred source in the player --

    @Test
    fun `both player lists lead with the preferred source's matches`() {
        val uhd = stream("Show.S01E03.2160p.WEB-FLUX")
        val fhd = stream("Show.S01E03.1080p.WEB-NTb")
        val preferred = com.nuvio.tv.core.stream.PreferredSource("A", com.nuvio.tv.domain.model.ReleaseResolution.P1080)
        val state = PlayerUiState(sourceAllStreams = listOf(uhd, fhd), episodeAllStreams = listOf(uhd, fhd), preferredSource = preferred)
            .withSourceList(emptyMap()).withEpisodeList(emptyMap())
        assertEquals(listOf(fhd, uhd), state.sourceListView.streams)
        assertEquals(1, state.sourceListView.preferredCount)
        assertEquals(listOf(fhd, uhd), state.episodeListView.streams)
        assertEquals(1, state.episodeListView.preferredCount)
        val none = state.copy(preferredSource = null).withSourceList(emptyMap())
        assertEquals(listOf(uhd, fhd), none.sourceListView.streams)
        assertEquals(0, none.sourceListView.preferredCount)
    }

    // D20: in MANUAL mode a preferred source alone makes the episode press select, and only a match plays.
    @Test
    fun `a preferred source lets a manual-mode episode press play its match and nothing else`() {
        fun manual(next: Boolean, binge: Boolean, fallback: Boolean = true) = com.nuvio.tv.data.local.PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = next,
            streamAutoPlayPreferBingeGroupForNextEpisode = binge,
            streamAutoPlayNextEpisodeFallbackEnabled = fallback
        )
        val off = manual(next = false, binge = false)
        assertEquals(ManualEpisodeSelect(autoSelect = false, matchOnly = false), manualEpisodeSelect(off, hasPreferred = false))
        assertEquals(ManualEpisodeSelect(autoSelect = true, matchOnly = true), manualEpisodeSelect(off, hasPreferred = true))
        // Where a setting already selects, a preferred source changes nothing.
        for (preferred in listOf(false, true)) {
            assertEquals(
                ManualEpisodeSelect(autoSelect = true, matchOnly = false),
                manualEpisodeSelect(manual(next = true, binge = false), preferred)
            )
            assertEquals(
                ManualEpisodeSelect(autoSelect = true, matchOnly = false),
                manualEpisodeSelect(manual(next = true, binge = false, fallback = false), preferred)
            )
            assertEquals(
                ManualEpisodeSelect(autoSelect = true, matchOnly = true),
                manualEpisodeSelect(manual(next = false, binge = true), preferred)
            )
            assertEquals(
                ManualEpisodeSelect(autoSelect = true, matchOnly = true),
                manualEpisodeSelect(manual(next = true, binge = true, fallback = false), preferred)
            )
            assertEquals(
                ManualEpisodeSelect(autoSelect = true, matchOnly = false),
                manualEpisodeSelect(manual(next = true, binge = true), preferred)
            )
            // Outside MANUAL the mode itself selects.
            assertEquals(
                ManualEpisodeSelect(autoSelect = false, matchOnly = false),
                manualEpisodeSelect(off.copy(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM), preferred)
            )
        }
    }
}
