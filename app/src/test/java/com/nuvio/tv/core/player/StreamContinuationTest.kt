package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ContentIdentity
import com.nuvio.tv.core.stream.FailedReleases
import com.nuvio.tv.core.stream.FailureSnapshot
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P7 (D9): the next episode keeps the source that was playing. */
class StreamContinuationTest {

    private fun stream(
        addon: String,
        name: String?,
        title: String?,
        group: String? = null,
        description: String? = null,
        url: String? = "https://x/${addon.lowercase()}/${title.hashCode()}/${name.hashCode()}/${description.hashCode()}",
        externalUrl: String? = null,
        // The release name; the fixtures' titles are filenames, as Torrentio's are.
        filename: String? = title,
        streamData: AioStreamData? = null
    ) = Stream(
        name = name, title = title, description = description, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null, filename = filename
        ),
        addonName = addon, addonLogo = null, streamData = streamData
    )

    // What played: episode 5, Torrentio 4K DV from FLUX, original audio.
    private val tio = "Torrentio\n4k DV"
    private val playing = stream("Torrentio", tio, "Show.S01E05.2160p.WEB-DL.DV-FLUX.mkv", group = "torrentio|4k|DV")
    private val target = ContinuationTarget.of(playing)

    @Test
    fun `the target reads group, addon, own name, labelling and release facts`() {
        assertEquals("torrentio|4k|DV", target.bingeGroup)
        assertEquals("Torrentio", target.addonName)
        assertEquals("torrentio\n4k dv", target.name)
        assertTrue(target.labelled)
        assertEquals(ContinuationFacts(ReleaseResolution.P2160, emptySet(), "flux"), target.facts)
        // A name that is only the addon's is no name; a bare gateway group is no group and unlabelled.
        val bare = ContinuationTarget.of(stream("AIOStreams", "AIOStreams", "Dora.S07E01.mkv", group = "aio123|"))
        assertNull(bare.name)
        assertNull(bare.bingeGroup)
        assertFalse(bare.labelled)
    }

    @Test
    fun `step 1 the playing group wins with the same-language member first`() {
        val other = stream("Comet", "Comet 4K", "Show.S01E06.2160p.mkv")
        val dual = stream("Torrentio", tio, "Show.S01E06.2160p.DUAL.mkv", group = "torrentio|4k|DV")
        val original = stream("Torrentio", tio, "Show.S01E06.2160p-FLUX.mkv", group = "torrentio|4k|DV")
        assertEquals(original, StreamContinuation.pick(listOf(other, dual, original), target))
        // Only the dual-audio member in the group: the addon named the group, so still it.
        assertEquals(dual, StreamContinuation.pick(listOf(other, dual), target))
    }

    // D10: the same release = same addon, named release group and languages. A FRENCH or iTA row
    // reads as no language on the text path, like the original, so only the group keeps it out.
    @Test
    fun `step 2 keeps the same release and never takes a same-named dub`() {
        val otherAddon = stream("Comet", tio, "Show.S01E06.2160p.DV-FLUX.mkv")
        val multi = stream("Torrentio", tio, "Show.S01E06.2160p.MULTI-FLUX.mkv")
        val french = stream("Torrentio", tio, "Show.S01E06.FRENCH.2160p.WEB.DV-ZT.mkv")
        val italian = stream("Torrentio", tio, "Show.S01E06.iTA.ENG.2160p.WEB.DV-MeM.mkv")
        val otherRelease = stream("Torrentio", tio, "Show.S01E06.2160p.DV-NTb.mkv")
        val sameReleaseOtherName = stream("Torrentio", "Torrentio\n4k", "Show.S01E06.2160p.WEB-FLUX.mkv")
        val sameRelease = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv")
        val dubsFirst = listOf(otherAddon, multi, french, italian, otherRelease, sameReleaseOtherName, sameRelease)
        assertEquals(sameRelease, StreamContinuation.pick(dubsFirst, target))
        // No same-named row of the release: the same release at the same resolution.
        assertEquals(sameReleaseOtherName, StreamContinuation.pick(dubsFirst - sameRelease, target))
        // No row of the release at all: nothing, however many same-named rows the addon lists.
        assertNull(StreamContinuation.pick(listOf(otherAddon, multi, french, italian, otherRelease), target))
    }

    @Test
    fun `step 2 by resolution needs the same resolution and skips a gateway's unlabelled rows`() {
        val aioTarget = ContinuationTarget.of(stream("AIO", null, "Show.S01E05.2160p.WEB-FLUX.mkv", group = "aio|2160p|FLUX"))
        val hd = stream("AIO", null, "Show.S01E06.1080p.WEB-FLUX.mkv", group = "aio|1080p|FLUX")
        val unlabelled = stream("AIO", null, "Show.S01E06.2160p.WEB-FLUX.mkv", group = "aio|")
        val same = stream("AIO", null, "Show.S01E06.2160p.WEB-FLUX.mkv")
        assertEquals(same, StreamContinuation.pick(listOf(hd, unlabelled, same), aioTarget))
        assertNull(StreamContinuation.pick(listOf(hd, unlabelled), aioTarget))
        // No release group or (without a name) no resolution on the playing side: never a guess.
        val noGroup = ContinuationTarget.of(stream("AIO", null, "Show.S01E05.2160p.mkv"))
        assertNull(StreamContinuation.pick(listOf(stream("AIO", null, "Show.S01E06.2160p.mkv")), noGroup))
        val noResolution = ContinuationTarget.of(stream("AIO", null, "Show.S01E05.WEB-FLUX.mkv"))
        assertNull(StreamContinuation.pick(listOf(stream("AIO", null, "Show.S01E06.WEB-FLUX.mkv")), noResolution))
    }

    // D11: the group comes only from a parsed source; display text and mid-line words never anchor.
    @Test
    fun `step 2 never anchors on a group read from display text`() {
        // A pack: the episode file names no group, the pack title ends "S01-S05 Complete 1080p".
        val packTarget = ContinuationTarget.of(
            stream("Torrentio", "Torrentio\n1080p", title = "Breaking Bad S01-S05 Complete 1080p", filename = "Breaking Bad S01E05.mkv")
        )
        val italianPack = stream("Torrentio", "Torrentio\n1080p", title = "Breaking Bad S01-S05 Complete iTA 1080p", filename = "Breaking Bad S01E06.mkv")
        assertNull(packTarget.facts.releaseGroup)
        assertNull(StreamContinuation.pick(listOf(italianPack), packTarget))
        // "Show - S01E05 - The Beginning (...)": "The" is an episode-title word, not a group.
        val titled = ContinuationTarget.of(
            stream("Torrentio", "Torrentio\n1080p", "Show (2019) - S01E05 - The Beginning (1080p BluRay x265 10bit Tigole).mkv")
        )
        val frenchTitled = stream("Torrentio", "Torrentio\n1080p", "Show (2019) - S01E06 - The Return (1080p WEB x265 FRENCH).mkv")
        assertNull(titled.facts.releaseGroup)
        assertNull(StreamContinuation.pick(listOf(frenchTitled), titled))
    }

    @Test
    fun `the parsed group comes from AIOStreams, a leading bracket or a terminal hyphen token`() {
        fun groupOf(stream: Stream) = ContinuationFacts.of(stream).releaseGroup
        val aio = AioStreamData(
            size = null, filename = null, cached = null,
            parsedFile = AioParsedFile(
                resolution = "2160p", quality = null, encode = null, releaseGroup = "FLUX",
                visualTags = emptyList(), audioTags = emptyList(), audioChannels = emptyList(),
                languages = emptyList(), editions = emptyList()
            )
        )
        assertEquals("flux", groupOf(stream("AIO", null, "Show.S01E05.2160p-NTb.mkv", streamData = aio)))
        // The AIOStreams parse is final: no group there = none, whatever the filename says.
        assertNull(groupOf(stream("AIO", null, "Show.S01E05.2160p-NTb.mkv", streamData = aio.copy(parsedFile = aio.parsedFile?.copy(releaseGroup = null)))))
        assertEquals("subsplease", groupOf(stream("Nyaa", null, "[SubsPlease] Show - 05 (1080p) [ABCD1234].mkv")))
        assertEquals("ntb", groupOf(stream("Torrentio", tio, "Show.S01E05.2160p.WEB.DV-NTb.mkv")))
        assertNull(groupOf(stream("Torrentio", tio, "Show.S01E05.2160p.WEB-DL.mkv")))
        assertNull(groupOf(stream("Torrentio", tio, "Show.Complete.Series.S01-S05")))
        assertNull(groupOf(stream("Torrentio", tio, "Show.S01E05.1080p-2019.mkv")))
        // Codec, source, service and Usenet repost words are never a group.
        listOf("x265", "HEVC", "10bit", "WEBRip", "AMZN", "Obfuscated", "xpost").forEach { tail ->
            assertNull(tail, groupOf(stream("Torrentio", tio, "Show.S01E05.1080p.WEB-$tail.mkv")))
        }
        // A dotted leading bracket is a site tag; a terminal group wins over any bracket.
        assertNull(groupOf(stream("Torrentio", tio, "[www.Torrent911.io] Show S01E05 1080p.mkv")))
        assertEquals("ntb", groupOf(stream("Torrentio", tio, "[HorribleRips] Show.S01E05.1080p-NTb.mkv")))
        // The resolver's parsed group first; else the torrent name when the filename names none.
        val resolver = StreamClientResolve(
            type = null, infoHash = null, fileIdx = null, sources = null, torrentName = "Show.S01.1080p.WEB-FLUX",
            filename = null, mediaType = null, title = null, season = null, episode = null, service = null,
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    torrentName = null, filename = null, size = null, network = null,
                    parsed = StreamClientResolveParsed(
                        rawTitle = null, parsedTitle = null, year = null, resolution = null, seasons = null,
                        episodes = null, quality = null, hdr = null, codec = null, audio = null, channels = null,
                        languages = null, group = "SiGMA", network = null, edition = null, duration = null,
                        bitDepth = null, extended = null, theatrical = null, remastered = null, unrated = null
                    )
                )
            )
        )
        val groupless = stream("Torrentio", tio, "Show S01E05.mkv")
        assertEquals("sigma", groupOf(groupless.copy(clientResolve = resolver)))
        val noParsedGroup = resolver.copy(stream = null)
        assertEquals("flux", groupOf(groupless.copy(clientResolve = noParsedGroup)))
        // Name, title and description are never read.
        assertNull(groupOf(stream("Torrentio", "Torrentio-FLUX", title = "Show S01E05-FLUX", description = "Show-FLUX", filename = null)))
    }

    // Spec 47 D2: a gateway's unlabelled rows share their group, name and resolution: no step 2.
    @Test
    fun `an unlabelled playing row gets no continuation`() {
        // Everything else step 2 compares matches (addon, name, parsed group, languages, a labelled
        // candidate): only the playing row's bare group keeps the dub out.
        val doraTarget = ContinuationTarget.of(stream("AIOStreams", "AIO 2160p", "Dora.S07E01.2160p-FLUX.mkv", group = "aio123|"))
        val frenchDub = stream("AIOStreams", "AIO 2160p", "Dora.S07E02.2160p-FLUX.mkv")
        assertNull(StreamContinuation.pick(listOf(frenchDub), doraTarget))
        val labelledTarget = ContinuationTarget.of(stream("AIOStreams", "AIO 2160p", "Dora.S07E01.2160p-FLUX.mkv"))
        assertEquals(frenchDub, StreamContinuation.pick(listOf(frenchDub), labelledTarget))
    }

    // As built at launch from the route: no title, the filename in the description slot.
    @Test
    fun `a launch-shaped target finds its source only with release facts and an addon`() {
        val next = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv")
        val launched = ContinuationTarget.of(
            stream("Torrentio", tio, title = null, description = "Torrentio 4K", filename = "Show.S01E05.2160p.WEB-DL.DV-FLUX.mkv")
        )
        assertEquals(next, StreamContinuation.pick(listOf(stream("Comet", "Comet", "x"), next), launched))
        // No filename in the route: no release group, so no step 2.
        val bare = ContinuationTarget.of(stream("Torrentio", tio, title = null))
        assertNull(StreamContinuation.pick(listOf(next), bare))
        // A cached-link launch may carry no addon name.
        val noAddon = ContinuationTarget.of(stream("", tio, title = null, filename = "Show.S01E05.2160p.DV-FLUX.mkv"))
        assertNull(StreamContinuation.pick(listOf(next, next.copy(addonName = "")), noAddon))
    }

    private fun inputs(mode: StreamAutoPlayMode, continuation: ContinuationTarget?) = AutoPlaySelection.Inputs(
        mode = mode,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Torrentio", "Comet"),
        selectedAddons = emptySet(),
        preferredBingeGroup = playing.behaviorHints?.bingeGroup,
        hiddenKeys = emptySet(),
        continuation = continuation
    )

    // D9: a step 2 hit counts as the group match, so binge-only MANUAL plays it; no hit = the mode.
    @Test
    fun `through select the continuation replaces the group match and counts as it`() {
        val comet = stream("Comet", "Comet 4K", "Show.S01E06.2160p.mkv")
        val sameName = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv")
        val streams = listOf(comet, sameName)
        assertEquals(sameName, AutoPlaySelection.select(streams, inputs(StreamAutoPlayMode.MANUAL, target), bingeGroupOnly = true))
        assertNull("no continuation: today's group match misses", AutoPlaySelection.select(streams, inputs(StreamAutoPlayMode.MANUAL, null), bingeGroupOnly = true))
        assertNull(AutoPlaySelection.select(listOf(comet), inputs(StreamAutoPlayMode.MANUAL, target), bingeGroupOnly = true))
        assertEquals(comet, AutoPlaySelection.select(listOf(comet), inputs(StreamAutoPlayMode.FIRST_STREAM, target)))
    }

    @Test
    fun `through select a recently failed or unplayable row loses within its step`() {
        val failed = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv", url = "https://x/failed")
        val external = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv", url = null, externalUrl = "https://web/page")
        val next = stream("Torrentio", tio, "Show.S01E06.2160p.DV-FLUX.mkv", url = "https://x/ok")
        val demoted = inputs(StreamAutoPlayMode.MANUAL, target).copy(
            failures = FailureSnapshot(excluded = emptySet(), demoted = setOfNotNull(FailedReleases.urlKey("https://x/failed")))
        )
        assertEquals(next, AutoPlaySelection.select(listOf(failed, external, next), demoted, bingeGroupOnly = true))
        assertEquals(failed, AutoPlaySelection.select(listOf(failed, external), demoted, bingeGroupOnly = true))
    }

    // The identity guard (P3) runs before the continuation: a wrong episode in the group is skipped.
    @Test
    fun `the identity guard filters before the continuation`() {
        fun named(filename: String) = stream("Torrentio", tio, filename, group = "torrentio|4k|DV").let {
            it.copy(behaviorHints = it.behaviorHints?.copy(filename = filename))
        }
        val wrong = named("Show.S01E07.2160p.mkv")
        val right = named("Show.S01E06.2160p.mkv")
        val identity = ContentIdentity(title = "Show", year = null, season = 1, episode = 6, film = false)
        assertEquals(
            right,
            AutoPlaySelection.select(listOf(wrong, right), inputs(StreamAutoPlayMode.MANUAL, target).copy(identity = identity), bingeGroupOnly = true)
        )
    }
}
