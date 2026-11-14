package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.StreamBadgeConfigServer
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LayoutSettingsUiState(
    /**
     * True once every bound preference and the stream badge settings have emitted. Until then
     * the defaults below are placeholders, so the pane composes no rows: a toggle on a placeholder
     * would flash the wrong value or be dropped by the setters' equality check. The two
     * StateFlow-backed preferences count their stateIn seed; their singleton store is warm long
     * before Settings.
     */
    val loaded: Boolean = false,
    val modernLandscapePostersEnabled: Boolean = false,
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val blurUnwatchedEpisodes: Boolean = false,
    val homeImdbRatingsVisibility: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL,
    val detailImdbRatingsVisibility: DetailImdbRatingsVisibility = DetailImdbRatingsVisibility.SHOW_ALL,
    val blurContinueWatchingNextUp: Boolean = false,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val detailPageTrailerButtonEnabled: Boolean = true,
    val detailPageTrailerAutoplayEnabled: Boolean = false,
    val detailPageTrailerAutoplayDelaySeconds: Int = 7,
    val imdbTrailersEnabled: Boolean = false,
    val preferExternalMetaAddonDetail: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val showFullReleaseDate: Boolean = true,
    val nextUpFromFurthestEpisode: Boolean = true,
    val showUnairedNextUp: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
)

sealed class LayoutSettingsEvent {
    data class SetModernLandscapePostersEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDiscoverLocation(val location: DiscoverLocation) : LayoutSettingsEvent()
    data class SetCatalogTypeSuffixEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerMuted(val muted: Boolean) : LayoutSettingsEvent()
    data class SetBlurUnwatchedEpisodes(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetHomeImdbRatingsVisibility(val visibility: HomeImdbRatingsVisibility) : LayoutSettingsEvent()
    data class SetDetailImdbRatingsVisibility(val visibility: DetailImdbRatingsVisibility) : LayoutSettingsEvent()
    data class SetBlurContinueWatchingNextUp(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetUseEpisodeThumbnailsInCw(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerButtonEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerAutoplayEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerAutoplayDelaySeconds(val seconds: Int) : LayoutSettingsEvent()
    data class SetImdbTrailersEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetPreferExternalMetaAddonDetail(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetHideUnreleasedContent(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetShowFullReleaseDate(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetNextUpFromFurthestEpisode(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetShowUnairedNextUp(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetContinueWatchingEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetContinueWatchingSortMode(val mode: ContinueWatchingSortMode) : LayoutSettingsEvent()
    data class SetContinueWatchingCardStyle(val style: ContinueWatchingCardStyle) : LayoutSettingsEvent()
}

@HiltViewModel
class LayoutSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val homeCatalogSettingsSyncService: com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(LayoutSettingsUiState())
    val uiState: StateFlow<LayoutSettingsUiState> = _uiState.asStateFlow()
    private var streamBadgeServer: StreamBadgeConfigServer? = null
    private var logoBytes: ByteArray? = null

    /**
     * Bindings still waiting for their first value, plus one token init holds until every
     * binding is registered (Main.immediate can deliver a StateFlow's first value inside bindTo).
     * Main thread only, like the collectors.
     */
    private var pendingBindings = 1

    private val _streamBadgeUiState = MutableStateFlow(StreamBadgeSettingsUiState())
    val streamBadgeUiState: StateFlow<StreamBadgeSettingsUiState> = _streamBadgeUiState.asStateFlow()

    private inline fun updateUiStateIfChanged(
        update: (LayoutSettingsUiState) -> LayoutSettingsUiState
    ) {
        _uiState.update { current ->
            val next = update(current)
            if (next == current) current else next
        }
    }

    init {
        loadLogoBytes()
        streamBadgeSettingsDataStore.settings.collectCountingFirst { settings ->
            _streamBadgeUiState.update { it.copy(settings = settings) }
        }
        layoutPreferenceDataStore.modernLandscapePostersEnabled.bindTo { copy(modernLandscapePostersEnabled = it) }
        layoutPreferenceDataStore.discoverLocation.bindTo { copy(discoverLocation = it) }
        layoutPreferenceDataStore.catalogTypeSuffixEnabled.bindTo { copy(catalogTypeSuffixEnabled = it) }
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled.bindTo { copy(focusedPosterBackdropTrailerEnabled = it) }
        layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted.bindTo { copy(focusedPosterBackdropTrailerMuted = it) }
        layoutPreferenceDataStore.blurUnwatchedEpisodes.bindTo { copy(blurUnwatchedEpisodes = it) }
        layoutPreferenceDataStore.homeImdbRatingsVisibility.bindTo { copy(homeImdbRatingsVisibility = it) }
        layoutPreferenceDataStore.detailImdbRatingsVisibility.bindTo { copy(detailImdbRatingsVisibility = it) }
        layoutPreferenceDataStore.blurContinueWatchingNextUp.bindTo { copy(blurContinueWatchingNextUp = it) }
        layoutPreferenceDataStore.useEpisodeThumbnailsInCw.bindTo { copy(useEpisodeThumbnailsInCw = it) }
        layoutPreferenceDataStore.detailPageTrailerButtonEnabled.bindTo { copy(detailPageTrailerButtonEnabled = it) }
        trailerSettingsDataStore.settings.bindTo { settings ->
            copy(
                detailPageTrailerAutoplayEnabled = settings.enabled,
                detailPageTrailerAutoplayDelaySeconds = settings.delaySeconds,
                imdbTrailersEnabled = settings.source == TrailerSource.IMDB
            )
        }
        layoutPreferenceDataStore.preferExternalMetaAddonDetail.bindTo { copy(preferExternalMetaAddonDetail = it) }
        layoutPreferenceDataStore.hideUnreleasedContent.bindTo { copy(hideUnreleasedContent = it) }
        layoutPreferenceDataStore.showFullReleaseDate.bindTo { copy(showFullReleaseDate = it) }
        layoutPreferenceDataStore.nextUpFromFurthestEpisode.bindTo { copy(nextUpFromFurthestEpisode = it) }
        layoutPreferenceDataStore.showUnairedNextUp.bindTo { copy(showUnairedNextUp = it) }
        layoutPreferenceDataStore.continueWatchingEnabled.bindTo { copy(continueWatchingEnabled = it) }
        layoutPreferenceDataStore.continueWatchingSortMode.bindTo { copy(continueWatchingSortMode = it) }
        layoutPreferenceDataStore.continueWatchingCardStyle.bindTo { copy(continueWatchingCardStyle = it) }
        releaseBinding()
    }

    private fun releaseBinding() {
        pendingBindings--
        if (pendingBindings == 0) updateUiStateIfChanged { it.copy(loaded = true) }
    }

    /**
     * One preference flow -> one UiState field (L5-43). distinctUntilChanged is a no-op on
     * the StateFlow-backed prefs (preferExternalMetaAddonDetail, nextUpFromFurthestEpisode).
     */
    private fun <T> Flow<T>.bindTo(reduce: LayoutSettingsUiState.(T) -> LayoutSettingsUiState) {
        distinctUntilChanged().collectCountingFirst { value -> updateUiStateIfChanged { it.reduce(value) } }
    }

    /** Collects in viewModelScope; the first value releases one pending binding (see [releaseBinding]). */
    private fun <T> Flow<T>.collectCountingFirst(onEach: (T) -> Unit) {
        pendingBindings++
        var first = true
        viewModelScope.launch {
            this@collectCountingFirst.collectLatest { value ->
                onEach(value)
                if (first) {
                    first = false
                    releaseBinding()
                }
            }
        }
    }

    fun onEvent(event: LayoutSettingsEvent) {
        when (event) {
            is LayoutSettingsEvent.SetModernLandscapePostersEnabled -> setModernLandscapePostersEnabled(event.enabled)
            is LayoutSettingsEvent.SetDiscoverLocation -> setDiscoverLocation(event.location)
            is LayoutSettingsEvent.SetCatalogTypeSuffixEnabled -> setCatalogTypeSuffixEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled -> setFocusedPosterBackdropTrailerEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted -> setFocusedPosterBackdropTrailerMuted(event.muted)
            is LayoutSettingsEvent.SetBlurUnwatchedEpisodes -> setBlurUnwatchedEpisodes(event.enabled)
            is LayoutSettingsEvent.SetHomeImdbRatingsVisibility -> setHomeImdbRatingsVisibility(event.visibility)
            is LayoutSettingsEvent.SetDetailImdbRatingsVisibility -> setDetailImdbRatingsVisibility(event.visibility)
            is LayoutSettingsEvent.SetBlurContinueWatchingNextUp -> setBlurContinueWatchingNextUp(event.enabled)
            is LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw -> setUseEpisodeThumbnailsInCw(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled -> setDetailPageTrailerButtonEnabled(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled -> setDetailPageTrailerAutoplayEnabled(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds -> setDetailPageTrailerAutoplayDelaySeconds(event.seconds)
            is LayoutSettingsEvent.SetImdbTrailersEnabled -> setImdbTrailersEnabled(event.enabled)
            is LayoutSettingsEvent.SetPreferExternalMetaAddonDetail -> setPreferExternalMetaAddonDetail(event.enabled)
            is LayoutSettingsEvent.SetHideUnreleasedContent -> setHideUnreleasedContent(event.enabled)
            is LayoutSettingsEvent.SetShowFullReleaseDate -> setShowFullReleaseDate(event.enabled)
            is LayoutSettingsEvent.SetNextUpFromFurthestEpisode -> setNextUpFromFurthestEpisode(event.enabled)
            is LayoutSettingsEvent.SetShowUnairedNextUp -> setShowUnairedNextUp(event.enabled)
            is LayoutSettingsEvent.SetContinueWatchingEnabled -> setContinueWatchingEnabled(event.enabled)
            is LayoutSettingsEvent.SetContinueWatchingSortMode -> setContinueWatchingSortMode(event.mode)
            is LayoutSettingsEvent.SetContinueWatchingCardStyle -> setContinueWatchingCardStyle(event.style)
        }
    }

    fun startStreamBadgeQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _streamBadgeUiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopStreamBadgeServer()
        streamBadgeServer = StreamBadgeConfigServer.startOnAvailablePort(
            currentRulesProvider = { _streamBadgeUiState.value.rules },
            onRulesChanged = { rules ->
                _streamBadgeUiState.update { it.copy(settings = it.settings.copy(rules = rules)) }
                viewModelScope.launch { streamBadgeSettingsDataStore.setStreamBadgeRules(rules) }
            },
            context = context,
            logoProvider = { logoBytes }
        )
        val server = streamBadgeServer
        if (server == null) {
            _streamBadgeUiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = server.url(ip)
        _streamBadgeUiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = QrCodeGenerator.generate(url, 512),
                serverUrl = url,
                serverError = null
            )
        }
    }

    /** The pane shows [StreamBadgeSettingsUiState.serverError] once, then clears it. */
    fun clearStreamBadgeError() {
        _streamBadgeUiState.update { it.copy(serverError = null) }
    }

    fun stopStreamBadgeQrMode() {
        stopStreamBadgeServer()
        _streamBadgeUiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null
            )
        }
    }

    fun setShowFileSizeBadges(enabled: Boolean) {
        viewModelScope.launch { streamBadgeSettingsDataStore.setShowFileSizeBadges(enabled) }
    }

    fun setUseAddonFormatting(enabled: Boolean) {
        viewModelScope.launch { streamBadgeSettingsDataStore.setUseAddonFormatting(enabled) }
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) {
        }
    }

    private fun stopStreamBadgeServer() {
        streamBadgeServer?.stop()
        streamBadgeServer = null
    }

    private fun setModernLandscapePostersEnabled(enabled: Boolean) {
        if (_uiState.value.modernLandscapePostersEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernLandscapePostersEnabled(enabled)
        }
    }

    private fun setDiscoverLocation(location: DiscoverLocation) {
        if (_uiState.value.discoverLocation == location) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setDiscoverLocation(location)
        }
    }

    private fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        if (_uiState.value.catalogTypeSuffixEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCatalogTypeSuffixEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        if (_uiState.value.focusedPosterBackdropTrailerEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        if (_uiState.value.focusedPosterBackdropTrailerMuted == muted) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerMuted(muted)
        }
    }

    private fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        if (_uiState.value.detailPageTrailerButtonEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setDetailPageTrailerButtonEnabled(enabled)
        }
    }

    private fun setDetailPageTrailerAutoplayEnabled(enabled: Boolean) {
        if (_uiState.value.detailPageTrailerAutoplayEnabled == enabled) return
        viewModelScope.launch {
            trailerSettingsDataStore.setEnabled(enabled)
        }
    }

    private fun setDetailPageTrailerAutoplayDelaySeconds(seconds: Int) {
        if (_uiState.value.detailPageTrailerAutoplayDelaySeconds == seconds) return
        viewModelScope.launch {
            trailerSettingsDataStore.setDelaySeconds(seconds)
        }
    }

    private fun setImdbTrailersEnabled(enabled: Boolean) {
        if (_uiState.value.imdbTrailersEnabled == enabled) return
        viewModelScope.launch {
            trailerSettingsDataStore.setSource(if (enabled) TrailerSource.IMDB else TrailerSource.YOUTUBE)
        }
    }

    private fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        if (_uiState.value.blurUnwatchedEpisodes == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setBlurUnwatchedEpisodes(enabled)
        }
    }

    private fun setHomeImdbRatingsVisibility(visibility: HomeImdbRatingsVisibility) {
        if (_uiState.value.homeImdbRatingsVisibility == visibility) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeImdbRatingsVisibility(visibility)
        }
    }

    private fun setDetailImdbRatingsVisibility(visibility: DetailImdbRatingsVisibility) {
        if (_uiState.value.detailImdbRatingsVisibility == visibility) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setDetailImdbRatingsVisibility(visibility)
        }
    }

    private fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        if (_uiState.value.blurContinueWatchingNextUp == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setBlurContinueWatchingNextUp(enabled)
        }
    }

    private fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        if (_uiState.value.useEpisodeThumbnailsInCw == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setUseEpisodeThumbnailsInCw(enabled)
        }
    }

    private fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        if (_uiState.value.preferExternalMetaAddonDetail == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setPreferExternalMetaAddonDetail(enabled)
            metaRepository.clearCache()
        }
    }

    private fun setHideUnreleasedContent(enabled: Boolean) {
        if (_uiState.value.hideUnreleasedContent == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setHideUnreleasedContent(enabled)
            // Spec 36/S9 (B5-2): the flag rides the home-catalog sync blob; without a push the
            // next pull writes the remote's old value back over the toggle.
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun setShowFullReleaseDate(enabled: Boolean) {
        if (_uiState.value.showFullReleaseDate == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowFullReleaseDate(enabled)
        }
    }

    private fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        if (_uiState.value.nextUpFromFurthestEpisode == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setNextUpFromFurthestEpisode(enabled)
        }
    }

    private fun setShowUnairedNextUp(enabled: Boolean) {
        if (_uiState.value.showUnairedNextUp == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowUnairedNextUp(enabled)
        }
    }

    private fun setContinueWatchingEnabled(enabled: Boolean) {
        if (_uiState.value.continueWatchingEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setContinueWatchingEnabled(enabled)
        }
    }

    private fun setContinueWatchingCardStyle(style: ContinueWatchingCardStyle) {
        if (_uiState.value.continueWatchingCardStyle == style) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setContinueWatchingCardStyle(style)
        }
    }

    private fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        if (_uiState.value.continueWatchingSortMode == mode) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setContinueWatchingSortMode(mode)
        }
    }

    override fun onCleared() {
        stopStreamBadgeServer()
        super.onCleared()
    }
}

data class StreamBadgeSettingsUiState(
    val settings: StreamBadgeSettings = StreamBadgeSettings(),
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val serverError: String? = null
) {
    val rules: StreamBadgeRules
        get() = settings.rules

    val showFileSizeBadges: Boolean
        get() = settings.showFileSizeBadges

    val useAddonFormatting: Boolean
        get() = settings.useAddonFormatting
}
