package com.nuvio.tv.ui.components.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 65 P6 (D16): which row says what, and where the row menu opens. */
class SourceRowMenuTest {

    @Test
    fun `the first row says Preferred while the view leads with the preferred source`() {
        val lastPlayed = SourceRowMarker.LAST_PLAYED
        assertEquals(SourceRowMarker.PREFERRED, sourceRowMarker(index = 0, marker = lastPlayed, markedIndex = 2, preferredCount = 2))
        // One row says it, though two match; the last played row keeps its own marker.
        assertNull(sourceRowMarker(index = 1, marker = lastPlayed, markedIndex = 2, preferredCount = 2))
        assertEquals(lastPlayed, sourceRowMarker(index = 2, marker = lastPlayed, markedIndex = 2, preferredCount = 2))
        // Preferred wins over Last played on the same row.
        assertEquals(SourceRowMarker.PREFERRED, sourceRowMarker(index = 0, marker = lastPlayed, markedIndex = 0, preferredCount = 1))
        // Nothing matches: the markers as before.
        assertEquals(lastPlayed, sourceRowMarker(index = 0, marker = lastPlayed, markedIndex = 0, preferredCount = 0))
        assertNull(sourceRowMarker(index = 0, marker = null, markedIndex = -1, preferredCount = 0))
        assertEquals(SourceRowMarker.PREFERRED, sourceRowMarker(index = 0, marker = null, markedIndex = -1, preferredCount = 1))
    }

    @Test
    fun `the player's Playing wins over Preferred on the playing row`() {
        val playing = SourceRowMarker.PLAYING
        assertEquals(playing, sourceRowMarker(index = 0, marker = playing, markedIndex = 0, preferredCount = 1))
        assertEquals(SourceRowMarker.PREFERRED, sourceRowMarker(index = 0, marker = playing, markedIndex = 3, preferredCount = 1))
        assertEquals(playing, sourceRowMarker(index = 3, marker = playing, markedIndex = 3, preferredCount = 1))
    }

    @Test
    fun `the menu opens under its row, above it when there is no room, never off the top`() {
        fun top(rowTop: Int, rowBottom: Int, menuHeight: Int = 110) =
            rowMenuTop(rowTop, rowBottom, menuHeight, offset = 5, screenHeight = 540, screenMargin = 28)
        assertEquals(346, top(rowTop = 240, rowBottom = 341))
        // Ends exactly at the margin: still under the row.
        assertEquals(402, top(rowTop = 300, rowBottom = 397))
        // One more pixel: above the row.
        assertEquals(301 - 5 - 110, top(rowTop = 301, rowBottom = 398))
        // The last row of a full list.
        assertEquals(430 - 5 - 110, top(rowTop = 430, rowBottom = 530))
        // A menu taller than the room above stays on screen.
        assertEquals(0, top(rowTop = 60, rowBottom = 530, menuHeight = 200))
    }
}
