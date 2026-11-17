package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 93 P2 (port of ysosrs 7abbc5ee3's tests): a DV track played as its HEVC base layer is
 * labelled from bl_signal_compatibility_id. Helper-level: a whole-file MKV run cannot publish a
 * track Format on the JVM (android.util.SparseArray is a stub; see MatroskaTracksAfterClustersTest).
 */
class DolbyVisionBaseLayerColorTest {

    @Test
    fun `compatibility id comes from the high nibble of the fifth byte`() {
        assertEquals(1, DolbyVisionCompatibility.compatibilityId(dvcc(profile = 8, compatibility = 1)))
        assertEquals(4, DolbyVisionCompatibility.compatibilityId(dvcc(profile = 8, compatibility = 4)))
        assertEquals(6, DolbyVisionCompatibility.compatibilityId(dvcc(profile = 7, compatibility = 6)))
        assertEquals(UNKNOWN, DolbyVisionCompatibility.compatibilityId(null))
        assertEquals(UNKNOWN, DolbyVisionCompatibility.compatibilityId(byteArrayOf(1, 0, 16, 0x35)))
    }

    @Test
    fun `profile seven and eight point one base layers are PQ`() {
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(7, 6))
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(7, UNKNOWN))
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(8, 1))
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(8, 1, bitstream = C.COLOR_TRANSFER_HLG))
    }

    @Test
    fun `profile eight point four base layer is HLG, not PQ`() {
        assertEquals(C.COLOR_TRANSFER_HLG, transfer(8, 4))
        assertEquals(C.COLOR_TRANSFER_HLG, transfer(8, UNKNOWN, bitstream = C.COLOR_TRANSFER_HLG))
    }

    @Test
    fun `profile eight point two and a bitstream that says SDR are left unlabelled`() {
        assertEquals(Format.NO_VALUE, transfer(8, 2))
        assertEquals(Format.NO_VALUE, transfer(8, UNKNOWN, bitstream = C.COLOR_TRANSFER_SDR))
    }

    @Test
    fun `profile eight with nothing to go on keeps the PQ fallback`() {
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(8, 0))
        assertEquals(C.COLOR_TRANSFER_ST2084, transfer(8, UNKNOWN))
    }

    @Test
    fun `profile five and plain HEVC are never labelled`() {
        assertEquals(Format.NO_VALUE, transfer(5, 0))
        assertEquals(Format.NO_VALUE, transfer(Format.NO_VALUE, 1))
    }

    @Test
    fun `missing colour info becomes BT2020 limited range with the base layer transfer`() {
        val info = DolbyVisionCompatibility.baseLayerColorInfo(null, C.COLOR_TRANSFER_HLG)!!
        assertEquals(C.COLOR_SPACE_BT2020, info.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, info.colorRange)
        assertEquals(C.COLOR_TRANSFER_HLG, info.colorTransfer)
    }

    @Test
    fun `a declared HDR transfer is kept as it is`() {
        val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertSame(pq, DolbyVisionCompatibility.baseLayerColorInfo(pq, C.COLOR_TRANSFER_HLG))
        // The P8.4 MP4/TS case: an SPS-derived HLG no longer becomes PQ.
        val hlg = pq.buildUpon().setColorTransfer(C.COLOR_TRANSFER_HLG).build()
        assertSame(hlg, DolbyVisionCompatibility.baseLayerColorInfo(hlg, C.COLOR_TRANSFER_ST2084))
    }

    @Test
    fun `partial container colour becomes BT2020 limited and keeps its static metadata`() {
        val staticInfo = ByteArray(25) { it.toByte() }
        val partial = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).setColorRange(C.COLOR_RANGE_FULL)
            .setHdrStaticInfo(staticInfo).build()
        val info = DolbyVisionCompatibility.baseLayerColorInfo(partial, C.COLOR_TRANSFER_ST2084)!!
        assertEquals(C.COLOR_SPACE_BT2020, info.colorSpace)
        assertEquals(C.COLOR_RANGE_LIMITED, info.colorRange)
        assertEquals(C.COLOR_TRANSFER_ST2084, info.colorTransfer)
        assertEquals(staticInfo.toList(), info.hdrStaticInfo!!.toList())
    }

    @Test
    fun `no transfer to apply leaves the colour info alone`() {
        assertNull(DolbyVisionCompatibility.baseLayerColorInfo(null, Format.NO_VALUE))
        val sdr = ColorInfo.SDR_BT709_LIMITED
        assertSame(sdr, DolbyVisionCompatibility.baseLayerColorInfo(sdr, Format.NO_VALUE))
    }

    private fun transfer(profile: Int, compatibility: Int, bitstream: Int = Format.NO_VALUE) =
        DolbyVisionCompatibility.baseLayerTransfer(profile, compatibility, bitstream)

    private companion object {
        const val UNKNOWN = DolbyVisionCompatibility.COMPATIBILITY_UNKNOWN

        /** A dvcC body: version 1.0, then profile(7) level(6) rpu/el/bl flags, then the compatibility id. */
        fun dvcc(profile: Int, compatibility: Int, level: Int = 6): ByteArray {
            val bytes = ByteArray(24)
            bytes[0] = 1
            bytes[2] = ((profile shl 1) or (level shr 5)).toByte()
            bytes[3] = (((level and 0x1F) shl 3) or 0x04 or 0x01).toByte()
            bytes[4] = (compatibility shl 4).toByte()
            return bytes
        }
    }
}
