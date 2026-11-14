package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.SubtitleParser

/**
 * Spec 61 Q4 (ysosrs 80f38da24): picture subtitles reach the text renderer undecoded, so only the
 * selected track is decoded, and only as its samples fall due. Parsed while the file is read,
 * every track is decoded whether it is shown or not, which for PGS means a full-picture bitmap
 * per sample on the loader thread (a remux carries 10-30 PGS tracks).
 *
 * Text formats stay parsed during extraction: ASS because libass creates its track from the
 * parser, the rest because nothing is gained by moving them.
 */
@UnstableApi
internal object RenderTimeSubtitles {

    private val RENDER_TIME_MIMES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB)

    fun decodedAtRenderTime(format: Format): Boolean = format.sampleMimeType in RENDER_TIME_MIMES

    /** The parser factory for extractors: formats decoded at render time are left unparsed. */
    fun extractionParsers(delegate: SubtitleParser.Factory): SubtitleParser.Factory =
        ExtractionParsers(delegate)

    /**
     * Raw subtitle samples need the text renderer's own decoders (off by default in Media3
     * 1.8.0, where a raw sample is an IllegalStateException); cue samples are unaffected.
     */
    fun enableRenderTimeDecoding(renderers: List<Renderer>, fromIndex: Int = 0) {
        for (index in fromIndex until renderers.size) {
            (renderers[index] as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
        }
    }

    private class ExtractionParsers(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean =
            !decodedAtRenderTime(format) && delegate.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser = delegate.create(format)
    }
}
