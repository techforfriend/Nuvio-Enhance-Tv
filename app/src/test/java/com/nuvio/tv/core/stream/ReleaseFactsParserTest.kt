package com.nuvio.tv.core.stream

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.ReleaseAudioChannel
import com.nuvio.tv.domain.model.ReleaseAudioTag
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.domain.model.ReleaseRankPreferences
import com.nuvio.tv.domain.model.ReleaseQuality
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.ReleaseVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.nuvio.tv.core.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release-fact extraction and exclusion checks. Expected sets were carried
 * over from the former list-level filter tests on the same fixtures: a
 * "required X" filter there is "facts carry X" here, an "excluded X" filter
 * is [ReleaseFactsParser.passesExclusionFilters].
 */
class ReleaseFactsParserTest {
    // None of the shipped exclusions, so counts reflect only the one under test.
    private val noExclusions = ReleaseRankPreferences(
        excludedQualities = emptyList(),
        excludedVisualTags = emptyList(),
        excludedEncodes = emptyList(),
        excludedReleaseGroups = emptyList()
    )

    private val dvTags = setOf(ReleaseVisualTag.DV, ReleaseVisualTag.DV_ONLY, ReleaseVisualTag.HDR_DV)
    private val hdrTags = setOf(
        ReleaseVisualTag.HDR,
        ReleaseVisualTag.HDR10,
        ReleaseVisualTag.HDR10_PLUS,
        ReleaseVisualTag.HLG,
        ReleaseVisualTag.HDR_ONLY,
        ReleaseVisualTag.HDR_DV
    )

    private fun facts(stream: Stream) = ReleaseFactsParser.factsFor(stream)

    private fun List<Stream>.sizesWhere(predicate: (ReleaseFacts) -> Boolean): Set<Long?> =
        filter { predicate(facts(it)) }.map { it.streamSize() }.toSet()

    private fun List<Stream>.countPassing(preferences: ReleaseRankPreferences): Int =
        count { ReleaseFactsParser.passesExclusionFilters(facts(it), preferences) }

    @Test
    fun `parses resolution hdr dv and codec from structured parse`() {
        val hdrHevc = stream(resolve = resolve(resolution = "2160p", hdr = listOf("HDR10"), codec = "HEVC", size = 10))
        val dvHevc = stream(resolve = resolve(resolution = "2160p", hdr = listOf("DV", "HDR10"), codec = "HEVC", size = 20))
        val sdrAvc = stream(resolve = resolve(resolution = "1080p", hdr = emptyList(), codec = "AVC", size = 30))
        val hdHevc = stream(resolve = resolve(resolution = "720p", hdr = emptyList(), codec = "HEVC", size = 40))
        val streams = listOf(hdrHevc, dvHevc, sdrAvc, hdHevc)

        val hdrNoDvHevc4k = streams.sizesWhere { f ->
            f.resolution == ReleaseResolution.P2160 &&
                f.visualTags.none { it in dvTags } &&
                f.visualTags.any { it in hdrTags } &&
                f.encode == ReleaseEncode.HEVC
        }
        assertEquals(setOf(10L), hdrNoDvHevc4k)
        assertEquals(setOf(20L), streams.sizesWhere { f -> f.visualTags.any { it in dvTags } })
        assertEquals(ReleaseResolution.P720, facts(hdHevc).resolution)
        assertEquals(ReleaseEncode.AVC, facts(sdrAvc).encode)
    }

    @Test
    fun `parses provided torbox response through dto mapper path`() {
        val response = NetworkModule.provideWireJson()
            .decodeFromString<StreamResponseDto>(torboxMegamindResponse)
        val streams = response.streams.orEmpty().map { it.toDomain("Addon", null) }

        assertEquals(7, streams.size)
        assertEquals(7, streams.countPassing(noExclusions))
        assertEquals(
            listOf(24_201_782_274L, 22_719_933_168L, 4_571_523_542L),
            streams.sortedByDescending { facts(it).size ?: -1L }.take(3).map { it.streamSize() }
        )

        assertEquals(setOf(4_571_523_542L), streams.sizesWhere { f -> f.visualTags.any { it in dvTags } })
        val noDv = streams.filter {
            ReleaseFactsParser.passesExclusionFilters(facts(it), noExclusions.copy(excludedVisualTags = dvTags.toList()))
        }
        assertEquals(6, noDv.size)
        assertTrue(noDv.none { it.description.orEmpty().contains("DV", ignoreCase = true) })

        assertEquals(setOf(4_571_523_542L), streams.sizesWhere { f -> f.visualTags.any { it in hdrTags } })
        assertEquals(6, streams.countPassing(noExclusions.copy(excludedVisualTags = hdrTags.toList())))

        assertEquals(
            setOf(4_571_523_542L, 3_859_136_613L, 2_946_516_232L),
            streams.sizesWhere { it.encode == ReleaseEncode.HEVC }
        )
        assertEquals(4, streams.count { facts(it).encode == ReleaseEncode.AVC })

        val atLeast1080 = setOf(ReleaseResolution.P2160, ReleaseResolution.P1440, ReleaseResolution.P1080)
        assertEquals(6, streams.count { facts(it).resolution in atLeast1080 })
        assertEquals(setOf(4_571_523_542L), streams.sizesWhere { it.resolution == ReleaseResolution.P2160 })
    }

    @Test
    fun `parses structured audio channels`() {
        val remuxAtmos = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "BluRay REMUX",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                audio = listOf("Atmos", "TrueHD"),
                channels = listOf("7.1"),
                languages = listOf("en"),
                group = "GOOD",
                size = 40_000_000_000
            )
        )
        val webAac = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "WEB-DL",
                codec = "AVC",
                audio = listOf("AAC"),
                channels = listOf("2.0"),
                languages = listOf("en"),
                group = "NOPE",
                size = 4_000_000_000
            )
        )
        val blurayDts = stream(
            resolve = resolve(
                resolution = "1080p",
                quality = "BluRay",
                codec = "AVC",
                audio = listOf("DTS"),
                channels = listOf("5.1"),
                languages = listOf("hi"),
                group = "GOOD",
                size = 12_000_000_000
            )
        )
        val streams = listOf(webAac, blurayDts, remuxAtmos)

        assertEquals(
            setOf(40_000_000_000L),
            streams.sizesWhere { f ->
                ReleaseVisualTag.HDR_ONLY in f.visualTags && ReleaseAudioTag.ATMOS in f.audioTags
            }
        )
        assertFalse(
            ReleaseFactsParser.passesExclusionFilters(
                facts(webAac),
                noExclusions.copy(excludedQualities = listOf(ReleaseQuality.WEB_DL))
            )
        )
        assertEquals("GOOD", facts(blurayDts).releaseGroup)
        assertTrue(ReleaseAudioChannel.CH_7_1 in facts(remuxAtmos).audioChannels)
    }

    @Test
    fun `parses every fact category from provided oppenheimer response`() {
        val streams = oppenheimerStreams()

        assertEquals(22, streams.size)
        assertEquals(22, streams.countPassing(noExclusions))
        assertEquals("a real response carries no suspect stream (Spec 65 P4)", 0, streams.count { facts(it).suspect })
        assertEquals(
            listOf(104_700_022_239L, 96_867_354_460L, 92_910_562_472L),
            streams.sortedByDescending { facts(it).size ?: -1L }.take(3).map { it.streamSize() }
        )
        assertEquals(
            listOf(104_700_022_239L, 51_056_367_324L, 14_559_208_901L, 13_916_786_260L, 5_371_431_742L, 1_468_475_610L),
            streams.groupBy { facts(it).resolution }
                .values
                .map { group -> group.maxOf { facts(it).size ?: -1L } }
                .sortedDescending()
        )
        assertEquals(
            setOf(14_559_208_901L, 13_916_786_260L),
            streams.sizesWhere { f -> f.size != null && f.size in 10_000_000_000L..20_000_000_000L }
        )
        assertEquals(setOf(14_559_208_901L, 4_622_252_199L), streams.sizesWhere { it.resolution == ReleaseResolution.P1440 })

        val no720 = streams.filter {
            ReleaseFactsParser.passesExclusionFilters(
                facts(it),
                noExclusions.copy(excludedResolutions = listOf(ReleaseResolution.P720))
            )
        }
        assertEquals(17, no720.size)
        assertTrue(no720.none { it.description.orEmpty().contains("720p", ignoreCase = true) })

        assertEquals(setOf(13_916_786_260L), streams.sizesWhere { it.quality == ReleaseQuality.WEB_DL })
        assertEquals(21, streams.countPassing(noExclusions.copy(excludedQualities = listOf(ReleaseQuality.WEB_DL))))

        assertEquals(
            setOf(87_052_038_851L, 92_910_562_472L, 89_079_717_868L, 96_867_354_460L),
            streams.sizesWhere { ReleaseVisualTag.HDR_DV in it.visualTags }
        )
        assertEquals(setOf(89_100_999_892L), streams.sizesWhere { ReleaseVisualTag.DV_ONLY in it.visualTags })
        assertEquals(setOf(14_559_208_901L, 4_622_252_199L), streams.sizesWhere { ReleaseVisualTag.HDR_ONLY in it.visualTags })
        assertEquals(18, streams.countPassing(noExclusions.copy(excludedVisualTags = listOf(ReleaseVisualTag.IMAX))))

        assertEquals(
            setOf(87_052_038_851L, 92_910_562_472L, 13_916_786_260L),
            streams.sizesWhere { ReleaseAudioTag.DD_PLUS in it.audioTags }
        )
        assertTrue(streams.sizesWhere { ReleaseAudioChannel.CH_5_1 in it.audioChannels }.contains(1_112_357_595L))
        assertEquals(setOf(14_559_208_901L), streams.sizesWhere { it.encode == ReleaseEncode.AV1 })
        assertEquals(setOf(1_468_475_610L), streams.sizesWhere { it.encode == ReleaseEncode.XVID })

        assertEquals(setOf(96_867_354_460L, 51_056_367_324L), streams.sizesWhere { it.releaseGroup.equals("SGF", ignoreCase = true) })
        assertEquals(20, streams.countPassing(noExclusions.copy(excludedReleaseGroups = listOf("SGF"))))
    }

    @Test
    fun `shipped default exclusions drop cam av1 and trash lq groups`() {
        fun passesDefaults(name: String) =
            ReleaseFactsParser.passesExclusionFilters(facts(stream(name = name, resolve = null)))

        assertFalse(passesDefaults("Movie.2023.1080p.CAM.x264-GRP"))
        assertFalse(passesDefaults("Movie.2023.2160p.WEB-DL.AV1-GRP"))
        assertFalse(passesDefaults("Movie.2023.1080p.BluRay.x264-YIFY"))
        assertTrue(passesDefaults("Movie.2023.2160p.BluRay.REMUX.HEVC.TrueHD.Atmos-FraMeSToR"))
    }

    @Test
    fun `token matching keeps boundaries and ignores case`() {
        fun named(name: String) = facts(stream(name = name, resolve = null))

        val dvHdr = named("Movie.2023.2160P.DoVi.HDR10.HEVC-GRP")
        assertEquals(ReleaseResolution.P2160, dvHdr.resolution)
        assertEquals(
            listOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR),
            dvHdr.visualTags
        )
        assertEquals(ReleaseEncode.HEVC, dvHdr.encode)

        // "dv"/"hdr" inside DVDRip/HDRip are not tokens.
        val rip = named("Movie.DVDRip.HDRip.XviD")
        assertEquals(listOf(ReleaseVisualTag.UNKNOWN), rip.visualTags)
        assertEquals(ReleaseQuality.HDRIP, rip.quality)
        assertEquals(ReleaseEncode.XVID, rip.encode)

        // Resolution tokens need a non-alphanumeric boundary on both sides.
        assertEquals(ReleaseResolution.UNKNOWN, named("Movie.2160p4k").resolution)
        assertEquals(ReleaseResolution.P1080, named("Show.1080.WEB").resolution)
        assertEquals(ReleaseResolution.P2160, named("Movie UHD BluRay").resolution)
        assertEquals(ReleaseResolution.P1080, named("Movie [FHD]").resolution)

        // Dotted channel tokens: "5.1" glued to DDP is not a token, "7.1" is.
        val ddp = named("Movie.DDP5.1.Atmos")
        assertEquals(listOf(ReleaseAudioChannel.UNKNOWN), ddp.audioChannels)
        assertEquals(listOf(ReleaseAudioTag.ATMOS, ReleaseAudioTag.DD_PLUS), ddp.audioTags)
        assertEquals(listOf(ReleaseAudioChannel.CH_7_1), named("Movie.TrueHD.7.1.Atmos").audioChannels)
    }

    @Test
    fun `formatter age 3d is not 3D, release-name 3D still is (B9)`() {
        // AIOStreams-style formatter block: the age line ends in "· 3d" (3 days old).
        val aged = facts(
            stream(
                name = "4K Remux",
                resolve = null,
                description = "Movie (2010)\nHEVC DV · HDR10\n58.3 GB · 54.6 Mbps · 3d"
            )
        )
        assertFalse(ReleaseVisualTag.THREE_D in aged.visualTags)
        assertTrue(ReleaseFactsParser.passesExclusionFilters(aged))

        fun named(name: String) = facts(stream(name = name, resolve = null))
        assertTrue(ReleaseVisualTag.THREE_D in named("Movie.2010.3D.1080p.BluRay.x264-GRP").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie 2010 3d half-sbs 1080p").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie.2010.3d.hsbs.1080p").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie.2010.3d.h-ou.1080p").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie 2010 3d sbs 1080p").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie.2010.3d.tab.1080p").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in named("movie.2010.half-sbs.3d.1080p").visualTags)
        assertFalse(ReleaseVisualTag.THREE_D in named("movie 2010 1080p 3d").visualTags)
        assertTrue(ReleaseVisualTag.THREE_D in facts(stream(resolve = resolve(hdr = listOf("3d")))).visualTags)
    }

    @Test
    fun `explicit height beats a UHD or 4K alias (B10)`() {
        fun named(name: String) = facts(stream(name = name, resolve = null)).resolution
        assertEquals(ReleaseResolution.P1080, named("Movie 2010 UHD BluRay 1080p DDP 5 1 x265-GRP"))
        assertEquals(ReleaseResolution.P1440, named("Movie.2010.UHD.BDRip.1440p.mkv"))
        assertEquals(ReleaseResolution.P1080, named("Movie 4K 1080p"))
        assertEquals(ReleaseResolution.P2160, named("Movie 2010 UHD BluRay"))
        assertEquals(ReleaseResolution.P2160, named("Movie 2010 2160p UHD BluRay"))
        // A bare number is as likely a seeder count as a height: it never beats a named alias,
        // but still beats "hd" (also the HD of DTS-HD).
        assertEquals(ReleaseResolution.P2160, named("Movie 2010 4K UHD BluRay x265-GRP 👤 720"))
        assertEquals(ReleaseResolution.P1080, named("Movie 2010 BluRay 1080 DTS-HD MA"))
        assertEquals(ReleaseResolution.P720, named("Movie 2010 HD WEB"))
    }

    @Test
    fun `dolby digital plus never also tags dolby digital (B11)`() {
        fun audio(name: String) = facts(stream(name = name, resolve = null)).audioTags
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie 1080p WEB-DL DD+5.1 H264"))
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie 1080p WEB-DL DD+ 5.1"))
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie Dolby Digital Plus 5.1"))
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie.1080p.WEB.EAC3.x264"))
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie.1080p.WEB.E-AC3.x264"))
        assertEquals(listOf(ReleaseAudioTag.DD_PLUS), audio("Movie 1080p E-AC-3 5.1"))
        assertEquals(listOf(ReleaseAudioTag.DD), audio("Movie.1080p.WEB-DL.DD5.1.H.264"))
        assertEquals(listOf(ReleaseAudioTag.DD), audio("Movie.1080p.BluRay.AC3.x264"))
        assertEquals(listOf(ReleaseAudioTag.DD), audio("Movie 1080p Dolby Digital 5.1"))
        assertEquals(listOf(ReleaseAudioTag.DD), audio("Movie 1080p DD 5.1"))
        assertEquals(
            listOf(ReleaseAudioTag.DD_PLUS, ReleaseAudioTag.DD),
            audio("Movie 1080p DDP 5.1 + AC3 commentary")
        )
    }

    @Test
    fun `streamData facts win over formatter text field by field (X1)`() {
        val facts = facts(
            aioStream(
                aioFile(
                    resolution = "2160p",
                    quality = "BluRay REMUX",
                    encode = "HEVC",
                    releaseGroup = "GRP",
                    visualTags = listOf("DV", "HDR10"),
                    audioTags = listOf("TrueHD", "Atmos"),
                    audioChannels = listOf("7.1")
                ),
                name = "Movie 1080p WEB-DL x264 DD+ 3D-OTHER"
            )
        )
        assertEquals(ReleaseResolution.P2160, facts.resolution)
        assertEquals(ReleaseQuality.BLURAY_REMUX, facts.quality)
        assertEquals(ReleaseEncode.HEVC, facts.encode)
        assertEquals("GRP", facts.releaseGroup)
        assertEquals(
            listOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR),
            facts.visualTags
        )
        assertEquals(listOf(ReleaseAudioTag.ATMOS, ReleaseAudioTag.TRUEHD), facts.audioTags)
        assertEquals(listOf(ReleaseAudioChannel.CH_7_1), facts.audioChannels)

        // Labels match case- and whitespace-insensitively; channels keep the text path's order.
        val loose = facts(
            aioStream(
                aioFile(resolution = " 1080P ", quality = "web-dl", audioChannels = listOf("2.0", "5.1")),
                name = "Stream"
            )
        )
        assertEquals(ReleaseResolution.P1080, loose.resolution)
        assertEquals(ReleaseQuality.WEB_DL, loose.quality)
        assertEquals(listOf(ReleaseAudioChannel.CH_5_1, ReleaseAudioChannel.CH_2_0), loose.audioChannels)
    }

    @Test
    fun `streamData fields it lacks or cannot map fall back to text, except visual tags and group`() {
        val facts = facts(
            aioStream(
                aioFile(resolution = "Unknown", encode = "VC-1", audioTags = listOf("PCM")),
                name = "Movie 2010 3D HDR 1080p BluRay DTS-HD MA 5.1 x264-GRP"
            )
        )
        assertEquals(ReleaseResolution.P1080, facts.resolution)
        assertEquals(ReleaseQuality.BLURAY, facts.quality)
        assertEquals(ReleaseEncode.AVC, facts.encode)
        assertEquals(
            listOf(ReleaseAudioTag.DTS_HD_MA, ReleaseAudioTag.DTS_HD, ReleaseAudioTag.DTS),
            facts.audioTags
        )
        assertEquals(listOf(ReleaseAudioChannel.CH_5_1), facts.audioChannels)
        // The AIOStreams parse is final for these two: no 3D/HDR from text (B9), no text group (B12).
        assertEquals(listOf(ReleaseVisualTag.UNKNOWN), facts.visualTags)
        assertEquals("", facts.releaseGroup)
        assertTrue(ReleaseFactsParser.passesExclusionFilters(facts))
    }

    @Test
    fun `streamData tags keep the text path hierarchy`() {
        fun both(visual: List<String>, audio: List<String>, text: String) {
            val aio = facts(aioStream(aioFile(visualTags = visual, audioTags = audio), name = "Stream"))
            val textFacts = facts(stream(name = text, resolve = null, description = null))
            assertEquals(text, textFacts.visualTags.toSet(), aio.visualTags.toSet())
            assertEquals(text, textFacts.audioTags.toSet(), aio.audioTags.toSet())
        }
        both(listOf("DV", "HDR10+"), listOf("DTS-HD MA"), "Movie 2160p DV HDR10+ DTS-HD MA")
        both(listOf("HDR10"), listOf("DTS:X", "Atmos"), "Movie 2160p HDR10 DTS:X Atmos")
        both(listOf("DV"), listOf("DD+"), "Movie 2160p DV DD+")
        both(listOf("HLG"), listOf("DTS-ES"), "Movie 2160p HLG DTS-ES")
        both(listOf("HDR+DV", "DV", "HDR10"), emptyList(), "Movie 2160p DV HDR10")
        // A sent combination label is re-derived: exactly one combination tag survives.
        val contradictory = facts(aioStream(aioFile(visualTags = listOf("DV Only", "HDR10")), name = "Stream"))
        assertEquals(
            listOf(ReleaseVisualTag.HDR_DV, ReleaseVisualTag.HDR10, ReleaseVisualTag.DV, ReleaseVisualTag.HDR),
            contradictory.visualTags
        )
    }

    @Test
    fun `streamData DVD REMUX reads as DVDRip`() {
        assertEquals(ReleaseQuality.DVDRIP, facts(aioStream(aioFile(quality = "DVD REMUX"), name = "Movie DVD REMUX")).quality)
    }

    @Test
    fun `editions come from streamData, then resolver flags, then text tokens (P6)`() {
        val aio = facts(
            aioStream(aioFile(editions = listOf("Extended Edition", "IMAX", "Director's Cut")), name = "Movie UNRATED")
        )
        assertEquals(listOf("EXTENDED", "DIRECTOR'S CUT"), aio.editions)
        assertTrue("IMAX edition is the IMAX visual tag", ReleaseVisualTag.IMAX in aio.visualTags)
        assertEquals(
            listOf("CRITERION"),
            facts(aioStream(aioFile(editions = listOf("Criterion Collection")), name = "Stream")).editions
        )
        val imaxEdition = facts(aioStream(aioFile(editions = listOf("IMAX Edition")), name = "Stream"))
        assertEquals(emptyList<String>(), imaxEdition.editions)
        assertEquals(listOf(ReleaseVisualTag.IMAX), imaxEdition.visualTags)

        val resolver = facts(stream(resolve = resolve(edition = "Remastered", extended = true), description = "UNCUT"))
        assertEquals(listOf("REMASTERED", "EXTENDED"), resolver.editions)

        fun named(name: String) = facts(stream(name = name, resolve = null)).editions
        assertEquals(listOf("EXTENDED", "REMASTERED"), named("Movie.2010.Extended.Remastered.1080p"))
        assertEquals(listOf("DIRECTOR'S CUT"), named("Movie 2010 Directors Cut 1080p"))
        assertEquals(emptyList<String>(), named("Movie.2010.1080p.WEB-DL"))
    }

    @Test
    fun `languages are codes or MULTI from streamData, resolver codes, then multi text (P7)`() {
        fun aio(vararg languages: String) =
            facts(aioStream(aioFile(languages = languages.toList()), name = "Stream")).languages
        assertEquals(listOf("EN", "NL"), aio("English", "Dutch"))
        assertEquals(listOf("MULTI"), aio("English", "French", "German"))
        assertEquals(listOf("MULTI"), aio("Dual Audio", "English"))
        assertEquals(listOf("PT", "ES"), aio("Portuguese (Brazil)", "Dubbed", "Latino", "Portuguese"))
        assertEquals(emptyList<String>(), aio("Unknown", "Original"))

        assertEquals(listOf("EN", "FR"), facts(stream(resolve = resolve(languages = listOf("en", "fr")))).languages)
        assertEquals(listOf("MULTI"), facts(stream(resolve = resolve(languages = listOf("multi audio", "en")))).languages)
        // Resolver parses write Latino as "la" (the Oppenheimer fixture's ...ENG.ITA.LATINO... files).
        assertEquals(listOf("EN", "ES"), facts(stream(resolve = resolve(languages = listOf("en", "la")))).languages)
        assertEquals(listOf("MULTI"), facts(stream(resolve = resolve(languages = listOf("multi", "en", "la", "it")))).languages)

        fun named(name: String) = facts(stream(name = name, resolve = null)).languages
        assertEquals(listOf("MULTI"), named("Movie.2010.MULTi.1080p.BluRay"))
        assertEquals(listOf("MULTI"), named("Movie 2010 1080p Dual Audio"))
        assertEquals(emptyList<String>(), named("Movie.2010.1080p.Multi-Subs"))
        assertEquals(emptyList<String>(), named("Movie.2010.1080p.Multi-Sub"))
        assertEquals(emptyList<String>(), named("Movie.2010.1080p.Multi.Subtitles"))
        assertEquals(emptyList<String>(), named("Movie 2010 1080p Multi Subbed"))
        assertEquals(emptyList<String>(), named("Movie.2010.1080p.WEB-DL"))
    }

    @Test
    fun `structured hdr tokens normalise case and punctuation`() {
        val tags = facts(stream(resolve = resolve(hdr = listOf("Dolby-Vision", "HDR10+")))).visualTags
        assertEquals(
            listOf(
                ReleaseVisualTag.HDR_DV,
                ReleaseVisualTag.HDR10_PLUS,
                ReleaseVisualTag.HDR10,
                ReleaseVisualTag.DV,
                ReleaseVisualTag.HDR
            ),
            tags
        )
    }

    private fun Stream.streamSize(): Long? = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize

    private fun oppenheimerStreams(): List<Stream> {
        val response = NetworkModule.provideWireJson()
            .decodeFromString<StreamResponseDto>(oppenheimerResponse)
        return response.streams.orEmpty()
            .map { it.toDomain("Addon", null) }
    }

    private fun stream(
        name: String? = "Stream",
        resolve: StreamClientResolve?,
        description: String? = "Description"
    ): Stream = Stream(
        name = name,
        title = "Title",
        description = description,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = resolve
    )

    private fun aioFile(
        resolution: String? = null,
        quality: String? = null,
        encode: String? = null,
        releaseGroup: String? = null,
        visualTags: List<String> = emptyList(),
        audioTags: List<String> = emptyList(),
        audioChannels: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        editions: List<String> = emptyList()
    ): AioParsedFile = AioParsedFile(
        resolution = resolution,
        quality = quality,
        encode = encode,
        releaseGroup = releaseGroup,
        visualTags = visualTags,
        audioTags = audioTags,
        audioChannels = audioChannels,
        languages = languages,
        editions = editions
    )

    private fun aioStream(parsedFile: AioParsedFile, name: String): Stream =
        stream(name = name, resolve = null, description = null).copy(
            streamData = AioStreamData(size = null, filename = null, cached = null, parsedFile = parsedFile)
        )

    private fun resolve(
        type: String? = "debrid",
        service: String? = "torbox",
        resolution: String? = null,
        quality: String? = null,
        hdr: List<String> = emptyList(),
        codec: String? = null,
        audio: List<String>? = null,
        channels: List<String>? = null,
        languages: List<String>? = null,
        group: String? = null,
        size: Long? = null,
        edition: String? = null,
        extended: Boolean? = null
    ): StreamClientResolve = StreamClientResolve(
        type = type,
        infoHash = "abc${size ?: ""}",
        fileIdx = size?.toInt() ?: 1,
        sources = null,
        torrentName = "Torrent",
        filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
        mediaType = "movie",
        title = "Title",
        season = null,
        episode = null,
        service = service,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = "Torrent ${resolution.orEmpty()} ${quality.orEmpty()}",
                filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
                size = size,
                network = null,
                parsed = StreamClientResolveParsed(
                    rawTitle = null,
                    parsedTitle = null,
                    year = null,
                    resolution = resolution,
                    seasons = null,
                    episodes = null,
                    quality = quality,
                    hdr = hdr,
                    codec = codec,
                    audio = audio,
                    channels = channels,
                    languages = languages,
                    group = group,
                    network = null,
                    edition = edition,
                    duration = null,
                    bitDepth = null,
                    extended = extended,
                    theatrical = null,
                    remastered = null,
                    unrated = null
                )
            )
        )
    )

    private val oppenheimerResponse = """
        {
          "streams": [
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"b12e2ad004742ae1348e4e22eebe8fcac07c88d7","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["DTS Lossless","Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","videoSize":92910562472}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ce1bdd5cf0fa18def9264ecfae3815ed5eba61bc","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"hdr":["DV","HDR"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","videoSize":96867354460}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e977543316017189e6ea8d6e729d1fd6d7b04bc9","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","videoSize":87052038851}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"972572ea1d90a6cf8a488fb38de4e439ef20c38a","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","hdr":["DV","HDR"],"languages":["multi","en","it"],"group":"NAHOM","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","videoSize":89079717868}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"3cddcfafc904a8a7c6449e2fe1f24877d3eacf1f","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"channels":["5.1"],"hdr":["DV"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","videoSize":89100999892}},
            {"name":"TB 1440p cached","description":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ecf30429dffab21493b001fbae9e1c69ac86fef7","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","stream":{"raw":{"parsed":{"resolution":"1440p","quality":"HDTV","codec":"av1","audio":["DTS Lossy"],"hdr":["HDR"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","videoSize":14559208901}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e4336787b03acd5e030902df8c2b33aefcda6c4c","sources":[],"filename":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","videoSize":41891055382}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"9a71fed91185ee611995c2a8ce374928cf64b575","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","videoSize":51056367324}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"4718d09f3c5f3c5a578d1ec61416de7a3747ef97","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","videoSize":42601767435}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ba977cf57b3bae48f73b312e6b715cb48265794d","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","en","es","pl","cs"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","videoSize":48286797994}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1e5fee8ca23ec735789e2323fdab6f809ae0c302","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","fr"],"group":"HYPERION","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","videoSize":44082903325}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"70af8f3beac7b8d9fd9b1538c2bf7242dbffb7c6","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"WEB-DL","codec":"avc","audio":["Dolby Digital Plus"],"channels":["5.1"],"languages":["multi"],"group":"TheBiscuitMan","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","videoSize":13916786260}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"7d2e222138aef25ffe38577e4cb9ad04e7d30356","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","audio":["AAC"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","videoSize":1741975811}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"b7b0179a26199b018e0542c635655d49f8292347","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"hevc","group":"PSA","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","videoSize":1112357595}},
            {"name":"TB 720p cached","description":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"f19604e63fe4f880177b0b4463f326e01f8657ce","sources":[],"fileIdx":0,"filename":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","title":"Oppenheimer","torrentName":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","videoSize":8401763164}},
            {"name":"TB 720p cached","description":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"5f40224807d0642859246f89510c41921528b61c","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","title":"Oppenheimer","torrentName":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","stream":{"raw":{"parsed":{"resolution":"720p","codec":"avc","audio":["Dolby Digital"],"channels":["5.1"],"languages":["multi","en","it"],"group":"MIRCrew","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","videoSize":3951560261}},
            {"name":"TB 480p cached","description":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"4fceb37f8a7f528bd01b526329e9bc02bacc2f9e","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","title":"Oppenheimer","torrentName":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","stream":{"raw":{"parsed":{"resolution":"480p","quality":"BDRip","codec":"xvid","languages":["pl"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","videoSize":1468475610}},
            {"name":"TB unknown cached","description":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1ebd4a76ccdf04ceb3bedd5ba08000c9cfbf779f","sources":[],"filename":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"REMUX","audio":["DTS Lossless"],"languages":["multi"],"group":"TABGPT"}}}},"behaviorHints":{"filename":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","videoSize":89021342424}},
            {"name":"TB unknown cached","description":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"53c49f467121b3fccadb95ce098e84f80e052180","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","title":"Oppenheimer","torrentName":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay REMUX","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","videoSize":104700022239}},
            {"name":"TB unknown cached","description":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e9f0213e614fc1b76a50637571e8b9c732933bbf","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","title":"Oppenheimer","torrentName":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay","languages":["es"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","videoSize":2163519374}},
            {"name":"TB unknown cached","description":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"f0da1448c8d29c9d0f1d5d9b374542a1b2c5a1b5","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","languages":["multi","en","fr","it","de"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","videoSize":4622252199}},
            {"name":"TB unknown cached","description":"Oppenheimer.(2023).[tmdbId=872585].mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ec50800ca611355438fa429ad60b4b1e6a93def0","sources":[],"filename":"Oppenheimer.(2023).[tmdbId=872585].mkv","title":"Oppenheimer","torrentName":"Oppenheimer.(2023).[tmdbId=872585].mkv","stream":{"raw":{"parsed":{"resolution":"unknown","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.(2023).[tmdbId=872585].mkv","videoSize":5371431742}}
          ],
          "resolveMode": "client"
        }
    """.trimIndent()

    private val torboxMegamindResponse = """
        {
          "streams": [
            {
              "name": "TB 2160p cached",
              "description": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:c7a807331e1dcdd08e4527f6363cd1e8a109fe01&dn=Megamind%20%282010%29%20UpScaled%202160p%20H265%2010%20bit%20DV%20HDR10%2B%20ita%20eng%20AC3%205.1%20sub%20ita%20eng%20Licdom.mkv",
                "infoHash": "c7a807331e1dcdd08e4527f6363cd1e8a109fe01",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "2160p",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "hdr": ["DV", "HDR10+"],
                      "languages": ["multi", "en", "it"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "videoSize": 4571523542
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:6744954d89d8190643cb80653cf9b5c4b482409f&dn=Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "infoHash": "6744954d89d8190643cb80653cf9b5c4b482409f",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "FiBERHD",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "videoSize": 24201782274
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:dbbbe7da95f9bac639af2d182746d33d0cea51fb&dn=Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "infoHash": "dbbbe7da95f9bac639af2d182746d33d0cea51fb",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "NOGRP",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "videoSize": 22719933168
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:11802541a66f717fbc58dd72cd267d55a6df0477&dn=Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "infoHash": "11802541a66f717fbc58dd72cd267d55a6df0477",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "WEB-DL",
                      "codec": "avc",
                      "audio": ["AAC"],
                      "channels": ["2.0"],
                      "group": "PiRaTeS",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "videoSize": 3079386380
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:3fa25f80fb3eddf076c9002cc16c2a9801233e4b&dn=Megamind%20%282010%29%201080p%2010bit%20Bluray%20x265%20HEVC%20%5BOrg%20DD%205.1%20Hindi%20%2B%20DD%205.1%20English%5D%20MSubs%20~%20TombDoc.mkv",
                "infoHash": "3fa25f80fb3eddf076c9002cc16c2a9801233e4b",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "videoSize": 3859136613
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:e4d12499cf481aeee9007b806c609917d3799e43&dn=Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "infoHash": "e4d12499cf481aeee9007b806c609917d3799e43",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital Plus"],
                      "channels": ["7.1"],
                      "group": "EDGE2020",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "videoSize": 2946516232
              }
            },
            {
              "name": "TB 720p cached",
              "description": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:52c6341e0e4cc4eb0e23bd16f93a00dcad93490c&dn=Megamind%202010%20x264%20720p%20Esub%20BluRay%20Dual%20Audio%20English%20Hindi%20GOPI%20SAHI.mkv",
                "infoHash": "52c6341e0e4cc4eb0e23bd16f93a00dcad93490c",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "title": "Megamind",
                "torrentName": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "720p",
                      "quality": "BluRay",
                      "codec": "avc",
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "videoSize": 796639202
              }
            }
          ],
          "resolveMode": "client"
        }
    """.trimIndent()
}
