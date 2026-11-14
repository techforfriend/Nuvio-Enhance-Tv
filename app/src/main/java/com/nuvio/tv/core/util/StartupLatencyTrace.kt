package com.nuvio.tv.core.util

import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Startup-latency trace: cold start to first Home frame / first catalog row
 * (Spec 69 M1) and profile-select to main UI (task 2.13). Logs elapsed-time
 * marks under a single tag so one logcat filter yields the full attribution
 * chain:
 *
 *   adb logcat -s NUVIO_STARTUP_TRACE
 *
 * The first call of any kind per process opens the cold-start run with T0 =
 * the process start time, so Application and Hilt setup, and marks fired from
 * singletons built there (AuthManager), are inside the measurement. A process
 * started for background work and opened later shows as a large
 * activity_create t; read such a run as warm, not cold. begin() (profile tap)
 * resets the clock; mark() logs the total since T0 and the step since the
 * previous mark; markOnce() logs a name at most once per process, for the
 * one-shot cold-start gates. Marks fired outside a run (e.g. a routine
 * foreground settings pull) log against the stale T0 and are recognisable by
 * their large t values. Logging only; no behavioural effect.
 */
object StartupLatencyTrace {
    private val trace = ElapsedTrace("NUVIO_STARTUP_TRACE")
    private val runBegun = AtomicBoolean(false)
    private val loggedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun begin(name: String) {
        runBegun.set(true)
        trace.begin(name)
    }

    fun mark(name: String) {
        beginColdStart()
        trace.mark(name)
    }

    fun markOnce(name: String) {
        if (loggedOnce.add(name)) mark(name)
    }

    fun beginColdStart() {
        if (runBegun.compareAndSet(false, true)) {
            trace.begin("process_start", Process.getStartElapsedRealtime())
        }
    }
}
