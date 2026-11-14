package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.sanitizedFetchMessage
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.core.util.encodeUrlArg
import com.nuvio.tv.core.util.getOrStartShared
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val healthStore: AddonHealthStore
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
        /** Default TTL when addon response has no Cache-Control header (6 hours). */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
        /** Minimum TTL for meta responses even when server says no-cache/no-store (5 minutes).
         *  Prevents excessive re-fetching on every details screen visit for addons
         *  that don't set meaningful Cache-Control headers. */
        private const val MIN_META_TTL_MS = 5L * 60 * 1000
        /**
         * How long a meta lookup waits for the installed addon list to populate.
         *
         * [AddonRepository.getInstalledAddons] is a StateFlow seeded with an empty
         * list, so a cold start reads the seed rather than the addons. On a Shield
         * cold start the disk-cached manifests land ~1.2 s after process spawn
         * (measured 2026-09-05, ke16 incident: dispatches inside that window lost
         * the old 750 ms wait and reported "no addon for (type=series)" for content
         * that was fetchable a second later), so the window must cover it. A
         * first-ever launch has nothing cached and waits on live fetches, usually
         * longer than this, so that one start falls back to the requested type
         * and pays the wait as well.
         *
         * [getMeta] checks its cache first, so a hit never waits.
         */
        private const val INSTALLED_ADDONS_WAIT_MS = 3000L

        /**
         * Deadline for one meta request (Spec 84 A6), above the 20 s addon default
         * (SafeApiCall.kt): a cold AIOMetadata long-show meta took 10-14 s on the server
         * before the device downloaded and decoded 4-8 MB, and the 20 s cut lost it (Spec 83
         * W1-04). Stays under the @Api client's 30 s callTimeout minus AddonHostGuard's 1 s
         * slack, so this cut, not the callTimeout, ends a dead addon's request and reads as
         * the caller's drop, not a host timeout.
         */
        internal const val META_FETCH_TIMEOUT_MS = 28_000L
    }

    /** Internal result type for the deferred meta lookup to distinguish
     *  "fetched meta", "nothing found", and "source addon already provides this data". */
    private sealed class MetaLookupResult {
        data class Found(val meta: Meta) : MetaLookupResult()
        /** Carries what was tried, so every caller can report it. The loop runs in a
         *  shared Deferred, so attempts held in one caller's flow would leave the
         *  others reporting nothing. */
        data class NotFound(
            val attemptedAddonNames: List<String> = emptyList(),
            val failures: List<MetaAttemptFailure> = emptyList(),
            /** T9 (0.9.0 relay): every candidate was attempted and every one of them answered
             *  "no such item". Set by the lookup loop, which is the only place that knows
             *  how many candidates there were, rather than inferred from [failures] by a
             *  caller. */
            val allAttemptsMissing: Boolean = false
        ) : MetaLookupResult()
        /** The first viable candidate is the same addon that served the catalog,
         *  so the item already has its meta — no request needed. */
        data object SourceSufficient : MetaLookupResult()
    }

    /** Result of one single-addon meta fetch ([fetchMetaOnce]). Callers show the same
     *  "not found" for every non-success, but health must not: a clean miss
     *  ([MetaFailureKind.MISSING]: Stremio 404, or a 200 carrying no meta like
     *  AIOMetadata's `{"meta":null}`) is the addon answering "no such item" and stamps
     *  [HealthOutcome.EMPTY], while transport and parse failures
     *  ([MetaFailureKind.REQUEST_FAILED]) stamp [HealthOutcome.FAILURE]. [Failed.detail]
     *  is the per-addon text the multi-addon loops report. */
    private sealed class MetaFetchOutcome {
        data class Fetched(val cached: CachedMeta) : MetaFetchOutcome()
        data class Failed(val kind: MetaFailureKind, val detail: String) : MetaFetchOutcome()
    }

    private enum class MetaFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class MetaAttemptFailure(
        val addonName: String,
        val kind: MetaFailureKind,
        val detail: String
    )

    /** Wrapper for cached meta with an expiration timestamp. */
    private data class CachedMeta(
        val meta: Meta,
        val expiresAtMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache: "addonBaseUrl|type:id" -> CachedMeta. Keyed per addon so
    // two addons serving meta for the same content id never overwrite each other.
    // Two TTL layers compose here (0.7.20 merge):
    //  - upstream 0.7.20: each entry carries the addon-declared Cache-Control
    //    max-age (CachedMeta.isExpired), so fast-changing meta refreshes without
    //    an app restart;
    //  - fork (main review F15): the cache itself is a bounded, access-ordered
    //    LRU whose 45-min internal TTL caps any longer declaration -- a series
    //    Meta is heavy (full episode list, cast, links) and these previously
    //    grew without bound for the whole session, competing with the player's
    //    buffer for RAM on low tiers. The cap also lets a series pick up
    //    newly-aired episodes within a session.
    // Net per-entry lifetime: min(declared max-age or the 6 h default, 45 min).
    private val metaCache = BoundedMetaCache<String, CachedMeta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = BoundedMetaCache<String, CachedMeta>()
    private val primaryAddonMetaCache = BoundedMetaCache<String, CachedMeta>()
    // Negative cache (main review F18): a genuine miss previously re-ran the full
    // candidate loop on every detail-screen visit / enrichment for that title.
    private val metaMissCache = BoundedMetaCache<String, Long>(
        maxEntries = 300, ttlMs = 5 * 60 * 1000L
    )

    /**
     * Minimal thread-safe LRU with per-entry TTL. Access-ordered so hot titles
     * stay resident; stale entries are dropped on read.
     */
    private class BoundedMetaCache<K : Any, V : Any>(
        private val maxEntries: Int = 150,
        private val ttlMs: Long = 45 * 60 * 1000L
    ) {
        private class Entry<V>(val value: V, val storedAtMs: Long)

        private val map = object : LinkedHashMap<K, Entry<V>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        operator fun get(key: K): V? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.storedAtMs > ttlMs) {
                map.remove(key)
                return null
            }
            return entry.value
        }

        @Synchronized
        operator fun set(key: K, value: V) {
            map[key] = Entry(value, System.currentTimeMillis())
        }

        @Synchronized
        fun remove(key: K) {
            map.remove(key)
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate
    // requests (see getOrStartShared).
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<MetaFetchOutcome>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<MetaLookupResult>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)

        // supportedCandidateType only ever returns one of these two, so every
        // metaCache entry written for this addon and title sits under one of two
        // keys. Check both, in the order it would pick them, before resolving
        // the addon, so a cache hit skips the cold-start wait below. Only metaCache
        // needs this: addonMetaCache is already canonical via metaLookupCacheKey.
        val probeTypes = listOf(requestedType, inferredType)
            .filter { it.isNotBlank() }
            .distinct()
        probeTypes.forEach { probeType ->
            val probeKey = addonMetaCacheKey(addonBaseUrl, probeType, id)
            metaCache[probeKey]?.let { cached ->
                if (!cached.isExpired()) {
                    emit(NetworkResult.Success(cached.meta))
                    return@flow
                }
                metaCache.remove(probeKey)
            }
        }

        // The caller names the addon but not necessarily a type it serves. Nuvio's
        // internal "tv" against a series-only addon is the usual case. Resolve it
        // like the multi-addon path does, so the request and the cached entry line
        // up with the other paths.
        val addon = findAddonByBaseUrl(addonBaseUrl)
        val advertisedType = addon?.supportedCandidateType(requestedType, inferredType)
        if (advertisedType == null) {
            // Both fall back to the requested type, for different reasons. Log which,
            // so a request expected to fail is not confused with an unknown addon.
            if (addon == null) {
                Log.w(
                    TAG,
                    "Addon unresolved (not installed, disabled, or URL not matched), " +
                        "requesting as-is host=${addonBaseUrl.safeHost()} type=$requestedType id=$id"
                )
            } else {
                Log.w(
                    TAG,
                    "Addon advertises neither type, requesting as-is " +
                        "addonId=${addon.id} requested=$requestedType inferred=$inferredType id=$id"
                )
            }
        }
        val effectiveType = advertisedType ?: requestedType

        val cacheKey = addonMetaCacheKey(addonBaseUrl, effectiveType, id)

        emit(NetworkResult.Loading)

        val healthT0 = android.os.SystemClock.elapsedRealtime()
        val deferred = inFlightMeta.getOrStartShared(repositoryScope, cacheKey) {
            fetchMetaOnce(addonBaseUrl, effectiveType, id).also { outcome ->
                if (outcome is MetaFetchOutcome.Fetched) {
                    addonMetaCache[metaLookupCacheKey(requestedType, id)] = outcome.cached
                }
            }
        }

        val outcome = deferred.await()
        val healthMs = android.os.SystemClock.elapsedRealtime() - healthT0
        when (outcome) {
            is MetaFetchOutcome.Fetched -> {
                healthStore.recordAddonOutcome(addonBaseUrl, HealthOutcome.SUCCESS, healthMs)
                emit(NetworkResult.Success(outcome.cached.meta))
            }
            // A miss is the addon working as designed: EMPTY keeps it out of the
            // failure streaks so it can never trip the Down breaker (two misses
            // in a row used to light a healthy addon red).
            is MetaFetchOutcome.Failed -> {
                val health = when (outcome.kind) {
                    MetaFailureKind.MISSING -> HealthOutcome.EMPTY
                    MetaFailureKind.REQUEST_FAILED -> HealthOutcome.FAILURE
                }
                healthStore.recordAddonOutcome(addonBaseUrl, health, healthMs)
                emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found)))
            }
        }
    }

    override fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String?
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = metaLookupCacheKey(type, id)
        addonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            addonMetaCache.remove(cacheKey)
        }
        // Fresh negative result: skip re-running the whole candidate loop (F18).
        if (metaMissCache[cacheKey] != null) {
            // Only every-candidate-answered-missing is stamped (T31), so the replay is a
            // final not-found like the first emission.
            emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found), NetworkResult.META_NOT_FOUND_CODE))
            return@flow
        }

        val requestedType = type.trim()
        // The shared lookup's SourceSufficient answer depends on the source addon, so
        // only callers naming the same source may join it.
        val normalizedSourceUrl = sourceAddonBaseUrl?.let(::addonUrlMatchKey)
        val inFlightKey = metaInFlightKey(cacheKey, normalizedSourceUrl)
        // Joining a lookup that is already running shares its outcome, failures included (Spec 37
        // P1, ysosrs c6129281b): running the loop again after a shared failure doubled the requests
        // to an addon that had just failed.
        inFlightAddonMeta[inFlightKey]?.let { existingDeferred ->
            emit(lookupOutcome(existingDeferred.await(), requestedType, id))
            return@flow
        }

        emit(NetworkResult.Loading)

        val addons = installedAddonsOrEmpty()

        val inferredType = inferCanonicalType(requestedType, id)
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        val attemptedAddonNames = linkedSetOf<String>()
        val prioritizedCandidates = prioritizedMetaCandidates(addons, requestedType, inferredType, id)

        if (prioritizedCandidates.isEmpty()) {
            // Last resort: try addons that declare the raw type (legacy behavior).
            val fallbackAddons = addons.filter { addon ->
                addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) } &&
                    addon.resources.any { it.name == "meta" }
            }

            for (addon in fallbackAddons) {
                attemptedAddonNames += addon.displayName
                when (val outcome = fetchMetaOnce(addon.baseUrl, requestedType, id)) {
                    is MetaFetchOutcome.Fetched -> {
                        addonMetaCache[cacheKey] = outcome.cached
                        emit(NetworkResult.Success(outcome.cached.meta))
                        return@flow
                    }
                    is MetaFetchOutcome.Failed ->
                        attemptedFailures += MetaAttemptFailure(addon.displayName, outcome.kind, outcome.detail)
                }
            }

            val fallbackMessage = if (fallbackAddons.isEmpty()) {
                context.getString(R.string.error_meta_no_supported_addon, requestedType)
            } else {
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = attemptedAddonNames.toList(),
                    failures = attemptedFailures
                )
            }
            // T9 (0.9.0 relay): classified like the main path below. No addon declaring
            // the type is final, and so is every legacy candidate answering "no such
            // item"; only a request failure is retryable.
            val fallbackFinal = fallbackAddons.isEmpty() ||
                attemptedFailures.all { it.kind == MetaFailureKind.MISSING }
            emit(
                NetworkResult.Error(
                    fallbackMessage,
                    code = if (fallbackFinal) NetworkResult.META_NOT_FOUND_CODE else null
                )
            )
            return@flow
        }

        val deferred = inFlightAddonMeta.getOrStartShared(repositoryScope, inFlightKey) {
            // Kept here rather than in the enclosing flow's lists: this
            // Deferred is shared, so only its creator would see those, and
            // everyone else would report "no addon found" for addons tried.
            val loopAddonNames = linkedSetOf<String>()
            val loopFailures = mutableListOf<MetaAttemptFailure>()

            for ((addon, candidateType) in prioritizedCandidates) {
                // If this candidate is the same addon that provided the catalog
                // data for this item, the item already carries its meta —
                // return immediately without making a request and without
                // trying further addons.
                if (normalizedSourceUrl != null && addonUrlMatchKey(addon.baseUrl) == normalizedSourceUrl) {
                    Log.d(TAG, "Source addon matched, catalog meta is sufficient addon=${addon.name} type=$candidateType id=$id")
                    return@getOrStartShared MetaLookupResult.SourceSufficient
                }

                Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id")
                loopAddonNames += addon.displayName
                when (val outcome = fetchMetaOnce(addon.baseUrl, candidateType, id)) {
                    is MetaFetchOutcome.Fetched -> {
                        addonMetaCache[cacheKey] = outcome.cached
                        return@getOrStartShared MetaLookupResult.Found(outcome.cached.meta)
                    }
                    is MetaFetchOutcome.Failed ->
                        loopFailures += MetaAttemptFailure(addon.displayName, outcome.kind, outcome.detail)
                }
            }
            // T9 (0.9.0 relay): derived over the failures, the same spelling the
            // fallback path uses — the loop appends exactly one failure per
            // attempted candidate, so a non-empty all-MISSING list means every
            // candidate was asked and every one answered "no such item".
            val allAttemptsMissing = loopFailures.isNotEmpty() &&
                loopFailures.all { it.kind == MetaFailureKind.MISSING }
            // T31 (P36, upstream 03dde8a94): only a definitive absence is remembered; a
            // timeout or network failure must not read as "not found" for the TTL.
            if (allAttemptsMissing) metaMissCache[cacheKey] = System.currentTimeMillis()
            MetaLookupResult.NotFound(
                attemptedAddonNames = loopAddonNames.toList(),
                failures = loopFailures,
                allAttemptsMissing = allAttemptsMissing
            )
        }

        emit(lookupOutcome(deferred.await(), requestedType, id))
    }

    private fun lookupOutcome(lookupResult: MetaLookupResult, requestedType: String, id: String): NetworkResult<Meta> =
        when (lookupResult) {
            is MetaLookupResult.Found -> NetworkResult.Success(lookupResult.meta)
            is MetaLookupResult.SourceSufficient ->
                NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE)
            // T9 (0.9.0 relay): every addon answering "no such item" is a final answer, not a
            // failure. Flag it so callers can cache the outcome instead of asking again on every
            // focus. Only a request failure stays retryable.
            is MetaLookupResult.NotFound -> NetworkResult.Error(
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = lookupResult.attemptedAddonNames,
                    failures = lookupResult.failures
                ),
                code = if (lookupResult.allAttemptsMissing) NetworkResult.META_NOT_FOUND_CODE else null
            )
        }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = metaLookupCacheKey(type, id)
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            primaryAddonMetaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val addons = installedAddonsOrEmpty()
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val candidate = selectPrimaryMetaCandidate(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType
        )

        if (candidate == null) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_no_supported_addon, requestedType)))
            return@flow
        }

        val (addon, candidateType) = candidate
        Log.d(
            TAG,
            "Trying primary meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id"
        )

        val deferred = inFlightPrimaryMeta.getOrStartShared(repositoryScope, cacheKey) {
            when (val outcome = fetchMetaOnce(addon.baseUrl, candidateType, id)) {
                is MetaFetchOutcome.Fetched -> {
                    primaryAddonMetaCache[cacheKey] = outcome.cached
                    outcome.cached.meta
                }
                is MetaFetchOutcome.Failed -> null
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(buildAggregateFailureMessage(
                type = requestedType,
                id = id,
                attemptedAddonNames = listOf(addon.displayName),
                failures = listOf(buildMissingMetaFailure(addon))
            )))
        }
    }

    private fun addonMetaCacheKey(addonBaseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(addonBaseUrl)
        return "$basePath$baseQuery|$type:$id"
    }

    /**
     * Key for the caches that are not per addon. Canonicalized so one title asked
     * for as "tv" and as "series" shares an entry instead of taking two.
     */
    private fun metaLookupCacheKey(type: String, id: String): String =
        "${inferCanonicalType(type.trim(), id).lowercase()}:$id"

    /**
     * Installed, enabled addons, waiting up to [INSTALLED_ADDONS_WAIT_MS] for a real
     * list ([awaitInstalledAddons]). Returns empty if nothing arrives in time, leaving
     * callers to use the type as given or raise their own no-addon error.
     */
    private suspend fun installedAddonsOrEmpty(): List<Addon> =
        awaitInstalledAddons(addonRepository.getInstalledAddons(), INSTALLED_ADDONS_WAIT_MS)

    /** The installed, enabled addon at [baseUrl], if it is still installed. */
    private suspend fun findAddonByBaseUrl(baseUrl: String): Addon? {
        val target = addonUrlMatchKey(baseUrl)
        return installedAddonsOrEmpty().firstOrNull { addonUrlMatchKey(it.baseUrl) == target }
    }

    /**
     * One meta request, shared by every lookup path, under [META_FETCH_TIMEOUT_MS].
     * A success is written to [metaCache] under this addon's key; callers add their
     * own lookup cache entry.
     */
    private suspend fun fetchMetaOnce(baseUrl: String, type: String, id: String): MetaFetchOutcome {
        val url = buildMetaUrl(baseUrl, type, id)
        return try {
            val response = withTimeoutOrNull(META_FETCH_TIMEOUT_MS) { api.getMeta(url) }
                ?: throw java.io.IOException(context.getString(R.string.network_error_addon_timeout))
            val metaDto = if (response.isSuccessful) response.body()?.meta else null
            when {
                metaDto != null -> {
                    val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                    val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                    val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                    metaCache[addonMetaCacheKey(baseUrl, type, id)] = cached
                    Log.d(TAG, "Meta fetch success type=$type id=$id ttl=${ttlMs}ms")
                    MetaFetchOutcome.Fetched(cached)
                }
                // 200 with no meta (e.g. `{"meta":null}`): the addon answered, it
                // just has nothing for this id.
                response.isSuccessful -> MetaFetchOutcome.Failed(MetaFailureKind.MISSING, missingMetaDetail())
                // Stremio's not-found convention: same as a 200 with no meta.
                else -> MetaFetchOutcome.Failed(
                    kind = if (response.code() == 404) MetaFailureKind.MISSING else MetaFailureKind.REQUEST_FAILED,
                    detail = response.message() ?: "HTTP ${response.code()}"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Meta fetch failed host=${url.safeHost()}: ${e.sanitizedFetchMessage()}")
            MetaFetchOutcome.Failed(MetaFailureKind.REQUEST_FAILED, e.sanitizedFetchMessage())
        }
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        val encodedType = encodeUrlArg(type)
        val encodedId = encodeUrlArg(id)
        return "$basePath/meta/$encodedType/$encodedId.json$baseQuery"
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = type.trim()
        // "tv" is Nuvio's internal synonym for episodic content. Stremio metadata
        // addons advertise episodic content as "series", so fold it over here rather
        // than requesting a type nothing declares. Live TV is a separate type
        // ("channel") and is left alone.
        if (normalizedType.equals("tv", ignoreCase = true)) return "series"
        val known = setOf("movie", "series", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "series"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    private fun selectPrimaryMetaCandidate(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String
    ): Pair<Addon, String>? {
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                return addon to requestedType
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    return addon to inferredType
                }
            }
        }
        val topMetaAddon = addons.firstOrNull { addon ->
            addon.resources.any { it.name == "meta" }
        } ?: return null
        val fallbackType = topMetaAddon.supportedCandidateType(requestedType, inferredType)
            ?: return null
        return topMetaAddon to fallbackType
    }

    private fun buildMissingMetaFailure(addon: Addon): MetaAttemptFailure {
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.MISSING,
            detail = missingMetaDetail()
        )
    }

    private fun missingMetaDetail(): String =
        context.getString(R.string.meta_error_detail_no_metadata_for_id)

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<MetaAttemptFailure>
    ): String {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_meta_no_addon_for_id, id, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == MetaFailureKind.MISSING }

        return if (missingOnly) {
            context.getString(R.string.error_meta_tried_none, triedAddons, id, type)
        } else {
            val issueSummary = failures
                .filter { it.kind == MetaFailureKind.REQUEST_FAILED }
                .distinctBy { it.addonName to it.detail }
                .take(3)
                .joinToString("; ") { "${it.addonName}: ${it.detail}" }
            if (issueSummary.isBlank()) {
                context.getString(R.string.error_meta_tried_generic, triedAddons, id, type)
            } else {
                context.getString(R.string.error_meta_tried_issues, triedAddons, id, type, issueSummary)
            }
        }
    }
    
    /**
     * Parses the max-age directive from a Cache-Control header value.
     * Returns the TTL in milliseconds, or [DEFAULT_TTL_MS] if the header is
     * missing or malformed. Applies [MIN_META_TTL_MS] as a floor so that
     * addons responding with no-cache/no-store/max-age=0 still get a short
     * grace period, preventing re-fetches on every details screen visit.
     */
    private fun parseMaxAgeMs(cacheControl: String?): Long {
        if (cacheControl == null) return DEFAULT_TTL_MS
        val parsed = CacheControl.parse(okhttp3.Headers.headersOf("Cache-Control", cacheControl))
        if (parsed.noStore || parsed.noCache) return MIN_META_TTL_MS
        val maxAgeSec = parsed.maxAgeSeconds
        val ttlMs = if (maxAgeSec >= 0) maxAgeSec * 1000L else DEFAULT_TTL_MS
        return maxOf(ttlMs, MIN_META_TTL_MS)
    }

    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
        primaryAddonMetaCache.clear()
        metaMissCache.clear()
        inFlightMeta.clear()
        inFlightAddonMeta.clear()
        inFlightPrimaryMeta.clear()
    }

    override fun getCachedMeta(type: String, id: String): Meta? {
        val cacheKey = metaLookupCacheKey(type, id)
        return addonMetaCache[cacheKey]?.takeIf { !it.isExpired() }?.meta
    }
}

/**
 * Key for the shared multi-addon lookup in flight. Its SourceSufficient answer depends
 * on the caller's source addon, so the normalized source is part of the key; the result
 * caches stay keyed on [lookupCacheKey] alone.
 */
internal fun metaInFlightKey(lookupCacheKey: String, normalizedSourceUrl: String?): String =
    "$lookupCacheKey|${normalizedSourceUrl.orEmpty()}"

/**
 * Candidates for the multi-addon meta lookup of [id], in request order:
 * 1) addons with a meta resource serving [requestedType] whose id prefixes accept [id];
 * 2) the same for [inferredType];
 * 3) only when 1-2 found none: addons supporting [requestedType] that declare no
 *    addon-level idPrefixes, then the first meta addon declaring none, under a type it
 *    advertises.
 * Type and id prefix come from the SAME meta resource (L8-25): an addon whose movie
 * resource takes "tt" and whose series resource takes "kitsu:" is no series candidate
 * for a tt id (the first meta resource's prefixes used to decide for every type).
 * Empty sends the caller to its legacy raw-type path.
 */
internal fun prioritizedMetaCandidates(
    addons: List<Addon>,
    requestedType: String,
    inferredType: String,
    id: String
): List<Pair<Addon, String>> {
    val candidates = linkedSetOf<Pair<Addon, String>>()
    addons.filter { it.servesMeta(requestedType, id) }.mapTo(candidates) { it to requestedType }
    if (!inferredType.equals(requestedType, ignoreCase = true)) {
        addons.filter { it.servesMeta(inferredType, id) }.mapTo(candidates) { it to inferredType }
    }
    if (candidates.isEmpty()) {
        addons.filter { it.supportsMetaType(requestedType) && it.idPrefixes.isEmpty() }
            .mapTo(candidates) { it to requestedType }
        addons.firstOrNull { addon -> addon.idPrefixes.isEmpty() && addon.resources.any { it.name == "meta" } }
            ?.let { top -> top.supportedCandidateType(requestedType, inferredType)?.let { candidates += top to it } }
    }
    return candidates.toList()
}

/**
 * Picks a meta type this addon actually advertises, preferring the requested one.
 * Returns null when it supports neither, so a candidate is never built with a type
 * the addon has already rejected.
 */
private fun Addon.supportedCandidateType(requestedType: String, inferredType: String): String? = when {
    supportsMetaType(requestedType) -> requestedType
    supportsMetaType(inferredType) -> inferredType
    else -> null
}
