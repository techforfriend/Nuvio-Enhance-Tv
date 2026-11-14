package com.nuvio.tv.core.profile

/**
 * Spec 64 K3 (D15): ask for a PIN profile's PIN again after Nuvio has been off screen for a while.
 * The setting is whole minutes on this TV; [OFF] asks only when Nuvio starts.
 */
object PinRelock {
    const val OFF = 0
    const val DEFAULT_MINUTES = 15
    const val ONE_HOUR = 60

    /** The picker's choices, in order (D15: Off, 5, 15, 30, 45 min, 1 hour). */
    val OPTIONS: List<Int> = listOf(OFF, 5, DEFAULT_MINUTES, 30, 45, ONE_HOUR)

    /** The shortest time on offer: what an unknown setting waits (fail closed). */
    val SHORTEST_MINUTES: Int = OPTIONS.first { it > OFF }

    /** A stored value the picker does not offer reads as the default. */
    fun sanitize(minutes: Int?): Int = minutes?.takeIf { it in OPTIONS } ?: DEFAULT_MINUTES
}

/**
 * Whether returning to Nuvio at [nowMs] must go back to Who's watching. [stoppedAtMs] is when Nuvio
 * last left the screen (both on the since-boot clock), null when it has not left since the main UI
 * opened. Only a profile with a PIN re-locks. A clock that went backwards (defensive: the stamp
 * lives in saved state, which a reboot does not keep) re-locks.
 */
fun shouldRelock(stoppedAtMs: Long?, nowMs: Long, afterMinutes: Int, hasPin: Boolean): Boolean {
    if (stoppedAtMs == null || afterMinutes <= PinRelock.OFF || !hasPin) return false
    if (nowMs < stoppedAtMs) return true
    return nowMs - stoppedAtMs >= afterMinutes * 60_000L
}

/**
 * [shouldRelock] from the values the app has collected: [relockMinutes] null (not loaded or
 * unreadable, as on every restored process's replayed ON_START) uses the shortest option; a
 * [pinEnabled] map or [activeProfileId] not loaded (or unreadable) counts as a PIN. Missing state
 * re-locks sooner, never later (fail closed); a restored process also re-locks under Off.
 */
fun relockDue(
    stoppedAtMs: Long?,
    nowMs: Long,
    relockMinutes: Int?,
    pinEnabled: Map<Int, Boolean>?,
    activeProfileId: Int?
): Boolean {
    val hasPin = activeProfileId == null || pinEnabled == null || pinEnabled[activeProfileId] == true
    return shouldRelock(stoppedAtMs, nowMs, relockMinutes ?: PinRelock.SHORTEST_MINUTES, hasPin)
}
