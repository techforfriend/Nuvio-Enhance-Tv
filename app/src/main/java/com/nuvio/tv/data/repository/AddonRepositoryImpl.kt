package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.core.util.safeHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.nuvio.tv.core.network.ADDON_REQUEST_TIMEOUT_MS
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.network.tls.AddonCertPinKeeper
import com.nuvio.tv.data.local.decodeManifestCache
import com.nuvio.tv.data.local.encodeManifestCache
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update

/**
 * T5 (ysosrs 7bb4e446f + 0.9.0 relay): @Singleton on the class, not only on the
 * @Binds in RepositoryModule — the binding scopes the AddonRepository INTERFACE,
 * and StartupSyncService injects AddonRepositoryImpl concretely, which otherwise
 * constructs a second instance with its own manifest cache, disk loader and
 * stateIn collector.
 */
@Singleton
class AddonRepositoryImpl(
    private val api: AddonApi,
    private val preferences: AddonPreferences,
    private val addonSyncService: AddonSyncService,
    private val authManager: AuthManager,
    @param:ApplicationContext private val context: Context,
    /** Backs syncScope, the cache disk IO and installedAddonsFlow; injectable for tests. */
    private val dispatcher: CoroutineDispatcher,
    /** Source of the refresh clock, injectable so the TTL policy is tested without waiting. */
    private val clock: () -> Long,
    /** Spec 68 P8: forgets trusted certificates no stored addon list uses; never throws. */
    private val pruneCertPins: suspend () -> Unit = {}
) : AddonRepository {

    @Inject
    constructor(
        api: AddonApi,
        preferences: AddonPreferences,
        addonSyncService: AddonSyncService,
        authManager: AuthManager,
        @ApplicationContext context: Context,
        certPinKeeper: AddonCertPinKeeper
    ) : this(
        api = api,
        preferences = preferences,
        addonSyncService = addonSyncService,
        authManager = authManager,
        context = context,
        dispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
        pruneCertPins = certPinKeeper::prune
    )

    companion object {
        private const val TAG = "AddonRepository"
        private const val MANIFEST_CACHE_PREFS = "addon_manifest_cache"
        private const val MANIFEST_CACHE_KEY = "manifests_v2"
        private const val LEGACY_MANIFEST_CACHE_KEY = "manifests"
        private const val MANIFEST_CACHE_TTL_MS = 6 * 60 * 60 * 1000L 
    }

    private val syncScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var syncJob: Job? = null

    @Volatile
    var isSyncingFromRemote = false

    // Main F23: canonicalisation lives in core.util - one implementation
    // shared with AddonPreferences and AddonSyncService.
    private fun canonicalizeUrl(url: String): String = canonicalizeAddonUrl(url)

    private fun normalizeUrl(url: String): String = addonUrlMatchKey(url)

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) {
            Log.d(TAG, "triggerRemoteSync: skipped (syncing from remote)")
            return
        }
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "triggerRemoteSync: skipped (not authenticated, state=${authManager.authState.value})")
            return
        }
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        // Captured now: a profile switch inside the delay must not push the other profile.
        val profileId = addonSyncService.currentProfileId()
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            val result = addonSyncService.pushToRemote(profileId)
            Log.d(TAG, "triggerRemoteSync: push result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        }
    }

    private val manifestCache = mutableMapOf<String, Addon>()
    private val manifestCacheLock = Any()
    private val manifestCacheRevision = MutableStateFlow(0L)
    @Volatile
    private var lastManifestRefreshAttemptTime = 0L
    private var manifestRefreshJob: Job? = null
    private val manifestRefreshLock = Any()

    // T5 (ysosrs 7bb4e446f): completes once the on-disk manifest cache has been
    // loaded, so installedAddonsFlow gates its first evaluation on it instead of
    // racing to re-fetch every manifest during a cold start.
    private val diskCacheLoaded = CompletableDeferred<Unit>()

    // T5: serialises disk persistence so an older snapshot can never overwrite a
    // newer one and silently drop an addon from the cache.
    private val persistMutex = Mutex()

    // T5: URLs whose manifest fetch failed; retried with backoff so a dropped
    // addon heals itself instead of waiting for a preference write or restart.
    private val failedManifestUrls = mutableSetOf<String>()
    private val failedManifestLock = Any()
    private var failedManifestRetryJob: Job? = null

    init {
        syncScope.launch {
            try {
                loadManifestCacheFromDisk()
            } finally {
                diskCacheLoaded.complete(Unit)
            }
        }
    }

    private fun isCacheStale(): Boolean =
        clock() - lastManifestRefreshAttemptTime > MANIFEST_CACHE_TTL_MS

    // T5 (0.9.0 relay): scheduling is serialised so the staleness check, the
    // timestamp and the job assignment happen as one step — without the lock two
    // recomputations can each observe a stale clock and an inactive job before
    // either launched coroutine runs, and both sweep. The attempt is recorded at
    // START, not completion: a sweep whose fetches all fail must still count, or
    // every later recomputation re-arms a full fetch of every addon indefinitely
    // while offline (the timestamp-only-on-success bug). Cost: a cancelled sweep
    // counts as an attempt; nothing cancels this job or syncScope today, so that
    // only arises at process death. Policy: minimum interval between refresh
    // STARTS, not a freshness guarantee after completion — do not "fix" by moving
    // the assignment to completion; that reinstates the bug this prevents.
    private fun scheduleManifestRefresh(urls: List<String>) {
        if (urls.isEmpty()) {
            // Nothing to attempt, so nothing is recorded — enabling an addon
            // straight afterwards must not inherit a full TTL window it never had.
            Log.d(TAG, "Background manifest refresh skipped: no enabled cached addons")
            return
        }
        synchronized(manifestRefreshLock) {
            if (manifestRefreshJob?.isActive == true) return
            // Re-checked under the lock: the caller tested this before we got here.
            if (!isCacheStale()) return
            lastManifestRefreshAttemptTime = clock()
            manifestRefreshJob = syncScope.launch {
                val refreshed = urls.map { url ->
                    async {
                        // Bound each refresh so awaitAll() isn't held by the slowest
                        // (or a dead) addon for the full 60 s client read timeout.
                        withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { fetchAddon(url) }
                            ?: NetworkResult.Error("Manifest refresh timed out: $url")
                    }
                }.awaitAll()
                if (refreshed.any { it is NetworkResult.Success }) {
                    Log.d(TAG, "Background manifest refresh completed")
                } else {
                    Log.w(TAG, "Background manifest refresh failed for all ${urls.size} addon(s)")
                }
            }
        }
    }

    // T5 (ysosrs 7bb4e446f): a dropped addon heals itself on a 30 s / 2 m / 10 m
    // backoff ladder instead of waiting for a preference write or restart.
    private fun scheduleFailedManifestRetry(urls: List<String>) {
        if (urls.isEmpty()) return
        synchronized(failedManifestLock) {
            failedManifestUrls.addAll(urls)
            if (failedManifestRetryJob?.isActive == true) return
            failedManifestRetryJob = syncScope.launch {
                val backoffsMs = longArrayOf(30_000L, 120_000L, 600_000L)
                for (delayMs in backoffsMs) {
                    delay(delayMs)
                    val toRetry = synchronized(failedManifestLock) { failedManifestUrls.toList() }
                    if (toRetry.isEmpty()) break
                    toRetry.forEach { url ->
                        val ok = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) {
                            fetchAddon(url) is NetworkResult.Success
                        } ?: false
                        if (ok) {
                            synchronized(failedManifestLock) { failedManifestUrls.remove(url) }
                            Log.d(TAG, "Recovered addon manifest on retry host=${url.safeHost()}")
                        }
                    }
                    if (synchronized(failedManifestLock) { failedManifestUrls.isEmpty() }) break
                }
            }
        }
    }

    // T5 (ysosrs 7bb4e446f): force a manifest re-fetch of every installed, ENABLED
    // addon, bypassing the in-memory cache — heals addons that dropped out because
    // their manifest fetch failed. Called from the addon manager's sync-now action.
    // T5 review (divergence from upstream): the attempt time is stamped at START,
    // under the same lock as the background sweep — upstream stamps at completion,
    // which lets this refresh's own successful fetches (revision bump → flow
    // recompute → still-stale check) schedule a duplicate concurrent full sweep.
    // Accepted cost, same as the cancelled-sweep case in the policy comment above:
    // a screen-exit cancel of a user-triggered refresh suppresses background
    // sweeps for one TTL window.
    override suspend fun refreshAllManifests() {
        val urls = preferences.installedAddonUrls.first()
        if (urls.isEmpty()) return
        val enabledByUrl = preferences.addonEnabledStates.first()
            .mapKeys { (url, _) -> canonicalizeUrl(url) }
        synchronized(manifestRefreshLock) {
            lastManifestRefreshAttemptTime = clock()
        }
        coroutineScope {
            urls.mapNotNull { url ->
                val canonical = canonicalizeUrl(url)
                if (enabledByUrl[canonical] == false) return@mapNotNull null
                async {
                    withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { fetchAddon(url) }
                }
            }.awaitAll()
        }
    }

    private suspend fun loadManifestCacheFromDisk() = kotlinx.coroutines.withContext(dispatcher) {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(LEGACY_MANIFEST_CACHE_KEY)) {
                prefs.edit().remove(LEGACY_MANIFEST_CACHE_KEY).apply()
            }
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return@withContext
            val cached: Map<String, Addon> = decodeManifestCache(json) ?: return@withContext
            synchronized(manifestCacheLock) {
                // Disk is a cold-start seed only: never downgrade a manifest that a
                // fresh fetch already placed in memory this process (lane B m4).
                cached.forEach { (url, addon) -> if (url !in manifestCache) manifestCache[url] = addon }
            }
            bumpManifestCacheRevision()
            Log.d(TAG, "Loaded ${cached.size} cached manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        syncScope.launch {
            persistMutex.withLock {
                try {
                    val snapshot = synchronized(manifestCacheLock) { manifestCache.toMap() }
                    val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putString(MANIFEST_CACHE_KEY, encodeManifestCache(snapshot)).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist manifest cache to disk", e)
                }
            }
        }
    }

    /**
     * Installed addons, re-evaluated on any URL, name, enabled-state or manifest-cache change.
     * Each pass emits the cached manifests first. On a cache miss it fetches every enabled miss in
     * parallel; each successful fetch that changes the cache bumps [manifestCacheRevision], so
     * [flatMapLatest] cancels this pass (sibling fetches included) and the next pass emits the
     * cache with that addon and re-fetches what is still missing: addons appear one by one. The
     * post-fetch `fresh != cached` emit is what reliably publishes a round no fetch changed the
     * cache in (misses that failed and became placeholders); the restart is asynchronous, so a
     * round whose last miss succeeded may also emit here first (same content, deduped by the
     * StateFlow). Batching it ("fetch all, emit once") would hold Home behind the slowest
     * manifest; kept as is (wave C S4, C4-5 pins). The TTL refresh runs on every pass, a pass
     * with misses included, over the enabled manifests that were cached at its start (the misses
     * were just fetched), so an addon whose manifest never loads no longer keeps the others from
     * refreshing (Spec 21 C4-R1).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val installedAddonsFlow: kotlinx.coroutines.flow.StateFlow<List<Addon>> =
        combine(
            preferences.installedAddonUrls,
            preferences.userSetNames,
            preferences.addonEnabledStates,
            manifestCacheRevision
        ) { urls, names, enabledStates, _ -> Triple(urls, names, enabledStates) }
        .flatMapLatest { (urls, userNames, enabledStates) ->
            flow {
                if (urls.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }

                // T5 (ysosrs 7bb4e446f): wait for the on-disk manifest cache before
                // the first evaluation, so a cold start reads cached manifests
                // instead of racing to re-fetch every addon at once.
                diskCacheLoaded.await()

                val enabledByUrl = enabledStates.mapKeys { (url, _) -> canonicalizeUrl(url) }
                val cached = urls.mapNotNull { url ->
                    val canonical = canonicalizeUrl(url)
                    val enabled = enabledByUrl[canonical] ?: true
                    getCachedManifest(canonical)
                        ?.copy(enabled = enabled)
                        ?: if (!enabled) placeholderAddon(canonical, userNames, enabled) else null
                }
                if (cached.isNotEmpty()) {
                    emit(applyDisplayNames(cached, userNames, enabledByUrl))
                }

                val missedUrls = urls.mapTo(HashSet()) { url -> canonicalizeUrl(url) }
                    .filterTo(HashSet()) { canonical ->
                        (enabledByUrl[canonical] ?: true) && getCachedManifest(canonical) == null
                    }
                if (missedUrls.isNotEmpty()) {
                    val failedUrls = CopyOnWriteArrayList<String>()
                    val fresh = coroutineScope {
                        urls.map { url ->
                            async {
                                val canonical = canonicalizeUrl(url)
                                val enabled = enabledByUrl[canonical] ?: true
                                if (!enabled) {
                                    return@async getCachedManifest(canonical)
                                        ?.copy(enabled = false)
                                        ?: placeholderAddon(canonical, userNames, enabled = false)
                                }
                                val cachedManifest = getCachedManifest(canonical)
                                if (cachedManifest != null) {
                                    cachedManifest.copy(enabled = enabled)
                                } else when (val result = fetchAddon(url)) {
                                    is NetworkResult.Success -> result.data.copy(enabled = enabled)
                                    else -> {
                                        // T5 (ysosrs 7bb4e446f): enabled addon whose manifest
                                        // fetch failed — keep it visible as a placeholder instead
                                        // of dropping it, and schedule a retry. The placeholder
                                        // carries no stream resource or catalog, so it stays
                                        // inert on the stream path until the real manifest lands
                                        // and bumps the cache revision.
                                        failedUrls.add(canonical)
                                        placeholderAddon(canonical, userNames, enabled = true)
                                    }
                                }
                            }
                        }.awaitAll()
                    }

                    if (fresh != cached) {
                        emit(applyDisplayNames(fresh, userNames, enabledByUrl))
                    }
                    if (failedUrls.isNotEmpty()) {
                        scheduleFailedManifestRetry(failedUrls.toList())
                    }
                }
                if (isCacheStale()) {
                    scheduleManifestRefresh(
                        urls.filter { url ->
                            val canonical = canonicalizeUrl(url)
                            (enabledByUrl[canonical] ?: true) && canonical !in missedUrls
                        }
                    )
                }
            }.flowOn(dispatcher)
        }
        .stateIn(syncScope, SharingStarted.Eagerly, emptyList<Addon>())

    override fun getInstalledAddons(): Flow<List<Addon>> = installedAddonsFlow

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val (basePath, baseQuery) = splitAddonBaseUrl(cleanBaseUrl)
        val manifestUrl = "$basePath/manifest.json$baseQuery"

        return when (val result = safeApiCall(context) { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                if (putCachedManifestIfChanged(cleanBaseUrl, addon)) {
                    Log.d(TAG, "Updated addon manifest cache host=${cleanBaseUrl.safeHost()} version=${addon.version} configVersion=${addon.configVersion}")
                }
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch addon manifest for host=${manifestUrl.safeHost()} code=${result.code} message=${result.message}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        if (!preferences.addAddon(cleanUrl)) return
        triggerRemoteSync()
    }

    override suspend fun removeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        if (!preferences.removeAddon(cleanUrl)) return
        // T5 review: also drop the retry-ladder entry, else the 30 s/2 m/10 m ladder
        // keeps fetching a removed addon and a successful retry re-caches a manifest
        // for a URL nothing looks up (upstream gap, fixed here).
        synchronized(failedManifestLock) { failedManifestUrls.remove(cleanUrl) }
        if (removeCachedManifest(cleanUrl)) {
            persistManifestCacheToDisk()
            bumpManifestCacheRevision()
        }
        pruneCertPins()
        triggerRemoteSync()
    }

    override suspend fun renameAddon(url: String, name: String, manifestName: String) {
        if (!preferences.setUserSetName(url, name, manifestName)) return
        triggerRemoteSync()
    }

    override fun getSearchOnSubmitUrls(): Flow<Set<String>> = preferences.searchOnSubmitUrls

    // Local-only setting (Spec 40 D1): nothing to push.
    override suspend fun setSearchOnSubmit(url: String, onSubmit: Boolean) {
        preferences.setSearchOnSubmit(url, onSubmit)
    }

    override suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>) {
        if (!preferences.spliceAddonOrder(urls, removedUrls)) return
        pruneCertPins()
        triggerRemoteSync()
    }

    override suspend fun moveAddon(url: String, direction: Int) {
        if (!preferences.moveAddon(url, direction)) return
        triggerRemoteSync()
    }

    override suspend fun setAddonEnabled(url: String, enabled: Boolean) {
        val cleanUrl = canonicalizeUrl(url)
        if (!preferences.setAddonEnabled(cleanUrl, enabled)) return
        if (enabled && getCachedManifest(cleanUrl) == null) {
            fetchAddon(cleanUrl)
        }
        triggerRemoteSync()
    }

    /**
     * @param profileId the profile the remote list was fetched for; read and written
     *   explicitly so a profile switch during the sync cannot replace another
     *   profile's addons (L9-05/67).
     */
    suspend fun reconcileWithRemoteAddonUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true,
        profileId: Int
    ) {
        val normalizedRemote = remoteUrls
            .map { canonicalizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeUrl(it) }
        val remoteSet = normalizedRemote.map { normalizeUrl(it) }.toSet()

        val initialLocalUrls = preferences.getInstalledAddonUrls(profileId)
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalUrls.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteAddonUrls: remote list empty while local has ${initialLocalUrls.size} entries; preserving local addons"
            )
            false
        } else {
            removeMissingLocal
        }

     
        val localByNormalized = linkedMapOf<String, String>()
        initialLocalUrls.forEach { url ->
            localByNormalized.putIfAbsent(normalizeUrl(url), canonicalizeUrl(url))
        }

        val remoteOrdered = normalizedRemote.map { remote ->
            localByNormalized[normalizeUrl(remote)] ?: remote
        }

        val finalList = if (shouldRemoveMissingLocal) {
            remoteOrdered
        } else {
            val extras = initialLocalUrls
                .map { canonicalizeUrl(it) }
                .filter { normalizeUrl(it) !in remoteSet }
            remoteOrdered + extras
        }

        if (shouldRemoveMissingLocal) {
            val removedAny = initialLocalUrls
                .filter { normalizeUrl(it) !in remoteSet }
                .map { canonicalizeUrl(it) }
                .fold(false) { removed, url ->
                    // T5 review: drop retry-ladder entries too, same rationale as
                    // removeAddon — a ladder retry on a removed URL can re-cache a
                    // manifest nothing looks up.
                    synchronized(failedManifestLock) { failedManifestUrls.remove(url) }
                    removeCachedManifest(url) || removed
                }
            if (removedAny) {
                persistManifestCacheToDisk()
                bumpManifestCacheRevision()
            }
        }


        val currentCanonical = initialLocalUrls.map { canonicalizeUrl(it) }
        if (finalList != currentCanonical) {
            preferences.setAddonOrder(finalList, profileId)
            pruneCertPins()
        }
    }

    private fun placeholderAddon(
        url: String,
        userSetNames: Map<String, String>,
        enabled: Boolean
    ): Addon {
        val canonical = canonicalizeUrl(url)
        // name stays the URL-derived fallback (no manifest name is known), so a rename panel on
        // this row never mistakes the user's name for the addon's own.
        val fallbackName = canonical.substringBefore("?").substringAfterLast("/").ifBlank { canonical }
        val displayName = (userSetNames[canonical] ?: userSetNames[url])?.takeIf { it.isNotBlank() }
            ?: fallbackName
        return Addon(
            id = canonical,
            name = fallbackName,
            displayName = displayName,
            version = "",
            description = null,
            logo = null,
            baseUrl = canonical,
            catalogs = emptyList(),
            types = emptyList(),
            rawTypes = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )
    }

    private fun applyDisplayNames(
        addons: List<Addon>,
        userSetNames: Map<String, String>,
        enabledStates: Map<String, Boolean>
    ): List<Addon> {
        val withUserNames = addons.map { addon ->
            val canonical = canonicalizeUrl(addon.baseUrl)
            val userSetName = userSetNames[canonical] ?: userSetNames[addon.baseUrl]
            val enabled = enabledStates[canonical] ?: addon.enabled
            if (!userSetName.isNullOrBlank() && userSetName != addon.name) {
                addon.copy(displayName = userSetName, enabled = enabled)
            } else {
                addon.copy(enabled = enabled)
            }
        }

        val unrenamed = withUserNames.filter { it.displayName == it.name }
        val nameCounts = mutableMapOf<String, Int>()
        for (addon in unrenamed) {
            nameCounts[addon.name] = (nameCounts[addon.name] ?: 0) + 1
        }

        val nameCounters = mutableMapOf<String, Int>()
        return withUserNames.map { addon ->
            if (addon.displayName != addon.name) {
                addon
            } else if ((nameCounts[addon.name] ?: 0) <= 1) {
                addon
            } else {
                val occurrence = (nameCounters[addon.name] ?: 0) + 1
                nameCounters[addon.name] = occurrence
                if (occurrence == 1) {
                    addon
                } else {
                    addon.copy(displayName = "${addon.name} ($occurrence)")
                }
            }
        }
    }

    private fun getCachedManifest(url: String): Addon? =
        synchronized(manifestCacheLock) { manifestCache[url] }

    private fun putCachedManifestIfChanged(url: String, addon: Addon): Boolean {
        val changed = synchronized(manifestCacheLock) {
            val existing = manifestCache[url]
            if (existing == null || hasManifestChanged(existing, addon)) {
                manifestCache[url] = addon
                true
            } else {
                false
            }
        }
        if (changed) {
            persistManifestCacheToDisk()
            bumpManifestCacheRevision()
        }
        return changed
    }

    private fun removeCachedManifest(url: String): Boolean =
        synchronized(manifestCacheLock) {
            manifestCache.remove(url) != null
        }

    private fun bumpManifestCacheRevision() {
        // T5 (0.9.0 relay): a sweep fetches every addon in parallel and each success
        // calls this through putCachedManifestIfChanged, so a plain read-modify-write
        // can lose an increment and with it one re-emission of installedAddonsFlow.
        manifestCacheRevision.update { it + 1 }
    }

    /**
     * Every manifest field counts except the three that are not manifest content:
     * displayName and enabled (user state, applied per emission) and timestamp
     * (the addon's `_timestamp`, which may change on every fetch). A new Addon field
     * counts automatically.
     */
    private fun hasManifestChanged(existing: Addon, incoming: Addon): Boolean =
        existing.copy(
            displayName = incoming.displayName,
            enabled = incoming.enabled,
            timestamp = incoming.timestamp
        ) != incoming
}
