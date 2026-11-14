package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.core.stream.FailedPlayback
import com.nuvio.tv.core.stream.FailedReleaseStorage
import com.nuvio.tv.core.stream.FailedReleases
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import com.nuvio.tv.core.stream.FailureReason
import com.nuvio.tv.core.stream.RecentFailures
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.FailureStrings
import com.nuvio.tv.ui.screens.player.RealFailureStrings
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 75 S6 (mockup stages 9-10): a Sources row's failure mark, its reason and age. */
class SourceFailureMarksTest {

    private val s: FailureStrings = RealFailureStrings

    private class MemoryStorage(var value: String? = null) : FailedReleaseStorage {
        override fun load(): String? = value
        override fun save(value: String) {
            this.value = value
        }
    }

    private class FakeClock(var nowMs: Long = 1_800_000_000_000L) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private val clock = FakeClock()
    private val store = FailedReleases(MemoryStorage(), clock)

    private fun stream(url: String) = Stream(
        name = "AIOStreams 2160p", title = null, description = null, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "AIOStreams", addonLogo = null
    )

    private val failed = stream("https://debrid.example/dl/a.mkv")
    private val healthy = stream("https://debrid.example/dl/b.mkv")

    @Test
    fun `a stored failure reads its reason and its age at the store read`() {
        store.record(FailureReason.DEAD_LINK, FailedPlayback(listOf(failed.url), failed), FailureMark(FailureCause.LINK_EXPIRED))
        clock.nowMs += 2 * 60_000L + 5_000L
        val recent = store.recent()
        assertEquals(SourceFailureMark("Link expired", "2 min ago"), SourceFailureMarks.forRow(failed, recent, emptySet(), s))
        assertNull(SourceFailureMarks.forRow(healthy, recent, emptySet(), s))
    }

    @Test
    fun `too slow carries both speeds`() {
        store.record(
            FailureReason.NO_START,
            FailedPlayback(listOf(failed.url), failed),
            FailureMark(FailureCause.TOO_SLOW, 18.4, 69.2)
        )
        clock.nowMs += 3 * 3_600_000L
        assertEquals(
            SourceFailureMark("Too slow: 18 of 70 Mbit/s", "3 h ago"),
            SourceFailureMarks.forRow(failed, store.recent(), emptySet(), s)
        )
    }

    @Test
    fun `a session failure the store has no entry for reads just now`() {
        assertEquals(
            SourceFailureMark("Failed", "just now"),
            SourceFailureMarks.forRow(failed, RecentFailures.EMPTY, setOf(failed.url!!), s)
        )
        assertNull(SourceFailureMarks.forRow(healthy, RecentFailures.EMPTY, setOf(failed.url!!), s))
    }

    @Test
    fun `the store's entry wins over the session's just now`() {
        store.record(FailureReason.DEAD_LINK, FailedPlayback(listOf(failed.url), failed), FailureMark(FailureCause.REMOVED))
        assertEquals(
            SourceFailureMark("File gone", "just now"),
            SourceFailureMarks.forRow(failed, store.recent(), setOf(failed.url!!), s)
        )
    }

    @Test
    fun `an entry from before causes were kept reads Failed`() {
        store.record(FailureReason.DEAD_LINK, FailedPlayback(listOf(failed.url), failed))
        assertEquals("Failed", SourceFailureMarks.forRow(failed, store.recent(), emptySet(), s)?.reason)
    }

    @Test
    fun ages() {
        assertEquals("just now", SourceFailureMarks.age(59_999L, s))
        assertEquals("1 min ago", SourceFailureMarks.age(60_000L, s))
        assertEquals("59 min ago", SourceFailureMarks.age(3_599_999L, s))
        assertEquals("1 h ago", SourceFailureMarks.age(3_600_000L, s))
        assertEquals("23 h ago", SourceFailureMarks.age(24 * 3_600_000L - 1, s))
    }
}
