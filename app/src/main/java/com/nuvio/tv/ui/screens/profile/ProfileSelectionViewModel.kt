package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.core.util.StartupLatencyTrace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.DpadCombo
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.PinLockouts
import com.nuvio.tv.core.profile.PinVerifyOutcome
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.data.local.DpadUnlockState
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.data.local.ProfileRatingCapStore
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.data.LiveTvUserStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val avatarRepository: AvatarRepository,
    private val profileBackgroundRepository: ProfileBackgroundRepository,
    private val profileLockStateDataStore: ProfileLockStateDataStore,
    private val pinLockouts: PinLockouts,
    private val liveTvUserStore: LiveTvUserStore,
    private val profileLookStore: ProfileLookStore,
    private val myPhotosStore: MyPhotosStore,
    private val ratingCapStore: ProfileRatingCapStore,
    photoUploadStarter: PhotoUploadStarter
) : ViewModel() {
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    /** The stored active id, null until loaded: the Live TV switch's primary check fails closed on it. */
    val storedActiveProfileId: StateFlow<Int?> = profileManager.storedActiveProfileId
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.canCreateProfile

    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = avatarRepository.catalog

    val profileBackgroundCatalog: StateFlow<List<ProfileBackgroundCatalogItem>> =
        profileBackgroundRepository.catalog

    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profileLockStateDataStore.pinEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Null until the DataStore emits — the unlock overlay gates on a loaded value. */
    val dpadUnlockState: StateFlow<DpadUnlockState?> =
        profileLockStateDataStore.dpadUnlockState
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Profile id -> its Live TV switch (Spec 34 D17); a profile missing from the map reads as on. */
    val liveTvEnabledById: StateFlow<Map<Int, Boolean>> = profileManager.profiles
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(list.map { p -> liveTvUserStore.enabledFor(p.id).map { p.id to it } }) { it.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Profile id -> its TV-only look entry (Spec 44); a profile without one is missing from the map.
     * Null until the entries have loaded: an editor opened before then would save over a look it
     * never saw, so Edit waits for it.
     */
    val lookEntriesById: StateFlow<Map<Int, ProfileLookEntry>?> = profileManager.profiles
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(list.map { p -> profileLookStore.entryFor(p.id).map { p.id to it } }) { pairs ->
                    pairs.mapNotNull { (id, entry) -> entry?.let { id to it } }.toMap()
                }
            }
        }
        .stateIn<Map<Int, ProfileLookEntry>?>(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Profile id -> its rating cap (Spec 64 K2; a null value is no limit). Null until loaded, and a
     * profile is missing until its own cap has loaded: Edit waits for it (an editor opened on the
     * wrong cap would show it) and, outside primary-managed Manage profiles, Add stays hidden (D10,
     * [showAddProfile]).
     */
    val ratingCapById: StateFlow<Map<Int, ContentRatingCap?>?> = profileManager.profiles
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(list.map { p -> ratingCapStore.capFor(p.id).map { p.id to it } }) { it.toMap() }
            }
        }
        .stateIn<Map<Int, ContentRatingCap?>?>(viewModelScope, SharingStarted.Eagerly, null)

    /** My photos, newest first (Spec 44); null until read, and Edit waits for it as for [lookEntriesById]. */
    val myPhotos: StateFlow<List<MyPhoto>?> = myPhotosStore.photos

    /**
     * Add from phone (Spec 44 D4): the editor's QR card and TV confirm. Held here (the editor's host),
     * but the card stops it on dispose and ON_STOP, so neither VM scope keeps the page running.
     */
    internal val photoUploads = PhotoUploadController(
        scope = viewModelScope,
        start = photoUploadStarter::start,
        photos = myPhotosStore.photos,
        addPhoto = myPhotosStore::add
    )

    /** Removes My photo [id] (hold panel, D5); [onResult] false when the file could not be deleted. */
    fun removePhoto(id: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(myPhotosStore.remove(id)) }
    }

    /** Profile id -> PIN lockout expiry (elapsedRealtime ms), process-wide ([PinLockouts]). */
    val pinLockedUntil: StateFlow<Map<Int, Long>> = pinLockouts.lockedUntil

    private val _isPinOperationInProgress = MutableStateFlow(false)
    val isPinOperationInProgress: StateFlow<Boolean> = _isPinOperationInProgress.asStateFlow()

    init {
        refreshProfilePinStates()
        viewModelScope.launch { avatarRepository.loadCatalog() }
        profileBackgroundRepository.ensureLoaded()
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return avatarRepository.getAvatarImageUrl(avatarId)
    }

    fun loadProfileBackground(id: String) {
        profileBackgroundRepository.loadSelectedAndPreload(id)
    }

    fun preloadProfileBackgrounds() {
        profileBackgroundRepository.preloadImages()
    }

    fun selectProfile(id: Int, onComplete: () -> Unit) {
        StartupLatencyTrace.begin("profile_tap")
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
            StartupLatencyTrace.mark("profile_active_set")
            onComplete()
        }
    }

    /**
     * [onResult] gets whether the profile was saved on this device (false only at the six-profile
     * cap), before the sync push, whose failures stay silent (as built). [liveTvEnabled] is always
     * written for the new id (a reused id may hold a stale value from a profile that vanished
     * without deletion); a failed write calls [onLiveTvNotSaved], the profile stays saved. The look
     * entry (Spec 44) is likewise always written for the new id before [onResult]: [localAvatar]
     * over the saved profile, or a clear (a reused id may hold a deleted profile's entry); a failed
     * write calls [onLookNotSaved]. The rating cap (Spec 64) is likewise always written for the new
     * id before [onResult] ([ratingCap] null clears a reused id's stale cap); a failed write calls
     * [onRatingCapNotSaved], and the profile keeps whatever the file held (none for a fresh id).
     */
    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        liveTvEnabled: Boolean = true,
        onLiveTvNotSaved: () -> Unit = {},
        localAvatar: ProfileLookPick? = null,
        onLookNotSaved: () -> Unit = {},
        ratingCap: ContentRatingCap? = null,
        onRatingCapNotSaved: () -> Unit = {},
        onResult: (Boolean) -> Unit = {}
    ) {
        // NonCancellable: the first saved non-primary makes MainActivity show Who's watching in place
        // of the main UI, which clears this VM; the cap and look writes must still land (Spec 64 D14).
        viewModelScope.launch {
            withContext(NonCancellable) {
                val created = profileManager.createProfile(
                    name = name,
                    avatarColorHex = avatarColorHex,
                    avatarId = avatarId
                )
                val lookSaved = created == null ||
                    profileLookStore.write(created.id, ProfileLookEntry.of(created, localAvatar, null))
                val capSaved = created == null || ratingCapStore.write(created.id, ratingCap)
                onResult(created != null)
                if (!lookSaved) onLookNotSaved()
                if (!capSaved) onRatingCapNotSaved()
                if (created != null && !liveTvUserStore.setEnabled(created.id, liveTvEnabled)) onLiveTvNotSaved()
                if (created != null) {
                    profileSyncService.pushToRemote()
                    refreshProfilePinStates()
                }
            }
        }
    }

    /**
     * As [createProfile]; false when the profile no longer exists (deleted meanwhile). [liveTvEnabled]
     * non-null writes the profile's Live TV switch after a successful save (the editor passes it
     * only when changed). After a successful save the look entry is rewritten before [onResult]:
     * [localAvatar] / [localBackground] over [profile]'s synced key, or a clear when both are null;
     * a failed write calls [onLookNotSaved]. [ratingCap] non-null (the editor passes it on every
     * save where the limit is editable, [ratingCapEditFor]) writes its cap after a successful save,
     * also before [onResult]; a failed write calls [onRatingCapNotSaved].
     */
    fun updateProfile(
        profile: UserProfile,
        liveTvEnabled: Boolean? = null,
        onLiveTvNotSaved: () -> Unit = {},
        localAvatar: ProfileLookPick? = null,
        localBackground: ProfileLookPick? = null,
        onLookNotSaved: () -> Unit = {},
        ratingCap: RatingCapEdit? = null,
        onRatingCapNotSaved: () -> Unit = {},
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val success = profileManager.updateProfile(profile)
            val lookSaved = !success ||
                profileLookStore.write(profile.id, ProfileLookEntry.of(profile, localAvatar, localBackground))
            val capSaved = !success || ratingCap == null || ratingCapStore.write(profile.id, ratingCap.cap)
            onResult(success)
            if (!lookSaved) onLookNotSaved()
            if (!capSaved) onRatingCapNotSaved()
            if (success && liveTvEnabled != null && !liveTvUserStore.setEnabled(profile.id, liveTvEnabled)) {
                onLiveTvNotSaved()
            }
            if (success) {
                profileSyncService.pushToRemote()
                refreshProfilePinStates()
            }
        }
    }

    fun deleteProfile(id: Int) {
        // NonCancellable: deleting the active profile returns the app to Who's watching (Spec 64 D14),
        // which clears this VM; the remote delete and push must still run or sync restores the profile.
        viewModelScope.launch {
            withContext(NonCancellable) {
                profileManager.deleteProfile(id)
                profileSyncService.deleteProfileData(id)
                profileSyncService.pushToRemote()
                refreshProfilePinStates()
            }
        }
    }

    override fun onCleared() {
        photoUploads.close()
        super.onCleared()
    }

    fun refreshProfilePinStates() {
        viewModelScope.launch { profileSyncService.refreshProfileLockStates() }
    }

    fun setProfilePin(
        profileId: Int,
        pin: String,
        currentPin: String? = null,
        onComplete: (SetProfilePinResult) -> Unit
    ) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profileSyncService.setProfilePin(profileId, pin, currentPin)
            // Server reporting CurrentPinRequired means a PIN exists remotely —
            // reconcile local cache so we never forget it again.
            if (result is SetProfilePinResult.Success || result is SetProfilePinResult.CurrentPinRequired) {
                profileLockStateDataStore.setPinEnabled(profileId, true)
            }
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun clearProfilePin(profileId: Int, currentPin: String? = null, onComplete: (Boolean) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val success = profileSyncService.clearProfilePin(profileId, currentPin).isSuccess
            if (success) {
                profileLockStateDataStore.setPinEnabled(profileId, false)
            }
            _isPinOperationInProgress.value = false
            onComplete(success)
        }
    }

    /** Checks [pin] on the server; a lockout it reports is recorded in [PinLockouts]. */
    fun verifyProfilePin(profileId: Int, pin: String, onComplete: (PinVerifyOutcome) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profileSyncService.verifyProfilePin(profileId, pin)
            _isPinOperationInProgress.value = false
            onComplete(pinLockouts.record(profileId, result))
        }
    }

    /**
     * Device-local D-pad verification (T4). The verdict reads the store fresh
     * via first() — the dpadUnlockState StateFlow can still hold its null
     * initial value, which would spuriously fail. The enabled gate lives at
     * the screen (single caller); this checks combination integrity only.
     * The explicit length check makes shorter sequences fail by construction
     * rather than by hash luck. No server call, so unlike [verifyProfilePin]
     * there is no in-progress gate or lockout — wrong combinations just reset
     * the capture.
     */
    fun verifyDpadCombo(sequence: List<DpadDirection>, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val state = profileLockStateDataStore.dpadUnlockState.first()
            // isComboSet guarantees non-empty salt/hash; orEmpty only satisfies
            // the compiler (smart cast can't cross the custom getter), verify()
            // rejects empties itself.
            val matched = state.isComboSet &&
                state.comboLength == sequence.size &&
                DpadCombo.verify(sequence, state.salt.orEmpty(), state.comboHash.orEmpty())
            onComplete(matched)
        }
    }
}
