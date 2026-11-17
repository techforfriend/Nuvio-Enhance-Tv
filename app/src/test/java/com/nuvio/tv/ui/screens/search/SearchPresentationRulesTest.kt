package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_ITEM_ID_PREFIX
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character remains in discover without becoming a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
        assertTrue(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "a",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `minimum query leaves discover while live search is pending`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "ab",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location off never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.OFF,
                query = "",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location not yet loaded never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = null,
                query = "",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `suggestions put prefix matches first and otherwise keep the addon's order`() {
        val addonOrder = linkedSetOf("The Lost World: Jurassic Park", "Jurassic Park", "Jurassic City", "Jurassic World")
        assertEquals(
            listOf("Jurassic Park", "Jurassic City", "Jurassic World", "The Lost World: Jurassic Park"),
            rankSuggestions(addonOrder, "JURAS", limit = 8)
        )
        assertEquals(listOf("Jurassic Park", "Jurassic City"), rankSuggestions(addonOrder, "juras", limit = 2))
    }

    @Test
    fun `results rank exact then prefix title matches, otherwise keeping the addon's order`() {
        fun item(name: String) = MetaPreview(
            id = name, type = ContentType.MOVIE, name = name, poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        )
        val addonOrder = listOf("The Lost World: Jurassic Park", "Jurassic World", "Jurassic Park", "Jurassic City").map(::item)

        assertEquals(
            listOf("Jurassic Park", "The Lost World: Jurassic Park", "Jurassic World", "Jurassic City"),
            rankByTitleMatch(addonOrder, "  JURASSIC park ").map { it.name }
        )
        assertEquals(
            listOf("Jurassic World", "Jurassic Park", "Jurassic City", "The Lost World: Jurassic Park"),
            rankByTitleMatch(addonOrder, "jurassic").map { it.name }
        )
        assertEquals(addonOrder, rankByTitleMatch(addonOrder, "  "))
    }

    @Test
    fun `a row shows see all from a full page of real items only`() {
        fun row(count: Int, loadingPlaceholders: Boolean = false) = CatalogRow(
            addonId = "cinemeta", addonName = "Cinemeta", addonBaseUrl = "", catalogId = "top",
            catalogName = "Movies", type = ContentType.MOVIE,
            items = List(count) { i ->
                MetaPreview(
                    id = if (loadingPlaceholders) "${PLACEHOLDER_ITEM_ID_PREFIX}$i" else "tt$i",
                    type = ContentType.MOVIE, name = "Title $i", poster = null,
                    posterShape = PosterShape.POSTER, background = null, logo = null,
                    description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
                )
            },
            isLoading = loadingPlaceholders
        )
        assertTrue(searchRowShowsSeeAll(row(15)))
        assertFalse(searchRowShowsSeeAll(row(14)))
        assertFalse(searchRowShowsSeeAll(row(15, loadingPlaceholders = true)))
    }

    private fun body(
        isDiscoverMode: Boolean = false,
        submittedQuery: String = "sintel",
        pending: Boolean = false,
        isSearching: Boolean = false,
        errorWithoutRows: Boolean = false,
        hasResults: Boolean = false
    ) = searchBody(isDiscoverMode, submittedQuery, pending, isSearching, errorWithoutRows, hasResults)

    @Test
    fun `before a query search shows the start message`() {
        assertEquals(SearchBody.START, body(submittedQuery = ""))
        assertEquals(SearchBody.START, body(submittedQuery = "s"))
        // Discover mode wins over stale rows and errors.
        assertEquals(SearchBody.START, body(isDiscoverMode = true, hasResults = true))
        assertEquals(SearchBody.START, body(isDiscoverMode = true, errorWithoutRows = true))
    }

    @Test
    fun `waiting shows the skeleton only while there are no results`() {
        assertEquals(SearchBody.LOADING, body(isSearching = true))
        // Typed past the minimum but not yet submitted (debounce): skeleton, not the start message.
        assertEquals(SearchBody.LOADING, body(submittedQuery = "", pending = true))
        assertEquals(SearchBody.RESULTS, body(isSearching = true, hasResults = true))
        assertEquals(SearchBody.RESULTS, body(pending = true, hasResults = true))
    }

    @Test
    fun `settled searches show the error or no results`() {
        assertEquals(SearchBody.ERROR, body(errorWithoutRows = true))
        assertEquals(SearchBody.LOADING, body(errorWithoutRows = true, isSearching = true))
        assertEquals(SearchBody.NO_RESULTS, body())
        assertEquals(SearchBody.RESULTS, body(hasResults = true))
    }

    @Test
    fun `down from the field returns to the last card only under the same query`() {
        val rows = listOf("cinemeta|movie", "cinemeta|series", "kitsu|anime")
        // Same query (submitted text may carry spaces): the row's index.
        assertEquals(1, searchCardRestoreRowIndex("cinemeta|series", "sintel", " sintel ", rows))
        // A new query reuses catalog keys with other titles: first-result entry.
        assertEquals(-1, searchCardRestoreRowIndex("cinemeta|series", "sintel", "spring", rows))
        // No card focused yet, or that row is gone from the results.
        assertEquals(-1, searchCardRestoreRowIndex(null, null, "sintel", rows))
        assertEquals(-1, searchCardRestoreRowIndex("tmdb|movie", "sintel", "sintel", rows))
    }
}
