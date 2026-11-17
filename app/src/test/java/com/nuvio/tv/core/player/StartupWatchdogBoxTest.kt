package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.FireReason
import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.core.stream.FailureMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 113 D1 (Shield window 12b F2): a failover chain that ends at the startup watchdog's box
 * (the cap spent, so the watchdog shows its box without failing over) counts the sources it left,
 * as the failover's own give-up does.
 */
class StartupWatchdogBoxTest {

    private fun fire(reason: FireReason, cause: FailureCause = FailureCause.NO_DATA) = PlaybackFailure(
        cause,
        "watchdog",
        facts = FailureFacts(watchdogFire = reason, waitedMs = 20_000L),
        fromStartupWatchdog = true
    )

    private val tried = listOf(
        TriedSource(FailureMark(FailureCause.NO_DATA), "S12 test files", "A.mkv"),
        TriedSource(FailureMark(FailureCause.NO_DATA), "S12 test files", "B.mkv"),
        TriedSource(FailureMark(FailureCause.NO_DATA), "S12 test files", "C.mkv")
    )
    private val earlierBox = PlaybackFailure(FailureCause.REMOVED, "404")
    private val failingOver = PlayerUiState(
        showLoadingOverlay = true,
        failoverNotice = FailoverNotice(FailureMark(FailureCause.NO_DATA), attempt = 3, max = 3, previousSteps = null),
        exhaustedFailover = ExhaustedFailover(earlierBox, tried.take(1))
    )

    @Test
    fun `a source-facing fire ends the chain and counts the sources tried`() {
        for (reason in FireReason.entries.filter(PlayerStartupWatchdogPolicy::failsOver)) {
            val fire = fire(reason)

            assertEquals(
                reason.toString(),
                failingOver.copy(
                    error = fire,
                    showLoadingOverlay = false,
                    exhaustedFailover = ExhaustedFailover(fire, tried),
                    failoverNotice = null
                ),
                failingOver.withStartupWatchdogBox(fire, tried)
            )
        }
    }

    @Test
    fun `a first source with nothing tried before it is a plain box`() {
        val fire = fire(FireReason.TracksNotRead)
        val state = PlayerUiState().withStartupWatchdogBox(fire, tried = emptyList())

        assertSame(fire, state.error)
        assertNull(state.exhaustedFailover)
    }

    @Test
    fun `a fire about the TV changes only the error slot`() {
        for (reason in FireReason.entries.filterNot(PlayerStartupWatchdogPolicy::failsOver)) {
            val fire = fire(reason, FailureCause.DECODER_STUCK)

            assertEquals(
                reason.toString(),
                failingOver.copy(error = fire, showLoadingOverlay = false),
                failingOver.withStartupWatchdogBox(fire, tried)
            )
        }
    }

    @Test
    fun `another error already shown keeps the slot and its state`() {
        val before = failingOver.copy(error = earlierBox)

        assertSame(before, before.withStartupWatchdogBox(fire(FireReason.TracksNotRead), tried))
    }
}
