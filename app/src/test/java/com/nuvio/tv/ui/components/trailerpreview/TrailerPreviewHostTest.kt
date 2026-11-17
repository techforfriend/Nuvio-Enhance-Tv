package com.nuvio.tv.ui.components.trailerpreview

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 96 P4: key mapping (PD6), overlay geometry (PD3/PD4, D10) and stage 2's meta row. */
class TrailerPreviewHostTest {
    private val window = Size(1920f, 1080f)
    private val grid = Size(704f, 396f)
    private val safe = Offset(96f, 48f)

    @Test
    fun `remote keys map to the preview's keys`() {
        assertEquals(TrailerPreviewKey.BACK, trailerPreviewKey(KeyEvent.KEYCODE_BACK))
        assertEquals(TrailerPreviewKey.OK, trailerPreviewKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TrailerPreviewKey.OK, trailerPreviewKey(KeyEvent.KEYCODE_ENTER))
        assertEquals(TrailerPreviewKey.DPAD, trailerPreviewKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TrailerPreviewKey.DPAD, trailerPreviewKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(TrailerPreviewKey.VOLUME, trailerPreviewKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(TrailerPreviewKey.VOLUME, trailerPreviewKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertEquals(TrailerPreviewKey.OTHER, trailerPreviewKey(KeyEvent.KEYCODE_MENU))
        assertEquals(TrailerPreviewKey.OTHER, trailerPreviewKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(TrailerPreviewKey.BACK, trailerPreviewKey(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(TrailerPreviewKey.OK, trailerPreviewKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(TrailerPreviewKey.DPAD, trailerPreviewKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TrailerPreviewKey.DPAD, trailerPreviewKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(TrailerPreviewKey.VOLUME, trailerPreviewKey(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `the row form draws over the widened card itself`() {
        val anchor = Rect(300f, 600f, 940f, 960f)
        assertEquals(anchor, trailerPreviewCardRect(anchor, grid = false, window, grid, safe))
    }

    @Test
    fun `the grid form lifts a larger card centred on the cell`() {
        val cell = Rect(800f, 400f, 1214f, 632f) // centre (1007, 516)
        assertEquals(Rect(655f, 318f, 1359f, 714f), trailerPreviewCardRect(cell, grid = true, window, grid, safe))
    }

    @Test
    fun `the lifted card stays inside the safe area`() {
        val topLeft = trailerPreviewCardRect(Rect(96f, 48f, 510f, 280f), grid = true, window, grid, safe)
        assertEquals(Offset(96f, 48f), topLeft.topLeft)
        val bottomRight = trailerPreviewCardRect(Rect(1410f, 800f, 1824f, 1032f), grid = true, window, grid, safe)
        assertEquals(Offset(1824f, 1032f), bottomRight.bottomRight)
    }

    @Test
    fun `the panel is centred with room for the hints under it`() {
        val panel = trailerPreviewPanelRect(window, Size(1600f, 900f), hintSpace = 44f)
        assertEquals(Rect(160f, 68f, 1760f, 968f), panel)
    }

    @Test
    fun `the meta row drops blanks and keeps the order`() {
        val full = TrailerPreviewCandidate("k", "tt1", "Spring", "2019", "movie", null, "Fantasy", "8 min", 7.6f)
        assertEquals(listOf("2019", "Fantasy", "8 min"), trailerPreviewMeta(full))
        val sparse = full.copy(releaseInfo = " ", genre = null)
        assertEquals(listOf("8 min"), trailerPreviewMeta(sparse))
    }

    @Test
    fun `a route change is a leave, the first route at startup is not`() {
        assertFalse(isTrailerPreviewRouteLeave(null, "home", "home"))
        assertFalse(isTrailerPreviewRouteLeave("home", "home", "home"))
        assertTrue(isTrailerPreviewRouteLeave("home", "detail/tt1", "home"))
        assertTrue(isTrailerPreviewRouteLeave("home", null, "home"))
        // The incoming screen's card registered first: it stays.
        assertFalse(isTrailerPreviewRouteLeave("home", "library", "library"))
    }

    private fun TestScope.panel(machine: TrailerPreviewMachine) {
        machine.focus(spring)
        advanceTimeBy(DELAY_MS + 1)
        machine.onFirstFrame()
        advanceTimeBy(DELAY_MS + 1)
    }

    private val spring = TrailerPreviewCandidate("a", "tt1", "Spring", "2019", "movie", null)
    private fun TestScope.machine() = TrailerPreviewMachine(this, { testScheduler.currentTime }, { DELAY_MS }) {
        TrailerPreviewSource("https://v/spring", null)
    }

    private fun key(m: TrailerPreviewMachine, g: TrailerPreviewKeyGate, action: Int, code: Int, repeat: Int = 0) =
        onTrailerPreviewKey(action, code, repeat, m, g)

    @Test
    fun `stage 2 OK is consumed down, repeat and up, and opens Details on the up`() = runTest {
        val m = machine()
        val g = TrailerPreviewKeyGate()
        panel(m)
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        assertEquals(TrailerPreviewKeyResult(true), key(m, g, KeyEvent.ACTION_DOWN, ok))
        assertEquals(TrailerPreviewKeyResult(true), key(m, g, KeyEvent.ACTION_DOWN, ok, repeat = 1))
        assertEquals(TrailerPreviewKeyResult(true, spring), key(m, g, KeyEvent.ACTION_UP, ok))
        assertEquals(TrailerPreviewKeyResult(false), key(m, g, KeyEvent.ACTION_UP, ok)) // no second up
    }

    @Test
    fun `Back is consumed with its up, a D-pad press passes with its up`() = runTest {
        val m = machine()
        val g = TrailerPreviewKeyGate()
        panel(m)
        val back = KeyEvent.KEYCODE_BACK
        assertEquals(TrailerPreviewKeyResult(true), key(m, g, KeyEvent.ACTION_DOWN, back))
        assertEquals(TrailerPreviewKeyResult(true), key(m, g, KeyEvent.ACTION_UP, back))
        m.leaveCurrent()
        panel(m)
        assertEquals(TrailerPreviewStage.PANEL, m.state.value?.stage)
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        assertEquals(TrailerPreviewKeyResult(false), key(m, g, KeyEvent.ACTION_DOWN, right))
        assertNull(m.state.value) // closed, and the press moves focus
        assertEquals(TrailerPreviewKeyResult(false), key(m, g, KeyEvent.ACTION_UP, right))
    }

    @Test
    fun `the handle ignores cards while In the card is off and opens only the registered card`() = runTest {
        val m = machine()
        val mode = mutableStateOf(false)
        val handle = TrailerPreviewHandle(m, mode, { "home" }, mutableStateOf(null), mutableStateOf(null))
        var opened = 0
        assertEquals(0L, handle.register(spring) { opened++ })
        handle.unregister(0L)
        advanceTimeBy(10_000)
        assertNull(m.state.value)
        mode.value = true
        val token = handle.register(spring) { opened++ }
        assertTrue(token != 0L)
        assertEquals("home", handle.registeredRoute)
        handle.openDetails(spring.copy(key = "other"))
        assertEquals(0, opened)
        handle.openDetails(spring)
        assertEquals(1, opened)
    }
}

private const val DELAY_MS = 3_000L
