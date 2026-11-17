package com.nuvio.tv.core.player

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlaybackNetworkingTest {

    // Spec 91 K2 (W3-02): plain progressive/HLS/DASH clients share the chunk client's jar.
    @Test
    fun `plain playback clients carry the player cookie jar`() {
        assertSame(PlayerPlaybackNetworking.playerCookieJar, PlayerPlaybackNetworking.createHttpClient().cookieJar)
        assertSame(
            PlayerPlaybackNetworking.playerCookieJar,
            PlayerPlaybackNetworking.createHttpClient(mapOf("Authorization" to "Basic eDp5")).cookieJar
        )
    }

    // Spec 92 W1: the PREWARM head/tail requests send the cookie a redirect set, or such hosts answer 403.
    @Test
    fun `the prewarm client carries the player cookie jar`() {
        assertSame(PlayerPlaybackNetworking.playerCookieJar, PlayerPlaybackNetworking.prewarmHttpClient.cookieJar)
    }

    @Test
    fun `a prewarm request sends the cookie its redirect set`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "/w1/cdn")
                    // Path-scoped: the jar is process-wide and keyed by host, so no other test's localhost request gets it.
                    .setHeader("Set-Cookie", "w1=ok; Max-Age=600; Path=/w1")
            )
            server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
            val request = Request.Builder().url(server.url("/w1/r")).header("Range", "bytes=0-0").build()

            PlayerPlaybackNetworking.prewarmHttpClient.newCall(request).execute().use { assertEquals(206, it.code) }

            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("w1=ok", server.takeRequest().getHeader("Cookie"))
        } finally {
            PlayerPlaybackNetworking.playerCookieJar.saveFromResponse(
                server.url("/w1/"), listOf(Cookie.parse(server.url("/w1/"), "w1=; Max-Age=0; Path=/w1")!!)
            )
            server.shutdown()
        }
    }

    @Test
    fun `the jar returns a host's cookies to that host only`() {
        val jar = PlayerPlaybackNetworking.playerCookieJar
        val host = "https://k2-cookie-test.example/a".toHttpUrl()
        val other = "https://k2-other-host.example/a".toHttpUrl()
        jar.saveFromResponse(host, listOf(Cookie.parse(host, "sid=1; Max-Age=600")!!))
        jar.saveFromResponse(host, listOf(Cookie.parse(host, "sid=2; Max-Age=600")!!))

        assertEquals(listOf("sid=2"), jar.loadForRequest(host).map { "${it.name}=${it.value}" })
        assertTrue(jar.loadForRequest(other).isEmpty())
    }

    @Test
    fun `a Secure cookie is never sent over http, and Path is honoured`() {
        val jar = PlayerPlaybackNetworking.playerCookieJar
        val https = "https://k2-secure-test.example/media/a".toHttpUrl()
        jar.saveFromResponse(https, listOf(Cookie.parse(https, "tok=1; Max-Age=600; Secure; Path=/media")!!))

        assertEquals(listOf("tok"), jar.loadForRequest(https).map { it.name })
        assertTrue(jar.loadForRequest("http://k2-secure-test.example/media/a".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://k2-secure-test.example/other".toHttpUrl()).isEmpty())
    }
}
