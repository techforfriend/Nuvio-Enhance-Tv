package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 64 K3 (D15): when coming back to Nuvio asks for the PIN again. */
class PinRelockTest {
    private val min = 60_000L

    @Test
    fun `every offered time re-locks at exactly that long away and not a millisecond sooner`() {
        (PinRelock.OPTIONS - PinRelock.OFF).forEach { minutes ->
            val away = minutes * min
            assertTrue("$minutes", shouldRelock(stoppedAtMs = 1_000, nowMs = 1_000 + away, afterMinutes = minutes, hasPin = true))
            assertFalse("$minutes", shouldRelock(stoppedAtMs = 1_000, nowMs = 1_000 + away - 1, afterMinutes = minutes, hasPin = true))
        }
    }

    @Test
    fun `no PIN, Off, or no stop never re-locks`() {
        assertFalse(shouldRelock(stoppedAtMs = 0, nowMs = 99 * min, afterMinutes = 5, hasPin = false))
        assertFalse(shouldRelock(stoppedAtMs = 0, nowMs = 99 * min, afterMinutes = PinRelock.OFF, hasPin = true))
        assertFalse(shouldRelock(stoppedAtMs = null, nowMs = 99 * min, afterMinutes = 5, hasPin = true))
    }

    @Test
    fun `a clock that went backwards re-locks`() {
        assertTrue(shouldRelock(stoppedAtMs = 50 * min, nowMs = 1 * min, afterMinutes = 60, hasPin = true))
    }

    @Test
    fun `stored values outside the offered choices read as the default`() {
        assertEquals(PinRelock.DEFAULT_MINUTES, PinRelock.sanitize(null))
        assertEquals(PinRelock.DEFAULT_MINUTES, PinRelock.sanitize(7))
        assertEquals(PinRelock.DEFAULT_MINUTES, PinRelock.sanitize(-1))
        PinRelock.OPTIONS.forEach { assertEquals(it, PinRelock.sanitize(it)) }
    }

    @Test
    fun `relockDue reads the active profile's PIN from the map`() {
        val away = 20 * min
        assertTrue(relockDue(0, away, 15, mapOf(2 to true), activeProfileId = 2))
        assertFalse(relockDue(0, away, 15, mapOf(2 to false), activeProfileId = 2))
        assertFalse(relockDue(0, away, 15, mapOf(1 to true), activeProfileId = 2))
    }

    @Test
    fun `relockDue fails closed on state not loaded or unreadable`() {
        val away = 20 * min
        assertTrue("no PIN map", relockDue(0, away, 15, pinEnabled = null, activeProfileId = 2))
        assertTrue("no active id", relockDue(0, away, 15, mapOf(2 to false), activeProfileId = null))
        assertTrue("no setting: shortest option, 5", relockDue(0, 5 * min, relockMinutes = null, pinEnabled = mapOf(2 to true), activeProfileId = 2))
        assertFalse("shortest option not yet reached", relockDue(0, 4 * min, relockMinutes = null, pinEnabled = mapOf(2 to true), activeProfileId = 2))
    }

    @Test
    fun `relockDue keeps Off and a return with no stop`() {
        assertFalse(relockDue(0, 99 * min, PinRelock.OFF, null, null))
        assertFalse(relockDue(null, 99 * min, 5, null, null))
    }
}
