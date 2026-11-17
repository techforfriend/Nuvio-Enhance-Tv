package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.core.player.PlayerUiState

/**
 * Whether Back treats an in-place episode switch as automatic (no Stream screen was visited for
 * it). MANUAL counts as auto-play whenever the runtime auto-selects the next episode's stream
 * (`shouldAutoSelectInManualMode`): with "prefer binge group" (NM `4ccd8b299`) or "auto-play next
 * episode" (upstream-sync T30, Arshad's pick; upstream leaves this one out).
 */
internal fun playerBackTreatsSwitchAsAutoPlay(
    streamAutoPlayMode: StreamAutoPlayMode,
    preferBingeGroupForNextEpisode: Boolean,
    autoPlayNextEpisodeEnabled: Boolean
): Boolean = streamAutoPlayMode != StreamAutoPlayMode.MANUAL ||
    preferBingeGroupForNextEpisode ||
    autoPlayNextEpisodeEnabled

internal val PlayerUiState.backTreatsSwitchAsAutoPlay: Boolean
    get() = playerBackTreatsSwitchAsAutoPlay(
        streamAutoPlayMode,
        streamAutoPlayPreferBingeGroupForNextEpisode,
        streamAutoPlayNextEpisodeEnabled
    )

/**
 * Spec 113 D1 (F3): a player entry launched before this process started, so Android restored it
 * from saved state after killing the app (Shield window 12b: the family's paused player, killed
 * behind the screensaver, started its launch episode from 0:00 after the PIN unlock). Its route
 * still names the launch episode and stream, not the one playing when the process died, so it must
 * not start. [launchStartedAtMs] is the route's launch time and [processStartElapsedMs]
 * `Process.getStartElapsedRealtime()`, both `SystemClock.elapsedRealtime()`; a route launched in
 * this process (a same-process recreate) or with no launch time (null or 0, which no real launch
 * carries) plays as before.
 */
internal fun playerRestoredAfterProcessDeath(launchStartedAtMs: Long?, processStartElapsedMs: Long): Boolean =
    launchStartedAtMs != null && launchStartedAtMs in 1L..<processStartElapsedMs

internal enum class PlayerBackRoute {
    /** The title's Details page (focus on the episode). */
    DETAIL,
    /** Auto-played into another episode: Details when the route asked for it, else pop. */
    DETAIL_OR_POP,
    /** Manually switched episode: the Stream screen of the episode now playing. */
    CURRENT_EPISODE_STREAMS,
    /** The screen the player was opened from (its Stream screen when on the back stack). */
    PREVIOUS_SCREEN
}

/**
 * Where Back from the player goes. A finished title goes to Details before the episode-switch
 * branches are considered (NM `2f9d86a7e`): a completed episode never reopens a source list.
 */
internal fun playerBackRoute(
    episodeChangedInPlace: Boolean,
    autoPlayEnabled: Boolean,
    playbackCompleted: Boolean,
    hasContentId: Boolean
): PlayerBackRoute = when {
    playbackCompleted && hasContentId -> PlayerBackRoute.DETAIL
    episodeChangedInPlace && autoPlayEnabled -> PlayerBackRoute.DETAIL_OR_POP
    episodeChangedInPlace -> PlayerBackRoute.CURRENT_EPISODE_STREAMS
    else -> PlayerBackRoute.PREVIOUS_SCREEN
}
