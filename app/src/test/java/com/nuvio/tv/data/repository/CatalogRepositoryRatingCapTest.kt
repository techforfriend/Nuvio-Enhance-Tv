package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogAppExtrasDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * Spec 64 K1b: every addon catalog surface (Home rows, Discover, search, folder and see-all
 * pages) reads through CatalogRepositoryImpl.getCatalog, which filters for the active cap.
 */
class CatalogRepositoryRatingCapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wireJson = NetworkModule.provideWireJson()
    private var cap: ContentRatingCap? = ContentRatingCap.OLDER_KIDS
    private val activeCap = mockk<ActiveRatingCap> { coEvery { current() } answers { cap } }
    private val metaRepository = mockk<MetaRepository>()

    private fun item(id: String, rating: String?, type: String = "movie") = MetaPreviewDto(
        id = id, type = type, name = "Name $id", appExtras = rating?.let { CatalogAppExtrasDto(certification = it) }
    )

    /** g: G, pg: PG, r: R, nr: NR, none: no rating. */
    private val mixed = CatalogResponseDto(
        metas = listOf(item("g", "G"), item("pg", "PG"), item("r", "R"), item("nr", "NR"), item("none", null))
    )

    private fun api(payload: CatalogResponseDto = mixed) = mockk<AddonApi> {
        coEvery { getCatalog(any()) } returns Response.success(payload)
    }

    private fun repository(api: AddonApi): CatalogRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tmp.root
        return CatalogRepositoryImpl(
            context = context,
            api = api,
            healthStore = mockk(relaxed = true),
            wireJson = wireJson,
            activeRatingCap = activeCap,
            ratingFilter = CatalogRatingFilter(metaRepository)
        )
    }

    private suspend fun CatalogRepositoryImpl.rows(
        skip: Int = 0,
        extraArgs: Map<String, String> = emptyMap(),
        type: String = "movie"
    ): List<CatalogRow> = getCatalog(
        addonBaseUrl = "https://addon.example",
        addonId = "addon",
        addonName = "Addon",
        catalogId = "top",
        catalogName = "Top",
        type = type,
        skip = skip,
        skipStep = 100,
        extraArgs = extraArgs,
        supportsSkip = true
    ).toList().filterIsInstance<NetworkResult.Success<CatalogRow>>().map { it.data }

    private fun CatalogRow.ids() = items.map { it.id }

    private fun metaRated(rating: String?) = mockk<Meta> { every { ageRating } returns rating }

    @Test
    fun `home row keeps only titles rated at or under the cap`() = runTest {
        assertEquals(listOf("g", "pg"), repository(api()).rows().single().ids())
    }

    @Test
    fun `uncapped profile sees the whole row`() = runTest {
        cap = null
        assertEquals(listOf("g", "pg", "r", "nr", "none"), repository(api()).rows().single().ids())
    }

    @Test
    fun `discover genre page hides unrated series without meta lookups`() = runTest {
        val payload = CatalogResponseDto(
            metas = listOf(item("s-g", "TV-G", "series"), item("s-none", null, "series"), item("s-nr", "NR", "series"))
        )
        val rows = repository(api(payload)).rows(extraArgs = mapOf("genre" to "Drama"), type = "series")
        assertEquals(listOf("s-g"), rows.single().ids())
        coVerify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
    }

    @Test
    fun `see-all page keeps the addon's paging offsets`() = runTest {
        val row = repository(api()).rows(skip = 100).single()
        assertEquals(listOf("g", "pg"), row.ids())
        assertEquals(105, row.nextSkip)
        assertEquals(true, row.hasMore)
    }

    @Test
    fun `memory tier holds the full row so a later uncapped read sees everything`() = runTest {
        val repository = repository(api())
        assertEquals(listOf("g", "pg"), repository.rows(skip = 100).single().ids())
        cap = null
        assertEquals(listOf("g", "pg", "r", "nr", "none"), repository.rows(skip = 100).single().ids())
    }

    @Test
    fun `memory tier filled by an uncapped read is filtered for a capped one`() = runTest {
        val api = api()
        val repository = repository(api)
        cap = null
        assertEquals(5, repository.rows(skip = 100).single().items.size)
        cap = ContentRatingCap.OLDER_KIDS
        assertEquals(listOf("g", "pg"), repository.rows(skip = 100).single().ids())
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    private fun pagedApi(vararg pages: Pair<Int, CatalogResponseDto>) = mockk<AddonApi> {
        pages.forEach { (pageSkip, payload) ->
            val path = if (pageSkip == 0) "top.json" else "top/skip=$pageSkip.json"
            coEvery { getCatalog("https://addon.example/catalog/movie/$path") } returns Response.success(payload)
        }
    }

    private fun page(vararg ratings: String?) = CatalogResponseDto(
        metas = ratings.mapIndexed { i, rating -> item("${rating ?: "none"}-$i", rating) }
    )

    @Test
    fun `a load-more page the cap empties fetches on to the next page with something visible`() = runTest {
        val api = pagedApi(
            100 to page("R", "R"),
            102 to page("TV-MA"),
            103 to CatalogResponseDto(metas = listOf(item("kid", "G"), item("adult", "R")))
        )
        val row = repository(api).rows(skip = 100).single()
        assertEquals(listOf("kid"), row.ids())
        assertEquals(105, row.nextSkip)
        assertEquals(true, row.hasMore)
    }

    @Test
    fun `hidden-page advance stops after three extra pages`() = runTest {
        val api = pagedApi(100 to page("R"), 101 to page("R"), 102 to page("R"), 103 to page("R"), 104 to page("G"))
        val row = repository(api).rows(skip = 100).single()
        assertEquals(emptyList<String>(), row.ids())
        coVerify(exactly = 4) { api.getCatalog(any()) }
    }

    @Test
    fun `a failed advance fetch surfaces as the load-more error`() = runTest {
        val api = pagedApi(100 to page("R"))
        coEvery { api.getCatalog("https://addon.example/catalog/movie/top/skip=101.json") } throws IOException("offline")
        val results = repository(api).getCatalog(
            addonBaseUrl = "https://addon.example", addonId = "addon", addonName = "Addon", catalogId = "top",
            catalogName = "Top", type = "movie", skip = 100, skipStep = 100, extraArgs = emptyMap(), supportsSkip = true
        ).toList()
        assertTrue(results.first() is NetworkResult.Loading)
        assertTrue(results.drop(1).single() is NetworkResult.Error)
    }

    @Test
    fun `advance stops at an empty raw page`() = runTest {
        val api = pagedApi(100 to page("R"), 101 to CatalogResponseDto(metas = emptyList()))
        val row = repository(api).rows(skip = 100).single()
        assertEquals(emptyList<String>(), row.ids())
        assertEquals(false, row.hasMore)
        coVerify(exactly = 2) { api.getCatalog(any()) }
    }

    @Test
    fun `an empty first page is not advanced`() = runTest {
        val api = pagedApi(0 to page("R"), 1 to page("G"))
        val rows = repository(api).rows()
        assertEquals(listOf(emptyList<String>()), rows.map { it.ids() })
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `disk row without ratings emits empty under a cap, then the rated fresh row`() = runTest {
        // A blob written before K1a: no app_extras on any item.
        val oldBlob = wireJson.encodeToString(
            CatalogResponseDto.serializer(),
            CatalogResponseDto(metas = listOf(MetaPreviewDto(id = "g", type = "movie", name = "Name g")))
        )
        File(tmp.root, "catalog_rows_v1.json").writeText(
            JSONObject().put(
                "https://addon.example/catalog/movie/top.json",
                JSONObject().put("b", oldBlob).put("t", System.currentTimeMillis())
            ).toString()
        )
        val rows = repository(api()).rows()
        assertEquals(listOf(emptyList(), listOf("g", "pg")), rows.map { it.ids() })
    }

    @Test
    fun `search looks up unrated series and keeps those the addon meta rates under the cap`() = runTest {
        val payload = CatalogResponseDto(
            metas = listOf(
                item("s-pg", null, "series"),
                item("s-ma", null, "series"),
                item("s-miss", null, "series"),
                item("s-rated", "TV-G", "series"),
                item("m-none", null, "movie")
            )
        )
        coEvery { metaRepository.getMeta("https://addon.example", "series", "s-pg") } returns
            flowOf(NetworkResult.Loading, NetworkResult.Success(metaRated("TV-PG")))
        coEvery { metaRepository.getMeta("https://addon.example", "series", "s-ma") } returns
            flowOf(NetworkResult.Success(metaRated("TV-MA")))
        coEvery { metaRepository.getMeta("https://addon.example", "series", "s-miss") } returns
            flowOf(NetworkResult.Error("not found"))

        val row = repository(api(payload)).rows(extraArgs = mapOf("search" to "bluey"), type = "series").single()

        assertEquals(listOf("s-pg", "s-rated"), row.ids())
        assertEquals("TV-PG", row.items.first().ageRating)
        // Rated series and movies are never looked up.
        coVerify(exactly = 3) { metaRepository.getMeta(any(), any(), any()) }
    }

    @Test
    fun `search for an uncapped profile makes no meta lookups`() = runTest {
        cap = null
        val payload = CatalogResponseDto(metas = listOf(item("s", null, "series")))
        val rows = repository(api(payload)).rows(extraArgs = mapOf("search" to "x"), type = "series")
        assertEquals(listOf("s"), rows.single().ids())
        coVerify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
    }
}
