package com.nuvio.tv.livetv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** Spec 34 D7 ladder: every rung, both watchdogs, the budget, with explicit times. */
class LiveTuneLadderTest {

    private val network = LiveFailure(FailureKind.NETWORK)
    private val dead = LiveFailure(FailureKind.DEAD, 404)
    private val s = 1_000L

    @Test
    fun `no streams is unavailable at once`() {
        val step = LiveTuneLadder(0, 0L).start(0L)
        assertEquals(LadderAction.Unavailable(LiveFailure(FailureKind.DEAD)), step.action)
        assertNull(step.abandoned)
    }

    @Test
    fun `a recoverable failure reloads once, then moves to the next stream`() {
        val ladder = LiveTuneLadder(2, 0L)
        assertEquals(LadderAction.Play(0), ladder.start(0L).action)
        assertEquals(LadderAction.Reload, ladder.onError(network, false, 1 * s).action)
        val step = ladder.onError(network, false, 2 * s)
        assertEquals(LadderAction.Play(1), step.action)
        assertEquals(0, step.abandoned)
        assertEquals(network, step.failure)
    }

    @Test
    fun `a dead-link failure skips the reload, the last stream ends unavailable`() {
        val ladder = LiveTuneLadder(2, 0L)
        ladder.start(0L)
        assertEquals(LadderAction.Play(1), ladder.onError(dead, false, 1 * s).action)
        val step = ladder.onError(dead, false, 2 * s)
        assertEquals(LadderAction.Unavailable(dead), step.action)
        assertEquals(1, step.abandoned)
        assertTrue(ladder.isDone)
        assertEquals(LadderAction.None, ladder.onError(network, false, 3 * s).action)
        assertNull(ladder.deadlineMs())
    }

    @Test
    fun `a cached-token stream's startup 401, 403 or 404 gets one fresh resolve, then moves on`() {
        for (failure in listOf(LiveFailure(FailureKind.BLOCKED, 401), LiveFailure(FailureKind.BLOCKED, 403), dead)) {
            val ladder = LiveTuneLadder(2, 0L)
            ladder.start(0L)
            ladder.onResolved(fromCache = true)
            assertEquals(LadderAction.Reload, ladder.onError(failure, false, 1 * s).action)
            ladder.onResolved(fromCache = false)
            assertEquals(LadderAction.Play(1), ladder.onError(failure, false, 2 * s).action)
        }
    }

    @Test
    fun `the cached-token reload is only for its codes and only for the attempt it was resolved for`() {
        val notStale = LiveTuneLadder(2, 0L)
        notStale.start(0L)
        notStale.onResolved(fromCache = true)
        assertEquals(LadderAction.Play(1), notStale.onError(LiveFailure(FailureKind.BLOCKED, 451), false, 1 * s).action)

        val fresh = LiveTuneLadder(2, 0L)
        fresh.start(0L)
        fresh.onResolved(fromCache = false)
        assertEquals(LadderAction.Play(1), fresh.onError(LiveFailure(FailureKind.BLOCKED, 403), false, 1 * s).action)

        // The flag belongs to stream 0's attempt: stream 1 (reached by the startup watchdog, no Reload) starts without it.
        val next = LiveTuneLadder(3, 0L)
        next.start(0L)
        next.onResolved(fromCache = true)
        assertEquals(LadderAction.Play(1), next.onTick(10 * s).action)
        assertEquals(LadderAction.Play(2), next.onError(LiveFailure(FailureKind.BLOCKED, 403), false, 11 * s).action)
    }

    @Test
    fun `behind the live window seeks to the edge twice, then counts as a failure`() {
        val ladder = LiveTuneLadder(1, 0L)
        ladder.start(0L)
        ladder.onReady(1 * s)
        assertEquals(LadderAction.SeekToDefault, ladder.onError(network, true, 60 * s).action)
        assertEquals(LadderAction.SeekToDefault, ladder.onError(network, true, 61 * s).action)
        assertEquals(LadderAction.Reload, ladder.onError(network, true, 62 * s).action)
    }

    @Test
    fun `startup watchdog moves on after 10 s without reloading a stream that never played`() {
        val ladder = LiveTuneLadder(2, 0L)
        ladder.start(0L)
        assertEquals(10 * s, ladder.deadlineMs())
        assertEquals(LadderAction.None, ladder.onTick(9 * s).action)
        val step = ladder.onTick(10 * s)
        assertEquals(LadderAction.Play(1), step.action)
        assertEquals(LiveFailure(FailureKind.STALL), step.failure)
        assertEquals(20 * s, ladder.deadlineMs())
    }

    @Test
    fun `the 30 s budget ends the tune even mid-stream`() {
        val ladder = LiveTuneLadder(5, 0L)
        ladder.start(0L)
        ladder.onError(network, false, 8 * s) // reload, watchdog to 18 s
        ladder.onError(network, false, 16 * s) // stream 1, watchdog to 26 s
        ladder.onError(network, false, 25 * s) // reload, watchdog to 35 s; budget 30 s
        assertEquals(30 * s, ladder.deadlineMs())
        val step = ladder.onTick(30 * s)
        assertEquals(LadderAction.Unavailable(LiveFailure(FailureKind.STALL)), step.action)
        assertEquals(1, step.abandoned)
    }

    @Test
    fun `a failure past the budget gives up instead of trying the next stream`() {
        val ladder = LiveTuneLadder(4, 0L)
        ladder.start(0L)
        ladder.onTick(10 * s) // stream 1
        ladder.onTick(20 * s) // stream 2 at 20 s
        val step = ladder.onError(dead, false, 31 * s)
        assertEquals(LadderAction.Unavailable(dead), step.action)
    }

    @Test
    fun `mid-play buffering over 10 s fails and gets a fresh budget and reload`() {
        val ladder = LiveTuneLadder(2, 0L)
        ladder.start(0L)
        ladder.onReady(1 * s)
        assertNull(ladder.deadlineMs())
        ladder.onBuffering(100 * s)
        assertEquals(110 * s, ladder.deadlineMs())
        ladder.onReady(105 * s) // recovered on its own
        assertNull(ladder.deadlineMs())
        ladder.onBuffering(200 * s)
        assertEquals(LadderAction.Reload, ladder.onTick(210 * s).action)
        assertEquals(220 * s, ladder.deadlineMs())
        assertEquals(LadderAction.Play(1), ladder.onTick(220 * s).action)
        assertEquals(230 * s, ladder.deadlineMs())
    }

    @Test
    fun `a live stream ending is a failure`() {
        val ladder = LiveTuneLadder(1, 0L)
        ladder.start(0L)
        ladder.onReady(1 * s)
        assertEquals(LadderAction.Reload, ladder.onEnded(50 * s).action)
        assertEquals(
            LadderAction.Unavailable(LiveFailure(FailureKind.STALL)),
            ladder.onEnded(51 * s).action
        )
    }

    @Test
    fun `every stream failing fast ends well inside the budget`() {
        val ladder = LiveTuneLadder(3, 0L)
        ladder.start(0L)
        ladder.onError(dead, false, 1 * s)
        ladder.onError(LiveFailure(FailureKind.BLOCKED, 403), false, 2 * s)
        val step = ladder.onError(dead, false, 3 * s)
        assertEquals(LadderAction.Unavailable(dead), step.action)
        assertFalse(step.abandoned == null)
    }

    @Test
    fun `a behind-live-window error after a long play recovers instead of hitting the tune budget`() {
        val ladder = LiveTuneLadder(2, 0L)
        ladder.start(0L)
        ladder.onReady(2 * s)
        assertEquals(LadderAction.SeekToDefault, ladder.onError(network, true, 600 * s).action)
        assertEquals(610 * s, ladder.deadlineMs())
        assertEquals(LadderAction.None, ladder.onTick(605 * s).action)
        assertEquals(LadderAction.None, ladder.onReady(606 * s).action)
        assertNull(ladder.deadlineMs())
    }

    @Test
    fun `a stream that reaches READY and fails at once still runs out of rungs`() {
        val ladder = LiveTuneLadder(2, 0L)
        ladder.start(0L)
        var now = 1 * s
        var step: LadderStep
        var actions = 0
        do {
            ladder.onReady(now)
            now += 2 * s
            step = ladder.onError(network, behindLiveWindow = true, nowMs = now)
            actions++
        } while (step.action !is LadderAction.Unavailable && actions < 50)
        assertEquals(LadderAction.Unavailable(network), step.action)
        // Per stream: two seeks, one reload, then the next stream (2 streams x 4 failures).
        assertEquals(8, actions)
    }

    @Test
    fun `a sustained play gets a reload even for a parse failure`() {
        val ladder = LiveTuneLadder(1, 0L)
        ladder.start(0L)
        ladder.onReady(1 * s)
        val parse = LiveFailure(FailureKind.PARSE)
        assertEquals(LadderAction.Reload, ladder.onError(parse, false, 3_600 * s).action)
        assertEquals(LadderAction.Unavailable(parse), ladder.onError(parse, false, 3_601 * s).action)
    }

    @Test
    fun `a recoverable failure past the budget gives up instead of reloading`() {
        val ladder = LiveTuneLadder(3, 0L)
        ladder.start(0L)
        val step = ladder.onError(network, false, 31 * s)
        assertEquals(LadderAction.Unavailable(network), step.action)
        assertEquals(0, step.abandoned)
    }

    @Test
    fun `events after unavailable change nothing`() {
        val ladder = LiveTuneLadder(1, 0L)
        ladder.start(0L)
        ladder.onError(dead, false, 1 * s)
        assertTrue(ladder.isDone)
        assertEquals(LadderAction.None, ladder.onReady(2 * s).action)
        assertEquals(LadderAction.None, ladder.onBuffering(3 * s).action)
        assertEquals(LadderAction.None, ladder.onEnded(4 * s).action)
        assertEquals(LadderAction.None, ladder.onTick(100 * s).action)
        assertNull(ladder.deadlineMs())
    }

    // JVM unit test: android.net.Uri is stubbed; a relaxed mock satisfies DataSpec.
    private val testUri: android.net.Uri = mockk(relaxed = true)

    private fun playbackError(code: Int, cause: Throwable? = null) = PlaybackException("x", cause, code)

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(testUri), ByteArray(0))

    @Test
    fun `player errors classify into failure kinds`() {
        val io = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(LiveFailure(FailureKind.DEAD, 404) to false, LiveTuneLadder.classify(playbackError(io, http(404))))
        assertEquals(LiveFailure(FailureKind.BLOCKED, 451) to false, LiveTuneLadder.classify(playbackError(io, http(451))))
        assertEquals(LiveFailure(FailureKind.BLOCKED, 403) to false, LiveTuneLadder.classify(playbackError(io, http(403))))
        assertEquals(LiveFailure(FailureKind.NETWORK, 503) to false, LiveTuneLadder.classify(playbackError(io, http(503))))
        assertEquals(
            LiveFailure(FailureKind.STALL) to true,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        )
        assertEquals(
            LiveFailure(FailureKind.NETWORK) to false,
            LiveTuneLadder.classify(
                playbackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException(UnknownHostException("h")))
            )
        )
        assertEquals(
            LiveFailure(FailureKind.PARSE) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        )
        assertEquals(
            LiveFailure(FailureKind.PARSE) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        )
        assertEquals(
            LiveFailure(FailureKind.NETWORK) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        )
        assertEquals(
            LiveFailure(FailureKind.DRM, drm = DrmFailure.NOT_ACCEPTED) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
        )
    }

    @Test
    fun `a licence failure is permanent - the next stream, no reload`() {
        val ladder = LiveTuneLadder(2, 0)
        ladder.start(0)
        val step = ladder.onError(LiveFailure(FailureKind.DRM, 403, drm = DrmFailure.REFUSED), false, 1_000)
        assertEquals(LadderAction.Play(1), step.action)
    }

    @Test
    fun `licence failures classify by who refused and how (Spec 74 D5)`() {
        val licence = PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        // The licence server's 403 is a refused licence, not a blocked stream.
        assertEquals(
            LiveFailure(FailureKind.DRM, 403, drm = DrmFailure.REFUSED) to false,
            LiveTuneLadder.classify(playbackError(licence, IOException(http(403))))
        )
        assertEquals(
            LiveFailure(FailureKind.DRM, drm = DrmFailure.UNSUPPORTED) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED))
        )
        // 6001 wins over any HTTP cause.
        assertEquals(
            LiveFailure(FailureKind.DRM, drm = DrmFailure.UNSUPPORTED) to false,
            LiveTuneLadder.classify(playbackError(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, http(403)))
        )
        // A busy or throttled licence server may heal: a network failure, as on a clear stream.
        for (code in listOf(503, 500, 429, 408)) {
            assertEquals(LiveFailure(FailureKind.NETWORK, code) to false, LiveTuneLadder.classify(playbackError(licence, IOException(http(code)))))
        }
        assertEquals(
            LiveFailure(FailureKind.DRM, 401, drm = DrmFailure.REFUSED) to false,
            LiveTuneLadder.classify(playbackError(licence, IOException(http(401))))
        )
        // No answer from the licence server: a network failure, like any other.
        assertEquals(
            LiveFailure(FailureKind.NETWORK) to false,
            LiveTuneLadder.classify(playbackError(licence, IOException(UnknownHostException("lic"))))
        )
        assertEquals(
            LiveFailure(FailureKind.NETWORK) to false,
            LiveTuneLadder.classify(
                playbackError(licence, HttpDataSource.HttpDataSourceException(DataSpec(testUri), 0, HttpDataSource.HttpDataSourceException.TYPE_OPEN))
            )
        )
        for (code in listOf(
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
        )) {
            assertEquals(LiveFailure(FailureKind.DRM, drm = DrmFailure.NOT_ACCEPTED) to false, LiveTuneLadder.classify(playbackError(code)))
        }
    }
}
