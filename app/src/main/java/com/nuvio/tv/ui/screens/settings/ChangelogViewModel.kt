package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.update.ChangelogLoad
import com.nuvio.tv.core.update.ChangelogRelease
import com.nuvio.tv.core.update.ChangelogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the changelog list shows (Spec 80 A2, A5). */
sealed interface ChangelogListState {
    data object Loading : ChangelogListState
    data object Failed : ChangelogListState
    data class Loaded(val releases: List<ChangelogRelease>) : ChangelogListState
}

/**
 * Spec 80 in Settings: About's changelog list (in About's pane) and one release's page (over the
 * whole Settings screen). One instance per Settings back-stack entry, so SettingsScreen (the page)
 * and About (the list) share it, and the page is still open when the player (its video) returns.
 */
@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val repository: ChangelogRepository
) : ViewModel() {

    private val _listOpen = MutableStateFlow(false)
    val listOpen: StateFlow<Boolean> = _listOpen.asStateFlow()

    private val _list = MutableStateFlow<ChangelogListState>(ChangelogListState.Loading)
    val list: StateFlow<ChangelogListState> = _list.asStateFlow()

    private val _page = MutableStateFlow<ChangelogRelease?>(null)

    /** The release whose page is open; null = none. */
    val page: StateFlow<ChangelogRelease?> = _page.asStateFlow()

    private val _pageForUpdate = MutableStateFlow(false)

    /** The open page is the update's, opened from the update panel: it carries the download card (Spec 80 B2). */
    val pageForUpdate: StateFlow<Boolean> = _pageForUpdate.asStateFlow()

    private var loading: Job? = null

    /**
     * Set just before the release video's Player route is pushed: Settings leaving the screen for it
     * keeps a running update download (Spec 80 D14); leaving for anything else stops it.
     */
    var playingVideo = false

    /** The release whose page was opened last: its list row takes focus when the list shows again. */
    var lastOpened: ChangelogRelease? = null
        private set

    /** The release whose page opens once the list loads (What's new's Read notes); null = none. */
    private var pendingPage: Int? = null

    /**
     * About's Changelog row: the list opens and loads afresh (D4: fetched each time it opens).
     * [openRelease] (a versionCode, the What's new card's Read notes) opens that release's page once
     * the list has it; a list without it, or one that fails, stays on the list.
     */
    fun openList(openRelease: Int? = null) {
        pendingPage = openRelease
        _listOpen.value = true
        load()
    }

    /** Back from the list, or Settings leaving About. */
    fun closeList() {
        lastOpened = null
        pendingPage = null
        _listOpen.value = false
        loading?.cancel()
    }

    /** Try again after a failed load. */
    fun retry() = load()

    fun openPage(release: ChangelogRelease, forUpdate: Boolean = false) {
        if (!forUpdate) lastOpened = release
        _pageForUpdate.value = forUpdate
        _page.value = release
    }

    /** Back from the page: the list shows again. */
    fun closePage() {
        _page.value = null
        _pageForUpdate.value = false
    }

    private fun load() {
        loading?.cancel()
        _list.value = ChangelogListState.Loading
        loading = viewModelScope.launch {
            val load = repository.load()
            _list.value = when (load) {
                is ChangelogLoad.Loaded -> ChangelogListState.Loaded(load.releases)
                ChangelogLoad.Failed -> ChangelogListState.Failed
            }
            val wanted = pendingPage ?: return@launch
            pendingPage = null
            (load as? ChangelogLoad.Loaded)?.releases?.firstOrNull { it.versionCode == wanted }?.let { openPage(it) }
        }
    }
}
