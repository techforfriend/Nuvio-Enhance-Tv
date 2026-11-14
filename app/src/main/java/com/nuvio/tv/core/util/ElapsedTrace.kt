package com.nuvio.tv.core.util

import android.os.SystemClock
import android.util.Log

/**
 * Shared begin/mark clock for the logcat stage traces (TtffTrace,
 * StartupLatencyTrace). begin() resets T0; mark() logs the total since T0 and
 * the step since the previous mark, and acts as begin() when no run has started.
 * begin() takes an explicit T0 when the run started before the call (cold start
 * anchors on the process start time).
 */
internal class ElapsedTrace(
    private val tag: String,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(tag, it) },
) {
    @Volatile private var t0ElapsedMs: Long = 0L
    @Volatile private var lastMarkElapsedMs: Long = 0L

    fun begin(name: String, atElapsedMs: Long = now()) {
        t0ElapsedMs = atElapsedMs
        lastMarkElapsedMs = atElapsedMs
        log("[$name] t=0 ms (trace begin)")
    }

    fun mark(name: String) {
        val at = now()
        if (t0ElapsedMs == 0L) {
            begin(name)
            return
        }
        log("[$name] t=+${at - t0ElapsedMs} ms (step +${at - lastMarkElapsedMs} ms)")
        lastMarkElapsedMs = at
    }

    /** T0 of the current run in SystemClock.elapsedRealtime() terms, if any. */
    fun t0ElapsedMsOrNull(): Long? = t0ElapsedMs.takeIf { it > 0L }
}
