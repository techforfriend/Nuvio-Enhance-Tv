package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElapsedTraceTest {

    private var clockMs = 0L
    private val lines = mutableListOf<String>()
    private val trace = ElapsedTrace("TEST", now = { clockMs }, log = { lines += it })

    @Test
    fun `mark logs total since begin and step since previous mark`() {
        clockMs = 1_000L
        trace.begin("start")
        clockMs = 1_250L
        trace.mark("a")
        clockMs = 1_400L
        trace.mark("b")

        assertEquals(
            listOf(
                "[start] t=0 ms (trace begin)",
                "[a] t=+250 ms (step +250 ms)",
                "[b] t=+400 ms (step +150 ms)",
            ),
            lines
        )
        assertEquals(1_000L, trace.t0ElapsedMsOrNull())
    }

    @Test
    fun `begin at an earlier T0 measures marks from that T0`() {
        clockMs = 5_000L
        trace.begin("process_start", atElapsedMs = 4_200L)
        clockMs = 5_100L
        trace.mark("activity_create")

        assertEquals(
            listOf(
                "[process_start] t=0 ms (trace begin)",
                "[activity_create] t=+900 ms (step +900 ms)",
            ),
            lines
        )
        assertEquals(4_200L, trace.t0ElapsedMsOrNull())
    }

    @Test
    fun `mark before any begin starts the run`() {
        assertNull(trace.t0ElapsedMsOrNull())
        clockMs = 500L
        trace.mark("first")

        assertEquals(listOf("[first] t=0 ms (trace begin)"), lines)
        assertEquals(500L, trace.t0ElapsedMsOrNull())
    }
}
