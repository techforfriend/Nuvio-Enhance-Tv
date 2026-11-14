package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 P5 (D8): what the failure store remembers, under which keys, and for how long. */
class FailedReleasesTest {

    private class MemoryStorage(var value: String? = null) : FailedReleaseStorage {
        var saves = 0
        override fun load(): String? = value
        override fun save(value: String) {
            this.value = value
            saves++
        }
    }

    private class FakeClock(var nowMs: Long = 1_800_000_000_000L) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
        fun advance(by: Duration) {
            nowMs += by.inWholeMilliseconds
        }
    }

    private val storage = MemoryStorage()
    private val clock = FakeClock()
    private val store = FailedReleases(storage, clock)

    private fun stream(
        url: String? = "https://debrid.example/dl/abc/file.mkv",
        infoHash: String? = null,
        fileIdx: Int? = null,
        filename: String? = null,
        size: Long? = null
    ) = Stream(
        name = "Torrentio\n2160p", title = null, description = null, url = url, ytId = null,
        infoHash = infoHash, fileIdx = fileIdx, externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null,
            videoSize = size, filename = filename
        ),
        addonName = "Torrentio", addonLogo = null
    )

    private fun recordFor(reason: FailureReason, playing: Stream) =
        store.record(reason, FailedPlayback(urls = listOf(playing.getStreamUrl()), listEntry = playing))

    @Test
    fun `an empty store snapshots as EMPTY`() {
        assertSame(FailureSnapshot.EMPTY, store.snapshot())
    }

    @Test
    fun `a wrong file is excluded by its url and by its release on another link`() {
        val playing = stream(infoHash = "ABCDEF", fileIdx = 3, filename = "Film.2019.2160p.mkv", size = 20_000_000_000)
        recordFor(FailureReason.WRONG_FILE, playing)
        val snapshot = store.snapshot()
        assertTrue(snapshot.excludes(playing))
        assertTrue(store.isExcludedUrl("https://debrid.example/dl/abc/file.mkv"))
        assertFalse(store.isExcludedUrl("https://debrid.example/dl/other.mkv"))
        // Same torrent file, another service's link (hash case differs).
        assertTrue(snapshot.excludes(stream(url = "https://other.example/x", infoHash = "abcdef", fileIdx = 3)))
        // Same filename and size, another addon with no hash.
        assertTrue(snapshot.excludes(stream(url = "https://other.example/y", filename = "film.2019.2160P.mkv", size = 20_000_000_000)))
        assertFalse(snapshot.demotes(playing))
    }

    @Test
    fun `another file of the same pack or a hash without a file index is not the release`() {
        recordFor(FailureReason.WRONG_FILE, stream(infoHash = "abcdef", fileIdx = 3))
        val snapshot = store.snapshot()
        assertFalse(snapshot.excludes(stream(url = "https://other.example/e4", infoHash = "abcdef", fileIdx = 4)))
        assertFalse(snapshot.excludes(stream(url = "https://other.example/e?", infoHash = "abcdef", fileIdx = null)))
        // Same name, other size: another encode.
        recordFor(FailureReason.WRONG_FILE, stream(url = "https://a.example/1", filename = "Show.S01E01.mkv", size = 1_000))
        assertFalse(store.snapshot().excludes(stream(url = "https://b.example/2", filename = "Show.S01E01.mkv", size = 2_000)))
    }

    @Test
    fun `a link-side failure is remembered by url only and demoted, not excluded`() {
        val playing = stream(infoHash = "abcdef", fileIdx = 3, filename = "Film.mkv", size = 5_000)
        recordFor(FailureReason.DEAD_LINK, playing)
        val snapshot = store.snapshot()
        assertTrue(snapshot.demotes(playing))
        assertFalse(snapshot.excludes(playing))
        assertFalse(snapshot.demotes(stream(url = "https://other.example/x", infoHash = "abcdef", fileIdx = 3)))
    }

    @Test
    fun `the player's own facts key the release when the list entry is unknown`() {
        store.record(
            FailureReason.WRONG_FILE,
            FailedPlayback(urls = listOf("http://127.0.0.1:8090/stream"), infoHash = "abcdef", fileIdx = 1)
        )
        assertTrue(store.snapshot().excludes(stream(url = "https://debrid.example/z", infoHash = "abcdef", fileIdx = 1)))
    }

    @Test
    fun `link-side entries last a day and a wrong file thirty days`() {
        val dead = stream(url = "https://a.example/dead")
        val wrong = stream(url = "https://a.example/wrong")
        recordFor(FailureReason.NO_START, dead)
        recordFor(FailureReason.WRONG_FILE, wrong)
        clock.advance(23.hours)
        assertTrue(store.snapshot().demotes(dead))
        clock.advance(2.hours)
        assertFalse(store.snapshot().demotes(dead))
        clock.advance(28.days)
        assertTrue(store.snapshot().excludes(wrong))
        clock.advance(2.days)
        assertSame(FailureSnapshot.EMPTY, store.snapshot())
    }

    @Test
    fun `a wrong file overrides a live link failure and only it excludes the url`() {
        val playing = stream()
        recordFor(FailureReason.DEAD_LINK, playing)
        assertFalse(store.isExcludedUrl("https://debrid.example/dl/abc/file.mkv"))
        recordFor(FailureReason.WRONG_FILE, playing)
        assertTrue(store.isExcludedUrl("https://debrid.example/dl/abc/file.mkv"))
        assertTrue(store.snapshot().excludes(playing))
        assertFalse(store.snapshot().demotes(playing))
    }

    @Test
    fun `a later link failure does not shorten a wrong-file exclusion`() {
        val playing = stream()
        recordFor(FailureReason.WRONG_FILE, playing)
        recordFor(FailureReason.DEAD_LINK, playing)
        clock.advance(2.days)
        assertTrue(store.snapshot().excludes(playing))
    }

    @Test
    fun `entries survive a restart and a corrupt file reads as empty`() {
        val playing = stream()
        recordFor(FailureReason.WRONG_FILE, playing)
        assertTrue(FailedReleases(storage, clock).snapshot().excludes(playing))
        assertFalse(storage.value.orEmpty().contains("debrid.example"))
        assertSame(FailureSnapshot.EMPTY, FailedReleases(MemoryStorage("{not json"), clock).snapshot())
        assertSame(FailureSnapshot.EMPTY, FailedReleases(MemoryStorage("""{"u:x":{"reason":"GONE","at":1}}"""), clock).snapshot())
    }

    @Test
    fun `the store keeps the newest entries up to the cap`() {
        repeat(FailedReleases.MAX_ENTRIES + 5) { i ->
            recordFor(FailureReason.DEAD_LINK, stream(url = "https://a.example/$i"))
            clock.advance(1.seconds)
        }
        val snapshot = store.snapshot()
        assertEquals(FailedReleases.MAX_ENTRIES, snapshot.demoted.size)
        assertFalse(snapshot.demotes(stream(url = "https://a.example/0")))
        assertTrue(snapshot.demotes(stream(url = "https://a.example/${FailedReleases.MAX_ENTRIES + 4}")))
    }

    @Test
    fun `snapshots of the same entries are equal values and a record changes them`() {
        recordFor(FailureReason.CORRUPT, stream())
        val before = store.snapshot()
        val again = store.snapshot()
        assertNotSame(before, again)
        assertEquals(before, again)
        recordFor(FailureReason.CORRUPT, stream(url = "https://a.example/2"))
        assertNotEquals(before, store.snapshot())
    }

    // As the player records with no list entry: its listed filename hint and listed size key the
    // release; the hasher's exact size or a URL-derived name would not, and miss (never exclude).
    @Test
    fun `player facts key the release by the listed filename and size`() {
        fun facts(filename: String?, size: Long?) = FailedPlayback(
            urls = listOf("https://debrid.example/played"), filename = filename, size = size
        )
        val candidate = stream(url = "https://other.example/c", filename = "Film.2019.2160p.mkv", size = 19_430_000_000)
        store.record(FailureReason.WRONG_FILE, facts("Film.2019.2160p.mkv", 19_431_234_567))
        assertFalse("hasher's exact size: a miss", store.snapshot().excludes(candidate))
        store.record(FailureReason.WRONG_FILE, facts("played", 19_430_000_000))
        assertFalse("URL-derived name: a miss", store.snapshot().excludes(candidate))
        store.record(FailureReason.WRONG_FILE, facts("Film.2019.2160p.mkv", 19_430_000_000))
        assertTrue(store.snapshot().excludes(candidate))
    }

    @Test
    fun `a magnet-only candidate matches by its hash and file index`() {
        recordFor(FailureReason.WRONG_FILE, stream(infoHash = "0123456789abcdef0123456789abcdef01234567", fileIdx = 2))
        val magnet = stream(url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567", fileIdx = 2)
        assertTrue(store.snapshot().excludes(magnet))
    }

    @Test
    fun `an expired wrong file no longer blocks a link failure on the same url`() {
        val playing = stream()
        recordFor(FailureReason.WRONG_FILE, playing)
        clock.advance(31.days)
        recordFor(FailureReason.DEAD_LINK, playing)
        val snapshot = store.snapshot()
        assertFalse(snapshot.excludes(playing))
        assertTrue(snapshot.demotes(playing))
    }

    @Test
    fun `demotedAmong returns the demoted candidates by identity, null when none`() {
        val failed = stream(url = "https://a.example/f")
        val ok = stream(url = "https://a.example/ok")
        recordFor(FailureReason.PLACEHOLDER, failed)
        val snapshot = store.snapshot()
        assertEquals(setOf(failed), snapshot.demotedAmong(listOf(failed, ok)))
        assertEquals(null, snapshot.demotedAmong(listOf(ok)))
        assertEquals(null, FailureSnapshot.EMPTY.demotedAmong(listOf(failed)))
    }

    @Test
    fun `nothing to key records nothing`() {
        store.record(FailureReason.WRONG_FILE, FailedPlayback(urls = listOf(null, " ")))
        assertEquals(0, storage.saves)
    }

    @Test
    fun `a mark is kept with the entry and read back by url or release for a day`() {
        val playing = stream(infoHash = "ABCDEF", fileIdx = 3, filename = "Film.2019.2160p.mkv", size = 20_000_000_000)
        store.record(
            FailureReason.NO_START,
            FailedPlayback(urls = listOf(playing.getStreamUrl()), listEntry = playing),
            FailureMark(FailureCause.TOO_SLOW, measuredMbps = 18.4, neededMbps = 70.0)
        )
        val recorded = clock.nowMs
        clock.advance(2.hours)
        val recent = FailedReleases(storage, clock).recent()
        val mark = recent.markFor(playing)
        assertEquals(FailureReason.NO_START, mark?.reason)
        assertEquals(FailureMark(FailureCause.TOO_SLOW, 18.4, 70.0), mark?.mark)
        assertEquals(recorded, mark?.atEpochMs)
        // A link-side failure is keyed by URL only: the same release on another link is unmarked.
        assertEquals(null, recent.markFor(stream(url = "https://other.example/x", infoHash = "abcdef", fileIdx = 3)))
        clock.advance(23.hours)
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `a wrong file is marked for a day only, on its other links too`() {
        val playing = stream(infoHash = "ABCDEF", fileIdx = 3)
        store.record(
            FailureReason.WRONG_FILE,
            FailedPlayback(urls = listOf(playing.getStreamUrl()), listEntry = playing),
            FailureMark(FailureCause.WRONG_FILE)
        )
        val otherLink = stream(url = "https://other.example/x", infoHash = "abcdef", fileIdx = 3)
        assertEquals(FailureCause.WRONG_FILE, store.recent().markFor(otherLink)?.mark?.cause)
        clock.advance(2.days)
        assertSame(RecentFailures.EMPTY, store.recent())
        assertTrue(store.snapshot().excludes(otherLink))
    }

    @Test
    fun `an entry without a cause marks without one`() {
        val playing = stream()
        recordFor(FailureReason.DEAD_LINK, playing)
        val mark = store.recent().markFor(playing)
        assertEquals(FailureReason.DEAD_LINK, mark?.reason)
        assertEquals(null, mark?.mark)
    }

    @Test
    fun `the newer of a release-key and a url-key match wins`() {
        val linkA = stream(url = "https://a.example/f", infoHash = "abcdef", fileIdx = 1)
        val linkB = stream(url = "https://b.example/f", infoHash = "abcdef", fileIdx = 1)
        store.record(FailureReason.WRONG_FILE, FailedPlayback(urls = listOf(linkA.getStreamUrl()), listEntry = linkA), FailureMark(FailureCause.WRONG_FILE))
        clock.advance(1.hours)
        store.record(FailureReason.DEAD_LINK, FailedPlayback(urls = listOf(linkB.getStreamUrl()), listEntry = linkB), FailureMark(FailureCause.LINK_EXPIRED))
        assertEquals(FailureCause.LINK_EXPIRED, store.recent().markFor(linkB)?.mark?.cause)
        // And the other way round: a newer wrong file on link A's release beats link B's older url entry.
        clock.advance(1.hours)
        store.record(FailureReason.WRONG_FILE, FailedPlayback(urls = listOf(linkA.getStreamUrl()), listEntry = linkA), FailureMark(FailureCause.WRONG_FILE))
        assertEquals(FailureCause.WRONG_FILE, store.recent().markFor(linkB)?.mark?.cause)
    }

    @Test
    fun `the mark window ends at exactly one day`() {
        recordFor(FailureReason.DEAD_LINK, stream())
        clock.advance(1.days)
        assertEquals(FailureReason.DEAD_LINK, store.recent().markFor(stream())?.reason)
        clock.advance(1.seconds)
        assertSame(RecentFailures.EMPTY, store.recent())
    }

    @Test
    fun `a speed JSON cannot hold is dropped, not written`() {
        val playing = stream()
        store.record(
            FailureReason.NO_START,
            FailedPlayback(urls = listOf(playing.getStreamUrl())),
            FailureMark(FailureCause.TOO_SLOW, measuredMbps = Double.NaN, neededMbps = Double.POSITIVE_INFINITY)
        )
        val mark = FailedReleases(storage, clock).recent().markFor(playing)?.mark
        assertEquals(FailureMark(FailureCause.TOO_SLOW, null, null), mark)
        store.record(
            FailureReason.NO_START,
            FailedPlayback(urls = listOf(playing.getStreamUrl())),
            FailureMark(FailureCause.TOO_SLOW, measuredMbps = -3.0, neededMbps = 70.0)
        )
        assertEquals(FailureMark(FailureCause.TOO_SLOW, null, 70.0), FailedReleases(storage, clock).recent().markFor(playing)?.mark)
    }

    @Test
    fun `entries from before Spec 75 and causes this build does not know still load`() {
        val key = FailedReleases.urlKey("https://debrid.example/dl/abc/file.mkv")!!
        val other = FailedReleases.urlKey("https://debrid.example/dl/def/file.mkv")!!
        storage.value = """{"$key":{"reason":"DEAD_LINK","at":${clock.nowMs}},""" +
            """"$other":{"reason":"NO_START","at":${clock.nowMs},"cause":"SOMETHING_NEW","measuredMbps":3.5}}"""
        val reloaded = FailedReleases(storage, clock)
        assertTrue(reloaded.snapshot().demotes(stream()))
        val mark = reloaded.recent().markFor(stream(url = "https://debrid.example/dl/def/file.mkv"))
        assertEquals(FailureReason.NO_START, mark?.reason)
        assertEquals(null, mark?.mark)
    }

    @Test
    fun `a future-dated entry is not marked`() {
        recordFor(FailureReason.DEAD_LINK, stream())
        clock.advance((-1).hours)
        assertSame(RecentFailures.EMPTY, store.recent())
    }
}
