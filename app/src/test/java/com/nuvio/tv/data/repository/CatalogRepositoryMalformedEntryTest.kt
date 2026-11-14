package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.testRatingCap
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryMalformedEntryTest {
    @Test
    fun `catalog skips entries without id or name and preserves pagination count`() = runTest {
        val payload =
            """
            {
              "metas": [
                { "id": "tt1", "type": "series", "name": "First" },
                { "id": "tt2", "type": "series" },
                null,
                { "id": " ", "type": "series", "name": "Missing ID" },
                { "id": "tt3", "type": "series", "name": "  Third  " }
              ]
            }
            """.trimIndent()
        val response = NetworkModule.provideWireJson().decodeFromString<CatalogResponseDto>(payload)
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(response)
        val repository = CatalogRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            healthStore = mockk(relaxed = true),
            wireJson = NetworkModule.provideWireJson(),
            activeRatingCap = testRatingCap(),
            ratingFilter = CatalogRatingFilter(mockk())
        )

        val result = repository.getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = "series",
            skip = 10,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Success

        assertEquals(listOf("tt1", "tt3"), result.data.items.map { it.id })
        assertEquals(listOf("First", "  Third  "), result.data.items.map { it.name })
        assertEquals(15, result.data.nextSkip)
    }

    @Test
    fun `garbage json response yields a marked-bad addon not a crash`() = runTest {
        // W2.3: a decode failure (now kotlinx SerializationException on the addon
        // path) must be classified as garbage by safeApiCall, feed the addon-health
        // FAILURE sample, and never carry the embedded response snippet.
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } throws SerializationException(
            "Unexpected JSON token at path \$.metas: HOSTILE_GARBAGE"
        )
        val healthStore = mockk<AddonHealthStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any<Int>()) } returns "Malformed response from source"
        val repository = CatalogRepositoryImpl(
            context = context,
            api = api,
            healthStore = healthStore,
            wireJson = NetworkModule.provideWireJson(),
            activeRatingCap = testRatingCap(),
            ratingFilter = CatalogRatingFilter(mockk())
        )

        val result = repository.getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = "series",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Error

        org.junit.Assert.assertTrue(result.garbage)
        org.junit.Assert.assertFalse(result.message.contains("HOSTILE_GARBAGE"))
        io.mockk.coVerify(atLeast = 1, timeout = 1_000) {
            healthStore.record(
                AddonHealthStore.addonKey("https://addon.example"),
                com.nuvio.tv.core.health.HealthOutcome.FAILURE,
                any()
            )
        }
    }
}
