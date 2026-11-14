package com.nuvio.tv.core.stream

import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.ContinuationTarget
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P6 (D16, D20): the source preferred for a show, its match, the list order and auto-play's step. */
class PreferredSourceTest {

    private fun stream(
        addon: String,
        filename: String?,
        group: String? = null,
        name: String? = addon,
        url: String? = "https://x/${addon.lowercase()}/${filename.hashCode()}/${group.hashCode()}",
        externalUrl: String? = null,
        languages: List<String>? = null
    ) = Stream(
        name = name, title = null, description = null, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null, filename = filename
        ),
        addonName = addon, addonLogo = null,
        streamData = languages?.let {
            AioStreamData(
                size = null, filename = filename, cached = true,
                parsedFile = AioParsedFile(
                    resolution = "1080p", quality = "WEB-DL", encode = "AVC", releaseGroup = "NTb",
                    visualTags = emptyList(), audioTags = emptyList(), audioChannels = emptyList(),
                    languages = it, editions = emptyList()
                )
            )
        }
    )

    // The row the viewer held OK on: episode 2, AIOStreams 1080p from NTb.
    private val held = stream("AIOStreams", "Cosmos.Laundromat.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", group = "aio|1080p|WEB-DL|NTb")
    private val preferred = PreferredSource.of(held)

    // Episode 3's list.
    private val uhd = stream("AIOStreams", "Cosmos.Laundromat.S01E03.2160p.WEB-DL.HDR10.HEVC-NTb.mkv", group = "aio|2160p|WEB-DL|NTb")
    private val next = stream("AIOStreams", "Cosmos.Laundromat.S01E03.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", group = "aio|1080p|WEB-DL|NTb")
    private val torrentio = stream("Torrentio", "Cosmos.Laundromat.S01E03.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", group = "torrentio|1080p")
    private val don = stream("AIOStreams", "Cosmos.Laundromat.S01E03.1080p.BluRay.DTS.x264-DON.mkv", group = "aio|1080p|BluRay|DON")
    private val episode3 = listOf(uhd, torrentio, don, next)

    @Test
    fun `preferring a row stores its addon, resolution, release-naming binge group and parsed release group`() {
        assertEquals(
            PreferredSource("AIOStreams", ReleaseResolution.P1080, bingeGroup = "aio|1080p|WEB-DL|NTb", releaseGroup = "ntb"),
            preferred
        )
        // A gateway's bare group names no release (Spec 47 D2): not an anchor.
        val bare = PreferredSource.of(stream("AIOStreams", "Dora.S07E01.1080p.mkv", group = "aio123| "))
        assertNull(bare.bingeGroup)
        assertNull(bare.releaseGroup)
    }

    @Test
    fun `a row matches on addon, resolution and the binge group`() {
        assertTrue(preferred.matches(next))
        assertFalse("another resolution", preferred.matches(uhd))
        assertFalse("another addon", preferred.matches(torrentio))
        assertFalse("another release", preferred.matches(don))
        // The group decides, not the file name: the same name under another group is another source.
        assertFalse(preferred.matches(next.copy(behaviorHints = next.behaviorHints?.copy(bingeGroup = "aio|1080p|WEB-DL|FLUX"))))
    }

    @Test
    fun `without a binge group the parsed release group is the anchor`() {
        val plain = PreferredSource.of(stream("Torrentio", "Show.S01E02.1080p.WEB-DL.H264-NTb.mkv"))
        assertEquals("ntb", plain.releaseGroup)
        assertTrue(plain.matches(stream("Torrentio", "Show.S01E03.1080p.WEB-DL.H264-NTb.mkv")))
        assertFalse(plain.matches(stream("Torrentio", "Show.S01E03.1080p.WEB-DL.H264-FLUX.mkv")))
        assertFalse("a row that names no group", plain.matches(stream("Torrentio", "Show S01E03 1080p")))
        assertFalse(plain.matches(stream("Torrentio", "Show.S01E03.2160p.WEB-DL.H265-NTb.mkv")))
    }

    @Test
    fun `a row that names no release is preferred as its addon at its resolution`() {
        val loose = PreferredSource.of(stream("Torrentio", "Show S01E02 1080p"))
        assertNull(loose.bingeGroup)
        assertNull(loose.releaseGroup)
        val first = stream("Torrentio", "Show.S01E03.1080p.WEB-DL.H264-FLUX.mkv")
        val second = stream("Torrentio", "Show.S01E03.1080p.BluRay.x264-DON.mkv")
        val list = listOf(stream("Torrentio", "Show.S01E03.2160p.mkv"), stream("Comet", "Show.S01E03.1080p.mkv"), first, second)
        // List order among the matches: no release was named, so there is no dub to tell apart.
        assertEquals(listOf(first, second), loose.matchesIn(list))
    }

    @Test
    fun `among the matches of one release the preferred row's languages go first`() {
        val group = "aio|1080p|WEB-DL|NTb"
        val original = PreferredSource.of(stream("AIOStreams", "S.S01E02.1080p-NTb.mkv", group = group, languages = listOf("English")))
        val dub = stream("AIOStreams", "S.S01E03.1080p.FRENCH-NTb.mkv", group = group, languages = listOf("French"))
        val english = stream("AIOStreams", "S.S01E03.1080p-NTb.mkv", group = group, languages = listOf("English"))
        assertEquals(listOf(english, dub), original.matchesIn(listOf(dub, english)))
        // None with those languages: list order, the match still stands.
        assertEquals(listOf(dub), original.matchesIn(listOf(dub)))
        val multi = stream("AIOStreams", "S.S01E03.1080p.MULTI-NTb.mkv", group = group, languages = listOf("French", "English"))
        assertEquals(listOf(dub, multi), original.matchesIn(listOf(dub, multi)))
    }

    @Test
    fun `the list shows the matches first and keeps the rest in order`() {
        assertEquals(listOf(next, uhd, torrentio, don) to 1, preferred.preferredFirst(episode3))
        // No match: the same list, nothing counted.
        val none = listOf(uhd, torrentio, don)
        val (rows, count) = preferred.preferredFirst(none)
        assertTrue(rows === none)
        assertEquals(0, count)
    }

    @Test
    fun `the stored names are the format`() {
        val json = Json.encodeToString(PreferredSource.serializer(), preferred.copy(languages = listOf("English")))
        assertEquals(
            """{"addonName":"AIOStreams","resolution":"P1080","bingeGroup":"aio|1080p|WEB-DL|NTb","releaseGroup":"ntb","languages":["English"]}""",
            json
        )
    }

    private fun inputs(
        mode: StreamAutoPlayMode,
        preferred: PreferredSource? = this.preferred,
        bingeGroup: String? = null,
        continuation: ContinuationTarget? = null,
        hiddenKeys: Set<String> = emptySet(),
        identity: ContentIdentity? = null,
        failures: FailureSnapshot = FailureSnapshot.EMPTY,
        source: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
        selectedAddons: Set<String> = emptySet()
    ) = AutoPlaySelection.Inputs(
        mode = mode,
        regexPattern = "",
        source = source,
        installedAddonNames = setOf("Torrentio"),
        selectedAddons = selectedAddons,
        preferredBingeGroup = bingeGroup,
        failures = failures,
        hiddenKeys = hiddenKeys,
        identity = identity,
        continuation = continuation,
        preferred = preferred
    )

    // D20: preferred > continuation (P7) > binge group, in every mode.
    @Test
    fun `auto-play takes the preferred source before the playing source and the binge group`() {
        val playing = stream("AIOStreams", "Cosmos.Laundromat.S01E02.2160p.WEB-DL.HDR10.HEVC-NTb.mkv", group = "aio|2160p|WEB-DL|NTb")
        val continuation = ContinuationTarget.of(playing)
        assertEquals(uhd, AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.FIRST_STREAM, preferred = null, continuation = continuation)))
        assertEquals(next, AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.FIRST_STREAM, continuation = continuation)))
        assertEquals(next, AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.QUALITY_RANK, bingeGroup = "torrentio|1080p")))
    }

    // D20, D9: it plays in MANUAL mode and counts as the binge match in the early check.
    @Test
    fun `a preferred match plays in manual mode and in the binge-only check`() {
        assertEquals(next, AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.MANUAL)))
        assertEquals(next, AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.QUALITY_RANK), bingeGroupOnly = true))
        // No match: manual shows the picker, the early check waits, a mode ranks as usual.
        val without = listOf(uhd, torrentio, don)
        assertNull(AutoPlaySelection.select(without, inputs(StreamAutoPlayMode.MANUAL)))
        assertNull(AutoPlaySelection.select(without, inputs(StreamAutoPlayMode.FIRST_STREAM), bingeGroupOnly = true))
        assertEquals(uhd, AutoPlaySelection.select(without, inputs(StreamAutoPlayMode.FIRST_STREAM)))
    }

    // A ranking bonus, not a lock: every guard that keeps a stream from auto-play still does.
    @Test
    fun `the guards and the addon scope still win over a preferred match`() {
        assertEquals(
            "hidden by a limit",
            uhd,
            AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.FIRST_STREAM, hiddenKeys = setOf(next.limitKey())))
        )
        val wrongEpisode = ContentIdentity(title = "Cosmos Laundromat", year = null, season = 1, episode = 4, film = false)
        assertNull("another episode", AutoPlaySelection.select(listOf(next), inputs(StreamAutoPlayMode.MANUAL, identity = wrongEpisode)))
        assertNull(
            "installed addons only",
            AutoPlaySelection.select(episode3, inputs(StreamAutoPlayMode.MANUAL, source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        )
        assertNull(
            "outside the addon allow-list",
            AutoPlaySelection.select(
                listOf(torrentio.copy(addonName = "AIOStreams"), next).map { it.copy(addonName = "Torrentio") },
                inputs(StreamAutoPlayMode.MANUAL, preferred = PreferredSource.of(held.copy(addonName = "Torrentio")), selectedAddons = setOf("Comet"))
            )
        )
        // A binge group that names only the quality: the stored release group still has to match.
        val quality = PreferredSource.of(stream("Torrentio", "S.S01E02.1080p.WEB-NTb.mkv", group = "torrentio|1080p"))
        assertFalse(quality.matches(stream("Torrentio", "S.S01E03.1080p.WEB-FLUX.mkv", group = "torrentio|1080p")))
        assertTrue(quality.matches(stream("Torrentio", "S.S01E03.1080p.WEB-NTb.mkv", group = "torrentio|1080p")))
        val external = next.copy(url = null, externalUrl = "https://example.com/page")
        assertNull("not playable", AutoPlaySelection.select(listOf(external), inputs(StreamAutoPlayMode.MANUAL)))
    }
}
