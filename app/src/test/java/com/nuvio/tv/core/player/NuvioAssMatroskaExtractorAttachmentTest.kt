package com.nuvio.tv.core.player

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.ID_FILE_MIME_TYPE
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.ID_FILE_NAME
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.MAX_ATTACHMENT_STRING_BYTES
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.MAX_FONT_BYTES
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.MAX_FONT_COUNT
import com.nuvio.tv.core.player.NuvioAssMatroskaExtractor.Companion.MAX_TOTAL_FONT_BYTES
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayOutputStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * L2-22: a FileData element seen before (or without) its FileName/FileMimeType used to
 * throw from requireNotNull, turning one malformed font attachment into a fatal source
 * error. It must now be skipped.
 *
 * Spec 68 X2: a font's size comes from the file; one over the per-font cap, or one that
 * would take the file past the aggregate cap, is skipped, never allocated. FileName and
 * FileMimeType are read as capped binary (the reader would allocate a STRING's declared size).
 */
class NuvioAssMatroskaExtractorAttachmentTest {

    private val assHandler = mockk<AssHandler>(relaxed = true)
    private val extractor = NuvioAssMatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, assHandler)
    private val input = mockk<ExtractorInput>(relaxed = true)

    /** Feeds FileName and FileMimeType through the extractor's own reader path. */
    private fun announceFont(name: String, mime: String = "font/ttf") {
        attachmentString(ID_FILE_NAME, name)
        attachmentString(ID_FILE_MIME_TYPE, mime)
    }

    private fun attachmentString(id: Int, value: String) {
        val bytes = value.toByteArray()
        val textInput = mockk<ExtractorInput>()
        every { textInput.readFully(any(), 0, bytes.size) } answers {
            bytes.copyInto(firstArg<ByteArray>())
        }
        extractor.handleAttachmentString(id, bytes.size, textInput)
    }

    @Test
    fun fileDataWithoutNameOrMimeIsSkipped() {
        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { input.skipFully(5) }
        verify(exactly = 0) { assHandler.addFont(any(), any()) }
    }

    @Test
    fun fontWithinCapIsRead() {
        announceFont("a.ttf")

        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { input.readFully(any(), 0, 5) }
        verify(exactly = 1) { assHandler.addFont("a.ttf", match { it.size == 5 }) }
    }

    @Test
    fun fontOverPerFontCapIsSkipped() {
        announceFont("huge.ttf")

        extractor.handleAttachmentData(contentSize = MAX_FONT_BYTES + 1, input = input)

        verify(exactly = 1) { input.skipFully(MAX_FONT_BYTES + 1) }
        verify(exactly = 0) { input.readFully(any(), any(), any()) }
        verify(exactly = 0) { assHandler.addFont(any(), any()) }
    }

    @Test
    fun negativeSizeFailsAsMalformed() {
        announceFont("bad.ttf")

        // A size cut negative by the reader's int cast can be neither read nor skipped.
        assertThrows(ParserException::class.java) {
            extractor.handleAttachmentData(contentSize = -1, input = input)
        }
        assertThrows(ParserException::class.java) {
            extractor.handleAttachmentString(ID_FILE_NAME, contentSize = -1, input = input)
        }
        verify(exactly = 0) { input.skipFully(any()) }
        verify(exactly = 0) { assHandler.addFont(any(), any()) }
    }

    @Test
    fun oversizedNameIsSkippedAndItsFontToo() {
        extractor.handleAttachmentString(ID_FILE_NAME, MAX_ATTACHMENT_STRING_BYTES + 1, input)
        attachmentString(ID_FILE_MIME_TYPE, "font/ttf")

        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { input.skipFully(MAX_ATTACHMENT_STRING_BYTES + 1) }
        verify(exactly = 1) { input.skipFully(5) }
        verify(exactly = 0) { assHandler.addFont(any(), any()) }
    }

    @Test
    fun zeroPaddedNameIsTrimmed() {
        attachmentString(ID_FILE_NAME, "a.ttf\u0000\u0000")
        attachmentString(ID_FILE_MIME_TYPE, "font/ttf")

        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { assHandler.addFont("a.ttf", any()) }
    }

    @Test
    fun nameAtTheCapIsRead() {
        val name = "n".repeat(MAX_ATTACHMENT_STRING_BYTES)
        announceFont(name)

        extractor.handleAttachmentData(contentSize = 5, input = input)

        verify(exactly = 1) { assHandler.addFont(name, any()) }
    }

    @Test
    fun fontReadAgainAfterSeekIsNotCountedTwice() {
        repeat(2) {
            announceFont("same.ttf")
            extractor.handleAttachmentData(contentSize = MAX_FONT_BYTES, input = input)
        }
        // Counted once, so three more maximal fonts still fit the aggregate cap.
        repeat(3) { index ->
            announceFont("f$index.ttf")
            extractor.handleAttachmentData(contentSize = MAX_FONT_BYTES, input = input)
        }

        verify(exactly = 1) { assHandler.addFont("same.ttf", any()) }
        verify(exactly = 4) { assHandler.addFont(any(), any()) }
        verify(exactly = 1) { input.skipFully(MAX_FONT_BYTES) }
    }

    @Test
    fun differentFontSharingANameStillLoads() {
        announceFont("dup.ttf")
        extractor.handleAttachmentData(contentSize = 5, input = input)
        announceFont("dup.ttf")
        extractor.handleAttachmentData(contentSize = 6, input = input)

        verify(exactly = 2) { assHandler.addFont("dup.ttf", any()) }
    }

    @Test
    fun fontsPastCountCapAreSkipped() {
        repeat(MAX_FONT_COUNT + 1) { index ->
            announceFont("t$index.ttf")
            extractor.handleAttachmentData(contentSize = 0, input = input)
        }

        verify(exactly = MAX_FONT_COUNT) { assHandler.addFont(any(), any()) }
        verify(exactly = 0) { assHandler.addFont("t$MAX_FONT_COUNT.ttf", any()) }
    }

    /**
     * The real seam: a Matroska byte stream through the extractor's EBML reader. An oversized
     * FileName must reach the capped handler (were it typed STRING, the reader would read it
     * whole and the font after it would load).
     */
    @Test
    fun readerRoutesAttachmentElementsThroughTheCaps() {
        every { assHandler.renderType } returns AssRenderType.CUES
        val mkv = element(ID_EBML_HEADER, element(ID_DOC_TYPE, "matroska".toByteArray())) +
            element(
                ID_SEGMENT,
                element(
                    ID_ATTACHMENTS,
                    attachedFile("a.ttf".toByteArray(), byteArrayOf(1, 2, 3)) +
                        attachedFile(ByteArray(MAX_ATTACHMENT_STRING_BYTES + 1) { 'x'.code.toByte() }, byteArrayOf(4))
                )
            )

        extractor.init(mockk<ExtractorOutput>(relaxed = true))
        val source = DefaultExtractorInput({ buffer, offset, length ->
            readFrom(mkv, buffer, offset, length)
        }, 0, mkv.size.toLong())
        var result: Int
        do {
            result = extractor.read(source, PositionHolder())
        } while (result == Extractor.RESULT_CONTINUE)

        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
        verify(exactly = 1) { assHandler.addFont("a.ttf", match { it.contentEquals(byteArrayOf(1, 2, 3)) }) }
        verify(exactly = 1) { assHandler.addFont(any(), any()) }
    }

    private var readPosition = 0

    private fun readFrom(data: ByteArray, buffer: ByteArray, offset: Int, length: Int): Int {
        if (readPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - readPosition)
        data.copyInto(buffer, offset, readPosition, readPosition + count)
        readPosition += count
        return count
    }

    private fun attachedFile(name: ByteArray, data: ByteArray): ByteArray = element(
        ID_ATTACHED_FILE,
        element(ID_FILE_NAME, name) + element(ID_FILE_MIME_TYPE, "font/ttf".toByteArray()) +
            element(ID_FILE_DATA, data)
    )

    /** An EBML element: its ID bytes, an 8-byte size vint, the payload. */
    private fun element(id: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = id.toBigInteger().toByteArray().dropWhile { it == 0.toByte() }
        idBytes.forEach { out.write(it.toInt()) }
        out.write(0x01)
        for (shift in 48 downTo 0 step 8) out.write((payload.size.toLong() shr shift).toInt() and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private companion object {
        // Container IDs the extractor does not export (its attachment IDs are used directly).
        const val ID_EBML_HEADER = 0x1A45DFA3
        const val ID_DOC_TYPE = 0x4282
        const val ID_SEGMENT = 0x18538067
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_DATA = 0x465C
    }

    @Test
    fun fontsPastAggregateCapAreSkippedWithoutFailing() {
        val fitting = (MAX_TOTAL_FONT_BYTES / MAX_FONT_BYTES).toInt()
        assertEquals(4, fitting)
        repeat(fitting) { index ->
            announceFont("f$index.ttf")
            extractor.handleAttachmentData(contentSize = MAX_FONT_BYTES, input = input)
        }

        announceFont("over.ttf")
        extractor.handleAttachmentData(contentSize = 1, input = input)

        verify(exactly = fitting) { assHandler.addFont(any(), any()) }
        verify(exactly = 0) { assHandler.addFont("over.ttf", any()) }
        verify(exactly = 1) { input.skipFully(1) }
    }
}
