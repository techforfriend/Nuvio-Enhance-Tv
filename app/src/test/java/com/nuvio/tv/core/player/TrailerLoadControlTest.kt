package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 123 C7: card previews start at 2 s buffered; everything else, and every rebuffer, as before. */
class TrailerLoadControlTest {
    private var quickStart = false

    // Prepared: the player's byte target is set, so with nothing allocated only the time thresholds decide.
    private val control = TrailerLoadControl({ quickStart }).apply { onPrepared(PlayerId.UNSET) }

    private fun params(bufferedMs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET,
        Timeline.EMPTY,
        LoadControl.EMPTY_MEDIA_PERIOD_ID,
        /* playbackPositionUs = */ 0L,
        bufferedMs * 1_000L,
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ true,
        rebuffering,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs = */ C.TIME_UNSET
    )

    @Test
    fun `without the flag a start waits 5 s`() {
        assertFalse(control.shouldStartPlayback(params(2_100)))
        assertFalse(control.shouldStartPlayback(params(4_900)))
        assertTrue(control.shouldStartPlayback(params(5_100)))
    }

    @Test
    fun `with the flag a start goes at 2 s`() {
        quickStart = true
        assertFalse(control.shouldStartPlayback(params(1_900)))
        assertTrue(control.shouldStartPlayback(params(2_100)))
    }

    @Test
    fun `a rebuffer keeps its 10 s with the flag`() {
        quickStart = true
        assertFalse(control.shouldStartPlayback(params(5_100, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(params(10_100, rebuffering = true)))
    }
}
