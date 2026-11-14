package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.update.DownloadState
import com.nuvio.tv.core.update.KnownRelease
import com.nuvio.tv.core.update.UpdateController
import com.nuvio.tv.core.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Spec 52 T2 in Settings: About's Updates rows, the update panel and the rail dot. One instance per
 * Settings back-stack entry, so SettingsScreen (the dot) and About (rows and panel) share it.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val controller: UpdateController
) : ViewModel() {

    val state: StateFlow<UpdateState> = controller.state

    /** Whether this build has the update check (fixed per build): About's Updates rows exist only then. */
    val supported: Boolean = controller.state.value.supported

    /** About's rail dot ([UpdateController.notice]). */
    val showNotice: StateFlow<Boolean> = controller.notice

    private val _panelRelease = MutableStateFlow<KnownRelease?>(null)

    /** The release the open update panel shows; null = no panel ([updatePanelRelease]). */
    val panelRelease: StateFlow<KnownRelease?> = _panelRelease.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { state ->
                _panelRelease.update { shown -> shown?.let { updatePanelRelease(state, it) } }
            }
        }
    }

    /** Check for updates. */
    fun checkNow() = controller.checkNow()

    fun setAutoCheck(enabled: Boolean) {
        // A failed write keeps the stored value, which the switch keeps showing.
        viewModelScope.launch { controller.setAutoCheck(enabled) }
    }

    /** Update available, or the pill's deep entry: opens on the known release, if one is newer. */
    fun openPanel() {
        controller.state.value.available?.let { _panelRelease.value = it }
    }

    /**
     * Not now, Back, ON_STOP while Settings shows, Settings leaving the screen other than for the
     * release video: the panel closes and a download stops (Spec 80 D14; the changelog page and its
     * video keep it, and the panel shows again on return).
     */
    fun closePanel() {
        _panelRelease.value = null
        controller.cancelDownload()
    }

    /** Download and install, or Try again. */
    fun download() {
        controller.downloadAndInstall()
    }

    /** The Settings back-stack entry ended (e.g. popped from under the release video): nothing may run on unseen. */
    override fun onCleared() {
        controller.cancelDownload()
    }

    /** Cancel while downloading: the download stops, the panel stays. */
    fun cancelDownload() = controller.cancelDownload()

    /** Spec 80 D14: the panel on screen (or not); a verified file goes to the installer at once only then. */
    fun setPanelShown(shown: Boolean) = controller.setPanelShown(shown)

    /** "Ready: Install now" (Spec 80 D6). */
    fun installReady() {
        controller.installReady()
    }
}

/**
 * What the open panel shows next, given it shows [shown]: the known release (a fresh check may
 * have learned a newer one); while a download runs or has failed after its release was dropped (a
 * pulled release, a file that never matched), still [shown]; null closes it (nothing newer and
 * nothing running, e.g. the fresh check found the release gone).
 */
internal fun updatePanelRelease(state: UpdateState, shown: KnownRelease): KnownRelease? = when {
    state.available != null -> state.available
    state.download != DownloadState.Idle -> shown
    else -> null
}
