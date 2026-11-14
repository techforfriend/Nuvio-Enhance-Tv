package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import com.google.common.collect.ImmutableList
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.ErrorTraits
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.Route
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.Rung
import com.nuvio.tv.core.player.PlayerErrorRecoveryPolicy.StreamState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Spec 21 P1: the golden route table for `onPlayerError` (rung order as it stood inline in
 * PlayerRuntimeControllerInitialization before the extraction) plus the error classification.
 */
class PlayerErrorRecoveryPolicyTest {

    private val plain = ErrorTraits(
        retryable = false,
        dolbyVisionDecoderFailure = false,
        runtimeCheckFault = false,
        decodingFailed = false,
        audioRendererFailed = false,
        audioTrackFailure = false,
        stuckPlayingNoProgress = false,
        rangeNotSatisfiable = false,
        deadSourceHttp = false,
        containerCorruption = false,
        videoDecoderUnsupported = false,
        unrecognizedContainer = false,
        behindLiveWindow = false,
        linkRefusedHttp = false,
        serverErrorHttp = false,
    )

    private val fresh = StreamState(
        inBackground = false,
        mapDv7ToHevcActive = false,
        manualDv81Mode2Active = false,
        experimentalDv7ToDv81Active = false,
        dv7Mode1AlreadyForced = false,
        rangeNotSatisfiableRetried = false,
        renderedFirstFrame = false,
        mpvHopAvailable = false,
        liveEdgeFixAvailable = true,
        linkRefreshAvailable = true,
    )

    private val audioHead = listOf(Rung.DENIED_AUDIO_FFMPEG, Rung.AUDIO_TRACK_PCM)
    private val sourceTail = listOf(Rung.PARSING_PROBE, Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER)
    private val defaultLadder = audioHead + sourceTail

    private data class Row(val name: String, val error: ErrorTraits, val state: StreamState, val expected: Route)

    private fun ladder(vararg rungs: Rung) = Route.Ladder(rungs.toList())
    private fun ladder(rungs: List<Rung>) = Route.Ladder(rungs)

    private val dvConversion = fresh.copy(experimentalDv7ToDv81Active = true)
    private val mode2 = fresh.copy(manualDv81Mode2Active = true)
    private val dvVideoFailure = plain.copy(dolbyVisionDecoderFailure = true, decodingFailed = true, retryable = true)

    // A message-matched DV failure or an 8000 carries no renderer format, so it also reads as an
    // audio-renderer failure: these rows pin DV precedence over AUDIO_LADDER_RENDERER_FAULT.
    private val dvNoFormat = dvVideoFailure.copy(audioRendererFailed = true)
    private val runtimeCheck = plain.copy(runtimeCheckFault = true, audioRendererFailed = true)

    // Spec 85 G5: the mpv hop's two triggers (retryable as 4001/3003 are) and an open gate.
    private val noVideoDecoder = plain.copy(videoDecoderUnsupported = true, retryable = true)
    private val unrecognized = plain.copy(unrecognizedContainer = true, retryable = true)
    private val hopOpen = fresh.copy(mpvHopAvailable = true)

    private val behindLive = plain.copy(behindLiveWindow = true)

    // Spec 86 R2/D2: a refused link (400/401/403/410) and a server error (429/5xx, retryable).
    private val refused = plain.copy(linkRefusedHttp = true)
    private val gone = plain.copy(linkRefusedHttp = true, deadSourceHttp = true)
    private val serverError = plain.copy(serverErrorHttp = true, retryable = true)
    private val midPlay = fresh.copy(renderedFirstFrame = true)

    private val table: List<Row> = listOf(
        // Background: only a retryable error defers; nothing else runs.
        Row("background retryable", plain.copy(retryable = true), fresh.copy(inBackground = true), Route.Background),
        Row("background fatal", plain, fresh.copy(inBackground = true), ladder(defaultLadder)),
        Row("foreground retryable", plain.copy(retryable = true), fresh, ladder(defaultLadder)),
        // DV decoder failure: mode 2 -> mode 1 once, else HDR10; nothing after it.
        Row("dv decoder", dvVideoFailure, fresh, ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)),
        Row("dv decoder mode2", dvVideoFailure, mode2, ladder(Rung.DV_MODE1_AFTER_DECODER_FAILURE)),
        Row("dv decoder without renderer format", dvNoFormat, fresh, ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)),
        Row("dv decoder without renderer format mode2", dvNoFormat, mode2, ladder(Rung.DV_MODE1_AFTER_DECODER_FAILURE)),
        Row(
            "dv decoder mode2 mode1 spent", dvVideoFailure, mode2.copy(dv7Mode1AlreadyForced = true),
            ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)
        ),
        Row(
            "dv decoder wins over audio track", dvVideoFailure.copy(audioTrackFailure = true), fresh,
            ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)
        ),
        Row("dv decoder under map-to-HEVC", dvVideoFailure, fresh.copy(mapDv7ToHevcActive = true), ladder(defaultLadder)),
        // 8000 with DV conversion armed: the DV ladder, not the audio one.
        Row("runtime check dv conversion", runtimeCheck, dvConversion, ladder(Rung.DV_HDR10_AFTER_RUNTIME_CHECK)),
        Row("runtime check mode2", runtimeCheck, mode2, ladder(Rung.DV_MODE1_AFTER_RUNTIME_CHECK)),
        Row(
            "runtime check mode2 mode1 spent", runtimeCheck, mode2.copy(dv7Mode1AlreadyForced = true),
            ladder(Rung.DV_HDR10_AFTER_RUNTIME_CHECK)
        ),
        Row(
            "runtime check no conversion", runtimeCheck, fresh,
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        Row(
            "runtime check under map-to-HEVC", runtimeCheck,
            dvConversion.copy(mapDv7ToHevcActive = true),
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        // Audio review F5: a video decoder crash skips the audio ladder.
        Row("video decoding failed", plain.copy(decodingFailed = true, retryable = true), fresh, ladder(defaultLadder)),
        Row(
            "audio decoding failed", plain.copy(decodingFailed = true, audioRendererFailed = true, retryable = true), fresh,
            ladder(listOf(Rung.AUDIO_LADDER_RENDERER_FAULT) + defaultLadder)
        ),
        Row("audio renderer, other code", plain.copy(audioRendererFailed = true), fresh, ladder(defaultLadder)),
        Row(
            "audio track failure", plain.copy(audioTrackFailure = true, audioRendererFailed = true), fresh,
            ladder(audioHead + Rung.AUDIO_LADDER_AUDIO_TRACK + sourceTail)
        ),
        Row(
            "stuck playing", plain.copy(stuckPlayingNoProgress = true, audioRendererFailed = true), fresh,
            ladder(audioHead + Rung.AUDIO_LADDER_STUCK_NO_PROGRESS + sourceTail)
        ),
        // The one same-URL retry with a one-shot latch ends the list.
        Row("416", plain.copy(rangeNotSatisfiable = true, retryable = true), fresh, ladder(audioHead + Rung.RANGE_416_FROM_START)),
        Row(
            "416 spent", plain.copy(rangeNotSatisfiable = true, retryable = true), fresh.copy(rangeNotSatisfiableRetried = true),
            ladder(defaultLadder)
        ),
        // Source-level rungs.
        Row(
            "dead source http", plain.copy(deadSourceHttp = true), fresh,
            ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER + sourceTail)
        ),
        Row("corruption before first frame", plain.copy(containerCorruption = true, retryable = true), fresh, ladder(defaultLadder)),
        Row(
            "corruption mid-play", plain.copy(containerCorruption = true, retryable = true), fresh.copy(renderedFirstFrame = true),
            ladder(
                audioHead + listOf(
                    Rung.PARSING_PROBE, Rung.MID_PLAY_CORRUPT_FAILOVER,
                    Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER,
                )
            )
        ),
        Row(
            "dead source and mid-play corruption", plain.copy(deadSourceHttp = true, containerCorruption = true),
            fresh.copy(renderedFirstFrame = true),
            ladder(
                audioHead + listOf(
                    Rung.DEAD_SOURCE_HTTP_FAILOVER, Rung.PARSING_PROBE,
                    Rung.MID_PLAY_CORRUPT_FAILOVER, Rung.AUTO_RETRY, Rung.STARTUP_EXHAUSTED_FAILOVER,
                )
            )
        ),
        // Spec 85 G5: no video decoder hops after the same-link retries, before the next source.
        Row(
            "no video decoder, hop open", noVideoDecoder, hopOpen,
            ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.AUTO_RETRY, Rung.MPV_FORMAT_HOP))
        ),
        Row("no video decoder, hop closed", noVideoDecoder, fresh, ladder(defaultLadder)),
        // An unrecognized container hops right after the parsing probe (spent: its tail hops itself).
        Row(
            "unrecognized container, hop open", unrecognized, hopOpen,
            ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.MPV_FORMAT_HOP))
        ),
        Row("unrecognized container, hop closed", unrecognized, fresh, ladder(defaultLadder)),
        // An open gate alone never hops: 4003 (a decoder that started), network and HTTP errors.
        Row("video decoding failed, hop open", plain.copy(decodingFailed = true, retryable = true), hopOpen, ladder(defaultLadder)),
        Row("retryable network error, hop open", plain.copy(retryable = true), hopOpen, ladder(defaultLadder)),
        Row(
            "dead source http, hop open", plain.copy(deadSourceHttp = true), hopOpen,
            ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER + sourceTail)
        ),
        // The background deferral comes before the hop, even with the gate's other terms open.
        Row("no video decoder in background", noVideoDecoder, hopOpen.copy(inBackground = true), Route.Background),
        // A DV decoder failure keeps the DV ladder (D1); under map-to-HEVC it takes the generic rungs.
        Row("dv decoder, hop open", dvVideoFailure, hopOpen, ladder(Rung.DV_HDR10_AFTER_DECODER_FAILURE)),
        Row("dv decoder under map-to-HEVC, hop open", dvVideoFailure, hopOpen.copy(mapDv7ToHevcActive = true), ladder(defaultLadder)),
        // Spec 86 R6: behind the live window re-anchors in place, before the budget and ladder, twice.
        Row("behind live window", behindLive, fresh, Route.LiveEdge),
        Row("behind live window mid-play", behindLive, fresh.copy(renderedFirstFrame = true), Route.LiveEdge),
        // 1002 is not retryable, so the background deferral never takes it.
        Row("behind live window in background", behindLive, fresh.copy(inBackground = true), Route.LiveEdge),
        Row("behind live window, fixes spent", behindLive, fresh.copy(liveEdgeFixAvailable = false), ladder(defaultLadder)),
        Row("other error, fixes spent", plain, fresh.copy(liveEdgeFixAvailable = false), ladder(defaultLadder)),
        // Spec 86 R2: mid-play, a refused link is refreshed once (ends the list), then fails over.
        Row("refused link mid-play", refused, midPlay, ladder(audioHead + Rung.MID_PLAY_LINK_REFRESH)),
        Row(
            "refused link mid-play, refresh spent", refused, midPlay.copy(linkRefreshAvailable = false),
            ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER)
        ),
        Row("410 mid-play", gone, midPlay, ladder(audioHead + Rung.MID_PLAY_LINK_REFRESH)),
        Row(
            "410 mid-play, refresh spent", gone, midPlay.copy(linkRefreshAvailable = false),
            ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER)
        ),
        // Before the first frame nothing changes: 400/401/403 take the startup ladder, 410 the dead-source arm.
        Row("refused link at startup", refused, fresh, ladder(defaultLadder)),
        Row("410 at startup", gone, fresh, ladder(audioHead + Rung.DEAD_SOURCE_HTTP_FAILOVER + sourceTail)),
        // Spec 86 D2: a mid-play 5xx / 429 storm fails over after the same-link retries.
        Row(
            "server error mid-play", serverError, midPlay,
            ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.AUTO_RETRY, Rung.MID_PLAY_SERVER_FAILOVER, Rung.STARTUP_EXHAUSTED_FAILOVER))
        ),
        Row("server error at startup", serverError, fresh, ladder(defaultLadder)),
        // A dropped connection (no HTTP status) and a decode error mid-play keep today's rungs.
        Row("network drop mid-play", plain.copy(retryable = true), midPlay, ladder(defaultLadder)),
        Row("video decoding failed mid-play", plain.copy(decodingFailed = true, retryable = true), midPlay, ladder(defaultLadder)),
    )

    @Test
    fun `golden route table`() {
        table.forEach { row ->
            assertEquals(row.name, row.expected, PlayerErrorRecoveryPolicy.route(row.error, row.state))
        }
    }

    @Test
    fun `the table reaches every rung`() {
        val reached = table.flatMap { (it.expected as? Route.Ladder)?.rungs.orEmpty() }.toSet()
        assertEquals(Rung.entries.toSet(), reached)
    }

    @Test
    fun `a rung that changes a snapshotted fact ends the list`() {
        // The snapshot contract in the policy KDoc: these rungs mutate route inputs, so nothing may follow them.
        val mutating = setOf(
            Rung.DV_MODE1_AFTER_DECODER_FAILURE, Rung.DV_MODE1_AFTER_RUNTIME_CHECK,
            Rung.RANGE_416_FROM_START, Rung.MPV_FORMAT_HOP, Rung.MID_PLAY_LINK_REFRESH,
        )
        table.mapNotNull { it.expected as? Route.Ladder }.forEach { route ->
            route.rungs.dropLast(1).forEach { assertFalse("$it before the end of ${route.rungs}", it in mutating) }
        }
    }

    // --- classify ---

    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, byteArrayOf())

    private fun renderer(
        mime: String,
        code: Int,
        cause: Throwable = IllegalStateException("codec"),
        codecs: String? = null,
    ) = ExoPlaybackException.createForRenderer(
        cause, "renderer", 0, Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build(),
        C.FORMAT_HANDLED, null, false, code
    )

    private fun classify(error: PlaybackException) = PlayerErrorRecoveryPolicy.classify(error)

    @Test
    fun `DV decoder failure by message, by init MIME and by init codec name`() {
        assertTrue(
            classify(
                PlaybackException(
                    "Decoder failed: c2.dolby-vision.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED
                )
            ).dolbyVisionDecoderFailure
        )
        assertTrue(
            classify(renderer(MimeTypes.VIDEO_DOLBY_VISION, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertTrue(
            classify(
                PlaybackException("Decoder init failed: dvhe.08", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
            ).dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                .dolbyVisionDecoderFailure
        )
        // A Dolby audio decoder's init failure names "dolby" but is not a DV video failure.
        assertFalse(
            classify(
                renderer(
                    MimeTypes.AUDIO_E_AC3_JOC, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    IllegalStateException("c2.dolby.eac3-joc.decoder")
                )
            ).dolbyVisionDecoderFailure
        )
        // DECODING_FAILED needs both phrases; another code never matches by message.
        assertFalse(
            classify(PlaybackException("c2.dolby-vision.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(PlaybackException("Decoder failed: c2.hevc.decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
                .dolbyVisionDecoderFailure
        )
        assertFalse(
            classify(PlaybackException("Decoder failed: c2.dolby-vision", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                .dolbyVisionDecoderFailure
        )
    }

    @Test
    fun `audio renderer failure reads the renderer format, absent format counts as audio`() {
        val video = classify(renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(video.decodingFailed)
        assertFalse(video.audioRendererFailed)
        assertTrue(classify(renderer(MimeTypes.AUDIO_TRUEHD, PlaybackException.ERROR_CODE_DECODING_FAILED)).audioRendererFailed)
        assertTrue(
            classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_DECODING_FAILED)).audioRendererFailed
        )
    }

    @Test
    fun `runtime check fault excludes the stuck-buffering watchdog`() {
        val code = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
        assertTrue(classify(PlaybackException("x", IllegalStateException("assertion"), code)).runtimeCheckFault)
        assertFalse(
            classify(PlaybackException("x", IllegalStateException("Playback stuck buffering and not loading"), code))
                .runtimeCheckFault
        )
    }

    @Test
    fun `audio track and stuck-playing classifications`() {
        assertTrue(classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)).audioTrackFailure)
        assertTrue(
            classify(PlaybackException("x", IllegalStateException("Playback stuck playing with no progress"), PlaybackException.ERROR_CODE_TIMEOUT))
                .stuckPlayingNoProgress
        )
        assertFalse(
            classify(PlaybackException("x", IllegalStateException("Playback stuck playing with no progress"), PlaybackException.ERROR_CODE_UNSPECIFIED))
                .stuckPlayingNoProgress
        )
    }

    @Test
    fun `timeout, loader NPE and MediaPeriodHolder errors take the generic rungs (P8)`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        fun routeOf(error: PlaybackException, state: StreamState) =
            PlayerErrorRecoveryPolicy.route(classify(error), state)
        // A socket timeout (2002): no same-URL timeout retry ahead of the auto-retry and failover.
        val timeout = PlaybackException(
            "x", IOException("read", SocketTimeoutException("t")), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertEquals(ladder(defaultLadder), routeOf(timeout, fresh))
        // Mid-play too: a timeout is a network fault, not corruption (AUTO_RETRY, not a source switch).
        assertEquals(ladder(defaultLadder), routeOf(timeout, fresh.copy(renderedFirstFrame = true)))
        val timeoutUnder2000 = PlaybackException("x", IOException("read", SocketTimeoutException("t")), io)
        assertFalse(classify(timeoutUnder2000).containerCorruption)
        // A Matroska NPE (2000, no connection cause) mid-play is container corruption: next source.
        val npe = PlaybackException("x", IllegalStateException("NullPointerException in MatroskaExtractor.readBlock"), io)
        assertEquals(
            ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.MID_PLAY_CORRUPT_FAILOVER) + Rung.AUTO_RETRY + Rung.STARTUP_EXHAUSTED_FAILOVER),
            routeOf(npe, fresh.copy(renderedFirstFrame = true))
        )
        assertEquals(ladder(defaultLadder), routeOf(npe, fresh))
        val mediaPeriodHolder = PlaybackException(
            "x", IllegalStateException("MediaPeriodHolder.info is null"), PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        assertEquals(ladder(defaultLadder), routeOf(mediaPeriodHolder, fresh))
    }

    @Test
    fun `416 is read from the direct cause, 404 and 410 from the whole chain`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertTrue(classify(PlaybackException("x", http(416), bad)).rangeNotSatisfiable)
        assertFalse(classify(PlaybackException("x", IOException("wrap", http(416)), bad)).rangeNotSatisfiable)
        assertTrue(classify(PlaybackException("x", IOException("wrap", http(404)), bad)).deadSourceHttp)
        assertTrue(classify(PlaybackException("x", http(410), bad)).deadSourceHttp)
        assertFalse(classify(PlaybackException("x", http(429), bad)).deadSourceHttp)
    }

    @Test
    fun `refused link is 400, 401, 403 or 410 and server error is 429 or 5xx, from the whole chain`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        listOf(400, 401, 403, 410).forEach { code ->
            assertTrue("$code", classify(PlaybackException("x", IOException("wrap", http(code)), bad)).linkRefusedHttp)
            assertFalse("$code", classify(PlaybackException("x", http(code), bad)).serverErrorHttp)
        }
        listOf(429, 500, 502, 503, 599).forEach { code ->
            assertTrue("$code", classify(PlaybackException("x", http(code), bad)).serverErrorHttp)
            assertFalse("$code", classify(PlaybackException("x", http(code), bad)).linkRefusedHttp)
        }
        listOf(404, 416).forEach { code ->
            val traits = classify(PlaybackException("x", http(code), bad))
            assertFalse("$code", traits.linkRefusedHttp || traits.serverErrorHttp)
        }
        // The PRDS shape: a chunk's status wrapped in plain IOExceptions under code 2000 (Spec 86 D2):
        // a server error, not corruption; mid-play it keeps the same-link retries, then fails over.
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        fun prds(code: Int) = PlaybackException("x", IOException("chunk", IOException("rate-limited", http(code))), io)
        listOf(429, 503, 502).forEach { code ->
            val traits = classify(prds(code))
            assertTrue("$code", traits.serverErrorHttp)
            assertFalse("$code", traits.containerCorruption)
            assertEquals(
                "$code",
                ladder(audioHead + listOf(Rung.PARSING_PROBE, Rung.AUTO_RETRY, Rung.MID_PLAY_SERVER_FAILOVER, Rung.STARTUP_EXHAUSTED_FAILOVER)),
                PlayerErrorRecoveryPolicy.route(traits, midPlay)
            )
        }
        assertEquals(ladder(audioHead + Rung.MID_PLAY_LINK_REFRESH), PlayerErrorRecoveryPolicy.route(classify(prds(403)), midPlay))
        // A plain 2000 with no status stays corruption.
        assertTrue(classify(PlaybackException("x", IOException("short chunk"), io)).containerCorruption)
        // No HTTP status at all (a dropped connection): neither.
        val drop = classify(PlaybackException("x", SocketException("reset"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(drop.linkRefusedHttp || drop.serverErrorHttp)
    }

    @Test
    fun `video decoder unsupported is 4001, 4002, 4004 or 4005 on a video format that is not Dolby Vision`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach { code ->
            assertTrue("$code", classify(renderer(MimeTypes.VIDEO_H264, code)).videoDecoderUnsupported)
        }
        // 4003: the decoder started (D1 leaves it out).
        assertFalse(classify(renderer(MimeTypes.VIDEO_H264, PlaybackException.ERROR_CODE_DECODING_FAILED)).videoDecoderUnsupported)
        val init = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        assertFalse(classify(renderer(MimeTypes.AUDIO_TRUEHD, init)).videoDecoderUnsupported)
        assertFalse(classify(PlaybackException("x", null, init)).videoDecoderUnsupported)
        // Dolby Vision by mime or by codec string under an HEVC mime (D1).
        assertFalse(classify(renderer(MimeTypes.VIDEO_DOLBY_VISION, init)).videoDecoderUnsupported)
        assertFalse(classify(renderer(MimeTypes.VIDEO_H265, init, codecs = "dvh1.08.06")).videoDecoderUnsupported)
        assertTrue(classify(renderer(MimeTypes.VIDEO_H265, init, codecs = "hvc1.2.4.L153.B0")).videoDecoderUnsupported)
        // A DV decoder named only in the message (HEVC mime, plain codecs) is the DV verdict, not this.
        val dvByName = classify(renderer(MimeTypes.VIDEO_H265, init, IllegalStateException("c2.dolby.hevc.decoder")))
        assertTrue(dvByName.dolbyVisionDecoderFailure)
        assertFalse(dvByName.videoDecoderUnsupported)
    }

    @Test
    fun `unrecognized container is 3003 with UnrecognizedInputFormatException in the chain`() {
        val unsupported = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        fun sniffFailure() = UnrecognizedInputFormatException("no extractor", mockk<Uri>(relaxed = true), ImmutableList.of())
        assertTrue(classify(PlaybackException("x", sniffFailure(), unsupported)).unrecognizedContainer)
        assertTrue(classify(PlaybackException("x", IOException("wrap", sniffFailure()), unsupported)).unrecognizedContainer)
        assertFalse(classify(PlaybackException("x", IOException("other"), unsupported)).unrecognizedContainer)
        assertFalse(
            classify(PlaybackException("x", sniffFailure(), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
                .unrecognizedContainer
        )
    }

    @Test
    fun `behind live window is 1002 only`() {
        assertTrue(classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)).behindLiveWindow)
        assertFalse(classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).behindLiveWindow)
        assertFalse(classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)).retryable)
    }

    @Test
    fun `container corruption is 3001, or 2000 without a connection-level cause`() {
        assertTrue(
            classify(PlaybackException("x", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)).containerCorruption
        )
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertTrue(classify(PlaybackException("x", IOException("short chunk"), io)).containerCorruption)
        assertFalse(classify(PlaybackException("x", SocketException("reset"), io)).containerCorruption)
    }
}
