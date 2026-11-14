package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.FailureReason
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec 35 R3: judge the playing file's length against the metadata runtime once per stream, on
 * the ExoPlayer progress tick. The tick calls this only for a stream the placeholder duration
 * gate did not reject, so a rejected stream never also warns (approved sandbox
 * `spec-35-runtime-warning` shape A). A mismatch pauses on the first frame, rewinds to 0:00 and
 * raises [PlayerUiState.runtimeMismatch]; MPV has no such tick and is not checked.
 *
 * Waits (without spending the one check) until the meta fetch supplies a runtime, but only for
 * [RuntimeMismatchPolicy.MAX_WARN_POSITION_MS] of playback. The runtime is the player's own meta
 * ([runtimeMismatchCheckMinutes]), never [PlayerRuntimeController.expectedRuntimeMinutes]: a
 * Continue Watching route carries the last playback's duration there.
 */
internal fun PlayerRuntimeController.checkRuntimeMismatch(player: ExoPlayer, durationMs: Long) {
    if (runtimeMismatchChecked || durationMs <= 0L) return
    val positionMs = player.currentPosition
    val runtimeMinutes = runtimeMismatchCheckMinutes(
        metaVideos, currentVideoId, currentSeason, currentEpisode, metaRuntime
    )
    if (runtimeMinutes == null && positionMs <= RuntimeMismatchPolicy.MAX_WARN_POSITION_MS) return
    runtimeMismatchChecked = true
    val mismatch = if (positionMs > RuntimeMismatchPolicy.MAX_WARN_POSITION_MS) {
        null
    } else {
        RuntimeMismatchPolicy.evaluate(
            durationMs = durationMs,
            expectedRuntimeMs = runtimeMinutes?.let { it * 60_000L },
            resumed = resumePositionApplied
        )
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "RUNTIME_MISMATCH warn=${mismatch != null} durationMs=$durationMs runtimeMin=${runtimeMinutes ?: -1} " +
            "positionMs=$positionMs resumed=$resumePositionApplied"
    )
    if (mismatch == null) return
    // Published before pause(): Media3 calls onIsPlayingChanged(false) synchronously, and its
    // schedulePauseOverlay must already see the warning to stay quiet. userPausedManually keeps
    // the AFR settle and track-AFR starts from resuming playback.
    hideControlsJob?.cancel()
    _uiState.update { it.copy(runtimeMismatch = mismatch, showControls = false) }
    userPausedManually = true
    player.pause()
    player.seekTo(0L)
}

/** The warning's buttons: Play anyway plays this stream; Pick another stream forgets it
 *  ([forgetRejectedStream]) and opens Sources. Any other play (a media key, the MediaSession)
 *  clears the warning in onIsPlayingChanged. */
internal fun PlayerRuntimeController.onRuntimeMismatchChoice(playAnyway: Boolean) {
    _uiState.update { it.copy(runtimeMismatch = null) }
    if (playAnyway) {
        userPausedManually = false
        _exoPlayer?.play()
    } else {
        forgetRejectedStream()
        showSourcesPanel()
    }
}

/**
 * Spec 42 P2 (F-2): a rejected stream was saved as this title's preferred source when it started
 * (last link + binge group). Drop both and record it as the wrong file (Spec 65 P5: its URLs and
 * release, 30 days), so neither Reuse last link, the remembered group nor auto-select brings it back.
 */
private fun PlayerRuntimeController.forgetRejectedStream() {
    recordPlaybackFailure(FailureReason.WRONG_FILE, playingListEntry())
    val key = streamCacheKey
    val cid = contentId
    scope.launch(NonCancellable) {
        key?.let { streamLinkCacheDataStore.evict(it) }
        cid?.let { bingeGroupCacheDataStore.replace(it, null) }
    }
}
