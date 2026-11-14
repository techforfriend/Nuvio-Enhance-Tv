package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.profile.ActiveRatingLimit
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 64 K2b: which titles Detail opens under the active profile's rating limit (D13). */
class DetailRatingGateTest {
    private val olderKids = ActiveRatingLimit(ContentRatingCap.OLDER_KIDS, "Zayn")

    @Test
    fun `no limit opens every title, rated or not`() {
        assertNull(detailRatingBlock(meta("NC-17"), limit = null, fromTmdbFallback = false))
        assertNull(detailRatingBlock(meta(null), limit = null, fromTmdbFallback = false))
        assertNull(detailRatingBlock(meta("15"), limit = null, fromTmdbFallback = true))
    }

    @Test
    fun `a rating within the limit opens`() {
        assertNull(detailRatingBlock(meta("PG"), olderKids, fromTmdbFallback = false))
        assertNull(detailRatingBlock(meta("TV-Y"), olderKids, fromTmdbFallback = false))
    }

    @Test
    fun `a rating over the limit blocks with that rating, normalised`() {
        assertEquals(
            DetailRatingBlock(title = "Tears of Steel", rating = "PG-13", limit = olderKids),
            detailRatingBlock(meta(" pg-13 "), olderKids, fromTmdbFallback = false)
        )
    }

    @Test
    fun `unknown, NR and non-US ratings block as unrated`() {
        listOf(null, "", "NR", "15", "PG13").forEach { rating ->
            assertEquals(
                rating.toString(),
                DetailRatingBlock(title = "Tears of Steel", rating = null, limit = olderKids),
                detailRatingBlock(meta(rating), olderKids, fromTmdbFallback = false)
            )
        }
    }

    @Test
    fun `a TMDB fallback meta blocks as unrated even with an allowed-looking rating`() {
        assertEquals(
            DetailRatingBlock(title = "Tears of Steel", rating = null, limit = olderKids),
            detailRatingBlock(meta("PG"), olderKids, fromTmdbFallback = true)
        )
    }

    private fun meta(ageRating: String?) = Meta(
        id = "tt1", type = ContentType.MOVIE, name = "Tears of Steel", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), runtime = null, director = emptyList(), writer = emptyList(), cast = emptyList(),
        castMembers = emptyList(), videos = emptyList(), country = null, awards = null, language = null,
        links = emptyList(), ageRating = ageRating
    )
}
