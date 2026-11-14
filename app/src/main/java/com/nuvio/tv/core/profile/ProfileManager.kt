package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 6
        const val PIN_LENGTH = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    /**
     * The stored active profile id, null until it has loaded. Readers that must not act on the
     * default 1 (the startup PIN gate) use this one flow for both the id and readiness.
     */
    val storedActiveProfileId: StateFlow<Int?> = profileDataStore.activeProfileId
        .stateIn<Int?>(scope, SharingStarted.Eagerly, null)

    val activeProfileReady: StateFlow<Boolean> = storedActiveProfileId
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Spec 64 D14: the active profile vanished without the picker; the main UI returns to Who's watching. */
    val activeProfileFellBack: StateFlow<Boolean> = profileDataStore.activeProfileFellBack
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Spec 62 H3: read synchronously by MainActivity's idle and wake checks; null until loaded,
     * which counts as off (a restored process must not return against a stored off).
     */
    val returnToPickerEnabled: StateFlow<Boolean?> = profileDataStore.returnToPickerEnabled
        .stateIn<Boolean?>(scope, SharingStarted.Eagerly, null)

    /**
     * Spec 62 H3: the stored profile list, null until loaded (where [profiles] shows a one-profile
     * placeholder), so a pending return is not dropped as "no one to switch to" before it loads.
     */
    val storedProfiles: StateFlow<List<UserProfile>?> = profileDataStore.profilesList
        .stateIn<List<UserProfile>?>(scope, SharingStarted.Eagerly, null)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { it == 1 }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    /** The new profile as saved, or null at the [MAX_PROFILES] cap. */
    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        avatarId: String? = null
    ): UserProfile? {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            avatarId = avatarId
        )
        profileDataStore.upsertProfile(profile)
        return profile
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        deleteProfileData(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileData(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)

        // The Continue Watching snapshot caches are keyed by profile id too; without
        // this a future profile that reuses the id would show this profile's row.
        ContinueWatchingEnrichmentCache.nextUpCacheFile(context.filesDir, profileId).delete()
        ContinueWatchingEnrichmentCache.inProgressCacheFile(context.filesDir, profileId).delete()

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        // Shadow copies follow ProfileDataStoreFactory.writeShadowCopy's
        // "$fileName.preferences_pb.bak" scheme (plus ".tmp" if a write was killed);
        // sweeping them too prevents recoverFromShadowCopy resurrecting this profile's
        // data into a future profile that reuses the ID.
        val deletionSuffixes = listOf(
            suffixWithExtension,
            "$suffixWithExtension.bak",
            "$suffixWithExtension.bak.tmp"
        )
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (deletionSuffixes.any(file.name::endsWith)) {
                    file.delete()
                }
            }
        }
    }
}
