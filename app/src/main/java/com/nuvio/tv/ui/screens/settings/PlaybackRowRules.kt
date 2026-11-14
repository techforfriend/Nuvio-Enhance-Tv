package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.SubtitleEdgeStyle

/** Every Playback row that only applies while another setting allows it (Spec 31 S7c2). */
internal enum class PlaybackRow {
    AUTO_SKIP,
    SOURCE_SCOPE,
    REUSE_CACHE,
    ALLOWED_ADDONS,
    REGEX,
    FALLBACK,
    STILL_WATCHING,
    STILL_WATCHING_THRESHOLD,
    REUSE_BINGE_GROUP,
    DOWNMIX,
    /** Number of channels and Maintain original audio. */
    DOWNMIX_OPTIONS,
    TUNNELING,
    OPTICAL,
    MAT,
    DV5_TO_DV81,
    INJECT_HDR10,
    OUTLINE_COLOR,
    /** Target buffer size and Allow larger target buffer. */
    TARGET_BUFFER,
    /** Wait after display switch (Spec 62 H1, D10): only Frame rate matching switches the display mode. */
    AFR_SETTLE_HOLD,
    /** HDR text colour, HDR brightness and Dim player controls (Spec 62 H2, D7). */
    HDR_SUBTITLE_STYLE
}

/**
 * Why [row] is dimmed under [settings], or null while it applies. A dimmed row stays focusable and
 * shows the reason as its subtitle (Q3). The conditions are the ones the old screen hid or
 * disabled rows by.
 */
@StringRes
internal fun playbackRowDimReason(row: PlaybackRow, settings: PlayerSettings): Int? {
    val nextEpisode = settings.streamAutoPlayNextEpisodeEnabled
    val mode = settings.streamAutoPlayMode
    return when (row) {
        PlaybackRow.AUTO_SKIP ->
            R.string.playback_needs_skip_intro.takeUnless { settings.skipIntroEnabled }
        PlaybackRow.SOURCE_SCOPE,
        PlaybackRow.ALLOWED_ADDONS ->
            R.string.playback_needs_auto_selection.takeIf { mode == StreamAutoPlayMode.MANUAL }
        PlaybackRow.REUSE_CACHE ->
            R.string.playback_needs_reuse_last_link.takeUnless { settings.streamReuseLastLinkEnabled }
        PlaybackRow.REGEX ->
            R.string.playback_needs_regex_mode.takeUnless { mode == StreamAutoPlayMode.REGEX_MATCH }
        PlaybackRow.FALLBACK -> when {
            !nextEpisode -> R.string.playback_needs_next_episode
            mode != StreamAutoPlayMode.MANUAL -> R.string.playback_needs_manual_selection
            else -> null
        }
        PlaybackRow.STILL_WATCHING ->
            R.string.playback_needs_next_episode.takeUnless { nextEpisode }
        PlaybackRow.STILL_WATCHING_THRESHOLD -> when {
            !nextEpisode -> R.string.playback_needs_next_episode
            !settings.stillWatchingEnabled -> R.string.playback_needs_still_watching
            else -> null
        }
        PlaybackRow.REUSE_BINGE_GROUP ->
            R.string.playback_needs_prefer_binge_group
                .takeUnless { settings.streamAutoPlayPreferBingeGroupForNextEpisode }
        PlaybackRow.DOWNMIX ->
            R.string.playback_needs_app_decoders.takeUnless { settings.isPreferAppDecoder }
        PlaybackRow.DOWNMIX_OPTIONS -> when {
            !settings.isPreferAppDecoder -> R.string.playback_needs_app_decoders
            !settings.downmixEnabled -> R.string.playback_needs_downmix
            else -> null
        }
        PlaybackRow.TUNNELING ->
            R.string.playback_not_with_app_decoders.takeUnless { settings.isTunnelingCompatible }
        PlaybackRow.OPTICAL ->
            R.string.playback_not_with_device_decoders.takeIf { settings.decoderPriority == 0 }
        PlaybackRow.MAT ->
            R.string.playback_needs_truehd.takeUnless { settings.allowTrueHdPassthrough }
        PlaybackRow.DV5_TO_DV81 ->
            R.string.playback_needs_dv81.takeUnless { settings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI }
        PlaybackRow.INJECT_HDR10 ->
            R.string.playback_needs_dv_strip.takeUnless {
                settings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV ||
                    settings.dv7HandlingMode == Dv7HandlingMode.HDR10_BASE_LAYER
            }
        PlaybackRow.OUTLINE_COLOR ->
            R.string.playback_needs_outline_edge
                .takeUnless { settings.subtitleStyle.edgeStyle == SubtitleEdgeStyle.OUTLINE }
        PlaybackRow.TARGET_BUFFER ->
            R.string.playback_not_with_managed_budget.takeIf { settings.bufferBudgetManaged }
        PlaybackRow.AFR_SETTLE_HOLD ->
            R.string.playback_needs_frame_rate_matching
                .takeIf { settings.frameRateMatchingMode == FrameRateMatchingMode.OFF }
        PlaybackRow.HDR_SUBTITLE_STYLE ->
            R.string.playback_needs_hdr_subtitle_style.takeUnless { settings.subtitleStyle.hdrStyleEnabled }
    }
}

/** What the display cannot do of what frame rate / resolution matching asks of it. */
internal enum class AfrProblem { FRAME_RATE, RESOLUTION, BOTH }

/**
 * The capability warning under Frame rate matching: a switch that is on while the display reports
 * a single mode for it. Null when there is none, or the display could not be read (no claim either
 * way).
 */
internal fun afrCapabilityProblem(
    snapshot: DisplayCapabilities.Snapshot,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
): AfrProblem? {
    if (!snapshot.apiSupported) return null
    val frameRate = frameRateMatchingMode != FrameRateMatchingMode.OFF && !snapshot.supportsFrameRateSwitching
    val resolution = resolutionMatchingEnabled && !snapshot.supportsResolutionSwitching
    return when {
        frameRate && resolution -> AfrProblem.BOTH
        frameRate -> AfrProblem.FRAME_RATE
        resolution -> AfrProblem.RESOLUTION
        else -> null
    }
}
