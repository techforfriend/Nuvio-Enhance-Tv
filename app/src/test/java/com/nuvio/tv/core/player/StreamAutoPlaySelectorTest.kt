package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {

    private val baseInputs = AutoPlaySelection.Inputs(
        mode = StreamAutoPlayMode.FIRST_STREAM,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("AddonA", "AddonB"),
        selectedAddons = emptySet(),
        preferredBingeGroup = null,
        hiddenKeys = emptySet()
    )

    @Test
    fun `AutoPlaySelection matches the argument set the stream screen used to pass`() {
        val first = stream(addonName = "AddonA", url = "https://example.com/a.mkv", name = "1080p")
        val second = stream(addonName = "AddonB", url = "https://example.com/b.mkv", name = "720p")
        val streams = listOf(first, second)

        val viaSelector = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = baseInputs.mode,
            regexPattern = baseInputs.regexPattern,
            source = baseInputs.source,
            installedAddonNames = baseInputs.installedAddonNames,
            selectedAddons = baseInputs.selectedAddons,
            preferredBingeGroup = baseInputs.preferredBingeGroup,
            preferBingeGroupInSelection = baseInputs.preferredBingeGroup != null
        )
        val viaExtraction = AutoPlaySelection.select(
            streams = streams,
            inputs = baseInputs
        )

        assertEquals(viaSelector, viaExtraction)
        assertEquals(first, viaExtraction)
    }

    @Test
    fun `AutoPlaySelection derives binge preference from the inputs`() {
        val other = stream(
            addonName = "AddonA",
            url = "https://example.com/other.mkv",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.mkv",
            bingeGroup = "same-group"
        )

        // preferredBingeGroup set -> preferBingeGroupInSelection is derived true,
        // and a binge match outranks the mode even in MANUAL.
        val withGroup = AutoPlaySelection.select(
            streams = listOf(other, preferred),
            inputs = baseInputs.copy(
                mode = StreamAutoPlayMode.MANUAL,
                preferredBingeGroup = "same-group"
            )
        )
        assertEquals(preferred, withGroup)

        // preferredBingeGroup null -> derived false, MANUAL yields the picker.
        val withoutGroup = AutoPlaySelection.select(
            streams = listOf(other, preferred),
            inputs = baseInputs.copy(mode = StreamAutoPlayMode.MANUAL)
        )
        assertNull(withoutGroup)
    }

    @Test
    fun `AutoPlaySelection bingeGroupOnly does not fall back to the mode`() {
        val only = stream(
            addonName = "AddonA",
            url = "https://example.com/only.mkv",
            bingeGroup = "other-group"
        )

        val eager = AutoPlaySelection.select(
            streams = listOf(only),
            inputs = baseInputs.copy(preferredBingeGroup = "missing-group"),
            bingeGroupOnly = true
        )
        assertNull(eager)

        // Same inputs without the flag fall back to FIRST_STREAM.
        val fallback = AutoPlaySelection.select(
            streams = listOf(only),
            inputs = baseInputs.copy(preferredBingeGroup = "missing-group")
        )
        assertEquals(only, fallback)
    }

    @Test
    fun `a nameless binge group is ignored and the mode decides (Spec 47 D2)`() {
        val nameless = "com.aiostreams.viren070|"
        val english = stream(
            addonName = "AddonA",
            url = "https://example.com/english.mkv",
            bingeGroup = "com.aiostreams.viren070|1080p|WEB-DL"
        )
        val dub = stream(
            addonName = "AddonB",
            url = "https://example.com/dub.mp4",
            bingeGroup = nameless
        )
        val streams = listOf(english, dub)

        // FIRST_STREAM: no pin to the nameless row; the mode's pick wins.
        assertEquals(
            english,
            AutoPlaySelection.select(streams, baseInputs.copy(preferredBingeGroup = nameless))
        )
        // MANUAL: no binge match means the picker, as with no group at all.
        assertNull(
            AutoPlaySelection.select(
                streams,
                baseInputs.copy(mode = StreamAutoPlayMode.MANUAL, preferredBingeGroup = nameless)
            )
        )
        // Binge-only (MANUAL next episode, eager pre-timeout check): picker / keep waiting.
        assertNull(
            AutoPlaySelection.select(
                streams,
                baseInputs.copy(preferredBingeGroup = nameless),
                bingeGroupOnly = true
            )
        )
        // A named AIOStreams group still matches over the mode.
        assertEquals(
            english,
            AutoPlaySelection.select(
                listOf(dub, english),
                baseInputs.copy(preferredBingeGroup = "com.aiostreams.viren070|1080p|WEB-DL")
            )
        )
    }

    @Test
    fun `orderAddonStreams follows installed addon order and leaves other groups last`() {
        val other = addonStreams("Other")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")
        val unknown = addonStreams("Unknown")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(other, addonB, addonA, unknown),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(addonA, addonB, other, unknown), ordered)
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source scope and addon allow-list`() {
        val notSelectedAddonMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/addon-b-match.m3u8",
            bingeGroup = "same-group"
        )
        val nonAddonMatch = stream(
            addonName = "Torbox Instant",
            url = "https://example.com/instant-match.m3u8",
            bingeGroup = "same-group"
        )
        val selectedAddonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/addon-a-match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(notSelectedAddonMatch, nonAddonMatch, selectedAddonMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = setOf("AddonA"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(selectedAddonMatch, selected)
    }

    @Test
    fun `addon allow-list does not filter non-addon groups under all sources`() {
        val notSelectedAddon = stream(addonName = "AddonB", url = "https://example.com/b.mkv")
        val nonAddon = stream(addonName = "Torbox Instant", url = "https://example.com/instant.mkv")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(notSelectedAddon, nonAddon),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = setOf("AddonA")
        )

        assertEquals(nonAddon, selected)
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "unmatched-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `REGEX_MATCH degrades to include-only when the derived exclusion regex cannot compile`() {
        // "(?!x(\)))" extracts exclusion word "x\"; joining it into \\b(x\\)\\b leaves the
        // group unterminated (the trailing \\ escapes the joiner paren). The selector must
        // skip the exclusion filter, not crash (batch-1 C).
        val camStream = stream(
            addonName = "AddonA",
            url = "https://example.com/cam.mkv",
            name = "CAM copy"
        )
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(camStream),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "(?!x(\\)))CAM",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null
        )

        assertEquals(camStream, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode auto-selects matching bingeGroup when prefer enabled`() {
        // Binge-group continuity is intentionally allowed in MANUAL mode so
        // next-episode / resume can skip the picker when a group was locked in.
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(matched, selected)
    }

    @Test
    fun `orderAddonStreams keeps torrent groups in installed addon order`() {
        val regular = addonStreams(
            "AddonA",
            stream(
                addonName = "AddonA",
                url = "https://example.com/regular.m3u8"
            )
        )
        val torrent = addonStreams(
            "AddonB",
            stream(
                addonName = "AddonB",
                infoHash = "abc123"
            )
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            // Input deliberately reversed against installed order so this pins the sort.
            streams = listOf(torrent, regular),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(regular, torrent), ordered)
    }

    @Test
    fun `quality rank prefers lossless remux over lossy web-dl`() {
        val lossyWebDl = stream(
            addonName = "AddonA",
            url = "https://example.com/webdl.mp4",
            name = "Movie 2160p WEB-DL DD+ 5.1"
        )
        val losslessRemux = stream(
            addonName = "AddonB",
            url = "https://example.com/remux.mkv",
            name = "Movie 2160p Remux TrueHD Atmos"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(lossyWebDl, losslessRemux),
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet()
        )

        assertEquals(losslessRemux, selected)
    }

    @Test
    fun `quality rank resolution wins outright over audio`() {
        val fourKLossy = stream(
            addonName = "AddonA",
            url = "https://example.com/a.mkv",
            name = "Movie 2160p WEB-DL AAC"
        )
        val fullHdLossless = stream(
            addonName = "AddonA",
            url = "https://example.com/b.mkv",
            name = "Movie 1080p Remux TrueHD"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(fullHdLossless, fourKLossy),
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet()
        )

        assertEquals(fourKLossy, selected)
    }

    @Test
    fun `a null binge group makes preferBingeGroupInSelection inert`() {
        // playNextEpisode passed preferBingeGroupInSelection as the SETTING,
        // while AutoPlaySelection derives it from preferredBingeGroup != null.
        // Those disagree in exactly one case: the setting on with no binge
        // group known. This proves the disagreement cannot change the pick --
        // selectAutoPlayStream gates the binge branch on
        // targetBingeGroup.isNotEmpty(), which is false either way.
        //
        // It is the one non-obvious step in bringing playNextEpisode onto the
        // shared call site, so it lives here rather than in a commit message.
        val first = stream(addonName = "AddonA", url = "https://example.com/a.mkv", name = "1080p")
        val second = stream(addonName = "AddonB", url = "https://example.com/b.mkv", name = "720p")
        val streams = listOf(first, second)

        fun select(prefer: Boolean): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = baseInputs.mode,
            regexPattern = baseInputs.regexPattern,
            source = baseInputs.source,
            installedAddonNames = baseInputs.installedAddonNames,
            selectedAddons = baseInputs.selectedAddons,
            preferredBingeGroup = null,
            preferBingeGroupInSelection = prefer
        )

        assertEquals(select(true), select(false))
        assertEquals(first, select(true))
    }

    @Test
    fun `REGEX_MATCH sees the behaviorHints filename (T28 P34)`() {
        val base = stream(addonName = "AddonA", url = "https://example.com/resolve/abc", name = "AIO 2160p")
        val withFile = base.copy(
            behaviorHints = base.behaviorHints!!.copy(filename = "Film.2160p.UHD.BluRay.REMUX.TrueHD.mkv")
        )
        fun pick(candidate: Stream, pattern: String) = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(candidate),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = pattern,
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            preferBingeGroupInSelection = false
        )

        assertEquals(withFile, pick(withFile, "REMUX"))
        assertNull(pick(base, "REMUX"))
    }

    // Spec 86 R8: auto-play picks only what the Stream screen's press opens (hasTarget): a torrent
    // row needs a readable info hash, so a hashless magnet or torrent: link is passed over.
    @Test
    fun `a torrent row without a readable info hash is never auto-selected`() {
        val hashlessMagnet = stream(addonName = "AddonA", url = "magnet:?xt=urn:btmh:1220abcd", name = "v2 magnet")
        val hashlessTorrent = stream(addonName = "AddonA", url = "torrent://bad", name = "bad torrent")
        val hashed = stream(addonName = "AddonA", name = "torrent", infoHash = "0123456789abcdef0123456789abcdef01234567")
        val http = stream(addonName = "AddonB", url = "https://example.com/b.mkv", name = "http")
        fun pick(vararg candidates: Stream, mode: StreamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM) =
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = candidates.toList(),
                mode = mode,
                regexPattern = "torrent|magnet|http",
                source = StreamAutoPlaySource.ALL_SOURCES,
                installedAddonNames = setOf("AddonA", "AddonB"),
                selectedAddons = emptySet()
            )

        assertEquals(http, pick(hashlessMagnet, hashlessTorrent, http))
        assertEquals(http, pick(hashlessMagnet, hashlessTorrent, http, mode = StreamAutoPlayMode.REGEX_MATCH))
        assertEquals(hashed, pick(hashlessMagnet, hashed, http))
        assertNull(pick(hashlessMagnet, hashlessTorrent))
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        infoHash: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null
    )

    private fun addonStreams(
        addonName: String,
        vararg streams: Stream
    ): AddonStreams = AddonStreams(
        addonName = addonName,
        addonLogo = null,
        streams = streams.toList()
    )
}
