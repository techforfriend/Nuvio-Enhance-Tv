package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the OSD top-band badges (wave U3a; data st7: DV profile, audio output mode, frame rate; st8:
 * degraded badges, DV fallback rule, toast priority), their resolution buckets and the output-truth
 * HDR label they share with Diagnostics.
 */
class OsdTechInfoTest {

    @Test
    fun `4K Dolby Vision remux reads resolution, HDR, audio, size in order`() {
        assertEquals(
            listOf("4K", "DOLBY VISION", "TRUEHD 7.1", "23.4 GB"),
            osdTechBadges(3840, 2160, "Dolby Vision", "TrueHD", 8, 25_125_558_681L).texts()
        )
    }

    @Test
    fun `SDR gets no HDR badge and unknown parts are left out`() {
        assertEquals(listOf("1080p", "E-AC-3 5.1"), osdTechBadges(1920, 1080, "SDR", "E-AC-3", 6, null).texts())
        assertEquals(listOf("720p"), osdTechBadges(1280, 720, null, null, null, 0L).texts())
        assertEquals(emptyList<String>(), osdTechBadges(0, 0, null, null, null, null).texts())
    }

    @Test
    fun `audio badge needs a codec and adds the layout when the channel count is known`() {
        assertEquals(listOf("DTS-HD"), osdTechBadges(0, 0, null, "DTS-HD", null, null).texts())
        assertEquals(listOf("FLAC 3CH"), osdTechBadges(0, 0, null, "FLAC", 3, null).texts())
        assertEquals(emptyList<String>(), osdTechBadges(0, 0, null, null, 6, null).texts())
    }

    @Test
    fun `resolution bucket follows the longer side, so crops and portrait keep their class`() {
        // Cropped widescreen / letterboxed
        assertEquals("1080p", resolutionLabel(1918, 802))
        assertEquals("480p", resolutionLabel(854, 357))
        assertEquals("720p", resolutionLabel(1278, 530))
        assertEquals("4K", resolutionLabel(3836, 1600))
        // Standard
        assertEquals("1080p", resolutionLabel(1920, 1080))
        assertEquals("720p", resolutionLabel(1280, 720))
        assertEquals("480p", resolutionLabel(854, 480))
        assertEquals("4K", resolutionLabel(3840, 2160))
        assertEquals("1440p", resolutionLabel(2560, 1440))
        assertEquals("1080p", resolutionLabel(2398, 1000))
        // Portrait
        assertEquals("1080p", resolutionLabel(1080, 1920))
        assertEquals("720p", resolutionLabel(720, 1280))
        // Below the buckets: the shorter side
        assertEquals("360p", resolutionLabel(640, 360))
        assertEquals("576p", resolutionLabel(720, 576))
    }

    @Test
    fun `size switches unit at a GiB`() {
        assertEquals("850 MB", fileSizeLabel(891_289_600L))
        assertEquals("1024 MB", fileSizeLabel(1_073_741_823L))
        assertEquals("1.0 GB", fileSizeLabel(1_073_741_824L))
    }

    @Test
    fun `live audio format names the codec only when it is the selected track`() {
        val selected = TrackInfo(index = 0, name = "English", language = "en", trackId = "2", codec = "DTS")
        val liveSame = Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build()
        val liveOld = Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
        assertEquals("DTS-HD", osdAudioCodec(selected, liveSame))
        assertEquals("DTS", osdAudioCodec(selected, liveOld))
        assertEquals("DTS", osdAudioCodec(selected, null))
        assertNull(osdAudioCodec(null, liveSame))
    }

    @Test
    fun `HDR label is the output, not the source mime`() {
        val dv = MimeTypes.VIDEO_DOLBY_VISION
        val hevc = MimeTypes.VIDEO_H265
        val pq = C.COLOR_TRANSFER_ST2084
        assertEquals("Dolby Vision", friendlyVideoHdrType(dv, pq, null, false))
        assertEquals("HDR10", friendlyVideoHdrType(dv, pq, "HDR10_BASE_LAYER", false))
        assertEquals("HDR10", friendlyVideoHdrType(dv, null, "STRIP_DV", false))
        assertEquals("Dolby Vision", friendlyVideoHdrType(hevc, pq, "DV81_LIBDOVI", true))
        assertEquals("HDR10", friendlyVideoHdrType(hevc, pq, "DV81_LIBDOVI", false))
        assertNull(friendlyVideoHdrType(hevc, null, "DV81_LIBDOVI", false))
        assertEquals("HLG", friendlyVideoHdrType(hevc, C.COLOR_TRANSFER_HLG, null, false))
        assertEquals("SDR", friendlyVideoHdrType(MimeTypes.VIDEO_H264, C.COLOR_TRANSFER_SDR, null, false))
    }

    @Test
    fun `st7 row names the DV profile, the audio output and the matched frame rate before the size`() {
        val badges = osdTechBadges(
            3840, 2160, "Dolby Vision", "TrueHD", 8, 25_125_558_681L,
            dvProfile = "7", dvEnhancementLayer = "FEL", audioOutput = AudioOutputMode.BITSTREAM,
            frameRate = 23.976f, displayRefreshHz = 23.976f
        )
        assertEquals(
            listOf("4K", "DOLBY VISION P7 FEL", "TRUEHD 7.1 · BITSTREAM", "23.976", "23.4 GB"),
            badges.texts()
        )
        assertEquals(listOf(false, false, false, true, false), badges.map { it.matched })
    }

    @Test
    fun `audio badge carries every output word, and an unmatched rate has no tick`() {
        assertEquals(listOf("TRUEHD 7.1 · MAT"), osdTechBadges(0, 0, null, "TrueHD", 8, null, audioOutput = AudioOutputMode.MAT).texts())
        assertEquals(listOf("DTS-HD 5.1 · AC-3"), osdTechBadges(0, 0, null, "DTS-HD", 6, null, audioOutput = AudioOutputMode.AC3_TRANSCODE).texts())
        assertEquals(listOf("TRUEHD 7.1 · PCM"), osdTechBadges(0, 0, null, "TrueHD", 8, null, audioOutput = AudioOutputMode.PCM).texts())
        assertEquals(listOf(OsdTechBadge("23.976")), osdTechBadges(0, 0, null, null, null, null, frameRate = 23.976f))
    }

    @Test
    fun `DV badge drops an unknown layer and profile, other HDR types ignore them`() {
        assertEquals(listOf("DOLBY VISION P8"), osdTechBadges(0, 0, "Dolby Vision", null, null, null, dvProfile = "8", dvEnhancementLayer = "unknown").texts())
        assertEquals(listOf("DOLBY VISION"), osdTechBadges(0, 0, "Dolby Vision", null, null, null).texts())
        assertEquals(listOf("HDR10"), osdTechBadges(0, 0, "HDR10", null, null, null, dvProfile = "7", dvEnhancementLayer = "FEL").texts())
    }

    @Test
    fun `frame rate reads three decimals without trailing zeros and needs a positive rate`() {
        assertEquals("23.976", frameRateLabel(23.976f))
        assertEquals("24", frameRateLabel(24f))
        assertEquals("29.97", frameRateLabel(29.97f))
        assertEquals(listOf("25"), osdTechBadges(0, 0, null, null, null, null, frameRate = 25f).texts())
        assertEquals(emptyList<String>(), osdTechBadges(0, 0, null, null, null, null, frameRate = 0f).texts())
    }

    @Test
    fun `refresh matches a whole multiple of the frame rate within tolerance`() {
        assertTrue(isRefreshMatch(23.976f, 23.976f))
        assertTrue(isRefreshMatch(24f, 23.976f))
        assertTrue(isRefreshMatch(47.952f, 23.976f))
        assertTrue(isRefreshMatch(50f, 25f))
        assertFalse(isRefreshMatch(60f, 23.976f))
        assertFalse(isRefreshMatch(59.94f, 25f))
        assertFalse(isRefreshMatch(0f, 24f))
        assertFalse(isRefreshMatch(60f, 24f))
        assertFalse(isRefreshMatch(24f, 60f))
    }

    @Test
    fun `output mode follows the Stats rule`() {
        val truehd = MimeTypes.AUDIO_TRUEHD
        val ac3 = MimeTypes.AUDIO_AC3
        assertEquals(AudioOutputMode.MAT, audioOutputMode(true, null, false, truehd))
        assertNull(audioOutputMode(false, null, false, truehd))
        assertEquals(AudioOutputMode.PCM, audioOutputMode(false, MimeTypes.AUDIO_RAW, false, truehd))
        assertEquals(AudioOutputMode.BITSTREAM, audioOutputMode(false, truehd, true, truehd))
        assertEquals(AudioOutputMode.AC3_TRANSCODE, audioOutputMode(false, ac3, true, MimeTypes.AUDIO_DTS))
        assertEquals(AudioOutputMode.BITSTREAM, audioOutputMode(false, ac3, true, ac3))
        // Unknown source: no transcode claimed.
        assertEquals(AudioOutputMode.BITSTREAM, audioOutputMode(false, ac3, true, null))
        // Direct playback refused (policy, speed, Bluetooth): PCM whatever the sink input mime.
        assertEquals(AudioOutputMode.PCM, audioOutputMode(false, truehd, false, truehd))
    }

    @Test
    fun `st8 row turns the degraded badges amber and says what happened`() {
        val badges = osdTechBadges(
            3840, 2160, "HDR10", "TrueHD", 8, 25_125_558_681L,
            dvProfile = "7", dvEnhancementLayer = "FEL", audioOutput = AudioOutputMode.PCM,
            frameRate = 23.976f, displayRefreshHz = 60f,
            degradations = setOf(
                PlaybackDegradation.DOLBY_VISION, PlaybackDegradation.AUDIO_PCM, PlaybackDegradation.FRAME_RATE
            )
        )
        assertEquals(listOf("4K", "DV → HDR10", "TRUEHD 7.1 · PCM", "23.976 → 60 HZ", "23.4 GB"), badges.texts())
        assertEquals(listOf(false, true, true, true, false), badges.map { it.degraded })
        assertEquals(listOf(false, false, false, false, false), badges.map { it.matched })
    }

    @Test
    fun `audio off, safe audio and VC-1 software mark their badge`() {
        assertEquals(
            listOf(OsdTechBadge("AUDIO OFF", degraded = true)),
            osdTechBadges(0, 0, null, null, null, null, degradations = setOf(PlaybackDegradation.AUDIO_OFF))
        )
        assertEquals(
            listOf(OsdTechBadge("E-AC-3 5.1 · BITSTREAM", degraded = true)),
            osdTechBadges(
                0, 0, null, "E-AC-3", 6, null,
                audioOutput = AudioOutputMode.BITSTREAM, degradations = setOf(PlaybackDegradation.AUDIO_SAFE)
            )
        )
        assertEquals(
            listOf(OsdTechBadge("1080p · SOFTWARE", degraded = true)),
            osdTechBadges(1920, 1080, null, null, null, null, degradations = setOf(PlaybackDegradation.VC1_SOFTWARE))
        )
    }

    @Test
    fun `frame rate ticks on a match and stays plain while the mismatch is not reported`() {
        assertEquals(listOf(OsdTechBadge("24", matched = true)), osdTechBadges(0, 0, null, null, null, null, frameRate = 24f, displayRefreshHz = 48f))
        // Track AFR still switching: the controller holds FRAME_RATE back, so no amber yet.
        assertEquals(listOf(OsdTechBadge("24")), osdTechBadges(0, 0, null, null, null, null, frameRate = 24f, displayRefreshHz = 60f))
        assertEquals(listOf(OsdTechBadge("24")), osdTechBadges(0, 0, null, null, null, null, frameRate = 24f))
        assertTrue(isFrameRateMismatch(23.976f, 60f))
        assertFalse(isFrameRateMismatch(23.976f, 24f))
        assertFalse(isFrameRateMismatch(null, 60f))
        assertFalse(isFrameRateMismatch(23.976f, null))
    }

    @Test
    fun `toast raises once per stream URL, after the first frame, and clears a stale notice`() {
        val dv = setOf(PlaybackDegradation.DOLBY_VISION, PlaybackDegradation.AUDIO_SAFE)
        assertEquals(DegradedNoticeStep.Raise(PlaybackDegradation.AUDIO_SAFE), degradedNoticeStep(false, true, dv))
        // Same URL (a rebuild retry, a later refresh): the earlier answer stands.
        assertEquals(DegradedNoticeStep.Keep, degradedNoticeStep(true, true, dv))
        assertEquals(DegradedNoticeStep.Keep, degradedNoticeStep(true, false, emptySet()))
        // New URL: nothing until the first frame, and nothing to say clears another URL's notice.
        assertEquals(DegradedNoticeStep.Clear, degradedNoticeStep(false, false, dv))
        assertEquals(DegradedNoticeStep.Clear, degradedNoticeStep(false, true, emptySet()))
    }

    @Test
    fun `DV fallback only when the requested handling wanted Dolby Vision`() {
        assertTrue(isDolbyVisionFallback("7", "HDR10", "AUTO", displayDv = true))
        assertFalse(isDolbyVisionFallback("7", "HDR10", "AUTO", displayDv = false))
        assertTrue(isDolbyVisionFallback("7", "HDR10", "DV81_LIBDOVI", displayDv = false))
        assertTrue(isDolbyVisionFallback("8", "HDR10", "OFF", displayDv = false))
        assertFalse(isDolbyVisionFallback("7", "HDR10", "HDR10_BASE_LAYER", displayDv = true))
        assertFalse(isDolbyVisionFallback("7", "HDR10", "STRIP_DV", displayDv = true))
        assertFalse(isDolbyVisionFallback("7", "Dolby Vision", "AUTO", displayDv = true))
        // A plain HDR10 file under AUTO (conversion armed, no source profile) never counts.
        assertFalse(isDolbyVisionFallback(null, "HDR10", "AUTO", displayDv = true))
        assertFalse(isDolbyVisionFallback("7", null, "AUTO", displayDv = true))
    }

    @Test
    fun `toast priority puts audio first and the frame rate last`() {
        assertEquals(
            PlaybackDegradation.AUDIO_PCM,
            setOf(PlaybackDegradation.FRAME_RATE, PlaybackDegradation.DOLBY_VISION, PlaybackDegradation.AUDIO_PCM).minOrNull()
        )
        assertEquals(PlaybackDegradation.AUDIO_OFF, PlaybackDegradation.entries.first())
        assertEquals(PlaybackDegradation.FRAME_RATE, PlaybackDegradation.entries.last())
    }

    @Test
    fun `hdr output types pick the HDR subtitle style (Spec 62 D7)`() {
        assertTrue(isHdrOutputType("Dolby Vision"))
        assertTrue(isHdrOutputType("HDR10"))
        assertTrue(isHdrOutputType("HLG"))
        assertFalse(isHdrOutputType("SDR"))
        assertFalse(isHdrOutputType(null))
    }

    private fun List<OsdTechBadge>.texts() = map { it.text }
}
