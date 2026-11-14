package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.profile.DpadCombo
import com.nuvio.tv.core.profile.DpadDirection
import com.nuvio.tv.core.profile.PinRelock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_lock_state",
    corruptionHandler = loggedResetCorruptionHandler("profile_lock_state")
)

/**
 * Device-local D-pad unlock state (T2). Deliberately device-scoped and account-wiped:
 * never synced (unlike the pin-enabled map below) and cleared with the rest of this
 * store on sign-out, so a combination set for one account can never unlock another
 * account's PIN-protected profiles on the same TV.
 */
data class DpadUnlockState(
    val enabled: Boolean = false,
    val comboHash: String? = null,
    val salt: String? = null,
    val comboLength: Int = 0
) {
    val isComboSet: Boolean
        get() = !comboHash.isNullOrEmpty() && !salt.isNullOrEmpty() && comboLength > 0
}

@Singleton
class ProfileLockStateDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileLockStateDataStore)

    // Serialises a lock-state pull (fetch + replace) with local PIN writes (L5-12): a pull
    // that read the server before setProfilePin landed must not replace the map after the
    // local setPinEnabled(true), or the startup gate would auto-enter the profile unlocked.
    private val pinMapMutex = Mutex()
    private val pinEnabledMapKey = stringPreferencesKey("pin_enabled_map")
    private val dpadUnlockEnabledKey = booleanPreferencesKey("dpad_unlock_enabled")
    private val dpadComboHashKey = stringPreferencesKey("dpad_combo_hash")
    private val dpadComboSaltKey = stringPreferencesKey("dpad_combo_salt")
    private val dpadComboLengthKey = intPreferencesKey("dpad_combo_length")
    private val settingsAccessGrantedKey = booleanPreferencesKey("settings_access_granted")
    private val relockAfterMinutesKey = intPreferencesKey("relock_after_minutes")

    val pinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        decode(prefs[pinEnabledMapKey])
    }

    val dpadUnlockState: Flow<DpadUnlockState> = dataStore.data.map { prefs ->
        DpadUnlockState(
            enabled = prefs[dpadUnlockEnabledKey] ?: false,
            comboHash = prefs[dpadComboHashKey],
            salt = prefs[dpadComboSaltKey],
            comboLength = prefs[dpadComboLengthKey] ?: 0
        )
    }

    /** T5: true only when the primary explicitly granted non-primary Settings access. */
    val settingsAccessGranted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[settingsAccessGrantedKey] ?: false
    }

    /** Spec 64 D15: minutes off screen before a PIN profile is asked again; [PinRelock.OFF] = only at start. */
    val relockAfterMinutes: Flow<Int> = dataStore.data.map { prefs ->
        PinRelock.sanitize(prefs[relockAfterMinutesKey])
    }

    suspend fun setRelockAfterMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[relockAfterMinutesKey] = PinRelock.sanitize(minutes)
        }
    }

    /**
     * Runs [pull] and, on success, replaces the whole pin-enabled map with its result; a
     * failed pull keeps the persisted map. Holds [pinMapMutex] across the fetch so a local
     * [setPinEnabled] issued meanwhile is applied after the (possibly stale) replace.
     */
    suspend fun refreshPinEnabled(
        pull: suspend () -> Result<Map<Int, Boolean>>
    ): Result<Map<Int, Boolean>> = pinMapMutex.withLock {
        pull().onSuccess { states ->
            dataStore.edit { prefs ->
                prefs[pinEnabledMapKey] = encode(states)
            }
        }
    }

    suspend fun setPinEnabled(profileId: Int, enabled: Boolean) {
        pinMapMutex.withLock {
            dataStore.edit { prefs ->
                val current = decode(prefs[pinEnabledMapKey]).toMutableMap()
                current[profileId] = enabled
                prefs[pinEnabledMapKey] = encode(current)
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    /** Saves a combination with a fresh salt; the raw sequence is never persisted. */
    suspend fun setDpadCombo(sequence: List<DpadDirection>) {
        require(DpadCombo.isValidLength(sequence.size)) {
            "D-pad combination length must be ${DpadCombo.MIN_LENGTH}..${DpadCombo.MAX_LENGTH}, got ${sequence.size}"
        }
        val salt = DpadCombo.generateSalt()
        dataStore.edit { prefs ->
            prefs[dpadComboSaltKey] = salt
            prefs[dpadComboHashKey] = DpadCombo.hash(sequence, salt)
            prefs[dpadComboLengthKey] = sequence.size
        }
    }

    /** The toggle can only be on while a combination is set (same predicate as [DpadUnlockState.isComboSet]). */
    suspend fun setDpadUnlockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val comboSet = DpadUnlockState(
                comboHash = prefs[dpadComboHashKey],
                salt = prefs[dpadComboSaltKey],
                comboLength = prefs[dpadComboLengthKey] ?: 0
            ).isComboSet
            prefs[dpadUnlockEnabledKey] = enabled && comboSet
        }
    }

    /** T5: the primary-owned grant; default (absent/false) is the T1 lock. */
    suspend fun setSettingsAccessGranted(granted: Boolean) {
        dataStore.edit { prefs ->
            prefs[settingsAccessGrantedKey] = granted
        }
    }

    suspend fun clearDpadCombo() {
        dataStore.edit { prefs ->
            prefs.remove(dpadComboHashKey)
            prefs.remove(dpadComboSaltKey)
            prefs.remove(dpadComboLengthKey)
            prefs[dpadUnlockEnabledKey] = false
        }
    }

    private fun encode(map: Map<Int, Boolean>): String =
        map.entries.joinToString(separator = ",") { "${it.key}:${it.value}" }

    private fun decode(raw: String?): Map<Int, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val enabled = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to enabled
        }.toMap()
    }
}
