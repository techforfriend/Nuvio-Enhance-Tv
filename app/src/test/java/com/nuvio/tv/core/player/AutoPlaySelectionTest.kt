package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ContentIdentity
import com.nuvio.tv.core.stream.FailureSnapshot
import com.nuvio.tv.core.stream.FailedPlayback
import com.nuvio.tv.core.stream.FailedReleaseStorage
import com.nuvio.tv.core.stream.FailedReleases
import com.nuvio.tv.core.stream.FailureReason
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shared inputs the stream screen and the prefetch ranking both build (B7). */
class AutoPlaySelectionTest {

    private val lookups = mutableListOf<String>()
    private val cache: suspend (String) -> String? = { id -> lookups += id; "grp-$id" }

    @Test
    fun `persisted binge group needs both prefer and reuse`() = runTest {
        val both = PlayerSettings(
            streamAutoPlayPreferBingeGroupForNextEpisode = true,
            streamAutoPlayReuseBingeGroup = true
        )
        assertEquals("grp-tt1", AutoPlaySelection.persistedBingeGroup(both, "tt1", cache))
        assertNull(AutoPlaySelection.persistedBingeGroup(both, null, cache))

        val noReuse = both.copy(streamAutoPlayReuseBingeGroup = false)
        val noPrefer = both.copy(streamAutoPlayPreferBingeGroupForNextEpisode = false)
        assertNull(AutoPlaySelection.persistedBingeGroup(noReuse, "tt2", cache))
        assertNull(AutoPlaySelection.persistedBingeGroup(noPrefer, "tt3", cache))
        assertEquals("the cache is read only when both are on", listOf("tt1"), lookups)
    }

    @Test
    fun `inputs from settings carry every selection setting`() {
        val settings = PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            streamAutoPlayRegex = "2160p",
            streamAutoPlaySource = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            streamAutoPlaySelectedAddons = setOf("B")
        )
        val failures = FailureSnapshot(excluded = setOf("u:x"), demoted = emptySet())
        val inputs = AutoPlaySelection.Inputs.fromSettings(settings, listOf("A", "B", "A"), "grp", failures, setOf("k"))
        assertEquals(
            AutoPlaySelection.Inputs(
                mode = StreamAutoPlayMode.REGEX_MATCH,
                regexPattern = "2160p",
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonNames = setOf("A", "B"),
                selectedAddons = setOf("B"),
                preferredBingeGroup = "grp",
                failures = failures,
                hiddenKeys = setOf("k")
            ),
            inputs
        )
    }

    /** A failure store holding [reason] for each of [streams]. */
    private fun failuresOf(reason: FailureReason, vararg streams: Stream): FailureSnapshot {
        val store = FailedReleases(
            object : FailedReleaseStorage {
                override fun load(): String? = null
                override fun save(value: String) = Unit
            },
            kotlin.time.Clock.System
        )
        streams.forEach { store.record(reason, FailedPlayback(urls = listOf(it.getStreamUrl()), listEntry = it)) }
        return store.snapshot()
    }

    // Spec 42 P2 (F-2) / Spec 65 P5: a stream rejected as the wrong file is never auto-selected
    // again, even when it ranks first or matches the remembered binge group.
    @Test
    fun `select skips wrong files`() {
        val badUrl = "https://x/wrong-film.m3u8"
        val bad = stream(url = badUrl, name = "wrong")
        val good = stream(url = "https://x/right.mkv", name = "right")
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        assertEquals(bad, AutoPlaySelection.select(listOf(bad, good), inputs))
        val wrong = failuresOf(FailureReason.WRONG_FILE, bad)
        assertEquals(good, AutoPlaySelection.select(listOf(bad, good), inputs.copy(failures = wrong)))
        assertNull(AutoPlaySelection.select(listOf(bad), inputs.copy(failures = wrong)))
        // Also as the remembered binge group's member, and by its release on another link.
        val group = StreamBehaviorHints(notWebReady = null, bingeGroup = "grp|2160p", countryWhitelist = null, proxyHeaders = null)
        val torrent = stream(url = "https://x/t.mkv", infoHash = "abcdef", fileIdx = 1).copy(behaviorHints = group)
        val sameFileElsewhere = stream(url = "https://y/t.mkv", infoHash = "abcdef", fileIdx = 1).copy(behaviorHints = group)
        val other = stream(url = "https://x/o.mkv").copy(behaviorHints = group)
        val torrentWrong = inputs.copy(failures = failuresOf(FailureReason.WRONG_FILE, torrent), preferredBingeGroup = "grp|2160p")
        assertEquals(other, AutoPlaySelection.select(listOf(torrent, sameFileElsewhere, other), torrentWrong))
        // Spec 51 P5: a stream the limit hides is skipped the same way, by its limit key.
        assertEquals(good, AutoPlaySelection.select(listOf(bad, good), inputs.copy(hiddenKeys = setOf(bad.limitKey()))))
        assertNull(AutoPlaySelection.select(listOf(bad), inputs.copy(hiddenKeys = setOf(bad.limitKey()))))
    }

    // Spec 65 P3: a candidate the identity guard rejects is never auto-selected, binge-group
    // matches included; an unnamed stream passes (D2); no identity = no check.
    @Test
    fun `select skips streams the identity guard rejects`() {
        fun named(url: String, filename: String?, group: String? = null) = stream(url = url).copy(
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null, filename = filename
            )
        )
        val wrong = named("https://x/a.mkv", "Daredevil.S02E07.2160p.NF.WEB-DL.mkv", group = "grp|2160p")
        val right = named("https://x/b.mkv", "Daredevil.S02E06.2160p.NF.WEB-DL.mkv")
        val unnamed = named("https://x/c.mkv", null)
        val identity = ContentIdentity(title = "Daredevil", year = 2015, season = 2, episode = 6, film = false)
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        assertEquals(wrong, AutoPlaySelection.select(listOf(wrong, right), inputs))
        assertEquals(right, AutoPlaySelection.select(listOf(wrong, right), inputs.copy(identity = identity)))
        assertEquals(
            right,
            AutoPlaySelection.select(listOf(wrong, right), inputs.copy(identity = identity, preferredBingeGroup = "grp|2160p"))
        )
        assertEquals(unnamed, AutoPlaySelection.select(listOf(wrong, unnamed, right), inputs.copy(identity = identity)))
        // An unnamed stream passes (D2).
        assertEquals(unnamed, AutoPlaySelection.select(listOf(wrong, unnamed), inputs.copy(identity = identity)))
        // D7: no "every candidate rejected" escape; all rejected = the picker.
        assertNull(AutoPlaySelection.select(listOf(wrong), inputs.copy(identity = identity)))
    }

    // Spec 65 D4: fromSettings and the constructor derive the film flag from the identity.
    @Test
    fun `the film flag follows the identity`() {
        val film = ContentIdentity(title = "Dune", year = 2021, season = null, episode = null, film = true)
        assertEquals(true, AutoPlaySelection.Inputs.fromSettings(PlayerSettings(), emptyList(), null, FailureSnapshot.EMPTY, emptySet(), film).filmSizeFloors)
        assertEquals(false, AutoPlaySelection.Inputs.fromSettings(PlayerSettings(), emptyList(), null, FailureSnapshot.EMPTY, emptySet(), film.copy(film = false)).filmSizeFloors)
        assertEquals(false, AutoPlaySelection.Inputs.fromSettings(PlayerSettings(), emptyList(), null, FailureSnapshot.EMPTY, emptySet()).filmSizeFloors)
    }

    // Spec 65 D4: the film size floors apply only when the identity says film.
    @Test
    fun `a film identity applies the film size floors`() {
        fun sized(url: String, filename: String, mib: Long) = stream(url = url).copy(
            name = filename,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null,
                videoSize = mib * 1024L * 1024L, filename = filename
            )
        )
        val small4k = sized("https://x/a.mkv", "Dune.2021.2160p.WEB-DL.HEVC-GRP.mkv", 1200)
        val real1080 = sized("https://x/b.mkv", "Dune.2021.1080p.BluRay.x264-GRP.mkv", 12_000)
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        val film = ContentIdentity(title = "Dune", year = 2021, season = null, episode = null, film = true)
        assertEquals(small4k, AutoPlaySelection.select(listOf(real1080, small4k), inputs))
        assertEquals(real1080, AutoPlaySelection.select(listOf(real1080, small4k), inputs.copy(identity = film, filmSizeFloors = true)))
        assertEquals(small4k, AutoPlaySelection.select(listOf(real1080, small4k), inputs.copy(identity = film.copy(film = false), filmSizeFloors = false)))
        // 1080p film floor 600 MiB, exact bound.
        val at599 = sized("https://x/c.mkv", "Dune.2021.1080p.WEB-DL.x264-GRP.mkv", 599)
        val at600 = sized("https://x/d.mkv", "Dune.2021.1080p.WEB-DL.x264-GRP.mkv", 600)
        val sd = sized("https://x/e.mkv", "Dune.2021.720p.WEB-DL.x264-GRP.mkv", 3000)
        assertEquals(sd, AutoPlaySelection.select(listOf(sd, at599), inputs.copy(identity = film, filmSizeFloors = true)))
        assertEquals(at600, AutoPlaySelection.select(listOf(sd, at600), inputs.copy(identity = film, filmSizeFloors = true)))
    }

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        name: String? = "n"
    ) = Stream(
        name = name, title = "t", description = "d", url = url, ytId = null,
        infoHash = infoHash, fileIdx = fileIdx, externalUrl = null, behaviorHints = null,
        addonName = "Addon", addonLogo = null
    )

    @Test
    fun `badge merge key ignores badges and keys torrents by lowercase hash and file`() {
        val plain = stream(url = "https://x/a.mkv")
        val badged = plain.copy(badges = listOf(StreamBadge(name = "4K")))
        assertEquals(plain.badgeMergeKey(), badged.badgeMergeKey())

        assertEquals(
            stream(infoHash = "ABC", fileIdx = 2).badgeMergeKey(),
            stream(infoHash = "abc", fileIdx = 2).badgeMergeKey()
        )
        assertNotEquals(
            stream(infoHash = "abc", fileIdx = 1).badgeMergeKey(),
            stream(infoHash = "abc", fileIdx = 2).badgeMergeKey()
        )
        // No playable url: informational entries stay distinct by name.
        assertNotEquals(stream(name = "a").badgeMergeKey(), stream(name = "b").badgeMergeKey())
        // clientResolve without a url keys by its raw filename, else its hash; name is ignored.
        fun resolve(filename: String?, hash: String?) = StreamClientResolve(
            type = null, infoHash = hash, fileIdx = null, sources = null, torrentName = null,
            filename = null, mediaType = null, title = null, season = null, episode = null,
            service = null,
            stream = StreamClientResolveStream(
                StreamClientResolveRaw(torrentName = null, filename = filename, size = null, network = null, parsed = null)
            )
        )
        assertEquals(
            stream(name = "a").copy(clientResolve = resolve("f.mkv", "h1")).badgeMergeKey(),
            stream(name = "b").copy(clientResolve = resolve("f.mkv", "h2")).badgeMergeKey()
        )
        assertEquals("Addon|h1", stream().copy(clientResolve = resolve(null, "h1")).badgeMergeKey())
    }

    // Spec 65 P5 (D8): a link-side failure ranks the stream last in every mode, never out.
    @Test
    fun `select ranks recently failed streams last in every mode`() {
        fun hinted(url: String, name: String, group: String? = null) = stream(url = url, name = name).copy(
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null
            )
        )
        val failed4k = hinted("https://x/a.mkv", "2160p", group = "grp|2160p")
        val ok1080 = hinted("https://x/b.mkv", "1080p", group = "grp|2160p")
        val ok720 = hinted("https://x/c.mkv", "720p")
        val streams = listOf(failed4k, ok1080, ok720)
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        val failed = inputs.copy(failures = failuresOf(FailureReason.DEAD_LINK, failed4k))
        assertEquals(failed4k, AutoPlaySelection.select(streams, inputs))
        assertEquals(ok1080, AutoPlaySelection.select(streams, failed))
        assertEquals(failed4k, AutoPlaySelection.select(streams, inputs.copy(mode = StreamAutoPlayMode.QUALITY_RANK)))
        assertEquals(ok1080, AutoPlaySelection.select(streams, failed.copy(mode = StreamAutoPlayMode.QUALITY_RANK)))
        assertEquals(ok1080, AutoPlaySelection.select(streams, failed.copy(mode = StreamAutoPlayMode.REGEX_MATCH, regexPattern = "p")))
        // The binge group still wins over the mode; its other member comes first.
        assertEquals(ok1080, AutoPlaySelection.select(streams, failed.copy(mode = StreamAutoPlayMode.MANUAL, preferredBingeGroup = "grp|2160p")))
        // Only failed streams left: still picked (ranked last, not out).
        assertEquals(failed4k, AutoPlaySelection.select(listOf(failed4k), failed))
        assertEquals(failed4k, AutoPlaySelection.select(listOf(failed4k), failed.copy(mode = StreamAutoPlayMode.QUALITY_RANK)))
    }

    // Spec 86 R7 (Spec 84 A10): a row AIOStreams reports uncached on its debrid service ranks
    // behind the rest in every mode, like a recently failed link, and is never removed; a row that
    // reports nothing (any other addon) is not demoted.
    @Test
    fun `select ranks rows reported uncached last in every mode`() {
        fun aio(url: String, name: String, cached: Boolean?, group: String? = null) = stream(url = url, name = name).copy(
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null
            ),
            streamData = AioStreamData(size = null, filename = null, cached = cached, parsedFile = null)
        )
        val uncached4k = aio("https://x/a.mkv", "2160p", cached = false, group = "grp|2160p")
        val cached1080 = aio("https://x/b.mkv", "1080p", cached = true, group = "grp|2160p")
        val unreported720 = aio("https://x/c.mkv", "720p", cached = null)
        val streams = listOf(uncached4k, cached1080, unreported720)
        val inputs = AutoPlaySelection.Inputs(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            preferredBingeGroup = null,
            hiddenKeys = emptySet()
        )
        assertEquals(cached1080, AutoPlaySelection.select(streams, inputs))
        assertEquals(cached1080, AutoPlaySelection.select(streams, inputs.copy(mode = StreamAutoPlayMode.QUALITY_RANK)))
        assertEquals(cached1080, AutoPlaySelection.select(streams, inputs.copy(mode = StreamAutoPlayMode.REGEX_MATCH, regexPattern = "p")))
        assertEquals(
            cached1080,
            AutoPlaySelection.select(streams, inputs.copy(mode = StreamAutoPlayMode.MANUAL, preferredBingeGroup = "grp|2160p"))
        )
        // Beside a recently failed row: both are demoted, the rest plays first.
        val failed = inputs.copy(failures = failuresOf(FailureReason.DEAD_LINK, cached1080))
        assertEquals(unreported720, AutoPlaySelection.select(streams, failed))
        // The preferred source (a hand choice) still beats a cached non-match; among its matches the
        // cached one goes first.
        val preferred = PreferredSource.of(uncached4k)
        assertEquals(uncached4k, AutoPlaySelection.select(streams, inputs.copy(preferred = preferred)))
        val cached4k = aio("https://x/d.mkv", "2160p", cached = true, group = "grp|2160p")
        assertEquals(cached4k, AutoPlaySelection.select(listOf(uncached4k, cached4k, cached1080), inputs.copy(preferred = preferred)))
        // Only uncached rows left: still picked (behind the rest, never out).
        assertEquals(uncached4k, AutoPlaySelection.select(listOf(uncached4k), inputs))
        assertEquals(uncached4k, AutoPlaySelection.select(listOf(uncached4k), inputs.copy(mode = StreamAutoPlayMode.QUALITY_RANK)))
    }
}
