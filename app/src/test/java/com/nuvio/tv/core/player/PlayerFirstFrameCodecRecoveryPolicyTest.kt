package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 21 P1: pins [PlayerFirstFrameCodecRecoveryPolicy], the first-frame watchdog's codec rungs
 * (DV mode 2 -> mode 1, then VC-1 software, then VC-1 track-selection bypass), then Spec 86 R1's
 * black-picture rungs (same-stream rebuild once, then the next source).
 */
class PlayerFirstFrameCodecRecoveryPolicyTest {

    private fun evaluate(
        playWhenReady: Boolean = true,
        mode2: Boolean = false,
        mode1Forced: Boolean = false,
        vc1: Boolean = false,
        vc1Software: Boolean = false,
        trackSelected: Boolean = false,
        vc1Bypass: Boolean = false,
        rebuilt: Boolean = false,
        error: Boolean = false,
        background: Boolean = false,
        priming: Boolean = false,
    ) = PlayerFirstFrameCodecRecoveryPolicy.evaluateAfterWatchdogTimeout(
        PlayerFirstFrameCodecRecoveryPolicy.Input(
            playWhenReady = playWhenReady,
            isManualDv81Mode2Active = mode2,
            dv7Mode1AlreadyForced = mode1Forced,
            currentVideoTrackIsLikelyVc1 = vc1,
            isVc1SoftwareFallbackActive = vc1Software,
            currentVideoTrackSelected = trackSelected,
            isVc1TrackSelectionBypassActive = vc1Bypass,
            blackStartRebuildDone = rebuilt,
            errorOnScreen = error,
            inBackground = background,
            primingFromZero = priming,
        )
    )

    @Test
    fun `not playing never recovers`() {
        assertEquals(RecoveryAction.None, evaluate(playWhenReady = false, mode2 = true))
        assertEquals(RecoveryAction.None, evaluate(playWhenReady = false, vc1 = true))
    }

    @Test
    fun `nothing eligible is no action`() {
        assertEquals(RecoveryAction.None, evaluate())
    }

    @Test
    fun `DV mode 2 retries at mode 1 once, ahead of the VC-1 rungs`() {
        assertEquals(RecoveryAction.RetryDv7Mode1, evaluate(mode2 = true, vc1 = true))
        assertEquals(RecoveryAction.None, evaluate(mode2 = true, mode1Forced = true))
    }

    @Test
    fun `VC-1 tries software first, then the selection bypass for an unselected track`() {
        assertEquals(RecoveryAction.RetryVc1Software, evaluate(vc1 = true))
        assertEquals(RecoveryAction.RetryVc1Software, evaluate(mode2 = true, mode1Forced = true, vc1 = true))
        assertEquals(RecoveryAction.RetryVc1TrackBypass, evaluate(vc1 = true, vc1Software = true))
    }

    @Test
    fun `VC-1 rungs end when the track is selected or the bypass is spent`() {
        // A selected track then takes R1's rebuild, not another VC-1 rung.
        assertEquals(RecoveryAction.RebuildSameStream, evaluate(vc1 = true, vc1Software = true, trackSelected = true))
        assertEquals(RecoveryAction.None, evaluate(vc1 = true, vc1Software = true, vc1Bypass = true))
    }

    @Test
    fun `VC-1 state without a VC-1 track is no action`() {
        assertEquals(RecoveryAction.None, evaluate(vc1Software = true))
    }

    @Test
    fun `a selected track with no frame rebuilds once, then goes to the next source`() {
        assertEquals(RecoveryAction.RebuildSameStream, evaluate(trackSelected = true))
        assertEquals(RecoveryAction.NextSource, evaluate(trackSelected = true, rebuilt = true))
        // The codec rungs still come first.
        assertEquals(RecoveryAction.RetryDv7Mode1, evaluate(mode2 = true, trackSelected = true, rebuilt = true))
        assertEquals(RecoveryAction.RetryVc1Software, evaluate(vc1 = true, trackSelected = true))
    }

    @Test
    fun `the black-picture rungs skip an unselected track, an error on screen and the background`() {
        assertEquals(RecoveryAction.None, evaluate(trackSelected = false, rebuilt = true))
        assertEquals(RecoveryAction.None, evaluate(trackSelected = true, error = true))
        assertEquals(RecoveryAction.None, evaluate(trackSelected = true, rebuilt = true, background = true))
        assertEquals(RecoveryAction.None, evaluate(playWhenReady = false, trackSelected = true))
    }

    @Test
    fun `a START_PRIME resume start keeps the startup watchdog's fallback (D5)`() {
        assertEquals(RecoveryAction.None, evaluate(trackSelected = true, priming = true))
        assertEquals(RecoveryAction.None, evaluate(trackSelected = true, rebuilt = true, priming = true))
        // The codec rungs are unchanged by it.
        assertEquals(RecoveryAction.RetryDv7Mode1, evaluate(mode2 = true, trackSelected = true, priming = true))
    }
}
