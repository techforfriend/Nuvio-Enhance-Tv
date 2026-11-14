package com.nuvio.tv.core.player

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupWatchdogPolicyTest {

    private val interval = PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS
    private val ceiling = PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS

    // --- shouldExtend: buffered-ahead growth gate ---

    @Test
    fun extendsWhileBufferedAheadGrowsUnderCeiling() {
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 5_000L,
                lastBufferedAheadMs = 0L,
            )
        )
    }

    @Test
    fun flatBufferedAheadFiresEvenUnderCeiling() {
        // A flat buffer is a stall, not a slow start — growth must be strict.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 4_000L,
                lastBufferedAheadMs = 4_000L,
            )
        )
    }

    @Test
    fun shrunkBufferedAheadFires() {
        // Buffer draining (e.g. playhead eating into it) is not progress either.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 3_000L,
                lastBufferedAheadMs = 4_000L,
            )
        )
    }

    @Test
    fun arrivingDataExtendsAFlatBufferUnderTheCeilingOnly() {
        // Spec 86 D3: nothing buffered yet, but bytes still arrive.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = interval,
                bufferedAheadMs = 0L,
                lastBufferedAheadMs = 0L,
                dataArriving = true,
            )
        )
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling,
                bufferedAheadMs = 0L,
                lastBufferedAheadMs = 0L,
                dataArriving = true,
            )
        )
    }

    // --- waitsForData: Spec 86 D3, only a start whose video is selected ---

    private fun input(
        tracks: Boolean = true,
        hasVideo: Boolean = true,
        videoSelected: Boolean = true,
        bytesGrew: Boolean? = true,
    ) = PlayerStartupWatchdogPolicy.FireInput(tracks, hasVideo, videoSelected, bytesGrew)

    @Test
    fun waitsForDataOnlyWithVideoSelectedAndBytesArriving() {
        assertTrue(PlayerStartupWatchdogPolicy.waitsForData(input()))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(bytesGrew = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(bytesGrew = null)))
        // Unread tracks, a refused or missing video track: no frame can come, so the fire stays at 20 s.
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(tracks = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(videoSelected = false)))
        assertFalse(PlayerStartupWatchdogPolicy.waitsForData(input(hasVideo = false, videoSelected = false)))
    }

    // --- shouldExtend: ceiling arithmetic (checks land at ~20/~40/~60 s) ---

    @Test
    fun ceilingBoundaryExtendsAtFortySecondCheckDespiteDrift() {
        // delay() never undershoots, so the ~40 s check lands a few ms past 40 s and
        // must still grant the re-arm — a zero-drift boundary would deny it and cap
        // the grace window at ~40 s instead of the documented ~60 s.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = 2 * interval + 50,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
    }

    @Test
    fun ceilingBoundaryFiresOnceCheckLandsAtCeilingOrLater() {
        // Extend while the check lands before the ceiling; at or past it, fire.
        assertTrue(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling - 1,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
        // A pathologically late check (main thread stalled for seconds) never re-arms,
        // so the worst-case fire is the ceiling plus one interval.
        assertFalse(
            PlayerStartupWatchdogPolicy.shouldExtend(
                elapsedMs = ceiling + interval,
                bufferedAheadMs = 10_000L,
                lastBufferedAheadMs = 5_000L,
            )
        )
    }

    // --- fireReason: four-way classification, scan gate first ---

    @Test
    fun tracksNeverReadIsSourceFacingEvenWithStaleTrackFlags() {
        // Order pin: a previous stream's stale video flags must not masquerade as
        // decoder blame when the new stream's headers never arrived.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = false,
                    streamHasVideoTrack = true,
                    videoTrackSelected = true,
                )
            ),
        )
    }

    @Test
    fun videoTrackPresentButUnselectedMeansFormatRejected() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = true,
                    videoTrackSelected = false,
                )
            ),
        )
    }

    @Test
    fun noVideoTrackMeansTracklessStream() {
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = false,
                    videoTrackSelected = false,
                )
            ),
        )
    }

    @Test
    fun videoSelectedWithoutFrameIsTheGenuineDecoderWedge() {
        // Unknown byte flow (mpv's input) keeps today's decoder reason.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive,
            PlayerStartupWatchdogPolicy.fireReason(
                PlayerStartupWatchdogPolicy.FireInput(
                    hasScannedTracksOnce = true,
                    streamHasVideoTrack = true,
                    videoTrackSelected = true,
                )
            ),
        )
        // No bytes arriving: the Codec2 wedge blocks the playback thread, so loading stops.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive,
            PlayerStartupWatchdogPolicy.fireReason(input(bytesGrew = false)),
        )
    }

    @Test
    fun videoSelectedWithBytesStillArrivingIsADataReason() {
        // Spec 86 R9/D3 (Shield window 2026-11-12): 36 of 38 Mbit/s arriving read as a stuck decoder.
        assertEquals(PlayerStartupWatchdogPolicy.FireReason.DataStillArriving, PlayerStartupWatchdogPolicy.fireReason(input()))
        // The track checks still come first.
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
            PlayerStartupWatchdogPolicy.fireReason(input(tracks = false)),
        )
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported,
            PlayerStartupWatchdogPolicy.fireReason(input(videoSelected = false)),
        )
        assertEquals(
            PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
            PlayerStartupWatchdogPolicy.fireReason(input(hasVideo = false, videoSelected = false)),
        )
    }

    // --- failsOver: Spec 86 R4/D3 ---

    @Test
    fun sourceFacingFiresFailOverRefusedFormatAndStuckDecoderStayOnTheBox() {
        val failing = PlayerStartupWatchdogPolicy.FireReason.entries.filter(PlayerStartupWatchdogPolicy::failsOver)
        assertEquals(
            listOf(
                PlayerStartupWatchdogPolicy.FireReason.TracksNotRead,
                PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack,
                PlayerStartupWatchdogPolicy.FireReason.DataStillArriving,
            ),
            failing,
        )
    }

    // --- messageRes: source-facing reasons share one string ---

    @Test
    fun messageMappingMatchesReasonSeverity() {
        assertEquals(
            R.string.player_error_startup_video_track_unsupported,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.VideoTrackUnsupported),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.TracksNotRead),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.NoVideoTrack),
        )
        assertEquals(
            R.string.player_error_startup_no_stream_data,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.DataStillArriving),
        )
        assertEquals(
            R.string.player_error_startup_timeout,
            PlayerStartupWatchdogPolicy.messageRes(PlayerStartupWatchdogPolicy.FireReason.DecoderUnresponsive),
        )
    }

    // --- shouldStopAfterGrace: Spec 56 R1 stop after the post-fire grace ---

    private fun stopAfterGrace(
        hasRenderedFirstFrame: Boolean = false,
        samePlayer: Boolean = true,
        currentError: String? = "fire",
    ) = PlayerStartupWatchdogPolicy.shouldStopAfterGrace(
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        samePlayer = samePlayer,
        currentError = currentError,
        fire = "fire",
    )

    @Test
    fun stopsWhenNothingChangedDuringGrace() {
        assertTrue(stopAfterGrace())
    }

    @Test
    fun lateFirstFrameKeepsThePlayer() {
        assertFalse(stopAfterGrace(hasRenderedFirstFrame = true))
    }

    @Test
    fun replacedPlayerIsNotStopped() {
        assertFalse(stopAfterGrace(samePlayer = false))
    }

    @Test
    fun retractedOrReplacedErrorKeepsThePlayer() {
        assertFalse(stopAfterGrace(currentError = null))
        assertFalse(stopAfterGrace(currentError = "decode failure"))
    }
}
