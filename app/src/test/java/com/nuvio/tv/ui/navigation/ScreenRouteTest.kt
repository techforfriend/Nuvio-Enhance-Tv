package com.nuvio.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.core.player.PlayerNavigationArgs
import com.nuvio.tv.core.util.encodeUrlArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRouteTest {

    @Test
    fun `stream route carries the request id separately from the progress video id`() {
        val route = Screen.Stream.createRoute(
            videoId = "yt_channel:abc",
            contentType = "movie",
            title = "Title",
            contentId = "yt_channel:abc",
            streamRequestId = "yt_id:x y"
        )

        assertTrue(route.startsWith("stream/yt_channel%3Aabc/"))
        assertTrue(route.endsWith("&streamRequestId=yt_id%3Ax%20y"))
    }

    @Test
    fun `stream route leaves request id empty when absent`() {
        val route = Screen.Stream.createRoute(videoId = "tt1", contentType = "movie", title = "T")

        assertTrue(route.endsWith("&streamRequestId="))
    }

    @Test
    fun `player route percent-encodes videoHash and infoHash`() {
        // Raw '&', '#', '%' used to split or truncate the following query args.
        val route = Screen.Player.createRoute(
            streamUrl = "http://h/v",
            title = "T",
            videoHash = "a&b#c",
            infoHash = "d%e&fileIdx=9",
            launchStartedAtMs = 0L
        )

        assertTrue(route.contains("&videoHash=a%26b%23c&videoSize="))
        assertTrue(route.contains("&infoHash=d%25e%26fileIdx%3D9&fileIdx=&"))
    }

    @Test
    fun `player route carries the sources time last, empty when there was no search`() {
        val route = Screen.Player.createRoute(streamUrl = "http://h/v", title = "T", launchStartedAtMs = 5L, sourcesFoundMs = 1_400L)
        assertTrue(route.endsWith("&launchStartedAtMs=5&sourcesFoundMs=1400"))
        assertTrue(Screen.Player.route.endsWith("&sourcesFoundMs={sourcesFoundMs}"))
        val none = Screen.Player.createRoute(streamUrl = "http://h/v", title = "T", launchStartedAtMs = 5L)
        assertTrue(none.endsWith("&sourcesFoundMs="))
    }

    @Test
    fun `player route marks a release video only when asked, read back by the nav args (Spec 80 D20)`() {
        assertTrue(Screen.Player.route.contains("&releaseVideo={releaseVideo}&"))
        val normal = Screen.Player.createRoute(streamUrl = "http://h/v", title = "T", launchStartedAtMs = 5L)
        assertTrue(normal.contains("&releaseVideo=false&"))
        val release = Screen.Player.createRoute(streamUrl = "http://h/v", title = "T", launchStartedAtMs = 5L, releaseVideo = true)
        assertTrue(release.contains("&releaseVideo=true&"))

        assertTrue(PlayerNavigationArgs.from(SavedStateHandle(mapOf("releaseVideo" to "true"))).releaseVideo)
        assertFalse(PlayerNavigationArgs.from(SavedStateHandle(mapOf("releaseVideo" to "false"))).releaseVideo)
        assertFalse(PlayerNavigationArgs.from(SavedStateHandle()).releaseVideo)
    }

    @Test
    fun `player route leaves hex hashes unchanged`() {
        val route = Screen.Player.createRoute(
            streamUrl = "http://h/v",
            title = "T",
            videoHash = "8e245d9679d31e12",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            launchStartedAtMs = 0L
        )

        assertTrue(route.contains("&videoHash=8e245d9679d31e12&"))
        assertTrue(route.contains("&infoHash=0123456789abcdef0123456789abcdef01234567&"))
    }

    @Test
    fun `collection editor route percent-encodes the collection id`() {
        assertEquals(
            "collection_editor?collectionId=a%26b%23c",
            Screen.CollectionEditor.createRoute("a&b#c")
        )
        assertEquals("collection_editor?collectionId=", Screen.CollectionEditor.createRoute())
    }

    @Test
    fun `search query route percent-encodes the name as one path segment`() {
        // Space -> %20 (not '+'), literal '+' -> %2B, '/' -> %2F: a single Navigation
        // decode restores the exact name.
        assertEquals("search/A%2FB%20C%2BD", Screen.Search.createRoute("A/B C+D"))
    }

    @Test
    fun `cast route carries department and breadcrumb, empty when absent`() {
        assertEquals(
            "cast_detail/7/Tom%2BJerry?department=Directing&sourceTitle=Tears%20of%20Steel&sectionLabel=Directed%20by",
            Screen.CastDetail.createRoute(7, "Tom+Jerry", "Directing", "Tears of Steel", "Directed by")
        )
        assertEquals(
            "cast_detail/7/A?department=&sourceTitle=&sectionLabel=",
            Screen.CastDetail.createRoute(7, "A")
        )
    }

    @Test
    fun `entity route carries the breadcrumb after the source type`() {
        assertEquals(
            "tmdb_entity_browse/network/49/HBO?sourceType=tv&sourceTitle=A%26B%20%E2%80%BA&sectionLabel=Network",
            Screen.TmdbEntityBrowse.createRoute("network", 49, "HBO", "tv", "A&B \u203A", "Network")
        )
        assertEquals(
            "tmdb_entity_browse/company/2/D?sourceType=movie&sourceTitle=&sectionLabel=",
            Screen.TmdbEntityBrowse.createRoute("company", 2, "D", "movie")
        )
    }

    @Test
    fun `root search route stays argument-free`() {
        assertEquals("search", Screen.Search.route)
    }

    @Test
    fun `search createRoute fills the QUERY_ROUTE argument slot`() {
        val query = "A/B C+D"
        val expected = Screen.Search.QUERY_ROUTE
            .replace("{${Screen.Search.QUERY_ARG}}", encodeUrlArg(query))

        assertEquals(expected, Screen.Search.createRoute(query))
    }
}
