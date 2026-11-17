package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackNavigationTest {

    private fun route(
        episodeChangedInPlace: Boolean,
        autoPlayEnabled: Boolean,
        playbackCompleted: Boolean = false,
        hasContentId: Boolean = true
    ) = playerBackRoute(episodeChangedInPlace, autoPlayEnabled, playbackCompleted, hasContentId)

    @Test
    fun `manual with binge group reuse counts as auto, so Back skips the next episode's streams`() {
        val autoPlay = playerBackTreatsSwitchAsAutoPlay(
            StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = true,
            autoPlayNextEpisodeEnabled = false
        )
        assertTrue(autoPlay)
        assertEquals(PlayerBackRoute.DETAIL_OR_POP, route(episodeChangedInPlace = true, autoPlayEnabled = autoPlay))
    }

    @Test
    fun `strict manual switch still opens the current episode's streams`() {
        val autoPlay = playerBackTreatsSwitchAsAutoPlay(
            StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false,
            autoPlayNextEpisodeEnabled = false
        )
        assertFalse(autoPlay)
        assertEquals(PlayerBackRoute.CURRENT_EPISODE_STREAMS, route(episodeChangedInPlace = true, autoPlayEnabled = autoPlay))
    }

    @Test
    fun `first stream mode counts as auto`() {
        assertTrue(
            playerBackTreatsSwitchAsAutoPlay(
                StreamAutoPlayMode.FIRST_STREAM,
                preferBingeGroupForNextEpisode = false,
                autoPlayNextEpisodeEnabled = false
            )
        )
    }

    @Test
    fun `manual with auto-play next episode counts as auto, so Back skips the next episode's streams (T30)`() {
        val autoPlay = playerBackTreatsSwitchAsAutoPlay(
            StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false,
            autoPlayNextEpisodeEnabled = true
        )
        assertTrue(autoPlay)
        assertEquals(PlayerBackRoute.DETAIL_OR_POP, route(episodeChangedInPlace = true, autoPlayEnabled = autoPlay))
    }

    @Test
    fun `same episode Back returns to the previous screen`() {
        assertEquals(PlayerBackRoute.PREVIOUS_SCREEN, route(episodeChangedInPlace = false, autoPlayEnabled = false))
        // auto-play mode alone (no switch happened) is not an auto switch
        assertEquals(PlayerBackRoute.PREVIOUS_SCREEN, route(episodeChangedInPlace = false, autoPlayEnabled = true))
    }

    @Test
    fun `a finished episode goes to Details before any episode-switch branch`() {
        assertEquals(
            PlayerBackRoute.DETAIL,
            route(episodeChangedInPlace = true, autoPlayEnabled = false, playbackCompleted = true)
        )
        assertEquals(
            PlayerBackRoute.DETAIL,
            route(episodeChangedInPlace = true, autoPlayEnabled = true, playbackCompleted = true)
        )
        assertEquals(
            PlayerBackRoute.DETAIL,
            route(episodeChangedInPlace = false, autoPlayEnabled = false, playbackCompleted = true)
        )
    }

    @Test
    fun `a finished title without a content id falls through to the switch branches`() {
        assertEquals(
            PlayerBackRoute.CURRENT_EPISODE_STREAMS,
            route(episodeChangedInPlace = true, autoPlayEnabled = false, playbackCompleted = true, hasContentId = false)
        )
        assertEquals(
            PlayerBackRoute.PREVIOUS_SCREEN,
            route(episodeChangedInPlace = false, autoPlayEnabled = false, playbackCompleted = true, hasContentId = false)
        )
    }

    @Test
    fun `a player launched before this process started was restored after a kill and does not start`() {
        // Shield window 12b: launched ~22:40, process restarted ~23:40 (elapsedRealtime ms).
        assertTrue(playerRestoredAfterProcessDeath(launchStartedAtMs = 1_000_000L, processStartElapsedMs = 4_600_000L))
    }

    @Test
    fun `a player launched in this process, or with no launch time, plays as before`() {
        assertFalse(playerRestoredAfterProcessDeath(launchStartedAtMs = 4_600_500L, processStartElapsedMs = 4_600_000L))
        assertFalse(playerRestoredAfterProcessDeath(launchStartedAtMs = 4_600_000L, processStartElapsedMs = 4_600_000L))
        assertFalse(playerRestoredAfterProcessDeath(launchStartedAtMs = null, processStartElapsedMs = 4_600_000L))
        // 0 is no launch time (a route fixture), never a launch before the process.
        assertFalse(playerRestoredAfterProcessDeath(launchStartedAtMs = 0L, processStartElapsedMs = 4_600_000L))
    }
}
