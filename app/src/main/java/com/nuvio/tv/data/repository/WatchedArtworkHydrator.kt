package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedArt
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchedArtworkHydrator"
internal const val WATCHED_ARTWORK_HYDRATION_BATCH = 20
private const val META_TIMEOUT_MS = 3_500L
// Lets a burst of marks or a sync apply settle before the active profile's set is planned.
internal const val WATCHED_ARTWORK_SETTLE_MS = 1_000L
// The first run waits this long at most for the installed-addon list (Spec 118 D5).
internal const val WATCHED_ARTWORK_ADDONS_WAIT_MS = 60_000L

/**
 * The titles to hydrate: those whose watched items carry no poster (History shows the poster), not yet claimed for
 * [profileId], newest mark first, at most [WATCHED_ARTWORK_HYDRATION_BATCH]. One item per title
 * (its newest), since art is per title.
 */
internal fun planWatchedArtworkBatch(
    items: List<WatchedItem>,
    claims: Set<String>,
    profileId: Int
): List<WatchedItem> = items
    .groupBy { it.contentId }
    .values
    .filter { group -> group.none { it.poster != null } }
    .map { group -> group.maxBy { it.watchedAt } }
    .filter { watchedArtworkClaim(profileId, it.contentId) !in claims }
    .sortedByDescending { it.watchedAt }
    .take(WATCHED_ARTWORK_HYDRATION_BATCH)

internal fun watchedArtworkClaim(profileId: Int, contentId: String): String = "$profileId:$contentId"

/**
 * Spec 118 R1: art for titles known only from the watched set (a sync pull or a mark carries
 * none), so Library History shows a poster instead of "No artwork". Mirrors the progress
 * hydration in WatchProgressRepositoryImpl: primary addon meta first, TMDB only when that has
 * no poster (it needs a key). Runs on every change to the active profile's watched set (local
 * marks, signed out included) and after each watched-items sync of any profile ([request]); works
 * through every poster-less title in batches.
 *
 * Claims (Spec 118 D5, the progress hydration's rule): every title tried is claimed; one whose
 * poster is found is stored and released (a later poster-less state, e.g. unmarked and marked
 * again, can retry); any other outcome (no poster anywhere, a backdrop alone, a failed or timed-out
 * fetch: the addon lookup cannot tell these apart) stays claimed until the next process. So the
 * first run waits for the installed-addon list ([WATCHED_ARTWORK_ADDONS_WAIT_MS]) rather than
 * claiming a cold start's whole history as missing.
 */
@OptIn(FlowPreview::class)
@Singleton
class WatchedArtworkHydrator @Inject constructor(
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Read and written only under [running].
    private val claims = HashSet<String>()
    private var addonsAwaited = false
    private val running = Mutex()

    init {
        // Each run's own merges re-emit; that next run plans nothing new and ends at once.
        scope.launch {
            // distinct: the store also re-emits on its cursor and watermark writes.
            watchedItemsPreferences.allItems
                .distinctUntilChanged()
                .debounce(WATCHED_ARTWORK_SETTLE_MS)
                .collect { items ->
                    if (items.any { it.poster == null }) hydrate(profileManager.activeProfileId.value)
                }
        }
    }

    /** Hydrates [profileId]'s poster-less titles in the background. */
    fun request(profileId: Int) {
        scope.launch { hydrate(profileId) }
    }

    internal suspend fun hydrate(profileId: Int): Unit = running.withLock {
        // Every title tried in this run: each is fetched at most once per run.
        val tried = HashSet<String>()
        try {
            if (!addonsAwaited) {
                // The shared cold-start wait; no addons at all = go on after it.
                awaitInstalledAddons(addonRepository.getInstalledAddons(), WATCHED_ARTWORK_ADDONS_WAIT_MS)
                addonsAwaited = true
            }
            while (true) {
                val items = watchedItemsPreferences.getAllItems(profileId)
                val batch = planWatchedArtworkBatch(items, claims + tried, profileId)
                if (batch.isEmpty()) break
                val found = LinkedHashMap<String, WatchedArt>()
                batch.forEach { item ->
                    val claim = watchedArtworkClaim(profileId, item.contentId)
                    tried.add(claim)
                    claims.add(claim)
                    try {
                        lookupArt(item.contentId, item.contentType)?.let { art ->
                            found[item.contentId] = art
                            if (art.poster != null) claims.remove(claim)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Watched artwork fetch failed for ${item.contentId}", e)
                    }
                }
                if (found.isNotEmpty()) watchedItemsPreferences.mergeArtwork(found, profileId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watched artwork hydration stopped for profile $profileId", e)
        }
    }

    /**
     * The title's art, or null when no source has a poster or a backdrop. A poster from the first
     * addon answer that has one (with an earlier answer's backdrop when it has none); else TMDB's
     * poster with the addon's backdrop.
     */
    private suspend fun lookupArt(contentId: String, contentType: String): WatchedArt? {
        val type = contentType.lowercase()
        val types = buildList {
            if (type.isNotBlank()) add(type)
            if (type in listOf("series", "tv")) addAll(listOf("series", "tv")) else add("movie")
        }.distinct()
        val ids = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:") || contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()
        var addonBackdrop: String? = null
        for (t in types) {
            for (id in ids) {
                val result = withTimeoutOrNull(META_TIMEOUT_MS) {
                    metaRepository.getMetaFromPrimaryAddon(type = t, id = id).first { it !is NetworkResult.Loading }
                }
                val meta = (result as? NetworkResult.Success)?.data ?: continue
                // Real landscape art only: backdropUrl falls back to the poster, and a landscape
                // card already falls back to the poster itself.
                val poster = meta.poster?.takeIf { it.isNotBlank() }
                if (poster != null) return WatchedArt(poster, meta.landscapeBackdropUrl ?: addonBackdrop)
                if (addonBackdrop == null) addonBackdrop = meta.landscapeBackdropUrl
            }
        }
        // History shows the poster (a backdrop alone still reads "No artwork"): ask TMDB for one.
        val tmdb = tmdbService.fetchImdbImages(contentId, contentType)
        return WatchedArt(tmdb?.posterUrl, addonBackdrop ?: tmdb?.backdropUrl)
            .takeIf { it.poster != null || it.backdrop != null }
    }
}
