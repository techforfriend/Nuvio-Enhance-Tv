package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Spec 87 T2 (W5-02): Media3's SsaParser strips `{...}` override blocks but keeps the text of
 * ASS drawing mode (`{\p1}m 0 0 l 1920 0 ...{\p0}`), so with libass off a sign mask shows as
 * its drawing commands. [parserFactory] is Media3's [DefaultSubtitleParserFactory] with that text
 * removed from each `Dialogue:` line before SsaParser reads it; a cue left with no text is
 * dropped. The libass path keeps its own parser factory and renders the drawings.
 *
 * Drawing mode follows libass (ass_parse.c / ass_render.c): `\p<n>` with n > 0 turns it on,
 * an empty or zero value turns it off, `\pos` and `\pbo` are other tags, `\r` leaves it alone,
 * and it ends with the event. A `{` without a closing `}` is not a block: plain text, or drawing
 * text in drawing mode.
 */
@UnstableApi
internal object AssDrawingFilter {

    // libass skips spaces after the backslash and reads the value with strtoll (sign allowed;
    // a negative value is 0, so only `+` and digits can turn drawing mode on).
    private val DRAWING_SCALE = Regex("""\\\s*p(?!os|bo)\s*\+?(\d*)""")
    // SsaParser's lines end at \r or \n only (ParsableByteArray.readLine).
    private val DIALOGUE_LINE = Regex("""^Dialogue:[^\r\n]*""", RegexOption.MULTILINE)

    /** The app's subtitle parsers when libass is off: [delegate] with SSA drawings filtered. */
    fun parserFactory(delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()): SubtitleParser.Factory =
        Factory(delegate)

    /** [dialogueLine] without its drawing-mode text; override blocks and other text stay. */
    fun stripDrawings(dialogueLine: String): String {
        val out = StringBuilder(dialogueLine.length)
        var drawing = false
        var index = 0
        while (index < dialogueLine.length) {
            val open = dialogueLine.indexOf('{', index)
            val close = if (open < 0) -1 else dialogueLine.indexOf('}', open + 1)
            val textEnd = if (close < 0) dialogueLine.length else open
            if (!drawing) out.append(dialogueLine, index, textEnd)
            if (close < 0) break
            out.append(dialogueLine, open, close + 1)
            DRAWING_SCALE.findAll(dialogueLine.substring(open + 1, close)).lastOrNull()?.let { tag ->
                drawing = tag.groupValues[1].any { it != '0' }
            }
            index = close + 1
        }
        return out.toString()
    }

    private class Factory(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser {
            val parser = delegate.create(format)
            return if (format.sampleMimeType == MimeTypes.TEXT_SSA) DrawingFreeParser(parser) else parser
        }
    }

    private class DrawingFreeParser(private val delegate: SubtitleParser) : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>
        ) {
            // SsaParser reads UTF-8 unless a BOM says otherwise (Matroska ASS is always UTF-8);
            // UTF-16 input never matches the tag pattern and passes through untouched.
            val text = String(data, offset, length, Charsets.UTF_8)
            // The gate over-matches (`\p0`, a backslash-p in dialogue): text that comes back
            // unchanged passes through below.
            val filtered = if (DRAWING_SCALE.containsMatchIn(text)) {
                DIALOGUE_LINE.replace(text) { stripDrawings(it.value) }
            } else {
                text
            }
            if (filtered == text) {
                delegate.parse(data, offset, length, outputOptions, output)
                return
            }
            val bytes = filtered.toByteArray(Charsets.UTF_8)
            delegate.parse(bytes, 0, bytes.size, outputOptions) { cuesWithTiming ->
                val visible = cuesWithTiming.cues.filter { it.bitmap != null || !it.text.isNullOrBlank() }
                // A segment the filter emptied is not emitted (nothing shows then, as if the
                // drawing event were absent); untouched segments go out as the delegate built them.
                when {
                    visible.size == cuesWithTiming.cues.size -> output.accept(cuesWithTiming)
                    visible.isNotEmpty() -> output.accept(
                        CuesWithTiming(visible, cuesWithTiming.startTimeUs, cuesWithTiming.durationUs)
                    )
                }
            }
        }

        override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior

        override fun reset() = delegate.reset()
    }
}
