package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isRatingAllowed
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 64 K1b: drops the titles a capped profile may not see from an addon catalog row, on the
 * row's catalog rating (`app_extras.certification`, mapped by CatalogMapper). Unknown ratings are
 * hidden (D1). Only [CatalogRow.items] change: paging fields stay the addon's, so a filtered page
 * can be empty while the catalog goes on (CatalogRepositoryImpl handles that case).
 *
 * Search results get one rescue (K0: search series carry no catalog rating, and the addon's own
 * meta has one for about half of them): unrated series are looked up through
 * [MetaRepository.getMeta] on the row's addon, whose `ageRating` is the same addon field, before
 * the filter runs. At most [LOOKUP_CONCURRENCY] lookups wait at once across all rows, and one
 * row's batch stops at [LOOKUP_BUDGET_MS], time queued for a permit included; whatever is still
 * unresolved then is hidden. A lookup given up on may finish in MetaRepository's shared fetch
 * and land in its cache, so the next search gets it.
 */
@Singleton
class CatalogRatingFilter @Inject constructor(
    private val metaRepository: MetaRepository
) {
    private val lookupPermits = Semaphore(LOOKUP_CONCURRENCY)

    /** [row] as profile [cap] may see it; null [cap] returns [row] unchanged. */
    suspend fun filter(row: CatalogRow, cap: ContentRatingCap?, lookUpUnratedSeries: Boolean): CatalogRow {
        if (cap == null) return row
        val items = if (lookUpUnratedSeries) withLookedUpRatings(row) else row.items
        return row.copy(items = items.filter { isRatingAllowed(it.ageRating, cap) })
    }

    private suspend fun withLookedUpRatings(row: CatalogRow): List<MetaPreview> {
        val unrated = row.items.filter {
            it.type == ContentType.SERIES && ContentRatingCap.tierOf(it.ageRating) == null
        }
        if (unrated.isEmpty()) return row.items
        val found = ConcurrentHashMap<String, String>()
        withTimeoutOrNull(LOOKUP_BUDGET_MS) {
            coroutineScope {
                unrated.forEach { item ->
                    launch {
                        lookupPermits.withPermit {
                            lookUpRating(row.addonBaseUrl, item)?.let { found[item.id] = it }
                        }
                    }
                }
            }
        }
        if (found.size < unrated.size) {
            Log.d(TAG, "Search rating lookup catalogId=${row.catalogId}: ${found.size}/${unrated.size} resolved")
        }
        return row.items.map { item -> found[item.id]?.let { item.copy(ageRating = it) } ?: item }
    }

    /** The addon meta's rating; null on a miss or any failure (the title is then hidden). */
    private suspend fun lookUpRating(addonBaseUrl: String, item: MetaPreview): String? = try {
        val result = metaRepository.getMeta(addonBaseUrl, item.apiType, item.id)
            .first { it !is NetworkResult.Loading }
        (result as? NetworkResult.Success)?.data?.ageRating
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Search rating lookup failed: ${e::class.simpleName}")
        null
    }

    private companion object {
        const val TAG = "CatalogRatingFilter"
        const val LOOKUP_CONCURRENCY = 6
        const val LOOKUP_BUDGET_MS = 6_000L
    }
}
