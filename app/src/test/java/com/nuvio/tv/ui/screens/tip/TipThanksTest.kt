package com.nuvio.tv.ui.screens.tip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 81 D8: the thank-you timeline settles at [THANKS_TOTAL_MS] (no spark left, heart full size,
 * text and Done in). That is the frame Remove animations jumps to; the jump itself is Compose's, not tested here.
 */
class TipThanksTest {

    private val sparks = thanksSparks(colours = 5)

    @Test
    fun `thirty sparks, the same every time, every fifth a heart`() {
        assertEquals(SPARK_COUNT, sparks.size)
        assertEquals(sparks, thanksSparks(colours = 5))
        assertEquals(6, sparks.count { it.heart })
        assertTrue(sparks.all { it.delayMs in 0..SPARK_MAX_DELAY_MS && it.colourIndex in 0..<5 })
    }

    @Test
    fun `nothing shows at the start and no spark is left at the end`() {
        assertTrue(sparks.all { sparkAt(it, 0f).alpha == 0f })
        assertTrue(sparks.all { sparkAt(it, THANKS_TOTAL_MS.toFloat()).alpha == 0f })
        assertEquals(0f to 12f, textAt(0, 0f))
        assertEquals(0f, heartAt(0f).second)
    }

    @Test
    fun `a spark flies out, then falls and fades`() {
        val spark = sparks.first()
        val out = sparkAt(spark, spark.delayMs + 0.45f * SPARK_MS)
        assertEquals(spark.x, out.dx, 0.01f)
        assertEquals(spark.y, out.dy, 0.01f)
        assertEquals(1f, out.alpha, 0.001f)
        val end = sparkAt(spark, spark.delayMs + SPARK_MS.toFloat())
        assertEquals(spark.y + 110f, end.dy, 0.01f)
        assertEquals(spark.x * 1.12f, end.dx, 0.01f)
        assertEquals(0f, end.alpha, 0.001f)
    }

    @Test
    fun `the heart overshoots, then settles at full size`() {
        val (peak, peakAlpha) = heartAt(0.55f * HEART_MS)
        assertEquals(1.08f, peak, 0.001f)
        assertEquals(1f, peakAlpha, 0.001f)
        val (scale, alpha) = heartAt(THANKS_TOTAL_MS.toFloat())
        assertEquals(1f, scale, 1e-4f)
        assertEquals(1f, alpha, 1e-4f)
    }

    @Test
    fun `the text rises in line by line and has settled at the end`() {
        assertEquals(0f, textAt(1, TEXT_START_MS + TEXT_STAGGER_MS - 1f).first)
        assertTrue(textAt(0, TEXT_START_MS + TEXT_STAGGER_MS.toFloat()).first > 0f)
        assertEquals(0f, doneAt(TEXT_START_MS + DONE_DELAY_MS.toFloat()), 1e-4f)
        assertEquals(1f, doneAt(THANKS_TOTAL_MS.toFloat()), 1e-4f)
        (0..2).forEach {
            val (alpha, rise) = textAt(it, THANKS_TOTAL_MS.toFloat())
            assertEquals(1f, alpha, 1e-4f)
            assertEquals(0f, rise, 1e-3f)
        }
    }
}
