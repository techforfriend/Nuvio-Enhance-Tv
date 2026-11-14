package com.nuvio.tv.core.tmdb

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.testRatingCap
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbCollectionResponse
import com.nuvio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbListDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbListItem
import com.nuvio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.http.Query

class TmdbCollectionSourceResolverTest {
    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<TmdbSettingsDataStore> {
        every { this@mockk.settings } returns MutableStateFlow(TmdbSettings(language = "en"))
    }

    @Test
    fun `parseTmdbId accepts ids and tmdb urls`() {
        val resolver = TmdbCollectionSourceResolver(context, mockk(relaxed = true), settings, TEST_TMDB_KEYS, testRatingCap())

        assertEquals(123, resolver.parseTmdbId("123"))
        assertEquals(456, resolver.parseTmdbId("https://www.themoviedb.org/list/456-marvel"))
        assertEquals(789, resolver.parseTmdbId("https://www.themoviedb.org/collection/789"))
    }

    @Test
    fun `movie discover uses primary release date query params`() {
        val queryNames = TmdbApi::class.java.methods
            .first { it.name == "discoverMovies" }
            .parameterAnnotations
            .flatMap { annotations -> annotations.mapNotNull { it as? Query } }
            .map { it.value }

        assertTrue("primary_release_date.gte" in queryNames)
        assertTrue("primary_release_date.lte" in queryNames)
        assertFalse("release_date.gte" in queryNames)
        assertFalse("release_date.lte" in queryNames)
        assertTrue("without_companies" in queryNames)
        assertTrue("without_genres" in queryNames)
        assertTrue("without_keywords" in queryNames)
        assertTrue("without_watch_providers" in queryNames)
    }

    @Test
    fun `list source maps items and pagination`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getListDetails(44, any(), "en", 2) } returns Response.success(
            TmdbListDetailsResponse(
                id = 44,
                name = "A List",
                items = listOf(
                    TmdbListItem(
                        id = 10,
                        title = "Movie",
                        mediaType = "movie",
                        posterPath = "/poster.jpg",
                        releaseDate = "2024-01-01",
                        voteAverage = 7.5
                    ),
                    TmdbListItem(
                        id = 11,
                        name = "Show",
                        mediaType = "tv",
                        posterPath = "/show.jpg",
                        firstAirDate = "2023-02-03",
                        voteAverage = 8.0
                    )
                ),
                page = 2,
                totalPages = 3
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())
        val result = resolver.resolve(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.LIST,
                title = "Imported",
                tmdbId = 44
            ),
            page = 2
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("Imported", result.data.catalogName)
        assertEquals(2, result.data.items.size)
        assertEquals("tmdb:10", result.data.items[0].id)
        assertEquals("series", result.data.items[1].apiType)
        // tmdbPreview: the list mapper takes releaseDate, else firstAirDate.
        assertEquals("movie", result.data.items[0].rawType)
        assertEquals("2024", result.data.items[0].releaseInfo)
        assertEquals("2024-01-01", result.data.items[0].released)
        assertEquals("series", result.data.items[1].rawType)
        assertEquals("2023", result.data.items[1].releaseInfo)
        assertEquals("2023-02-03", result.data.items[1].released)
        assertEquals(2, result.data.currentPage)
        assertTrue(result.data.hasMore)
    }

    @Test
    fun `with no key a source resolves to the typed no-key error and nothing is requested`() = runTest {
        every { context.getString(R.string.tmdb_needs_key) } returns "Needs a TMDB key"
        val api = mockk<TmdbApi>() // strict: any call fails the test
        val resolver = TmdbCollectionSourceResolver(context, api, settings, { "" }, testRatingCap())

        val sources = listOf(
            TmdbCollectionSource(sourceType = TmdbCollectionSourceType.LIST, title = "List", tmdbId = 44),
            TmdbCollectionSource(sourceType = TmdbCollectionSourceType.COLLECTION, title = "Saga", tmdbId = 10),
            TmdbCollectionSource(sourceType = TmdbCollectionSourceType.PERSON, title = "Person", tmdbId = 7),
            TmdbCollectionSource(sourceType = TmdbCollectionSourceType.DISCOVER, title = "Discover")
        )
        sources.forEach { source ->
            val error = resolver.resolve(source).first { it is NetworkResult.Error } as NetworkResult.Error
            assertEquals(source.sourceType.name, NetworkResult.NO_TMDB_KEY_CODE, error.code)
            assertEquals("Needs a TMDB key", error.message)
        }

        // The collection editor shows the message instead of "TMDB list not found".
        val thrown = runCatching { resolver.listImportMetadata(44) }.exceptionOrNull()
        assertTrue("got $thrown", thrown is TmdbNoKeyException)
        assertEquals("Needs a TMDB key", thrown?.message)
        assertTrue(runCatching { resolver.searchCompanies("pixar") }.exceptionOrNull() is TmdbNoKeyException)
    }

    @Test
    fun `a capped profile gets an empty last page and nothing is requested`() = runTest {
        val api = mockk<TmdbApi>() // strict: any call fails the test
        val resolver = TmdbCollectionSourceResolver(
            context, api, settings, TEST_TMDB_KEYS, testRatingCap(ContentRatingCap.TEENS)
        )
        val source = TmdbCollectionSource(sourceType = TmdbCollectionSourceType.PERSON, title = "Person", tmdbId = 7)

        val result = resolver.resolve(source).first { it !is NetworkResult.Loading } as NetworkResult.Success

        assertTrue(result.data.items.isEmpty())
        assertFalse(result.data.hasMore)
    }

    @Test
    fun `list import metadata uses tmdb list name`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getListDetails(44, TEST_TMDB_KEY, "en", 1) } returns Response.success(
            TmdbListDetailsResponse(
                id = 44,
                name = "Weekend Watchlist"
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())

        val metadata = resolver.listImportMetadata(44)

        assertEquals("Weekend Watchlist", metadata.title)
    }

    @Test
    fun `collection import metadata uses name and poster cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCollectionDetails(10, any(), "en") } returns Response.success(
            TmdbCollectionResponse(
                id = 10,
                name = "Star Wars Collection",
                posterPath = "/collection.jpg",
                backdropPath = "/collection-backdrop.jpg"
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())

        val metadata = resolver.collectionImportMetadata(10)

        assertEquals("Star Wars Collection", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/collection.jpg", metadata.coverImageUrl)
    }

    @Test
    fun `company import metadata uses name and logo cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCompanyDetails(420, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(
                id = 420,
                name = "Marvel Studios",
                logoPath = "/marvel.png"
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())

        val metadata = resolver.companyImportMetadata(420)

        assertEquals("Marvel Studios", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/marvel.png", metadata.coverImageUrl)
    }

    @Test
    fun `network import metadata uses name and logo cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getNetworkDetails(2552, any()) } returns Response.success(
            TmdbNetworkDetailsResponse(
                id = 2552,
                name = "Apple TV+",
                logoPath = "/apple.png"
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())

        val metadata = resolver.networkImportMetadata(2552)

        assertEquals("Apple TV+", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/apple.png", metadata.coverImageUrl)
    }

    @Test
    fun `tv discover maps first air date and series type`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.discoverTv(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Response.success(
            TmdbDiscoverResponse(
                page = 1,
                totalPages = 1,
                results = listOf(
                    TmdbDiscoverResult(
                        id = 7,
                        name = "Show",
                        releaseDate = "1999-09-09",
                        firstAirDate = "2021-05-06"
                    )
                )
            )
        )
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())
        val result = resolver.resolve(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.DISCOVER,
                title = "Shows",
                mediaType = TmdbCollectionMediaType.TV
            )
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        val item = result.data.items.single()
        assertEquals("series", item.rawType)
        assertEquals("2021", item.releaseInfo)
        assertEquals("2021-05-06", item.released)
    }

    @Test
    fun `discover source forwards advanced movie filters`() = runTest {
        val api = mockk<TmdbApi>()
        var capturedGenres: String? = null
        var capturedDateGte: String? = null
        var capturedRating: Double? = null
        var capturedKeywords: String? = null
        var capturedCompanies: String? = null
        var capturedWithoutCompanies: String? = null
        var capturedWithoutGenres: String? = null
        var capturedWithoutKeywords: String? = null
        var capturedWithoutWatchProviders: String? = null
        coEvery {
            api.discoverMovies(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            capturedCompanies = arg(4)
            capturedGenres = arg(7)
            capturedDateGte = arg(8)
            capturedRating = arg(9)
            capturedKeywords = arg(13)
            capturedWithoutCompanies = arg(18)
            capturedWithoutGenres = arg(19)
            capturedWithoutKeywords = arg(20)
            capturedWithoutWatchProviders = arg(21)
            Response.success(
                TmdbDiscoverResponse(
                    page = 1,
                    totalPages = 1,
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 99,
                            title = "Filtered",
                            posterPath = "/filtered.jpg",
                            releaseDate = "2020-01-01"
                        )
                    )
                )
            )
        }
        val resolver = TmdbCollectionSourceResolver(context, api, settings, TEST_TMDB_KEYS, testRatingCap())
        val result = resolver.resolve(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.DISCOVER,
                title = "Filtered",
                mediaType = TmdbCollectionMediaType.MOVIE,
                filters = TmdbCollectionFilters(
                    withGenres = "28,12",
                    releaseDateGte = "2020-01-01",
                    voteAverageGte = 7.0,
                    withKeywords = "9715",
                    withCompanies = "420",
                    withoutCompanies = "2",
                    withoutGenres = "16",
                    withoutKeywords = "818",
                    withoutWatchProviders = "8|337"
                )
            )
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("28,12", capturedGenres)
        assertEquals("2020-01-01", capturedDateGte)
        assertEquals(7.0, capturedRating)
        assertEquals("9715", capturedKeywords)
        assertEquals("420", capturedCompanies)
        assertEquals("2", capturedWithoutCompanies)
        assertEquals("16", capturedWithoutGenres)
        assertEquals("818", capturedWithoutKeywords)
        assertEquals("8|337", capturedWithoutWatchProviders)
        assertEquals("tmdb:99", result.data.items.single().id)
        assertEquals("2020", result.data.items.single().releaseInfo)
        assertEquals("2020-01-01", result.data.items.single().released)
        assertEquals("https://image.tmdb.org/t/p/w500/filtered.jpg", result.data.items.single().poster)
    }
}
