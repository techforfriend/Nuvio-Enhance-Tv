package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import com.nuvio.tv.livetv.player.LiveFrameRate.Family
import com.nuvio.tv.livetv.player.LiveFrameRate.Settled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 94 T4: the frame-rate families, the counting meter and the probe. */
class LiveFrameRateTest {

    @Test
    fun `families cover the broadcast rates and nothing else, edges pinned`() {
        for (fps in listOf(25f, 24.5f, 25.5f, 50f, 49f, 51f)) assertEquals("$fps", Family.PAL, LiveFrameRate.family(fps))
        for (fps in listOf(23.5f, 23.976f, 24f, 24.49f, 29.5f, 29.97f, 30.5f, 59f, 59.94f, 61f)) {
            assertEquals("$fps", Family.OTHER, LiveFrameRate.family(fps))
        }
        for (fps in listOf(0f, 12.5f, 23.4f, 25.6f, 40f, 48.9f, 51.1f, 75f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull("$fps", LiveFrameRate.family(fps))
        }
    }

    /** [samples] samples 3 s of wall time apart, media time running at [speed]. */
    private fun feed(meter: LiveFrameRate.Meter, fps: Float, samples: Int, speed: Float = 1f): List<Family?> =
        (0 until samples).map { i ->
            val mediaMs = (3_000L * i * speed).toLong()
            meter.sample((fps * mediaMs / 1000f).toLong(), mediaMs, 3_000L * i)
        }

    @Test
    fun `two agreeing windows settle the family`() {
        assertEquals(listOf(null, null, Family.PAL), feed(LiveFrameRate.Meter(), 25f, 3))
        assertEquals(listOf(null, null, Family.OTHER), feed(LiveFrameRate.Meter(), 60f, 3))
        assertEquals(Family.PAL, feed(LiveFrameRate.Meter(), 50f, 3).last())
    }

    @Test
    fun `live speed control does not move a rate across families`() {
        // Media3 plays live at up to 1.03x: 24 fps is 24.7 frames per wall second, still 24 per media second.
        assertEquals(Family.OTHER, feed(LiveFrameRate.Meter(), 24f, 3, speed = 1.03f).last())
        assertEquals(Family.PAL, feed(LiveFrameRate.Meter(), 25f, 3, speed = 0.97f).last())
    }

    @Test
    fun `a disagreeing or unreadable window starts the streak again`() {
        val m = LiveFrameRate.Meter()
        assertNull(m.sample(0, 0, 0))
        assertNull(m.sample(75, 3_000, 3_000)) // 25 fps
        assertNull(m.sample(255, 6_000, 6_000)) // 60 fps: streak restarts
        assertNull(m.sample(285, 9_000, 9_000)) // 10 fps: no family
        assertNull(m.sample(360, 12_000, 12_000)) // 25 fps, first of a new streak
        assertEquals(Family.PAL, m.sample(435, 15_000, 15_000))
    }

    @Test
    fun `a stall, a jump or restarted counters take a new baseline`() {
        val m = LiveFrameRate.Meter()
        m.sample(0, 0, 0)
        m.sample(75, 3_000, 3_000)
        // Media time stood still for 2 of 3 s (a rebuffer inside the window): no rate from that pair.
        assertNull(m.sample(100, 4_000, 6_000))
        // A seek to the live edge (media jumps 20 s in 3 s): no rate either.
        assertNull(m.sample(175, 24_000, 9_000))
        // Counters went back (a new renderer enable): no rate.
        assertNull(m.sample(10, 27_000, 12_000))
        // The streak from the first window carries on.
        assertEquals(Family.PAL, m.sample(85, 30_000, 15_000))

        val r = LiveFrameRate.Meter()
        r.sample(0, 0, 0)
        r.sample(75, 3_000, 3_000)
        r.reset() // paused
        assertNull(r.sample(75, 3_000, 60_000))
        assertNull(r.sample(150, 6_000, 63_000))
        assertEquals(Family.PAL, r.sample(225, 9_000, 66_000))
    }

    @Test
    fun `media time going back is a new baseline`() {
        val m = LiveFrameRate.Meter()
        m.sample(0, 10_000, 0)
        m.sample(75, 13_000, 3_000)
        // A re-prepare or period removal moves media time back: no rate from that pair.
        assertNull(m.sample(150, 2_000, 6_000))
        assertEquals(Family.PAL, m.sample(225, 5_000, 9_000))
    }

    @Test
    fun `a frame of jitter either way keeps 24 and 25 apart`() {
        // 3 s windows: 24 fps = 72 frames, 25 fps = 75; the boundary (24.5) is 73.5.
        for ((frames, family) in listOf(71 to Family.OTHER, 73 to Family.OTHER, 74 to Family.PAL, 76 to Family.PAL)) {
            val m = LiveFrameRate.Meter()
            m.sample(0, 0, 0)
            m.sample(frames.toLong(), 3_000, 3_000)
            assertEquals("$frames", family, m.sample(2L * frames, 6_000, 6_000))
        }
    }

    @Test
    fun `the probe takes a declared rate first, else counts while playing`() {
        assertEquals(Settled(Family.PAL, declared = true), LiveFrameRate.Probe().step(50f, null, false, 0, 0))
        assertEquals(Settled(Family.OTHER, declared = true), LiveFrameRate.Probe().step(29.97f, 0, true, 0, 0))

        // An unclassifiable declared rate (12.5) falls back to counting, and the result says measured.
        val p = LiveFrameRate.Probe()
        assertNull(p.step(12.5f, 0, true, 0, 0))
        assertNull(p.step(12.5f, 75, true, 3_000, 3_000))
        assertEquals(Settled(Family.PAL, declared = false), p.step(12.5f, 150, true, 6_000, 6_000))

        // No counters or not playing: the meter restarts.
        val q = LiveFrameRate.Probe()
        assertNull(q.step(null, 0, true, 0, 0))
        assertNull(q.step(null, 75, true, 3_000, 3_000))
        assertNull(q.step(null, 150, false, 6_000, 6_000))
        assertNull(q.step(null, null, true, 9_000, 9_000))
        assertNull(q.step(null, 150, true, 9_000, 9_000))
        assertNull(q.step(null, 225, true, 12_000, 12_000))
        assertEquals(Settled(Family.PAL, declared = false), q.step(null, 300, true, 15_000, 15_000))
    }

    @Test
    fun `matching off keeps the seamless-only strategy`() {
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, LiveFrameRate.strategy(false))
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, LiveFrameRate.strategy(true))
    }
}
