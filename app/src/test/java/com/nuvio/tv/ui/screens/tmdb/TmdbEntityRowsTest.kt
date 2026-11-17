package com.nuvio.tv.ui.screens.tmdb

import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityHeader
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.home.LayoutUiPrefs
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbEntityRowsTest {

    private fun item(id: String, type: ContentType) = MetaPreview(
        id = id,
        type = type,
        name = id,
        poster = "https://image.tmdb.org/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private val moviePopular = TmdbEntityRail(
        mediaType = TmdbEntityMediaType.MOVIE,
        railType = TmdbEntityRailType.POPULAR,
        items = listOf(item("tmdb:1", ContentType.MOVIE)),
        currentPage = 2,
        hasMore = false,
        isLoading = true
    )
    private val tvTopRated = TmdbEntityRail(
        mediaType = TmdbEntityMediaType.TV,
        railType = TmdbEntityRailType.TOP_RATED,
        items = listOf(item("tmdb:2", ContentType.SERIES)),
        hasMore = true
    )

    private fun header(headquarters: String? = null, country: String? = null) = TmdbEntityHeader(
        id = 7,
        kind = TmdbEntityKind.COMPANY,
        name = "Blender Studio",
        logo = null,
        originCountry = country,
        secondaryLabel = headquarters,
        description = null
    )

    @Test
    fun `rails become catalog rows keyed by media and rail with the rail's paging state`() {
        val rows = entityCatalogRows(listOf(moviePopular, tvTopRated)) { media, rail -> "${rail.value} · ${media.value}" }

        assertEquals(listOf("movie_popular", "tv_top_rated"), rows.map { it.catalogId })
        assertEquals(listOf("popular · movie", "top_rated · tv"), rows.map { it.catalogName })
        assertEquals(listOf("movie", "series"), rows.map { it.apiType })
        assertTrue(rows.all { it.addonId == ENTITY_ROWS_ADDON_ID && it.addonBaseUrl.isEmpty() && it.addonName.isEmpty() })

        val movies = rows[0]
        assertSame(moviePopular.items, movies.items)
        assertFalse(movies.hasMore) // set from the rail, not CatalogRow's default true
        assertTrue(movies.isLoading)
        assertEquals(2, movies.currentPage)
        assertTrue(rows[1].hasMore)
        assertFalse(rows[1].isLoading)
        // Without it MarqueeHomeRows never asks for the next page.
        assertTrue(rows.all { it.supportsSkip })
    }

    @Test
    fun `a row's load-more request finds its rail and only for this page's rows`() {
        val data = TmdbEntityBrowseData(header = header(), rails = listOf(moviePopular, tvTopRated))

        assertSame(tvTopRated, data.railForCatalog("tv_top_rated", ENTITY_ROWS_ADDON_ID))
        assertNull(data.railForCatalog("tv_top_rated", "some.addon"))
        assertNull(data.railForCatalog("movie_recent", ENTITY_ROWS_ADDON_ID))
    }

    @Test
    fun `entry mode is the rows for a network or no description, else the description's layout decides`() {
        val described = header().copy(description = "Blender Studio is the film studio of the Blender Foundation.")

        assertNull(entityEntryMode(described))
        assertEquals(EntityPageMode.ROWS, entityEntryMode(header()))
        assertEquals(EntityPageMode.ROWS, entityEntryMode(header().copy(description = "  ")))
        assertEquals(EntityPageMode.ROWS, entityEntryMode(described.copy(kind = TmdbEntityKind.NETWORK)))
    }

    @Test
    fun `subline is the headquarters, else the origin country's name, else none`() {
        assertEquals("Amsterdam, Netherlands", entityHeaderSubline(header("Amsterdam, Netherlands", "NL"), Locale.US))
        assertEquals("Netherlands", entityHeaderSubline(header(headquarters = " ", country = "NL"), Locale.US))
        assertNull(entityHeaderSubline(header(), Locale.US))
    }

    @Test
    fun `layout prefs drop the type suffix, addon name and trailers and keep the rest`() {
        val prefs = LayoutUiPrefs(
            catalogAddonNameEnabled = true,
            catalogTypeSuffixEnabled = true,
            hideUnreleasedContent = true,
            showFullReleaseDate = false,
            modernLandscapePostersEnabled = true,
            homeImdbRatingsVisibility = HomeImdbRatingsVisibility.HIDE_ALL,
            trailerAutoplayMode = TrailerAutoplayMode.CARD,
            focusedPosterBackdropTrailerMuted = false
        )

        assertEquals(
            prefs.copy(
                catalogAddonNameEnabled = false,
                catalogTypeSuffixEnabled = false,
                trailerAutoplayMode = TrailerAutoplayMode.OFF
            ),
            entityLayoutPrefs(prefs)
        )
    }
}
