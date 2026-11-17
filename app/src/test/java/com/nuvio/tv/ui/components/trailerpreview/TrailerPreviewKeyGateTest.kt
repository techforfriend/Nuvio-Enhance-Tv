package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewKeyGate.Held
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 96 PD6: every press the preview acted on keeps its repeats and KeyUp. */
class TrailerPreviewKeyGateTest {
    private val back = 4
    private val ok = 23

    @Test
    fun `a consumed press consumes its repeats and its up`() {
        val gate = TrailerPreviewKeyGate()
        assertFalse(gate.onKeyDown(back, 0))
        gate.hold(back, Held.CONSUMED)
        assertTrue(gate.onKeyDown(back, 1))
        assertEquals(Held.CONSUMED, gate.onKeyUp(back))
        assertNull(gate.onKeyUp(back))
    }

    @Test
    fun `an untouched key is not the gate's`() {
        val gate = TrailerPreviewKeyGate()
        assertFalse(gate.onKeyDown(ok, 0))
        assertFalse(gate.onKeyDown(ok, 3))
        assertNull(gate.onKeyUp(ok))
    }

    @Test
    fun `an open-on-up press consumes its repeats and reports its up`() {
        val gate = TrailerPreviewKeyGate()
        assertFalse(gate.onKeyDown(ok, 0))
        gate.hold(ok, Held.OPEN_ON_UP)
        assertTrue(gate.onKeyDown(ok, 1))
        assertEquals(Held.OPEN_ON_UP, gate.onKeyUp(ok))
    }

    @Test
    fun `a fresh press forgets an up that never came`() {
        val gate = TrailerPreviewKeyGate()
        gate.hold(ok, Held.OPEN_ON_UP)
        assertFalse(gate.onKeyDown(ok, 0))
        assertNull(gate.onKeyUp(ok))
    }
}
