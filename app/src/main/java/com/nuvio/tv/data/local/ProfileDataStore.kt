package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import kotlinx.serialization.Serializable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_settings",
    corruptionHandler = loggedResetCorruptionHandler("profile_settings")
)

@Singleton
class ProfileDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wireJson: Json
) {
    private val dataStore = context.profileDataStore

    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = ACTIVE_PROFILE_ID_KEY
    private val hasEverSelectedProfileKey = booleanPreferencesKey("profile_has_ever_selected")
    private val rememberLastProfileEnabledKey = booleanPreferencesKey("remember_last_profile_enabled")
    private val returnToPickerEnabledKey = booleanPreferencesKey("return_to_picker_enabled")


    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        val json = prefs[profilesJsonKey]
        if (json != null) {
            parseProfiles(json)
        } else {
            listOf(defaultPrimaryProfile())
        }
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    /** Spec 64 D14: the active profile was removed and the id fell back to 1 ([fallBackIfActiveRemoved]). */
    val activeProfileFellBack: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_FELL_BACK_KEY] == true
    }

    val hasEverSelectedProfile: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasEverSelectedProfileKey] ?: false
    }

    val rememberLastProfileEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rememberLastProfileEnabledKey] ?: false
    }

    /** Spec 62 H3 (D8): return to Who's watching after 30 min without use and on wake; on by default. */
    val returnToPickerEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[returnToPickerEnabledKey] ?: true
    }

    suspend fun setActiveProfile(id: Int) {
        dataStore.edit { prefs ->
            prefs[activeProfileIdKey] = id
            prefs.remove(ACTIVE_PROFILE_FELL_BACK_KEY)
            prefs[hasEverSelectedProfileKey] = true
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rememberLastProfileEnabledKey] = enabled
        }
    }

    suspend fun setReturnToPickerEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[returnToPickerEnabledKey] = enabled
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            prefs.fallBackIfActiveRemoved(current.map(UserProfile::id))
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        dataStore.edit { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            prefs.fallBackIfActiveRemoved(normalizedProfiles.map(UserProfile::id))
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = context.getString(R.string.profile_default_name, 1),
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val parsed = wireJson.decodeFromString<List<ProfileJson>>(json)
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        return wireJson.encodeToString(profiles.map { ProfileJson.fromDomain(it) })
    }
}

internal val ACTIVE_PROFILE_ID_KEY = intPreferencesKey("active_profile_id")
internal val ACTIVE_PROFILE_FELL_BACK_KEY = booleanPreferencesKey("active_profile_fell_back")

/**
 * When the active profile is not among [remainingIds] (deleted, or dropped by sync), falls the
 * active id back to 1 and records that it did (Spec 64 D14): the app must then ask Who's watching
 * again instead of carrying on as the primary without its PIN. `setActiveProfile` clears the record.
 */
internal fun MutablePreferences.fallBackIfActiveRemoved(remainingIds: Collection<Int>) {
    if ((this[ACTIVE_PROFILE_ID_KEY] ?: 1) !in remainingIds) {
        this[ACTIVE_PROFILE_ID_KEY] = 1
        this[ACTIVE_PROFILE_FELL_BACK_KEY] = true
    }
}

@Serializable
internal data class ProfileJson(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        usesPrimaryPlugins = usesPrimaryPlugins,
        avatarId = avatarId,
        avatarUrl = avatarUrl,
        profileBackgroundId = profileBackgroundId,
        profileBackgroundUrl = profileBackgroundUrl
    )

    companion object {
        fun fromDomain(profile: UserProfile) = ProfileJson(
            id = profile.id,
            name = profile.name,
            avatarColorHex = profile.avatarColorHex,
            usesPrimaryAddons = profile.usesPrimaryAddons,
            usesPrimaryPlugins = profile.usesPrimaryPlugins,
            avatarId = profile.avatarId,
            avatarUrl = profile.avatarUrl,
            profileBackgroundId = profile.profileBackgroundId,
            profileBackgroundUrl = profile.profileBackgroundUrl
        )
    }
}
