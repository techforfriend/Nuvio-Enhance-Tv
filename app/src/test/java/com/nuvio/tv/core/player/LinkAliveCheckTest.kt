package com.nuvio.tv.core.player

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 97 A6: the remembered link's HEAD check keeps its status rule on OkHttp. */
class LinkAliveCheckTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun alive(code: Int): Boolean {
        server.enqueue(MockResponse().setResponseCode(code))
        return isHttpLinkAlive(server.url("/f.mkv").toString(), emptyMap(), client)
    }

    @Test
    fun `only a positive gone answer is dead`() {
        listOf(400, 401, 403, 404, 410).forEach { assertFalse("$it", alive(it)) }
        listOf(200, 206, 405, 429, 500).forEach { assertTrue("$it", alive(it)) }
    }

    @Test
    fun `sends a HEAD with the link's headers and follows redirects`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/moved").toString()))
        server.enqueue(MockResponse().setResponseCode(403))
        assertFalse(isHttpLinkAlive(server.url("/f.mkv").toString(), mapOf("X-Token" to "t"), client))
        val first = server.takeRequest()
        assertEquals("HEAD", first.method)
        assertEquals("t", first.getHeader("X-Token"))
        assertEquals("/moved", server.takeRequest().path)
    }

    @Test
    fun `network errors and bad urls read as alive`() {
        val url = server.url("/f.mkv").toString()
        server.shutdown()
        assertTrue(isHttpLinkAlive(url, emptyMap(), client))
        assertTrue(isHttpLinkAlive("not a url", emptyMap(), client))
    }
}
