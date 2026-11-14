package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 75 S4: the probe's sharpening of a failure and the mark a failover records. */
class PlaybackFailureTest {

    private val readLine = PlaybackSteps.line(StepMarks(0, linkAtMs = 100, connectAtMs = 300), PlaybackStep.READ, 700)
    private val unreadable = PlaybackFailure(FailureCause.UNREADABLE, "3003", steps = readLine)

    @Test
    fun `a web page keeps the step line, the cause and the finding change`() {
        val page = ServerResponseFinding(ServerResponseFinding.Kind.WEB_PAGE, 200, "text/html", 14_000, title = "Limit")
        val sharp = unreadable.sharpenedBy(page) { error("no redraw for the same step") }
        assertEquals(FailureCause.WEB_PAGE, sharp.cause)
        assertSame(readLine, sharp.steps)
        assertEquals(page, sharp.facts.probe)
        assertEquals("3003", sharp.message)
    }

    @Test
    fun `an HTTP error the probe met redraws the line at its own step`() {
        val gone = ServerResponseFinding(ServerResponseFinding.Kind.HTTP_ERROR, 410, null, null)
        val connectLine = PlaybackSteps.line(StepMarks(0, linkAtMs = 100), PlaybackStep.CONNECT, 700)
        var redrawnFor: FailureCause? = null
        val sharp = unreadable.sharpenedBy(gone) { cause -> redrawnFor = cause; connectLine }
        assertEquals(FailureCause.LINK_EXPIRED, sharp.cause)
        assertEquals(FailureCause.LINK_EXPIRED, redrawnFor)
        assertSame(connectLine, sharp.steps)
    }

    @Test
    fun `only a finding that named a non-media body skips the mime-clear retry`() {
        val page = ServerResponseFinding(ServerResponseFinding.Kind.WEB_PAGE, 200, "text/html", 14_000)
        assertEquals(true, unreadable.sharpenedBy(page) { readLine }.probeNamedNonMedia)
        assertEquals(false, unreadable.probeNamedNonMedia) // the probe learned nothing
        val gone = ServerResponseFinding(ServerResponseFinding.Kind.HTTP_ERROR, 410, null, null)
        assertEquals(true, unreadable.sharpenedBy(gone) { readLine }.probeNamedNonMedia) // a dead link
    }

    @Test
    fun `the mark carries the cause and the speeds`() {
        val slow = PlaybackFailure(FailureCause.TOO_SLOW, "watchdog", facts = FailureFacts(measuredMbps = 18.4, neededMbps = 69.6))
        assertEquals(FailureMark(FailureCause.TOO_SLOW, 18.4, 69.6), slow.mark)
    }

    @Test
    fun `only a source-caused failure is recorded when the box ends playback (D6 5)`() {
        val recorded = setOf(FailureCause.TOO_SLOW, FailureCause.NO_DATA, FailureCause.TORRENT, FailureCause.NO_URL)
        FailureCause.entries.forEach { cause ->
            assertEquals(cause.name, cause in recorded, PlaybackFailure(cause, "x").recordedWhenBoxEnds)
        }
    }

    @Test
    fun `the exit decision takes a source-caused box not yet recorded, never a failover's failure or a TV fault`() {
        val slow = PlaybackFailure(FailureCause.TOO_SLOW, "watchdog", facts = FailureFacts(measuredMbps = 3.0, neededMbps = 70.0))
        // No box (none shown, or a late first frame retracted the watchdog's).
        assertNull(boxEndingFailureToRecord(null, null))
        assertSame(slow, boxEndingFailureToRecord(slow, null))
        // Recorded already: by the failover that gave up at it, or by the first of two exits.
        assertNull(boxEndingFailureToRecord(slow, slow))
        assertSame(slow, boxEndingFailureToRecord(slow, PlaybackFailure(FailureCause.LINK_EXPIRED, "410")))
        // The TV's or the receiver's fault never ranks the source down.
        assertNull(boxEndingFailureToRecord(PlaybackFailure(FailureCause.DECODER_STUCK, "watchdog"), null))
        assertNull(boxEndingFailureToRecord(PlaybackFailure(FailureCause.AUDIO_FAILED, "audio"), null))
    }
}
