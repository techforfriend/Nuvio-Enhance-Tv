package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Spec 61 Q2: natural end when the position runs past the duration and audio has ended. */
class PlaybackOverrunTest {

    private val duration = 2_400_000L

    private fun action(
        positionMs: Long,
        durationMs: Long = duration,
        isLive: Boolean = false,
        playerEnded: Boolean = false,
        alreadyHandled: Boolean = false,
        hasRenderedFirstFrame: Boolean = true,
        audioEnded: () -> Boolean = { true }
    ) = exoOverrunAction(
        positionMs = positionMs,
        durationMs = durationMs,
        isLive = isLive,
        playerEnded = playerEnded,
        alreadyHandled = alreadyHandled,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        audioEnded = audioEnded
    )

    @Test
    fun `position past the grace with audio ended forces the end`() {
        assertEquals(ExoOverrunAction.FORCE_END, action(duration + EXO_END_OVERRUN_GRACE_MS))
    }

    @Test
    fun `overrun inside the grace does nothing`() {
        assertEquals(ExoOverrunAction.NONE, action(duration + EXO_END_OVERRUN_GRACE_MS - 1))
    }

    @Test
    fun `audio still playing past the duration is a wrong duration, not an end`() {
        assertEquals(ExoOverrunAction.NONE, action(duration + 60_000L, audioEnded = { false }))
    }

    @Test
    fun `normal playback never reads the audio renderer`() {
        var asked = false
        val probe = { asked = true; true }
        assertEquals(ExoOverrunAction.NONE, action(duration - 5_000L, audioEnded = probe))
        assertEquals(ExoOverrunAction.NONE, action(duration + 5_000L, alreadyHandled = true, audioEnded = probe))
        assertFalse(asked)
    }

    @Test
    fun `unknown duration, live and a real ENDED are left alone`() {
        assertEquals(ExoOverrunAction.NONE, action(5_000L, durationMs = 0L))
        assertEquals(ExoOverrunAction.NONE, action(5_000L, durationMs = Long.MIN_VALUE + 1)) // C.TIME_UNSET
        assertEquals(ExoOverrunAction.NONE, action(duration + 5_000L, isLive = true))
        assertEquals(ExoOverrunAction.NONE, action(duration + 5_000L, playerEnded = true))
    }

    @Test
    fun `no forced end before the first frame`() {
        assertEquals(ExoOverrunAction.NONE, action(duration + 5_000L, hasRenderedFirstFrame = false))
    }

    @Test
    fun `a forced end fires once, and re-arms after a seek back`() {
        assertEquals(ExoOverrunAction.NONE, action(duration + 5_000L, alreadyHandled = true))
        assertEquals(ExoOverrunAction.REARM, action(duration - 60_000L, alreadyHandled = true))
        // A forward seek clamps to the duration: still the end, not a seek back.
        assertEquals(ExoOverrunAction.NONE, action(duration, alreadyHandled = true))
        assertEquals(ExoOverrunAction.NONE, action(duration - 60_000L))
    }
}
