package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.isRatingAllowed
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** A CW title's rating lookup: [Found] is final (its rating may be null = unknown), [Failed] is retried. */
internal sealed interface CwRatingLookup {
    data class Found(val rating: String?) : CwRatingLookup
    data object Failed : CwRatingLookup
}

/**
 * Spec 64 K1c: Continue Watching (main row and upcoming) as the active profile's cap may see it,
 * applied once to HomeViewModel's exposed state, so every CW publish path (the cold-start
 * snapshot, the pipeline's partial and final passes, reconciliation) passes through it.
 *
 * Uncapped: the state passes through as the same instance and nothing is looked up. Capped: a
 * title is shown only once its addon rating is known and within the cap (D1); the gate looks
 * ratings up itself (the pipeline skips its meta fetch for overlay-seeded items), at most
 * [LOOKUP_CONCURRENCY] at a time, through MetaRepository, whose caches the pipeline shares.
 * Until the cap has loaded, CW is hidden. A failed lookup is retried with a back-off from
 * [FIRST_RETRY_DELAY_MS] up to [MAX_RETRY_DELAY_MS] while the title is still in CW; it stays
 * hidden meanwhile.
 */
internal class ContinueWatchingRatingGate(
    private val scope: CoroutineScope,
    readCap: suspend () -> ContentRatingCap?,
    private val lookUpRating: suspend (contentType: String, contentId: String) -> CwRatingLookup
) {
    private sealed interface CapState {
        data object Loading : CapState
        data class Ready(val cap: ContentRatingCap?) : CapState
    }

    private val cap = MutableStateFlow<CapState>(CapState.Loading)
    /** Final lookups by [key]; absent = not known yet. */
    private val ratings = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val requested = ConcurrentHashMap.newKeySet<String>()
    /** Keys of the CW titles in the latest capped state; a retry stops once its title leaves. */
    @Volatile private var currentKeys: Set<String> = emptySet()
    private val lookupPermits = Semaphore(LOOKUP_CONCURRENCY)

    init {
        scope.launch { cap.value = CapState.Ready(readCap()) }
    }

    /** [states] with CW filtered for the cap; shared on [scope] for its lifetime. */
    fun gate(states: StateFlow<HomeUiState>): StateFlow<HomeUiState> =
        combine(states, cap, ratings, ::visible)
            .stateIn(scope, SharingStarted.Eagerly, visible(states.value, cap.value, ratings.value))

    private fun visible(state: HomeUiState, capState: CapState, known: Map<String, String?>): HomeUiState {
        val cap = when (capState) {
            CapState.Loading -> return state.withContinueWatching(emptyList(), emptyList())
            is CapState.Ready -> capState.cap ?: return state
        }
        currentKeys = (state.continueWatchingItems + state.upcomingItems)
            .mapTo(HashSet()) { item -> item.typeAndId().let { (type, id) -> key(type, id) } }
        fun allowed(item: ContinueWatchingItem): Boolean {
            val (type, id) = item.typeAndId()
            val key = key(type, id)
            if (key !in known) {
                request(type, id)
                return false
            }
            return isRatingAllowed(known[key], cap)
        }
        return state.withContinueWatching(
            state.continueWatchingItems.filter(::allowed),
            state.upcomingItems.filter(::allowed)
        )
    }

    /** The same instance when nothing was dropped, so an unchanged state is not re-emitted. */
    private fun HomeUiState.withContinueWatching(
        main: List<ContinueWatchingItem>,
        upcoming: List<ContinueWatchingItem>
    ): HomeUiState =
        if (main.size == continueWatchingItems.size && upcoming.size == upcomingItems.size) this
        else copy(continueWatchingItems = main, upcomingItems = upcoming)

    private fun request(type: String, id: String) {
        val key = key(type, id)
        if (!requested.add(key)) return
        scope.launch {
            var retryDelay = FIRST_RETRY_DELAY_MS
            while (true) {
                val result = lookupPermits.withPermit { lookUpRating(type, id) }
                if (result is CwRatingLookup.Found) {
                    ratings.update { it + (key to result.rating) }
                    return@launch
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                if (key !in currentKeys) {
                    // Gone from CW: a later state that shows it again requests afresh.
                    requested.remove(key)
                    return@launch
                }
            }
        }
    }

    private fun ContinueWatchingItem.typeAndId(): Pair<String, String> = when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType to progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentType to info.contentId
    }

    private fun key(type: String, id: String) = "$type:$id"

    internal companion object {
        private const val TAG = "CwRatingGate"
        const val LOOKUP_CONCURRENCY = 4
        const val FIRST_RETRY_DELAY_MS = 15_000L
        const val MAX_RETRY_DELAY_MS = 5 * 60_000L
        private const val LOOKUP_TIMEOUT_MS = 6_000L

        /**
         * The addon rating of a CW title through [metaRepository]: the CW pipeline's type and id
         * candidates over all addons (its fallback path; shared cache). The first meta found is
         * final, rated or not; no meta, a timeout or an error is [CwRatingLookup.Failed].
         */
        fun metaRepositoryLookup(
            metaRepository: MetaRepository
        ): suspend (String, String) -> CwRatingLookup = { contentType, contentId ->
            val ids = listOfNotNull(contentId, contentId.takeIf { it.startsWith("tmdb:") }?.substringAfter(':'))
            try {
                cwMetaTypeCandidates(contentType).firstNotNullOfOrNull { type ->
                    ids.firstNotNullOfOrNull { id ->
                        val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                            metaRepository.getMetaFromAllAddons(type = type, id = id)
                                .first { it !is NetworkResult.Loading }
                        }
                        (result as? NetworkResult.Success)?.let { CwRatingLookup.Found(it.data.ageRating) }
                    }
                } ?: CwRatingLookup.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "CW rating lookup failed: ${e::class.simpleName}")
                CwRatingLookup.Failed
            }
        }
    }
}
