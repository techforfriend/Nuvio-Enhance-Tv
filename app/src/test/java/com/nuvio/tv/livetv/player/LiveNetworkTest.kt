package com.nuvio.tv.livetv.player

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 90 L1 (D4, D5): when the device counts as online, and how often it asks. */
class LiveNetworkTest {

    private var link = true
    private var answer = true
    private var probes = 0
    private var now = 0L
    private val network = LiveNetwork(hasLink = { link }, probe = { probes++; answer }, nowMs = { now })

    @Test
    fun `a yes is reused for 60 s, then asked again`() = runTest {
        assertTrue(network.online())
        now = LiveNetwork.ONLINE_REUSE_MS - 1
        assertTrue(network.online())
        assertEquals(1, probes)
        now = LiveNetwork.ONLINE_REUSE_MS
        assertTrue(network.online())
        assertEquals(2, probes)
    }

    @Test
    fun `a no is never reused`() = runTest {
        answer = false
        assertFalse(network.online())
        assertFalse(network.online())
        assertEquals(2, probes)
        answer = true
        assertTrue(network.online())
        assertEquals(3, probes)
    }

    @Test
    fun `no network link is offline without a request, and drops an earlier yes`() = runTest {
        assertTrue(network.online())
        link = false
        assertFalse(network.online())
        assertEquals(1, probes)
        // The link is back within the 60 s, the internet maybe not: ask again.
        link = true
        answer = false
        assertFalse(network.online())
        assertEquals(2, probes)
    }

    @Test
    fun `a link lost while the probe ran answers no and leaves no reusable yes`() = runTest {
        val racing = LiveNetwork(hasLink = { link }, probe = { probes++; link = false; true }, nowMs = { now })
        assertFalse(racing.online())
        link = true
        assertFalse(racing.online())
        assertEquals(2, probes)
    }

    @Test
    fun `callers queued behind a probe share its answer`() = runTest {
        val slow = LiveNetwork(hasLink = { true }, probe = { probes++; delay(3_000); false }, nowMs = { now })
        val answers = (1..3).map { async { slow.online() } }.awaitAll()
        assertEquals(listOf(false, false, false), answers)
        assertEquals(1, probes)
        // A later caller asks afresh: a no is not reused.
        assertFalse(slow.online())
        assertEquals(2, probes)
    }

    @Test
    fun `an offline answer waits only for the session that asked, with nothing playing`() {
        assertEquals(LiveNetwork.Outcome.PROCEED, LiveNetwork.outcome(online = true, sameSession = false, playing = true))
        assertEquals(LiveNetwork.Outcome.PROCEED, LiveNetwork.outcome(online = true, sameSession = true, playing = false))
        assertEquals(LiveNetwork.Outcome.WAIT, LiveNetwork.outcome(online = false, sameSession = true, playing = false))
        // The next stream already plays, or the user tuned elsewhere: no record and no wait.
        assertEquals(LiveNetwork.Outcome.NONE, LiveNetwork.outcome(online = false, sameSession = true, playing = true))
        assertEquals(LiveNetwork.Outcome.NONE, LiveNetwork.outcome(online = false, sameSession = false, playing = false))
    }

    @Test
    fun `offline retries wait 5, 10, 20, 40 s, then every 60 s`() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..5).map(LiveNetwork::retryDelayMs))
    }

    @Test
    fun `only failures an outage produces are network-class`() {
        assertTrue(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.NETWORK)))
        assertTrue(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.STALL)))
        // A server answered: its status is the stream's (5xx, or an Xtream panel's refusal, Spec 90 L5).
        assertFalse(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.NETWORK, 503)))
        assertFalse(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.NETWORK, 403)))
        assertFalse(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.DEAD, 404)))
        assertFalse(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.PARSE)))
        assertFalse(LiveNetwork.isNetworkClass(LiveFailure(FailureKind.DRM, drm = DrmFailure.REFUSED)))
    }
}
