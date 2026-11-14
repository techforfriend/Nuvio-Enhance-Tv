package com.nuvio.tv.core.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Spec 68 X1: a redirect hop off the first request's site loses every stream credential. */
class SubtitleRedirectCredentialInterceptorTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer

    /** "localhost" and "127.0.0.1" are foreign to each other; same host on two ports is same site. */
    private val lookup: (String) -> String? = { null }

    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(subtitleRedirectCredentialInterceptor(lookup))
        .build()

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        first.shutdown()
        second.shutdown()
    }

    private fun fetchVia(redirectTarget: String) {
        first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirectTarget))
        second.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nhi\n"))
        val request = Request.Builder()
            .url(first.url("/a.srt"))
            .header("Authorization", "Bearer t")
            .header("Cookie", "session=1")
            .header("Proxy-Authorization", "Basic x")
            .header("Referer", "https://cdn.example.com/")
            .build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun sameHostHop_keepsCredentials() {
        fetchVia(second.url("/b.srt").toString())
        first.takeRequest()
        val hop = second.takeRequest()
        assertEquals("session=1", hop.getHeader("Cookie"))
        assertEquals("Basic x", hop.getHeader("Proxy-Authorization"))
    }

    @Test
    fun offSiteHop_dropsAllCredentials_keepsReferer() {
        val offSite = second.url("/b.srt").newBuilder()
            .host(if (first.hostName == "localhost") "127.0.0.1" else "localhost")
            .build()
        fetchVia(offSite.toString())
        assertEquals("Bearer t", first.takeRequest().getHeader("Authorization"))
        val hop = second.takeRequest()
        assertNull(hop.getHeader("Authorization"))
        assertNull(hop.getHeader("Cookie"))
        assertNull(hop.getHeader("Proxy-Authorization"))
        assertEquals("https://cdn.example.com/", hop.getHeader("Referer"))
    }
}
