package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import com.nuvio.tv.domain.model.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One home for the usesPrimaryAddons mapping (L9-08/38/42): a non-primary profile with
 * usesPrimaryAddons reads profile 1's addon and home-catalog stores and never writes them.
 */
internal fun isAddonReadOnly(profiles: List<UserProfile>, profileId: Int): Boolean =
    profileId != 1 && profiles.firstOrNull { it.id == profileId }?.usesPrimaryAddons == true

/** The profile whose addon / home-catalog stores [profileId] reads. */
internal fun addonOwnerProfileId(profiles: List<UserProfile>, profileId: Int): Int =
    if (isAddonReadOnly(profiles, profileId)) 1 else profileId

/** [addonOwnerProfileId] of the active profile, re-resolved when the id OR the profile list changes. */
internal fun ProfileManager.addonOwnerProfileIdFlow(): Flow<Int> =
    combine(activeProfileId, profiles) { activeId, profileList ->
        addonOwnerProfileId(profileList, activeId)
    }.distinctUntilChanged()

/**
 * The stored addon order after the phone config page proposes [proposed] and removes
 * [removed] (L5-b, QB2). Stored urls that either names are the page's slots: they take
 * [proposed] in order, and a slot left over by a removal is dropped. Every other stored url
 * (an addon the page never showed, e.g. its manifest was still loading when the page was
 * served) keeps its slot, where replacing the list with [proposed] used to delete it.
 * Proposed urls left after every page slot is filled follow at the end. Urls match by
 * [addonUrlMatchKey].
 */
internal fun splicedAddonOrder(
    stored: List<String>,
    proposed: List<String>,
    removed: Collection<String>
): List<String> {
    val queue = ArrayDeque(proposed.distinctBy(::addonUrlMatchKey))
    val pageKeys = (proposed + removed).mapTo(HashSet(), ::addonUrlMatchKey)
    val result = ArrayList<String>(stored.size + queue.size)
    for (url in stored) {
        if (addonUrlMatchKey(url) !in pageKeys) result += url
        else queue.removeFirstOrNull()?.let(result::add)
    }
    result += queue
    return result
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "addon_preferences"
    }

    private fun activeProfileId(): Int = profileManager.activeProfileId.value

    private fun isReadOnly(profileId: Int): Boolean =
        isAddonReadOnly(profileManager.profiles.value, profileId)

    /** Store read and written for [profileId] (profile 1's for a read-only profile). */
    private fun store(profileId: Int = activeProfileId()) =
        factory.get(addonOwnerProfileId(profileManager.profiles.value, profileId), FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = profileManager.addonOwnerProfileIdFlow()

    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")
    private val userSetNamesKey = stringPreferencesKey("addon_user_set_names")
    private val addonEnabledStatesKey = stringPreferencesKey("installed_addon_enabled_states")
    // Spec 40: canonical urls of addons whose search catalogs run only on submit. Local to this
    // TV: addon sync pushes url/order/enabled/name only, and this feature is not in the
    // profile-settings blob.
    private val searchOnSubmitUrlsKey = stringSetPreferencesKey("addon_search_on_submit_urls")
    // Main F23: canonicalisation lives in core.util - one implementation
    // shared with AddonRepositoryImpl and AddonSyncService.
    private fun canonicalizeUrl(url: String): String = canonicalizeAddonUrl(url)

    val installedAddonUrls: Flow<List<String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[orderedUrlsKey]
            if (json != null) {
                parseUrlList(json)
            } else {
                val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
                legacySet.toList()
            }
        }
    }

    val addonEnabledStates: Flow<Map<String, Boolean>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            preferences[addonEnabledStatesKey]
                ?.let(::parseEnabledStateMap)
                .orEmpty()
        }
    }

    /** Spec 40: canonical urls of the addons searched only on submit (profile 1's for a read-only profile). */
    val searchOnSubmitUrls: Flow<Set<String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences -> preferences.searchOnSubmitUrlSet() }
    }.distinctUntilChanged()

    /** Spec 40: marks [url] searched only on submit, or as you type. False for a read-only profile or no change. */
    suspend fun setSearchOnSubmit(url: String, onSubmit: Boolean): Boolean {
        val profileId = activeProfileId()
        if (isReadOnly(profileId)) return false
        val canonical = canonicalizeUrl(url)
        var changed = false
        store(profileId).edit { preferences ->
            val current = preferences.searchOnSubmitUrlSet()
            // Case-insensitive, as readers and the prune match: a mark stored in another spelling
            // counts as set, and unsetting drops every spelling.
            val matchKey = canonical.lowercase()
            val marked = current.any { it.lowercase() == matchKey }
            val updated = when {
                onSubmit -> if (marked) current else current + canonical
                else -> current.filterTo(HashSet()) { it.lowercase() != matchKey }
            }
            if (updated == current) return@edit
            preferences[searchOnSubmitUrlsKey] = updated
            changed = true
        }
        return changed
    }

    private fun Preferences.searchOnSubmitUrlSet(): Set<String> =
        this[searchOnSubmitUrlsKey]?.mapTo(HashSet(), ::canonicalizeUrl).orEmpty()

    suspend fun addAddon(url: String): Boolean {
        val profileId = activeProfileId()
        if (isReadOnly(profileId)) return false
        var changed = false
        store(profileId).edit { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = encodeStringList(current + normalizedUrl)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states[normalizedUrl] = true
            preferences[addonEnabledStatesKey] = encodeBooleanMap(states)
            changed = true
        }
        return changed
    }

    suspend fun removeAddon(url: String): Boolean {
        val profileId = activeProfileId()
        if (isReadOnly(profileId)) return false
        var changed = false
        store(profileId).edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            if (indexToRemove == -1) return@edit
            preferences[orderedUrlsKey] = encodeStringList(current)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states.remove(normalizedUrl)
            preferences[addonEnabledStatesKey] = encodeBooleanMap(states)
            preferences.pruneSearchOnSubmit(current.map(::canonicalizeUrl))
            changed = true
        }
        return changed
    }

    /**
     * @param profileId the profile being written; a sync apply passes the profile it fetched
     *   for, so a profile switch mid-sync cannot redirect the write (L9-05/67).
     */
    suspend fun setAddonOrder(urls: List<String>, profileId: Int = activeProfileId()): Boolean =
        editAddonOrder(profileId) { urls.map(::canonicalizeUrl) }

    /** Applies a phone config page change to the stored order in one edit: [splicedAddonOrder]. */
    suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>): Boolean =
        editAddonOrder(activeProfileId()) { current ->
            splicedAddonOrder(current, urls.map(::canonicalizeUrl), removedUrls)
        }

    /**
     * Moves [url] one slot by [direction] (-1 up, +1 down) inside the stored list, in one
     * edit (L5-68): the move never rebuilds the list from a UI snapshot that may lack
     * addons, and back-to-back moves each see the previous one. Writes what [setAddonOrder]
     * writes for the moved list. False when [url] is not stored or already at the edge.
     */
    suspend fun moveAddon(url: String, direction: Int): Boolean =
        editAddonOrder(activeProfileId()) { current ->
            val normalizedUrl = canonicalizeUrl(url)
            val index = current.indexOfFirst { it.equals(normalizedUrl, ignoreCase = true) }
            val newIndex = index + direction
            if (index == -1 || newIndex !in current.indices) null
            else current.toMutableList().apply { add(newIndex, removeAt(index)) }
        }

    /**
     * One edit of [profileId]'s stored order: [reorder] maps the canonical stored list to the
     * new one (null leaves it); an unchanged result writes nothing. False for a read-only
     * profile or no change. Shared by [setAddonOrder], [spliceAddonOrder] and [moveAddon].
     */
    private suspend fun editAddonOrder(
        profileId: Int,
        reorder: (List<String>) -> List<String>?
    ): Boolean {
        if (isReadOnly(profileId)) return false
        var changed = false
        store(profileId).edit { preferences ->
            val currentUrls = getCurrentList(preferences).map(::canonicalizeUrl)
            val orderedUrls = reorder(currentUrls) ?: return@edit
            if (orderedUrls == currentUrls) return@edit
            preferences.writeAddonOrder(orderedUrls)
            changed = true
        }
        return changed
    }

    /**
     * Stores [orderedUrls] as the addon order and rewrites the enabled states to exactly
     * those urls (unknown = enabled); shared by [setAddonOrder], [spliceAddonOrder] and
     * [moveAddon].
     */
    private fun MutablePreferences.writeAddonOrder(orderedUrls: List<String>) {
        this[orderedUrlsKey] = encodeStringList(orderedUrls)
        val currentStates = getCurrentEnabledStates(this)
        this[addonEnabledStatesKey] = encodeBooleanMap(
            orderedUrls.associateWith { url -> currentStates[url] ?: true }
        )
        pruneSearchOnSubmit(orderedUrls)
    }

    /** Drops search-on-submit marks of urls no longer installed, so a reinstall starts as-you-type. */
    private fun MutablePreferences.pruneSearchOnSubmit(installedCanonicalUrls: List<String>) {
        val current = searchOnSubmitUrlSet()
        if (current.isEmpty()) return
        // Case-insensitive, as every other url match in this class (a splice may re-spell an url).
        val installed = installedCanonicalUrls.mapTo(HashSet()) { it.lowercase() }
        val kept = current.filterTo(HashSet()) { it.lowercase() in installed }
        if (kept != current) this[searchOnSubmitUrlsKey] = kept
    }

    suspend fun setAddonEnabled(url: String, enabled: Boolean): Boolean {
        val profileId = activeProfileId()
        if (isReadOnly(profileId)) return false
        var changed = false
        store(profileId).edit { preferences ->
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            val normalizedUrl = canonicalizeUrl(url)
            if ((states[normalizedUrl] ?: true) == enabled) return@edit
            states[normalizedUrl] = enabled
            preferences[addonEnabledStatesKey] = encodeBooleanMap(states)
            changed = true
        }
        return changed
    }

    suspend fun setAddonEnabledStates(states: Map<String, Boolean>, profileId: Int = activeProfileId()) {
        if (isReadOnly(profileId)) return
        store(profileId).edit { preferences ->
            preferences[addonEnabledStatesKey] = encodeBooleanMap(
                states.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    /** Snapshot of the installed addon URLs [profileId] sees (profile 1's for a read-only profile). */
    suspend fun getInstalledAddonUrls(profileId: Int): List<String> =
        getCurrentList(store(profileId).data.first())

    /**
     * Spec 68 P8: the list in [storeProfileId]'s own store, read straight from the factory with no
     * usesPrimaryAddons mapping (that mapping resolves through `profiles.value`, whose initial value
     * is a placeholder).
     */
    suspend fun getStoredAddonUrls(storeProfileId: Int): List<String> =
        getCurrentList(factory.get(storeProfileId, FEATURE).data.first())

    /** Snapshot of [profileId]'s user-set addon names (profile 1's for a read-only profile). */
    suspend fun getUserSetNames(profileId: Int): Map<String, String> =
        store(profileId).data.first().userSetNameMap()

    /** Snapshot of [profileId]'s addon enabled states (profile 1's for a read-only profile). */
    suspend fun getAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        getCurrentEnabledStates(store(profileId).data.first())

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
    }

    private fun parseUrlList(json: String): List<String> {
        return decodeStringList(json) ?: getDefaultAddons().toList()
    }

    val userSetNames: Flow<Map<String, String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences -> preferences.userSetNameMap() }
    }

    suspend fun setUserSetNames(names: Map<String, String>, profileId: Int = activeProfileId()) {
        if (isReadOnly(profileId)) return
        store(profileId).edit { preferences ->
            preferences[userSetNamesKey] = encodeStringMap(
                names.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    /**
     * Sets the active profile's name for the addon at [url], in one whole-map edit (the key is
     * synced as a whole map). A blank [name], or one equal to [manifestName], removes the entry
     * so the addon shows its own name. False for a read-only profile or no change.
     */
    suspend fun setUserSetName(url: String, name: String, manifestName: String): Boolean {
        val profileId = activeProfileId()
        if (isReadOnly(profileId)) return false
        val canonical = canonicalizeUrl(url)
        var changed = false
        store(profileId).edit { preferences ->
            val current = preferences.userSetNameMap()
            val updated = current.withCustomName(canonical, name, manifestName)
            if (updated == current) return@edit
            preferences[userSetNamesKey] = encodeStringMap(updated)
            changed = true
        }
        return changed
    }

    private fun Preferences.userSetNameMap(): Map<String, String> =
        this[userSetNamesKey]?.let(::parseNameMap).orEmpty()

    private fun parseNameMap(json: String): Map<String, String> {
        val parsed = decodeStringMap(json) ?: emptyMap()
        return parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
    }

    private fun getCurrentEnabledStates(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> {
        val parsed = decodeBooleanMap(json) ?: emptyMap()
        return parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
    }

    private fun getDefaultAddons(): Set<String> = setOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io"
    )
}
