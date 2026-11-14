package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.StreamTextSizeParser
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failover walk ([nextLiveSource]): rows after the playing one, then from the top (Spec 86 R5),
 * the dead / hidden / non-candidate filters on both legs, and rows reported uncached last (R7).
 */
class NextLiveSourceTest {

    private fun row(name: String, cached: Boolean? = null) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://cdn.example/$name.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "addon",
        addonLogo = null,
        streamData = cached?.let { AioStreamData(size = null, filename = null, cached = it, parsedFile = null) }
    )

    private val a = row("a")
    private val b = row("b")
    private val c = row("c")
    private val d = row("d")
    private val list = listOf(a, b, c, d)

    private fun next(
        streams: List<Stream>,
        playingIdx: Int,
        dead: Set<String> = emptySet(),
        hidden: Set<String> = emptySet(),
        deadKeys: Set<String> = emptySet(),
        p2pEnabled: Boolean = false
    ): Stream? = nextLiveSource(
        streams = streams,
        startIdx = if (playingIdx >= 0) playingIdx + 1 else 0,
        deadUrls = dead,
        hiddenKeys = hidden,
        p2pEnabled = p2pEnabled,
        deadKeys = deadKeys
    )

    private fun torrent(name: String) = Stream(
        name = name, title = null, description = null, url = null, ytId = null,
        infoHash = name.padEnd(40, '0'), fileIdx = 0, externalUrl = null, behaviorHints = null,
        addonName = "addon", addonLogo = null
    )

    @Test
    fun `rows after the playing one come first`() {
        assertEquals(c, next(list, playingIdx = 1))
        assertEquals(d, next(list, playingIdx = 1, dead = setOf(c.url!!)))
    }

    @Test
    fun `nothing after the playing row wraps to the top, never to the playing row`() {
        assertEquals(a, next(list, playingIdx = 3))
        assertEquals(a, next(list, playingIdx = 2, dead = setOf(d.url!!)))
        assertEquals(b, next(list, playingIdx = 2, dead = setOf(d.url!!, a.url!!)))
        // Every other row dead: the playing row itself is not offered again.
        assertNull(next(list, playingIdx = 2, dead = setOf(a.url!!, b.url!!, d.url!!)))
        assertNull(next(listOf(a), playingIdx = 0))
    }

    @Test
    fun `the wrapped rows keep the hidden and candidate filters`() {
        val external = Stream(
            name = "ext", title = null, description = null, url = null, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = "https://addon.example/watch", behaviorHints = null,
            addonName = "addon", addonLogo = null
        )
        assertEquals(b, next(listOf(a, b, c), playingIdx = 2, hidden = setOf(a.limitKey())))
        // An external link above the playing row would open a browser: never wrapped to.
        assertNull(next(listOf(external, a), playingIdx = 1))
    }

    // Review e527c7bb (major): a torrent row has no http URL for the dead-URL set, so the wrap must
    // skip a row the failover already left by its list key.
    @Test
    fun `the wrap never returns to a torrent row the failover already left`() {
        val failedTorrent = torrent("t1")
        val streams = listOf(failedTorrent, b)
        assertEquals(failedTorrent, next(streams, playingIdx = 1, p2pEnabled = true))
        assertNull(next(streams, playingIdx = 1, p2pEnabled = true, deadKeys = setOf(failedTorrent.limitKey())))
        // P2P off: a torrent row above the playing one is never a candidate.
        assertNull(next(streams, playingIdx = 1, p2pEnabled = false))
    }

    @Test
    fun `a start index past the list never reads past its end`() {
        assertNull(nextLiveSource(listOf(a), startIdx = 5, deadUrls = setOf(a.url!!), hiddenKeys = emptySet(), p2pEnabled = false))
    }

    @Test
    fun `a playing row not in the list walks every row`() {
        assertEquals(a, next(list, playingIdx = -1))
        assertEquals(d, next(list, playingIdx = -1, dead = setOf(a.url!!, b.url!!, c.url!!)))
        assertNull(next(list, playingIdx = -1, dead = list.map { it.url!! }.toSet()))
    }

    private fun listed(name: String, filename: String?, size: Long?, addon: String = "addon") = row(name).copy(
        addonName = addon,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null,
            videoSize = size, filename = filename
        )
    )

    // R10 (Shield 2026-11-12): a reloaded list signs new URLs, so the playing row is found by its
    // listed release; only a single match counts.
    @Test
    fun `the playing row is found by addon, filename and size when its URL changed`() {
        val streams = listOf(
            listed("top", "Top.mkv", 40L),
            listed("fresh-url", "Dune.CAPTCHA.mkv", 9L),
            listed("next", "Next.mkv", 16L)
        )
        assertEquals(1, listedReleaseIndex(streams, "addon", "Dune.CAPTCHA.mkv", 9L))
        assertEquals(streams[2], next(streams, playingIdx = 1))
    }

    @Test
    fun `the listed release matches only one row of the same addon, size and name`() {
        val streams = listOf(listed("x", "F.mkv", 9L), listed("y", "F.mkv", 9L, addon = "other"))
        assertEquals(0, listedReleaseIndex(streams, "addon", "F.mkv", 9L))
        assertEquals(-1, listedReleaseIndex(streams, "addon", "F.mkv", 8L))
        assertEquals(-1, listedReleaseIndex(streams, "addon", "G.mkv", 9L))
        // A twin on another service of the same addon: ambiguous, not guessed.
        assertEquals(-1, listedReleaseIndex(streams + listed("z", "F.mkv", 9L), "addon", "F.mkv", 9L))
        // A fact missing: no match.
        assertEquals(-1, listedReleaseIndex(streams, "addon", null, 9L))
        assertEquals(-1, listedReleaseIndex(streams, "addon", "F.mkv", null))
        assertEquals(-1, listedReleaseIndex(streams, null, "F.mkv", 9L))
        assertEquals(-1, listedReleaseIndex(streams, "addon", "F.mkv", 0L))
        assertEquals(-1, listedReleaseIndex(listOf(listed("n", null, 9L)), "addon", "F.mkv", 9L))
    }

    @Test
    fun `a size read from the stream text matches the same parsed value`() {
        val textOnly = listed("t", "T.mkv", size = null).copy(description = "T.mkv 9.3 GB")
        val parsed = StreamTextSizeParser.effectiveSizeBytes(textOnly)
        assertNotNull(parsed)
        assertEquals(0, listedReleaseIndex(listOf(textOnly), "addon", "T.mkv", parsed))
    }

    @Test
    fun `rows reported uncached come after every other candidate, wrap included`() {
        val uncachedNext = row("u1", cached = false)
        val cachedAbove = row("c1", cached = true)
        val unreportedLast = row("n1")
        val streams = listOf(cachedAbove, row("playing"), uncachedNext, unreportedLast)
        assertEquals(unreportedLast, next(streams, playingIdx = 1))
        assertEquals(cachedAbove, next(streams, playingIdx = 1, dead = setOf(unreportedLast.url!!)))
        // Only uncached rows left: still taken (behind the rest, never out).
        assertEquals(uncachedNext, next(streams, playingIdx = 1, dead = setOf(unreportedLast.url!!, cachedAbove.url!!)))
    }
}
