package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * Spec 85 D1/D2: when an ExoPlayer start moves to mpv because ExoPlayer cannot play the file: the
 * gate ([hopAllowed], G2) and the track triggers ([reasonForTracks], G4). Pure, so every row is
 * pinned by `MpvEngineHopPolicyTest`.
 */
internal object MpvEngineHopPolicy {

    /** The controller's facts for [hopAllowed]. */
    data class GateInput(
        /** ExoPlayer is the engine and its player exists. */
        val exoActive: Boolean,
        val firstFrameRendered: Boolean,
        val inBackground: Boolean,
        /** The URL already took its one automatic engine switch (G1, `engineHopStreamUrls`). */
        val switchUsedForUrl: Boolean,
        /** The current video track's sample mime and codecs (selected, else first). */
        val videoMime: String?,
        val videoCodecs: String?,
        /** The DV profile the extractors recorded while converting or stripping DV (re-signalled as HEVC). */
        val dvSourceProfile: Int?,
    )

    /**
     * G2: an ExoPlayer start before its first frame, in the foreground (the controller holds a
     * trigger seen in the background for resume), whose URL has not had its one automatic switch (D3's
     * loop guard), and never Dolby Vision (D1). Not gated on the auto-switch setting, which covers
     * mpv->Exo only (D1).
     */
    fun hopAllowed(input: GateInput): Boolean = with(input) {
        exoActive && !firstFrameRendered && !inBackground && !switchUsedForUrl &&
            !isDolbyVision(videoMime, videoCodecs) && dvSourceProfile == null
    }

    enum class Reason {
        /** H.264 High 10 (D2): no hardware decoder takes it; mpv decodes it in software. */
        HI10P,
        /** A video track no renderer supports (E1, E6: an unknown sample mime lands here too). */
        VIDEO_UNSUPPORTED,
        /** Audio but no video track in a progressive file (VC-1 in TS: Media3 has no reader). */
        NO_VIDEO_TRACK,
        /** Audio tracks, none of them playable (E8). */
        AUDIO_UNSUPPORTED,
    }

    /** One track of an ExoPlayer `Tracks` group, as the triggers read it ([support]: a `C.FORMAT_*`). */
    data class TrackFact(
        val mime: String?,
        val codecs: String?,
        val support: Int,
        val selected: Boolean,
    )

    /**
     * The track trigger for one ExoPlayer tracks update, or null. Never for Dolby Vision (D1: its
     * pipeline is ExoPlayer's), and nothing before any audio or video track is known. An adaptive
     * stream ([adaptive]) may list audio renditions apart from video, so it never takes
     * [Reason.NO_VIDEO_TRACK]. A DRM-protected track counts as playable.
     */
    fun reasonForTracks(video: List<TrackFact>, audio: List<TrackFact>, adaptive: Boolean): Reason? {
        if (video.any { isDolbyVision(it.mime, it.codecs) }) return null
        val effectiveVideo = video.filter { it.selected }.ifEmpty { video }
        return when {
            effectiveVideo.any { isAvcHigh10(it.codecs) } -> Reason.HI10P
            video.isNotEmpty() && video.none { it.selected } && video.all { it.isUnplayableVideo() } ->
                Reason.VIDEO_UNSUPPORTED
            video.isEmpty() && audio.isNotEmpty() && !adaptive -> Reason.NO_VIDEO_TRACK
            audio.isNotEmpty() && audio.none { it.selected } && audio.all { it.support in UNSUPPORTED } ->
                Reason.AUDIO_UNSUPPORTED
            else -> null
        }
    }

    /** A Dolby Vision video track: its sample mime, or a DV codec string ([DvCodecString]). */
    fun isDolbyVision(mime: String?, codecs: String?): Boolean =
        mime == MimeTypes.VIDEO_DOLBY_VISION || DvCodecString.profile(codecs) != null

    /**
     * An RFC 6381 AVC codec string ("avc1.6E0028": profile_idc, constraint flags, level, in hex)
     * with profile_idc 110, High 10 (D2). High 4:2:2 (122) and 4:4:4 (244) are left out until a
     * real file needs them.
     */
    fun isAvcHigh10(codecs: String?): Boolean = codecs.orEmpty().split(',').any { entry ->
        val parts = entry.trim().split('.')
        parts.size >= 2 &&
            parts[0].lowercase(Locale.US) in AVC_PREFIXES &&
            parts[1].length >= 2 &&
            parts[1].substring(0, 2).toIntOrNull(16) == AVC_PROFILE_HIGH_10
    }

    private fun TrackFact.isUnplayableVideo(): Boolean =
        support in UNSUPPORTED || mime == null || mime == MimeTypes.VIDEO_UNKNOWN

    private val UNSUPPORTED = setOf(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_UNSUPPORTED_TYPE)
    private val AVC_PREFIXES = setOf("avc1", "avc3")
    private const val AVC_PROFILE_HIGH_10 = 110
}
