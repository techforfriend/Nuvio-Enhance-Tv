package com.nuvio.tv.core.player

/**
 * First-frame watchdog codec recovery ladder after [PlayerFirstFrameWatchdogPolicy]
 * force-play is skipped or already applied — DV mode downgrade, then VC1 software,
 * then VC1 track-selection bypass; then (Spec 86 R1/D1) for a selected video track still
 * drawing nothing (black picture, sound playing) on a normal start (D5): one same-stream rebuild
 * per black start, then the next source.
 */
internal object PlayerFirstFrameCodecRecoveryPolicy {

    /**
     * [blackStartRebuildDone] (this black start already had R1's same-stream rebuild) picks
     * NextSource over RebuildSameStream. [errorOnScreen] (its own handling owns the player),
     * [inBackground] (no surface, so no frame is expected), [primingFromZero] (a START_PRIME resume
     * start still priming from 0: left out by D5, it keeps the startup watchdog's fallback; its
     * playhead is the prime's ~0, not the resume target) or an unselected video track skips both.
     * Once the prime frame drew and the player sought to the target, a black start there is a
     * normal one (playhead = the target) and takes the rungs.
     */
    data class Input(
        val playWhenReady: Boolean,
        val isManualDv81Mode2Active: Boolean,
        val dv7Mode1AlreadyForced: Boolean,
        val currentVideoTrackIsLikelyVc1: Boolean,
        val isVc1SoftwareFallbackActive: Boolean,
        val currentVideoTrackSelected: Boolean,
        val isVc1TrackSelectionBypassActive: Boolean,
        val blackStartRebuildDone: Boolean,
        val errorOnScreen: Boolean,
        val inBackground: Boolean,
        val primingFromZero: Boolean,
    )

    sealed class RecoveryAction {
        data object None : RecoveryAction()
        data object RetryDv7Mode1 : RecoveryAction()
        data object RetryVc1Software : RecoveryAction()
        data object RetryVc1TrackBypass : RecoveryAction()
        data object RebuildSameStream : RecoveryAction()
        data object NextSource : RecoveryAction()
    }

    fun evaluateAfterWatchdogTimeout(input: Input): RecoveryAction {
        if (!input.playWhenReady) return RecoveryAction.None

        if (input.isManualDv81Mode2Active && !input.dv7Mode1AlreadyForced) {
            return RecoveryAction.RetryDv7Mode1
        }
        if (input.currentVideoTrackIsLikelyVc1 && !input.isVc1SoftwareFallbackActive) {
            return RecoveryAction.RetryVc1Software
        }
        if (input.currentVideoTrackIsLikelyVc1 &&
            !input.currentVideoTrackSelected &&
            input.isVc1SoftwareFallbackActive &&
            !input.isVc1TrackSelectionBypassActive
        ) {
            return RecoveryAction.RetryVc1TrackBypass
        }
        // Spec 86 R1/D1. An unselected video track is a refused format: Spec 85's mpv hop and the
        // startup watchdog's box own it (another source would not change this TV's decoders).
        if (!input.currentVideoTrackSelected || input.errorOnScreen || input.inBackground || input.primingFromZero) {
            return RecoveryAction.None
        }
        return if (input.blackStartRebuildDone) RecoveryAction.NextSource else RecoveryAction.RebuildSameStream
    }
}
