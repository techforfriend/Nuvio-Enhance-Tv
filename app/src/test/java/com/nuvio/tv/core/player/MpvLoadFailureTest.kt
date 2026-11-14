package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 85 D3: mpv END_FILE fields -> the failed load's text, or null when the file did not fail;
 * G10: mpv's track-list -> the startup watchdog's fire input (and so its fire reason).
 */
class MpvLoadFailureTest {

    @Test
    fun errorReasonReturnsMpvErrorText() {
        assertEquals("loading failed", mpvLoadFailureOf(reason = "error", fileError = "loading failed"))
        assertEquals(
            "no audio or video data played",
            mpvLoadFailureOf(reason = "error", fileError = "no audio or video data played")
        )
    }

    @Test
    fun errorReasonWithoutTextStillFails() {
        assertEquals("unknown error", mpvLoadFailureOf(reason = "error", fileError = null))
        assertEquals("unknown error", mpvLoadFailureOf(reason = "error", fileError = "  "))
    }

    @Test
    fun nonErrorReasonsAreNotFailures() {
        listOf("eof", "stop", "quit", "redirect", "unknown", null).forEach { reason ->
            assertNull(reason, mpvLoadFailureOf(reason = reason, fileError = "loading failed"))
        }
    }

    private fun fireReason(types: List<String>, videoSelected: Boolean) =
        PlayerStartupWatchdogPolicy.fireReason(mpvWatchdogFireInput(types, videoSelected))

    @Test
    fun noTracksOrOnlyAnExternalSubtitleIsTracksNotRead() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            fireReason(emptyList(), videoSelected = false)
        )
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            fireReason(listOf("sub"), videoSelected = false)
        )
    }

    @Test
    fun audioOnlyIsNoVideoTrack() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
            fireReason(listOf("audio", "sub"), videoSelected = false)
        )
    }

    @Test
    fun videoTrackNotSelectedIsVideoTrackUnsupported() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported,
            fireReason(listOf("Video", "audio"), videoSelected = false)
        )
    }

    @Test
    fun videoTrackSelectedIsDecoderUnresponsive() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive,
            fireReason(listOf("video", "audio"), videoSelected = true)
        )
    }
}
