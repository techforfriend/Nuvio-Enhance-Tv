package com.nuvio.tv.ui.components.marquee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 118 R2: the meta line's score is never cut; the genres give way first. */
class MarqueeMetaRowFitTest {

    // date, age, genres, runtime; score 60 wide; each gap between items costs 23 (10 + dot 3 + 10).
    private val natural = intArrayOf(115, 40, 150, 45)
    private val genres = listOf("26 September 2012", "PG-13", "Science Fiction, Action", "12 min")

    private fun fit(available: Int, order: List<Int>, minPart: Int = 40) =
        fitMetaParts(natural, fixed = 60, hasScore = true, available = available, dotSlot = 23, minPart = minPart, order = order)

    @Test
    fun `a line that fits is left whole`() {
        // 350 + 60 + 4 gaps x 23 = 502
        assertArrayEquals(natural, fit(502, metaShrinkOrder(genres, false, genres[2])))
    }

    @Test
    fun `the genres give way first and everything else stays`() {
        val widths = fit(460, metaShrinkOrder(genres, false, genres[2]))
        assertArrayEquals(intArrayOf(115, 40, 108, 45), widths)
        assertEquals(460, widths.sum() + 60 + 4 * 23)
    }

    @Test
    fun `genres too narrow to read are dropped with their dot, then the end gives way`() {
        // Without genres: 200 + 60 + 3 x 23 = 329; at 300 the runtime (last) is next and is dropped too.
        assertArrayEquals(intArrayOf(115, 40, 0, 0), fit(300, metaShrinkOrder(genres, false, genres[2])))
    }

    @Test
    fun `without a marked part the parts give way from the end`() {
        // 502 - 460 = 42 over: the runtime (45) would be left 3 wide, so it is dropped.
        assertArrayEquals(intArrayOf(115, 40, 150, 0), fit(460, metaShrinkOrder(genres, false, null)))
    }

    @Test
    fun `order puts the marked part, then an episode label, then the rest from the end`() {
        assertEquals(listOf(2, 0, 3, 1), metaShrinkOrder(genres, truncateFirst = true, shrinkPart = genres[2]))
        assertEquals(listOf(0, 3, 2, 1), metaShrinkOrder(genres, truncateFirst = true, shrinkPart = "absent"))
        assertEquals(emptyList<Int>(), metaShrinkOrder(emptyList(), truncateFirst = true, shrinkPart = null))
    }

    @Test
    fun `parts alone, no score, still fit`() {
        val widths = fitMetaParts(natural, fixed = 0, hasScore = false, available = 300, dotSlot = 23, minPart = 40,
            order = metaShrinkOrder(genres, false, genres[2]))
        // 350 + 3 x 23 = 419; 119 over: genres 150 -> 31 < 40, dropped; then 200 + 2 x 23 = 246 fits.
        assertArrayEquals(intArrayOf(115, 40, 0, 45), widths)
    }
}
