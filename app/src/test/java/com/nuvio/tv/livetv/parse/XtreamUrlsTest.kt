package com.nuvio.tv.livetv.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 78 D1 (movie/series items skipped) and D3 (extension-less live URLs play directly). */
class XtreamUrlsTest {

    @Test
    fun `movie and series items are VOD`() {
        assertTrue(XtreamUrls.isVod(XtreamFixtures.MOVIE))
        assertTrue(XtreamUrls.isVod(XtreamFixtures.SERIES))
        assertTrue(XtreamUrls.isVod("https://xt.example/movie/u/p/77"))
        assertTrue(XtreamUrls.isVod("http://xt.example:8080/series/u/p/12.mkv?token=t"))
    }

    @Test
    fun `live items and look-alikes are not VOD`() {
        listOf(
            XtreamFixtures.LIVE_TS,
            XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.LIVE_BARE,
            "http://cdn.example/movie/trailer.mp4",
            "http://cdn.example/movie/channel/hd/index.m3u8",
            "http://cdn.example/series/u/p/12/extra.mp4",
            "http://cdn.example/movies/u/p/12.mp4",
            "http://xt.example/live/movie/pass/1001.ts",
            "http://xt.example/series/pass/1001",
            "http://cdn.example/movie/u//12.mp4",
            "rtmp://xt.example/movie/u/p/12.mp4",
            "not a url /movie/u/p/1.mkv"
        ).forEach { assertFalse(it, XtreamUrls.isVod(it)) }
    }

    @Test
    fun `extension-less live paths are bare live`() {
        assertTrue(XtreamUrls.isBareLive(XtreamFixtures.LIVE_BARE))
        assertTrue(XtreamUrls.isBareLive(XtreamFixtures.LIVE_BARE_LIVE_PREFIX))
        assertTrue(XtreamUrls.isBareLive("https://xt.example/my@account.xc/my_password/1477"))
    }

    @Test
    fun `other shapes are not bare live`() {
        listOf(
            XtreamFixtures.LIVE_TS,
            XtreamFixtures.LIVE_M3U8,
            XtreamFixtures.MOVIE,
            "http://xt.example/u/p/1003?token=x",
            "http://xt.example/u/p/1003#frag",
            "http://xt.example/u/p/1003/",
            "http://xt.example/u/p/abc",
            "http://xt.example/p/1003",
            "http://xt.example/vod/u/p/1003",
            "http://xt.example/a/u/p/1003",
            "http://xt.example/u/%20/1003",
            "udp://239.0.0.1:1234"
        ).forEach { assertFalse(it, XtreamUrls.isBareLive(it)) }
    }
}
