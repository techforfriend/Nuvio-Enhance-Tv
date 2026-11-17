package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import com.nuvio.tv.data.trailer.TrailerService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Spec 96 PD2: the card's trailer resolves in Home's order. */
class TrailerPreviewResolveTest {
    private val service = mockk<TrailerService>()
    private val ytUrl = "https://www.youtube.com/watch?v=yt1"

    private fun candidate(fallbackYtId: String? = "yt1") =
        TrailerPreviewCandidate("a", "tt1", "Spring", "2019", "movie", fallbackYtId)

    private fun itemLookupReturns(source: TrailerPlaybackSource?) =
        coEvery { service.getTrailerPlaybackSourceForItem("tt1", "Spring", "2019", "movie") } returns source

    @Test
    fun `the item lookup wins and the YouTube id is not asked`() = runTest {
        itemLookupReturns(TrailerPlaybackSource("https://v/item", "https://a/item"))
        assertEquals(
            TrailerPreviewSource("https://v/item", "https://a/item"),
            resolveTrailerPreviewSource(service, candidate())
        )
        coVerify(exactly = 0) { service.getTrailerPlaybackSourceFromYouTubeUrl(any()) }
    }

    @Test
    fun `nothing or a blank URL from the lookup falls back to the first YouTube id`() = runTest {
        coEvery { service.getTrailerPlaybackSourceFromYouTubeUrl(ytUrl) } returns TrailerPlaybackSource("https://v/yt", "")
        for (lookup in listOf(null, TrailerPlaybackSource(" "))) {
            itemLookupReturns(lookup)
            // A blank audio URL is none.
            assertEquals(TrailerPreviewSource("https://v/yt", null), resolveTrailerPreviewSource(service, candidate()))
        }
    }

    @Test
    fun `no YouTube id or a blank fallback is no trailer`() = runTest {
        itemLookupReturns(null)
        assertNull(resolveTrailerPreviewSource(service, candidate(fallbackYtId = null)))
        coEvery { service.getTrailerPlaybackSourceFromYouTubeUrl(ytUrl) } returns TrailerPlaybackSource("")
        assertNull(resolveTrailerPreviewSource(service, candidate()))
    }

    @Test
    fun `a failed lookup is no trailer, a cancellation is rethrown`() = runTest {
        coEvery { service.getTrailerPlaybackSourceForItem(any(), any(), any(), any()) } throws IllegalStateException()
        assertNull(resolveTrailerPreviewSource(service, candidate()))
        coEvery { service.getTrailerPlaybackSourceForItem(any(), any(), any(), any()) } throws CancellationException()
        try {
            resolveTrailerPreviewSource(service, candidate())
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
        }
    }
}
