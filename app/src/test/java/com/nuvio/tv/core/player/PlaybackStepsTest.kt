package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.StepResult.Status
import com.nuvio.tv.core.stream.FailureCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 75 S3: the step line's times and the step each cause fails at. */
class PlaybackStepsTest {

    private fun statuses(line: StepLine) = line.steps.map { it.status }
    private fun times(line: StepLine) = line.steps.map { it.durationMs }

    @Test
    fun `an expired link fails at Connecting with the mockup's times`() {
        // 1.4 s search, 0.6 s to the link, 0.3 s until the 410.
        val marks = StepMarks(startedAtMs = 1_000, sourcesMs = 1_400, linkAtMs = 1_600, connectAtMs = 1_900)
        val line = PlaybackSteps.line(marks, PlaybackSteps.stepOf(FailureCause.LINK_EXPIRED)!!, nowMs = 2_000)
        assertEquals(PlaybackStep.CONNECT, line.failed)
        assertEquals(
            listOf(Status.PASSED, Status.PASSED, Status.FAILED, Status.NOT_REACHED, Status.NOT_REACHED, Status.NOT_REACHED),
            statuses(line)
        )
        assertEquals(listOf(1_400L, 600L, 300L, null, null, null), times(line))
    }

    @Test
    fun `a failed step that never ended runs to the failure`() {
        // Too slow: the file's tracks never arrived; 20 s from the answer to the watchdog.
        val marks = StepMarks(startedAtMs = 0, sourcesMs = 1_300, linkAtMs = 400, connectAtMs = 700)
        val line = PlaybackSteps.line(marks, PlaybackStep.READ, nowMs = 20_700)
        assertEquals(listOf(1_300L, 400L, 300L, 20_000L, null, null), times(line))
    }

    @Test
    fun `unmeasured steps pass without a time and the failed one counts from the last end`() {
        // No scrape (cached link), no HTTP events (MPV): the decoder hangs after the tracks.
        val marks = StepMarks(startedAtMs = 0, tracksAtMs = 900)
        val line = PlaybackSteps.line(marks, PlaybackStep.PICTURE, nowMs = 12_900)
        assertEquals(listOf(null, null, null, null, 12_000L, null), times(line))
        assertEquals(Status.PASSED, line.steps[0].status)
        assertEquals(Status.NOT_REACHED, line.steps[5].status)
        // A gap: no connect mark, so Reading the file counts from the link.
        val gap = PlaybackSteps.line(StepMarks(startedAtMs = 0, linkAtMs = 500), PlaybackStep.READ, nowMs = 3_500)
        assertEquals(3_000L, gap.steps[3].durationMs)
        assertNull(gap.steps[2].durationMs)
    }

    @Test
    fun `a failure after the picture started marks its cause's step with the measured times`() {
        val marks = StepMarks(0, 1_000, 500, 700, 1_600, 2_400, 2_000)
        val line = PlaybackSteps.line(marks, PlaybackStep.READ, nowMs = 600_000)
        assertEquals(listOf(1_000L, 500L, 200L, 900L, null, null), times(line))
        val sound = PlaybackSteps.line(marks, PlaybackStep.SOUND, nowMs = 600_000)
        assertEquals(listOf(1_000L, 500L, 200L, 900L, 800L, 400L), times(sound))
    }

    @Test
    fun `after the first frame a step that never ended is red with no time`() {
        // MPV 40 min in, the audio output never marked: not "40 min" under Sound (D4).
        val marks = StepMarks(0, 1_000, 500, 700, 1_600, 2_400, soundAtMs = null)
        val line = PlaybackSteps.line(marks, PlaybackStep.SOUND, nowMs = 2_400_000)
        assertEquals(StepResult(PlaybackStep.SOUND, Status.FAILED, null), line.steps[5])
        assertEquals(800L, line.steps[4].durationMs)
    }

    @Test
    fun `causes map to the step of the approved table, engine faults to the step reached`() {
        assertEquals(PlaybackStep.CONNECT, PlaybackSteps.stepOf(FailureCause.NO_CONNECTION))
        assertEquals(PlaybackStep.READ, PlaybackSteps.stepOf(FailureCause.WEB_PAGE))
        assertEquals(PlaybackStep.READ, PlaybackSteps.stepOf(FailureCause.PLACEHOLDER))
        assertEquals(PlaybackStep.PICTURE, PlaybackSteps.stepOf(FailureCause.FROZEN))
        assertEquals(PlaybackStep.SOUND, PlaybackSteps.stepOf(FailureCause.AUDIO_FAILED))
        assertEquals(PlaybackStep.LINK, PlaybackSteps.stepOf(FailureCause.TORRENT))
        assertNull(PlaybackSteps.stepOf(FailureCause.ENGINE))
        assertNull(PlaybackSteps.stepOf(FailureCause.UNKNOWN))
        assertEquals(PlaybackStep.LINK, PlaybackSteps.reachedStep(StepMarks(0)))
        assertEquals(PlaybackStep.CONNECT, PlaybackSteps.reachedStep(StepMarks(0, linkAtMs = 1)))
        assertEquals(PlaybackStep.READ, PlaybackSteps.reachedStep(StepMarks(0, linkAtMs = 1, connectAtMs = 2)))
        assertEquals(PlaybackStep.PICTURE, PlaybackSteps.reachedStep(StepMarks(0, tracksAtMs = 3)))
        assertEquals(PlaybackStep.PICTURE, PlaybackSteps.reachedStep(StepMarks(0, tracksAtMs = 3, soundAtMs = 4)))
        assertEquals(PlaybackStep.PICTURE, PlaybackSteps.reachedStep(StepMarks(0, linkAtMs = 1, connectAtMs = 2, soundAtMs = 4)))
        assertEquals(PlaybackStep.SOUND, PlaybackSteps.reachedStep(StepMarks(0, tracksAtMs = 3, pictureAtMs = 4)))
    }

    @Test
    fun `the clock follows redirects until the serving host answers`() {
        val clock = PlaybackStepClock()
        clock.start(nowMs = 0, sourcesMs = 1_400, url = null)
        val attempt = clock.attempt
        clock.requestStarted(attempt, 100)
        clock.redirectAnswered(attempt, 500)
        clock.redirectAnswered(attempt, 600)
        clock.servingAnswered(attempt, 900)
        // Later calls (chunks, probes) change nothing.
        clock.requestStarted(attempt, 1_000)
        clock.redirectAnswered(attempt, 1_100)
        clock.servingAnswered(attempt, 1_200)
        clock.tracksRead(1_500)
        clock.tracksRead(1_700)
        clock.pictureShown(2_000)
        clock.soundOpened(1_800)
        assertEquals(StepMarks(0, 1_400, 600, 900, 1_500, 2_000, 1_800), clock.snapshot())
    }

    @Test
    fun `a direct link is known when its first request leaves`() {
        val clock = PlaybackStepClock()
        clock.start(nowMs = 0, sourcesMs = null, url = null)
        clock.requestStarted(clock.attempt, 250)
        clock.requestStarted(clock.attempt, 400)
        clock.servingAnswered(clock.attempt, 700)
        assertEquals(250L, clock.snapshot()!!.linkAtMs)
        assertEquals(700L, clock.snapshot()!!.connectAtMs)
    }

    @Test
    fun `a TorrServer failure on a start-only attempt fails Getting the link after the search`() {
        val line = PlaybackSteps.line(StepMarks(1_000, sourcesMs = 1_400), PlaybackSteps.stepOf(FailureCause.TORRENT)!!, 4_000)
        assertEquals(listOf(1_400L, 3_000L, null, null, null, null), times(line))
        assertEquals(
            listOf(Status.PASSED, Status.FAILED, Status.NOT_REACHED, Status.NOT_REACHED, Status.NOT_REACHED, Status.NOT_REACHED),
            statuses(line)
        )
    }

    @Test
    fun `a serving answer with no link mark leaves Getting the link unmeasured`() {
        val clock = PlaybackStepClock()
        clock.start(nowMs = 0, sourcesMs = null, url = null)
        clock.servingAnswered(clock.attempt, 700)
        assertEquals(StepMarks(0, connectAtMs = 700), clock.snapshot())
        val line = PlaybackSteps.line(clock.snapshot()!!, PlaybackStep.READ, nowMs = 900)
        assertNull(line.steps[1].durationMs)
        assertNull(line.steps[2].durationMs) // never an invented 0 ms Connecting
    }

    @Test
    fun `a call from an earlier attempt never marks the current one`() {
        val clock = PlaybackStepClock()
        assertNull(clock.snapshot())
        clock.tracksRead(5) // before any start: ignored
        clock.start(nowMs = 0, sourcesMs = null, url = null)
        val old = clock.attempt
        clock.start(nowMs = 10_000, sourcesMs = null, url = null)
        clock.requestStarted(old, 10_100)
        clock.servingAnswered(old, 10_200)
        assertEquals(StepMarks(10_000), clock.snapshot())
    }

    @Test
    fun `a sound failure before the first frame leaves picture unreached`() {
        val marks = StepMarks(0, 1_000, 500, 700, 1_600)
        val line = PlaybackSteps.line(marks, PlaybackStep.SOUND, nowMs = 3_600)
        assertEquals(StepResult.Status.NOT_REACHED, line.steps[4].status)
        assertEquals(StepResult(PlaybackStep.SOUND, Status.FAILED, 2_000L), line.steps[5])
    }

    @Test
    fun `out-of-order marks give no time rather than zero`() {
        val marks = StepMarks(startedAtMs = 1_000, linkAtMs = 900, connectAtMs = 1_200)
        val line = PlaybackSteps.line(marks, PlaybackStep.READ, nowMs = 1_100)
        assertNull(line.steps[1].durationMs) // link before the start
        assertEquals(300L, line.steps[2].durationMs)
        assertNull(line.steps[3].durationMs) // the failure before the connect mark
        assertNull(PlaybackSteps.line(StepMarks(0, sourcesMs = -5), PlaybackStep.LINK, 10).steps[0].durationMs)
    }

    @Test
    fun `the playback client's calls mark the attempt of their own url only`() {
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/cdn/file.mkv")
                .setHeadersDelay(200, TimeUnit.MILLISECONDS)
        )
        server.enqueue(MockResponse().setResponseCode(410).setHeadersDelay(150, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))
        server.start()
        val previous = PlaybackConnectionEvents.stepClock
        val clock = PlaybackStepClock()
        try {
            PlaybackConnectionEvents.stepClock = clock
            val streamUrl = server.url("/resolve/abc").toString()
            clock.start(nowMs = android.os.SystemClock.elapsedRealtime(), sourcesMs = null, url = streamUrl)
            val client = OkHttpClient.Builder().eventListenerFactory(PlaybackConnectionEvents).build()
            client.newCall(Request.Builder().url(streamUrl).build()).execute().close()
            val marks = clock.snapshot()!!
            // The link moved to the redirect's answer (200 ms in); the serving host answers 150 ms later.
            assertTrue("$marks", marks.linkAtMs!! - marks.startedAtMs >= 190)
            assertTrue("$marks", marks.connectAtMs!! - marks.linkAtMs!! >= 140)
            // Another URL on the same client (a subtitle) marks nothing.
            clock.start(nowMs = 0, sourcesMs = null, url = streamUrl)
            client.newCall(Request.Builder().url(server.url("/subs.srt")).build()).execute().close()
            assertEquals(StepMarks(0), clock.snapshot())
        } finally {
            PlaybackConnectionEvents.stepClock = previous
            server.shutdown()
            unmockkStatic(android.os.SystemClock::class)
        }
    }

    @Test
    fun `after a prewarm resolved the link, requests to the resolved url count for the stream`() {
        val server = MockWebServer()
        // The prewarm: the stream URL redirects to B.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/resolved.mkv"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        // The player asks B, which redirects again to C (a rotating CDN); then it asks C directly.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn2/x.mkv"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("y"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("z"))
        server.start()
        val previous = PlaybackConnectionEvents.stepClock
        val clock = PlaybackStepClock()
        try {
            PlaybackConnectionEvents.stepClock = clock
            val streamUrl = server.url("/resolve/xyz").toString()
            val client = OkHttpClient.Builder().eventListenerFactory(PlaybackConnectionEvents).build()
            // The press-time prewarm while the previous stream's attempt runs: marks nothing of it,
            // learns the redirects.
            clock.start(nowMs = 0, sourcesMs = null, url = server.url("/previous.mkv").toString())
            client.newCall(Request.Builder().url(streamUrl).build()).execute().close()
            assertEquals(StepMarks(0), clock.snapshot())
            // B counts for the stream.
            clock.start(nowMs = 0, sourcesMs = null, url = streamUrl)
            client.newCall(Request.Builder().url(server.url("/cdn/resolved.mkv")).build()).execute().close()
            assertNotNull(clock.snapshot()!!.linkAtMs)
            assertNotNull(clock.snapshot()!!.connectAtMs)
            // C keeps the chain's first origin (the stream URL), not B.
            clock.start(nowMs = 0, sourcesMs = null, url = streamUrl)
            client.newCall(Request.Builder().url(server.url("/cdn2/x.mkv")).build()).execute().close()
            assertNotNull(clock.snapshot()!!.connectAtMs)
        } finally {
            PlaybackConnectionEvents.stepClock = previous
            server.shutdown()
        }
    }

    @Test
    fun `a call that started before the attempt never marks it, even when it answers after`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setBody("x").setHeadersDelay(300, TimeUnit.MILLISECONDS))
        server.start()
        val previous = PlaybackConnectionEvents.stepClock
        val clock = PlaybackStepClock()
        try {
            PlaybackConnectionEvents.stepClock = clock
            val streamUrl = server.url("/late/stream.mkv").toString()
            val client = OkHttpClient.Builder().eventListenerFactory(PlaybackConnectionEvents).build()
            // A press-time prewarm of the stream leaves while the previous stream's attempt runs ...
            clock.start(nowMs = 0, sourcesMs = null, url = server.url("/previous.mkv").toString())
            val done = java.util.concurrent.CountDownLatch(1)
            client.newCall(Request.Builder().url(streamUrl).build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = done.countDown()
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                    done.countDown()
                }
            })
            server.takeRequest(5, TimeUnit.SECONDS) // the call has left
            // ... and the stream's own attempt starts before its headers answer.
            clock.start(nowMs = 10_000, sourcesMs = null, url = streamUrl)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(StepMarks(10_000), clock.snapshot())
        } finally {
            PlaybackConnectionEvents.stepClock = previous
            server.shutdown()
        }
    }

    @Test
    fun `the route's sources time parses, empty or negative is none`() {
        fun parse(value: String?) = PlayerNavigationArgs.from(
            SavedStateHandle(if (value == null) emptyMap() else mapOf("sourcesFoundMs" to value))
        ).sourcesFoundMs
        assertEquals(1_400L, parse("1400"))
        assertNull(parse(""))
        assertNull(parse("-3"))
        assertNull(parse("x"))
        assertNull(parse(null))
    }
}
