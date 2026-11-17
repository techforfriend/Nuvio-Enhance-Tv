package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 93 P5 (D1-D3). */
class ResumeRewindTest {

    @Test
    fun `a saved start moves back by the setting`() {
        assertEquals(595_000L, ResumeRewind.rewound(600_000L, 5))
        assertEquals(570_000L, ResumeRewind.rewound(600_000L, 30))
    }

    @Test
    fun `never below 0`() {
        assertEquals(0L, ResumeRewind.rewound(3_000L, 5))
    }

    @Test
    fun `Off or no position leaves the start alone`() {
        assertEquals(600_000L, ResumeRewind.rewound(600_000L, 0))
        assertEquals(0L, ResumeRewind.rewound(0L, 5))
    }

    @Test
    fun `a pause of 30 s or more rewinds`() {
        assertEquals(
            595_000L,
            ResumeRewind.afterPause(pausedAtMs = 1_000L, pausedPositionMs = 600_000L, nowMs = 31_000L, positionMs = 600_000L, rewindSeconds = 5),
        )
    }

    @Test
    fun `a shorter pause plays on`() {
        assertNull(ResumeRewind.afterPause(1_000L, 600_000L, 30_999L, 600_000L, 5))
    }

    @Test
    fun `no user pause on record or Off plays on`() {
        assertNull(ResumeRewind.afterPause(null, 600_000L, 100_000L, 600_000L, 5))
        assertNull(ResumeRewind.afterPause(1_000L, 600_000L, 100_000L, 600_000L, 0))
    }

    @Test
    fun `a seek while paused keeps the chosen spot`() {
        assertNull(ResumeRewind.afterPause(1_000L, 600_000L, 100_000L, 540_000L, 5))
    }

    @Test
    fun `a small position drift while paused still rewinds`() {
        assertEquals(595_400L, ResumeRewind.afterPause(1_000L, 600_000L, 100_000L, 600_400L, 5))
    }

    @Test
    fun `a pause at the start clamps at 0`() {
        assertEquals(0L, ResumeRewind.afterPause(1_000L, 2_000L, 100_000L, 2_000L, 5))
    }
}
