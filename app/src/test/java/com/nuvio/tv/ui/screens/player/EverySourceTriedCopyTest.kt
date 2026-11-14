package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.ExhaustedFailover
import com.nuvio.tv.core.player.FailoverNotice
import com.nuvio.tv.core.player.FailureFacts
import com.nuvio.tv.core.player.PlaybackFailure
import com.nuvio.tv.core.player.TriedSource
import com.nuvio.tv.core.player.asTriedSource
import com.nuvio.tv.core.player.exhaustedFailoverOf
import com.nuvio.tv.core.player.triedFor
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import com.nuvio.tv.ui.components.sources.SourceFailureMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 75 S5: the every-source-tried box (mockup stage 6, S5 Q1-Q3 recommended wording) and the failover marks. */
class EverySourceTriedCopyTest {

    private val s: FailureStrings = RealFailureStrings

    private fun tried(cause: FailureCause, addon: String? = "AIOStreams", file: String? = "a.mkv") =
        TriedSource(FailureMark(cause), addon, file)

    private val expired = PlaybackFailure(
        FailureCause.LINK_EXPIRED,
        "raw",
        facts = FailureFacts(streamName = "[RD+] AIOStreams 2160p")
    )

    @Test
    fun `a single failure keeps the plain eyebrow, body and no count line`() {
        val c = PlaybackFailureCopy.of(expired, s)
        assertEquals("Playback error", c.eyebrow)
        assertEquals(s.get(R.string.failure_body_link_expired, "Real-Debrid"), c.body)
        assertNull(c.also)
    }

    @Test
    fun `the drawn exhausted box`() {
        val earlier = listOf(
            tried(FailureCause.LINK_EXPIRED),
            tried(FailureCause.WEB_PAGE),
            tried(FailureCause.LINK_EXPIRED),
            tried(FailureCause.TOO_SLOW)
        )
        val c = PlaybackFailureCopy.of(expired, s, earlier)
        assertEquals("Playback error \u00b7 5 sources tried", c.eyebrow)
        assertEquals(
            "Real-Debrid no longer serves this file at that address. Pick a source from another service, " +
                "or refresh the list for fresh links.",
            c.body
        )
        assertEquals("The 4 sources before it failed too: 2 expired links, 1 web page, 1 too slow.", c.also)
    }

    @Test
    fun `one earlier source reads in the singular`() {
        val c = PlaybackFailureCopy.of(expired, s, listOf(tried(FailureCause.TEXT)))
        assertEquals("Playback error \u00b7 2 sources tried", c.eyebrow)
        assertEquals("The source before it failed too: 1 text reply.", c.also)
    }

    @Test
    fun `another cause keeps its own body when every source was tried`() {
        val removed = expired.copy(cause = FailureCause.REMOVED)
        val c = PlaybackFailureCopy.of(removed, s, listOf(tried(FailureCause.REMOVED)))
        assertEquals(s.get(R.string.failure_body_removed, "Real-Debrid"), c.body)
    }

    @Test
    fun `counts put the most frequent first and keep failing order on a tie`() {
        val earlier = listOf(
            tried(FailureCause.NO_DATA),
            tried(FailureCause.SERVER_DOWN),
            tried(FailureCause.SERVER_DOWN),
            tried(FailureCause.UNKNOWN),
            tried(FailureCause.WRONG_FILE)
        )
        assertEquals("2 servers down, 2 other failures, 1 no data", PlaybackFailureCopy.countsByReason(earlier, s))
    }

    @Test
    fun `details list each earlier source after the failure's own lines`() {
        val earlier = listOf(
            tried(FailureCause.LINK_EXPIRED, file = "Tears.of.Steel.mkv"),
            TriedSource(FailureMark(FailureCause.TOO_SLOW, 18.4, 69.2), addon = null, fileName = " ")
        )
        val own = PlaybackFailureCopy.details(expired, s)
        val c = PlaybackFailureCopy.of(expired, s, earlier)
        assertEquals(own, c.details.take(own.size))
        val lines = c.details.drop(own.size).map { line -> line.map { it.text to it.tone } }
        assertEquals(
            listOf(
                listOf(
                    "Link expired" to DetailSpan.Tone.BOLD,
                    "\u00b7 AIOStreams" to DetailSpan.Tone.MUTED,
                    "\u00b7 Tears.of.Steel.mkv" to DetailSpan.Tone.MUTED
                ),
                listOf("Too slow: 18 of 70 Mbit/s" to DetailSpan.Tone.BOLD)
            ),
            lines
        )
    }

    @Test
    fun `a failure becomes the source it left`() {
        val failure = PlaybackFailure(
            FailureCause.TOO_SLOW,
            "raw",
            facts = FailureFacts(addon = "Torrentio", fileName = "b.mkv", measuredMbps = 9.0, neededMbps = 30.0)
        )
        assertEquals(TriedSource(FailureMark(FailureCause.TOO_SLOW, 9.0, 30.0), "Torrentio", "b.mkv"), failure.asTriedSource)
    }

    @Test
    fun `the failover line and toast name the mark`() {
        val mark = FailureMark(FailureCause.LINK_EXPIRED)
        val notice = FailoverNotice(mark, attempt = 2, max = 3, previousSteps = null)
        assertEquals("Link expired, trying next source (2/3)", PlaybackFailureCopy.failoverLine(notice, s))
        assertEquals("Link expired, playing the next source", PlaybackFailureCopy.failoverToast(mark, s))
        assertEquals(
            "Too slow: 18 of 70 Mbit/s, playing the next source",
            PlaybackFailureCopy.failoverToast(FailureMark(FailureCause.TOO_SLOW, 18.4, 69.2), s)
        )
    }

    @Test
    fun `the box counts the tried sources only for the failure the failover gave up at`() {
        val earlier = listOf(tried(FailureCause.WEB_PAGE))
        val exhausted = ExhaustedFailover(expired, earlier)
        assertEquals(earlier, exhausted.triedFor(expired))
        // A later failure of the same cause with any other fact is not the one the failover gave up at.
        assertEquals(emptyList<TriedSource>(), exhausted.triedFor(expired.copy(facts = expired.facts.copy(fileSizeBytes = 1L))))
        // The startup watchdog's box, or a later engine failure: a plain box.
        assertEquals(emptyList<TriedSource>(), exhausted.triedFor(expired.copy(fromStartupWatchdog = true)))
        assertEquals(emptyList<TriedSource>(), exhausted.triedFor(PlaybackFailure(FailureCause.ENGINE, "boom")))
        assertEquals(emptyList<TriedSource>(), (null as ExhaustedFailover?).triedFor(expired))
    }

    @Test
    fun `a give-up with no source left behind is a plain box`() {
        assertNull(exhaustedFailoverOf(expired, emptyList()))
        val earlier = listOf(tried(FailureCause.NO_DATA))
        assertEquals(ExhaustedFailover(expired, earlier), exhaustedFailoverOf(expired, earlier))
    }

    @Test
    fun `every cause has a mark and both count forms`() {
        FailureCause.entries.forEach { cause ->
            assertTrue(cause.name, SourceFailureMarks.reason(FailureMark(cause), s).isNotBlank())
            val one = PlaybackFailureCopy.countsByReason(listOf(tried(cause)), s)
            val two = PlaybackFailureCopy.countsByReason(listOf(tried(cause), tried(cause)), s)
            assertTrue(cause.name, one.startsWith("1 ") && two.startsWith("2 "))
            // The singular and plural items differ, except the phrases with no plural ("too slow", "no data").
            if (cause != FailureCause.TOO_SLOW && cause != FailureCause.NO_DATA) {
                assertTrue(cause.name, one.removePrefix("1 ") != two.removePrefix("2 "))
            }
        }
        assertEquals("Failed", SourceFailureMarks.reason(null, s))
        assertEquals("Too slow", SourceFailureMarks.reason(FailureMark(FailureCause.TOO_SLOW, 18.0, null), s))
        assertEquals("Too slow: 9.9 of 30 Mbit/s", SourceFailureMarks.reason(FailureMark(FailureCause.TOO_SLOW, 9.95, 29.4), s))
    }
}
