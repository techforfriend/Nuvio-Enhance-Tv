package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AspectMode
import com.nuvio.tv.core.player.PLAYBACK_SPEEDS
import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 25 U6e: the Speed panel's initial row and the More tray's value dots. */
class SpeedPanelAndTrayTest {

    @Test
    fun speedPanelOpensOnTheCurrentSpeed() {
        assertEquals(PLAYBACK_SPEEDS.indexOf(1f), speedPanelInitialIndex(1f))
        assertEquals(PLAYBACK_SPEEDS.indexOf(1.25f), speedPanelInitialIndex(1.25f))
        assertEquals(0, speedPanelInitialIndex(0.25f))
        assertEquals(PLAYBACK_SPEEDS.lastIndex, speedPanelInitialIndex(2f))
    }

    @Test
    fun anOffListSpeedOpensOnItsNearestRow() {
        assertEquals(PLAYBACK_SPEEDS.indexOf(1.25f), speedPanelInitialIndex(1.2f))
        assertEquals(PLAYBACK_SPEEDS.lastIndex, speedPanelInitialIndex(3f))
    }

    @Test
    fun onlyNonDefaultValuesCarryTheDot() {
        assertFalse(speedValueMarked(1f))
        assertTrue(speedValueMarked(1.25f))
        assertTrue(speedValueMarked(0.5f))
        assertFalse(aspectValueMarked(AspectMode.ORIGINAL))
        AspectMode.entries.filter { it != AspectMode.ORIGINAL }.forEach { assertTrue(aspectValueMarked(it)) }
    }

    @Test
    fun switchEngineDimsOnlyWhenItWouldGoToABarredMpv() {
        // Spec 97 D31 stage 16: on ExoPlayer (or AUTO) the switch goes to mpv.
        assertTrue(switchEngineDimmed(mpvBarredForStream = true, engine = InternalPlayerEngine.EXOPLAYER))
        assertTrue(switchEngineDimmed(mpvBarredForStream = true, engine = InternalPlayerEngine.AUTO))
        // On mpv the switch goes to ExoPlayer, never barred.
        assertFalse(switchEngineDimmed(mpvBarredForStream = true, engine = InternalPlayerEngine.MVP_PLAYER))
        assertFalse(switchEngineDimmed(mpvBarredForStream = false, engine = InternalPlayerEngine.EXOPLAYER))
    }
}
