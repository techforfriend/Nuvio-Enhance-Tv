/*
 * Ported from edde746/plezy
 * android/app/src/main/kotlin/com/edde746/plezy/exoplayer/PgsCompositionParser.kt
 * at commit bb95d54bace51c4c806bc22b9bbe2f60edeb465c (parser added in a7354cd3b6d8, Plezy #1953).
 * Licensed under the GNU General Public License v3.0 (GPL-3.0), as is this app.
 * Changes: wired as a render-time SubtitleDecoder (Spec 61 Q4), RLE decode and cue layout split
 * into pure functions for JVM tests.
 */
package com.nuvio.tv.core.player

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleParser
import java.util.zip.Inflater
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spec 93 P4 (A1-2 / S2-31): PGS reaches the text renderer raw (Spec 61 Q4), where Media3's
 * default decoder wraps its PgsParser. That parser keeps one bitmap and the first composition
 * object's position, so a display set with two objects (dialogue and a sign) shows one, and it
 * keeps no state between display sets, so palette-only fades blank the screen. PGS goes to
 * [PgsCompositionParser] instead; every other format to Media3's default.
 */
@UnstableApi
internal object PgsSubtitleDecoderFactory : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.APPLICATION_PGS || SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (format.sampleMimeType == MimeTypes.APPLICATION_PGS) {
            PgsCompositionDecoder()
        } else {
            SubtitleDecoderFactory.DEFAULT.createDecoder(format)
        }
}

/**
 * Media3's DelegatingSubtitleDecoder is package-private; this is its body. The decoder keeps one
 * parser for the track, so the epoch's objects and palettes carry between samples; a flush (seek)
 * resets it.
 */
@UnstableApi
private class PgsCompositionDecoder : SimpleSubtitleDecoder("PgsCompositionDecoder") {
    private val parser = PgsCompositionParser()

    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        if (reset) parser.reset()
        return parser.parseToLegacySubtitle(data, 0, length)
    }
}

/**
 * PGS parser that renders every composition object in a display set, after FFmpeg's pgssubdec
 * (which Kodi, mpv and VLC share): objects and palettes are cached per epoch keyed by id, any
 * non-Normal composition state releases the caches, palette sections update entries in place,
 * and the display-end section composes one cue per object reference. Colours use limited-range
 * BT.709 for HD planes and BT.601 otherwise, as FFmpeg does.
 */
@UnstableApi
internal class PgsCompositionParser : SubtitleParser {

    private class PgsObject {
        var width = 0
        var height = 0
        var rle = ByteArray(0)
        var received = 0

        val complete: Boolean
            get() = rle.isNotEmpty() && received == rle.size
    }

    private class ObjectRef(val objectId: Int, val x: Int, val y: Int)

    /** One object of a display set, decoded: ARGB pixels and its place on the plane. */
    internal class ComposedObject(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val planeWidth: Int,
        val planeHeight: Int
    )

    private val buffer = ParsableByteArray()
    private val inflatedBuffer = ParsableByteArray()
    private var inflater: Inflater? = null

    // Epoch state: kept across display sets until a non-Normal composition state starts a new
    // epoch or reset() follows a seek.
    private val objects = HashMap<Int, PgsObject>()
    private val palettes = HashMap<Int, IntArray>()

    // Latest presentation composition.
    private var planeWidth = 0
    private var planeHeight = 0
    private var paletteId = 0
    private var objectRefs = emptyList<ObjectRef>()
    // One composition per presentation section: repeated END sections (a damaged or hostile
    // stream) must not decode the same objects again.
    private var composePending = false

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun reset() {
        objects.clear()
        palettes.clear()
        objectRefs = emptyList()
        composePending = false
        planeWidth = 0
        planeHeight = 0
        paletteId = 0
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        val cues = parseDisplaySets(data, offset, length).map(::toCue)
        output.accept(CuesWithTiming(cues, C.TIME_UNSET, C.TIME_UNSET))
    }

    /** Reads every section of one sample and returns the objects its display-end sections compose. */
    internal fun parseDisplaySets(data: ByteArray, offset: Int, length: Int): List<ComposedObject> {
        buffer.reset(data, offset + length)
        buffer.setPosition(offset)
        val inflater = this.inflater ?: Inflater().also { this.inflater = it }
        if (Util.maybeInflate(buffer, inflatedBuffer, inflater)) {
            buffer.reset(inflatedBuffer.data, inflatedBuffer.limit())
        }
        val composed = ArrayList<ComposedObject>()
        while (buffer.bytesLeft() >= 3) {
            readNextSection(composed)
        }
        return composed
    }

    private fun readNextSection(composed: MutableList<ComposedObject>) {
        val sectionType = buffer.readUnsignedByte()
        val sectionLength = buffer.readUnsignedShort()
        val nextSectionPosition = buffer.position + sectionLength
        if (nextSectionPosition > buffer.limit()) {
            buffer.setPosition(buffer.limit())
            return
        }
        when (sectionType) {
            SECTION_TYPE_PALETTE -> parsePalette(sectionLength)
            SECTION_TYPE_OBJECT -> parseObject(sectionLength)
            SECTION_TYPE_PRESENTATION -> parsePresentation(sectionLength)
            SECTION_TYPE_END -> compose(composed)
            // Window sections (0x17) carry nothing the composition needs.
        }
        buffer.setPosition(nextSectionPosition)
    }

    private fun parsePresentation(sectionLength: Int) {
        if (sectionLength < 11) return
        val width = buffer.readUnsignedShort()
        val height = buffer.readUnsignedShort()
        // Review ee4801d1: the plane bounds every object's size, so it is bounded itself.
        if (width !in 1..MAX_PLANE_DIMENSION || height !in 1..MAX_PLANE_DIMENSION) {
            objectRefs = emptyList()
            composePending = false
            return
        }
        planeWidth = width
        planeHeight = height
        buffer.skipBytes(3) // Frame rate (1), composition number (2).
        val compositionState = buffer.readUnsignedByte()
        if (compositionState and 0xC0 != 0) {
            // Epoch start / acquisition point: complete data follows; the old epoch is released.
            objects.clear()
            palettes.clear()
        }
        buffer.skipBytes(1) // Palette update flag.
        paletteId = buffer.readUnsignedByte()
        val objectCount = buffer.readUnsignedByte()
        var remaining = sectionLength - 11
        val refs = ArrayList<ObjectRef>(min(objectCount, MAX_OBJECT_REFS))
        for (i in 0 until min(objectCount, MAX_OBJECT_REFS)) {
            if (remaining < 8) break
            val objectId = buffer.readUnsignedShort()
            buffer.skipBytes(1) // Window id.
            val flags = buffer.readUnsignedByte()
            val x = buffer.readUnsignedShort()
            val y = buffer.readUnsignedShort()
            remaining -= 8
            if (flags and 0x80 != 0) {
                // Cropped object: skip the crop rectangle (not applied, as FFmpeg).
                if (remaining < 8) break
                buffer.skipBytes(8)
                remaining -= 8
            }
            refs.add(ObjectRef(objectId, x, y))
        }
        objectRefs = refs
        composePending = true
    }

    private fun parsePalette(sectionLength: Int) {
        if (sectionLength % 5 != 2) return
        val id = buffer.readUnsignedByte()
        buffer.skipBytes(1) // Palette version.
        if (palettes.size >= MAX_EPOCH_PALETTES && id !in palettes) return
        // Entries update in place: fade ramps resend only the entries that changed.
        val palette = palettes.getOrPut(id) { IntArray(256) }
        val bt709 = planeHeight <= 0 || planeHeight > 576
        repeat(sectionLength / 5) {
            val index = buffer.readUnsignedByte()
            val y = buffer.readUnsignedByte()
            val cr = buffer.readUnsignedByte()
            val cb = buffer.readUnsignedByte()
            val alpha = buffer.readUnsignedByte()
            palette[index] = argbColor(y, cb, cr, alpha, bt709)
        }
    }

    private fun parseObject(sectionLength: Int) {
        if (sectionLength < 4) return
        val id = buffer.readUnsignedShort()
        if (objects.size >= MAX_EPOCH_OBJECTS && id !in objects) return
        buffer.skipBytes(1) // Object version.
        val isFirstFragment = buffer.readUnsignedByte() and 0x80 != 0
        var remaining = sectionLength - 4
        val obj = objects.getOrPut(id) { PgsObject() }
        if (isFirstFragment) {
            if (remaining < 7) return
            val rleLength = buffer.readUnsignedInt24() - 4 // The length includes width and height.
            val width = buffer.readUnsignedShort()
            val height = buffer.readUnsignedShort()
            remaining -= 7
            val maxWidth = if (planeWidth > 0) planeWidth else MAX_PLANE_DIMENSION
            val maxHeight = if (planeHeight > 0) planeHeight else MAX_PLANE_DIMENSION
            // A display set arrives as one sample, so its RLE never exceeds the sample: a larger
            // declared length is a lie and is not allocated.
            if (rleLength <= 0 || rleLength > buffer.limit() || width !in 1..maxWidth || height !in 1..maxHeight) {
                obj.rle = ByteArray(0)
                obj.received = 0
                return
            }
            obj.width = width
            obj.height = height
            obj.rle = ByteArray(rleLength)
            obj.received = 0
        }
        val bytesToRead = min(remaining, obj.rle.size - obj.received)
        if (bytesToRead > 0) {
            buffer.readBytes(obj.rle, obj.received, bytesToRead)
            obj.received += bytesToRead
        }
    }

    private fun compose(composed: MutableList<ComposedObject>) {
        if (!composePending || planeWidth <= 0 || planeHeight <= 0) return
        composePending = false
        val palette = palettes[paletteId] ?: return
        for (ref in objectRefs) {
            // A missing or incomplete object (a damaged stream, or a Normal set after a seek
            // whose epoch data was never seen) is skipped.
            val obj = objects[ref.objectId] ?: continue
            if (!obj.complete) continue
            composed.add(
                ComposedObject(
                    pixels = decodeRle(obj.rle, obj.width, obj.height, palette),
                    width = obj.width,
                    height = obj.height,
                    x = ref.x,
                    y = ref.y,
                    planeWidth = planeWidth,
                    planeHeight = planeHeight
                )
            )
        }
    }

    private fun toCue(obj: ComposedObject): Cue =
        Cue.Builder()
            .setBitmap(Bitmap.createBitmap(obj.pixels, obj.width, obj.height, Bitmap.Config.ARGB_8888))
            .setPosition(obj.x.toFloat() / obj.planeWidth)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(obj.y.toFloat() / obj.planeHeight, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(obj.width.toFloat() / obj.planeWidth)
            .setBitmapHeight(obj.height.toFloat() / obj.planeHeight)
            .build()

    internal companion object {
        private const val SECTION_TYPE_PALETTE = 0x14
        private const val SECTION_TYPE_OBJECT = 0x15
        private const val SECTION_TYPE_PRESENTATION = 0x16
        private const val SECTION_TYPE_END = 0x80

        // Blu-ray limits, as FFmpeg's pgssubdec enforces them.
        private const val MAX_OBJECT_REFS = 2
        private const val MAX_EPOCH_OBJECTS = 64
        private const val MAX_EPOCH_PALETTES = 8

        // Bounds object size when a damaged stream declares it before any presentation section.
        private const val MAX_PLANE_DIMENSION = 4096

        /** PGS run-length decode to ARGB through [palette]; stops at the end of either buffer. */
        fun decodeRle(rle: ByteArray, width: Int, height: Int, palette: IntArray): IntArray {
            val pixels = IntArray(width * height)
            var input = 0
            var output = 0
            while (input < rle.size && output < pixels.size) {
                val first = rle[input++].toInt() and 0xFF
                if (first != 0) {
                    pixels[output++] = palette[first]
                    continue
                }
                if (input >= rle.size) break
                val flags = rle[input++].toInt() and 0xFF
                if (flags == 0) continue // End of line.
                var run = flags and 0x3F
                if (flags and 0x40 != 0) {
                    if (input >= rle.size) break
                    run = run shl 8 or (rle[input++].toInt() and 0xFF)
                }
                val color = if (flags and 0x80 != 0) {
                    if (input >= rle.size) break
                    palette[rle[input++].toInt() and 0xFF]
                } else {
                    palette[0]
                }
                val end = min(output + run, pixels.size)
                pixels.fill(color, output, end)
                output = end
            }
            return pixels
        }

        fun argbColor(y: Int, cb: Int, cr: Int, alpha: Int, bt709: Boolean): Int {
            val yc = (y - 16) * (255f / 219f)
            val cbc = (cb - 128) * (255f / 224f)
            val crc = (cr - 128) * (255f / 224f)
            val r: Float
            val g: Float
            val b: Float
            if (bt709) {
                r = yc + 1.5747f * crc
                g = yc - 0.1873f * cbc - 0.4682f * crc
                b = yc + 1.8556f * cbc
            } else {
                r = yc + 1.402f * crc
                g = yc - 0.34414f * cbc - 0.71414f * crc
                b = yc + 1.772f * cbc
            }
            return (alpha shl 24) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }
}
