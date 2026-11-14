package com.nuvio.tv.livetv.player

import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 34 D8: states, cooldowns, try order, and the bounded file store. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamHealthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 60L * 60 * 1000
    private val min = 60L * 1000
    private val t0 = 1_000L * hour

    private fun failedNever(kind: FailureKind, failures: Int = 1) =
        StreamHealthRecord(lastFailedMs = t0, lastFailure = kind, failures = failures)

    private fun failedAfterPlay(kind: FailureKind) =
        StreamHealthRecord(lastPlayedMs = t0 - 2 * hour, lastFailedMs = t0, lastFailure = kind, failures = 1)

    @Test
    fun `failure kinds map to states by whether the stream played recently`() {
        assertEquals(StreamState.UNREACHABLE, StreamHealth.state(failedNever(FailureKind.NETWORK)))
        assertEquals(StreamState.FLAKY, StreamHealth.state(failedAfterPlay(FailureKind.STALL)))
        assertEquals(StreamState.BLOCKED, StreamHealth.state(failedNever(FailureKind.BLOCKED)))
        assertEquals(StreamState.DISPUTED, StreamHealth.state(failedAfterPlay(FailureKind.BLOCKED)))
        assertEquals(StreamState.DEAD, StreamHealth.state(failedNever(FailureKind.DEAD)))
        assertEquals(StreamState.DISPUTED, StreamHealth.state(failedAfterPlay(FailureKind.DEAD)))
        assertEquals(StreamState.DEAD, StreamHealth.state(failedAfterPlay(FailureKind.PARSE)))
        assertEquals(StreamState.DEAD, StreamHealth.state(failedNever(FailureKind.DRM)))
        // Spec 74 S2b: a licence refused after a recent play is likely an expired token.
        assertEquals(StreamState.DISPUTED, StreamHealth.state(failedAfterPlay(FailureKind.DRM)))
        assertEquals(StreamState.ALIVE, StreamHealth.state(StreamHealth.played(t0)))
        // A play older than a day no longer vouches for the stream.
        val stale = StreamHealthRecord(lastPlayedMs = t0 - 25 * hour, lastFailedMs = t0, lastFailure = FailureKind.DEAD, failures = 1)
        assertEquals(StreamState.DEAD, StreamHealth.state(stale))
    }

    @Test
    fun `network-class cooldown doubles from 5 minutes and caps at a day, HTTP is a day`() {
        assertEquals(5 * min, StreamHealth.cooldownMs(failedNever(FailureKind.NETWORK, 1)))
        assertEquals(10 * min, StreamHealth.cooldownMs(failedNever(FailureKind.NETWORK, 2)))
        assertEquals(40 * min, StreamHealth.cooldownMs(failedNever(FailureKind.STALL, 4)))
        assertEquals(24 * hour, StreamHealth.cooldownMs(failedNever(FailureKind.NETWORK, 12)))
        assertEquals(24 * hour, StreamHealth.cooldownMs(failedNever(FailureKind.NETWORK, 500)))
        assertEquals(24 * hour, StreamHealth.cooldownMs(failedNever(FailureKind.BLOCKED)))
        assertEquals(5 * min, StreamHealth.cooldownMs(failedAfterPlay(FailureKind.DEAD)))
        assertTrue(StreamHealth.isCooling(failedNever(FailureKind.NETWORK), t0 + 4 * min))
        assertFalse(StreamHealth.isCooling(failedNever(FailureKind.NETWORK), t0 + 5 * min))
        assertFalse(StreamHealth.isCooling(null, t0))
    }

    @Test
    fun `failures count up and a play resets them`() {
        val once = StreamHealth.failed(null, FailureKind.NETWORK, t0)
        val twice = StreamHealth.failed(once, FailureKind.STALL, t0 + min)
        assertEquals(2, twice.failures)
        assertEquals(FailureKind.STALL, twice.lastFailure)
        assertEquals(StreamHealthRecord(lastPlayedMs = t0 + 2 * min), StreamHealth.played(t0 + 2 * min))
    }

    @Test
    fun `last good first, then source order, cooling last`() {
        val a = LiveStream("http://a/1.m3u8")
        val b = LiveStream("http://b/1.m3u8")
        val c = LiveStream("http://c/1.m3u8")
        val d = LiveStream("http://d/1.m3u8")
        val records = mapOf(
            "http://a/1.m3u8" to failedNever(FailureKind.DEAD),
            "http://b/1.m3u8" to StreamHealthRecord(lastPlayedMs = t0 - hour),
            "http://c/1.m3u8" to StreamHealthRecord(lastPlayedMs = t0 - min)
        )
        assertEquals(listOf(c, b, d, a), StreamHealth.order(listOf(a, b, c, d), records, t0))
        assertEquals(listOf(a, d), StreamHealth.order(listOf(a, d), emptyMap(), t0))
        assertFalse(StreamHealth.allCooling(listOf(a, b), records, t0))
        assertTrue(StreamHealth.allCooling(listOf(a), records, t0))
        assertFalse(StreamHealth.allCooling(emptyList(), records, t0))
    }

    @Test
    fun `store persists, skips repeat healthy writes, and evicts the oldest`() = runTest {
        val dir = tmp.newFolder("livetv")
        val store = StreamHealthStore(dir, UnconfinedTestDispatcher(testScheduler), maxRecords = 2)
        val a = LiveStream("http://a/1.m3u8")
        val b = LiveStream("http://b/1.m3u8")
        val c = LiveStream("http://c/1.m3u8")
        store.recordFailed(a, FailureKind.NETWORK, t0)
        store.recordPlayed(b, t0 + min)
        store.recordPlayed(b, t0 + 2 * min) // memory only: healthy and written within the hour
        val reread = StreamHealthStore(dir, UnconfinedTestDispatcher(testScheduler), maxRecords = 2)
        reread.load()
        assertEquals(t0 + min, reread.records.value["http://b/1.m3u8"]?.lastPlayedMs)
        assertEquals(1, reread.records.value["http://a/1.m3u8"]?.failures)

        store.recordFailed(c, FailureKind.DEAD, t0 + 3 * min)
        assertEquals(setOf("http://b/1.m3u8", "http://c/1.m3u8"), store.records.value.keys)
        val evicted = StreamHealthStore(dir, UnconfinedTestDispatcher(testScheduler), maxRecords = 2)
        evicted.load()
        assertEquals(setOf("http://b/1.m3u8", "http://c/1.m3u8"), evicted.records.value.keys)
    }

    @Test
    fun `skipped writes leave the file at most an hour stale, and a play after a failure is written`() = runTest {
        val dir = tmp.newFolder("livetv")
        val store = StreamHealthStore(dir, UnconfinedTestDispatcher(testScheduler))
        val a = LiveStream("http://a/1.m3u8")
        store.recordPlayed(a, t0)
        // Zapping back every 20 minutes: the file catches up once the written play is an hour old.
        for (step in 1..4) store.recordPlayed(a, t0 + step * 20 * min)
        assertEquals(t0 + 60 * min, reread(dir)["http://a/1.m3u8"]?.lastPlayedMs)

        store.recordFailed(a, FailureKind.NETWORK, t0 + 61 * min)
        store.recordPlayed(a, t0 + 62 * min)
        assertEquals(StreamHealthRecord(lastPlayedMs = t0 + 62 * min), reread(dir)["http://a/1.m3u8"])
    }

    private suspend fun kotlinx.coroutines.test.TestScope.reread(dir: java.io.File): Map<String, StreamHealthRecord> {
        val fresh = StreamHealthStore(dir, UnconfinedTestDispatcher(testScheduler))
        fresh.load()
        return fresh.records.value
    }

    @Test
    fun `a failure stamped in the future never cools, and a dispute is judged at its failure time`() {
        val future = StreamHealthRecord(lastFailedMs = t0 + hour, lastFailure = FailureKind.BLOCKED, failures = 1)
        assertFalse(StreamHealth.isCooling(future, t0))
        // Played 2 h before failing: still disputed (5 min) a day later, not re-read as blocked.
        val disputed = failedAfterPlay(FailureKind.BLOCKED)
        assertEquals(StreamState.DISPUTED, StreamHealth.state(disputed))
        assertFalse(StreamHealth.isCooling(disputed, t0 + 23 * hour))
    }
}
