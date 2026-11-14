package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalPlaybackCompletionRulesTest {

    @Test
    fun `real episode end is treated as natural completion`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 2_400_000L // 40 min
            )
        )
    }

    @Test
    fun `short debrid placeholder is not natural completion`() {
        // Comet-style cache-sync / error clips are a few seconds long.
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 5_000L
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 30_000L
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 120_999L
            )
        )
    }

    @Test
    fun `just over placeholder threshold is natural completion`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 121_000L
            )
        )
    }

    @Test
    fun `no first frame is not natural completion`() {
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = false,
                hasFatalError = false,
                durationMs = 2_400_000L
            )
        )
    }

    @Test
    fun `fatal error is not natural completion`() {
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = true,
                durationMs = 2_400_000L
            )
        )
    }

    @Test
    fun `zero or unknown duration is still allowed when first frame rendered`() {
        // Match external-player behavior: missing duration is left to the normal path.
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 0L
            )
        )
    }

    @Test
    fun `a release video ends naturally however short, normal playback keeps the placeholder rule (Spec 80 D20)`() {
        assertTrue(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 30_000L,
                releaseVideo = true
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = false,
                durationMs = 30_000L,
                releaseVideo = false
            )
        )
        // The other two gates still hold for a release video.
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = false,
                hasFatalError = false,
                durationMs = 30_000L,
                releaseVideo = true
            )
        )
        assertFalse(
            shouldTreatAsNaturalPlaybackCompletion(
                hasRenderedFirstFrame = true,
                hasFatalError = true,
                durationMs = 30_000L,
                releaseVideo = true
            )
        )
    }
}
