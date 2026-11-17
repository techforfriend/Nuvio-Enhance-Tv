package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * The trailer pool's load control: today's buffer sizes, plus a quicker first start for card previews only (Spec 123
 * C7). While [quickStart] reads true, a start that is not a rebuffer also goes once [QUICK_START_BUFFER_MS] is
 * buffered; hero and Detail trailers (flag false) keep the 5 s start, and every rebuffer keeps its 10 s.
 * [quickStart] is read on ExoPlayer's playback thread.
 */
@UnstableApi
class TrailerLoadControl(
    private val quickStart: () -> Boolean,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
) : DefaultLoadControl(
    allocator,
    /* minBufferMs = */ 30_000,
    /* maxBufferMs = */ 120_000,
    /* bufferForPlaybackMs = */ 5_000,
    /* bufferForPlaybackAfterRebufferMs = */ 10_000,
    DEFAULT_TARGET_BUFFER_BYTES,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
) {
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (quickStart() && !parameters.rebuffering) {
            val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
            if (bufferedUs >= QUICK_START_BUFFER_MS * 1_000L) return true
        }
        return super.shouldStartPlayback(parameters)
    }

    companion object {
        const val QUICK_START_BUFFER_MS = 2_000L
    }
}
