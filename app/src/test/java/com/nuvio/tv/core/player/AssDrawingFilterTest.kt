package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AssDrawingFilterTest {

    @Test
    fun removesDrawingCommandsAndKeepsOverrideBlocks() {
        val line = """Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\an7\pos(0,0)\p1}m 0 0 l 1920 0 1920 1080 0 1080{\p0}"""
        assertEquals(
            """Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\an7\pos(0,0)\p1}{\p0}""",
            AssDrawingFilter.stripDrawings(line)
        )
    }

    @Test
    fun keepsTextAroundADrawing() {
        val line = """Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,Left {\p2}m 0 0 b 10 0 10 10 0 10{\p0}Right"""
        assertEquals(
            """Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,Left {\p2}{\p0}Right""",
            AssDrawingFilter.stripDrawings(line)
        )
    }

    @Test
    fun drawingRunsToTheEndOfTheLineWithoutP0() {
        assertEquals(
            """Dialogue: 0,a,b,S,,0,0,0,,{\p1}{\bord0}""",
            AssDrawingFilter.stripDrawings("""Dialogue: 0,a,b,S,,0,0,0,,{\p1}m 0 0 l 5 5{\bord0}l 9 9""")
        )
    }

    // libass skips spaces after the backslash and parses the value with strtoll.
    @Test
    fun spacedAndSignedScalesFollowLibass() {
        assertEquals(
            """Dialogue: 0,a,b,S,,0,0,0,,{\ p1}""",
            AssDrawingFilter.stripDrawings("""Dialogue: 0,a,b,S,,0,0,0,,{\ p1}m 0 0 l 5 5""")
        )
        assertEquals(
            """Dialogue: 0,a,b,S,,0,0,0,,{\p+1}""",
            AssDrawingFilter.stripDrawings("""Dialogue: 0,a,b,S,,0,0,0,,{\p+1}m 0 0 l 5 5""")
        )
    }

    @Test
    fun posPboZeroAndNegativeScalesAreNotDrawingMode() {
        val lines = listOf(
            """Dialogue: 0,a,b,S,,0,0,0,,{\pos(100,200)}Hello""",
            """Dialogue: 0,a,b,S,,0,0,0,,{\pbo5}Hello""",
            """Dialogue: 0,a,b,S,,0,0,0,,{\p0}Hello""",
            """Dialogue: 0,a,b,S,,0,0,0,,{\p-1}Hello""",
            """Dialogue: 0,a,b,S,,0,0,0,,{\p1\p0}Hello""",
            """Dialogue: 0,a,b,S,,0,0,0,,Plain {unclosed \p1 text"""
        )
        for (line in lines) assertEquals(line, AssDrawingFilter.stripDrawings(line))
    }

    @Test
    fun anUnclosedBraceInDrawingModeIsDrawing() {
        assertEquals(
            """Dialogue: 0,a,b,S,,0,0,0,,{\p1}""",
            AssDrawingFilter.stripDrawings("""Dialogue: 0,a,b,S,,0,0,0,,{\p1}m 0 0 {l 5 5""")
        )
    }

    private class RecordingParser(private val emit: List<CuesWithTiming>) : SubtitleParser {
        var received: String? = null
        var receivedArray: ByteArray? = null
        var receivedOffset = -1
        var receivedLength = -1

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>
        ) {
            receivedArray = data
            receivedOffset = offset
            receivedLength = length
            received = String(data, offset, length, Charsets.UTF_8)
            emit.forEach(output::accept)
        }

        // Not SsaParser's MERGE, so forwarding is visible.
        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }

    private class FixedFactory(private val parser: SubtitleParser) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean = true
        override fun getCueReplacementBehavior(format: Format): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        override fun create(format: Format): SubtitleParser = parser
    }

    private val ssa: Format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()

    private fun cue(text: String): Cue = Cue.Builder().setText(text).build()

    private fun filtered(recording: RecordingParser): SubtitleParser =
        AssDrawingFilter.parserFactory(FixedFactory(recording)).create(ssa)

    private fun parseAll(parser: SubtitleParser, data: ByteArray, offset: Int = 0, length: Int = data.size): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        parser.parse(data, offset, length, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out
    }

    @Test
    fun ssaParserGetsDrawingFreeDialogueAndEmptiedCuesAreDropped() {
        val file = "[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\\p1}m 0 0 l 10 0 10 10{\\p0}\r\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello\r\n"
        val untouched = CuesWithTiming(listOf(cue("Hello")), 2_000_000L, 1_000_000L)
        val recording = RecordingParser(
            listOf(
                CuesWithTiming(listOf(cue(""), cue("Hello")), 1_000_000L, 1_000_000L),
                untouched,
                CuesWithTiming(listOf(cue(" ")), 3_000_000L, 1_000_000L)
            )
        )

        val out = parseAll(filtered(recording), file.toByteArray(Charsets.UTF_8))

        assertEquals(file.replace("m 0 0 l 10 0 10 10", ""), recording.received)
        assertEquals(2, out.size)
        assertEquals(listOf("Hello"), out[0].cues.map { it.text.toString() })
        assertEquals(1_000_000L, out[0].startTimeUs)
        assertEquals(1_000_000L, out[0].durationUs)
        assertSame(untouched, out[1])
    }

    @Test
    fun drawingStateEndsWithEachDialogueLine() {
        val file = "Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\\p1}m 0 0 l 5 5\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello\n"
        val recording = RecordingParser(emptyList())

        parseAll(filtered(recording), file.toByteArray(Charsets.UTF_8))

        assertEquals(file.replace("m 0 0 l 5 5", ""), recording.received)
    }

    // The MKV path hands each sample as a slice of a larger buffer.
    @Test
    fun rewritesOnlyTheSampleSliceAtAnOffset() {
        val sample = "Dialogue: 0:00:00:00,0:00:01:00,0,0,Sign,,0,0,0,,{\\p1}m 0 0 l 5 5{\\p0}Caf\u00e9 \u65e5\u672c\u8a9e"
        val sampleBytes = sample.toByteArray(Charsets.UTF_8)
        val data = "JUNK".toByteArray() + sampleBytes + "TAIL".toByteArray()
        val recording = RecordingParser(emptyList())

        parseAll(filtered(recording), data, offset = 4, length = sampleBytes.size)

        assertEquals(sample.replace("m 0 0 l 5 5", ""), recording.received)
    }

    @Test
    fun inputWithoutDrawingsPassesThroughUntouched() {
        val sampleBytes = "Dialogue: 0:00:00:00,0:00:01:00,0,0,Default,,0,0,0,,{\\pos(1,2)}Caf\u00e9".toByteArray(Charsets.UTF_8)
        val data = "JUNK".toByteArray() + sampleBytes + "TAIL".toByteArray()
        val emitted = CuesWithTiming(listOf(cue("")), 1_000_000L, 1_000_000L)
        val recording = RecordingParser(listOf(emitted))

        val out = parseAll(filtered(recording), data, offset = 4, length = sampleBytes.size)

        assertSame(data, recording.receivedArray)
        assertEquals(4, recording.receivedOffset)
        assertEquals(sampleBytes.size, recording.receivedLength)
        assertSame(emitted, out.single())
    }

    @Test
    fun forwardsTheDelegatesCueReplacementBehavior() {
        val factory = AssDrawingFilter.parserFactory(FixedFactory(RecordingParser(emptyList())))
        assertEquals(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE, factory.getCueReplacementBehavior(ssa))
        assertEquals(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE, factory.create(ssa).cueReplacementBehavior)
    }

    @Test
    fun otherFormatsKeepTheDelegateParser() {
        val recording = RecordingParser(emptyList())
        val srt = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        assertSame(recording, AssDrawingFilter.parserFactory(FixedFactory(recording)).create(srt))
    }
}
