package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.local.Dv7HandlingMode
import java.util.Locale

/**
 * One OSD top-band tech badge; [matched] draws the green tick (frame rate the display matched),
 * [degraded] the amber style (a recovery changed what plays, data st8).
 */
data class OsdTechBadge(val text: String, val matched: Boolean = false, val degraded: Boolean = false)

/**
 * Recoveries the app made for this playback (data P2), in toast priority order. Only fallbacks, never a
 * user or policy choice: PCM by setting, Bluetooth or speed, or an HDR10 base layer chosen in settings,
 * is not degraded.
 */
enum class PlaybackDegradation { AUDIO_OFF, AUDIO_PCM, AUDIO_SAFE, DOLBY_VISION, VC1_SOFTWARE, FRAME_RATE }

/**
 * True when a Dolby Vision source plays without Dolby Vision although the requested DV7 handling wanted
 * it: AUTO on a display that reports DV, Convert-to-DV8.1, or native (OFF). [sourceProfile] is the
 * diagnostics' per-playback profile (null for non-DV files, so AUTO arming conversion never counts).
 */
internal fun isDolbyVisionFallback(
    sourceProfile: String?,
    outputHdrType: String?,
    requestedDv7Mode: String,
    displayDv: Boolean
): Boolean {
    if (sourceProfile.isNullOrBlank() || outputHdrType == null || outputHdrType == HDR_TYPE_DOLBY_VISION) return false
    val requested = Dv7HandlingMode.entries.firstOrNull { it.name == requestedDv7Mode } ?: return false
    return when (requested) {
        Dv7HandlingMode.AUTO -> displayDv
        Dv7HandlingMode.DV81_LIBDOVI, Dv7HandlingMode.OFF -> true
        Dv7HandlingMode.HDR10_BASE_LAYER, Dv7HandlingMode.STRIP_DV -> false
    }
}

/** True when the AFR-applied display mode ([refreshHz]) does not show [fps] cleanly. */
internal fun isFrameRateMismatch(fps: Float?, refreshHz: Float?): Boolean =
    fps != null && fps > 0f && refreshHz != null && !isRefreshMatch(refreshHz, fps)

/** What one badge refresh does to the degraded toast. */
internal sealed interface DegradedNoticeStep {
    /** Show the copy for [degradation] and mark this stream URL as notified. */
    data class Raise(val degradation: PlaybackDegradation) : DegradedNoticeStep
    /** Already notified for this URL: leave the notice as it is (showing, or cleared once shown). */
    data object Keep : DegradedNoticeStep
    /** Nothing to say yet for this URL; a notice still pending from another URL is stale. */
    data object Clear : DegradedNoticeStep
}

/**
 * Once per playback: the highest-priority degradation raises the toast the first time any is present
 * for a stream URL, after the first frame (DV and frame rate are first-frame facts, and a rebuild
 * retry of the same URL, where most fallbacks start, keeps its earlier answer).
 */
internal fun degradedNoticeStep(
    raisedForCurrentUrl: Boolean,
    firstFrameRendered: Boolean,
    degradations: Set<PlaybackDegradation>
): DegradedNoticeStep = when {
    raisedForCurrentUrl -> DegradedNoticeStep.Keep
    !firstFrameRendered -> DegradedNoticeStep.Clear
    else -> degradations.minOrNull()?.let { DegradedNoticeStep.Raise(it) } ?: DegradedNoticeStep.Clear
}

/** How the selected audio leaves the box (data P1); [label] is the Audio panel word, uppercased on the badge. */
enum class AudioOutputMode(val label: String) {
    BITSTREAM("Bitstream"),
    MAT("MAT"),
    /** The app's own x -> AC-3 transcode, the only one it performs. */
    AC3_TRANSCODE("AC-3"),
    PCM("PCM")
}

/**
 * The output mode from the sinks: MAT when the MAT wrapper is packing; else unknown until the sink
 * is configured; PCM unless the sink plays the bitstream directly; AC-3 when it carries AC-3 for a
 * non-AC-3 source (our transcode, which "bitstream" would hide).
 */
internal fun audioOutputMode(
    matActive: Boolean,
    sinkInputMime: String?,
    directPlayback: Boolean,
    sourceMime: String?
): AudioOutputMode? = when {
    matActive -> AudioOutputMode.MAT
    sinkInputMime == null -> null
    !directPlayback -> AudioOutputMode.PCM
    sinkInputMime == MimeTypes.AUDIO_AC3 && sourceMime != null && sourceMime != MimeTypes.AUDIO_AC3 ->
        AudioOutputMode.AC3_TRANSCODE
    else -> AudioOutputMode.BITSTREAM
}

/**
 * Tech badges for the player OSD's top band (wave U3, Marquee spec §Player OSD; data st7): resolution,
 * HDR type (Dolby Vision names the source profile and enhancement layer), audio codec + channel layout
 * + output mode, frame rate (ticked when the AFR-applied [displayRefreshHz] matches it), file size, in
 * that order; parts that are unknown are left out, and SDR gets no HDR badge. [degradations] turn
 * their badge amber and say what happened (data st8): "1080p · SOFTWARE", "DV → HDR10", "AUDIO OFF",
 * "23.976 → 60 HZ"; the PCM and safe-audio rungs keep the audio text.
 */
internal fun osdTechBadges(
    width: Int,
    height: Int,
    hdrType: String?,
    audioCodec: String?,
    audioChannelCount: Int?,
    sizeBytes: Long?,
    dvProfile: String? = null,
    dvEnhancementLayer: String? = null,
    audioOutput: AudioOutputMode? = null,
    frameRate: Float? = null,
    displayRefreshHz: Float? = null,
    degradations: Set<PlaybackDegradation> = emptySet()
): List<OsdTechBadge> = listOfNotNull(
    if (width > 0 && height > 0) {
        val software = PlaybackDegradation.VC1_SOFTWARE in degradations
        val label = resolutionLabel(width, height)
        OsdTechBadge(if (software) "$label · SOFTWARE" else label, degraded = software)
    } else null,
    hdrType?.takeUnless { it == HDR_TYPE_SDR }?.let {
        if (PlaybackDegradation.DOLBY_VISION in degradations) {
            OsdTechBadge("DV → ${it.uppercase(Locale.ROOT)}", degraded = true)
        } else {
            OsdTechBadge(hdrBadgeLabel(it, dvProfile, dvEnhancementLayer))
        }
    },
    if (PlaybackDegradation.AUDIO_OFF in degradations) {
        OsdTechBadge("AUDIO OFF", degraded = true)
    } else audioCodec?.let { codec ->
        val layout = audioChannelCount?.let(TrackFormatNames::getChannelLayoutName)
        val name = listOfNotNull(codec, layout).joinToString(" ")
        OsdTechBadge(
            listOfNotNull(name, audioOutput?.label).joinToString(" · ").uppercase(Locale.ROOT),
            degraded = PlaybackDegradation.AUDIO_PCM in degradations || PlaybackDegradation.AUDIO_SAFE in degradations
        )
    },
    frameRate?.takeIf { it > 0f }?.let { fps ->
        val label = frameRateLabel(fps)
        when {
            displayRefreshHz == null -> OsdTechBadge(label)
            PlaybackDegradation.FRAME_RATE in degradations ->
                OsdTechBadge("$label → ${frameRateLabel(displayRefreshHz)} HZ", degraded = true)
            isRefreshMatch(displayRefreshHz, fps) -> OsdTechBadge(label, matched = true)
            // A mismatch the caller does not report (track AFR still switching) stays plain.
            else -> OsdTechBadge(label)
        }
    },
    sizeBytes?.takeIf { it > 0L }?.let { OsdTechBadge(fileSizeLabel(it)) }
)

/** "DOLBY VISION P7 FEL": the source profile and enhancement layer when known (a converted P7 still reads P7). */
private fun hdrBadgeLabel(hdrType: String, dvProfile: String?, dvEnhancementLayer: String?): String {
    if (hdrType != HDR_TYPE_DOLBY_VISION) return hdrType.uppercase(Locale.ROOT)
    val profile = dvProfile?.trim()?.toIntOrNull()?.let { "P$it" }
    val layer = dvEnhancementLayer?.takeIf { it == DV_LAYER_FEL || it == DV_LAYER_MEL }
    return listOfNotNull(hdrType.uppercase(Locale.ROOT), profile, layer).joinToString(" ")
}

/** "23.976", "24", "29.97": three decimals, trailing zeros dropped. */
internal fun frameRateLabel(fps: Float): String =
    String.format(Locale.ROOT, "%.3f", fps).trimEnd('0').trimEnd('.')

/**
 * True when [refreshHz] shows [fps] cleanly: a whole multiple (24 Hz for 23.976, 48 for 24, 50 for 25)
 * within [FRAME_RATE_MATCH_TOLERANCE] of the nearest one.
 */
internal fun isRefreshMatch(refreshHz: Float, fps: Float): Boolean {
    if (refreshHz <= 0f || fps <= 0f) return false
    val ratio = refreshHz / fps
    val nearestMultiple = kotlin.math.round(ratio).coerceAtLeast(1f)
    return kotlin.math.abs(ratio - nearestMultiple) / nearestMultiple <= FRAME_RATE_MATCH_TOLERANCE
}

private const val FRAME_RATE_MATCH_TOLERANCE = 0.02f

/** Enhancement-layer names the diagnostics record (dvElType) and the DV badge shows. */
internal const val DV_LAYER_FEL = "FEL"
internal const val DV_LAYER_MEL = "MEL"

/**
 * The selected audio track's codec name: the renderer's [live] format wins when it is that track
 * (the dvmkv extractor may refine core DTS to DTS-HD after the track list froze); right after an
 * audio switch the renderer can still hold the old track's format, so an id mismatch falls back
 * to the track list.
 */
internal fun osdAudioCodec(selected: TrackInfo?, live: Format?): String? {
    if (selected == null) return null
    val liveName = live?.takeIf { it.id == selected.trackId }
        ?.let { TrackFormatNames.formatName(it.sampleMimeType, it.codecs) }
    return liveName ?: selected.codec
}

/** Short resolution label from the larger dimension ("4K", "1080p", ..., else "<short side>p"). */
internal fun resolutionLabel(width: Int, height: Int): String {
    val maxDim = maxOf(width, height)
    return when {
        maxDim >= 3600 -> "4K"
        maxDim >= 2400 -> "1440p"
        maxDim >= 1800 -> "1080p"
        maxDim >= 1200 -> "720p"
        maxDim >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
}

/** Binary units, one decimal from a GiB up ("23.4 GB"), whole MiB below ("850 MB"). */
internal fun fileSizeLabel(bytes: Long): String =
    if (bytes >= GIB) String.format(Locale.ROOT, "%.1f GB", bytes / GIB.toDouble())
    else String.format(Locale.ROOT, "%.0f MB", bytes / MIB.toDouble())

private const val MIB = 1_048_576L
private const val GIB = 1_073_741_824L
private const val HDR_TYPE_SDR = "SDR"
private const val HDR_TYPE_DOLBY_VISION = "Dolby Vision"
private const val HDR_TYPE_HDR10 = "HDR10"
private const val HDR_TYPE_HLG = "HLG"
/** An output type from [friendlyVideoHdrType] the TV receives as HDR (Spec 62 D7). */
internal fun isHdrOutputType(type: String?): Boolean =
    type == HDR_TYPE_DOLBY_VISION || type == HDR_TYPE_HDR10 || type == HDR_TYPE_HLG

/**
 * Human-friendly HDR/output type for the diagnostics card and the OSD badge — reflects what is
 * actually output, not just the source track mime. When DV7 is stripped to the
 * HDR10 base layer the output is HDR10/SDR even though the track mime is DV.
 */
internal fun friendlyVideoHdrType(
    mimeType: String?,
    colorTransfer: Int?,
    effectiveModeName: String?,
    dvConversionOccurred: Boolean
): String? {
    val isDolbyVisionMime = mimeType?.lowercase() == MimeTypes.VIDEO_DOLBY_VISION
    fun fromTransfer(): String? = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> HDR_TYPE_HDR10
        C.COLOR_TRANSFER_HLG -> HDR_TYPE_HLG
        C.COLOR_TRANSFER_SDR -> HDR_TYPE_SDR
        else -> null
    }
    return when {
        // Ignore DV data: output is HDR10/SDR, never Dolby Vision.
        effectiveModeName == "HDR10_BASE_LAYER" -> fromTransfer() ?: HDR_TYPE_HDR10
        // DV RPU stripped: output is HDR10 base layer, never Dolby Vision.
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: HDR_TYPE_HDR10
        // DV8.1 conversion, but only label it DV if a conversion actually ran. AUTO arms
        // this mode for every file on a DV display, so plain SDR/HDR10 lands here too.
        effectiveModeName == "DV81_LIBDOVI" && dvConversionOccurred -> HDR_TYPE_DOLBY_VISION
        effectiveModeName == "DV81_LIBDOVI" -> fromTransfer()
        // Native DV passthrough.
        isDolbyVisionMime -> HDR_TYPE_DOLBY_VISION
        else -> fromTransfer()
    }
}
