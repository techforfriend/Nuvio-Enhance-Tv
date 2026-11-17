package com.nuvio.tv.ui.components

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

/**
 * Spec 96 P2: a (re)start begins at startPositionMs, and 0 keeps the pre-Spec 96 call (the item's default start).
 * Both the play effect and the ON_RESUME re-prepare go through [loadTrailer]. That a mute change no longer
 * restarts playback is an effect-key property of the composable (no Compose test harness here): checked on device.
 */
class TrailerLoadTest {
    private val player = mockk<ExoPlayer>(relaxed = true)

    @Test
    fun `a start position is applied with the media item, then prepare`() {
        player.loadTrailer("https://v/trailer", audioUrl = null, startPositionMs = 37_000)

        verifyOrder {
            player.setMediaItem(any<MediaItem>(), 37_000L)
            player.prepare()
        }
    }

    @Test
    fun `zero keeps the default-start call`() {
        player.loadTrailer("https://v/trailer", audioUrl = null, startPositionMs = 0)

        verify { player.setMediaItem(any<MediaItem>()) }
        verify(exactly = 0) { player.setMediaItem(any<MediaItem>(), any<Long>()) }
    }

    @Test
    fun `a separate audio track starts at the same position`() {
        val factory = mockk<MediaSource.Factory> {
            every { createMediaSource(any()) } returns mockk(relaxed = true)
        }
        player.loadTrailer("https://v/trailer", "https://a/trailer", 12_000) { factory }

        verify(exactly = 2) { factory.createMediaSource(any()) }
        verifyOrder {
            player.setMediaSource(any<MediaSource>(), 12_000L)
            player.prepare()
        }
    }
}
