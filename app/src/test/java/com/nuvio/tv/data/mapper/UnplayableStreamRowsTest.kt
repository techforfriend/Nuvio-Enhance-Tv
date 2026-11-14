package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.remote.dto.VideoDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 84 A4/A11: rows the app cannot play are not listed. Rows decode through the wire Json,
 * so an addon's Usenet or archive fields in any shape never fail the response.
 */
class UnplayableStreamRowsTest {

    private val json = NetworkModule.provideWireJson()

    private fun listed(rows: String): List<String?> =
        json.decodeFromString<StreamResponseDto>("""{"streams":[$rows]}""").streams.orEmpty()
            .toListedStreams("Addon", null)
            .map { it.name }

    @Test
    fun `rows whose only target the app cannot play are left out`() {
        val rows = listOf(
            """{"name":"yt","ytId":"dQw4w9WgXcQ"}""",
            """{"name":"nzb","nzbUrl":"https://indexer.example/get.nzb","servers":["nntps://u:p@news.example:563/4"]}""",
            """{"name":"rar","rarUrls":[{"url":"https://h.example/a.part1.rar","bytes":1}]}""",
            """{"name":"zip","zipUrls":[{"url":"https://h.example/a.zip"}]}""",
            """{"name":"7z","7zipUrls":[{"url":"https://h.example/a.7z"}]}""",
            """{"name":"tgz","tgzUrls":[{"url":"https://h.example/a.tgz"}]}""",
            """{"name":"tar","tarUrls":[{"url":"https://h.example/a.tar"}]}""",
            // An unexpected shape is still only presence.
            """{"name":"odd","nzbUrl":{"href":42}}"""
        )
        assertEquals(emptyList<String?>(), listed(rows.joinToString(",")))
    }

    @Test
    fun `a disc image is left out by url or filename`() {
        val rows = listOf(
            """{"name":"iso-url","url":"https://cdn.example/Movie.2020.BluRay.ISO?token=1"}""",
            """{"name":"iso-name","url":"https://aio.example/playback/abc","behaviorHints":{"filename":"Movie.iso"}}""",
            """{"name":"iso-torrent","infoHash":"0123456789abcdef0123456789abcdef01234567","behaviorHints":{"filename":"Movie.iso"}}""",
            """{"name":"iso-fragment","url":"https://cdn.example/Movie.iso#t=1"}""",
            """{"name":"iso-spaced","url":"https://aio.example/playback/def","behaviorHints":{"filename":"Movie.iso "}}"""
        )
        assertEquals(emptyList<String?>(), listed(rows.joinToString(",")))
    }

    @Test
    fun `playable rows and target-less info rows stay, in order`() {
        val rows = listOf(
            """{"name":"url","url":"https://cdn.example/Movie.mkv"}""",
            """{"name":"info","title":"Configure this addon"}""",
            """{"name":"torrent","infoHash":"0123456789abcdef0123456789abcdef01234567"}""",
            """{"name":"yt-and-url","ytId":"dQw4w9WgXcQ","url":"https://cdn.example/clip.mp4"}""",
            """{"name":"nzb-and-url","nzbUrl":"https://indexer.example/get.nzb","url":"https://usenet.example/stream.mkv"}""",
            """{"name":"external","externalUrl":"https://example.com/watch"}""",
            """{"name":"isolated","url":"https://cdn.example/Isolated.2021.mkv"}""",
            // ".iso" not at the end of the path or the filename.
            """{"name":"iso-mkv","url":"https://cdn.example/Movie.iso.mkv"}""",
            """{"name":"iso-dir","url":"https://cdn.example/a.iso/play"}""",
            """{"name":"iso-query","url":"https://cdn.example/play.mkv?name=Movie.iso"}"""
        )
        assertEquals(
            listOf("url", "info", "torrent", "yt-and-url", "nzb-and-url", "external", "isolated", "iso-mkv", "iso-dir", "iso-query"),
            listed(rows.joinToString(","))
        )
    }

    @Test
    fun `a meta video's own streams follow the same rule`() {
        val video = json.decodeFromString<VideoDto>(
            """{"id":"yt_id:UC1:abc","title":"Clip","streams":[{"name":"yt","ytId":"abc"},{"name":"url","url":"https://cdn.example/a.mp4"}]}"""
        )
        assertEquals(listOf("url"), video.toDomainOrNull()?.streams?.map { it.name })
    }
}
