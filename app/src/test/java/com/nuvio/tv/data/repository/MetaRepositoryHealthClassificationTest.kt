package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
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
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Meta-miss health classification: an addon answering "no such item" (Stremio
 * 404, or a 200 carrying no meta like AIOMetadata's `{"meta":null}`) is a
 * working addon with nothing to give, and stamps EMPTY. Only transport and
 * parse failures, and an addon's own error placeholder meta (Spec 95 M1),
 * stamp FAILURE. Regression guard: misses used to stamp FAILURE,
 * so two ordinary misses in a row tripped the Down breaker on a healthy addon.
 * Also pins the deliberately-unchanged emission contract: miss and failure both
 * end the flow in the same NetworkResult.Error message.
 */
class MetaRepositoryHealthClassificationTest {

    private val contentId = "tt0944947"
    private val addonBaseUrl = "https://addon.example"

    @Test
    fun `200 with no meta is a miss and stamps EMPTY`() = runTest {
        val healthStore = recordingHealthStore()
        val repository = newRepository(
            apiReturning { Response.success(MetaResponseDto(meta = null)) },
            healthStore
        )

        val result = repository.getMeta(addonBaseUrl, "tv", contentId).last()

        assertEquals(META_NOT_FOUND_STUB, (result as NetworkResult.Error).message)
        assertSingleSample(healthStore, HealthOutcome.EMPTY)
    }

    @Test
    fun `404 is a miss and stamps EMPTY`() = runTest {
        val healthStore = recordingHealthStore()
        val repository = newRepository(
            apiReturning { Response.error(404, errorBody()) },
            healthStore
        )

        val result = repository.getMeta(addonBaseUrl, "tv", contentId).last()

        assertEquals(META_NOT_FOUND_STUB, (result as NetworkResult.Error).message)
        assertSingleSample(healthStore, HealthOutcome.EMPTY)
    }

    @Test
    fun `HTTP 500 stamps FAILURE`() = runTest {
        val healthStore = recordingHealthStore()
        val repository = newRepository(
            apiReturning { Response.error(500, errorBody()) },
            healthStore
        )

        val result = repository.getMeta(addonBaseUrl, "tv", contentId).last()

        // Same error emission as a miss: callers cannot (and must not) tell apart.
        assertEquals(META_NOT_FOUND_STUB, (result as NetworkResult.Error).message)
        assertSingleSample(healthStore, HealthOutcome.FAILURE)
    }

    @Test
    fun `transport failure stamps FAILURE`() = runTest {
        val healthStore = recordingHealthStore()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } throws IOException("boom")
        val repository = newRepository(api, healthStore)

        repository.getMeta(addonBaseUrl, "tv", contentId).last()

        assertSingleSample(healthStore, HealthOutcome.FAILURE)
    }

    @Test
    fun `error placeholder meta stamps FAILURE`() = runTest {
        val healthStore = recordingHealthStore()
        val repository = newRepository(
            apiReturning {
                Response.success(
                    MetaResponseDto(meta = MetaDto(id = "ratelimit_error", type = "series", name = "Rate limit exceeded"))
                )
            },
            healthStore
        )

        val result = repository.getMeta(addonBaseUrl, "tv", contentId).last()

        assertEquals(META_NOT_FOUND_STUB, (result as NetworkResult.Error).message)
        assertSingleSample(healthStore, HealthOutcome.FAILURE)
    }

    @Test
    fun `found meta stamps SUCCESS`() = runTest {
        val healthStore = recordingHealthStore()
        val repository = newRepository(
            apiReturning {
                Response.success(
                    MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = "Test"))
                )
            },
            healthStore
        )

        val result = repository.getMeta(addonBaseUrl, "tv", contentId).last()

        assertTrue(result is NetworkResult.Success)
        assertSingleSample(healthStore, HealthOutcome.SUCCESS)
    }

    /**
     * Exactly one sample of [outcome] for this fetch — the total-count check
     * also catches a double-stamp regression that a bare "never FAILURE" check
     * could miss when the extra call lands after the assertion runs.
     */
    private fun assertSingleSample(healthStore: AddonHealthStore, outcome: HealthOutcome) {
        coVerify(exactly = 1, timeout = VERIFY_TIMEOUT_MS) {
            healthStore.record("addon:$addonBaseUrl", outcome, any())
        }
        coVerify(exactly = 1, timeout = VERIFY_TIMEOUT_MS) {
            healthStore.record(any(), any(), any())
        }
    }

    private fun errorBody(): ResponseBody = "".toResponseBody()

    private fun apiReturning(response: () -> Response<MetaResponseDto>): AddonApi =
        mockk<AddonApi>().also { api ->
            coEvery { api.getMeta(any()) } coAnswers { response() }
        }

    private fun recordingHealthStore(): AddonHealthStore = mockk(relaxed = true)

    private fun newRepository(api: AddonApi, healthStore: AddonHealthStore): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
            every { getString(R.string.error_meta_not_found) } returns META_NOT_FOUND_STUB
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(
                listOf(
                    Addon(
                        id = "test.addon",
                        name = "Test Addon",
                        version = "1.0.0",
                        description = null,
                        logo = null,
                        baseUrl = addonBaseUrl,
                        catalogs = emptyList(),
                        types = listOf(ContentType.MOVIE, ContentType.SERIES),
                        rawTypes = listOf("movie", "series"),
                        resources = listOf(
                            AddonResource(name = "meta", types = listOf("series"), idPrefixes = null)
                        ),
                        idPrefixes = listOf("tt")
                    )
                )
            )
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = healthStore
        )
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 5_000L
        const val META_NOT_FOUND_STUB = "meta-not-found-stub"
    }
}
