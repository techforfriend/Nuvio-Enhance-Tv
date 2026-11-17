package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.trailerpreview.TrailerPreviewCandidate
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 96 PD3: which cards may preview, and what they hand the preview. */
class TrailerPreviewCandidateTest {
    private val meta = MetaPreview(
        id = "tt1", type = ContentType.MOVIE, name = "Spring", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = "2019", imdbRating = 7.6f,
        genres = listOf("Fantasy", "Animation"), runtime = "8 min", trailerYtIds = listOf("yt1", "yt2")
    )

    private fun item(itemId: String = "tt1", metaPreview: MetaPreview? = meta) = ModernCarouselItem(
        key = "k", title = "Spring", subtitle = null, imageUrl = null,
        heroPreview = HeroPreview(
            title = "Spring", logo = null, description = null, episodeLabel = null, yearText = null, imdbText = null,
            genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null
        ),
        payload = ModernPayload.Catalog(
            focusKey = "row_tt1", itemId = itemId, itemType = "movie", addonBaseUrl = "",
            trailerTitle = "Spring", trailerReleaseInfo = "2019", trailerApiType = "movie"
        ),
        metaPreview = metaPreview
    )

    @Test
    fun `a catalog card hands over its focus key, id, first YouTube id and meta`() {
        assertEquals(
            TrailerPreviewCandidate("row_tt1", "tt1", "Spring", "2019", "movie", "yt1", "Fantasy", "8 min", 7.6f),
            item().trailerPreviewCandidate()
        )
    }

    @Test
    fun `placeholders, cards without meta and non-catalog cards never preview`() {
        assertNull(item(itemId = "${PLACEHOLDER_ITEM_ID_PREFIX}1").trailerPreviewCandidate())
        assertNull(item(metaPreview = null).trailerPreviewCandidate())
        val cw = item().copy(payload = ModernPayload.ContinueWatching(io.mockk.mockk(relaxed = true)))
        assertNull(cw.trailerPreviewCandidate())
    }

    @Test
    fun `the row slot widens from the rest width and keeps the row's height in view`() {
        assertEquals(224, previewRowSlotWidth(rest = 224, full = 640, progress = 0f))
        assertEquals(432, previewRowSlotWidth(rest = 224, full = 640, progress = 0.5f))
        assertEquals(640, previewRowSlotWidth(rest = 224, full = 640, progress = 1f))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 640f, 336f), previewRowBringRect(640f, 336f))
    }

    @Test
    fun `poster-height rows overflow evenly, landscape rows downward`() {
        assertEquals(-6, previewRowCardY(slotHeight = 336, cardHeight = 348, landscapeRow = false)) // poster / wide card
        assertEquals(0, previewRowCardY(slotHeight = 256, cardHeight = 360, landscapeRow = true))
    }
}
