package com.nuvio.tv.ui.components.trailerpreview

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 123 C5: the Morph's fade ramp and the cover rect shared by the video layer and the poster. */
class TrailerPreviewMorphTest {
    @Test
    fun `the ramp is 0 before its start, 1 after its end, linear between`() {
        assertEquals(0f, trailerPreviewRamp(0.3f, 0.5f, 0.92f), 0f)
        assertEquals(0.5f, trailerPreviewRamp(0.71f, 0.5f, 0.92f), 1e-4f)
        assertEquals(1f, trailerPreviewRamp(0.95f, 0.5f, 0.92f), 0f)
    }

    @Test
    fun `a 16x9 frame covers a portrait cell by its height, centred`() {
        val cell = Rect(100f, 50f, 220f, 230f) // 120x180
        assertEquals(Rect(0f, 50f, 320f, 230f), trailerPreviewCoverRect(cell, Size(1600f, 900f)))
    }

    @Test
    fun `a 16x9 frame on a 16x9 rect is the rect itself`() {
        val card = Rect(52f, 315f, 404f, 513f) // 352x198
        val cover = trailerPreviewCoverRect(card, Size(800f, 450f))
        assertEquals(card.left, cover.left, 1e-3f)
        assertEquals(card.top, cover.top, 1e-3f)
        assertEquals(card.width, cover.width, 1e-3f)
        assertEquals(card.height, cover.height, 1e-3f)
    }

    @Test
    fun `a portrait poster covers a 16x9 card by its width, centred`() {
        val card = Rect(0f, 0f, 352f, 198f)
        val cover = trailerPreviewCoverRect(card, Size(168f, 252f))
        assertEquals(0f, cover.left, 1e-3f)
        assertEquals(352f, cover.width, 1e-3f)
        assertEquals(99f, cover.center.y, 1e-3f)
    }
}
