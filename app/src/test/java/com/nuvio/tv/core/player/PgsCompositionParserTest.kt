package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 93 P4: every composition object of a PGS display set is shown, and the epoch's objects and
 * palettes carry across display sets. Pure layer only: Bitmap is a stub on the JVM.
 */
class PgsCompositionParserTest {

    private val white = PgsCompositionParser.argbColor(235, 128, 128, 255, bt709 = true)

    @Test
    fun `a display set with two objects composes both at their own places`() {
        val parser = PgsCompositionParser()
        val sample = epochStart(objects = listOf(Ref(1, 100, 900), Ref(2, 600, 80)), objectIds = listOf(1, 2))
        val composed = parser.parse(sample)
        assertEquals(2, composed.size)
        assertEquals(listOf(100 to 900, 600 to 80), composed.map { it.x to it.y })
        assertTrue(composed.all { it.planeWidth == 1920 && it.planeHeight == 1080 })
        assertTrue(composed.all { obj -> obj.pixels.all { it == white } })
    }

    @Test
    fun `a palette-only Normal set reuses the epoch's object instead of blanking`() {
        val parser = PgsCompositionParser()
        parser.parse(epochStart(objects = listOf(Ref(1, 100, 900)), objectIds = listOf(1)))
        // Fade step: Normal state, palette 0 entry 1 now half transparent, no object data.
        val fade = section(0x16, presentation(state = 0x00, refs = listOf(Ref(1, 100, 900)))) +
            section(0x14, palette(alpha = 128)) + section(0x80, ByteArray(0))
        val composed = parser.parse(fade)
        assertEquals(1, composed.size)
        assertEquals(128, composed[0].pixels[0] ushr 24)
    }

    @Test
    fun `an empty composition clears the screen`() {
        val parser = PgsCompositionParser()
        parser.parse(epochStart(objects = listOf(Ref(1, 100, 900)), objectIds = listOf(1)))
        val clear = section(0x16, presentation(state = 0x00, refs = emptyList())) + section(0x80, ByteArray(0))
        assertEquals(0, parser.parse(clear).size)
    }

    @Test
    fun `after a reset a Normal set has no epoch to draw from`() {
        val parser = PgsCompositionParser()
        parser.parse(epochStart(objects = listOf(Ref(1, 100, 900)), objectIds = listOf(1)))
        parser.reset()
        val normal = section(0x16, presentation(state = 0x00, refs = listOf(Ref(1, 100, 900)))) +
            section(0x80, ByteArray(0))
        assertEquals(0, parser.parse(normal).size)
    }

    @Test
    fun `a truncated object is skipped, not drawn half`() {
        // Object 2 declares 8 RLE bytes but its section carries 4: incomplete, so only object 1 shows.
        val refs = listOf(Ref(1, 100, 900), Ref(2, 600, 80))
        val sample = epochStartHead(refs) + objectSection(1) +
            objectSection(2, rleDeclared = 8, rle = byteArrayOf(1, 1, 1, 1)) + section(0x80, ByteArray(0))
        assertEquals(listOf(100), PgsCompositionParser().parse(sample).map { it.x })
    }

    @Test
    fun `RLE decodes literal pixels, runs and end of line`() {
        val palette = IntArray(256) { it }
        // 5, run of 3 x colour 7, run of 2 x colour 0, end of line, 9
        val rle = byteArrayOf(5, 0, 0x83.toByte(), 7, 0, 0x02, 0, 0, 9)
        assertArrayEquals(intArrayOf(5, 7, 7, 7, 0, 0, 9, 0), PgsCompositionParser.decodeRle(rle, 4, 2, palette))
    }

    @Test
    fun `the decoder factory takes PGS and leaves the rest to Media3`() {
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
        val srt = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        assertTrue(PgsSubtitleDecoderFactory.supportsFormat(pgs))
        assertTrue(PgsSubtitleDecoderFactory.supportsFormat(srt))
        val decoder = PgsSubtitleDecoderFactory.createDecoder(pgs)
        try {
            assertEquals("PgsCompositionDecoder", decoder.name)
        } finally {
            decoder.release()
        }
    }

    @Test
    fun `repeated end sections compose once`() {
        val sample = epochStart(objects = listOf(Ref(1, 100, 900)), objectIds = listOf(1)) +
            section(0x80, ByteArray(0)) + section(0x80, ByteArray(0))
        assertEquals(1, PgsCompositionParser().parse(sample).size)
    }

    @Test
    fun `an oversized plane or RLE length is refused`() {
        val hugePlane = section(0x16, presentation(state = 0x80, refs = listOf(Ref(1, 0, 0)), width = 65535)) +
            section(0x14, palette(alpha = 255)) + objectSection(1) + section(0x80, ByteArray(0))
        assertEquals(0, PgsCompositionParser().parse(hugePlane).size)
        val lyingRle = epochStartHead(listOf(Ref(1, 0, 0))) +
            objectSection(1, rleDeclared = 0xFFFFF0) + section(0x80, ByteArray(0))
        assertEquals(0, PgsCompositionParser().parse(lyingRle).size)
    }

    // ── PGS sample builder ──

    private data class Ref(val id: Int, val x: Int, val y: Int)

    private fun PgsCompositionParser.parse(sample: ByteArray) = parseDisplaySets(sample, 0, sample.size)

    private fun epochStartHead(refs: List<Ref>): ByteArray =
        section(0x16, presentation(state = 0x80, refs = refs)) + section(0x14, palette(alpha = 255))

    private fun epochStart(objects: List<Ref>, objectIds: List<Int>): ByteArray =
        epochStartHead(objects) + objectIds.fold(ByteArray(0)) { acc, id -> acc + objectSection(id) } +
            section(0x80, ByteArray(0))

    private fun presentation(state: Int, refs: List<Ref>, width: Int = 1920): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(width); out.u16(1080); out.write(0x10); out.u16(1)
        out.write(state); out.write(0); out.write(0); out.write(refs.size)
        for (ref in refs) {
            out.u16(ref.id); out.write(0); out.write(0); out.u16(ref.x); out.u16(ref.y)
        }
        return out.toByteArray()
    }

    /** Palette 0, entry 1 = white at [alpha]. */
    private fun palette(alpha: Int): ByteArray = byteArrayOf(0, 0, 1, 235.toByte(), 128.toByte(), 128.toByte(), alpha.toByte())

    /** A 2x2 object of colour 1, its four literal pixels as RLE. */
    private fun objectSection(id: Int, rleDeclared: Int = 4, rle: ByteArray = byteArrayOf(1, 1, 1, 1)): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(id); out.write(0); out.write(0xC0)
        val length = rleDeclared + 4
        out.write(length ushr 16); out.write(length ushr 8 and 0xFF); out.write(length and 0xFF)
        out.u16(2); out.u16(2)
        out.write(rle)
        return section(0x15, out.toByteArray())
    }

    private fun section(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value ushr 8 and 0xFF); write(value and 0xFF)
    }
}
