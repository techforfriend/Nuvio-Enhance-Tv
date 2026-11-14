package com.nuvio.tv.ui.screens.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 84 A4: pins the [StreamPlaybackInfo.hasTarget] predicate that gates the search cancel in
 * playbackInfoFor and the press/auto-play routing in StreamScreen (those call sites have no
 * ViewModel harness and are not covered here).
 */
class StreamPlaybackInfoTargetTest {

    private fun info(url: String? = null, isTorrent: Boolean = false, infoHash: String? = null, isExternal: Boolean = false) =
        StreamPlaybackInfo(
            url = url,
            title = "Title",
            streamName = "Stream",
            year = null,
            isExternal = isExternal,
            isTorrent = isTorrent,
            infoHash = infoHash,
            ytId = null,
            headers = null,
            contentId = null,
            contentType = null,
            contentName = null,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = null,
            season = null,
            episode = null,
            episodeTitle = null,
            bingeGroup = null
        )

    @Test
    fun `a url, an external link or a hashed torrent is a target`() {
        assertTrue(info(url = "https://cdn.example/a.mkv").hasTarget)
        assertTrue(info(url = "https://example.com/watch", isExternal = true).hasTarget)
        assertTrue(info(isTorrent = true, infoHash = "0123456789abcdef0123456789abcdef01234567").hasTarget)
    }

    @Test
    fun `an info row or a torrent without its hash is not`() {
        assertFalse(info().hasTarget)
        assertFalse(info(isTorrent = true).hasTarget)
    }
}
