package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.AioParsedFile
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P4: fake-quality and implausible-size streams rank below every legitimate one. */
class StreamQualityRankSuspectTest {

    @Test
    fun `a tiny 4K file ranks below a real 1080p one`() {
        val fake4k = stream("Oppenheimer.2023.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX.mkv", sizeMib = 500)
        val real1080 = stream("Oppenheimer.2023.1080p.BluRay.DDP5.1.x264-SPARKS.mkv", sizeMib = 14_000)

        assertEquals(listOf(real1080, fake4k), StreamQualityRank.rank(listOf(fake4k, real1080)))
    }

    @Test
    fun `release text applies the episode floors only, at their exact bounds`() {
        val film4k = "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv"
        assertTrue(suspect(film4k, 699))
        assertFalse(suspect(film4k, 700))
        // The film floor (1800 MiB) is not read from text: film vs episode is not knowable there.
        assertFalse(suspect(film4k, 1200))
        val film1080 = "Heat.1995.1080p.BluRay.x264-GRP.mkv"
        assertTrue(suspect(film1080, 149))
        assertFalse(suspect(film1080, 150))
        assertFalse(suspect(film1080, 500))
        assertFalse(suspect("Heat.1995.720p.BluRay.x264-GRP.mkv", 50))
        assertFalse(suspect("Heat.1995.1440p.WEB.x264-GRP.mkv", 50))
    }

    @Test
    fun `episodes named without SxxEyy keep legitimate sizes (review majors)`() {
        listOf(
            "[SubsPlease] One Piece - 1071 (1080p) [B2A1B3C4].mkv",
            "Show.1x04.1080p.WEB.x264-GRP.mkv",
            "Show.S01 - 05.1080p.WEB.x264-GRP.mkv",
            "Show.Ep05.2024.1080p.WEB.x264-GRP.mkv",
            "The.Daily.Show.2024.10.21.Guest.1080p.WEB.x264-GRP.mkv"
        ).forEach { assertFalse(it, suspect(it, 300)) }
        assertFalse(suspect("Severance.S02E01.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.H.265-FLUX.mkv", 900))
    }

    @Test
    fun `a size printed in Torrentio formatter text counts`() {
        val fake4k = stream("Oppenheimer.2023.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX.mkv", sizeMib = null)
            .copy(title = "Oppenheimer.2023.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX.mkv\n👤 12 💾 0.6 GB ⚙️ TPB")
        val real4k = fake4k.copy(title = "Oppenheimer.2023.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX.mkv\n👤 40 💾 21.81 GB ⚙️ TPB")
        assertTrue(ReleaseFactsParser.factsFor(fake4k).suspect)
        assertFalse(ReleaseFactsParser.factsFor(real4k).suspect)
    }

    @Test
    fun `unknown size or resolution is never implausible (D2)`() {
        assertFalse(suspect("Oppenheimer.2023.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX.mkv", null))
        assertFalse(suspect("Oppenheimer.2023.WEB-DL.DDP5.1-FLUX.mkv", 100))
        // A zero structured size and a kilobyte reading (a bitrate) are not sizes.
        assertFalse(suspect("Oppenheimer.2023.2160p.WEB-DL.HEVC-FLUX.mkv", 0))
        val bitrate = stream("Oppenheimer.2023.2160p.WEB-DL.HEVC-FLUX.mkv", sizeMib = null)
            .copy(title = "Oppenheimer.2023.2160p.WEB-DL.HEVC-FLUX.mkv\nDD5.1 640kb")
        assertFalse(ReleaseFactsParser.factsFor(bitrate).suspect)
    }

    @Test
    fun `fake-quality spellings are suspect`() {
        listOf(
            "Gladiator.II.2024.1080p.HDCAM.x264-GRP.mkv",
            "Gladiator.II.2024.CAMRip.x264-GRP.mkv",
            "Wicked.2024.1080p.HDTS.x264-GRP.mkv",
            "Wicked.2024.720p.TELESYNC.x264-GRP.mkv",
            "Wicked.2024.HDTC.x264-GRP.mkv",
            "Wicked.2024.DVDSCR.x264-GRP.mkv",
            "Wicked.2024.WORKPRINT.x264-GRP.mkv",
            "Alien.1979.2160p.Upscaled.WEB-DL.x265-GRP.mkv",
            "Alien.1979.2160p.AI.Enhanced.x265-GRP.mkv",
            "Wicked.2024.R5.x264-GRP.mkv",
            "Leo.2023.S.Print.x264-GRP.mkv",
            "Leo.2023.S_Print.x264-GRP.mkv",
            "Leo.2023.PreDVD.x264-GRP.mkv",
            "Leo.2023.PDVDRip.x264-GRP.mkv",
            "Alien.1979.2160p.Up-Rez.x265-GRP.mkv",
            "Wicked.2024.TSRip.x264-GRP.mkv",
            "Wicked.2024.HQCAM.x264-GRP.mkv",
            "Wicked.2024.TELECINE.x264-GRP.mkv",
            "Wicked.2024.Screener.x264-GRP.mkv",
            "Wicked.2024.BDSCR.x264-GRP.mkv"
        ).forEach { assertTrue(it, suspect(it, null)) }
    }

    @Test
    fun `legitimate releases with lookalike tokens are not suspect`() {
        listOf(
            "Heat.1995.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP.mkv",
            "Heat.1995.1080p.HDTV.x264-GRP.mkv",
            // Without extension blanking "hd.ts" would read as HDTS.
            "Heat.1995.1080p.WEB-DL.AAC.HD.ts",
            "Alien.1979.2160p.Not.Upscaled.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1-GRP.mkv",
            "Alien.1979.2160p.Not.AI.Upscaled.UHD.BluRay.REMUX.HDR.HEVC-GRP.mkv",
            "Alien.1979.2160p.Not.AI.Enhanced.UHD.BluRay.REMUX.HDR.HEVC-GRP.mkv",
            "Alien 1979 2160p No Upscale UHD BluRay REMUX",
            "Apocalypse.Now.1979.Final.Cut.Directors.Print.2160p.UHD.BluRay.mkv",
            "Apocalypse Now 1979 Director's Print 2160p UHD BluRay",
            "Apocalypse Now 1979 Director\u2019s Print 2160p UHD BluRay",
            "Scream.1996.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FraMeSToR.mkv"
        ).forEach { assertFalse(it, suspect(it, null)) }
    }

    @Test
    fun `an AIOStreams Upscaled parse is suspect`() {
        val aio = stream("AIOStreams 4K", sizeMib = null).copy(
            streamData = AioStreamData(
                size = null,
                filename = null,
                cached = true,
                parsedFile = AioParsedFile(
                    resolution = "2160p",
                    quality = "Upscaled",
                    encode = "HEVC",
                    releaseGroup = null,
                    visualTags = emptyList(),
                    audioTags = emptyList(),
                    audioChannels = emptyList(),
                    languages = emptyList(),
                    editions = emptyList()
                )
            )
        )
        assertTrue(ReleaseFactsParser.factsFor(aio).suspect)
    }

    @Test
    fun `the exclusion pool still comes first`() {
        // A suspect stream outranks nothing it could not before: a default-excluded group stays dropped.
        val tiny4k = stream("Movie.2023.2160p.WEB-DL.HEVC-GRP.mkv", sizeMib = 600)
        val yify = stream("Movie.2023.1080p.BluRay.x264-YIFY.mkv", sizeMib = 2000)
        assertEquals(listOf(tiny4k), StreamQualityRank.rank(listOf(yify, tiny4k)))
    }

    @Test
    fun `an all-suspect pool keeps its quality order`() {
        val tiny4k = stream("Movie.2023.2160p.WEB-DL.HEVC-GRP.mkv", sizeMib = 600)
        val tiny1080 = stream("Movie.2023.1080p.WEB-DL.x264-GRP.mkv", sizeMib = 100)
        assertTrue(ReleaseFactsParser.factsFor(tiny4k).suspect && ReleaseFactsParser.factsFor(tiny1080).suspect)

        assertEquals(listOf(tiny4k, tiny1080), StreamQualityRank.rank(listOf(tiny1080, tiny4k)))
    }

    private fun suspect(filename: String, sizeMib: Long?): Boolean =
        ReleaseFactsParser.factsFor(stream(filename, sizeMib)).suspect

    private fun stream(filename: String, sizeMib: Long?): Stream = Stream(
        name = filename,
        title = null,
        description = null,
        url = "https://example.com/stream",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeMib?.let { it * 1024L * 1024L },
            filename = filename
        ),
        addonName = "TestAddon",
        addonLogo = null
    )
}
