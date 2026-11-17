package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCancelCause.LIFECYCLE
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Aborted
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Armed
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.DwellEnded
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.FirstFrame
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Panel
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Playing
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Prepared
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.ResolveStarted
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Resolved
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewEvent.Shown
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 123 C1: the timing trace, ms since the arm, under virtual time. */
class TrailerPreviewTraceTest {
    private val spring = TrailerPreviewCandidate("a", "tt1", "Spring", "2019", "movie", null)
    private val sintel = TrailerPreviewCandidate("b", "tt2", "Sintel", "2010", "movie", null)

    private class Harness(scope: TestScope, val source: TrailerPreviewSource?) {
        val events = mutableListOf<TrailerPreviewEvent>()
        val machine = TrailerPreviewMachine(
            scope,
            { scope.testScheduler.currentTime },
            { DELAY_MS },
            trace = { events += it }
        ) {
            delay(RESOLVE_MS)
            source
        }
    }

    private fun TestScope.harness(source: TrailerPreviewSource? = TrailerPreviewSource("https://v/spring", null)) =
        Harness(this, source)

    @Test
    fun `a full preview traces every step in order`() = runTest {
        val h = harness()
        h.machine.onPlaying() // no preview: nothing
        h.machine.focus(spring)
        advanceTimeBy(DELAY_MS + 401) // the lookup ran during the dwell; no frame yet
        h.machine.onShown() // still loading: not shown yet
        h.machine.onFirstFrame()
        h.machine.onShown()
        h.machine.onShown() // later draws do not count
        advanceTimeBy(600)
        h.machine.onPlaying()
        advanceTimeBy(DELAY_MS + 1)
        val loading = SETTLE_MS + RESOLVE_MS
        assertEquals(
            listOf(
                Armed("tt1", rearm = false),
                ResolveStarted(SETTLE_MS),
                Resolved(loading, found = true, durationMs = RESOLVE_MS),
                Prepared(loading),
                DwellEnded(DELAY_MS),
                FirstFrame(DELAY_MS + 401),
                Shown(DELAY_MS + 401),
                Playing(DELAY_MS + 1_001),
                Panel(DELAY_MS + 401 + DELAY_MS)
            ),
            h.events
        )
    }

    @Test
    fun `a frame before the dwell's end traces hidden, then shown at the dwell's end`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(SETTLE_MS + RESOLVE_MS + 100)
        h.machine.onFirstFrame()
        h.machine.onShown() // hidden: not shown
        advanceTimeBy(DELAY_MS - SETTLE_MS - RESOLVE_MS - 100 + 1)
        h.machine.onShown()
        val loading = SETTLE_MS + RESOLVE_MS
        assertEquals(
            listOf(
                Armed("tt1", rearm = false),
                ResolveStarted(SETTLE_MS),
                Resolved(loading, found = true, durationMs = RESOLVE_MS),
                Prepared(loading),
                FirstFrame(loading + 100, hidden = true),
                DwellEnded(DELAY_MS),
                Shown(DELAY_MS + 1)
            ),
            h.events
        )
    }

    @Test
    fun `leaving after a hidden frame is a hidden abort while loading`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(SETTLE_MS + RESOLVE_MS + 100)
        h.machine.onFirstFrame()
        h.machine.leaveCurrent()
        assertEquals(Aborted(SETTLE_MS + RESOLVE_MS + 100, TrailerPreviewAbortPhase.LOADING, hidden = true), h.events.last())
    }

    @Test
    fun `leaving before the settle traces only the arm`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(SETTLE_MS - 1)
        h.machine.focus(sintel)
        assertEquals(listOf(Armed("tt1", false), Armed("tt2", false)), h.events)
    }

    @Test
    fun `leaving during the lookup is an abort while resolving`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(SETTLE_MS + 100)
        h.machine.leaveCurrent()
        assertEquals(Aborted(SETTLE_MS + 100, TrailerPreviewAbortPhase.RESOLVING, hidden = true), h.events.last())
        advanceTimeBy(RESOLVE_MS)
        assertEquals(3, h.events.size) // the cancelled lookup traces no end
    }

    @Test
    fun `the first frame timeout is an abort while loading`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(DELAY_MS + TrailerPreviewMachine.FIRST_FRAME_TIMEOUT_MS + 1)
        assertEquals(
            Aborted(DELAY_MS + TrailerPreviewMachine.FIRST_FRAME_TIMEOUT_MS, TrailerPreviewAbortPhase.LOADING),
            h.events.last()
        )
    }

    @Test
    fun `a cancel while loading is an abort, and its resume a re-arm`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        h.machine.cancel(LIFECYCLE)
        assertEquals(Aborted(DELAY_MS + RESOLVE_MS + 1, TrailerPreviewAbortPhase.LOADING), h.events.last())
        h.machine.resume(LIFECYCLE)
        assertEquals(Armed("tt1", rearm = true), h.events.last())
    }

    @Test
    fun `a playback error while loading is an abort`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        h.machine.onEndedOrError()
        assertEquals(Aborted(DELAY_MS + RESOLVE_MS + 1, TrailerPreviewAbortPhase.LOADING), h.events.last())
    }

    @Test
    fun `no trailer ends at the lookup, with no abort on leave`() = runTest {
        val h = harness(source = null)
        h.machine.focus(spring)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        h.machine.leaveCurrent()
        assertEquals(Resolved(SETTLE_MS + RESOLVE_MS, found = false, durationMs = RESOLVE_MS), h.events.last())
    }

    @Test
    fun `closing a shown card traces no abort and no late shown`() = runTest {
        val h = harness()
        h.machine.focus(sintel)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        h.machine.onFirstFrame()
        h.machine.onKeyDown(TrailerPreviewKey.BACK)
        h.machine.onShown() // a late draw after the close
        assertEquals(FirstFrame(DELAY_MS + RESOLVE_MS + 1), h.events.last())
    }

    @Test
    fun `a resume re-arm is traced as such, from its own start`() = runTest {
        val h = harness()
        h.machine.focus(spring)
        advanceTimeBy(1_000)
        h.machine.cancel(LIFECYCLE)
        advanceTimeBy(5_000)
        h.machine.resume(LIFECYCLE)
        advanceTimeBy(SETTLE_MS + 1)
        assertEquals(listOf(Armed("tt1", false), Armed("tt1", true), ResolveStarted(SETTLE_MS)), h.events)
    }

    @Test
    fun `log lines name the step, the source and the re-arm`() {
        assertEquals("arm (focus) tt1 source=IMDB", trailerPreviewTimingLine(Armed("tt1", false), TrailerSource.IMDB))
        assertEquals("re-arm (resume) tt1 source=unknown", trailerPreviewTimingLine(Armed("tt1", true), null))
        assertEquals("resolve end none in 120 ms", trailerPreviewTimingLine(Resolved(3_120, false, 120), null))
        assertEquals("aborted while loading", trailerPreviewTimingLine(Aborted(9, TrailerPreviewAbortPhase.LOADING), null))
        assertEquals(
            "aborted while resolving (hidden, before dwell end)",
            trailerPreviewTimingLine(Aborted(9, TrailerPreviewAbortPhase.RESOLVING, hidden = true), null)
        )
        assertEquals("first frame (hidden)", trailerPreviewTimingLine(FirstFrame(9, hidden = true), null))
        assertEquals("dwell end", trailerPreviewTimingLine(DwellEnded(3_000), null))
    }
}

private const val DELAY_MS = 3_000L
private const val RESOLVE_MS = 250L
private val SETTLE_MS = trailerPreviewSettleMs(DELAY_MS)
