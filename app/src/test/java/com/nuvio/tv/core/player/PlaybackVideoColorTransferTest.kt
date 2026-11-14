package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 62 D12: the SPS fallback decides the HDR output type, so its parse is pinned on real
 * libx265 / libx264 parameter sets (64x64, one frame; only the VUI transfer differs between the
 * HEVC SPS blobs), in the 4-byte Annex-B form HevcConfig writes into initializationData.
 */
@UnstableApi
class PlaybackVideoColorTransferTest {

    @Test
    fun `HEVC SPS with PQ transfer reads ST2084`() {
        assertEquals(C.COLOR_TRANSFER_ST2084, PlaybackVideoColorTransfer.of(hevc(SPS_PQ)))
    }

    @Test
    fun `HEVC SPS with HLG transfer reads HLG`() {
        assertEquals(C.COLOR_TRANSFER_HLG, PlaybackVideoColorTransfer.of(hevc(SPS_HLG)))
    }

    @Test
    fun `HEVC SPS with BT709 transfer reads SDR`() {
        assertEquals(C.COLOR_TRANSFER_SDR, PlaybackVideoColorTransfer.of(hevc(SPS_BT709)))
    }

    @Test
    fun `Dolby Vision track with an HEVC SPS reads it too`() {
        val format = hevc(SPS_PQ).buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()
        assertEquals(C.COLOR_TRANSFER_ST2084, PlaybackVideoColorTransfer.of(format))
    }

    @Test
    fun `declared colour info wins over the SPS`() {
        val format = hevc(SPS_PQ).buildUpon()
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build())
            .build()
        assertEquals(C.COLOR_TRANSFER_HLG, PlaybackVideoColorTransfer.of(format))
    }

    @Test
    fun `H264 parameter sets give no value`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setInitializationData(listOf(annexB(AVC_SPS, AVC_PPS)))
            .build()
        assertEquals(Format.NO_VALUE, PlaybackVideoColorTransfer.of(format))
    }

    @Test
    fun `garbage and empty init data give no value`() {
        val garbage = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setInitializationData(listOf(byteArrayOf(0, 0, 0, 1, 0x42, 0x01, 0x7F)))
            .build()
        assertEquals(Format.NO_VALUE, PlaybackVideoColorTransfer.of(garbage))
        val none = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        assertEquals(Format.NO_VALUE, PlaybackVideoColorTransfer.of(none))
    }

    private fun hevc(sps: String): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setInitializationData(listOf(annexB(VPS, sps, PPS)))
        .build()

    private fun annexB(vararg nals: String): ByteArray =
        nals.fold(ByteArray(0)) { acc, hex -> acc + byteArrayOf(0, 0, 0, 1) + hex.hexBytes() }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val VPS = "40010c01ffff02200000030090000003000003001e959809"
        const val PPS = "4401c172b42240"
        const val SPS_PQ =
            "42010102200000030090000003000003001ea0208104d96566924caf016a12201208000003000800000300c840"
        const val SPS_HLG =
            "42010102200000030090000003000003001ea0208104d96566924caf016a12241208000003000800000300c840"
        const val SPS_BT709 =
            "42010102200000030090000003000003001ea0208104d96566924caf016a12021208000003000800000300c840"
        const val AVC_SPS = "6764000aacd94426c044000003000400000300c83c489658"
        const val AVC_PPS = "68ebe3cb22c0"
    }
}
