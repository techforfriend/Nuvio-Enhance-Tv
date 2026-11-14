package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Format-capability G2 / Spec 85 G8: start engine for files ExoPlayer cannot open, and its session bookkeeping. */
class PlayerStartupEngineTest {

    private val exo = InternalPlayerEngine.EXOPLAYER
    private val mpv = InternalPlayerEngine.MVP_PLAYER
    private val auto = InternalPlayerEngine.AUTO

    @Test
    fun `manual override wins, a start-on-mpv name starts on mpv, otherwise the setting`() {
        assertEquals(exo, startupEngineBeforeAuto(override = exo, setting = exo, startsOnMpvByName = true))
        assertEquals(mpv, startupEngineBeforeAuto(override = null, setting = exo, startsOnMpvByName = true))
        assertEquals(mpv, startupEngineBeforeAuto(override = null, setting = auto, startsOnMpvByName = true))
        assertEquals(exo, startupEngineBeforeAuto(override = null, setting = exo, startsOnMpvByName = false))
        assertEquals(auto, startupEngineBeforeAuto(override = null, setting = auto, startsOnMpvByName = false))
        // Spec 85 G8: a WMV/ASF name starts on mpv like Blu-ray (the name rows: PlayerMediaSourceFactoryTest).
        assertEquals(
            mpv,
            startupEngineBeforeAuto(
                override = null,
                setting = exo,
                startsOnMpvByName = PlayerMediaSourceFactory.startsOnMpvByName("https://debrid.example/dl/9", "Concert.wmv")
            )
        )
    }

    @Test
    fun `the started engine is kept for the settings observer, an override is not`() {
        // Whatever chose it (the setting, AUTO, a start-on-mpv name), the running engine stays
        // through settings writes: an engine changed in Settings over the paused film (Spec 33 D7)
        // applies from the next start, not under the live player.
        assertEquals(mpv, sessionEngineToKeep(override = null, effective = mpv))
        assertEquals(exo, sessionEngineToKeep(override = null, effective = exo))
        // A manual override is tracked separately.
        assertNull(sessionEngineToKeep(override = exo, effective = exo))
    }

    @Test
    fun `a same-stream restart stays on the engine the stream plays in`() {
        // Spec 61 Q3. After an mpv startup failover to ExoPlayer the override is exo: a network
        // retry must not go back to the mpv setting and fail over again.
        val afterFailover = sameStreamRestartEngine(override = exo, session = null)
        assertEquals(
            exo,
            startupEngineBeforeAuto(override = afterFailover, setting = mpv, startsOnMpvByName = false)
        )
        // No override: the engine this start resolved, not a setting changed mid-film.
        val startedOnExo = sameStreamRestartEngine(override = null, session = exo)
        assertEquals(
            exo,
            startupEngineBeforeAuto(override = startedOnExo, setting = mpv, startsOnMpvByName = false)
        )
        // A manual switch wins over the engine the stream started in.
        assertEquals(mpv, sameStreamRestartEngine(override = mpv, session = exo))
        // Nothing started yet: the setting decides, as for a new stream.
        assertNull(sameStreamRestartEngine(override = null, session = null))
    }
}
