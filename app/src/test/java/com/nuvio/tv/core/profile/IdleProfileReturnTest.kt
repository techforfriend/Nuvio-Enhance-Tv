package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Spec 62 H3 (D3, D8, D13).
class IdleProfileReturnTest {
    private var now = 1_000L
    private val idle = IdleProfileReturn { now }
    private val thirty = IdleProfileReturn.IDLE_MS

    private fun check(enabled: Boolean = true, external: Boolean = false, screensaver: Boolean = false) =
        idle.checkIdle(enabled, external, screensaver)

    private fun start(enabled: Boolean = true, slept: Boolean = false, external: Boolean = false) =
        idle.onAppStart(enabled, slept, external)

    @Test
    fun `returns after 30 minutes without use and waits only the time left`() {
        now += 10 * 60_000L
        assertEquals(20 * 60_000L, check())
        assertFalse(idle.requested.value)
        now += 20 * 60_000L
        assertEquals(thirty, check())
        assertTrue(idle.requested.value)
    }

    @Test
    fun `a key press starts the 30 minutes again`() {
        now += 29 * 60_000L
        idle.onUse()
        now += 29 * 60_000L
        check()
        assertFalse(idle.requested.value)
    }

    @Test
    fun `never during playback, and the 30 minutes start again when it ends`() {
        idle.setInPlayback(true)
        now += 3 * 60 * 60_000L
        assertEquals(IdleProfileReturn.HELD_POLL_MS, check())
        assertFalse(idle.requested.value)
        assertFalse(start(slept = true))
        idle.setInPlayback(false)
        now += 29 * 60_000L
        check()
        assertFalse(idle.requested.value)
        now += 60_000L
        check()
        assertTrue(idle.requested.value)
    }

    @Test
    fun `off, an external player or a dialog counts as use and polls again soon`() {
        listOf(
            { check(enabled = false) },
            { check(external = true) },
            {
                idle.onWindowFocusChanged(hasFocus = false)
                check().also { idle.onWindowFocusChanged(hasFocus = true) }
            }
        ).forEach { held ->
            now += 2 * thirty
            assertEquals(IdleProfileReturn.HELD_POLL_MS, held())
            assertFalse(idle.requested.value)
            check()
            assertFalse(idle.requested.value)
        }
    }

    @Test
    fun `regaining focus is use`() {
        idle.onWindowFocusChanged(hasFocus = false)
        now += 29 * 60_000L
        idle.onWindowFocusChanged(hasFocus = true)
        now += 29 * 60_000L
        check()
        assertFalse(idle.requested.value)
    }

    @Test
    fun `the system screensaver is idle time until it stops`() {
        idle.onWindowFocusChanged(hasFocus = false)
        idle.onDreamingStarted()
        now += thirty
        check()
        assertTrue(idle.requested.value)
        idle.consume()

        // Stopped while a dialog keeps focus: that window is use again.
        idle.onDreamingStarted()
        now += 10 * 60_000L
        idle.onDreamingStopped()
        now += 2 * thirty
        assertEquals(IdleProfileReturn.HELD_POLL_MS, check())
        assertFalse(idle.requested.value)
    }

    @Test
    fun `every wake from standby returns at start, even after a short standby`() {
        now += 60_000L
        assertTrue(start(slept = true))
    }

    @Test
    fun `30 minutes away returns at start, a short trip away does not`() {
        now += 10 * 60_000L
        assertFalse(start())
        now += thirty
        assertTrue(start())
        // The return restarts the idle time and clears a pending idle request.
        assertFalse(idle.requested.value)
        assertFalse(start())
    }

    @Test
    fun `the setting off never returns at start`() {
        now += 2 * thirty
        assertFalse(start(enabled = false, slept = true))
    }

    @Test
    fun `coming back from an external player is use, not idle time`() {
        now += 2 * 60 * 60_000L
        assertFalse(start(slept = true, external = true))
        check()
        assertFalse(idle.requested.value)
    }

    @Test
    fun `a consumed request does not fire again at once`() {
        now += thirty
        check()
        assertTrue(idle.requested.value)
        idle.consume()
        now += 60_000L
        check()
        assertFalse(idle.requested.value)
    }

    @Test
    fun `under the screensaver the timeout is owed, and the dismissing press returns`() {
        now += thirty
        check(screensaver = true)
        assertFalse(idle.requested.value)
        assertTrue(idle.owed)
        idle.onUse()
        idle.onScreensaverDismissed(enabled = true)
        assertTrue(idle.requested.value)
        assertFalse(idle.owed)
    }

    @Test
    fun `a press with nothing owed, or the setting off, does not return`() {
        idle.onScreensaverDismissed(enabled = true)
        assertFalse(idle.requested.value)
        now += thirty
        check(screensaver = true)
        idle.onScreensaverDismissed(enabled = false)
        assertFalse(idle.requested.value)
        assertFalse(idle.owed)
    }

    @Test
    fun `an owed return is decided at the next start and never survives it`() {
        now += thirty
        check(screensaver = true)
        // Left the app without a key press (another app took the screen); back a minute later.
        now += 60_000L
        assertTrue(start())
        assertFalse(idle.owed)
        assertFalse(idle.requested.value)
        idle.onScreensaverDismissed(enabled = true)
        assertFalse(idle.requested.value)
    }

    @Test
    fun `start drops a request it has not applied`() {
        now += thirty
        check()
        assertTrue(idle.requested.value)
        now += 60_000L
        assertFalse(start())
        assertFalse(idle.requested.value)
    }

    @Test
    fun `playback clears anything owed`() {
        now += thirty
        check(screensaver = true)
        idle.setInPlayback(true)
        assertFalse(idle.owed)
        idle.setInPlayback(false)
        idle.onScreensaverDismissed(enabled = true)
        assertFalse(idle.requested.value)
    }

    @Test
    fun `ending playback that never started does not restart the idle time`() {
        now += 29 * 60_000L
        idle.setInPlayback(false)
        now += 60_000L
        check()
        assertTrue(idle.requested.value)
    }

    @Test
    fun `turning the setting off clears an owed return`() {
        now += thirty
        check(screensaver = true)
        assertTrue(idle.owed)
        check(enabled = false)
        assertFalse(idle.owed)
        idle.onScreensaverDismissed(enabled = true)
        assertFalse(idle.requested.value)
    }

    @Test
    fun `start with the setting off clears an owed return`() {
        now += thirty
        check(screensaver = true)
        assertFalse(start(enabled = false))
        assertFalse(idle.owed)
        assertFalse(start())
    }

    @Test
    fun `a return needs someone to switch to`() {
        assertFalse(profileReturnHasTarget(profileCount = 1, activeProfileHasPin = false))
        assertTrue(profileReturnHasTarget(profileCount = 2, activeProfileHasPin = false))
        assertTrue(profileReturnHasTarget(profileCount = 1, activeProfileHasPin = true))
        assertTrue(profileReturnHasTarget(profileCount = 1, activeProfileHasPin = null))
        // The list still loading counts; the gate waits for it.
        assertTrue(profileReturnHasTarget(profileCount = null, activeProfileHasPin = false))
    }

    @Test
    fun `the active profile's PIN state is known only once the id and the map load`() {
        assertEquals(null, activeProfilePinState(storedActiveProfileId = null, pinEnabled = mapOf(1 to true)))
        assertEquals(null, activeProfilePinState(storedActiveProfileId = 1, pinEnabled = null))
        assertEquals(true, activeProfilePinState(storedActiveProfileId = 1, pinEnabled = mapOf(1 to true)))
        assertEquals(false, activeProfilePinState(storedActiveProfileId = 2, pinEnabled = mapOf(1 to true)))
        assertEquals(false, activeProfilePinState(storedActiveProfileId = 1, pinEnabled = mapOf(1 to false)))
    }
}
