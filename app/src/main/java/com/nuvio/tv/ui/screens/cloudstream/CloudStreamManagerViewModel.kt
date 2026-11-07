package com.nuvio.tv.ui.screens.cloudstream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.cloudstream.AddCloudStreamRepositoryResult
import com.nuvio.tv.core.cloudstream.CloudStreamInstallResult
import com.nuvio.tv.core.cloudstream.CloudStreamPluginItem
import com.nuvio.tv.core.cloudstream.CloudStreamRepository
import com.nuvio.tv.core.cloudstream.CloudStreamRepositoryItem
import com.nuvio.tv.core.cloudstream.CloudStreamUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin ViewModel over [CloudStreamRepository]: the repository owns the registry
 * state; this layer owns the screen's transient input (repo URL field, busy
 * flags, one-shot messages) and launches repository work on viewModelScope.
 * Repository calls that are already fire-and-forget are called directly.
 */
@HiltViewModel
class CloudStreamManagerViewModel @Inject constructor() : ViewModel() {
    val uiState: StateFlow<CloudStreamUiState> = CloudStreamRepository.uiState

    private val _repoUrl = MutableStateFlow("")
    val repoUrl: StateFlow<String> = _repoUrl

    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage

    private val _isAddingRepository = MutableStateFlow(false)
    val isAddingRepository: StateFlow<Boolean> = _isAddingRepository

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _busyPluginIds = MutableStateFlow<Set<String>>(emptySet())
    val busyPluginIds: StateFlow<Set<String>> = _busyPluginIds

    fun onRepoUrlChange(value: String) {
        _repoUrl.value = value
    }

    fun clearTransientMessage() {
        _transientMessage.value = null
    }

    fun acceptSecurityWarning() {
        CloudStreamRepository.acceptSecurityWarning()
    }

    fun addRepository(url: String = _repoUrl.value.trim()) {
        if (url.isBlank() || _isAddingRepository.value) return
        viewModelScope.launch {
            _isAddingRepository.value = true
            when (val result = CloudStreamRepository.addRepository(url)) {
                is AddCloudStreamRepositoryResult.Success -> {
                    if (url == _repoUrl.value.trim()) _repoUrl.value = ""
                }
                is AddCloudStreamRepositoryResult.Error -> _transientMessage.value = result.message
            }
            _isAddingRepository.value = false
        }
    }

    /** Discovers community repos from the cs-repos database (MegaRepo). */
    fun discoverRepositories() {
        if (_isDiscovering.value) return
        viewModelScope.launch {
            _isDiscovering.value = true
            CloudStreamRepository.discoverRepositories("MegaRepo")
                .onFailure { _transientMessage.value = it.message ?: "Discovery failed" }
            _isDiscovering.value = false
        }
    }

    fun refreshAll() {
        // Fire-and-forget: per-repo progress shows through uiState.isRefreshing.
        CloudStreamRepository.refreshAll()
    }

    fun refreshRepository(repo: CloudStreamRepositoryItem) {
        CloudStreamRepository.refreshRepository(repo.manifest.sourceUrl)
    }

    fun setRepositoryEnabled(repo: CloudStreamRepositoryItem, enabled: Boolean) {
        CloudStreamRepository.setRepositoryEnabled(repo.manifest.sourceUrl, enabled)
    }

    fun removeRepository(repo: CloudStreamRepositoryItem) {
        CloudStreamRepository.removeRepository(repo.manifest.sourceUrl)
    }

    fun installPlugin(plugin: CloudStreamPluginItem) {
        runPluginJob(plugin) { CloudStreamRepository.installPlugin(plugin.metadata.id.value) }
    }

    fun updatePlugin(plugin: CloudStreamPluginItem) {
        runPluginJob(plugin) { CloudStreamRepository.updatePlugin(plugin.metadata.id.value) }
    }

    fun setPluginEnabled(plugin: CloudStreamPluginItem, enabled: Boolean) {
        CloudStreamRepository.setPluginEnabled(plugin.metadata.id.value, enabled)
    }

    fun removePlugin(plugin: CloudStreamPluginItem) {
        CloudStreamRepository.removePlugin(plugin.metadata.id.value)
    }

    private fun runPluginJob(
        plugin: CloudStreamPluginItem,
        job: suspend () -> CloudStreamInstallResult,
    ) {
        val id = plugin.metadata.id.value
        if (id in _busyPluginIds.value) return
        viewModelScope.launch {
            _busyPluginIds.value = _busyPluginIds.value + id
            when (val result = job()) {
                is CloudStreamInstallResult.Success -> Unit
                is CloudStreamInstallResult.Error -> _transientMessage.value = result.message
            }
            _busyPluginIds.value = _busyPluginIds.value - id
        }
    }
}
