package com.nuvio.tv.data.mapper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins withoutResolverOnlyStreams: only clientResolve entries with no playable
 * target (url, torrent, external link) are dropped. A YouTube id is no target
 * (Spec 84 A4).
 */
class WithoutResolverOnlyStreamsTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"

    private fun stream(
        name: String,
        url: String? = null,
        ytId: String? = null,
        infoHash: String? = null,
        externalUrl: String? = null,
        clientResolve: StreamClientResolve? = null
    ) = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = clientResolve
    )

    private fun debridResolve(infoHash: String? = hash) = StreamClientResolve(
        type = "debrid",
        infoHash = infoHash,
        fileIdx = null,
        sources = null,
        torrentName = null,
        filename = null,
        mediaType = null,
        title = null,
        season = null,
        episode = null,
        service = "realdebrid"
    )

    private fun kept(s: Stream) = listOf(s).withoutResolverOnlyStreams() == listOf(s)

    @Test
    fun `plain url stream is kept`() {
        assertTrue(kept(stream("url", url = "https://cdn.example/movie.mkv")))
    }

    @Test
    fun `infoHash torrent is kept`() {
        assertTrue(kept(stream("torrent", infoHash = hash)))
    }

    @Test
    fun `youtube stream with debrid clientResolve is dropped`() {
        val yt = stream("yt", ytId = "dQw4w9WgXcQ", clientResolve = debridResolve())
        assertEquals(emptyList<Stream>(), listOf(yt).withoutResolverOnlyStreams())
    }

    @Test
    fun `http externalUrl with debrid clientResolve is kept`() {
        assertTrue(kept(stream("ext", externalUrl = "https://example.com/watch", clientResolve = debridResolve())))
    }

    @Test
    fun `info-only row without target or clientResolve is kept`() {
        assertTrue(kept(stream("info")))
    }

    @Test
    fun `clientResolve-only debrid stream is dropped`() {
        val resolverOnly = stream("resolver-only", clientResolve = debridResolve())
        assertEquals(emptyList<Stream>(), listOf(resolverOnly).withoutResolverOnlyStreams())
    }

    @Test
    fun `resolved url with debrid clientResolve is kept`() {
        assertTrue(
            kept(stream("resolved", url = "https://cdn.example/resolved.mkv", clientResolve = debridResolve()))
        )
    }

    @Test
    fun `hybrid torrent with debrid clientResolve is kept`() {
        val hybrid = stream("hybrid", infoHash = hash, clientResolve = debridResolve())
        assertTrue(hybrid.isTorrent())
        assertTrue(kept(hybrid))
    }

    @Test
    fun `mixed list drops only the resolver-only entry and keeps order`() {
        val a = stream("a", url = "https://cdn.example/a.mkv")
        val b = stream("b", clientResolve = debridResolve())
        val c = stream("c", infoHash = hash)
        assertEquals(listOf(a, c), listOf(a, b, c).withoutResolverOnlyStreams())
    }
}
