package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.core.util.safeHost
import kotlinx.coroutines.flow.update

/**
 * Spec 70 R2 (D5): acts on a frozen picture from the progress tick, after the detector has taken
 * this tick's snapshot. Rung 1 seeks half a second before [positionMs] (the app playhead); rung 2 is the dead-source
 * failover (toast, "trying next source (n/3)"; with no source list in hand it loads one first). When no
 * source is left, either way, the error box shows the generic "Playback error" over the frozen
 * picture, as the failover's own empty-list path does.
 *
 * @return true when a failover is under way: the caller ends its tick, the next stream restarts it.
 */
internal fun PlayerRuntimeController.maybeRecoverFrozenPicture(player: Player, positionMs: Long): Boolean {
    val nowMs = SystemClock.elapsedRealtime()
    val stalledForMs = playbackAnalyticsDiagnostics.currentPositionStallMs
    val decision = PlayerFreezeRecoveryPolicy.evaluate(
        PlayerFreezeRecoveryPolicy.Input(
            stalledForMs = stalledForMs,
            nowMs = nowMs,
            lastSeekAtMs = freezeRecoverySeekAtMs,
            failoverDone = freezeFailoverDone,
            errorShown = _uiState.value.error != null
        )
    )
    return when (decision) {
        PlayerFreezeRecoveryPolicy.Decision.NONE -> false
        PlayerFreezeRecoveryPolicy.Decision.SEEK -> {
            freezeRecoverySeekAtMs = nowMs
            // Half a second back, as the storm/snap rollbacks: Media3 may skip a seek to the
            // position it already holds, and the jump also restarts the detector's count.
            val target = (positionMs - PlayerFreezeRecoveryPolicy.SEEK_BACK_MS).coerceAtLeast(0L)
            Log.w(
                PlayerRuntimeController.TAG,
                "FREEZE_RECOVERY rung=seek stalledMs=$stalledForMs pos=${positionMs}ms seekTo=${target}ms " +
                    "host=${currentStreamUrl.safeHost()}"
            )
            player.seekTo(target)
            false
        }
        PlayerFreezeRecoveryPolicy.Decision.FAILOVER -> {
            freezeFailoverDone = true
            Log.w(
                PlayerRuntimeController.TAG,
                "FREEZE_RECOVERY rung=failover stalledMs=$stalledForMs " +
                    "sinceSeekMs=${freezeRecoverySeekAtMs?.let { nowMs - it }} host=${currentStreamUrl.safeHost()}"
            )
            val failure = playbackFailure(
                cause = com.nuvio.tv.core.stream.FailureCause.FROZEN,
                message = context.getString(R.string.player_error_playback_fallback),
                facts = FailureFacts(frozenForMs = stalledForMs)
            )
            // A source that froze mid-play: the mid-play bucket (Spec 65 P5), ranked last for a day.
            if (advanceToNextLiveSource(failure, com.nuvio.tv.core.stream.FailureReason.CORRUPT)) {
                true
            } else {
                Log.w(PlayerRuntimeController.TAG, "FREEZE_RECOVERY no source left; surfacing error")
                // Fatal for this playback, as the placeholder reject: no auto-advance under the error.
                cancelNextEpisodeAutoPlayOnFatalError()
                _uiState.update { it.copy(error = failure, showLoadingOverlay = false) }
                false
            }
        }
    }
}
