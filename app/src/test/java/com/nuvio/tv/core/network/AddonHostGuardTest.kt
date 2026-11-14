package com.nuvio.tv.core.network

import com.nuvio.tv.core.network.AddonHostGuard.Admission
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AddonHostGuardTest {

    private var now = 1_000_000L
    private val guard = AddonHostGuard(nowMs = { now }, wallNowMs = { now })
    private val host = "addon.example"

    private fun fail(times: Int) = repeat(times) {
        assertEquals(Admission.ALLOW, guard.admit(host))
        guard.onNetworkFailure(host, probe = false)
    }

    @Test
    fun `a 429 pauses only that host, for Retry-After`() {
        guard.onAnswer(host, 429, "5", probe = false)
        assertEquals(Admission.SKIP_RATE_LIMITED, guard.admit(host))
        assertEquals(Admission.ALLOW, guard.admit("other.example"))
        now += 5_000L
        assertEquals(Admission.ALLOW, guard.admit(host))
    }

    @Test
    fun `a 429 without Retry-After pauses for the fallback`() {
        guard.onAnswer(host, 429, null, probe = false)
        now += AddonHostGuard.PAUSE_FALLBACK_MS - 1
        assertEquals(Admission.SKIP_RATE_LIMITED, guard.admit(host))
        now += 1
        assertEquals(Admission.ALLOW, guard.admit(host))
    }

    @Test
    fun `a 503 pauses only with Retry-After, else counts as a failure`() {
        guard.onAnswer(host, 503, "3", probe = false)
        assertEquals(Admission.SKIP_RATE_LIMITED, guard.admit(host))
        now += 3_000L
        repeat(3) { guard.onAnswer(host, 503, null, probe = false) }
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(host))
    }

    @Test
    fun `three failures within the window open the breaker, a success between resets`() {
        fail(2)
        guard.onAnswer(host, 404, null, probe = false) // a 4xx is an answer: the host is alive
        fail(2)
        assertEquals(Admission.ALLOW, guard.admit(host))
        guard.onNetworkFailure(host, probe = false)
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(host))
    }

    @Test
    fun `failures spread past the window do not open it`() {
        fail(2)
        now += AddonHostGuard.FAILURE_WINDOW_MS + 1
        fail(1)
        assertEquals(Admission.ALLOW, guard.admit(host))
    }

    @Test
    fun `after the cooldown one probe runs and decides`() {
        fail(3)
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(host))
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(host)) // probe in flight
        guard.onNetworkFailure(host, probe = true)
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(host)) // re-opened
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(host))
        guard.onAnswer(host, 200, null, probe = true)
        assertEquals(Admission.ALLOW, guard.admit(host))
    }

    @Test
    fun `a probe failure does not re-open a breaker another call closed`() {
        fail(3)
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(host))
        guard.onAnswer(host, 200, null, probe = false) // a call admitted before the breaker opened
        guard.onNetworkFailure(host, probe = true)
        assertEquals(Admission.ALLOW, guard.admit(host))
    }

    @Test
    fun `a probe with no outcome frees its slot`() {
        fail(3)
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(host))
        guard.onNoOutcome(host, probe = true)
        assertEquals(Admission.PROBE, guard.admit(host))
    }
}

class AddonHostGuardInterceptorTest {

    private companion object {
        const val KEY = "addon.example:443"
    }

    private var now = 1_000_000L
    private val guard = AddonHostGuard(nowMs = { now }, wallNowMs = { now })
    private var online = true
    private var onlineReadThrows = false
    private val interceptor = AddonHostGuardInterceptor(guard, deviceOnline = {
        if (onlineReadThrows) throw SecurityException("no access")
        online
    })
    private val request = Request.Builder().url("https://addon.example/key/stream/movie/tt1.json").build()
    private var canceled = false
    private var callTimeout = okio.Timeout().timeout(30, java.util.concurrent.TimeUnit.SECONDS)

    private fun response(req: Request, code: Int, fromNetwork: Boolean = true, fromCache: Boolean = false): Response {
        fun bare() = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m").build()
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .body("{}".toResponseBody(null))
            .apply { if (fromNetwork) networkResponse(bare()) }
            .apply { if (fromCache) cacheResponse(bare()) }
            .build()
    }

    private fun chain(answer: (Request) -> Response): Pair<Interceptor.Chain, List<Request>> {
        val seen = mutableListOf<Request>()
        val call = mockk<Call> {
            every { isCanceled() } answers { canceled }
            every { timeout() } answers { callTimeout }
        }
        val captured = slot<Request>()
        val c = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { call() } returns call
            every { proceed(capture(captured)) } answers { seen += captured.captured; answer(captured.captured) }
        }
        return c to seen
    }

    private fun openBreaker() = repeat(3) { guard.onNetworkFailure(KEY, probe = false) }

    @Test
    fun `a skipped request asks only the cache and ends in a synthetic answer`() {
        openBreaker()
        // OkHttp's unsatisfiable only-if-cached answer: no cache, no network response.
        val (c, seen) = chain { response(it, 504, fromNetwork = false) }
        val out = interceptor.intercept(c)
        assertEquals(503, out.code)
        assertEquals(AddonHostGuardInterceptor.SKIPPED_BREAKER_OPEN, out.message)
        assertTrue(seen.single().cacheControl.onlyIfCached)
    }

    @Test
    fun `a skipped request still gets a fresh cached answer`() {
        guard.onAnswer(KEY, 429, "30", probe = false)
        val (c, _) = chain { response(it, 200, fromNetwork = false, fromCache = true) }
        assertEquals(200, interceptor.intercept(c).code)
    }

    @Test
    fun `network failures count, cancelled calls do not`() {
        val (failing, _) = chain { throw IOException("refused") }
        repeat(2) { runCatching { interceptor.intercept(failing) } }
        canceled = true
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        assertEquals(Admission.ALLOW, guard.admit(KEY))
        canceled = false
        runCatching { interceptor.intercept(failing) }
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(KEY))
    }

    @Test
    fun `a call that ran out its callTimeout counts, though OkHttp cancelled it`() {
        // 1.5 s timeout less the 1 s slack: a call lasting 0.6 s ran it out.
        val (failing, _) = chain { Thread.sleep(600); throw java.io.InterruptedIOException("timeout") }
        canceled = true
        callTimeout = okio.Timeout().timeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(KEY))
    }

    @Test
    fun `a cancelled call well short of its callTimeout does not count`() {
        val (failing, _) = chain { throw IOException("Canceled") }
        canceled = true
        callTimeout = okio.Timeout().timeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        assertEquals(Admission.ALLOW, guard.admit(KEY))
    }

    @Test
    fun `a throwing online read counts the failure, frees the probe and keeps the caller's exception`() {
        openBreaker()
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        onlineReadThrows = true
        val original = IOException("refused")
        val (failing, _) = chain { throw original }
        val thrown = runCatching { interceptor.intercept(failing) }.exceptionOrNull()
        assertTrue(thrown === original)
        // The probe failed and was counted: re-opened, not wedged in flight.
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(KEY))
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(KEY))
    }

    @Test
    fun `a probe that throws frees its slot through the interceptor`() {
        openBreaker()
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        canceled = true // the caller went away mid-probe
        val (failing, _) = chain { throw IOException("Canceled") }
        runCatching { interceptor.intercept(failing) }
        assertEquals(Admission.PROBE, guard.admit(KEY))
    }

    @Test
    fun `failures while the device is offline do not count`() {
        val (failing, _) = chain { throw java.net.UnknownHostException("no network") }
        online = false
        repeat(5) { runCatching { interceptor.intercept(failing) } }
        assertEquals(Admission.ALLOW, guard.admit(KEY))
    }

    @Test
    fun `a rate-limited skip with no cache ends in a synthetic 429`() {
        guard.onAnswer(KEY, 429, "30", probe = false)
        val (c, _) = chain { response(it, 504, fromNetwork = false) }
        val out = interceptor.intercept(c)
        assertEquals(429, out.code)
        assertEquals(AddonHostGuardInterceptor.SKIPPED_RATE_LIMITED, out.message)
    }

    @Test
    fun `a probe answered 200 through the interceptor closes the breaker, a 5xx re-opens it`() {
        openBreaker()
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        interceptor.intercept(chain { response(it, 500) }.first)
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(KEY))
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        interceptor.intercept(chain { response(it, 200) }.first)
        assertEquals(Admission.ALLOW, guard.admit(KEY))
    }

    @Test
    fun `a probe ending in a non-IO error frees its slot and rethrows`() {
        openBreaker()
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        val boom = IllegalStateException("boom")
        val thrown = runCatching { interceptor.intercept(chain { throw boom }.first) }.exceptionOrNull()
        assertTrue(thrown === boom)
        assertEquals(Admission.PROBE, guard.admit(KEY))
    }

    @Test
    fun `hosts are keyed with their port`() {
        openBreaker()
        assertEquals(Admission.ALLOW, guard.admit("addon.example:8080"))
    }

    @Test
    fun `a cache hit says nothing about the host`() {
        val (c, _) = chain { response(it, 200, fromNetwork = false, fromCache = true) }
        repeat(2) { guard.onNetworkFailure(KEY, probe = false) }
        interceptor.intercept(c)
        guard.onNetworkFailure(KEY, probe = false)
        assertEquals(Admission.SKIP_BREAKER_OPEN, guard.admit(KEY))
    }

    @Test
    fun `a 429 answer pauses the host`() {
        val (c, _) = chain { response(it, 429).newBuilder().header("Retry-After", "5").build() }
        interceptor.intercept(c)
        assertEquals(Admission.SKIP_RATE_LIMITED, guard.admit(KEY))
    }

    // Spec 68 X7 P6: the certificate flag.

    private fun certificateFailure() =
        javax.net.ssl.SSLHandshakeException("untrusted").apply {
            initCause(java.security.cert.CertificateException("self-signed"))
        }

    @Test
    fun `a certificate failure flags the host and counts for the breaker, even offline`() {
        online = false
        val (failing, _) = chain { throw certificateFailure() }
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        assertEquals(setOf(KEY), guard.certificateFailures.value)
        assertEquals(Admission.SKIP_CERTIFICATE, guard.admit(KEY))
    }

    @Test
    fun `the open breaker of a flagged host skips with the typed certificate error, a cache hit still serves`() {
        val (failing, _) = chain { throw certificateFailure() }
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        val (uncached, seen) = chain { response(it, 504, fromNetwork = false) }
        val thrown = runCatching { interceptor.intercept(uncached) }.exceptionOrNull()
        assertTrue(thrown is com.nuvio.tv.core.network.tls.AddonCertificateSkipException)
        assertTrue(seen.single().cacheControl.onlyIfCached)
        val (cached, _) = chain { response(it, 200, fromNetwork = false, fromCache = true) }
        assertEquals(200, interceptor.intercept(cached).code)
        // Neither the skip nor the cache hit cleared the flag.
        assertEquals(setOf(KEY), guard.certificateFailures.value)
    }

    @Test
    fun `an answer clears the flag, and so does a plain outage`() {
        val (failing, _) = chain { throw certificateFailure() }
        runCatching { interceptor.intercept(failing) }
        interceptor.intercept(chain { response(it, 200) }.first)
        assertTrue(guard.certificateFailures.value.isEmpty())
        runCatching { interceptor.intercept(failing) }
        runCatching { interceptor.intercept(chain { throw IOException("refused") }.first) }
        assertTrue(guard.certificateFailures.value.isEmpty())
    }

    @Test
    fun `a cancelled call leaves the flag`() {
        runCatching { interceptor.intercept(chain { throw certificateFailure() }.first) }
        canceled = true
        callTimeout = okio.Timeout().timeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { interceptor.intercept(chain { throw IOException("Canceled") }.first) }
        assertEquals(setOf(KEY), guard.certificateFailures.value)
    }

    @Test
    fun `an addon URL's row key is the key its calls are flagged under`() {
        // The interceptor keys request.url (addon.example, default port 443): the Addons row must match it.
        val (failing, _) = chain { throw certificateFailure() }
        runCatching { interceptor.intercept(failing) }
        assertEquals(KEY, addonGuardKeyOf("https://Addon.example/key/manifest.json"))
        assertTrue(addonGuardKeyOf("https://addon.example/key")!! in guard.certificateFailures.value)
        assertEquals("nas.lan:8443", addonGuardKeyOf("stremio://nas.lan:8443/x"))
    }

    @Test
    fun `a half-open probe failing on its certificate re-opens and frees its slot`() {
        val (failing, _) = chain { throw certificateFailure() }
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(KEY))
        guard.onNoOutcome(KEY, probe = true) // hand the slot back for the interceptor's own probe
        runCatching { interceptor.intercept(failing) }
        assertEquals(Admission.SKIP_CERTIFICATE, guard.admit(KEY))
        now += AddonHostGuard.OPEN_COOLDOWN_MS
        assertEquals(Admission.PROBE, guard.admit(KEY)) // not wedged in flight
    }

    @Test
    fun `Trust closes the breaker and clears the flag but keeps a 429 pause`() {
        val (failing, _) = chain { throw certificateFailure() }
        repeat(3) { runCatching { interceptor.intercept(failing) } }
        guard.onTrusted(KEY)
        assertTrue(guard.certificateFailures.value.isEmpty())
        assertEquals(Admission.ALLOW, guard.admit(KEY))
        guard.onAnswer(KEY, 429, "30", probe = false)
        guard.onTrusted(KEY)
        assertEquals(Admission.SKIP_RATE_LIMITED, guard.admit(KEY))
    }
}
