package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.stream.FailureCause
import com.nuvio.tv.ui.screens.player.FailureBoxAction.EXTERNAL_PLAYER
import com.nuvio.tv.ui.screens.player.FailureBoxAction.NEXT_SOURCE
import com.nuvio.tv.ui.screens.player.FailureBoxAction.RETRY
import com.nuvio.tv.ui.screens.player.FailureBoxAction.SWITCH_ENGINE
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureActionsTest {

    private val linkCauses = setOf(
        FailureCause.LINK_EXPIRED, FailureCause.BLOCKED, FailureCause.REMOVED, FailureCause.OTHER_HTTP,
        FailureCause.WEB_PAGE, FailureCause.TINY_FILE, FailureCause.TEXT, FailureCause.LOGIN,
        FailureCause.PLACEHOLDER, FailureCause.NO_URL, FailureCause.TOO_SLOW, FailureCause.CORRUPT,
        FailureCause.FROZEN, FailureCause.TORRENT, FailureCause.WRONG_FILE
    )
    private val passingCauses = setOf(
        FailureCause.NO_CONNECTION, FailureCause.SERVER_DOWN, FailureCause.RATE_LIMITED, FailureCause.NO_DATA,
        FailureCause.ENGINE, FailureCause.UNKNOWN
    )
    private val deviceCauses = setOf(
        FailureCause.VIDEO_UNSUPPORTED, FailureCause.UNREADABLE, FailureCause.DECODER_STUCK, FailureCause.AUDIO_FAILED
    )

    @Test
    fun `the three groups cover every cause once`() {
        assertEquals(FailureCause.entries.toSet(), linkCauses + passingCauses + deviceCauses)
        assertEquals(FailureCause.entries.size, linkCauses.size + passingCauses.size + deviceCauses.size)
    }

    @Test
    fun `a link or source cause leads with Next source, and draws nothing when none is left`() {
        linkCauses.forEach { cause ->
            assertEquals(cause.name, listOf(NEXT_SOURCE), PlaybackFailureActions.of(cause, nextSource = true, switchEngine = true))
            assertEquals(cause.name, emptyList<FailureBoxAction>(), PlaybackFailureActions.of(cause, nextSource = false, switchEngine = true))
        }
    }

    @Test
    fun `a passing cause leads with Retry, then Next source when one is left`() {
        passingCauses.forEach { cause ->
            assertEquals(cause.name, listOf(RETRY, NEXT_SOURCE), PlaybackFailureActions.of(cause, nextSource = true, switchEngine = true))
            assertEquals(cause.name, listOf(RETRY), PlaybackFailureActions.of(cause, nextSource = false, switchEngine = true))
        }
    }

    @Test
    fun `a this-TV cause offers the other engine first, then Next source and External player`() {
        deviceCauses.forEach { cause ->
            assertEquals(
                cause.name,
                listOf(SWITCH_ENGINE, NEXT_SOURCE, EXTERNAL_PLAYER),
                PlaybackFailureActions.of(cause, nextSource = true, switchEngine = true)
            )
            // Stage 5: both engines failed this link, so Next source leads.
            assertEquals(
                cause.name,
                listOf(NEXT_SOURCE, EXTERNAL_PLAYER),
                PlaybackFailureActions.of(cause, nextSource = true, switchEngine = false)
            )
            assertEquals(cause.name, listOf(EXTERNAL_PLAYER), PlaybackFailureActions.of(cause, nextSource = false, switchEngine = false))
        }
    }
}
