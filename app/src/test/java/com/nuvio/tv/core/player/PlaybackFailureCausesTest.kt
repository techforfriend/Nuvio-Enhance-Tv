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
import io.mockk.mockk
import com.nuvio.tv.core.player.PlaybackFailureCauses.Renderer
import com.nuvio.tv.core.player.PlayerStartupWatchdogPolicy.FireReason
import com.nuvio.tv.core.stream.FailureCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 75 S2: every failure path's facts map to one plain cause. */
class PlaybackFailureCausesTest {

    private fun ofError(
        code: Int,
        http: Int? = null,
        unrecognized: Boolean = false,
        renderer: Renderer = Renderer.OTHER,
        playing: Boolean = false
    ) = PlaybackFailureCauses.ofPlaybackError(code, http, unrecognized, renderer, playing)

    @Test
    fun `http answers name their cause`() {
        val expected = mapOf(
            401 to FailureCause.LINK_EXPIRED, 410 to FailureCause.LINK_EXPIRED,
            400 to FailureCause.BLOCKED, 403 to FailureCause.BLOCKED,
            404 to FailureCause.REMOVED, 429 to FailureCause.RATE_LIMITED,
            500 to FailureCause.SERVER_DOWN, 503 to FailureCause.SERVER_DOWN, 599 to FailureCause.SERVER_DOWN,
            416 to FailureCause.OTHER_HTTP, 451 to FailureCause.OTHER_HTTP, 302 to FailureCause.OTHER_HTTP
        )
        expected.forEach { (code, cause) -> assertEquals("HTTP $code", cause, PlaybackFailureCauses.ofHttp(code)) }
    }

    @Test
    fun `the http code wins over the error code`() {
        assertEquals(
            FailureCause.LINK_EXPIRED,
            ofError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http = 410, playing = true)
        )
    }

    @Test
    fun `network errors are unreachable, a missing file is removed`() {
        assertEquals(FailureCause.NO_CONNECTION, ofError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(FailureCause.NO_CONNECTION, ofError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(FailureCause.REMOVED, ofError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun `a parse failure is unreadable before the first frame and corrupt after it`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        )) {
            assertEquals(FailureCause.UNREADABLE, ofError(code))
            assertEquals(FailureCause.CORRUPT, ofError(code, playing = true))
        }
        assertEquals(FailureCause.UNREADABLE, ofError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, unrecognized = true))
        assertEquals(FailureCause.UNKNOWN, ofError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun `decoder errors split by renderer and by whether the picture started`() {
        assertEquals(FailureCause.VIDEO_UNSUPPORTED, ofError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, renderer = Renderer.VIDEO))
        assertEquals(FailureCause.AUDIO_FAILED, ofError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, renderer = Renderer.AUDIO))
        assertEquals(FailureCause.VIDEO_UNSUPPORTED, ofError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(FailureCause.VIDEO_UNSUPPORTED, ofError(PlaybackException.ERROR_CODE_DECODING_FAILED, renderer = Renderer.VIDEO))
        assertEquals(FailureCause.CORRUPT, ofError(PlaybackException.ERROR_CODE_DECODING_FAILED, renderer = Renderer.VIDEO, playing = true))
        assertEquals(FailureCause.AUDIO_FAILED, ofError(PlaybackException.ERROR_CODE_DECODING_FAILED, renderer = Renderer.AUDIO, playing = true))
        assertEquals(FailureCause.AUDIO_FAILED, ofError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(FailureCause.AUDIO_FAILED, ofError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertEquals(FailureCause.UNKNOWN, ofError(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
    }

    @Test
    fun `the startup watchdog's fire reasons`() {
        assertEquals(FailureCause.VIDEO_UNSUPPORTED, PlaybackFailureCauses.ofStartupWatchdog(FireReason.VideoTrackUnsupported, null, null))
        assertEquals(FailureCause.DECODER_STUCK, PlaybackFailureCauses.ofStartupWatchdog(FireReason.DecoderUnresponsive, 5.0, 70.0))
        assertEquals(FailureCause.TOO_SLOW, PlaybackFailureCauses.ofStartupWatchdog(FireReason.TracksNotRead, 18.4, 70.0))
        assertEquals(FailureCause.TOO_SLOW, PlaybackFailureCauses.ofStartupWatchdog(FireReason.NoVideoTrack, 18.4, 70.0))
        assertEquals(FailureCause.NO_DATA, PlaybackFailureCauses.ofStartupWatchdog(FireReason.TracksNotRead, 0.0, 70.0))
        assertEquals(FailureCause.NO_DATA, PlaybackFailureCauses.ofStartupWatchdog(FireReason.TracksNotRead, 18.4, null))
        assertEquals(FailureCause.NO_DATA, PlaybackFailureCauses.ofStartupWatchdog(FireReason.TracksNotRead, null, 70.0))
        assertEquals(FailureCause.NO_DATA, PlaybackFailureCauses.ofStartupWatchdog(FireReason.TracksNotRead, 90.0, 70.0))
        // Spec 86 R9: the Shield window's case (36.3 of 37.8 Mbit/s, data still arriving) is a data reason.
        assertEquals(FailureCause.TOO_SLOW, PlaybackFailureCauses.ofStartupWatchdog(FireReason.DataStillArriving, 36.3, 37.8))
        assertEquals(FailureCause.NO_DATA, PlaybackFailureCauses.ofStartupWatchdog(FireReason.DataStillArriving, 0.0, 37.8))
    }

    @Test
    fun `a probe finding sharpens the fallback, nothing found keeps it`() {
        fun finding(kind: ServerResponseFinding.Kind, status: Int = 200) = ServerResponseFinding(kind, status, null, null)
        assertEquals(FailureCause.UNREADABLE, PlaybackFailureCauses.ofProbe(null, FailureCause.UNREADABLE))
        assertEquals(FailureCause.WEB_PAGE, PlaybackFailureCauses.ofProbe(finding(ServerResponseFinding.Kind.WEB_PAGE), FailureCause.UNREADABLE))
        assertEquals(FailureCause.TEXT, PlaybackFailureCauses.ofProbe(finding(ServerResponseFinding.Kind.TEXT), FailureCause.UNREADABLE))
        assertEquals(FailureCause.TINY_FILE, PlaybackFailureCauses.ofProbe(finding(ServerResponseFinding.Kind.TINY_FILE), FailureCause.UNREADABLE))
        assertEquals(FailureCause.LOGIN, PlaybackFailureCauses.ofProbe(finding(ServerResponseFinding.Kind.LOGIN_PAGE), FailureCause.UNREADABLE))
        assertEquals(
            FailureCause.LINK_EXPIRED,
            PlaybackFailureCauses.ofProbe(finding(ServerResponseFinding.Kind.HTTP_ERROR, status = 410), FailureCause.UNREADABLE)
        )
    }

    @Test
    fun `mbps is bits over seconds, null without a span`() {
        assertEquals(18.4, PlaybackFailureCauses.mbps(46_000_000, 20_000)!!, 1e-9)
        assertNull(PlaybackFailureCauses.mbps(1_000, 0))
        assertNull(PlaybackFailureCauses.mbps(-1, 1_000))
    }

    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    private fun renderer(mime: String, code: Int) = ExoPlaybackException.createForRenderer(
        IllegalStateException("codec"), "renderer", 0, Format.Builder().setSampleMimeType(mime).build(),
        C.FORMAT_HANDLED, null, false, code
    )

    @Test
    fun `the exception extension reads the http code, the renderer and an unrecognised container`() {
        val http = ExoPlaybackException.createForSource(
            HttpDataSource.InvalidResponseCodeException(410, null, null, emptyMap(), spec, byteArrayOf()),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertEquals(FailureCause.LINK_EXPIRED, http.failureCause(renderedFirstFrame = false))
        assertEquals(
            FailureCause.AUDIO_FAILED,
            renderer(MimeTypes.AUDIO_TRUEHD, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).failureCause(false)
        )
        assertEquals(
            FailureCause.VIDEO_UNSUPPORTED,
            renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).failureCause(false)
        )
        assertEquals(
            FailureCause.CORRUPT,
            renderer(MimeTypes.VIDEO_H265, PlaybackException.ERROR_CODE_DECODING_FAILED).failureCause(true)
        )
        val unrecognized = ExoPlaybackException.createForSource(
            UnrecognizedInputFormatException("no extractor", mockk<Uri>(relaxed = true), com.google.common.collect.ImmutableList.of()),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        assertEquals(FailureCause.UNREADABLE, unrecognized.failureCause(false))
        assertEquals(
            FailureCause.UNKNOWN,
            PlaybackException("x", null, PlaybackException.ERROR_CODE_UNSPECIFIED).failureCause(false)
        )
    }
}
