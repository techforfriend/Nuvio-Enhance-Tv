package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Spec 95 M1 (D1, D2): an addon's error placeholder meta is a failed answer, not meta. */
class MetaRepositoryErrorPlaceholderTest {

    private val contentId = "tt0988824"

    @Test
    fun `the addons' own error shapes are placeholders`() {
        // AIOStreams main/resources.ts: a failed sub-addon lookup.
        assertTrue(isAddonErrorMeta(contentId, "[\u274C] Anime Kitsu"))
        // AIOStreams transformers/stremio.ts createErrorMeta.
        assertTrue(isAddonErrorMeta("aiostreamserror.%7B%7D", "AIOStreams - Error"))
        assertTrue(isAddonErrorMeta("x", "  [\u274C] AIOStreams - Error"))
        // NexoTV middleware/rateLimiter.ts.
        assertTrue(
            isAddonErrorMeta(
                "ratelimit_error",
                "\u26A0\uFE0F Rate limit exceeded \u2014 please wait a few minutes before trying again."
            )
        )
    }

    @Test
    fun `real titles are kept`() {
        assertFalse(isAddonErrorMeta(contentId, "[Dub] Naruto Shippuuden"))
        assertFalse(isAddonErrorMeta(contentId, "Rate My Plate"))
        assertFalse(isAddonErrorMeta(contentId, "Too Many Requests"))
        assertFalse(isAddonErrorMeta(contentId, "The \u274C Factor"))
        assertFalse(isAddonErrorMeta("ratelimit_error_2", "Real Title"))
    }

    @Test
    fun `a placeholder from the first addon falls through to the next`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(match { it.startsWith("https://aio.example") }) } returns
            metaResponse("[\u274C] Anime Kitsu", "Request for meta for Anime Kitsu timed out after 30000ms")
        coEvery { api.getMeta(match { it.startsWith("https://cinemeta.example") }) } returns
            metaResponse("Breaking Bad")
        val repository = newRepository(api, addon("aio", "https://aio.example"), addon("cm", "https://cinemeta.example"))

        val result = repository.getMetaFromAllAddons("series", contentId).last()

        assertEquals("Breaking Bad", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `a placeholder alone is a retryable failure, never cached`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("[\u274C] Anime Kitsu", "timed out")
        val repository = newRepository(api, addon("aio", "https://aio.example"))

        val first = repository.getMetaFromAllAddons("series", contentId).last()
        val second = repository.getMetaFromAllAddons("series", contentId).last()

        assertNull("a failed upstream is not a final not-found", (first as NetworkResult.Error).code)
        assertNull((second as NetworkResult.Error).code)
        coVerify(exactly = 2) { api.getMeta(any()) }
        assertNull(repository.getCachedMeta("series", contentId))
    }

    @Test
    fun `a placeholder on the legacy fallback path stays retryable`() = runTest {
        // idPrefixes reject the tt id, so no prioritized candidate: the raw-type fallback runs.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("[\u274C] Anime Kitsu", "timed out")
        val repository = newRepository(api, addon("aio", "https://aio.example", idPrefixes = listOf("kitsu")))

        val result = repository.getMetaFromAllAddons("series", contentId).last()

        assertNull((result as NetworkResult.Error).code)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `a placeholder from the primary addon is never cached`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns Response.success(
            MetaResponseDto(meta = MetaDto(id = "aiostreamserror.%7B%7D", type = "movie", name = "AIOStreams - Error"))
        )
        val repository = newRepository(api, addon("aio", "https://aio.example"))

        val first = repository.getMetaFromPrimaryAddon("series", contentId).last()
        val second = repository.getMetaFromPrimaryAddon("series", contentId).last()

        assertTrue(first is NetworkResult.Error)
        assertTrue(second is NetworkResult.Error)
        coVerify(exactly = 2) { api.getMeta(any()) }
    }

    @Test
    fun `a placeholder on the single-addon path is never cached`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("[\u274C] Anime Kitsu")
        val repository = newRepository(api, addon("aio", "https://aio.example"))

        repository.getMeta("https://aio.example", "series", contentId).last()
        val second = repository.getMeta("https://aio.example", "series", contentId).last()

        assertTrue(second is NetworkResult.Error)
        coVerify(exactly = 2) { api.getMeta(any()) }
    }

    private fun metaResponse(name: String, description: String? = null): Response<MetaResponseDto> =
        Response.success(
            MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = name, description = description))
        )

    private fun addon(id: String, baseUrl: String, idPrefixes: List<String> = listOf("tt")) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.SERIES),
        rawTypes = listOf("series"),
        resources = listOf(AddonResource(name = "meta", types = listOf("series"), idPrefixes = null)),
        idPrefixes = idPrefixes
    )

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "Meta error"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )
    }
}
