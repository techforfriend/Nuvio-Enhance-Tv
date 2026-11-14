package com.nuvio.tv.core.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 70 R2 (D6): the freeze detector in [PlayerPlaybackAnalyticsDiagnostics] that the policy reads. */
class PlayerPlaybackAnalyticsFreezeTest {

    private var position = 0L
    private var playing = true
    private var now = 0L
    private val player = mockk<Player>(relaxed = true).also { p ->
        every { p.currentPosition } answers { position }
        every { p.bufferedPosition } answers { position + 20_000L }
        every { p.playbackState } returns Player.STATE_READY
        every { p.playWhenReady } answers { playing }
        every { p.isPlaying } answers { playing }
        every { p.playbackParameters } returns PlaybackParameters.DEFAULT
    }
    private val diagnostics = PlayerPlaybackAnalyticsDiagnostics()

    private fun tick() = diagnostics.recordProgressSnapshot(player, hasRenderedFirstFrame = true, now = now)

    /** The production cadence: one snapshot every 500 ms for [ms]. */
    private fun run(ms: Long) {
        repeat((ms / 500L).toInt()) {
            now += 500L
            tick()
        }
    }

    private fun start(atPosition: Long) {
        position = atPosition
        now = 1_000L
        tick()
    }

    @Test
    fun `position stuck while playing counts up`() {
        start(60_000L)
        run(10_000L)
        assertEquals(10_000L, diagnostics.currentPositionStallMs)
    }

    @Test
    fun `a pause does not count, and a freeze right after resume counts from the resume`() {
        start(60_000L)
        run(3_000L) // 3 s frozen before the pause
        assertEquals(3_000L, diagnostics.currentPositionStallMs)
        playing = false
        run(30_000L) // a long pause clears it
        assertEquals(0L, diagnostics.currentPositionStallMs)
        playing = true
        run(10_000L) // resumed, picture frozen
        assertEquals(10_000L, diagnostics.currentPositionStallMs)
    }

    @Test
    fun `a gap between snapshots is not counted as a freeze`() {
        start(60_000L)
        run(3_000L)
        now += 15_000L // tick stopped: error, then the in-place retry
        tick()
        assertEquals(0L, diagnostics.currentPositionStallMs)
        run(1_000L)
        assertEquals(1_000L, diagnostics.currentPositionStallMs)
    }

    @Test
    fun `a late tick within 2 s still counts, a longer gap does not`() {
        start(60_000L)
        run(3_000L)
        now += 2_000L // late tick, still watched
        tick()
        assertEquals(5_000L, diagnostics.currentPositionStallMs)
        now += 2_001L
        tick()
        assertEquals(0L, diagnostics.currentPositionStallMs)
    }

    @Test
    fun `advancing or jumping back resets the count`() {
        start(60_000L)
        run(5_000L)
        position = 60_500L
        run(500L)
        assertEquals(0L, diagnostics.currentPositionStallMs)
        position = 20_000L // a backward seek the tick never saw as buffering
        run(500L)
        run(500L)
        assertEquals(500L, diagnostics.currentPositionStallMs)
    }

    @Test
    fun `position moving reads false only once the stall passes a few ticks`() {
        start(60_000L)
        position = 60_500L
        run(500L)
        assertEquals(true, diagnostics.isPositionMoving())
        run(2_000L)
        assertEquals(false, diagnostics.isPositionMoving())
    }

    @Test
    fun `small backward jitter during a freeze does not reset the count`() {
        start(60_000L)
        position = 59_990L
        run(5_000L)
        position = 60_000L
        run(5_000L)
        assertEquals(10_000L, diagnostics.currentPositionStallMs)
    }
}
