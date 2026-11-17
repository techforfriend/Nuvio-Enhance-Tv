package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 93 P3: mpv's forward cache follows the file's bitrate, bounded per RAM tier. */
class MpvDemuxerCacheTest {

    private val mib = 1024L * 1024L

    @Test
    fun `a high-bitrate remux gets the tier cap`() {
        // 60 GB over 2 h: ~67 Mbps, 60 s of it is ~500 MB.
        val size = 60_000_000_000L
        assertEquals(96 * mib, mpvForwardCacheBytes(size, 7200.0, lowRamTier = true))
        assertEquals(192 * mib, mpvForwardCacheBytes(size, 7200.0, lowRamTier = false))
    }

    @Test
    fun `a mid-bitrate file gets 60 seconds`() {
        // 1 MB/s (8 Mbps): 60 MB.
        assertEquals(60_000_000L, mpvForwardCacheBytes(3_600_000_000L, 3600.0, lowRamTier = true))
    }

    @Test
    fun `a low-bitrate file stops at the floor`() {
        // 2 Mbps: 15 MB wanted, 32 MiB given.
        assertEquals(32 * mib, mpvForwardCacheBytes(900_000_000L, 3600.0, lowRamTier = true))
    }

    @Test
    fun `an unknown size or duration keeps the default`() {
        assertNull(mpvForwardCacheBytes(null, 3600.0, lowRamTier = true))
        assertNull(mpvForwardCacheBytes(-1L, 3600.0, lowRamTier = true))
        assertNull(mpvForwardCacheBytes(1_000_000L, null, lowRamTier = true))
        assertNull(mpvForwardCacheBytes(1_000_000L, 0.0, lowRamTier = true))
        assertNull(mpvForwardCacheBytes(1_000_000L, Double.NaN, lowRamTier = true))
    }
}
