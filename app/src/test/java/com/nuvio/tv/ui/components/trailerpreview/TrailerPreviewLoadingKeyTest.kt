package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCancelCause.LIFECYCLE
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCancelCause.SCREENSAVER
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 123 C6: the loading key the corner spinner shows for, from the dwell's end to the first frame. */
class TrailerPreviewLoadingKeyTest {
    private val spring = TrailerPreviewCandidate("a", "tt1", "Spring", "2019", "movie", null)
    private val sintel = TrailerPreviewCandidate("b", "tt2", "Sintel", "2010", "movie", null)
    private val source = TrailerPreviewSource("https://v/spring", null)

    private fun TestScope.machine(found: Boolean = true, resolveMs: Long = RESOLVE_MS) =
        TrailerPreviewMachine(this, { testScheduler.currentTime }, { DELAY_MS }) {
            delay(resolveMs)
            source.takeIf { found }
        }

    @Test
    fun `set when the dwell ends, cleared at the first frame`() = runTest {
        val m = machine()
        m.focus(spring)
        advanceTimeBy(DELAY_MS - 1)
        assertNull(m.loadingKey.value) // browsing, and the hidden lookup and buffering (C2), write nothing
        advanceTimeBy(2)
        assertEquals("a", m.loadingKey.value) // the dwell ended with no frame yet
        advanceTimeBy(RESOLVE_MS)
        assertEquals("a", m.loadingKey.value) // buffering (LOADING)
        m.onFirstFrame()
        assertNull(m.loadingKey.value)
    }

    @Test
    fun `no trailer found after the dwell clears it`() = runTest {
        val m = machine(found = false, resolveMs = SLOW_RESOLVE_MS)
        m.focus(spring)
        advanceTimeBy(DELAY_MS + 1)
        assertEquals("a", m.loadingKey.value) // still looking
        advanceTimeBy(SLOW_RESOLVE_MS)
        assertNull(m.loadingKey.value)
    }

    @Test
    fun `no trailer found before the dwell ends never sets it`() = runTest {
        val m = machine(found = false)
        m.focus(spring)
        advanceTimeBy(DELAY_MS * 3)
        assertNull(m.loadingKey.value)
    }

    @Test
    fun `a frame before the dwell ends never sets it`() = runTest {
        val m = machine()
        m.focus(spring)
        advanceTimeBy(DELAY_MS - 1)
        m.onFirstFrame() // hidden
        advanceTimeBy(2)
        assertNull(m.loadingKey.value)
        assertEquals(TrailerPreviewStage.CARD, m.state.value?.stage)
    }

    @Test
    fun `leaving or moving on clears it`() = runTest {
        val m = machine()
        m.focus(spring)
        advanceTimeBy(DELAY_MS + 1)
        m.focus(sintel)
        assertNull(m.loadingKey.value)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        assertEquals("b", m.loadingKey.value)
        m.leaveCurrent()
        assertNull(m.loadingKey.value)
    }

    @Test
    fun `a cancel cause clears it, its resume sets it again after a fresh dwell`() = runTest {
        val m = machine()
        m.focus(spring)
        advanceTimeBy(DELAY_MS + 1)
        m.cancel(SCREENSAVER) // while buffering past the dwell
        assertNull(m.loadingKey.value)
        m.resume(SCREENSAVER)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + 1)
        assertEquals("a", m.loadingKey.value)
        m.cancel(LIFECYCLE) // while buffering
        assertNull(m.loadingKey.value)
        assertNull(m.state.value)
    }

    @Test
    fun `the first frame timeout and reset clear it`() = runTest {
        val m = machine()
        m.focus(spring)
        advanceTimeBy(DELAY_MS + RESOLVE_MS + TrailerPreviewMachine.FIRST_FRAME_TIMEOUT_MS + 1)
        assertNull(m.loadingKey.value)
        m.leaveCurrent()
        m.focus(sintel)
        advanceTimeBy(DELAY_MS + 1)
        assertEquals("b", m.loadingKey.value)
        m.reset()
        assertNull(m.loadingKey.value)
    }

    @Test
    fun `the spinner shows for the loading key, else an invisible preview's`() {
        val loading = TrailerPreviewState(spring, source, 0L, TrailerPreviewStage.LOADING)
        assertEquals("a", trailerPreviewLoadingKey("a", null))
        assertEquals("a", trailerPreviewLoadingKey(null, loading))
        assertNull(trailerPreviewLoadingKey(null, loading.copy(paused = true))) // buffering hidden during the dwell
        assertNull(trailerPreviewLoadingKey(null, loading.copy(stage = TrailerPreviewStage.CARD)))
        assertNull(trailerPreviewLoadingKey(null, null))
    }
}

private const val DELAY_MS = 3_000L
private const val RESOLVE_MS = 400L
// Longer than the settle's lead: the lookup ends after the dwell.
private const val SLOW_RESOLVE_MS = 2_500L
