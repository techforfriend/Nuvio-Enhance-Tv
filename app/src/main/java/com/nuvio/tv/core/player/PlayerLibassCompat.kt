package com.nuvio.tv.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.R
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.render.AssRenderer
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())

private const val TAG = "PlayerLibassCompat"

/**
 * Spec 98 (issue #2): a Latin (Vietnamese included), Greek and Cyrillic subset of the bundled
 * Open Sans, Regular and Bold, whose family name is "sans-serif". ass-kt 0.4.0 sets libass's default family to "sans-serif" with
 * fontconfig as the only system provider (AssKt.c nativeAssRenderInit), and no installed font is
 * named that, so when fontconfig finds no covering font libass draws .notdef boxes. libass tries
 * the default family by name, glyph coverage checked, before fontconfig's fallback, so these
 * fonts fill that gap; scripts they lack still reach fontconfig. Recipe and evidence: Spec 98 D5, D7.
 */
internal val LIBASS_FALLBACK_FONTS: List<Pair<String, Int>> = listOf(
    "libass_fallback_regular.ttf" to R.raw.libass_fallback_regular,
    "libass_fallback_bold.ttf" to R.raw.libass_fallback_bold
)

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    playerMediaSourceFactory: PlayerMediaSourceFactory? = null,
    dataSourceFactory: DataSource.Factory = PlayerPlaybackNetworking.createDataSourceFactory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
    assRendererWrapper: (Renderer) -> Renderer = { it }
): ExoPlayer {
    val assHandler = AssHandler(renderType)
    // Straight to the library, not AssHandler.addFont: that buffers fonts until the first track
    // and drops the buffer on every media-item transition; library fonts live with the handler.
    addLibassFallbackFonts(
        readFont = { resId -> context.resources.openRawResource(resId).use { it.readBytes() } },
        addFont = assHandler.ass::addFont
    )
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    // Spec 61 Q4: the MKV extractor leaves PGS/VobSub raw for the text renderer; ASS still
    // reaches libass through the parser.
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = RenderTimeSubtitles.extractionParsers(assSubtitleParserFactory),
        assHandler = assHandler
    )
    playerMediaSourceFactory?.configureSubtitleParsing(
        extractorsFactory = assExtractorsFactory,
        subtitleParserFactory = assSubtitleParserFactory
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(
        dataSourceFactory,
        assExtractorsFactory
    )
    mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

    val player = this
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory.withAssSupport(assHandler).wrapAssRenderer(assRendererWrapper))
        .build()

    assHandlersByPlayer[player] = assHandler
    assHandler.init(player)
    return player
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

/**
 * Hands each of [LIBASS_FALLBACK_FONTS] to [addFont] (the libass library, before any track or
 * renderer exists). A font that cannot be read is skipped: libass then falls back as before.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun addLibassFallbackFonts(
    readFont: (resId: Int) -> ByteArray,
    addFont: (name: String, data: ByteArray) -> Unit
) {
    for ((name, resId) in LIBASS_FALLBACK_FONTS) {
        val bytes = try {
            readFont(resId)
        } catch (e: Exception) {
            Log.w(TAG, "libass fallback font $name unreadable", e)
            continue
        }
        addFont(name, bytes)
    }
}

/**
 * Spec 36/S3 (B2-24, upstream 59019f8cc): the libass renderer is added by ass-media itself, so it
 * never passes through buildTextRenderers and its subtitle/audio delay wrapper. Wrap it here.
 */
@OptIn(UnstableApi::class)
internal fun RenderersFactory.wrapAssRenderer(wrapper: (Renderer) -> Renderer): RenderersFactory {
    val delegate = this
    return object : RenderersFactory {
        override fun createRenderers(
            eventHandler: Handler,
            videoRendererEventListener: VideoRendererEventListener,
            audioRendererEventListener: AudioRendererEventListener,
            textRendererOutput: TextOutput,
            metadataRendererOutput: MetadataOutput
        ): Array<Renderer> {
            return delegate.createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput
            ).map { renderer ->
                if (renderer is AssRenderer) wrapper(renderer) else renderer
            }.toTypedArray()
        }

        override fun createSecondaryRenderer(
            renderer: Renderer,
            eventHandler: Handler,
            videoRendererEventListener: VideoRendererEventListener,
            audioRendererEventListener: AudioRendererEventListener,
            textRendererOutput: TextOutput,
            metadataRendererOutput: MetadataOutput
        ): Renderer? {
            return delegate.createSecondaryRenderer(
                renderer,
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput
            )
        }
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        return delegate.create(normalizeSsaFormat(format))
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    val delegate = this
    fun swapMatroska(extractors: Array<Extractor>): Array<Extractor> {
        extractors.forEachIndexed { index, extractor ->
            // Stock MatroskaExtractor: replace with ASS-aware variant for libass support.
            if (extractor is StockMatroskaExtractor) {
                extractors[index] = NuvioAssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
            // The DV7 factory swaps in a vendored DvMatroskaExtractor for DV conversion.
            // Preserve its Dolby Vision transformer while enabling libass and zlib subtitle
            // decompression from the same vendored Matroska extractor base class.
            if (extractor is DvMatroskaExtractor) {
                extractors[index] = NuvioAssMatroskaExtractor(
                    subtitleParserFactory = subtitleParserFactory,
                    assHandler = assHandler,
                    dolbyVisionSampleTransformer = extractor.dolbyVisionSampleTransformer
                )
            }
        }
        return extractors
    }
    // Both overloads: progressive loading calls createExtractors(uri, responseHeaders)
    // (Media3 1.8.0 BundledExtractorsAdapter.init), and the default interface method would
    // drop to the no-arg one, losing the delegate's URI/Content-Type extractor ordering
    // (upstream-sync T28 P33, `44e0fc456`).
    return object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            swapMatroska(delegate.createExtractors())

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>
        ): Array<Extractor> = swapMatroska(delegate.createExtractors(uri, responseHeaders))
    }
}
