package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DpadUnlockState
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Profiles pane shows, read from the stores (never a placeholder). */
data class ProfilesPaneState(
    val profileCount: Int,
    val rememberLastProfile: Boolean,
    /** Spec 62 H3: return to Who's watching after 30 min without use and on wake (this TV). */
    val returnToPicker: Boolean,
    /** T5: the primary-owned grant letting other profiles into Settings. */
    val settingsAccessGranted: Boolean,
    val dpadUnlock: DpadUnlockState,
    /** Spec 64 D15: Ask for PIN again, minutes off screen ([com.nuvio.tv.core.profile.PinRelock]). */
    val relockMinutes: Int
)

/**
 * The Settings shell's primary-profile gate and the Profiles pane's rows. Live profile management
 * (create/update/delete) lives on the profile management screen.
 */
@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    profileManager: ProfileManager,
    private val profileDataStore: ProfileDataStore,
    private val profileLockStateDataStore: ProfileLockStateDataStore
) : ViewModel() {

    val isPrimaryProfileActive: StateFlow<Boolean> = profileManager.isPrimaryProfileActive

    /** Null until every store has emitted: the pane renders rows only after (no dropped first toggle). */
    val paneState: StateFlow<ProfilesPaneState?> = combine(
        combine(
            profileDataStore.rememberLastProfileEnabled,
            profileDataStore.returnToPickerEnabled
        ) { rememberLast, returnToPicker -> rememberLast to returnToPicker },
        profileDataStore.profilesList,
        profileLockStateDataStore.settingsAccessGranted,
        profileLockStateDataStore.dpadUnlockState,
        profileLockStateDataStore.relockAfterMinutes
    ) { (rememberLast, returnToPicker), profiles, accessGranted, dpad, relockMinutes ->
        ProfilesPaneState(profiles.size, rememberLast, returnToPicker, accessGranted, dpad, relockMinutes)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setRememberLastProfile(enabled: Boolean) {
        viewModelScope.launch { profileDataStore.setRememberLastProfileEnabled(enabled) }
    }

    fun setReturnToPicker(enabled: Boolean) {
        viewModelScope.launch { profileDataStore.setReturnToPickerEnabled(enabled) }
    }

    fun setSettingsAccessGranted(granted: Boolean) {
        viewModelScope.launch { profileLockStateDataStore.setSettingsAccessGranted(granted) }
    }

    fun setRelockAfterMinutes(minutes: Int) {
        viewModelScope.launch { profileLockStateDataStore.setRelockAfterMinutes(minutes) }
    }

    fun setDpadUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { profileLockStateDataStore.setDpadUnlockEnabled(enabled) }
    }
}
