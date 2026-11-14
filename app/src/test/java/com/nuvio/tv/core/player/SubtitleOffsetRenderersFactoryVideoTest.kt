package com.nuvio.tv.core.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 61 Q8: the VC-1 pts-repair swap rebuilds the platform video renderer from a fresh
 * builder; the DV7-to-HEVC mapping set on the factory must reach that renderer.
 */
class SubtitleOffsetRenderersFactoryVideoTest {

    // Relaxed, but with no system services: the renderer's frame-release helper then skips
    // its DisplayManager hookup instead of casting a mock Object.
    private val context = mockk<Context>(relaxed = true).also {
        every { it.applicationContext } returns it
        every { it.getSystemService(any<String>()) } returns null
    }

    private fun factory(): SubtitleOffsetRenderersFactory {
        return SubtitleOffsetRenderersFactory(
            context = context,
            subtitleDelayUsProvider = { 0L },
            audioDelayUsProvider = { 0L },
            shouldStripSdhProvider = { false },
            isBuiltInSubtitleProvider = { true },
            videoBoundsFractionProvider = { null },
            gainAudioProcessor = mockk(relaxed = true),
            audioOutputPlan = mockk(relaxed = true),
            deniedTranscodeMimes = emptySet(),
            preferFfmpegAudio = false,
            matPassthroughEnabled = false,
            audioPassthroughPolicy = mockk(relaxed = true),
            playbackSpeedProvider = { 1f },
            onPlaybackSpeedAwareAudioSinkCreated = {},
            onMatRoutingAudioSinkCreated = {},
            onFfmpegAudioRendererChanged = {}
        )
    }

    private fun platformVideoRenderer(factory: SubtitleOffsetRenderersFactory): MediaCodecVideoRenderer {
        // buildVideoRenderers is protected; call it the way DefaultRenderersFactory does.
        val build = SubtitleOffsetRenderersFactory::class.java.getDeclaredMethod(
            "buildVideoRenderers",
            Context::class.java,
            Int::class.javaPrimitiveType,
            MediaCodecSelector::class.java,
            Boolean::class.javaPrimitiveType,
            Handler::class.java,
            VideoRendererEventListener::class.java,
            Long::class.javaPrimitiveType,
            ArrayList::class.java
        ).apply { isAccessible = true }
        val out = ArrayList<Renderer>()
        build.invoke(
            factory,
            context,
            0, // EXTENSION_RENDERER_MODE_OFF: the platform renderer only
            MediaCodecSelector.DEFAULT,
            true,
            mockk<Handler>(relaxed = true),
            mockk<VideoRendererEventListener>(relaxed = true),
            5_000L,
            out
        )
        assertEquals(1, out.size)
        val renderer = out.single()
        assertTrue(renderer is Vc1PtsRepairVideoRenderer)
        return renderer as MediaCodecVideoRenderer
    }

    private fun MediaCodecVideoRenderer.mapsDv7ToHevc(): Boolean =
        MediaCodecVideoRenderer::class.java.getDeclaredField("mapDV7ToHevc")
            .apply { isAccessible = true }
            .getBoolean(this)

    @Test
    fun `DV7 mapping reaches the VC-1 repair renderer`() {
        val factory = factory().apply { mapDv7ToHevc = true }
        assertTrue(platformVideoRenderer(factory).mapsDv7ToHevc())
    }

    @Test
    fun `DV7 mapping stays off by default`() {
        assertEquals(false, platformVideoRenderer(factory()).mapsDv7ToHevc())
    }

    @Test
    fun `text renderers decode raw picture subtitles`() {
        // Spec 61 Q4: PGS/VobSub samples arrive undecoded; without this flag the first one is an
        // IllegalStateException in TextRenderer. Set before the delay wrapper hides the type.
        val build = SubtitleOffsetRenderersFactory::class.java.getDeclaredMethod(
            "buildTextRenderers",
            Context::class.java,
            androidx.media3.exoplayer.text.TextOutput::class.java,
            android.os.Looper::class.java,
            Int::class.javaPrimitiveType,
            ArrayList::class.java
        ).apply { isAccessible = true }
        val out = ArrayList<Renderer>()
        build.invoke(
            factory(),
            context,
            mockk<androidx.media3.exoplayer.text.TextOutput>(relaxed = true),
            mockk<android.os.Looper>(relaxed = true),
            0,
            out
        )
        val textRenderers = out.map { wrapper ->
            assertTrue(wrapper is SubtitleOffsetRenderer)
            SubtitleOffsetRenderer::class.java.getDeclaredField("baseRenderer")
                .apply { isAccessible = true }
                .get(wrapper)
        }.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>()
        assertTrue(textRenderers.isNotEmpty())
        textRenderers.forEach { renderer ->
            val legacy = androidx.media3.exoplayer.text.TextRenderer::class.java
                .getDeclaredField("legacyDecodingEnabled")
                .apply { isAccessible = true }
                .getBoolean(renderer)
            assertTrue(legacy)
        }
    }
}
