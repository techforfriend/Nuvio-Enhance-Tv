package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Spec 62 H4; cases from upstream NM b71ccb1f6 (PR #4440).
class ContinueWatchingMarkWatchedTest {
    private fun inProgress(
        contentType: String = "series",
        season: Int? = 1,
        episode: Int? = 2,
        duration: Long = 3_000L
    ) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = "tt1",
            contentType = contentType,
            name = "Show",
            poster = "poster",
            backdrop = "backdrop",
            logo = "logo",
            videoId = "tt1:1:2",
            season = season,
            episode = episode,
            episodeTitle = "Pilot",
            position = 1_000L,
            duration = duration,
            lastWatched = 1L,
            source = "trakt",
            traktPlaybackId = 9L
        )
    )

    private fun nextUp(hasAired: Boolean = true, thumbnail: String? = "thumb") = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = "tt1",
            contentType = "series",
            name = "Show",
            poster = "poster",
            backdrop = "backdrop",
            logo = "logo",
            videoId = "tt1:1:3",
            season = 1,
            episode = 3,
            episodeTitle = "Next",
            thumbnail = thumbnail,
            hasAired = hasAired,
            lastWatched = 1L,
            sortTimestamp = 1L
        )
    )

    @Test
    fun `in progress items and aired episodes can be marked`() {
        assertTrue(inProgress().canMarkWatched())
        assertTrue(nextUp().canMarkWatched())
    }

    @Test
    fun `upcoming episode cannot be marked`() {
        assertFalse(nextUp(hasAired = false).canMarkWatched())
    }

    @Test
    fun `in progress episode becomes completed`() {
        val result = inProgress().toCompletedProgress(nowMs = 5L)

        assertEquals("tt1:1:2", result.videoId)
        assertEquals(1, result.season)
        assertEquals(2, result.episode)
        assertEquals(3_000L, result.position)
        assertEquals(3_000L, result.duration)
        assertEquals(100f, result.progressPercent)
        assertEquals(5L, result.lastWatched)
        assertTrue(result.isCompleted())
        // A fresh local entry: the old playback session ids are not carried over.
        assertEquals(WatchProgress.SOURCE_LOCAL, result.source)
        assertNull(result.traktPlaybackId)
    }

    @Test
    fun `movie without known duration still completes`() {
        val result = inProgress(contentType = "movie", season = null, episode = null, duration = 0L)
            .toCompletedProgress()

        assertEquals(1L, result.duration)
        assertEquals(result.duration, result.position)
        assertNull(result.season)
        assertTrue(result.isCompleted())
    }

    @Test
    fun `next up targets the episode shown on the card`() {
        val result = nextUp().toCompletedProgress(nowMs = 5L)

        assertEquals("tt1:1:3", result.videoId)
        assertEquals(3, result.episode)
        assertEquals("Next", result.episodeTitle)
        assertEquals("thumb", result.backdrop)
        assertEquals(100f, result.progressPercent)
        assertTrue(result.isCompleted())
    }

    @Test
    fun `next up without thumbnail keeps the backdrop`() {
        assertEquals("backdrop", nextUp(thumbnail = null).toCompletedProgress().backdrop)
    }
}
