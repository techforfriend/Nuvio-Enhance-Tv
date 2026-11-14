package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.AFR_SETTLE_HOLD_DEFAULT_SECONDS
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

// Spec 62 H1 (D6): the adjustable settle hold after a real display-mode switch.
class AfrSettleHoldTest {

    @Test
    fun `default keeps the old fixed 2 s hold and 8 s track deadline`() {
        assertEquals(AFR_SETTLE_HOLD_DEFAULT_SECONDS, PlayerSettings().afrSettleHoldSeconds)
        val holdMs = afrSettleHoldMs(PlayerSettings().afrSettleHoldSeconds)
        assertEquals(2_000L, holdMs)
        assertEquals(8_000L, trackAfrDeadlineMs(holdMs))
    }

    @Test
    fun `deadline grows with the hold so a long wait is not clipped`() {
        val holdMs = afrSettleHoldMs(10)
        assertEquals(10_000L, holdMs)
        assertEquals(16_000L, trackAfrDeadlineMs(holdMs))
    }

    @Test
    fun `zero is off and out-of-range values clamp to 0-10 s`() {
        assertEquals(0L, afrSettleHoldMs(0))
        assertEquals(0L, afrSettleHoldMs(-3))
        assertEquals(10_000L, afrSettleHoldMs(42))
    }

    @Test
    fun `the hold left after a switch, whoever made it`() {
        // Spec 85: ExoPlayer switched the display 500 ms before mpv's start (a hop); mpv waits the rest.
        assertEquals(1_500L, afrSettleHoldRemainingMs(holdMs = 2_000L, lastSwitchElapsedMs = 10_000L, nowElapsedMs = 10_500L))
        // mpv's own preflight switched just now: the whole hold, as before.
        assertEquals(2_000L, afrSettleHoldRemainingMs(holdMs = 2_000L, lastSwitchElapsedMs = 10_000L, nowElapsedMs = 10_000L))
        // Settled long ago, no switch yet, or the hold is off.
        assertEquals(0L, afrSettleHoldRemainingMs(holdMs = 2_000L, lastSwitchElapsedMs = 10_000L, nowElapsedMs = 60_000L))
        assertEquals(0L, afrSettleHoldRemainingMs(holdMs = 2_000L, lastSwitchElapsedMs = 0L, nowElapsedMs = 60_000L))
        assertEquals(0L, afrSettleHoldRemainingMs(holdMs = 0L, lastSwitchElapsedMs = 10_000L, nowElapsedMs = 10_000L))
        // Never more than the hold.
        assertEquals(2_000L, afrSettleHoldRemainingMs(holdMs = 2_000L, lastSwitchElapsedMs = 10_000L, nowElapsedMs = 9_000L))
    }
}
