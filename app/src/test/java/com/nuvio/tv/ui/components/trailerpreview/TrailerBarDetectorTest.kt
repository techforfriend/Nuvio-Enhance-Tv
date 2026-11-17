package com.nuvio.tv.ui.components.trailerpreview

import androidx.compose.ui.geometry.Size
import com.nuvio.tv.ui.components.trailerpreview.TrailerShapeReason.AGREED
import com.nuvio.tv.ui.components.trailerpreview.TrailerShapeReason.BUDGET
import com.nuvio.tv.ui.components.trailerpreview.TrailerShapeReason.CAP
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 123 C4 (D3, D10, D12): bar detection on synthetic frames, the shape-once rule, memory and sizes. */
class TrailerBarDetectorTest {
    private val w = TRAILER_SAMPLE_WIDTH
    private val h = TRAILER_SAMPLE_HEIGHT
    private val video = 16f / 9f

    private fun gray(l: Int): Int = (0xFF shl 24) or (l shl 16) or (l shl 8) or l

    /** A frame of [luma] picture with [top]/[bottom] black rows; [paint] overrides a pixel's luma. */
    private fun frame(
        top: Int = 0,
        bottom: Int = top,
        luma: Int = 120,
        paint: (x: Int, y: Int) -> Int? = { _, _ -> null }
    ): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        gray(paint(x, y) ?: if (y < top || y >= h - bottom) 0 else luma)
    }

    private fun detect(pixels: IntArray, frameAspect: Float = video) = detectTrailerVisibleAspect(pixels, w, h, frameAspect)

    @Test
    fun `letterboxed scope, 2_00 and 1_85 read their shapes`() {
        assertEquals(2.4f, detect(frame(top = 28))!!, 0.02f)
        assertEquals(2.0f, detect(frame(top = 12))!!, 0.01f)
        assertEquals(1.846f, detect(frame(top = 4))!!, 0.01f)
    }

    @Test
    fun `a full frame and a native scope frame read their own shape`() {
        assertEquals(video, detect(frame())!!, 0.001f)
        assertEquals(2.39f, detect(frame(), frameAspect = 2.39f)!!, 0.001f)
        assertEquals(video, detect(frame(top = 1))!!, 0.001f) // bars under 2 rows are none
    }

    @Test
    fun `pillarboxed frames say nothing, so their side bars stay`() {
        for (shape in listOf(4f / 3f, 1.66f)) { // The Matrix, Poor Things
            val side = ((w - w * shape / video) / 2).toInt()
            assertNull(detect(frame { x, _ -> if (x < side || x >= w - side) 0 else null }))
        }
    }

    @Test
    fun `a pixel-identical repeat says nothing, a changed frame reads again`() {
        val first = frame(top = 28)
        assertEquals(2.4f, trailerSampleAspect(first, null, w, h, video)!!, 0.02f)
        assertNull(trailerSampleAspect(first.copyOf(), first, w, h, video))
        assertEquals(2.4f, trailerSampleAspect(frame(top = 28, luma = 121), first, w, h, video)!!, 0.02f)
    }

    @Test
    fun `dark, logo and text-card frames say nothing`() {
        assertNull(detect(frame(luma = 10))) // black
        assertNull(detect(frame(top = 28, luma = 30))) // dark picture between bars
        // A centred square logo on black: the "bars" stop at its box, symmetric, but it does not reach the sides.
        assertNull(detect(frame(luma = 0) { x, y -> if (x in 66..125 && y in 78..137) 200 else null }))
        // Two centred lines of text on black.
        assertNull(detect(frame(luma = 0) { x, y -> if (x in 40..151 && (y in 96..103 || y in 112..119)) 230 else null }))
    }

    @Test
    fun `dark-sided shots say nothing`() {
        val darkSides: (Int, Int) -> Int? = { x, _ -> if (x < 20 || x >= w - 20) 8 else null }
        assertNull(detect(frame(paint = darkSides)))
        assertNull(detect(frame(paint = darkSides), frameAspect = 2.39f))
    }

    @Test
    fun `subtitles in one bar make the bars unequal, so the sample says nothing`() {
        assertNull(detect(frame(top = 28) { x, y -> if (y in 200..206 && x in 60..130) 235 else null }))
    }

    @Test
    fun `4 agreeing samples decide the smallest of them, and sampling stops`() {
        val d = TrailerShapeDecider(startPositionMs = 0)
        assertFalse(d.isDue(999))
        assertTrue(d.isDue(1_000))
        assertNull(d.onSample(1_000, 2.40f, 1f, video))
        assertFalse(d.isDue(2_999))
        assertTrue(d.isDue(3_000))
        assertNull(d.onSample(3_000, 2.33f, 1f, video))
        assertNull(d.onSample(6_000, 2.36f, 1f, video))
        assertEquals(TrailerShape(2.33f, AGREED), d.onSample(9_000, 2.38f, 1f, video))
        assertFalse(d.isDue(60_000))
    }

    @Test
    fun `a lone full-frame title card does not join a scope trailer's set`() {
        val d = TrailerShapeDecider(startPositionMs = 37_000) // a resume: offsets count from its start
        assertTrue(d.isDue(38_000))
        assertNull(d.onSample(38_000, 2.40f, 1f, video))
        assertNull(d.onSample(40_000, video, 1f, video))
        assertNull(d.onSample(43_000, 2.40f, 1f, video))
        assertNull(d.onSample(46_000, 2.39f, 1f, video))
        assertEquals(TrailerShape(2.39f, AGREED), d.onSample(49_000, 2.40f, 1f, video))
    }

    @Test
    fun `after 9 s samples come every 3 s, a late one keeps a 1 s gap`() {
        val d = TrailerShapeDecider(startPositionMs = 0)
        listOf(1_000L, 3_000L, 6_000L, 9_000L).forEach { d.onSample(it, null, 1f, video) }
        assertFalse(d.isDue(11_999))
        assertTrue(d.isDue(12_000))
        d.onSample(14_500, null, 1f, video) // deferred while the panel grew
        assertFalse(d.isDue(15_400))
        assertTrue(d.isDue(15_500))
    }

    @Test
    fun `10 samples without agreement keep the frame's own shape`() {
        val d = TrailerShapeDecider(startPositionMs = 0)
        repeat(9) { assertNull(d.onSample(it * 3_000L, if (it % 2 == 0) null else 2.0f + it * 0.1f, 1f, 2.39f)) }
        assertEquals(TrailerShape(2.39f, CAP), d.onSample(30_000, null, 1f, 2.39f))
    }

    @Test
    fun `the 3rd sample over the budget keeps the frame's own shape`() {
        val d = TrailerShapeDecider(startPositionMs = 0)
        assertNull(d.onSample(1_000, 2.4f, 9f, video))
        assertNull(d.onSample(3_000, null, 2f, video))
        assertNull(d.onSample(6_000, 2.0f, 12f, video))
        assertEquals(TrailerShape(video, BUDGET), d.onSample(9_000, 2.4f, 8.5f, video))
    }

    @Test
    fun `the machine remembers sampled shapes, not fallbacks, until reset`() {
        val machine = TrailerPreviewMachine(TestScope(), { 0L }, { 3_000L }) { null }
        machine.onShapeDecided("https://v/a", TrailerShape(2.39f, AGREED))
        machine.onShapeDecided("https://v/b", TrailerShape(video, CAP))
        assertEquals(2.39f, machine.rememberedShape("https://v/a"))
        assertNull(machine.rememberedShape("https://v/b"))
        repeat(TrailerPreviewMachine.SHAPE_MEMORY) { machine.onShapeDecided("https://v/n$it", TrailerShape(2f, AGREED)) }
        assertNull(machine.rememberedShape("https://v/a")) // the last 32 only
        assertEquals(2f, machine.rememberedShape("https://v/n0"))
        machine.reset()
        assertNull(machine.rememberedShape("https://v/n0"))
    }

    @Test
    fun `the panel keeps its height until the window caps its width`() {
        val window = Size(960f, 540f)
        assertEquals(Size(800f, 450f), trailerPreviewPanelSize(video, window, 450f, 48f).round())
        assertEquals(Size(864f, 362f), trailerPreviewPanelSize(2.39f, window, 450f, 48f).round()) // D1
        assertEquals(Size(600f, 450f), trailerPreviewPanelSize(1.2f, window, 450f, 48f).round()) // clamped to 4:3
        assertEquals(Size(1076f, 450f), trailerPreviewPanelSize(2.39f, Size.Zero, 450f, 48f).round()) // before layout
    }

    @Test
    fun `the player is the frame's shape fitted in the panel box`() {
        val box = Size(800f, 450f)
        assertEquals(Size(800f, 335f), trailerPreviewPlayerSize(2.39f, box).round())
        assertEquals(Size(600f, 450f), trailerPreviewPlayerSize(4f / 3f, box).round())
        assertEquals(Size(800f, 450f), trailerPreviewPlayerSize(video, box).round())
    }

    private fun Size.round() = Size(Math.round(width).toFloat(), Math.round(height).toFloat())
}
