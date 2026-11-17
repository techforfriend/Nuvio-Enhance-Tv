package com.nuvio.tv.core.player

import android.view.Display
import com.nuvio.tv.core.player.FrameRateUtils.DisplayModeValues
import com.nuvio.tv.core.player.FrameRateUtils.OriginalDisplayMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 93 P1: the original display mode is restored by its values, after the HDR exit. */
class FrameRateUtilsTest {

    private fun mode(id: Int, width: Int, height: Int, refresh: Float): Display.Mode = mockk {
        every { modeId } returns id
        every { physicalWidth } returns width
        every { physicalHeight } returns height
        every { refreshRate } returns refresh
    }

    private val uhd60 = DisplayModeValues(3840, 2160, 59.94f)

    @Test
    fun `restore finds the original by values after the ids were renumbered`() {
        // Before standby the UI mode was id 3; afterwards id 3 is 2160p23.976 and the UI mode is id 7.
        val modes = listOf(
            mode(3, 3840, 2160, 23.976f),
            mode(5, 1920, 1080, 59.94f),
            mode(7, 3840, 2160, 59.94f),
            mode(8, 3840, 2160, 50f)
        )
        assertEquals(7, FrameRateUtils.pickModeByValues(modes, uhd60)?.modeId)
    }

    @Test
    fun `restore accepts a refresh within the match tolerance and refuses a missing mode`() {
        val modes = listOf(mode(1, 3840, 2160, 59.940063f), mode(2, 3840, 2160, 60f))
        assertEquals(1, FrameRateUtils.pickModeByValues(modes, uhd60)?.modeId)
        assertNull(FrameRateUtils.pickModeByValues(listOf(mode(4, 1920, 1080, 59.94f)), uhd60))
        assertNull(FrameRateUtils.pickModeByValues(listOf(mode(4, 3840, 2160, 50f)), uhd60))
    }

    @Test
    fun `59_94 and 60 Hz are told apart when both are offered`() {
        // The film switched a 59.94 UI to 60 Hz; the restore must pick 59.94, not call 60 close enough.
        val modes = listOf(mode(1, 3840, 2160, 60f), mode(2, 3840, 2160, 59.94f))
        assertEquals(2, FrameRateUtils.pickModeByValues(modes, uhd60)?.modeId)
        assertEquals(1, FrameRateUtils.pickModeByValues(modes, DisplayModeValues(3840, 2160, 60f))?.modeId)
    }

    @Test
    fun `the restore writes 59_94 while the panel shows 60, never treats them as the same`() {
        val m60 = mode(1, 3840, 2160, 60f)
        val m5994 = mode(2, 3840, 2160, 59.94f)
        assertEquals(2, FrameRateUtils.restoreModeId(listOf(m60, m5994), active = m60, target = uhd60))
    }

    @Test
    fun `the restore keeps the active mode when it is exactly the original`() {
        // Twin modes with the same values: no switch to the first-listed one.
        val twinA = mode(4, 3840, 2160, 59.94f)
        val twinB = mode(9, 3840, 2160, 59.94f)
        assertEquals(9, FrameRateUtils.restoreModeId(listOf(twinA, twinB), active = twinB, target = uhd60))
    }

    @Test
    fun `the restore hands back to the system default when the original is gone`() {
        val m24 = mode(3, 3840, 2160, 23.976f)
        assertEquals(0, FrameRateUtils.restoreModeId(listOf(m24), active = m24, target = uhd60))
    }

    @Test
    fun `a session keeps its first original`() {
        val state = OriginalDisplayMode()
        assertFalse(state.record(uhd60))
        assertFalse(state.record(DisplayModeValues(3840, 2160, 23.976f)))
        assertEquals(uhd60, state.take())
        assertNull(state.take())
    }

    @Test
    fun `an exit restore is pending until it fires`() {
        val state = OriginalDisplayMode()
        state.record(uhd60)
        assertEquals(uhd60, state.deferForExit())
        assertNull(state.take())
        assertEquals(uhd60, state.takePending())
        assertNull(state.takePending())
    }

    @Test
    fun `a switch inside the exit window cancels the restore and keeps the UI mode as original`() {
        val state = OriginalDisplayMode()
        state.record(uhd60)
        state.deferForExit()
        // The next player starts while the panel is still at the film's 23.976.
        assertTrue(state.record(DisplayModeValues(3840, 2160, 23.976f)))
        assertNull(state.takePending())
        assertEquals(uhd60, state.take())
    }

    @Test
    fun `nothing recorded means nothing to defer`() {
        val state = OriginalDisplayMode()
        assertNull(state.deferForExit())
        assertNull(state.takePending())
    }

    @Test
    fun `clear drops the original and a pending restore`() {
        val state = OriginalDisplayMode()
        state.record(uhd60)
        state.deferForExit()
        state.clear()
        assertNull(state.takePending())
        assertFalse(state.record(DisplayModeValues(1920, 1080, 60f)))
        assertEquals(DisplayModeValues(1920, 1080, 60f), state.take())
    }
}
