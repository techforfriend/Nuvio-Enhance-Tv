package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualEngineSwitchTargetTest {

    @Test
    fun `a manual switch goes to the other engine`() {
        assertEquals(InternalPlayerEngine.MVP_PLAYER, manualEngineSwitchTarget(InternalPlayerEngine.EXOPLAYER, mpvViewAttached = false))
        assertEquals(InternalPlayerEngine.MVP_PLAYER, manualEngineSwitchTarget(InternalPlayerEngine.EXOPLAYER, mpvViewAttached = true))
        assertEquals(InternalPlayerEngine.EXOPLAYER, manualEngineSwitchTarget(InternalPlayerEngine.MVP_PLAYER, mpvViewAttached = false))
        assertEquals(InternalPlayerEngine.EXOPLAYER, manualEngineSwitchTarget(InternalPlayerEngine.MVP_PLAYER, mpvViewAttached = true))
    }

    @Test
    fun `under AUTO the attached mpv view decides`() {
        assertEquals(InternalPlayerEngine.EXOPLAYER, manualEngineSwitchTarget(InternalPlayerEngine.AUTO, mpvViewAttached = true))
        assertEquals(InternalPlayerEngine.MVP_PLAYER, manualEngineSwitchTarget(InternalPlayerEngine.AUTO, mpvViewAttached = false))
    }
}
