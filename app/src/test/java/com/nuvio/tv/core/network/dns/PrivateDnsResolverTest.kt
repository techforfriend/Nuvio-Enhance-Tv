package com.nuvio.tv.core.network.dns

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PrivateDnsResolverTest {

    private val systemCalls = mutableListOf<String>()
    private val system = Dns { host -> systemCalls += host; listOf(InetAddress.getByName("192.0.2.2")) }

    private val dohCalls = mutableListOf<Pair<PrivateDnsProvider, String>>()

    @Volatile private var chosen: PrivateDnsProvider? = PrivateDnsProvider.CLOUDFLARE
    @Volatile private var dohAnswer: (PrivateDnsProvider, String) -> List<InetAddress> = { provider, _ ->
        listOf(InetAddress.getByName(if (provider == PrivateDnsProvider.CLOUDFLARE) "1.2.3.4" else "5.6.7.8"))
    }
    private var now = 1_000L

    private val resolver = PrivateDnsResolver(
        provider = { chosen },
        doh = { provider -> Dns { host -> synchronized(dohCalls) { dohCalls += provider to host }; dohAnswer(provider, host) } },
        system = system,
        nowMs = { now }
    )

    private fun assertThrowsUnknownHost(host: String) {
        try {
            resolver.lookup(host)
            fail("expected UnknownHostException for $host")
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun `local names and ip literals keep the system resolver`() {
        listOf("nas", "tower.local", "TOWER.LOCAL.", "router.home", "box.lan", "x.home.arpa", "svc.internal",
            "pc.localdomain", "app.localhost", "192.0.2.50", "::1").forEach { resolver.lookup(it) }
        assertEquals(11, systemCalls.size)
        assertTrue(dohCalls.isEmpty())
    }

    @Test
    fun `public look-alikes of local names go to the provider`() {
        val names = listOf("tower.local.example.com", "example.lan.io", "fritz.box", "1.2.3.4.nip.io", "home.example.net")
        names.forEach { resolver.lookup(it) }
        assertTrue(systemCalls.isEmpty())
        assertEquals(names, dohCalls.map { it.second })
    }

    @Test
    fun `off uses the system resolver`() {
        chosen = PrivateDnsProvider.OFF
        resolver.lookup("api.example.com")
        assertEquals(listOf("api.example.com"), systemCalls)
        assertTrue(dohCalls.isEmpty())
    }

    @Test
    fun `an unreadable setting fails a public lookup and never asks the system`() {
        chosen = null
        assertThrowsUnknownHost("api.example.com")
        assertTrue(systemCalls.isEmpty())
        resolver.lookup("tower.local")
        assertEquals("local names still work", listOf("tower.local"), systemCalls)
    }

    @Test
    fun `a provider failure fails the lookup, with no fallback`() {
        listOf(
            UnknownHostException("x: NXDOMAIN"),
            UnknownHostException("x: SERVFAIL"),
            UnknownHostException("x").apply { initCause(IOException("response: 500")) }
        ).forEach { failure ->
            dohAnswer = { _, _ -> throw failure }
            assertThrowsUnknownHost("blocked.example.com")
        }
        assertTrue(systemCalls.isEmpty())
    }

    @Test
    fun `answers are cached 60 s per provider, failures not at all`() {
        assertEquals(InetAddress.getByName("1.2.3.4"), resolver.lookup("API.example.com.").single())
        resolver.lookup("api.example.com")
        assertEquals(1, dohCalls.size)
        now += PrivateDnsResolver.CACHE_TTL_MS
        resolver.lookup("api.example.com")
        assertEquals(2, dohCalls.size)

        dohAnswer = { _, _ -> throw UnknownHostException("down") }
        assertThrowsUnknownHost("other.example.com")
        assertThrowsUnknownHost("other.example.com")
        assertEquals(4, dohCalls.size)
    }

    @Test
    fun `a provider change clears the cache`() {
        resolver.lookup("api.example.com")
        chosen = PrivateDnsProvider.QUAD9
        assertEquals(InetAddress.getByName("5.6.7.8"), resolver.lookup("api.example.com").single())
        assertEquals(PrivateDnsProvider.QUAD9 to "api.example.com", dohCalls.last())
    }

    @Test
    fun `an answer from before a provider change is not cached`() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        dohAnswer = { provider, _ ->
            if (provider == PrivateDnsProvider.CLOUDFLARE) {
                inFlight.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            listOf(InetAddress.getByName(if (provider == PrivateDnsProvider.CLOUDFLARE) "1.2.3.4" else "5.6.7.8"))
        }
        val old = thread { resolver.lookup("api.example.com") }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        chosen = PrivateDnsProvider.QUAD9
        resolver.lookup("warm.example.com") // activates Quad9, clearing the cache
        release.countDown()
        old.join(5_000)
        assertFalse("the old lookup finished", old.isAlive)
        assertEquals(InetAddress.getByName("5.6.7.8"), resolver.lookup("api.example.com").single())
    }

    @Test
    fun `the cache holds at most 256 hosts`() {
        repeat(PrivateDnsResolver.CACHE_MAX_HOSTS + 10) { resolver.lookup("h$it.example.com") }
        val calls = dohCalls.size
        resolver.lookup("h${PrivateDnsResolver.CACHE_MAX_HOSTS + 9}.example.com")
        assertEquals("the newest entry stays cached", calls, dohCalls.size)
        resolver.lookup("h0.example.com")
        assertEquals("the oldest entry went when the cap was hit", calls + 1, dohCalls.size)
    }

    @Test
    fun `AppDns fails a public lookup closed while its setting source is unreadable`() {
        val before = PrivateDnsSettings.source
        PrivateDnsSettings.source = { null }
        try {
            try {
                AppDns.lookup("api.example.com")
                fail("expected UnknownHostException")
            } catch (_: UnknownHostException) {
            }
        } finally {
            PrivateDnsSettings.source = before
        }
    }
}
