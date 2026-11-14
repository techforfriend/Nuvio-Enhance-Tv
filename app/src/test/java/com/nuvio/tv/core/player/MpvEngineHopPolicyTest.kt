package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.MpvEngineHopPolicy.Reason
import com.nuvio.tv.core.player.MpvEngineHopPolicy.TrackFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 85 G4: the ExoPlayer track triggers that restart a start on mpv (D1/D2, E1/E6/E8). */
class MpvEngineHopPolicyTest {

    private fun video(
        mime: String? = MimeTypes.VIDEO_H265,
        codecs: String? = "hvc1.2.4.L153.B0",
        support: Int = C.FORMAT_HANDLED,
        selected: Boolean = true,
    ) = TrackFact(mime, codecs, support, selected)

    private fun audio(
        mime: String? = MimeTypes.AUDIO_E_AC3,
        support: Int = C.FORMAT_HANDLED,
        selected: Boolean = true,
    ) = TrackFact(mime, codecs = null, support = support, selected = selected)

    private fun reason(video: List<TrackFact>, audio: List<TrackFact>, adaptive: Boolean = false) =
        MpvEngineHopPolicy.reasonForTracks(video, audio, adaptive)

    @Test
    fun aPlayableFileStaysOnExo() {
        assertNull(reason(listOf(video()), listOf(audio())))
        // EXCEEDS_CAPABILITIES is selected and played by ExoPlayer: not a trigger.
        assertNull(reason(listOf(video(support = C.FORMAT_EXCEEDS_CAPABILITIES)), listOf(audio())))
        // No tracks read yet (or a stopped player): nothing to judge.
        assertNull(reason(emptyList(), emptyList()))
    }

    @Test
    fun hi10pHopsWhateverItsSupport() {
        // Shield reports Hi10P as EXCEEDS_CAPABILITIES and selects it (F3); D2 routes it to mpv.
        val hi10p = video(MimeTypes.VIDEO_H264, "avc1.6E0028", C.FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(Reason.HI10P, reason(listOf(hi10p), listOf(audio())))
        assertEquals(Reason.HI10P, reason(listOf(hi10p.copy(support = C.FORMAT_HANDLED)), listOf(audio())))
        // Unselected, it is still the file's video.
        assertEquals(Reason.HI10P, reason(listOf(hi10p.copy(selected = false)), listOf(audio())))
        // 8-bit High (100) plays on ExoPlayer.
        val high = video(MimeTypes.VIDEO_H264, "avc1.640028")
        assertNull(reason(listOf(high), listOf(audio())))
        // The selected track decides: an unselected Hi10P beside a selected 8-bit track stays.
        assertNull(reason(listOf(hi10p.copy(selected = false), high), listOf(audio())))
    }

    @Test
    fun isAvcHigh10ReadsTheProfileByte() {
        assertTrue(MpvEngineHopPolicy.isAvcHigh10("avc1.6E0028"))
        assertTrue(MpvEngineHopPolicy.isAvcHigh10("avc1.6e001f"))
        assertTrue(MpvEngineHopPolicy.isAvcHigh10("AVC3.6E0033"))
        assertTrue(MpvEngineHopPolicy.isAvcHigh10(" avc1.6E0028 , mp4a.40.2"))
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1.640028")) // High
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1.7A0028")) // High 4:2:2, left out
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1.4D401F")) // Main
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("hvc1.6E.4.L153")) // not AVC
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1.110.40")) // legacy decimal form: not read
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1.6"))
        assertFalse(MpvEngineHopPolicy.isAvcHigh10("avc1"))
        assertFalse(MpvEngineHopPolicy.isAvcHigh10(""))
        assertFalse(MpvEngineHopPolicy.isAvcHigh10(null))
    }

    @Test
    fun aVideoTrackNoRendererSupportsHops() {
        val wmv3 = video(MimeTypes.VIDEO_VC1, "wmv3", C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertEquals(Reason.VIDEO_UNSUPPORTED, reason(listOf(wmv3), listOf(audio())))
        assertEquals(
            Reason.VIDEO_UNSUPPORTED,
            reason(listOf(wmv3.copy(support = C.FORMAT_UNSUPPORTED_TYPE)), listOf(audio()))
        )
        // E6: a track whose sample mime is unknown.
        assertEquals(
            Reason.VIDEO_UNSUPPORTED,
            reason(listOf(video(mime = null, codecs = null, support = C.FORMAT_HANDLED, selected = false)), listOf(audio()))
        )
        assertEquals(
            Reason.VIDEO_UNSUPPORTED,
            reason(listOf(video(MimeTypes.VIDEO_UNKNOWN, null, C.FORMAT_HANDLED, selected = false)), listOf(audio()))
        )
        // Adaptive streams take this trigger too.
        assertEquals(Reason.VIDEO_UNSUPPORTED, reason(listOf(wmv3), listOf(audio()), adaptive = true))
        // Selected despite its support (the VC-1 track-selection bypass): ExoPlayer is trying it.
        assertNull(reason(listOf(wmv3.copy(selected = true)), listOf(audio())))
    }

    @Test
    fun anUnsupportedVideoTrackBesideAPlayableOneStays() {
        val unsupported = video(MimeTypes.VIDEO_VC1, null, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertNull(reason(listOf(unsupported, video()), listOf(audio())))
        // Unselected but playable (e.g. a DRM or capability decision): not this trigger.
        assertNull(reason(listOf(video(support = C.FORMAT_UNSUPPORTED_DRM, selected = false)), listOf(audio())))
        assertNull(reason(listOf(video(support = C.FORMAT_EXCEEDS_CAPABILITIES, selected = false)), listOf(audio())))
    }

    @Test
    fun audioWithoutVideoHopsOnlyForProgressiveFiles() {
        // VC-1 in TS: Media3 has no reader, so the file reads as audio only.
        assertEquals(Reason.NO_VIDEO_TRACK, reason(emptyList(), listOf(audio(MimeTypes.AUDIO_AC3))))
        // HLS/DASH may list audio renditions apart from video.
        assertNull(reason(emptyList(), listOf(audio()), adaptive = true))
    }

    @Test
    fun audioNoneOfItPlayableHops() {
        val wma = audio("audio/x-ms-wma", C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertEquals(Reason.AUDIO_UNSUPPORTED, reason(listOf(video()), listOf(wma)))
        assertEquals(
            Reason.AUDIO_UNSUPPORTED,
            reason(listOf(video()), listOf(wma, wma.copy(support = C.FORMAT_UNSUPPORTED_TYPE)))
        )
        // One playable track (DRM counts) keeps ExoPlayer, selected or not (audio turned off).
        assertNull(reason(listOf(video()), listOf(wma, audio(selected = false))))
        assertNull(reason(listOf(video()), listOf(wma, audio(support = C.FORMAT_UNSUPPORTED_DRM, selected = false))))
        assertNull(reason(listOf(video()), listOf(wma, audio(support = C.FORMAT_EXCEEDS_CAPABILITIES, selected = false))))
        // Selected despite its support: ExoPlayer is trying it.
        assertNull(reason(listOf(video()), listOf(wma.copy(selected = true))))
        // A video file with no audio at all plays.
        assertNull(reason(listOf(video()), emptyList()))
    }

    private val open = MpvEngineHopPolicy.GateInput(
        exoActive = true,
        firstFrameRendered = false,
        inBackground = false,
        switchUsedForUrl = false,
        videoMime = MimeTypes.VIDEO_H264,
        videoCodecs = "avc1.6E0028",
        dvSourceProfile = null,
    )

    @Test
    fun theGateOpensOnlyForAnExoStartBeforeItsFirstFrame() {
        assertTrue(MpvEngineHopPolicy.hopAllowed(open))
        // No track read yet (an error before tracks, S3) is not a reason to refuse.
        assertTrue(MpvEngineHopPolicy.hopAllowed(open.copy(videoMime = null, videoCodecs = null)))
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(exoActive = false)))
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(firstFrameRendered = true)))
        // Held back in the background; resume reads the tracks again.
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(inBackground = true)))
        // D3: the URL already switched once (either direction, or a manual switch to ExoPlayer).
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(switchUsedForUrl = true)))
    }

    @Test
    fun theGateNeverOpensForDolbyVision() {
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(videoMime = MimeTypes.VIDEO_DOLBY_VISION, videoCodecs = null)))
        assertFalse(MpvEngineHopPolicy.hopAllowed(open.copy(videoMime = MimeTypes.VIDEO_H265, videoCodecs = "dvhe.07.06")))
        // DV stripped or converted by the extractors is re-signalled as HEVC; their record decides.
        assertFalse(
            MpvEngineHopPolicy.hopAllowed(
                open.copy(videoMime = MimeTypes.VIDEO_H265, videoCodecs = "hvc1.2.4.L153.B0", dvSourceProfile = 7)
            )
        )
    }

    @Test
    fun dolbyVisionNeverHops() {
        // D1: every trigger, E8 included.
        val dvMime = video(MimeTypes.VIDEO_DOLBY_VISION, null, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertNull(reason(listOf(dvMime), listOf(audio())))
        val dvCodecs = video(MimeTypes.VIDEO_H265, "dvhe.05.06", C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertNull(reason(listOf(dvCodecs), listOf(audio())))
        val wma = audio("audio/x-ms-wma", C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertNull(reason(listOf(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06")), listOf(wma)))
        assertTrue(MpvEngineHopPolicy.isDolbyVision(MimeTypes.VIDEO_H265, "dvh1.08.06"))
        assertFalse(MpvEngineHopPolicy.isDolbyVision(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0"))
    }

    @Test
    fun triggersAreReadInOrder() {
        val wma = audio("audio/x-ms-wma", C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        val hi10p = video(MimeTypes.VIDEO_H264, "avc1.6E0028", C.FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(Reason.HI10P, reason(listOf(hi10p), listOf(wma)))
        val unsupported = video(MimeTypes.VIDEO_VC1, null, C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false)
        assertEquals(Reason.VIDEO_UNSUPPORTED, reason(listOf(unsupported), listOf(wma)))
        assertEquals(Reason.NO_VIDEO_TRACK, reason(emptyList(), listOf(wma)))
    }
}
