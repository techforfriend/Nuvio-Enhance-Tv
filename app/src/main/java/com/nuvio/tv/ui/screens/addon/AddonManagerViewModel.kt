package com.nuvio.tv.ui.screens.addon

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.sync.HomeCatalogOrdering
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.network.ADDON_REQUEST_TIMEOUT_MS
import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.tls.AddonCertPinKeeper
import com.nuvio.tv.core.network.tls.AddonCertProbe
import com.nuvio.tv.core.network.tls.AddonCertProbeResult
import com.nuvio.tv.core.network.tls.AddonCertTrustAction
import com.nuvio.tv.core.network.tls.addonServerLabel
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.AddonInfo
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.core.server.CatalogInfo
import com.nuvio.tv.core.server.CatalogSourceInfo
import com.nuvio.tv.core.server.CollectionInfo
import com.nuvio.tv.core.server.CollectionSourceInfo
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.FolderInfo
import com.nuvio.tv.core.server.PageState
import com.nuvio.tv.core.server.PendingAddonChange
import com.nuvio.tv.core.server.TmdbFiltersInfo
import com.nuvio.tv.core.server.TmdbSourceMetadataInfo
import com.nuvio.tv.core.server.TmdbSourceMetadataRequest
import com.nuvio.tv.core.server.TmdbSourceSearchRequest
import com.nuvio.tv.core.server.TmdbSourceSearchResultInfo
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import com.nuvio.tv.core.util.withAcceptedAddonScheme
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "AddonManagerViewModel"

/** Internal constructor: it takes the internal [AddonHostGuard] (Spec 68 P6). */
@HiltViewModel
class AddonManagerViewModel @Inject internal constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val startupSyncService: StartupSyncService,
    private val profileManager: ProfileManager,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    @param:ApplicationContext private val context: Context,
    private val healthStore: AddonHealthStore,
    private val hostGuard: AddonHostGuard,
    private val certProbe: AddonCertProbe,
    private val certTrustAction: AddonCertTrustAction,
    private val certPinKeeper: AddonCertPinKeeper
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddonManagerUiState())
    val uiState: StateFlow<AddonManagerUiState> = _uiState.asStateFlow()

    val isReadOnly: Boolean
        get() {
            return AddonManagementAccess.isReadOnly(profileManager.activeProfile)
        }

    val webConfigMode: AddonWebConfigMode
        get() = AddonManagementAccess.webConfigMode(profileManager.activeProfile)

    private var server: AddonConfigServer? = null
    // Read by the NanoHTTPD config-server threads (see startQrMode's providers);
    // @Volatile publishes whole-value swaps so server threads never see a torn
    // or stale-forever snapshot.
    @Volatile private var logoBytes: ByteArray? = null
    @Volatile private var homeCatalogOrderKeys: List<String> = emptyList()
    @Volatile private var disabledHomeCatalogKeys: Set<String> = emptySet()
    @Volatile private var followAddonsOrderEnabled: Boolean = false
    @Volatile private var currentCollections: List<Collection> = emptyList()
    /**
     * Addon urls the phone page was last served. A proposal's removals are judged against
     * these, not the live list: an addon whose manifest was still loading when the page
     * loaded is absent from the page, and must not count as removed once it appears (QB2).
     */
    @Volatile private var servedAddonUrls: List<String> = emptyList()
    private var reloadJob: Job? = null

    /** An install or a Review certificate in progress (one at a time: they share the panel). */
    private var certificateJob: Job? = null

    /** The open certificate panel's answer: true = Trust. */
    private var certificateDecision: CompletableDeferred<Boolean>? = null

    init {
        observeInstalledAddons()
        observeSearchOnEnter()
        observeHealth()
        observeCertificateFailures()
        // Spec 68 P8: opening the Addons page forgets certificates no profile's addons use.
        viewModelScope.launch { certPinKeeper.prune() }
        observeCatalogPreferences()
        observeCollections()
        loadLogoBytes()
    }

    private fun observeHealth() {
        viewModelScope.launch {
            healthStore.levels.collect { levels ->
                _uiState.update { it.copy(healthByUrl = levels) }
            }
        }
    }

    private fun observeCertificateFailures() {
        viewModelScope.launch {
            hostGuard.certificateFailures.collect { keys ->
                _uiState.update { it.copy(certificateFailureKeys = keys) }
            }
        }
    }

    private fun observeSearchOnEnter() {
        viewModelScope.launch {
            addonRepository.getSearchOnSubmitUrls().collect { urls ->
                _uiState.update { it.copy(searchOnEnterKeys = urls.mapTo(HashSet(), ::addonUrlMatchKey)) }
            }
        }
    }

    fun requestAddonSyncNow() {
        startupSyncService.requestAddonSyncNow()
        // T5 (ysosrs 7bb4e446f): also force a manifest re-fetch of every installed
        // addon (bypassing the in-memory cache), so an addon that dropped out
        // because its manifest fetch failed reappears without a log-out / log-in.
        viewModelScope.launch { addonRepository.refreshAllManifests() }
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun onInstallUrlChange(url: String) {
        _uiState.update { it.copy(installUrl = url, error = null) }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null, transientMessageIsError = false) }
    }

    fun installAddon() {
        val rawUrl = uiState.value.installUrl.trim()
        if (rawUrl.isBlank()) {
            val message = context.getString(R.string.addon_error_invalid_url)
            _uiState.update {
                it.copy(
                    error = message,
                    transientMessage = message,
                    transientMessageIsError = true
                )
            }
            return
        }

        val normalizedUrl = normalizeAddonUrl(rawUrl)
        if (normalizedUrl == null) {
            val message = context.getString(R.string.addon_error_invalid_scheme)
            _uiState.update {
                it.copy(
                    error = message,
                    transientMessage = message,
                    transientMessageIsError = true
                )
            }
            return
        }

        if (certificateJob?.isActive == true) return
        certificateJob = viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, error = null, transientMessage = null) }
            try {
                // Spec 68 P8: a pin trusted during this attempt survives a prune until it ends.
                certPinKeeper.withInstallExemption(normalizedUrl) { install(normalizedUrl) }
            } finally {
                _uiState.update { it.copy(isInstalling = false) }
            }
        }
    }

    private suspend fun install(normalizedUrl: String) {
        var result = addonRepository.fetchAddon(normalizedUrl)
        if (result is NetworkResult.Error && result.certificate) {
            // Cancel, Back or a probe that found nothing to trust ends the attempt (its line already shown).
            if (!resolveCertificate(normalizedUrl, addonName = null)) return
            result = addonRepository.fetchAddon(normalizedUrl)
        }
        when (val outcome = result) {
            is NetworkResult.Success -> {
                addonRepository.addAddon(normalizedUrl)
                val addonName = outcome.data.displayName.ifBlank { outcome.data.baseUrl }
                _uiState.update {
                    it.copy(
                        installUrl = "",
                        transientMessage = context.getString(R.string.addon_install_success, addonName),
                        transientMessageIsError = false
                    )
                }
            }
            is NetworkResult.Error -> _uiState.update {
                it.copy(error = outcome.message, transientMessage = outcome.message, transientMessageIsError = true)
            }
            NetworkResult.Loading -> Unit
        }
    }

    /** Row action (stage 3): probes the addon's server and, when its certificate fails, opens the panel. */
    fun reviewCertificate(addon: Addon) {
        if (certificateJob?.isActive == true) return
        certificateJob = viewModelScope.launch {
            // Spec 68 D11 Q9: no line after Trust; the row clears when the addon answers.
            resolveCertificate(addon.baseUrl, addon.displayName, addonUrl = addon.baseUrl)
        }
    }

    /** The certificate panel's answer: Trust ([trust] true), or Cancel / Keep paused / Back. */
    fun onCertificateDecision(trust: Boolean) {
        certificateDecision?.complete(trust)
    }

    /**
     * Spec 68 P7: probes [url]'s server. An untrusted certificate opens the panel; true when the user
     * trusted it (pin stored, the server's breaker reset, its installed addons re-fetched). Any other
     * outcome shows its line and returns false.
     */
    private suspend fun resolveCertificate(url: String, addonName: String?, addonUrl: String? = null): Boolean {
        val label = addonServerLabel(url).orEmpty()
        return when (val probed = certProbe.probe(url)) {
            is AddonCertProbeResult.Untrusted -> {
                val trusted = askTrust(AddonCertificatePanelState(probed, addonName, addonUrl))
                if (trusted) certTrustAction.trust(probed)
                trusted
            }
            AddonCertProbeResult.VerifiesNow -> showCertificateLine(R.string.addon_cert_verifies_now, label, isError = false)
            AddonCertProbeResult.NoCertificate -> showCertificateLine(R.string.addon_cert_unreachable, label, isError = true)
            AddonCertProbeResult.Inconsistent -> showCertificateLine(R.string.addon_cert_inconsistent, label, isError = true)
        }
    }

    private fun showCertificateLine(@StringRes line: Int, label: String, isError: Boolean): Boolean {
        val message = context.getString(line, label)
        _uiState.update { it.copy(transientMessage = message, transientMessageIsError = isError) }
        return false
    }

    /** Opens [panel] and waits for the user; the panel closes however the wait ends. */
    private suspend fun askTrust(panel: AddonCertificatePanelState): Boolean {
        val decision = CompletableDeferred<Boolean>()
        certificateDecision = decision
        _uiState.update { it.copy(certificatePanel = panel) }
        return try {
            decision.await()
        } finally {
            certificateDecision = null
            _uiState.update { it.copy(certificatePanel = null) }
        }
    }

    // The canonical identity form: a '/' ending the query is addon config and survives (L8-19).
    private fun normalizeAddonUrl(input: String): String? =
        withAcceptedAddonScheme(input.trim())?.let(::canonicalizeAddonUrl)

    private fun normalizeUrlForComparison(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }

    fun removeAddon(baseUrl: String) {
        viewModelScope.launch {
            addonRepository.removeAddon(baseUrl)
        }
    }

    /**
     * Configure panel's Reload addon: re-fetches [addon]'s manifest (a changed one replaces the
     * cached copy, so the row updates) and reports the result as a transient message. A press
     * while a reload runs is ignored; a fetch past [ADDON_REQUEST_TIMEOUT_MS] counts as failed.
     */
    fun reloadAddon(addon: Addon) {
        if (reloadJob?.isActive == true) return
        reloadJob = viewModelScope.launch {
            val result = withTimeoutOrNull(ADDON_REQUEST_TIMEOUT_MS) { addonRepository.fetchAddon(addon.baseUrl) }
            val failed = result !is NetworkResult.Success
            val message = context.getString(
                if (failed) R.string.addon_reload_failed else R.string.addon_reload_done,
                addon.displayName
            )
            _uiState.update { it.copy(transientMessage = message, transientMessageIsError = failed) }
        }
    }

    /** Rename panel's Save: [name] blank or equal to the manifest name restores the addon's own name. */
    fun renameAddon(addon: Addon, name: String) {
        viewModelScope.launch {
            addonRepository.renameAddon(addon.baseUrl, name, manifestName = addon.name)
        }
    }

    fun setAddonEnabled(baseUrl: String, enabled: Boolean) {
        viewModelScope.launch {
            addonRepository.setAddonEnabled(baseUrl, enabled)
        }
    }

    /** Spec 40: [onEnter] = the addon's search catalogs run only when Search is pressed. */
    fun setSearchOnEnter(baseUrl: String, onEnter: Boolean) {
        viewModelScope.launch {
            addonRepository.setSearchOnSubmit(baseUrl, onEnter)
        }
    }

    fun moveAddonUp(baseUrl: String) {
        reorderAddon(baseUrl, -1)
    }

    fun moveAddonDown(baseUrl: String) {
        reorderAddon(baseUrl, 1)
    }

    // L5-68: the move is applied to the stored list, not this screen's snapshot, which can
    // lack addons whose manifest is still loading (they would be dropped and synced away).
    private fun reorderAddon(baseUrl: String, direction: Int) {
        viewModelScope.launch {
            addonRepository.moveAddon(baseUrl, direction)
        }
    }

    /** A failed start is a transient error message (shown on every host, read-only profiles included). */
    fun startQrMode(webConfigMode: AddonWebConfigMode = this.webConfigMode) {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            showQrStartError(R.string.error_network_required)
            return
        }

        stopServerInternal()
        servedAddonUrls = emptyList()

        server = AddonConfigServer.startOnAvailablePort(
            context = context,
            webConfigMode = webConfigMode,
            currentPageStateProvider = {
                val addons = _uiState.value.installedAddons
                servedAddonUrls = addons.map { it.baseUrl }
                // Single ordering engine (backlog G4-5): the QR page must render the
                // exact arrangement the TV Catalog Order screen shows for the same
                // persisted state, including follow-mode normalization.
                val addonEntries = HomeCatalogOrdering.buildAddonCatalogEntries(addons)
                val collectionEntries = HomeCatalogOrdering.buildCollectionEntries(currentCollections)
                val entryByKey = (addonEntries + collectionEntries).associateBy { it.key }
                val orderedKeys = HomeCatalogOrdering.effectiveOrder(
                    addonEntries = addonEntries,
                    collectionEntries = collectionEntries,
                    savedOrderKeys = homeCatalogOrderKeys,
                    followAddonsOrder = followAddonsOrderEnabled
                )
                val unifiedCatalogs = orderedKeys.mapNotNull { key ->
                    val entry = entryByKey[key] ?: return@mapNotNull null
                    CatalogInfo(
                        key = entry.key,
                        disableKey = entry.disableKey,
                        catalogName = entry.catalogName,
                        addonName = entry.addonName,
                        type = entry.typeLabel,
                        isDisabled = HomeCatalogOrdering.isDisabled(entry, disabledHomeCatalogKeys)
                    )
                }

                PageState(
                    addons = addons.map { addon ->
                        AddonInfo(
                            url = addon.baseUrl,
                            name = addon.displayName.ifBlank { addon.baseUrl },
                            description = addon.description
                        )
                    },
                    catalogs = unifiedCatalogs,
                    manifestAddonOrderKeys = addonEntries.map { it.key },
                    collections = collectionsToServerFormat(currentCollections),
                    disabledCollectionKeys = disabledHomeCatalogKeys
                        .filter { it.startsWith("collection_") },
                    followAddonsOrder = followAddonsOrderEnabled
                )
            },
            onChangeProposed = { change -> handleChangeProposed(change) },
            tmdbMetadataProvider = { request -> fetchTmdbSourceMetadata(request) },
            tmdbSearchProvider = { request -> searchTmdbSources(request) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            showQrStartError(R.string.error_server_ports_unavailable)
            return
        }

        val url = activeServer.url(ip)
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                error = null
            )
        }
    }

    private fun showQrStartError(@StringRes message: Int) {
        _uiState.update {
            it.copy(transientMessage = context.getString(message), transientMessageIsError = true)
        }
    }

    fun stopQrMode() {
        stopServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingChange = null
            )
        }
    }

    private suspend fun fetchAddonInfo(url: String): AddonInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val result = withTimeoutOrNull(15_000L) {
                    addonRepository.fetchAddon(url)
                } ?: return@withContext null

                when (result) {
                    is NetworkResult.Success -> AddonInfo(
                        url = result.data.baseUrl,
                        name = result.data.name.ifBlank { url },
                        description = result.data.description
                    )
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
    }

    /**
     * Bridges a suspend resolver call to the QR config-server's synchronous
     * handler thread (G6 consolidation, 2026-08-30: previously four inline
     * runBlocking+runCatching wrappers). Safe because these handlers run on
     * the server's own dispatch thread, never main; failures surface as
     * Result, so callers pick getOrNull/getOrElse per their fallback.
     */
    private fun <T> qrServerBlockingCall(block: suspend () -> T): Result<T> =
        runBlocking { runCatching { block() } }

    private fun fetchTmdbSourceMetadata(request: TmdbSourceMetadataRequest): TmdbSourceMetadataInfo? {
        val sourceType = runCatching { TmdbCollectionSourceType.valueOf(request.sourceType.uppercase()) }.getOrNull()
            ?: return null
        return qrServerBlockingCall {
            val metadata = when (sourceType) {
                TmdbCollectionSourceType.LIST -> tmdbCollectionSourceResolver.listImportMetadata(request.tmdbId)
                TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.collectionImportMetadata(request.tmdbId)
                TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.companyImportMetadata(request.tmdbId)
                TmdbCollectionSourceType.NETWORK -> tmdbCollectionSourceResolver.networkImportMetadata(request.tmdbId)
                TmdbCollectionSourceType.PERSON,
                TmdbCollectionSourceType.DIRECTOR -> tmdbCollectionSourceResolver.personImportMetadata(request.tmdbId)
                TmdbCollectionSourceType.DISCOVER -> return@qrServerBlockingCall null
            }
            TmdbSourceMetadataInfo(
                title = metadata.title,
                coverImageUrl = metadata.coverImageUrl
            )
        }.getOrNull()
    }

    private fun searchTmdbSources(request: TmdbSourceSearchRequest): List<TmdbSourceSearchResultInfo> {
        val sourceType = runCatching { TmdbCollectionSourceType.valueOf(request.sourceType.uppercase()) }.getOrNull()
            ?: return emptyList()
        return qrServerBlockingCall {
            when (sourceType) {
                TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.searchCompanies(request.query)
                    .map {
                        TmdbSourceSearchResultInfo(
                            id = it.id,
                            title = it.name ?: context.getString(com.nuvio.tv.R.string.web_tmdb_company_fallback, it.id),
                            subtitle = it.originCountry?.takeIf { value -> value.isNotBlank() }
                        )
                    }
                TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.searchCollections(request.query)
                    .map {
                        TmdbSourceSearchResultInfo(
                            id = it.id,
                            title = it.name ?: context.getString(com.nuvio.tv.R.string.web_tmdb_collection_fallback, it.id),
                            subtitle = it.overview?.takeIf { value -> value.isNotBlank() }
                        )
                    }
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun handleChangeProposed(change: PendingAddonChange) {
        val currentUrls = _uiState.value.installedAddons.map { normalizeUrlForComparison(it.baseUrl) }.toSet()
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()
        val currentCatalogEntries = HomeCatalogOrdering.buildAddonCatalogEntries(_uiState.value.installedAddons)
        val availableCatalogKeys = currentCatalogEntries.map { it.key }.toSet()
        val collectionKeysSet = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeysSet
        val availableDisableKeyToName = currentCatalogEntries.associate { entry ->
            entry.disableKey to "${entry.catalogName} • ${entry.addonName}"
        }

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentUrls }
        val removed = servedAddonUrls
            .filter { normalizeUrlForComparison(it) !in proposedNormalized }
        val resolvedProposedCatalogOrderKeys = if (change.proposedCatalogOrderKeys.isEmpty()) {
            currentCatalogEntries.map { it.key }
        } else {
            change.proposedCatalogOrderKeys
                .asSequence()
                .filter { it in allValidOrderKeys }
                .distinct()
                .toList()
        }
        val currentDisabledCatalogKeys = currentCatalogEntries
            .filter { HomeCatalogOrdering.isDisabled(it, disabledHomeCatalogKeys) }
            .map { it.disableKey }
            .toSet()
        val resolvedProposedDisabledCatalogKeys = if (change.proposedDisabledCatalogKeys.isEmpty()) {
            currentDisabledCatalogKeys.toList()
        } else {
            change.proposedDisabledCatalogKeys
                .asSequence()
                .filter { it in availableDisableKeyToName }
                .distinct()
                .toList()
        }
        val proposedDisabledSet = resolvedProposedDisabledCatalogKeys.toSet()
        val newlyDisabledCatalogs = (proposedDisabledSet - currentDisabledCatalogKeys)
            .mapNotNull { availableDisableKeyToName[it] }
        val newlyEnabledCatalogs = (currentDisabledCatalogKeys - proposedDisabledSet)
            .mapNotNull { availableDisableKeyToName[it] }
        // Baseline = the order currently in effect (saved-merged / follow-built), not
        // manifest order — the web client echoes the served list, so comparing against
        // the engine's effective order keeps the "catalogs reordered" confirm row honest.
        val currentEffectiveOrder = HomeCatalogOrdering.effectiveOrder(
            addonEntries = currentCatalogEntries,
            collectionEntries = HomeCatalogOrdering.buildCollectionEntries(currentCollections),
            savedOrderKeys = homeCatalogOrderKeys,
            followAddonsOrder = followAddonsOrderEnabled
        )
        val catalogsReordered = resolvedProposedCatalogOrderKeys != currentEffectiveOrder

        val removedNameMap = _uiState.value.installedAddons
            .associateBy({ normalizeUrlForComparison(it.baseUrl) }, { it.displayName })
        val removedNames = removed.associateWith { url ->
            removedNameMap[normalizeUrlForComparison(url)] ?: url
        }

        val proposedCollectionsJson = change.proposedCollectionsJson
        val collectionsChanged = proposedCollectionsJson != null
        val proposedCollectionCount = if (proposedCollectionsJson != null) {
            try { parseCollectionsFromJson(proposedCollectionsJson).size } catch (_: Exception) { 0 }
        } else 0
        val proposedDisabledCollectionKeys = change.proposedDisabledCollectionKeys

        _uiState.update {
            it.copy(
                pendingChange = PendingChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    proposedCatalogOrderKeys = resolvedProposedCatalogOrderKeys,
                    proposedDisabledCatalogKeys = resolvedProposedDisabledCatalogKeys,
                    addedUrls = added,
                    removedUrls = removed,
                    catalogsReordered = catalogsReordered,
                    disabledCatalogNames = newlyDisabledCatalogs,
                    enabledCatalogNames = newlyEnabledCatalogs,
                    removedNames = removedNames,
                    collectionsChanged = collectionsChanged,
                    proposedCollectionsJson = proposedCollectionsJson,
                    proposedCollectionCount = proposedCollectionCount,
                    proposedDisabledCollectionKeys = proposedDisabledCollectionKeys,
                    proposedFollowAddonsOrder = change.proposedFollowAddonsOrder
                )
            )
        }

        if (added.isNotEmpty()) {
            viewModelScope.launch {
                val addedNames = withContext(Dispatchers.IO) {
                    added.associateWith { url ->
                        fetchAddonInfo(url)?.name ?: url
                    }
                }
                _uiState.update { state ->
                    val pending = state.pendingChange
                    if (pending == null || pending.changeId != change.id) {
                        state
                    } else {
                        state.copy(
                            pendingChange = pending.copy(addedNames = addedNames)
                        )
                    }
                }
            }
        }
    }

    fun confirmPendingChange() {
        val pending = _uiState.value.pendingChange ?: return

        _uiState.update { it.copy(pendingChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            addonRepository.spliceAddonOrder(pending.proposedUrls, pending.removedUrls)
            applyCatalogPreferencesFromPending(pending, pending.proposedUrls)
            if (pending.collectionsChanged && pending.proposedCollectionsJson != null) {
                try {
                    val newCollections = parseCollectionsFromJson(pending.proposedCollectionsJson)
                    collectionsDataStore.setCollections(newCollections)
                    collectionSyncService.triggerPush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply collections from pending change", e)
                }
            }
            homeCatalogSettingsSyncService.triggerPush()
            // Apply follow addons order change
            if (pending.proposedFollowAddonsOrder != null) {
                layoutPreferenceDataStore.setFollowAddonsOrder(pending.proposedFollowAddonsOrder)
            }
            server?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingChange = null) }

            delay(2500)

            stopServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    fun rejectPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingChange = null) }
    }

    private suspend fun applyCatalogPreferencesFromPending(
        pending: PendingChangeInfo,
        validUrls: List<String>
    ) {
        val validUrlSet = validUrls.map { normalizeUrlForComparison(it) }.toSet()
        val targetAddons = _uiState.value.installedAddons.filter { addon ->
            addon.enabled && normalizeUrlForComparison(addon.baseUrl) in validUrlSet
        }
        val availableCatalogEntries = HomeCatalogOrdering.buildAddonCatalogEntries(targetAddons)
        val availableCatalogKeys = availableCatalogEntries.map { it.key }.toSet()
        val availableDisableKeys = availableCatalogEntries.map { it.disableKey }.toSet()
        // Collection keys are also valid in the ordering
        val collectionKeys = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeys

        val validCatalogOrder = pending.proposedCatalogOrderKeys
            .asSequence()
            .filter { it in allValidOrderKeys }
            .distinct()
            .toList()
        val validDisabledCatalogs = pending.proposedDisabledCatalogKeys
            .asSequence()
            .filter { it in availableDisableKeys }
            .distinct()
            .toList()

        layoutPreferenceDataStore.setHomeCatalogOrderKeys(validCatalogOrder)
        // One write (L5-09/30): addon-catalog keys from the proposal plus its collection
        // keys; collection keys are never addon disable keys, so the two never overlap.
        layoutPreferenceDataStore.setDisabledHomeCatalogKeys(
            validDisabledCatalogs + pending.proposedDisabledCollectionKeys
        )
    }

    private fun observeCatalogPreferences() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeCatalogOrderKeys.collect { keys ->
                homeCatalogOrderKeys = keys
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.disabledHomeCatalogKeys.collect { keys ->
                disabledHomeCatalogKeys = keys.toSet()
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.followAddonsOrder.collect { enabled ->
                followAddonsOrderEnabled = enabled
            }
        }
    }

    private fun observeCollections() {
        viewModelScope.launch {
            collectionsDataStore.collections.collect { cols ->
                currentCollections = cols
            }
        }
    }

    private fun collectionsToServerFormat(cols: List<Collection>): List<CollectionInfo> {
        return cols.map { col ->
            CollectionInfo(
                id = col.id,
                title = col.title,
                backdropImageUrl = col.backdropImageUrl,
                pinToTop = col.pinToTop,
                focusGlowEnabled = col.focusGlowEnabled,
                viewMode = col.viewMode.name,
                showAllTab = col.showAllTab,
                folders = col.folders.map { folder ->
                    FolderInfo(
                        id = folder.id,
                        title = folder.title,
                        coverImageUrl = folder.coverImageUrl,
                        focusGifUrl = folder.focusGifUrl,
                        focusGifEnabled = folder.focusGifEnabled,
                        coverEmoji = folder.coverEmoji,
                        tileShape = folder.tileShape.name,
                        hideTitle = folder.hideTitle,
                        heroBackdropUrl = folder.heroBackdropUrl,
                        heroVideoUrl = folder.heroVideoUrl,
                        titleLogoUrl = folder.titleLogoUrl,
                        catalogSources = folder.catalogSources.map { src ->
                            CatalogSourceInfo(
                                addonId = src.addonId,
                                type = src.type,
                                catalogId = src.catalogId,
                                genre = src.genre
                            )
                        },
                        sources = folder.sources.map { source ->
                            when (source) {
                                is AddonCatalogCollectionSource -> CollectionSourceInfo(
                                    provider = "addon",
                                    addonId = source.addonId,
                                    type = source.type,
                                    catalogId = source.catalogId,
                                    genre = source.genre
                                )
                                is TmdbCollectionSource -> CollectionSourceInfo(
                                    provider = "tmdb",
                                    tmdbSourceType = source.sourceType.name,
                                    title = source.title,
                                    tmdbId = source.tmdbId,
                                    mediaType = source.mediaType.name,
                                    sortBy = source.sortBy,
                                    filters = TmdbFiltersInfo(
                                        withGenres = source.filters.withGenres,
                                        releaseDateGte = source.filters.releaseDateGte,
                                        releaseDateLte = source.filters.releaseDateLte,
                                        voteAverageGte = source.filters.voteAverageGte,
                                        voteAverageLte = source.filters.voteAverageLte,
                                        voteCountGte = source.filters.voteCountGte,
                                        withOriginalLanguage = source.filters.withOriginalLanguage,
                                        withOriginCountry = source.filters.withOriginCountry,
                                        withKeywords = source.filters.withKeywords,
                                        withCompanies = source.filters.withCompanies,
                                        withNetworks = source.filters.withNetworks,
                                        year = source.filters.year,
                                        watchRegion = source.filters.watchRegion,
                                        withWatchProviders = source.filters.withWatchProviders
                                    )
                                )
                                is TraktCollectionSource -> CollectionSourceInfo(
                                    provider = "trakt",
                                    title = source.title,
                                    traktListId = source.traktListId,
                                    mediaType = source.mediaType.name,
                                    sortBy = source.sortBy,
                                    sortHow = source.sortHow
                                )
                            }
                        }
                    )
                }
            )
        }
    }

    private fun parseCollectionsFromJson(json: String): List<Collection> {
        return collectionsDataStore.importFromJson(json)
    }

    private fun observeInstalledAddons() {
        viewModelScope.launch {
            if (_uiState.value.installedAddons.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            addonRepository.getInstalledAddons()
                .catch { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
                .collect { addons ->
                    _uiState.update { state ->
                        state.copy(
                            installedAddons = addons,
                            isLoading = false,
                            error = null
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopServerInternal()
    }
}
