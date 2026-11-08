package com.nuvio.tv.ui.screens.cloudstream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.cloudstream.CloudStreamEpisode
import com.nuvio.tv.core.cloudstream.CloudStreamPluginItem
import com.nuvio.tv.core.cloudstream.CloudStreamRepository
import com.nuvio.tv.core.cloudstream.CloudStreamSearchItem
import com.nuvio.tv.core.cloudstream.CloudStreamStorage
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One provider catalog row on Home: a main-page section title and its posters. */
data class CloudStreamHomeSection(
    val title: String,
    val items: List<CloudStreamSearchItem>,
)

/** A Home card the user tapped, resolved to a Stream-screen play target. */
data class CloudStreamHomePlayTarget(
    val videoId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String?,
    val season: Int?,
    val episode: Int?,
)

/** A series card whose episode the user still has to pick. */
data class CloudStreamHomeEpisodePick(
    val item: CloudStreamSearchItem,
    val episodes: List<CloudStreamEpisode>,
)

data class CloudStreamHomeUiState(
    val providers: List<CloudStreamPluginItem> = emptyList(),
    val selectedProviderId: String? = null,
    val sections: List<CloudStreamHomeSection> = emptyList(),
    val isLoading: Boolean = false,
) {
    val selectedProvider: CloudStreamPluginItem?
        get() = providers.firstOrNull { it.metadata.id.value == selectedProviderId }
}

/**
 * Home's CloudStream section: the runnable providers, the selected provider's
 * main-page sections, and the card-click flow (movie/live → Stream screen at once;
 * series → episode picker first). The selected provider id persists in the
 * `nuvio_cloudstream` prefs so the same provider is shown next launch.
 *
 * Hidden entirely when no provider is runnable: the section only exists for users
 * who installed CS3 plugins.
 */
@HiltViewModel
class CloudStreamHomeViewModel @Inject constructor(
    private val streamSource: CloudStreamStreamSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CloudStreamHomeUiState())
    val uiState: StateFlow<CloudStreamHomeUiState> = _uiState.asStateFlow()

    private val _showProviderPicker = MutableStateFlow(false)
    val showProviderPicker: StateFlow<Boolean> = _showProviderPicker.asStateFlow()

    private val _pendingEpisodes = MutableStateFlow<CloudStreamHomeEpisodePick?>(null)
    val pendingEpisodes: StateFlow<CloudStreamHomeEpisodePick?> = _pendingEpisodes.asStateFlow()

    private val _pendingPlay = MutableStateFlow<CloudStreamHomePlayTarget?>(null)
    val pendingPlay: StateFlow<CloudStreamHomePlayTarget?> = _pendingPlay.asStateFlow()

    // A card tap whose load() failed: the reason, shown once as a toast on Home.
    // Without this a failed tap did nothing at all and looked like the card
    // just ignored the press.
    private val _tapError = MutableStateFlow<String?>(null)
    val tapError: StateFlow<String?> = _tapError.asStateFlow()

    private var lastRunnableIds: List<String> = emptyList()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            CloudStreamRepository.uiState.collect { state ->
                val runnable = state.plugins.filter { it.isRunnable }
                val runnableIds = runnable.map { it.metadata.id.value }
                if (runnableIds == lastRunnableIds) return@collect
                lastRunnableIds = runnableIds
                val savedId = CloudStreamStorage.loadHomeProviderId()
                val selectedId = savedId?.takeIf { it in runnableIds }
                    ?: runnableIds.firstOrNull()
                if (selectedId != savedId) {
                    CloudStreamStorage.saveHomeProviderId(selectedId)
                }
                _uiState.value = _uiState.value.copy(
                    providers = runnable,
                    selectedProviderId = selectedId,
                )
                if (selectedId != null) loadSections(selectedId) else {
                    _uiState.value = _uiState.value.copy(sections = emptyList(), isLoading = false)
                }
            }
        }
    }

    fun showProviderPicker() {
        if (_uiState.value.providers.isNotEmpty()) _showProviderPicker.value = true
    }

    fun dismissProviderPicker() {
        _showProviderPicker.value = false
    }

    fun selectProvider(providerId: String) {
        _showProviderPicker.value = false
        if (providerId == _uiState.value.selectedProviderId) return
        CloudStreamStorage.saveHomeProviderId(providerId)
        _uiState.value = _uiState.value.copy(selectedProviderId = providerId)
        loadSections(providerId)
    }

    fun refreshSections() {
        _uiState.value.selectedProviderId?.let { loadSections(it) }
    }

    private fun loadSections(providerId: String) {
        // A provider switch while a load is in flight cancels the stale load;
        // the new one always runs.
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadJob = viewModelScope.launch {
            try {
                val sections = CloudStreamRepository.getMainPage(providerId, page = 1)
                    .getOrNull()
                    ?.mapNotNull { (title, items) ->
                        val visible = items.filter { it.posterUrl?.isNotBlank() == true }
                        if (visible.isEmpty()) null else CloudStreamHomeSection(title, visible)
                    }
                    .orEmpty()
                // The provider may have been uninstalled/switched while loading.
                if (providerId == _uiState.value.selectedProviderId) {
                    _uiState.value = _uiState.value.copy(
                        sections = sections,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                // Never swallow cancellation: a provider switch cancels this load.
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (providerId == _uiState.value.selectedProviderId) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    /** A Home CS3 card was clicked: resolve it to a play target or an episode pick. */
    fun onCardClick(item: CloudStreamSearchItem) {
        viewModelScope.launch {
            val result = CloudStreamRepository.load(item.providerId, item.data)
            val loaded = result.getOrNull()
            if (loaded == null) {
                val reason = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "could not be opened"
                _tapError.value = "${item.name}: $reason"
                return@launch
            }
            val contentType = item.type.tvTypeString
            val episodes = loaded.episodes
            if (contentType == "series" && episodes.isNotEmpty()) {
                _pendingEpisodes.value = CloudStreamHomeEpisodePick(item, episodes)
            } else {
                _pendingPlay.value = playTarget(item, loaded.data, season = null, episode = null)
            }
        }
    }

    fun onEpisodeSelected(episode: CloudStreamEpisode) {
        val pending = _pendingEpisodes.value ?: return
        _pendingEpisodes.value = null
        _pendingPlay.value = playTarget(
            pending.item,
            episode.data,
            season = episode.season,
            episode = episode.episode,
        )
    }

    fun dismissEpisodePicker() {
        _pendingEpisodes.value = null
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }

    fun consumeTapError() {
        _tapError.value = null
    }

    private fun playTarget(
        item: CloudStreamSearchItem,
        data: String,
        season: Int?,
        episode: Int?,
    ) = CloudStreamHomePlayTarget(
        videoId = streamSource.cs3VideoId(item.providerId, data),
        contentType = item.type.tvTypeString,
        title = item.name,
        posterUrl = item.posterUrl,
        season = season,
        episode = episode,
    )
}
