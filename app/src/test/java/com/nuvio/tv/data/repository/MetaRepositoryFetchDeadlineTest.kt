package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Spec 84 A6: one meta request may run [MetaRepositoryImpl.META_FETCH_TIMEOUT_MS] (28 s), past the
 * 20 s addon default that cut cold long-show metas. The addon below reaches the legacy raw-type
 * path, which runs the request on the collector's coroutine, so runTest's virtual clock drives the
 * deadline (the other lookup paths run it on the repository's IO scope).
 */
class MetaRepositoryFetchDeadlineTest {

    @Test
    fun `a meta answering after 20 s but inside the deadline is read`() = runTest {
        val repository = repositoryAnsweringAfter(25_000)

        val result = repository.getMetaFromAllAddons("movie", CONTENT_ID).last()

        assertEquals("Late", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `a meta past the deadline is cut at the deadline`() = runTest {
        val repository = repositoryAnsweringAfter(29_000)

        val result = repository.getMetaFromAllAddons("movie", CONTENT_ID).last()

        assertTrue(result is NetworkResult.Error)
        assertEquals(MetaRepositoryImpl.META_FETCH_TIMEOUT_MS, testScheduler.currentTime)
    }

    private fun TestScope.repositoryAnsweringAfter(delayMs: Long): MetaRepositoryImpl {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any(), any()) } coAnswers {
            delay(delayMs)
            Response.success(MetaResponseDto(MetaDto(id = CONTENT_ID, type = "movie", name = "Late")))
        }
        val addonRepository = mockk<AddonRepository> {
            // rawTypes declares movie but the meta resource serves only series: no prioritized
            // candidate, so the lookup takes the legacy raw-type path.
            every { getInstalledAddons() } returns flowOf(
                listOf(
                    Addon(
                        id = "test.addon",
                        name = "Test Addon",
                        version = "1.0.0",
                        description = null,
                        logo = null,
                        baseUrl = "https://addon.example",
                        catalogs = emptyList(),
                        types = emptyList(),
                        rawTypes = listOf("movie", "series"),
                        resources = listOf(AddonResource(name = "meta", types = listOf("series"), idPrefixes = null)),
                        idPrefixes = listOf("tt")
                    )
                )
            )
        }
        return MetaRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true)
        )
    }

    private companion object {
        const val CONTENT_ID = "tt0944947"
    }
}
