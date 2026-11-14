package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.testRatingCap
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.CatalogRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/** Wave C S4 C4-3: the memory and disk tiers of CatalogRepositoryImpl (class KDoc). */
class CatalogRepositoryCacheTiersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wireJson = NetworkModule.provideWireJson()

    private fun payload(vararg ids: String) =
        CatalogResponseDto(metas = ids.map { MetaPreviewDto(id = it, type = "movie", name = "Name $it") })

    private fun repository(api: AddonApi): CatalogRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tmp.root
        return CatalogRepositoryImpl(
            context = context,
            api = api,
            healthStore = mockk(relaxed = true),
            wireJson = wireJson,
            activeRatingCap = testRatingCap(),
            ratingFilter = CatalogRatingFilter(mockk())
        )
    }

    private suspend fun CatalogRepositoryImpl.rows(skip: Int = 0, extraArgs: Map<String, String> = emptyMap()) =
        getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = skip,
            skipStep = 100,
            extraArgs = extraArgs,
            supportsSkip = true
        ).toList().filterNot { it is NetworkResult.Loading }

    private fun NetworkResult<CatalogRow>.ids() = (this as NetworkResult.Success).data.items.map { it.id }

    private val diskFile get() = File(tmp.root, "catalog_rows_v1.json")

    /** The disk write is fire-and-forget on the repository's IO scope. */
    private fun awaitDiskKeys(count: Int): Set<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (diskFile.exists()) {
                val keys = JSONObject(diskFile.readText()).keys().asSequence().toSet()
                if (keys.size >= count) return keys
            }
            Thread.sleep(20)
        }
        error("disk cache not written")
    }

    /** First instance fetches page 1 so the disk tier holds it; a second instance starts with an empty memory tier. */
    private suspend fun seedDisk() {
        val seedApi = mockk<AddonApi>()
        coEvery { seedApi.getCatalog(any()) } returns Response.success(payload("tt1"))
        repository(seedApi).rows()
        awaitDiskKeys(1)
    }

    @Test
    fun `memory tier serves a repeat within the TTL without the network`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt1"))
        val repository = repository(api)
        assertEquals(listOf("tt1"), repository.rows(skip = 10).single().ids())
        assertEquals(listOf("tt1"), repository.rows(skip = 10).single().ids())
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `clearCaches forces the network again`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt1"))
        val repository = repository(api)
        repository.rows(skip = 10)
        repository.clearCaches()
        repository.rows(skip = 10)
        coVerify(exactly = 2) { api.getCatalog(any()) }
    }

    @Test
    fun `clearCaches drops the disk tier too`() = runTest {
        seedDisk()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt2"))
        val repository = repository(api)
        repository.clearCaches()
        val emitted = repository.rows()
        assertEquals(listOf(listOf("tt2")), emitted.map { it.ids() })
    }

    @Test
    fun `only the first page without extras is written to disk`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt1"))
        val repository = repository(api)
        repository.rows(skip = 10)
        repository.rows(extraArgs = mapOf("genre" to "Drama"))
        repository.rows()
        assertEquals(setOf("https://addon.example/catalog/movie/top.json"), awaitDiskKeys(1))
    }

    @Test
    fun `disk hit serves the stored row, then the fresh one`() = runTest {
        seedDisk()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt2"))
        val emitted = repository(api).rows()
        assertEquals(listOf(listOf("tt1"), listOf("tt2")), emitted.map { it.ids() })
    }

    @Test
    fun `disk hit with an identical fresh row emits once`() = runTest {
        seedDisk()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(payload("tt1"))
        val emitted = repository(api).rows()
        assertEquals(listOf(listOf("tt1")), emitted.map { it.ids() })
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }

    @Test
    fun `disk hit keeps the stored row on a network error`() = runTest {
        seedDisk()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } throws IOException("offline")
        val emitted = repository(api).rows()
        assertEquals(listOf(listOf("tt1")), emitted.map { it.ids() })
        assertTrue(emitted.none { it is NetworkResult.Error })
    }
}
