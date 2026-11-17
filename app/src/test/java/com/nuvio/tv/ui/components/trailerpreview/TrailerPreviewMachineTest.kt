package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCancelCause.LIFECYCLE
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCancelCause.SCREENSAVER
import com.nuvio.tv.domain.model.TrailerPreviewDelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 96 PD5/PD6: stages, keys, done causes and resume memory under virtual time. */
class TrailerPreviewMachineTest {
    private val spring = candidate("a", "tt1")
    private val sintel = candidate("b", "tt2")
    private val springSource = TrailerPreviewSource("https://v/spring", "https://a/spring")

    private fun candidate(key: String, id: String) =
        TrailerPreviewCandidate(key, id, "Title $id", "2019", "movie", null)

    private class Harness(scope: TestScope, var source: TrailerPreviewSource?, var delayMs: Long) {
        val requests = mutableListOf<String>()
        val machine = TrailerPreviewMachine(scope, { scope.testScheduler.currentTime }, { delayMs }) {
            requests += it.itemId
            source
        }
        private val tokens = HashMap<String, Long>()

        /** Registers [c] as a card would, keeping its latest token. */
        fun focus(c: TrailerPreviewCandidate): Long = machine.focus(c).also { tokens[c.key] = it }

        /** Unregisters [c]'s latest registration. */
        fun leave(c: TrailerPreviewCandidate) = machine.leave(tokens.getValue(c.key))
    }

    private fun TestScope.harness(source: TrailerPreviewSource? = springSource, delayMs: Long = DEFAULT_DELAY_MS) =
        Harness(this, source, delayMs)

    /** Focus [c], dwell, resolve, first frame: stage 1. */
    private fun TestScope.playCard(h: Harness, c: TrailerPreviewCandidate = spring) {
        h.focus(c)
        advanceTimeBy(h.delayMs + 1)
        h.machine.onFirstFrame()
    }

    @Test
    fun `nothing is resolved before the settle, then it loads paused until the dwell ends`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(SETTLE_MS - 1)
        assertEquals(emptyList<String>(), h.requests)
        assertNull(h.machine.state.value)
        advanceTimeBy(2)
        assertEquals(listOf("tt1"), h.requests)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals(true, h.machine.state.value?.paused)
        advanceTimeBy(DEFAULT_DELAY_MS - SETTLE_MS - 2)
        assertEquals(true, h.machine.state.value?.paused)
        advanceTimeBy(2)
        assertEquals(false, h.machine.state.value?.paused) // the dwell ended: it plays, still hidden
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
    }

    @Test
    fun `the settle is the lead before the dwell's end, never before a third of it`() {
        assertEquals(333L, trailerPreviewSettleMs(1_000))
        assertEquals(1_000L, trailerPreviewSettleMs(3_000))
        assertEquals(8_000L, trailerPreviewSettleMs(10_000))
        assertEquals(3_000L, trailerPreviewSettleMs(3_000, leadMs = 0)) // the rollback: the pre-C2 timing
    }

    @Test
    fun `a frame before the dwell ends stays hidden, stage 1 at the dwell's end`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(SETTLE_MS + 500)
        h.machine.onFirstFrame()
        h.machine.onFirstFrame() // a repeat changes nothing
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals(true, h.machine.state.value?.paused)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.OK)) // hidden: keys pass
        advanceTimeBy(DEFAULT_DELAY_MS - SETTLE_MS - 500 - 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        advanceTimeBy(2)
        assertEquals(TrailerPreviewStage.CARD, h.machine.state.value?.stage)
        assertEquals(false, h.machine.state.value?.paused)
        h.machine.onFirstFrame() // late: no effect
        advanceTimeBy(DEFAULT_DELAY_MS - 2)
        assertEquals(TrailerPreviewStage.CARD, h.machine.state.value?.stage)
        advanceTimeBy(2)
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage)
    }

    @Test
    fun `a lookup that ends after the dwell loads unpaused`() = runTest {
        val gate = CompletableDeferred<TrailerPreviewSource?>()
        val machine = TrailerPreviewMachine(this, { testScheduler.currentTime }, { DEFAULT_DELAY_MS }) { gate.await() }
        machine.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 500)
        gate.complete(springSource)
        runCurrent()
        assertEquals(TrailerPreviewStage.LOADING, machine.state.value?.stage)
        assertEquals(false, machine.state.value?.paused)
        machine.onFirstFrame()
        assertEquals(TrailerPreviewStage.CARD, machine.state.value?.stage)
    }

    @Test
    fun `leaving or a cancel while hidden stops it`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(SETTLE_MS + 1)
        h.machine.onFirstFrame()
        h.leave(spring)
        assertNull(h.machine.state.value)
        advanceTimeBy(DEFAULT_DELAY_MS * 2)
        assertNull(h.machine.state.value) // the dwell's end does not bring it back
        assertNull(h.machine.loadingKey.value) // nor the spinner
        h.focus(spring)
        advanceTimeBy(SETTLE_MS + 1)
        h.machine.reset()
        advanceTimeBy(DEFAULT_DELAY_MS * 2)
        assertNull(h.machine.state.value)
        assertNull(h.machine.loadingKey.value)
        h.focus(spring)
        advanceTimeBy(SETTLE_MS + 1)
        h.machine.cancel(LIFECYCLE)
        assertNull(h.machine.state.value)
        h.machine.resume(LIFECYCLE) // a fresh dwell
        advanceTimeBy(SETTLE_MS + 1)
        assertEquals(true, h.machine.state.value?.paused)
    }

    @Test
    fun `the next card starts hidden again after a shown one`() = runTest {
        val h = harness()
        playCard(h) // its dwell ended
        h.focus(sintel)
        advanceTimeBy(SETTLE_MS + 1)
        assertEquals(true, h.machine.state.value?.paused)
        h.machine.onFirstFrame()
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
    }

    @Test
    fun `the next card waits for its own frame after a hidden one`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(SETTLE_MS + 1)
        h.machine.onFirstFrame() // hidden, then left
        h.focus(sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals("b", h.machine.loadingKey.value)
    }

    @Test
    fun `the first frame timeout counts from the lookup's end when it ends after the dwell`() = runTest {
        val gate = CompletableDeferred<TrailerPreviewSource?>()
        val machine = TrailerPreviewMachine(this, { testScheduler.currentTime }, { DEFAULT_DELAY_MS }) { gate.await() }
        machine.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 2_000)
        gate.complete(springSource)
        runCurrent()
        advanceTimeBy(TrailerPreviewMachine.FIRST_FRAME_TIMEOUT_MS - 1)
        assertEquals(TrailerPreviewStage.LOADING, machine.state.value?.stage)
        advanceTimeBy(2)
        assertNull(machine.state.value)
    }

    @Test
    fun `the first frame timeout counts from the dwell's end when the lookup ends first`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + TrailerPreviewMachine.FIRST_FRAME_TIMEOUT_MS - 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        advanceTimeBy(2)
        assertNull(h.machine.state.value)
    }

    @Test
    fun `moving on during the dwell resolves only the card that stays`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(1_000)
        h.focus(sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(listOf("tt2"), h.requests)
    }

    @Test
    fun `first frame is stage 1, 3 s later stage 2`() = runTest {
        val h = harness()
        playCard(h)
        assertEquals(TrailerPreviewStage.CARD, h.machine.state.value?.stage)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage)
    }

    @Test
    fun `no trailer found shows nothing and does not ask again while focused`() = runTest {
        val h = harness(source = null)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        h.machine.cancel(LIFECYCLE)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value)
        assertEquals(listOf("tt1"), h.requests)
    }

    @Test
    fun `leaving during a slow resolve cancels it`() = runTest {
        val gate = CompletableDeferred<TrailerPreviewSource?>()
        val machine = TrailerPreviewMachine(this, { testScheduler.currentTime }, { DEFAULT_DELAY_MS }) { gate.await() }
        val token = machine.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        machine.leave(token)
        gate.complete(springSource)
        runCurrent()
        assertNull(machine.state.value)
    }

    @Test
    fun `no first frame within 10 s of the resolve is done`() = runTest {
        val gate = CompletableDeferred<TrailerPreviewSource?>()
        val machine = TrailerPreviewMachine(this, { testScheduler.currentTime }, { 3_000L }) { gate.await() }
        machine.focus(spring)
        advanceTimeBy(3_000 + 5_000) // a slow resolve does not eat into the timeout
        gate.complete(springSource)
        runCurrent()
        advanceTimeBy(9_999)
        assertEquals(TrailerPreviewStage.LOADING, machine.state.value?.stage)
        advanceTimeBy(2)
        assertNull(machine.state.value)
        machine.cancel(LIFECYCLE)
        machine.resume(LIFECYCLE) // done: nothing re-arms it
        advanceTimeBy(10_000)
        assertNull(machine.state.value)
    }

    @Test
    fun `keys before the trailer shows pass untouched`() = runTest {
        val h = harness()
        h.focus(spring)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.BACK))
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.DPAD))
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals(listOf("tt1"), h.requests) // the Back during the dwell left it running
    }

    @Test
    fun `stage 1 Back is consumed and the card is done until focus leaves`() = runTest {
        val h = harness()
        playCard(h)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_CONSUME, h.machine.onKeyDown(TrailerPreviewKey.BACK))
        assertNull(h.machine.state.value)
        h.focus(spring) // re-registration of the same card changes nothing
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals(0L, h.machine.state.value?.startPositionMs) // Back clears the resume record (D9)
    }

    @Test
    fun `stage 1 D-pad and OK close and pass through`() = runTest {
        val h = harness()
        playCard(h)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_PASS, h.machine.onKeyDown(TrailerPreviewKey.OK))
        assertNull(h.machine.state.value)
        playCard(h, sintel)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_PASS, h.machine.onKeyDown(TrailerPreviewKey.DPAD))
    }

    @Test
    fun `stage 2 keys follow D4`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.CONSUME_OPEN_ON_UP, h.machine.onKeyDown(TrailerPreviewKey.OK))
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage) // still showing until the up
        assertEquals(spring, h.machine.openDetailsOnKeyUp())
        assertNull(h.machine.state.value)
        assertNull(h.machine.openDetailsOnKeyUp())

        h.leave(spring)
        playCard(h, sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_PASS, h.machine.onKeyDown(TrailerPreviewKey.OTHER))

        playCard(h, spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_PASS, h.machine.onKeyDown(TrailerPreviewKey.DPAD))

        playCard(h, sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.CLOSE_AND_CONSUME, h.machine.onKeyDown(TrailerPreviewKey.BACK))
        assertNull(h.machine.state.value)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value) // done until focus leaves
    }

    @Test
    fun `stage 2 Back forgets the record`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onProgress(42_000)
        h.machine.onKeyDown(TrailerPreviewKey.BACK)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `an OK held while the trailer ends or the volume changes still opens Details on its up`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onKeyDown(TrailerPreviewKey.OK)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.VOLUME))
        h.machine.onEndedOrError()
        assertEquals(spring, h.machine.openDetailsOnKeyUp())
    }

    @Test
    fun `another key or a cancel during a held OK drops the open`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onKeyDown(TrailerPreviewKey.OK)
        h.machine.onKeyDown(TrailerPreviewKey.BACK)
        assertNull(h.machine.openDetailsOnKeyUp())

        h.leave(spring)
        playCard(h, sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onKeyDown(TrailerPreviewKey.OK)
        h.machine.cancel(LIFECYCLE)
        assertNull(h.machine.openDetailsOnKeyUp())
    }

    @Test
    fun `a passed key that did not move focus leaves the card done`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.machine.onKeyDown(TrailerPreviewKey.DPAD) // grid row end: focus stays
        h.machine.cancel(LIFECYCLE)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value)
        assertEquals(listOf("tt1"), h.requests)
        h.leave(spring) // the record survives the passed key (PD5)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `a passed key that moved focus arms the next card normally`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.machine.onKeyDown(TrailerPreviewKey.DPAD)
        h.focus(sintel) // Right moved focus
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(sintel, h.machine.state.value?.candidate)
        h.machine.onKeyDown(TrailerPreviewKey.DPAD) // LOADING: passes, nothing recorded
        h.focus(spring) // Left, back within 10 s (D1)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `back on the same title within 10 s waits 3 s then resumes 5 s back`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.focus(sintel) // leaves spring at 42 s
        advanceTimeBy(4_000)
        h.focus(spring)
        advanceTimeBy(SETTLE_MS - 1)
        assertNull(h.machine.state.value)
        advanceTimeBy(2)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `back after 10 s starts over`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        advanceTimeBy(10_001)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `back at exactly 10 s still resumes`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        advanceTimeBy(10_000)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `only the last title is kept`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        playCard(h, sintel) // leaves spring; sintel plays
        h.machine.onProgress(9_000)
        h.focus(spring) // leaves sintel: the record is now sintel's
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `a different resolved video does not resume`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        h.source = TrailerPreviewSource("https://v/other", null)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `leaving before stage 1 keeps the record`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(1_000)
        h.leave(spring) // during the dwell: record untouched
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `a progress report after the stop does not move the record`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        h.machine.onProgress(0) // TrailerPlayer reports (0, 0) once playback stops
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `the end or an error is done and leaves nothing to resume`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.machine.onEndedOrError()
        assertNull(h.machine.state.value)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `a cancel during the dwell waits for resume, then a fresh dwell`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(SETTLE_MS - 1)
        h.machine.cancel(LIFECYCLE) // lifecycle pause
        advanceTimeBy(60_000) // nothing runs while paused
        assertEquals(emptyList<String>(), h.requests)
        assertNull(h.machine.state.value)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(SETTLE_MS - 1)
        assertNull(h.machine.state.value)
        advanceTimeBy(2)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
    }

    @Test
    fun `a cancel while shown is done and resume does not replay it`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.cancel(LIFECYCLE)
        assertNull(h.machine.state.value)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertNull(h.machine.state.value)
        assertEquals(listOf("tt1"), h.requests)
    }

    @Test
    fun `a cancel during the resolve drops it and resume asks again`() = runTest {
        var gate = CompletableDeferred<TrailerPreviewSource?>()
        var requests = 0
        val machine = TrailerPreviewMachine(this, { testScheduler.currentTime }, { DEFAULT_DELAY_MS }) {
            requests++
            gate.await()
        }
        machine.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        machine.cancel(LIFECYCLE)
        gate.complete(springSource)
        runCurrent()
        assertNull(machine.state.value)
        gate = CompletableDeferred()
        machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        gate.complete(springSource)
        runCurrent()
        assertEquals(2, requests)
        assertEquals(TrailerPreviewStage.LOADING, machine.state.value?.stage)
    }

    @Test
    fun `a cancel during the invisible LOADING keeps the record and resume retries`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        h.machine.cancel(SCREENSAVER) // screensaver before the first frame
        assertNull(h.machine.state.value)
        h.machine.resume(SCREENSAVER)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `resume without a cancel does nothing`() = runTest {
        val h = harness()
        h.focus(spring)
        advanceTimeBy(1_000)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(listOf("tt1"), h.requests)
    }

    @Test
    fun `another title failing keeps the last title's record`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onProgress(42_000)
        h.focus(sintel) // record: spring at 42 s
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onEndedOrError() // sintel errors in LOADING
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `Details from stage 2 keeps the record for a quick return`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onProgress(20_000)
        h.machine.onKeyDown(TrailerPreviewKey.OK)
        h.machine.openDetailsOnKeyUp()
        h.leave(spring) // the card leaves composition with the screen
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(15_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `the delay setting times the dwell and the move to stage 2`() = runTest {
        val h = harness(delayMs = 1_000)
        h.focus(spring)
        advanceTimeBy(332) // the settle of a 1 s dwell is 333 ms
        assertEquals(emptyList<String>(), h.requests)
        advanceTimeBy(2)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        h.delayMs = 5_000 // a change before the first frame applies to stage 2's timer, not this dwell
        advanceTimeBy(1_000 - 334)
        assertEquals(true, h.machine.state.value?.paused)
        advanceTimeBy(1)
        assertEquals(false, h.machine.state.value?.paused)
        h.machine.onFirstFrame()
        advanceTimeBy(4_999)
        assertEquals(TrailerPreviewStage.CARD, h.machine.state.value?.stage)
        advanceTimeBy(2)
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage)
    }

    @Test
    fun `the resume wait uses the delay setting`() = runTest {
        val h = harness(delayMs = 2_000)
        playCard(h)
        h.machine.onProgress(42_000)
        h.leave(spring)
        h.focus(spring)
        advanceTimeBy(665) // the settle of a 2 s dwell is 666 ms
        assertNull(h.machine.state.value)
        advanceTimeBy(2)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `volume keys pass without closing in every stage`() = runTest {
        val h = harness()
        h.focus(spring)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.VOLUME))
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onFirstFrame()
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.VOLUME))
        assertEquals(TrailerPreviewStage.CARD, h.machine.state.value?.stage)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewKeyDecision.PASS, h.machine.onKeyDown(TrailerPreviewKey.VOLUME))
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage)
    }

    @Test
    fun `a stale token does not leave the card that registered again`() = runTest {
        val h = harness()
        val outgoing = h.machine.focus(spring) // the outgoing screen's card
        advanceTimeBy(1_000)
        val incoming = h.machine.focus(spring) // the incoming screen's card: same key, new token, no re-arm
        assertNotEquals(outgoing, incoming)
        h.machine.leave(outgoing) // the outgoing card disposes: no-op
        advanceTimeBy(DEFAULT_DELAY_MS - 1_000 + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage) // the first dwell ran on
        advanceTimeBy(DEFAULT_DELAY_MS * 2)
        assertEquals(listOf("tt1"), h.requests)
        h.machine.leave(incoming)
        assertNull(h.machine.state.value)
    }

    @Test
    fun `a token from before another card's focus is stale`() = runTest {
        val h = harness()
        val first = h.machine.focus(spring)
        h.machine.focus(sintel)
        h.machine.leave(first) // spring's late blur after sintel took focus
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(sintel, h.machine.state.value?.candidate)
    }

    @Test
    fun `reset forgets the card and the resume record`() = runTest {
        val h = harness()
        val token = h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onFirstFrame()
        h.machine.onProgress(42_000)
        h.machine.reset()
        assertNull(h.machine.state.value)
        h.machine.leave(token) // the disposed card's unregister: no-op
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(listOf("tt1"), h.requests)
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(0L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `reset after Back lets the card play again`() = runTest {
        val h = harness()
        playCard(h)
        h.machine.onKeyDown(TrailerPreviewKey.BACK)
        h.machine.reset()
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
    }

    @Test
    fun `cancel and resume pair once`() = runTest {
        val h = harness()
        h.focus(spring)
        h.machine.cancel(LIFECYCLE)
        h.machine.cancel(LIFECYCLE) // twice: still one fresh dwell
        h.machine.resume(LIFECYCLE)
        h.machine.resume(LIFECYCLE) // twice: no second dwell
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(listOf("tt1"), h.requests)
    }

    @Test
    fun `a card left while paused is not armed by resume`() = runTest {
        val h = harness()
        h.focus(spring)
        h.machine.cancel(LIFECYCLE)
        h.leave(spring)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(emptyList<String>(), h.requests)
    }

    @Test
    fun `a card focused while paused waits for resume`() = runTest {
        val h = harness()
        h.focus(spring)
        h.machine.cancel(LIFECYCLE)
        h.focus(sintel) // focus restored without a key while paused
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), h.requests)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(listOf("tt2"), h.requests) // one dwell, the new card's
        assertEquals(sintel, h.machine.state.value?.candidate)
    }

    @Test
    fun `the first card's timeout does not hit the next card`() = runTest {
        val h = harness()
        playCard(h) // spring's arm timeout would fire 10 s after its resolve
        h.focus(sintel)
        advanceTimeBy(DEFAULT_DELAY_MS + 1) // sintel LOADING
        advanceTimeBy(7_000) // past spring's old timeout instant, before sintel's own
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
        assertEquals(sintel, h.machine.state.value?.candidate)
    }

    @Test
    fun `a repeated first frame does not drop the panel back`() = runTest {
        val h = harness()
        playCard(h)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onFirstFrame() // ExoPlayer reports a first frame again after a seek
        assertEquals(TrailerPreviewStage.PANEL, h.machine.state.value?.stage)
    }

    @Test
    fun `a route change leaves the card at once and keeps the record`() = runTest {
        val h = harness()
        val token = h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        h.machine.onFirstFrame()
        h.machine.onProgress(42_000)
        h.machine.leaveCurrent()
        assertNull(h.machine.state.value)
        h.machine.leave(token) // the outgoing card's late dispose: no-op
        h.focus(spring) // back on the route within 10 s
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(37_000L, h.machine.state.value?.startPositionMs)
    }

    @Test
    fun `nothing arms until every cancel cause has ended`() = runTest {
        val h = harness()
        h.focus(spring)
        h.machine.cancel(SCREENSAVER)
        h.machine.cancel(LIFECYCLE) // Home key under the screensaver
        h.machine.resume(LIFECYCLE) // back to the app, screensaver still up
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(emptyList<String>(), h.requests)
        h.machine.resume(SCREENSAVER)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(TrailerPreviewStage.LOADING, h.machine.state.value?.stage)
    }

    @Test
    fun `reset keeps a live cancel cause`() = runTest {
        val h = harness()
        h.machine.cancel(SCREENSAVER)
        h.machine.reset() // the host is recreated while the screensaver is still up
        h.focus(spring)
        advanceTimeBy(DEFAULT_DELAY_MS * 3)
        assertEquals(emptyList<String>(), h.requests)
        h.machine.resume(SCREENSAVER)
        advanceTimeBy(DEFAULT_DELAY_MS + 1)
        assertEquals(listOf("tt1"), h.requests)
    }
}

private const val DEFAULT_DELAY_MS = TrailerPreviewDelay.DEFAULT_S * 1_000L
private val SETTLE_MS = trailerPreviewSettleMs(DEFAULT_DELAY_MS)
