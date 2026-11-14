package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRowRulesTest {

    private fun reason(row: PlaybackRow, settings: PlayerSettings) = playbackRowDimReason(row, settings)

    @Test
    fun `wait after display switch needs frame rate matching`() {
        assertEquals(
            R.string.playback_needs_frame_rate_matching,
            reason(PlaybackRow.AFR_SETTLE_HOLD, PlayerSettings())
        )
        // Resolution matching alone never switches the display mode (D10).
        assertEquals(
            R.string.playback_needs_frame_rate_matching,
            reason(PlaybackRow.AFR_SETTLE_HOLD, PlayerSettings(resolutionMatchingEnabled = true))
        )
        listOf(FrameRateMatchingMode.START, FrameRateMatchingMode.START_STOP).forEach { mode ->
            assertNull(reason(PlaybackRow.AFR_SETTLE_HOLD, PlayerSettings(frameRateMatchingMode = mode)))
        }
    }

    @Test
    fun `auto-skip rows need Skip Intro`() {
        assertEquals(R.string.playback_needs_skip_intro, reason(PlaybackRow.AUTO_SKIP, PlayerSettings(skipIntroEnabled = false)))
        assertNull(reason(PlaybackRow.AUTO_SKIP, PlayerSettings(skipIntroEnabled = true)))
    }

    @Test
    fun `source scope follows the same rule as allowed addons`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            val settings = PlayerSettings(streamAutoPlayMode = mode)
            assertEquals(reason(PlaybackRow.ALLOWED_ADDONS, settings), reason(PlaybackRow.SOURCE_SCOPE, settings))
        }
    }

    @Test
    fun `last link cache needs Reuse last link`() {
        assertEquals(
            R.string.playback_needs_reuse_last_link,
            reason(PlaybackRow.REUSE_CACHE, PlayerSettings(streamReuseLastLinkEnabled = false))
        )
        assertNull(reason(PlaybackRow.REUSE_CACHE, PlayerSettings(streamReuseLastLinkEnabled = true)))
    }

    @Test
    fun `allowed addons apply to every automatic mode, not Manual`() {
        assertEquals(
            R.string.playback_needs_auto_selection,
            reason(PlaybackRow.ALLOWED_ADDONS, PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL))
        )
        listOf(StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.REGEX_MATCH, StreamAutoPlayMode.QUALITY_RANK)
            .forEach { mode ->
                assertNull(reason(PlaybackRow.ALLOWED_ADDONS, PlayerSettings(streamAutoPlayMode = mode)))
            }
    }

    @Test
    fun `regex pattern applies only to the regex mode`() {
        assertNull(reason(PlaybackRow.REGEX, PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH)))
        assertEquals(
            R.string.playback_needs_regex_mode,
            reason(PlaybackRow.REGEX, PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK))
        )
    }

    @Test
    fun `fallback needs next episode first, then Manual selection`() {
        val manualOn = PlayerSettings(
            streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL
        )
        assertNull(reason(PlaybackRow.FALLBACK, manualOn))
        assertEquals(
            R.string.playback_needs_next_episode,
            reason(PlaybackRow.FALLBACK, manualOn.copy(streamAutoPlayNextEpisodeEnabled = false, streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM))
        )
        assertEquals(
            R.string.playback_needs_manual_selection,
            reason(PlaybackRow.FALLBACK, manualOn.copy(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM))
        )
    }

    @Test
    fun `still watching rows follow next episode, the threshold also the prompt`() {
        val on = PlayerSettings(streamAutoPlayNextEpisodeEnabled = true, stillWatchingEnabled = true)
        assertNull(reason(PlaybackRow.STILL_WATCHING, on))
        assertNull(reason(PlaybackRow.STILL_WATCHING_THRESHOLD, on))
        val noNext = on.copy(streamAutoPlayNextEpisodeEnabled = false)
        assertEquals(R.string.playback_needs_next_episode, reason(PlaybackRow.STILL_WATCHING, noNext))
        assertEquals(R.string.playback_needs_next_episode, reason(PlaybackRow.STILL_WATCHING_THRESHOLD, noNext))
        assertEquals(
            R.string.playback_needs_still_watching,
            reason(PlaybackRow.STILL_WATCHING_THRESHOLD, on.copy(stillWatchingEnabled = false))
        )
    }

    @Test
    fun `downmix needs app decoders, its options also downmix`() {
        val appOn = PlayerSettings(decoderPriority = 2, downmixEnabled = true)
        assertNull(reason(PlaybackRow.DOWNMIX, appOn))
        assertNull(reason(PlaybackRow.DOWNMIX_OPTIONS, appOn))
        listOf(0, 1).forEach { priority ->
            val device = appOn.copy(decoderPriority = priority)
            assertEquals(R.string.playback_needs_app_decoders, reason(PlaybackRow.DOWNMIX, device))
            assertEquals(R.string.playback_needs_app_decoders, reason(PlaybackRow.DOWNMIX_OPTIONS, device))
        }
        assertEquals(
            R.string.playback_needs_downmix,
            reason(PlaybackRow.DOWNMIX_OPTIONS, appOn.copy(downmixEnabled = false))
        )
    }

    @Test
    fun `tunneling is out with app decoders, optical with device decoders only`() {
        assertEquals(R.string.playback_not_with_app_decoders, reason(PlaybackRow.TUNNELING, PlayerSettings(decoderPriority = 2)))
        assertNull(reason(PlaybackRow.TUNNELING, PlayerSettings(decoderPriority = 1)))
        assertEquals(R.string.playback_not_with_device_decoders, reason(PlaybackRow.OPTICAL, PlayerSettings(decoderPriority = 0)))
        assertNull(reason(PlaybackRow.OPTICAL, PlayerSettings(decoderPriority = 2)))
    }

    @Test
    fun `MAT needs TrueHD passthrough`() {
        assertEquals(R.string.playback_needs_truehd, reason(PlaybackRow.MAT, PlayerSettings(allowTrueHdPassthrough = false)))
        assertNull(reason(PlaybackRow.MAT, PlayerSettings(allowTrueHdPassthrough = true)))
    }

    @Test
    fun `DV rows follow Dolby Vision handling`() {
        Dv7HandlingMode.entries.forEach { mode ->
            val settings = PlayerSettings(dv7HandlingMode = mode)
            assertEquals(
                if (mode == Dv7HandlingMode.DV81_LIBDOVI) null else R.string.playback_needs_dv81,
                reason(PlaybackRow.DV5_TO_DV81, settings)
            )
            val strips = mode == Dv7HandlingMode.STRIP_DV || mode == Dv7HandlingMode.HDR10_BASE_LAYER
            assertEquals(
                if (strips) null else R.string.playback_needs_dv_strip,
                reason(PlaybackRow.INJECT_HDR10, settings)
            )
        }
    }

    @Test
    fun `afr warning names what the display cannot switch, nothing when unread`() {
        fun display(frameRate: Boolean, resolution: Boolean, api: Boolean = true) = DisplayCapabilities.Snapshot(
            supportsFrameRateSwitching = frameRate,
            supportsResolutionSwitching = resolution,
            supportedModes = emptyList(),
            currentModeId = 1,
            apiSupported = api
        )
        val single = display(frameRate = false, resolution = false)
        assertEquals(AfrProblem.BOTH, afrCapabilityProblem(single, FrameRateMatchingMode.START, true))
        assertEquals(AfrProblem.FRAME_RATE, afrCapabilityProblem(single, FrameRateMatchingMode.START_STOP, false))
        assertEquals(AfrProblem.RESOLUTION, afrCapabilityProblem(single, FrameRateMatchingMode.OFF, true))
        assertNull(afrCapabilityProblem(single, FrameRateMatchingMode.OFF, false))
        assertNull(afrCapabilityProblem(display(true, true), FrameRateMatchingMode.START, true))
        assertNull(afrCapabilityProblem(display(false, false, api = false), FrameRateMatchingMode.START, true))
    }

    @Test
    fun `Auto may run either engine`() {
        assertEquals(listOf(true, false, true), InternalPlayerEngine.entries.map { it.usesExoPlayer })
        assertEquals(listOf(false, true, true), InternalPlayerEngine.entries.map { it.usesMpv })
    }

    @Test
    fun `reuse binge group needs Prefer binge group`() {
        assertEquals(
            R.string.playback_needs_prefer_binge_group,
            reason(PlaybackRow.REUSE_BINGE_GROUP, PlayerSettings(streamAutoPlayPreferBingeGroupForNextEpisode = false))
        )
        assertNull(
            reason(PlaybackRow.REUSE_BINGE_GROUP, PlayerSettings(streamAutoPlayPreferBingeGroupForNextEpisode = true))
        )
    }

    @Test
    fun `HDR subtitle rows need the separate HDR style`() {
        assertEquals(
            R.string.playback_needs_hdr_subtitle_style,
            reason(PlaybackRow.HDR_SUBTITLE_STYLE, PlayerSettings())
        )
        assertNull(
            reason(
                PlaybackRow.HDR_SUBTITLE_STYLE,
                PlayerSettings(subtitleStyle = SubtitleStyleSettings(hdrStyleEnabled = true))
            )
        )
    }

    @Test
    fun `outline colour needs the Outline text edge`() {
        fun settings(edge: SubtitleEdgeStyle) = PlayerSettings(subtitleStyle = SubtitleStyleSettings(edgeStyle = edge))
        assertNull(reason(PlaybackRow.OUTLINE_COLOR, settings(SubtitleEdgeStyle.OUTLINE)))
        assertEquals(R.string.playback_needs_outline_edge, reason(PlaybackRow.OUTLINE_COLOR, settings(SubtitleEdgeStyle.NONE)))
        assertEquals(
            R.string.playback_needs_outline_edge,
            reason(PlaybackRow.OUTLINE_COLOR, settings(SubtitleEdgeStyle.DROP_SHADOW))
        )
    }

    @Test
    fun `target buffer rows dim under the managed budget`() {
        assertEquals(
            R.string.playback_not_with_managed_budget,
            reason(PlaybackRow.TARGET_BUFFER, PlayerSettings(bufferBudgetManaged = true))
        )
        assertNull(reason(PlaybackRow.TARGET_BUFFER, PlayerSettings(bufferBudgetManaged = false)))
    }
}
