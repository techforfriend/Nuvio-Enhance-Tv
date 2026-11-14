package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.data.local.InternalPlayerEngine

/** Spec 86 R3: a fix button the error box can draw before Go Back and Details. */
internal enum class FailureBoxAction { RETRY, SWITCH_ENGINE, NEXT_SOURCE, EXTERNAL_PLAYER }

/**
 * Spec 86 R3: what the player can offer the box now: an untried source is left ([nextSource]), and
 * the engine Play with switches to ([engineTarget], null when this link already had both).
 */
internal data class FailureBoxOptions(val nextSource: Boolean, val engineTarget: InternalPlayerEngine?) {
    fun actionsFor(cause: FailureCause): List<FailureBoxAction> =
        PlaybackFailureActions.of(cause, nextSource = nextSource, switchEngine = engineTarget != null)
}

/**
 * Spec 86 R3 (D4, sandbox spec-86-failure-box-actions v1): the cause picks the fix buttons, the most
 * likely fix first (it takes focus); Go Back and Details always follow. Link or source causes: Next
 * source. Passing causes (the same link may work a minute later): Retry, Next source. This TV or
 * this player: the other engine, Next source, External player. [nextSource] = an untried source is
 * left; [switchEngine] = the other engine has not had this link.
 */
internal object PlaybackFailureActions {
    fun of(cause: FailureCause, nextSource: Boolean, switchEngine: Boolean): List<FailureBoxAction> =
        when (cause) {
            FailureCause.LINK_EXPIRED, FailureCause.BLOCKED, FailureCause.REMOVED, FailureCause.OTHER_HTTP,
            FailureCause.WEB_PAGE, FailureCause.TINY_FILE, FailureCause.TEXT, FailureCause.LOGIN,
            FailureCause.PLACEHOLDER, FailureCause.NO_URL, FailureCause.TOO_SLOW, FailureCause.CORRUPT,
            FailureCause.FROZEN, FailureCause.TORRENT, FailureCause.WRONG_FILE ->
                listOfNotNull(FailureBoxAction.NEXT_SOURCE.takeIf { nextSource })
            FailureCause.NO_CONNECTION, FailureCause.SERVER_DOWN, FailureCause.RATE_LIMITED, FailureCause.NO_DATA,
            FailureCause.ENGINE, FailureCause.UNKNOWN ->
                listOfNotNull(FailureBoxAction.RETRY, FailureBoxAction.NEXT_SOURCE.takeIf { nextSource })
            FailureCause.VIDEO_UNSUPPORTED, FailureCause.UNREADABLE, FailureCause.DECODER_STUCK,
            FailureCause.AUDIO_FAILED ->
                listOfNotNull(
                    FailureBoxAction.SWITCH_ENGINE.takeIf { switchEngine },
                    FailureBoxAction.NEXT_SOURCE.takeIf { nextSource },
                    FailureBoxAction.EXTERNAL_PLAYER
                )
        }
}
