package com.nuvio.tv.core.cloudstream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CloudStreamRepositoryItem(
    val manifest: CloudStreamRepositoryManifest,
    val enabled: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class CloudStreamPluginItem(
    val metadata: CloudStreamPluginMetadata,
    val compatibility: CloudStreamCompatibility,
    val installedVersion: Int? = null,
    val installedAtEpochMs: Long = 0L,
    val enabled: Boolean = false,
    val verified: Boolean = false,
    val isInstalling: Boolean = false,
    val errorMessage: String? = null,
) {
    val isInstalled: Boolean
        get() = installedVersion != null

    val hasUpdate: Boolean
        get() = installedVersion != null && metadata.version > installedVersion

    val isRunnable: Boolean
        get() = isInstalled && enabled && verified && compatibility.isRunnable
}

data class CloudStreamUiState(
    val repositories: List<CloudStreamRepositoryItem> = emptyList(),
    val discoveredRepositories: List<CloudStreamRepositoryManifest> = emptyList(),
    val plugins: List<CloudStreamPluginItem> = emptyList(),
    val registryRevision: Long = 0L,
    val securityWarningAccepted: Boolean = false,
)

sealed interface AddCloudStreamRepositoryResult {
    data class Success(val repository: CloudStreamRepositoryManifest) : AddCloudStreamRepositoryResult
    data class Error(val message: String) : AddCloudStreamRepositoryResult
}

sealed interface CloudStreamInstallResult {
    data class Success(val plugin: CloudStreamPluginItem) : CloudStreamInstallResult
    data class Error(val message: String) : CloudStreamInstallResult
}

data class CloudStreamBulkInstallFailure(
    val pluginName: String,
    val message: String,
)

data class CloudStreamBulkInstallResult(
    val requestedCount: Int,
    val installedCount: Int,
    val enabledCount: Int,
    val skippedCount: Int,
    val failures: List<CloudStreamBulkInstallFailure> = emptyList(),
)

/**
 * Repository + plugin registry for CloudStream on TV.
 *
 * TV-native rewrite of Enhanced-2's `fullCommonMain` CloudStreamRepository with the same
 * semantics (repo fetch, plugin download + SHA-256 verify + atomic install, enable/disable,
 * in-flight request dedup, link/load caches). Differences from the source:
 * - Plain `object`, no KMP expect/actual; no per-profile state (one registry for the TV app).
 * - HTTP via a plain OkHttpClient instead of NuvioMobile's addon http helpers.
 * - `android.util.Log` instead of kermit/RuntimeDiagnostics.
 * - No precompiled-adapter registry (no KickTR equivalent on TV); providers come only
 *   from [CloudStreamDexRuntime].
 * - No profile handling: `initialize()` restores the single stored registry.
 */
object CloudStreamRepository {
    private const val TAG = "CloudStreamRepo"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(CloudStreamUiState())
    val uiState: StateFlow<CloudStreamUiState> = _uiState.asStateFlow()

    private var initialized = false
    private val refreshJobs = mutableMapOf<String, Job>()
    private val mainPageRequestMutex = Mutex()
    private val mainPageRequests =
        mutableMapOf<String, kotlinx.coroutines.Deferred<Result<List<Pair<String, List<CloudStreamSearchItem>>>>>>()
    private val loadLinksRequestMutex = Mutex()
    private val loadLinksRequests =
        mutableMapOf<String, kotlinx.coroutines.Deferred<Result<List<CloudStreamPlaybackSource>>>>()
    private val loadLinksCache = linkedMapOf<String, CachedCloudStreamLinks>()
    private val loadLinksCacheMutex = Mutex()
    private val loadResultCache = linkedMapOf<String, CachedCloudStreamLoadItem>()
    private val loadResultCacheMutex = Mutex()

    private const val LOAD_LINKS_CACHE_MAX_ENTRIES = 16
    private const val LOAD_LINKS_CACHE_TTL_MS = 120_000L
    private const val LOAD_RESULT_CACHE_MAX_ENTRIES = 16
    private const val LOAD_RESULT_CACHE_TTL_MS = 600_000L
    private const val REPOSITORY_DISCOVERY_CONCURRENCY = 6
    private const val PROVIDER_REQUEST_CONCURRENCY = 4

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        _uiState.value = restoreState()
    }

    /** Plugins that can currently provide streams, in registry order. */
    fun runnablePlugins(): List<CloudStreamPluginItem> {
        initialize()
        val disabledRepoUrls = _uiState.value.repositories.filterNot { it.enabled }.map { it.manifest.sourceUrl }.toSet()
        return _uiState.value.plugins
            .filter { it.isRunnable && it.metadata.repositoryManifestUrl !in disabledRepoUrls }
    }

    fun clearLocalState() {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        initialized = false
        _uiState.value = CloudStreamUiState()
        cancelInFlightMainPageRequests()
        cancelInFlightLoadLinksRequests()
        loadLinksCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadLinksCache.clear() } finally { loadLinksCacheMutex.unlock() }
            }
        }
        loadResultCacheMutex.tryLock().let { locked ->
            if (locked) {
                try { loadResultCache.clear() } finally { loadResultCacheMutex.unlock() }
            }
        }
        CloudStreamDexRuntime.clear()
        CloudStreamStorage.clearPackages()
        CloudStreamStorage.clearAllState()
    }

    fun acceptSecurityWarning() {
        initialize()
        _uiState.update { it.copy(securityWarningAccepted = true) }
        persist()
    }

    suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult {
        initialize()
        val manifestUrl = runCatching { resolveCloudStreamRepositoryInput(rawUrl) }
            .getOrElse { return AddCloudStreamRepositoryResult.Error(it.message ?: "Invalid repository URL") }
        if (_uiState.value.repositories.any { it.manifest.sourceUrl == manifestUrl }) {
            return AddCloudStreamRepositoryResult.Error("CloudStream repository is already added")
        }

        return runCatching { fetchRepository(manifestUrl) }
            .fold(
                onSuccess = { (repository, plugins) ->
                    _uiState.update { current ->
                        current.copy(
                            repositories = current.repositories + CloudStreamRepositoryItem(repository),
                            plugins = (current.plugins + plugins.map(::newPluginItem))
                                .distinctBy { it.metadata.id.value }
                                .sortedBy { it.metadata.name.lowercase() },
                        )
                    }
                    CloudStreamDexRuntime.registerNativeRepository(
                        url = manifestUrl,
                        name = repository.name,
                        iconUrl = repository.iconUrl,
                    )
                    persist()
                    AddCloudStreamRepositoryResult.Success(repository)
                },
                onFailure = { error ->
                    Log.w(TAG, "CloudStream repository install failed url=$manifestUrl", error)
                    AddCloudStreamRepositoryResult.Error(error.message ?: "Could not load CloudStream repository")
                },
            )
    }

    suspend fun discoverRepositories(rawInput: String): Result<Int> {
        initialize()
        val input = rawInput.trim()
        if (input.isBlank()) return Result.failure(IllegalArgumentException("Repository input is empty"))

        return runCatching {
            val databaseUrl = when {
                input.equals("MegaProvider", ignoreCase = true) ||
                    input.equals("MegaRepo", ignoreCase = true) ||
                    input.contains("self-similarity/MegaRepo", ignoreCase = true) ||
                    input.contains("cs-repos/master/repos-db.json", ignoreCase = true) ->
                    "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
                else -> null
            }

            val repositoryUrls = if (databaseUrl != null) {
                json.parseToJsonElement(httpGetText(databaseUrl)).jsonArray.mapNotNull { element ->
                    when {
                        element is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
                        element is kotlinx.serialization.json.JsonObject -> element["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }.map(String::trim).filter(String::isNotBlank).distinct()
            } else {
                listOf(resolveCloudStreamRepositoryInput(input))
            }

            val manifests = coroutineScope {
                val gate = Semaphore(REPOSITORY_DISCOVERY_CONCURRENCY)
                repositoryUrls.map { url ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            runCatching {
                                CloudStreamRepositoryParser.parseRepository(url, httpGetText(url))
                            }.getOrNull()
                        }
                    }
                }.mapNotNull { it.await() }
            }.distinctBy { it.sourceUrl }

            _uiState.update { current ->
                current.copy(
                    discoveredRepositories = manifests.filterNot { discovered ->
                        current.repositories.any { it.manifest.sourceUrl == discovered.sourceUrl }
                    },
                )
            }
            Log.i(TAG, "CloudStream repository discovery found ${manifests.size} repositories")
            manifests.size
        }
    }

    fun refreshRepository(manifestUrl: String) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        if (refreshJobs[normalizedUrl]?.isActive == true) return
        _uiState.update { current ->
            current.copy(
                repositories = current.repositories.map { item ->
                    if (item.manifest.sourceUrl == normalizedUrl) item.copy(isRefreshing = true, errorMessage = null) else item
                },
            )
        }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching { fetchRepository(normalizedUrl) }
                    .fold(
                        onSuccess = { (repository, plugins) -> applyRepositoryRefresh(repository, plugins) },
                        onFailure = { error ->
                            Log.w(TAG, "CloudStream repository refresh failed url=$normalizedUrl", error)
                            _uiState.update { current ->
                                current.copy(
                                    repositories = current.repositories.map { item ->
                                        if (item.manifest.sourceUrl == normalizedUrl) {
                                            item.copy(isRefreshing = false, errorMessage = error.message ?: "Refresh failed")
                                        } else item
                                    },
                                )
                            }
                        },
                    )
                persist()
            } finally {
                if (refreshJobs[normalizedUrl] === job) refreshJobs.remove(normalizedUrl)
            }
        }
        refreshJobs[normalizedUrl] = job
        job.start()
    }

    fun refreshAll() {
        initialize()
        _uiState.value.repositories.forEach { refreshRepository(it.manifest.sourceUrl) }
    }

    fun removeRepository(manifestUrl: String) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: return
        val removedPlugins = _uiState.value.plugins.filter { it.metadata.repositoryManifestUrl == normalizedUrl }
        removedPlugins.forEach {
            CloudStreamDexRuntime.unload(it.metadata.id.value)
            CloudStreamStorage.deletePackage(it.metadata.id.storageKey)
        }
        scope.launch {
            CloudStreamDexRuntime.removeNativeRepository(normalizedUrl)
        }
        _uiState.update { current ->
            current.copy(
                repositories = current.repositories.filterNot { it.manifest.sourceUrl == normalizedUrl },
                plugins = current.plugins.filterNot { it.metadata.repositoryManifestUrl == normalizedUrl },
                registryRevision = current.registryRevision + 1,
            )
        }
        persist()
    }

    fun setRepositoryEnabled(manifestUrl: String, enabled: Boolean) {
        initialize()
        val normalizedUrl = runCatching { normalizeCloudStreamRepositoryUrl(manifestUrl) }.getOrNull() ?: manifestUrl
        val affectedUnloadPluginIds = mutableListOf<String>()
        _uiState.update { current ->
            var changed = false
            val repositories = current.repositories.map { repo ->
                if (repo.manifest.sourceUrl == normalizedUrl && repo.enabled != enabled) {
                    changed = true
                    repo.copy(enabled = enabled)
                } else repo
            }
            val plugins = current.plugins.map { plugin ->
                if (plugin.metadata.repositoryManifestUrl != normalizedUrl) return@map plugin
                // Keep the user's per-plugin choice: disabling a repo only unloads its
                // plugins (below); re-enabling must not resurrect plugins the user
                // deliberately disabled. runnablePlugins() already excludes plugins
                // whose repo is disabled, so this flag is purely the user's choice.
                if (!enabled && plugin.enabled) {
                    affectedUnloadPluginIds += plugin.metadata.id.value
                }
                plugin
            }
            current.copy(
                repositories = repositories,
                plugins = plugins,
                registryRevision = current.registryRevision + if (changed) 1 else 0,
            )
        }
        if (!enabled) {
            affectedUnloadPluginIds.forEach { pluginId ->
                CloudStreamDexRuntime.unload(pluginId)
            }
        }
        persist()
    }

    suspend fun installPlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    suspend fun updatePlugin(pluginId: String): CloudStreamInstallResult = installOrUpdate(pluginId)

    suspend fun installAndEnablePlugins(pluginIds: List<String>): CloudStreamBulkInstallResult {
        initialize()
        val requestedIds = pluginIds.distinct()
        if (requestedIds.isEmpty()) {
            return CloudStreamBulkInstallResult(
                requestedCount = 0,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
            )
        }
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamBulkInstallResult(
                requestedCount = requestedIds.size,
                installedCount = 0,
                enabledCount = 0,
                skippedCount = 0,
                failures = listOf(
                    CloudStreamBulkInstallFailure(
                        pluginName = "CloudStream",
                        message = "Accept the third-party plugin security warning before installation",
                    ),
                ),
            )
        }

        var installedCount = 0
        var enabledCount = 0
        var skippedCount = 0
        val failures = mutableListOf<CloudStreamBulkInstallFailure>()

        requestedIds.forEach { pluginId ->
            val before = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            if (before == null) {
                failures += CloudStreamBulkInstallFailure(pluginName = pluginId, message = "CloudStream plugin was not found")
                return@forEach
            }
            if (before.isInstalling) {
                skippedCount += 1
                return@forEach
            }
            if (!before.metadata.status.canInstall) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = "This CloudStream plugin is marked as down",
                )
                return@forEach
            }
            if (!before.compatibility.isRunnable) {
                failures += CloudStreamBulkInstallFailure(
                    pluginName = before.metadata.name,
                    message = before.compatibility.reason,
                )
                return@forEach
            }

            val needsInstall = !before.isInstalled || before.hasUpdate || !before.verified
            val installResult = if (needsInstall) installOrUpdate(pluginId) else CloudStreamInstallResult.Success(before)
            when (installResult) {
                is CloudStreamInstallResult.Success -> {
                    if (needsInstall) installedCount += 1
                    setPluginEnabled(pluginId, true)
                    val after = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
                    if (after?.isRunnable == true) {
                        enabledCount += 1
                    } else {
                        failures += CloudStreamBulkInstallFailure(
                            pluginName = installResult.plugin.metadata.name,
                            message = "The package was installed, but could not be enabled because verification or compatibility checks failed.",
                        )
                    }
                }
                is CloudStreamInstallResult.Error -> {
                    failures += CloudStreamBulkInstallFailure(
                        pluginName = before.metadata.name,
                        message = installResult.message,
                    )
                }
            }
        }

        return CloudStreamBulkInstallResult(
            requestedCount = requestedIds.size,
            installedCount = installedCount,
            enabledCount = enabledCount,
            skippedCount = skippedCount,
            failures = failures,
        )
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        initialize()
        _uiState.update { current ->
            var changed = false
            val plugins = current.plugins.map { item ->
                if (item.metadata.id.value != pluginId) return@map item
                val nextEnabled = enabled && item.isInstalled && item.verified && item.compatibility.isRunnable &&
                    item.metadata.status.canInstall
                changed = item.enabled != nextEnabled
                item.copy(enabled = nextEnabled, errorMessage = null)
            }
            current.copy(
                plugins = plugins,
                registryRevision = current.registryRevision + if (changed) 1 else 0,
            )
        }
        if (!enabled) {
            CloudStreamDexRuntime.unload(pluginId)
        }
        persist()
    }

    fun removePlugin(pluginId: String) {
        initialize()
        val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId } ?: return
        CloudStreamDexRuntime.unload(pluginId)
        CloudStreamStorage.deletePackage(plugin.metadata.id.storageKey)
        _uiState.update { current ->
            current.copy(
                plugins = current.plugins.map { item ->
                    if (item.metadata.id.value == pluginId) {
                        item.copy(
                            installedVersion = null,
                            installedAtEpochMs = 0L,
                            enabled = false,
                            verified = false,
                            errorMessage = null,
                        )
                    } else item
                },
                registryRevision = current.registryRevision + 1,
            )
        }
        persist()
    }

    suspend fun getMainPage(
        providerId: String,
        page: Int,
    ): Result<List<Pair<String, List<CloudStreamSearchItem>>>> {
        val normalizedPage = page.coerceAtLeast(1)
        val requestKey = providerId + ":" + normalizedPage

        val request = mainPageRequestMutex.withLock {
            mainPageRequests[requestKey]?.takeIf { it.isActive && !it.isCancelled }
                ?: scope.async(start = CoroutineStart.DEFAULT) {
                    providerResult(providerId) { getMainPage(normalizedPage) }
                }.also { deferred ->
                    mainPageRequests[requestKey] = deferred
                    deferred.invokeOnCompletion {
                        // Non-suspend cleanup: best-effort, a missed removal
                        // is harmless (the next request overwrites the entry).
                        if (mainPageRequestMutex.tryLock()) {
                            try {
                                if (mainPageRequests[requestKey] === deferred) {
                                    mainPageRequests.remove(requestKey)
                                }
                            } finally {
                                mainPageRequestMutex.unlock()
                            }
                        }
                    }
                }
        }

        return request.await()
    }

    private fun cancelInFlightMainPageRequests() {
        if (!mainPageRequestMutex.tryLock()) return
        val requests = try {
            mainPageRequests.values.toList().also { mainPageRequests.clear() }
        } finally {
            mainPageRequestMutex.unlock()
        }
        requests.forEach { it.cancel() }
    }

    suspend fun search(
        query: String,
        providerId: String?,
    ): List<Result<List<CloudStreamSearchItem>>> {
        initialize()
        val activeIds = runnableProviderIds().filter { providerId == null || it == providerId }
        val results = coroutineScope {
            val gate = Semaphore(PROVIDER_REQUEST_CONCURRENCY)
            activeIds.map { id ->
                async {
                    gate.withPermit {
                        providerResult(id) { search(query.trim()) }
                    }
                }
            }.map { it.await() }
        }
        return results
    }

    suspend fun load(providerId: String, data: String): Result<CloudStreamLoadItem> {
        initialize()
        val cacheKey = "$providerId\u0000$data"
        val now = System.currentTimeMillis()
        loadResultCacheMutex.withLock {
            val entry = loadResultCache[cacheKey]
            if (entry != null && entry.expiresAtEpochMs > now) {
                // Refresh recency: linkedMapOf is insertion-ordered, so a
                // cache hit must move the entry to the end to keep LRU order.
                loadResultCache.remove(cacheKey)
                loadResultCache[cacheKey] = entry
                entry.item
            } else {
                null
            }
        }?.let { cached ->
            return Result.success(cached)
        }
        return providerResult(providerId) { load(data) }
            .onSuccess { item ->
                loadResultCacheMutex.withLock {
                    loadResultCache[cacheKey] =
                        CachedCloudStreamLoadItem(item, now + LOAD_RESULT_CACHE_TTL_MS)
                    while (loadResultCache.size > LOAD_RESULT_CACHE_MAX_ENTRIES) {
                        loadResultCache.remove(loadResultCache.entries.first().key)
                    }
                }
            }
    }

    suspend fun loadByExternalId(
        providerId: String,
        externalId: String,
    ): Result<CloudStreamLoadItem?> {
        return providerResult(providerId) {
            loadByExternalId(externalId)
        }
    }

    suspend fun loadLinks(providerId: String, data: String): Result<List<CloudStreamPlaybackSource>> {
        initialize()
        val requestKey = "$providerId\u0000$data"
        val now = System.currentTimeMillis()
        loadLinksCacheMutex.withLock {
            val entry = loadLinksCache[requestKey]
            if (entry != null && entry.expiresAtEpochMs > now) {
                loadLinksCache.remove(requestKey)
                loadLinksCache[requestKey] = entry
                entry.links
            } else {
                null
            }
        }?.let { cached ->
            return Result.success(cached)
        }

        val request = loadLinksRequestMutex.withLock {
            loadLinksRequests[requestKey]?.takeIf { it.isActive && !it.isCancelled }
                ?: scope.async(start = CoroutineStart.DEFAULT) {
                    providerResult(providerId) { loadLinks(data) }
                }.also { deferred ->
                    loadLinksRequests[requestKey] = deferred
                    deferred.invokeOnCompletion {
                        if (loadLinksRequestMutex.tryLock()) {
                            try {
                                if (loadLinksRequests[requestKey] === deferred) {
                                    loadLinksRequests.remove(requestKey)
                                }
                            } finally {
                                loadLinksRequestMutex.unlock()
                            }
                        }
                    }
                }
        }

        return request.await()
            .onSuccess { links ->
                loadLinksCacheMutex.withLock {
                    loadLinksCache[requestKey] = CachedCloudStreamLinks(
                        links,
                        System.currentTimeMillis() + LOAD_LINKS_CACHE_TTL_MS,
                    )
                    while (loadLinksCache.size > LOAD_LINKS_CACHE_MAX_ENTRIES) {
                        loadLinksCache.remove(loadLinksCache.entries.first().key)
                    }
                }
            }
    }

    private fun cancelInFlightLoadLinksRequests() {
        if (!loadLinksRequestMutex.tryLock()) return
        val requests = try {
            loadLinksRequests.values.toList().also { loadLinksRequests.clear() }
        } finally {
            loadLinksRequestMutex.unlock()
        }
        requests.forEach { it.cancel() }
    }

    private suspend fun installOrUpdate(pluginId: String): CloudStreamInstallResult {
        initialize()
        if (!_uiState.value.securityWarningAccepted) {
            return CloudStreamInstallResult.Error("Accept the third-party plugin security warning before installation")
        }
        val item = _uiState.value.plugins.firstOrNull { it.metadata.id.value == pluginId }
            ?: return CloudStreamInstallResult.Error("CloudStream plugin was not found")
        if (!item.metadata.status.canInstall) {
            return CloudStreamInstallResult.Error("This CloudStream plugin is marked as down")
        }
        markInstalling(pluginId, true, null)
        return runCatching {
            val bytes = httpGetBytes(
                item.metadata.packageUrl,
                headers = mapOf("Accept" to "application/zip, application/octet-stream"),
            )
            require(bytes.isNotEmpty()) { "Downloaded .cs3 package is empty" }
            item.metadata.fileSize?.let { expectedSize ->
                if (bytes.size.toLong() != expectedSize) {
                    Log.w(
                        TAG,
                        "CloudStream package metadata size mismatch id=$pluginId expected=$expectedSize actual=${bytes.size}",
                    )
                }
            }
            CloudStreamPackageInspector.inspect(bytes)
            val expectedHash = item.metadata.fileHash
            val verified = expectedHash?.matches(bytes) ?: true
            if (expectedHash != null) require(verified) { ".cs3 SHA-256 hash mismatch" }
            CloudStreamDexRuntime.unload(pluginId)
            CloudStreamStorage.savePackageAtomically(item.metadata.id.storageKey, bytes)
            val installed = item.copy(
                installedVersion = item.metadata.version,
                installedAtEpochMs = System.currentTimeMillis(),
                enabled = item.enabled && verified && item.compatibility.isRunnable,
                verified = verified,
                isInstalling = false,
                errorMessage = null,
            )
            _uiState.update { current ->
                current.copy(
                    plugins = current.plugins.map { if (it.metadata.id.value == pluginId) installed else it },
                    registryRevision = current.registryRevision + 1,
                )
            }
            persist()
            installed
        }.fold(
            onSuccess = { CloudStreamInstallResult.Success(it) },
            onFailure = { error ->
                Log.w(TAG, "CloudStream plugin installation failed id=$pluginId", error)
                markInstalling(pluginId, false, error.message ?: "Plugin installation failed")
                CloudStreamInstallResult.Error(error.message ?: "Plugin installation failed")
            },
        )
    }

    private suspend fun fetchRepository(
        manifestUrl: String,
    ): Pair<CloudStreamRepositoryManifest, List<CloudStreamPluginMetadata>> {
        var lastManifestError: Throwable? = null
        val manifest = cloudStreamManifestCandidates(manifestUrl).firstNotNullOfOrNull { candidate ->
            runCatching {
                CloudStreamRepositoryParser.parseRepository(candidate, httpGetText(candidate))
            }.onFailure { error ->
                lastManifestError = error
                Log.w(TAG, "CloudStream repository manifest failed url=$candidate", error)
            }.getOrNull()
        } ?: throw lastManifestError ?: IllegalStateException("CloudStream repository manifest could not be loaded")
        var loadedListCount = 0
        val lists = manifest.pluginListUrls.mapNotNull { pluginListUrl ->
            runCatching {
                val listPayload = httpGetText(pluginListUrl)
                CloudStreamRepositoryParser.parsePluginList(manifest.sourceUrl, pluginListUrl, listPayload)
            }.onSuccess {
                loadedListCount += 1
            }.onFailure { error ->
                Log.w(TAG, "CloudStream plugin list failed url=$pluginListUrl", error)
            }.getOrNull()
        }
        require(loadedListCount > 0) { "CloudStream repository plugin lists could not be loaded" }
        return manifest to CloudStreamRepositoryParser.mergePluginLists(lists)
    }

    private fun cloudStreamManifestCandidates(manifestUrl: String): List<String> {
        val normalized = normalizeCloudStreamRepositoryUrl(manifestUrl)
        val alternate = when {
            "/master/repo.json" in normalized -> normalized.replace("/master/repo.json", "/main/repo.json")
            "/main/repo.json" in normalized -> normalized.replace("/main/repo.json", "/master/repo.json")
            else -> null
        }
        return listOfNotNull(normalized, alternate).distinct()
    }

    private fun applyRepositoryRefresh(
        repository: CloudStreamRepositoryManifest,
        plugins: List<CloudStreamPluginMetadata>,
    ) {
        _uiState.update { current ->
            val previousById = current.plugins.associateBy { it.metadata.id.value }
            val refreshed = plugins.map { metadata ->
                previousById[metadata.id.value]?.copy(
                    metadata = metadata,
                    compatibility = resolveCompatibility(metadata),
                    enabled = previousById[metadata.id.value]?.enabled == true && metadata.status.canInstall,
                    errorMessage = null,
                ) ?: newPluginItem(metadata)
            }
            current.copy(
                repositories = current.repositories.map { item ->
                    if (item.manifest.sourceUrl == repository.sourceUrl) {
                        CloudStreamRepositoryItem(repository)
                    } else item
                },
                plugins = (current.plugins.filterNot { it.metadata.repositoryManifestUrl == repository.sourceUrl } + refreshed)
                    .sortedBy { it.metadata.name.lowercase() },
                registryRevision = current.registryRevision + 1,
            )
        }
    }

    private fun newPluginItem(metadata: CloudStreamPluginMetadata): CloudStreamPluginItem =
        CloudStreamPluginItem(
            metadata = metadata,
            compatibility = resolveCompatibility(metadata),
        )

    private fun markInstalling(pluginId: String, installing: Boolean, error: String?) {
        _uiState.update { current ->
            current.copy(
                plugins = current.plugins.map { item ->
                    if (item.metadata.id.value == pluginId) {
                        item.copy(isInstalling = installing, errorMessage = error)
                    } else item
                },
            )
        }
    }

    private fun restoreState(): CloudStreamUiState {
        val payload = CloudStreamStorage.loadState(1) ?: return CloudStreamUiState()
        val stored = runCatching { json.decodeFromString<StoredCloudStreamState>(payload) }
            .onFailure { Log.w(TAG, "Could not restore CloudStream state", it) }
            .getOrNull() ?: return CloudStreamUiState()
        val repositories = stored.repositories.map { repository ->
            val manifestUrl = runCatching { normalizeCloudStreamRepositoryUrl(repository.manifestUrl) }
                .getOrDefault(repository.manifestUrl)
            CloudStreamRepositoryItem(
                manifest = CloudStreamRepositoryManifest(
                    sourceUrl = manifestUrl,
                    name = repository.name,
                    description = repository.description,
                    iconUrl = repository.iconUrl,
                    manifestVersion = repository.manifestVersion,
                    pluginListUrls = repository.pluginListUrls,
                ),
                enabled = repository.enabled,
            )
        }
        val plugins = stored.plugins.map { plugin ->
            val metadata = plugin.toMetadata()
            val legacyStorageKey = CloudStreamPluginId(plugin.repositoryManifestUrl, plugin.internalName).storageKey
            val storageKey = metadata.id.storageKey
            val packageExists = plugin.installedVersion != null && (
                CloudStreamStorage.packageExists(storageKey) ||
                    (legacyStorageKey != storageKey &&
                        CloudStreamStorage.migratePackage(legacyStorageKey, storageKey))
                )
            val compatibility = resolveCompatibility(metadata)
            CloudStreamPluginItem(
                metadata = metadata,
                compatibility = compatibility,
                installedVersion = plugin.installedVersion.takeIf { packageExists },
                installedAtEpochMs = plugin.installedAtEpochMs.takeIf { packageExists } ?: 0L,
                // Keep the stored per-plugin choice: do NOT fold the repo's enabled
                // state in here — runnablePlugins() already excludes plugins whose
                // repo is disabled, and folding it would wipe a deliberately-disabled
                // plugin (or a deliberately-enabled one of a disabled repo) on every
                // restart, the first persist afterwards making the loss permanent.
                enabled = plugin.enabled && packageExists && plugin.verified && compatibility.isRunnable && metadata.status.canInstall,
                verified = plugin.verified && packageExists,
            )
        }
        return CloudStreamUiState(
            repositories = repositories,
            plugins = plugins,
            registryRevision = 1,
            securityWarningAccepted = stored.securityWarningAccepted,
        )
    }

    private fun persist() {
        val current = _uiState.value
        val stored = StoredCloudStreamState(
            repositories = current.repositories.map { item ->
                StoredCloudStreamRepository(
                    manifestUrl = item.manifest.sourceUrl,
                    name = item.manifest.name,
                    description = item.manifest.description,
                    iconUrl = item.manifest.iconUrl,
                    manifestVersion = item.manifest.manifestVersion,
                    pluginListUrls = item.manifest.pluginListUrls,
                    enabled = item.enabled,
                )
            },
            plugins = current.plugins.map { item -> item.toStored() },
            securityWarningAccepted = current.securityWarningAccepted,
        )
        CloudStreamStorage.saveState(1, json.encodeToString(stored))
    }

    private fun CloudStreamPluginItem.toStored(): StoredCloudStreamPlugin = StoredCloudStreamPlugin(
        repositoryManifestUrl = metadata.repositoryManifestUrl,
        packageUrl = metadata.packageUrl,
        status = metadata.status.name,
        availableVersion = metadata.version,
        installedVersion = installedVersion,
        name = metadata.name,
        internalName = metadata.internalName,
        authors = metadata.authors,
        description = metadata.description,
        fileSize = metadata.fileSize,
        projectUrl = metadata.projectUrl,
        language = metadata.language,
        rawTvTypes = metadata.rawTvTypes,
        iconUrl = metadata.iconUrl,
        apiVersion = metadata.apiVersion,
        fileHash = metadata.fileHash?.wireValue,
        enabled = enabled,
        verified = verified,
        installedAtEpochMs = installedAtEpochMs.takeIf { installedVersion != null } ?: 0L,
    )

    private fun StoredCloudStreamPlugin.toMetadata(): CloudStreamPluginMetadata {
        val normalizedRepositoryUrl = runCatching { normalizeCloudStreamRepositoryUrl(repositoryManifestUrl) }
            .getOrDefault(repositoryManifestUrl)
        return CloudStreamPluginMetadata(
            id = CloudStreamPluginId(normalizedRepositoryUrl, internalName),
            repositoryManifestUrl = normalizedRepositoryUrl,
            packageUrl = packageUrl,
            status = runCatching { CloudStreamPluginStatus.valueOf(status) }
                .getOrDefault(CloudStreamPluginStatus.Unknown),
            version = availableVersion,
            name = name,
            internalName = internalName,
            authors = authors,
            description = description,
            fileSize = fileSize,
            projectUrl = projectUrl,
            language = language,
            tvTypes = rawTvTypes.map(CloudStreamTvType::fromWireValue).distinct(),
            rawTvTypes = rawTvTypes,
            iconUrl = iconUrl,
            apiVersion = apiVersion,
            fileHash = CloudStreamFileHash.parse(fileHash),
        )
    }

    private fun runnableProviderIds(): List<String> {
        val disabledRepoUrls = _uiState.value.repositories.filterNot { it.enabled }.map { it.manifest.sourceUrl }.toSet()
        return _uiState.value.plugins
            .filter { it.isRunnable && it.metadata.repositoryManifestUrl !in disabledRepoUrls }
            .map { it.metadata.id.value }
    }

    private fun resolveCompatibility(metadata: CloudStreamPluginMetadata): CloudStreamCompatibility =
        CloudStreamCompatibilityResolver.resolve(
            metadata = metadata,
            supportsAndroidDex = CloudStreamDexRuntime.supportsAndroidDex,
        )

    private suspend fun <T> providerResult(
        providerId: String,
        block: suspend CloudStreamProvider.() -> T,
    ): Result<T> {
        initialize()
        if (providerId !in runnableProviderIds()) {
            return Result.failure(IllegalStateException("CloudStream provider is disabled or incompatible"))
        }
        return runCatching {
            val plugin = _uiState.value.plugins.firstOrNull { it.metadata.id.value == providerId }
                ?: error("CloudStream plugin was not found")
            val provider = CloudStreamDexRuntime.provider(plugin)
                ?: error("CloudStream provider adapter was not found")
            provider.block()
        }.onFailure { Log.w(TAG, "CloudStream provider request failed id=$providerId", it) }
    }

    private suspend fun resolveCloudStreamRepositoryInput(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val expanded = when {
            trimmed.startsWith("!") ->
                resolveCloudStreamShortLink("https://py.md/${trimmed.removePrefix("!")}")
            trimmed.matches(Regex("^[a-zA-Z0-9!_-]+$")) ->
                resolveCloudStreamShortLink("https://cutt.ly/$trimmed")
            else -> trimmed
        }
        return normalizeCloudStreamRepositoryUrl(expanded)
    }

    private suspend fun resolveCloudStreamShortLink(url: String): String {
        val request = Request.Builder().url(url).get().build()
        val location = kotlinx.coroutines.withContext(Dispatchers.IO) {
            noRedirectClient.newCall(request).execute().use { response ->
                response.header("Location")?.substringBefore(',')?.trim()
            }
        }
        require(!location.isNullOrBlank()) { "CloudStream short repository link did not redirect" }
        require(!location.startsWith("https://py.md/404") && !location.startsWith("https://cutt.ly/404")) {
            "CloudStream short repository link was not found"
        }
        return location
    }

    private suspend fun httpGetText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "NuvioKE-TV")
            .get()
            .build()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                require(response.isSuccessful) {
                    "CloudStream request failed url=$url status=${response.code}"
                }
                body
            }
        }
    }

    private suspend fun httpGetBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", "NuvioKE-TV")
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                require(response.isSuccessful) {
                    "CloudStream download failed url=$url status=${response.code}"
                }
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    private data class CachedCloudStreamLinks(
        val links: List<CloudStreamPlaybackSource>,
        val expiresAtEpochMs: Long,
    )

    private data class CachedCloudStreamLoadItem(
        val item: CloudStreamLoadItem,
        val expiresAtEpochMs: Long,
    )
}
