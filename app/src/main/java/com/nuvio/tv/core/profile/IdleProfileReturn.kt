package com.nuvio.tv.core.profile

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec 62 H3 (D3, D8, D13): hand the TV back to Who's watching after [IDLE_MS] without a key press
 * outside the player, and on every wake from standby. MainActivity owns one instance and feeds it
 * on the main thread; this class only decides.
 *
 * D13, one decision point: a return is decided only at an app event, never held for later:
 * - [onAppStart] at ON_START (a wake, 30 minutes away, or a return owed behind the screensaver),
 *   answered synchronously so the caller can hold the main UI back before a launcher or deep-link
 *   launch composes (as Spec 64 K3's relock does); it clears anything owed or requested.
 * - [checkIdle] at the idle timeout while resumed: returns now when no in-app screensaver shows,
 *   else marks the return [owed].
 * - [onScreensaverDismissed] at the key press that dismisses the in-app screensaver: an owed
 *   return happens now.
 * The two resumed-time outcomes raise [requested], which the composition applies at once (no
 * launch can be in flight while resumed); ON_START drops a request it has not applied.
 *
 * Playback is never interrupted: time in the built-in player, Live TV or an external player counts
 * as use, and the 30 minutes start again when it ends. Another window holding focus (a dialog,
 * whose keys this activity never sees) counts as use too, except the system screensaver (a dream,
 * from ACTION_DREAMING_STARTED to ACTION_DREAMING_STOPPED), which is idle time. Regaining focus is
 * use: a dream ends at a key press or at standby (which [onAppStart] handles). Times are on the
 * since-boot clock.
 */
class IdleProfileReturn(private val nowMs: () -> Long = SystemClock::elapsedRealtime) {

    companion object {
        const val IDLE_MS = 30 * 60_000L
        /** How soon to check again while the return is held off (off, playback, a dialog). */
        const val HELD_POLL_MS = 60_000L
    }

    private var lastUseMs = nowMs()
    private var windowFocused = true
    private var dreaming = false

    /** Playback on screen; see [setInPlayback]. */
    var inPlayback = false
        private set

    /** The idle timeout passed under the in-app screensaver: the next app event decides. */
    var owed = false
        private set

    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    /** A key press: the idle time starts again. */
    fun onUse() {
        lastUseMs = nowMs()
    }

    /**
     * Playback on screen (the player route, or Live TV / the player playing) started or ended;
     * its end starts the idle time again. Nothing stays owed across playback.
     */
    fun setInPlayback(active: Boolean) {
        if (inPlayback && !active) onUse()
        if (active) owed = false
        inPlayback = active
    }

    /** ACTION_DREAMING_STARTED: the system screensaver took the screen; that time is idle. */
    fun onDreamingStarted() {
        dreaming = true
    }

    /** ACTION_DREAMING_STOPPED: an unfocused window is a dialog again (use). */
    fun onDreamingStopped() {
        dreaming = false
    }

    /** The activity window's focus; regaining it starts the idle time again. */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowFocused = hasFocus
        if (hasFocus) onUse()
    }

    /**
     * ON_START: whether to go back to Who's watching now. [slept]: the TV went to standby since
     * the app was last on screen. Coming back from an external player is use, not idle time (the
     * app was stopped for the whole film); returning over playback never happens. Whatever the
     * answer, nothing stays owed or requested past this point.
     */
    fun onAppStart(enabled: Boolean, slept: Boolean, externalPlayback: Boolean): Boolean {
        val wasOwed = owed
        owed = false
        _requested.value = false
        if (externalPlayback) {
            onUse()
            return false
        }
        val due = enabled && !inPlayback && (slept || wasOwed || nowMs() - lastUseMs >= IDLE_MS)
        if (due) onUse()
        return due
    }

    /**
     * The idle check while the app is resumed. [screensaverShowing]: the in-app screensaver is up,
     * so a due return waits for the key press that dismisses it. Returns how long to wait before
     * the next check.
     */
    fun checkIdle(enabled: Boolean, externalPlayback: Boolean, screensaverShowing: Boolean): Long {
        if (!enabled || inPlayback || externalPlayback || (!windowFocused && !dreaming)) {
            onUse()
            if (!enabled) owed = false
            return HELD_POLL_MS
        }
        val idleMs = nowMs() - lastUseMs
        if (idleMs < IDLE_MS) return IDLE_MS - idleMs
        // A decision restarts the idle time, so a dropped one cannot fire again at once.
        onUse()
        if (screensaverShowing) owed = true else _requested.value = true
        return IDLE_MS
    }

    /** The key press that dismissed the in-app screensaver: an owed return happens now. */
    fun onScreensaverDismissed(enabled: Boolean) {
        val due = owed && enabled && !inPlayback
        owed = false
        if (due) _requested.value = true
    }

    /** The composition applied (or dropped) the request. */
    fun consume() {
        _requested.value = false
    }
}

/**
 * Whether the active profile has a PIN: null until both the stored active id and the PIN map have
 * loaded (the startup PIN gate's "maybe locked").
 */
fun activeProfilePinState(storedActiveProfileId: Int?, pinEnabled: Map<Int, Boolean>?): Boolean? =
    storedActiveProfileId?.let { id -> pinEnabled?.let { it[id] == true } }

/**
 * D13: a return applies only when someone can be switched to: more than one profile, or a PIN on
 * the active one. An unknown count ([profileCount] null, the list still loading) or PIN state
 * ([activeProfileHasPin] null) counts, as the PIN gate treats it; the gate waits for both to load
 * and drops the return once it finds no one.
 */
fun profileReturnHasTarget(profileCount: Int?, activeProfileHasPin: Boolean?): Boolean =
    profileCount == null || profileCount > 1 || activeProfileHasPin != false
