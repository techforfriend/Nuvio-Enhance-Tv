package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.PlayerFreezeRecoveryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFreezeRecoveryPolicyTest {

    private fun decide(
        stalledForMs: Long,
        nowMs: Long = 1_000_000L,
        lastSeekAtMs: Long? = null,
        failoverDone: Boolean = false,
        errorShown: Boolean = false,
    ) = PlayerFreezeRecoveryPolicy.evaluate(
        PlayerFreezeRecoveryPolicy.Input(stalledForMs, nowMs, lastSeekAtMs, failoverDone, errorShown)
    )

    @Test
    fun `a freeze shorter than the act threshold is left alone`() {
        assertEquals(Decision.NONE, decide(stalledForMs = 7_500L)) // the Shield's self-healing freeze
        assertEquals(Decision.NONE, decide(stalledForMs = 9_999L))
    }

    @Test
    fun `first freeze past the threshold seeks`() {
        assertEquals(Decision.SEEK, decide(stalledForMs = 10_000L))
    }

    @Test
    fun `a freeze that carries on through the seek fails over after its own window`() {
        val seekAt = 1_000_000L
        assertEquals(Decision.NONE, decide(stalledForMs = 15_000L, nowMs = seekAt + 5_000L, lastSeekAtMs = seekAt))
        assertEquals(Decision.FAILOVER, decide(stalledForMs = 20_000L, nowMs = seekAt + 10_000L, lastSeekAtMs = seekAt))
    }

    @Test
    fun `a new freeze soon after the seek fails over, a late one seeks again`() {
        val seekAt = 1_000_000L
        assertEquals(Decision.FAILOVER, decide(stalledForMs = 10_000L, nowMs = seekAt + 40_000L, lastSeekAtMs = seekAt))
        assertEquals(Decision.FAILOVER, decide(stalledForMs = 10_000L, nowMs = seekAt + 60_000L, lastSeekAtMs = seekAt))
        assertEquals(Decision.SEEK, decide(stalledForMs = 10_000L, nowMs = seekAt + 60_001L, lastSeekAtMs = seekAt))
    }

    @Test
    fun `nothing after the failover ran or while an error shows`() {
        assertEquals(Decision.NONE, decide(stalledForMs = 30_000L, failoverDone = true))
        assertEquals(Decision.NONE, decide(stalledForMs = 30_000L, errorShown = true))
    }
}
