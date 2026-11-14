package com.nuvio.tv.core.player

import android.view.Display
import com.nuvio.tv.core.stream.FailedPlayback
import com.nuvio.tv.core.stream.FailedReleaseStorage
import com.nuvio.tv.core.stream.FailedReleases
import com.nuvio.tv.core.stream.FailureReason
import com.nuvio.tv.core.stream.FailureSnapshot
import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P8 (D15): what the device cannot play as released, on the rows and in auto-play. */
class DecodeCapabilityTest {

    /** The Shield: no hardware AV1 decoder at any probed size; a Dolby Vision display. */
    private val shield = DecodeCapability(
        softwareOnly = DecodeCapability.PROBED_SIZES.keys.map { VideoShape(ReleaseEncode.AV1, it) }.toSet(),
        dolbyVisionAsHdr10 = false
    )
    private val hdr10Tv = DecodeCapability(softwareOnly = emptySet(), dolbyVisionAsHdr10 = true)

    private val av1 = stream("Tears.of.Steel.2012.2160p.WEB-DL.AV1.DDP5.1-GRP.mkv")
    private val av1Other = stream("Tears.of.Steel.2012.1080p.WEB-DL.AV1.AAC-GRP.mkv")
    private val hevc = stream("Tears.of.Steel.2012.2160p.UHD.BluRay.HDR.HEVC.DTS-HD.MA.5.1-SPHD.mkv")
    private val avc = stream("Tears.of.Steel.2012.1080p.BluRay.x264.DTS-GRP.mkv")
    private val dolbyVision = stream("Tears.of.Steel.2012.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1.Atmos-GRP.mkv")

    private fun verdict(capability: DecodeCapability, stream: Stream) =
        capability.verdict(ReleaseFactsParser.factsFor(stream))

    @Test
    fun `a codec with no hardware decoder at its resolution decodes in software`() {
        assertEquals(DecodeVerdict.SOFTWARE, verdict(shield, av1))
        assertEquals(DecodeVerdict.SOFTWARE, verdict(shield, av1Other))
        assertTrue(shield.softwareOnly.names(av1))
    }

    @Test
    fun `a stream the device decodes in hardware has no verdict`() {
        assertNull(verdict(shield, hevc))
        assertNull(verdict(shield, avc))
        assertFalse(shield.softwareOnly.names(hevc))
    }

    @Test
    fun `no verdict under 1080p or for an unknown resolution`() {
        assertNull(verdict(shield, stream("Tears.of.Steel.2012.720p.WEB-DL.AV1.AAC-GRP.mkv")))
        assertNull(verdict(shield, stream("Tears.of.Steel.2012.WEB-DL.AV1.AAC-GRP.mkv")))
    }

    @Test
    fun `dolby vision on a display without it plays reduced`() {
        assertEquals(DecodeVerdict.REDUCED, verdict(hdr10Tv, dolbyVision))
        assertNull(verdict(hdr10Tv, hevc))
        assertNull(verdict(shield, dolbyVision))
        assertFalse(hdr10Tv.softwareOnly.names(dolbyVision))
    }

    @Test
    fun `software decode is named before reduced`() {
        val both = shield.copy(dolbyVisionAsHdr10 = true)
        assertEquals(DecodeVerdict.SOFTWARE, verdict(both, stream("Film.2023.2160p.WEB-DL.DV.AV1.DDP5.1-GRP.mkv")))
    }

    @Test
    fun `an unknown capability says nothing`() {
        assertTrue(DecodeCapability.UNKNOWN.saysNothing)
        assertNull(verdict(DecodeCapability.UNKNOWN, av1))
        assertNull(verdict(DecodeCapability.UNKNOWN, dolbyVision))
        assertFalse(shield.saysNothing)
        assertFalse(hdr10Tv.saysNothing)
    }

    @Test
    fun `the display reads as HDR10 without dolby vision only when it reports that`() {
        val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10
        val hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        val dv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        val hlg = Display.HdrCapabilities.HDR_TYPE_HLG
        assertTrue(DecodeCapability.dolbyVisionAsHdr10(intArrayOf(hdr10, hlg)))
        assertTrue(DecodeCapability.dolbyVisionAsHdr10(intArrayOf(hdr10Plus)))
        assertFalse(DecodeCapability.dolbyVisionAsHdr10(intArrayOf(dv, hdr10, hlg)))
        // SDR-only or HLG-only: not "plays as HDR10".
        assertFalse(DecodeCapability.dolbyVisionAsHdr10(intArrayOf()))
        assertFalse(DecodeCapability.dolbyVisionAsHdr10(intArrayOf(hlg)))
    }

    // ---- auto-play (the mode's own step) ----

    private fun inputs(
        mode: StreamAutoPlayMode,
        decode: DecodeCapability = shield,
        regex: String = "",
        bingeGroup: String? = null,
        failures: FailureSnapshot = FailureSnapshot.EMPTY
    ) = AutoPlaySelection.Inputs(
        mode = mode,
        regexPattern = regex,
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Addon"),
        selectedAddons = emptySet(),
        preferredBingeGroup = bingeGroup,
        failures = failures,
        hiddenKeys = emptySet(),
        softwareOnly = decode.softwareOnly
    )

    @Test
    fun `first stream skips a software-decoded stream`() {
        assertEquals(hevc, AutoPlaySelection.select(listOf(av1, hevc, avc), inputs(StreamAutoPlayMode.FIRST_STREAM)))
    }

    @Test
    fun `a software-decoded stream plays when every candidate is one`() {
        assertEquals(av1, AutoPlaySelection.select(listOf(av1, av1Other), inputs(StreamAutoPlayMode.FIRST_STREAM)))
        assertEquals(av1, AutoPlaySelection.select(listOf(av1, av1Other), inputs(StreamAutoPlayMode.REGEX_MATCH, regex = "AV1")))
    }

    @Test
    fun `regex skips a software-decoded stream`() {
        assertEquals(
            hevc,
            AutoPlaySelection.select(listOf(av1, avc, hevc), inputs(StreamAutoPlayMode.REGEX_MATCH, regex = "2160p"))
        )
    }

    @Test
    fun `a regex that leaves only software-decoded streams still plays one`() {
        assertEquals(
            av1,
            AutoPlaySelection.select(listOf(hevc, av1, avc), inputs(StreamAutoPlayMode.REGEX_MATCH, regex = "AV1"))
        )
    }

    @Test
    fun `a device with a hardware decoder for everything picks as before`() {
        assertEquals(
            av1,
            AutoPlaySelection.select(listOf(av1, hevc), inputs(StreamAutoPlayMode.FIRST_STREAM, decode = DecodeCapability.UNKNOWN))
        )
        assertEquals(
            av1,
            AutoPlaySelection.select(listOf(av1, hevc), inputs(StreamAutoPlayMode.FIRST_STREAM, decode = hdr10Tv))
        )
    }

    @Test
    fun `dolby vision that plays reduced is not skipped`() {
        assertEquals(
            dolbyVision,
            AutoPlaySelection.select(listOf(dolbyVision, hevc), inputs(StreamAutoPlayMode.FIRST_STREAM, decode = hdr10Tv))
        )
    }

    @Test
    fun `the binge group the viewer was on plays although software-decoded`() {
        val grouped = av1.copy(behaviorHints = hints("addon|2160p|AV1|GRP"))
        assertEquals(
            grouped,
            AutoPlaySelection.select(
                listOf(hevc, grouped),
                inputs(StreamAutoPlayMode.FIRST_STREAM, bingeGroup = "addon|2160p|AV1|GRP")
            )
        )
    }

    @Test
    fun `a working software-decoded stream comes before a recently failed one`() {
        val store = FailedReleases(
            object : FailedReleaseStorage {
                override fun load(): String? = null
                override fun save(value: String) = Unit
            },
            kotlin.time.Clock.System
        )
        store.record(FailureReason.DEAD_LINK, FailedPlayback(urls = listOf(hevc.getStreamUrl()), listEntry = hevc))
        val failed = store.snapshot()
        val working = stream("Tears.of.Steel.2012.1080p.WEB-DL.H264.AAC-GRP.mkv")
        assertEquals(av1, AutoPlaySelection.select(listOf(hevc, av1), inputs(StreamAutoPlayMode.FIRST_STREAM, failures = failed)))
        assertEquals(
            working,
            AutoPlaySelection.select(listOf(hevc, av1, working), inputs(StreamAutoPlayMode.FIRST_STREAM, failures = failed))
        )
    }

    @Test
    fun `quality rank keeps its own order`() {
        // AV1 is already outside the rank's pool; when everything is, its fallback order stands
        // (the 2160p one, although the 1080p one is listed first).
        assertEquals(hevc, AutoPlaySelection.select(listOf(av1, avc, hevc), inputs(StreamAutoPlayMode.QUALITY_RANK)))
        assertEquals(av1, AutoPlaySelection.select(listOf(av1Other, av1), inputs(StreamAutoPlayMode.QUALITY_RANK)))
    }

    @Test
    fun `the software-only shapes are part of the auto-play snapshot`() {
        val a = inputs(StreamAutoPlayMode.FIRST_STREAM)
        assertEquals(a, inputs(StreamAutoPlayMode.FIRST_STREAM))
        assertFalse(a == inputs(StreamAutoPlayMode.FIRST_STREAM, decode = DecodeCapability.UNKNOWN))
    }

    @Test
    fun `the fixtures parse as the codecs they name`() {
        assertEquals(ReleaseEncode.AV1, ReleaseFactsParser.factsFor(av1).encode)
        assertEquals(ReleaseResolution.P2160, ReleaseFactsParser.factsFor(av1).resolution)
        assertEquals(ReleaseEncode.HEVC, ReleaseFactsParser.factsFor(hevc).encode)
        assertEquals(ReleaseEncode.AVC, ReleaseFactsParser.factsFor(avc).encode)
    }

    private fun hints(bingeGroup: String? = null, filename: String? = null) = StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = bingeGroup,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = filename
    )

    private fun stream(filename: String) = Stream(
        name = "Addon",
        title = filename,
        description = null,
        url = "https://example.com/${filename.hashCode()}",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = hints(filename = filename),
        addonName = "Addon",
        addonLogo = null
    )
}
