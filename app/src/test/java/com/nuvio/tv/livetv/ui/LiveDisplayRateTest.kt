package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.player.LiveFrameRate.Family
import com.nuvio.tv.livetv.ui.LiveDisplayRate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 94 T4 D1-D3: when Live TV switches the display, and what a restore puts back. */
class LiveDisplayRateTest {

    @Test
    fun `the decision table`() {
        // Setting not read yet: nothing, whatever else holds.
        assertEquals(Action.NONE, LiveDisplayRate.action(null, true, Family.PAL))
        // Off restores, in full screen or not.
        assertEquals(Action.RESTORE, LiveDisplayRate.action(false, true, Family.PAL))
        assertEquals(Action.RESTORE, LiveDisplayRate.action(false, false, null))
        // D2: the preview never switches; D3: an unknown rate keeps the display.
        assertEquals(Action.NONE, LiveDisplayRate.action(true, false, Family.PAL))
        assertEquals(Action.NONE, LiveDisplayRate.action(true, false, Family.OTHER))
        assertEquals(Action.NONE, LiveDisplayRate.action(true, true, null))
        assertEquals(Action.PAL, LiveDisplayRate.action(true, true, Family.PAL))
        assertEquals(Action.RESTORE, LiveDisplayRate.action(true, true, Family.OTHER))
    }

    private class FakeWindow(var requested0: Int, private val pal: Int?) : LiveDisplayRate.Window {
        val requests = ArrayList<Int>()
        override val requested: Int get() = requested0
        override fun palModeId(): Int? = pal
        override fun request(modeId: Int) {
            requests += modeId
            requested0 = modeId
        }
    }

    @Test
    fun `a switch remembers what the window asked for and a restore puts exactly that back`() {
        val s = LiveDisplayRate.Switch()
        val w = FakeWindow(requested0 = 0, pal = 7)
        s.apply(Action.PAL, w)
        s.apply(Action.PAL, w) // a zap to another 50 Hz channel: no second request
        assertEquals(listOf(7), w.requests)
        s.apply(Action.RESTORE, w)
        assertEquals(listOf(7, 0), w.requests)
        // PAL again right after (the display may still be at 50 Hz): the window request decides, so it is written.
        s.apply(Action.PAL, w)
        assertEquals(listOf(7, 0, 7), w.requests)
        s.restore(w)
        assertEquals(listOf(7, 0, 7, 0), w.requests)
    }

    @Test
    fun `a restore Live TV never caused changes nothing`() {
        val s = LiveDisplayRate.Switch()
        // A VOD "On start" request (24 Hz mode 3) is not Live TV's to undo.
        val w = FakeWindow(requested0 = 3, pal = 7)
        s.apply(Action.RESTORE, w)
        s.restore(w)
        assertEquals(emptyList<Int>(), w.requests)
        // Switched from it, Live TV puts it back.
        s.apply(Action.PAL, w)
        s.restore(w)
        assertEquals(listOf(7, 3), w.requests)
    }

    @Test
    fun `no 50 Hz mode means no switch and nothing to restore`() {
        val s = LiveDisplayRate.Switch()
        val w = FakeWindow(requested0 = 0, pal = null)
        s.apply(Action.PAL, w)
        s.restore(w)
        assertEquals(emptyList<Int>(), w.requests)
    }
}
