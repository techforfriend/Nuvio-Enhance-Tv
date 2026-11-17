package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LocalHomeCatalogSettingsState
import com.nuvio.tv.core.sync.SyncHomeCatalogPayload
import com.nuvio.tv.core.sync.buildHomeCatalogSyncPayload
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import com.nuvio.tv.domain.model.TrailerPreviewDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LayoutPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "LayoutPreferenceDS"
        private const val FEATURE = "layout_settings"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    // Home-catalog order/disabled/title keys follow the addon owner (L9-38): a read-only
    // (usesPrimaryAddons) profile reads profile 1's; reads and writes go through these two
    // resolvers, and a read-only profile never writes profile 1's catalog layout (the
    // AddonPreferences rule). Declared before the flows that use it at construction.
    private val catalogOwnerProfileIdFlow: Flow<Int> = profileManager.addonOwnerProfileIdFlow()

    private fun catalogReadStore(profileId: Int) =
        store(addonOwnerProfileId(profileManager.profiles.value, profileId))

    /** Null for a read-only profile: catalog-layout writes are dropped, never redirected. */
    private fun catalogWriteStore(profileId: Int) =
        if (isAddonReadOnly(profileManager.profiles.value, profileId)) null else store(profileId)

    // Retired (Spec 31 S5): selected_layout, has_chosen_layout. Only the Essential/Advanced
    // first-launch picker wrote them and only its routing read them; Home is the one layout.
    // Stored values stay untouched (never read or rewritten; the sync blob carries them). Don't reuse.
    // Retired 2026-10-15 (Spec 26 B8): hero_catalog_key, hero_catalog_keys. The fork cut the hero
    // catalog picker (e20bdc4bd), so a stored or synced value steered the hero with no way to
    // change it; the hero now always picks from the home rows. Stored values stay untouched
    // (never read or rewritten; the profile-settings sync blob carries them as-is). Don't reuse.
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val customCatalogTitlesKey = stringPreferencesKey("custom_catalog_titles")
    // Retired 2026-10-06 (U1b: the top bar replaced both sidebars): sidebar_collapsed_by_default,
    // modern_sidebar_enabled, glass_sidepanel_enabled, modern_sidebar_blur_enabled. Stored values
    // stay untouched (never read or rewritten; the profile-settings sync blob carries them as-is).
    // Don't reuse these names.
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    // Retired 2026-10-17 (Spec 31 S2: no reader, no writer): hero_section_enabled (Home shows the
    // hero), poster_labels_enabled (unread since U4e), poster_card_width_dp and
    // poster_card_corner_radius_dp (unread since Spec 28e). Stored values stay untouched (never
    // read or rewritten; the profile-settings sync blob carries them as-is). Don't reuse.
    private val catalogAddonNameEnabledKey = booleanPreferencesKey("catalog_addon_name_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    // Retired 2026-10-06 (U1c: Marquee Home is always full-bleed, the trailer plays behind the
    // hero only): focused_poster_backdrop_expand_enabled, focused_poster_backdrop_expand_delay_seconds,
    // focused_poster_backdrop_trailer_playback_target, modern_hero_full_screen_backdrop. Stored
    // values stay untouched (never read or rewritten).
    private val focusedPosterBackdropTrailerEnabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val focusedPosterBackdropTrailerMutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    // Spec 96: with the switch above on, true = the trailer plays in the focused card (TrailerAutoplayMode.CARD).
    private val trailerCardPreviewKey = booleanPreferencesKey("trailer_card_preview")
    // Spec 96 D12: seconds, TrailerPreviewDelay range; clamped on read (the profile sync writes any int).
    private val trailerCardPreviewDelaySecondsKey = intPreferencesKey("trailer_card_preview_delay_seconds")
    // Retired 2026-10-17 (Spec 31 S1: no Marquee card draws card depth): card_depth_enabled,
    // card_depth_edge_strength, card_depth_sheen_strength, card_depth_edge_coverage,
    // card_depth_posters_enabled, card_depth_continue_watching_enabled,
    // card_depth_episode_cards_enabled, card_depth_cast_enabled, card_depth_trailers_enabled.
    // Stored values stay untouched (never read or rewritten; the profile-settings sync blob
    // carries them as-is). Don't reuse these names.
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val homeImdbRatingsVisibilityKey = stringPreferencesKey("home_imdb_ratings_visibility")
    private val detailImdbRatingsVisibilityKey = stringPreferencesKey("detail_imdb_ratings_visibility")
    private val useEpisodeThumbnailsInCwKey = booleanPreferencesKey("use_episode_thumbnails_in_cw")
    private val continueWatchingEnabledKey = booleanPreferencesKey("continue_watching_enabled")
    private val continueWatchingCardStyleKey = stringPreferencesKey("continue_watching_card_style")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")
    private val nextUpFromFurthestEpisodeKey = booleanPreferencesKey("next_up_from_furthest_episode")
    private val blurContinueWatchingNextUpKey = booleanPreferencesKey("blur_continue_watching_next_up")
    private val continueWatchingSortModeKey = stringPreferencesKey("continue_watching_sort_mode")
    private val detailPageTrailerButtonEnabledKey = booleanPreferencesKey("detail_page_trailer_button_enabled")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")
    private val showFullReleaseDateKey = booleanPreferencesKey("show_full_release_date")
    private val smoothBringIntoViewEnabledKey = booleanPreferencesKey("smooth_bring_into_view_enabled")
    private val fastHorizontalNavigationEnabledKey = booleanPreferencesKey("fast_horizontal_navigation_enabled")
    private val addonHealthEnabledKey = booleanPreferencesKey("addon_health_enabled")
    private val followAddonsOrderKey = booleanPreferencesKey("follow_addons_order")
    private val composeHighlighterEnabledKey = booleanPreferencesKey("compose_highlighter_enabled")

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    private fun Preferences.getStringOrMigrateSet(key: Preferences.Key<String>): String? {
        return try {
            this[key]
        } catch (e: ClassCastException) {
            val setKey = stringSetPreferencesKey(key.name)
            val legacySet = try { this[setKey] } catch (_: Exception) { null }
            if (legacySet != null) {
                Log.w(TAG, "Key '${key.name}' stored as Set instead of String (${legacySet.size} items), converting")
                encodeStringList(legacySet.toList())
            } else {
                Log.e(TAG, "ClassCastException for key '${key.name}' but no Set value found", e)
                null
            }
        }
    }

    val continueWatchingEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[continueWatchingEnabledKey] ?: true
    }

    val continueWatchingCardStyle: Flow<ContinueWatchingCardStyle> = profileFlow { prefs ->
        val styleName = prefs[continueWatchingCardStyleKey] ?: ContinueWatchingCardStyle.CARD.name
        try {
            ContinueWatchingCardStyle.valueOf(styleName)
        } catch (e: IllegalArgumentException) {
            ContinueWatchingCardStyle.CARD
        }
    }

    val homeCatalogOrderKeys: Flow<List<String>> = catalogOwnerProfileIdFlow.flatMapLatest { ownerId ->
        factory.get(ownerId, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs.getStringOrMigrateSet(homeCatalogOrderKeysKey))
        }
    }

    val disabledHomeCatalogKeys: Flow<List<String>> = catalogOwnerProfileIdFlow.flatMapLatest { ownerId ->
        factory.get(ownerId, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs.getStringOrMigrateSet(disabledHomeCatalogKeysKey))
        }
    }

    val customCatalogTitles: Flow<Map<String, String>> = catalogOwnerProfileIdFlow.flatMapLatest { ownerId ->
        factory.get(ownerId, FEATURE).data.map { prefs ->
            parseCustomTitles(prefs.getStringOrMigrateSet(customCatalogTitlesKey))
        }
    }

    val modernLandscapePostersEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernLandscapePostersEnabledKey] ?: false
    }

    val discoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[discoverLocationKey] ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(stored) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val catalogAddonNameEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogAddonNameEnabledKey] ?: true
    }

    val catalogTypeSuffixEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogTypeSuffixEnabledKey] ?: true
    }

    val focusedPosterBackdropTrailerMuted: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerMutedKey] ?: true
    }

    val trailerAutoplayMode: Flow<TrailerAutoplayMode> = profileFlow { prefs ->
        TrailerAutoplayMode.of(
            enabled = prefs[focusedPosterBackdropTrailerEnabledKey] ?: false,
            card = prefs[trailerCardPreviewKey] ?: false
        )
    }

    val trailerCardPreviewDelaySeconds: Flow<Int> = profileFlow { prefs ->
        (prefs[trailerCardPreviewDelaySecondsKey] ?: TrailerPreviewDelay.DEFAULT_S)
            .coerceIn(TrailerPreviewDelay.MIN_S, TrailerPreviewDelay.MAX_S)
    }

    val blurUnwatchedEpisodes: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurUnwatchedEpisodesKey] ?: false
    }

    val homeImdbRatingsVisibility: Flow<HomeImdbRatingsVisibility> = profileFlow { prefs ->
        val stored = prefs[homeImdbRatingsVisibilityKey] ?: HomeImdbRatingsVisibility.SHOW_ALL.name
        runCatching { HomeImdbRatingsVisibility.valueOf(stored) }
            .getOrDefault(HomeImdbRatingsVisibility.SHOW_ALL)
    }

    val detailImdbRatingsVisibility: Flow<DetailImdbRatingsVisibility> = profileFlow { prefs ->
        val stored = prefs[detailImdbRatingsVisibilityKey] ?: DetailImdbRatingsVisibility.SHOW_ALL.name
        runCatching { DetailImdbRatingsVisibility.valueOf(stored) }
            .getOrDefault(DetailImdbRatingsVisibility.SHOW_ALL)
            .asEpisodeVisibility()
    }

    val useEpisodeThumbnailsInCw: Flow<Boolean> = profileFlow { prefs ->
        prefs[useEpisodeThumbnailsInCwKey] ?: true
    }

    val showUnairedNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[showUnairedNextUpKey] ?: true
    }

    val nextUpFromFurthestEpisode: StateFlow<Boolean> = profileFlow { prefs ->
        prefs[nextUpFromFurthestEpisodeKey] ?: true
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val blurContinueWatchingNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurContinueWatchingNextUpKey] ?: false
    }

    val continueWatchingSortMode: Flow<ContinueWatchingSortMode> = profileFlow { prefs ->
        val stored = prefs[continueWatchingSortModeKey] ?: ContinueWatchingSortMode.DEFAULT.name
        runCatching { ContinueWatchingSortMode.valueOf(stored) }
            .getOrDefault(ContinueWatchingSortMode.DEFAULT)
    }

    val detailPageTrailerButtonEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailPageTrailerButtonEnabledKey] ?: true
    }

    val preferExternalMetaAddonDetail: StateFlow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: true
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val hideUnreleasedContent: Flow<Boolean> = profileFlow { prefs ->
        prefs[hideUnreleasedContentKey] ?: false
    }

    val showFullReleaseDate: Flow<Boolean> = profileFlow { prefs ->
        prefs[showFullReleaseDateKey] ?: true
    }

    val smoothBringIntoViewEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[smoothBringIntoViewEnabledKey] ?: true
    }

    val fastHorizontalNavigationEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[fastHorizontalNavigationEnabledKey] ?: false
    }

    val addonHealthEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[addonHealthEnabledKey] ?: true
    }

    val followAddonsOrder: Flow<Boolean> = profileFlow { prefs ->
        prefs[followAddonsOrderKey] ?: false
    }

    val composeHighlighterEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[composeHighlighterEnabledKey] ?: false
    }

    suspend fun setSmoothBringIntoViewEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[smoothBringIntoViewEnabledKey] = enabled
        }
    }

    suspend fun setFastHorizontalNavigationEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fastHorizontalNavigationEnabledKey] = enabled
        }
    }

    suspend fun setAddonHealthEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[addonHealthEnabledKey] = enabled
        }
    }

    suspend fun setFollowAddonsOrder(enabled: Boolean) {
        store().edit { prefs ->
            prefs[followAddonsOrderKey] = enabled
        }
    }

    suspend fun setComposeHighlighterEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[composeHighlighterEnabledKey] = enabled
        }
    }

    suspend fun setHomeCatalogOrderKeys(keys: List<String>) {
        updateCatalogKeys(homeCatalogOrderKeysKey) { keys }
    }

    suspend fun setDisabledHomeCatalogKeys(keys: List<String>) {
        updateCatalogKeys(disabledHomeCatalogKeysKey) { keys }
    }

    /**
     * Read-modify-write of the stored home catalog order in one edit (L5-73): [transform]
     * gets the stored keys and returns the new keys, or null to leave them unchanged.
     * True when written.
     */
    suspend fun updateHomeCatalogOrderKeys(transform: (List<String>) -> List<String>?): Boolean =
        updateCatalogKeys(homeCatalogOrderKeysKey, transform)

    /** [updateHomeCatalogOrderKeys] for the disabled home catalog keys. */
    suspend fun updateDisabledHomeCatalogKeys(transform: (List<String>) -> List<String>?): Boolean =
        updateCatalogKeys(disabledHomeCatalogKeysKey, transform)

    /**
     * Sets the custom Home title of catalog [key] in one edit of the whole title map (synced in
     * the home-catalog payload): a blank [title], or one equal to [originalName], removes the
     * entry. True when written; false for a read-only profile or no change.
     */
    suspend fun setCustomCatalogTitle(key: String, title: String, originalName: String): Boolean {
        val target = catalogWriteStore(profileManager.activeProfileId.value) ?: return false
        var written = false
        target.edit { prefs ->
            val current = parseCustomTitles(prefs.getStringOrMigrateSet(customCatalogTitlesKey))
            val updated = current.withCustomName(key, title, originalName)
            if (updated == current) return@edit
            if (updated.isEmpty()) {
                prefs.remove(customCatalogTitlesKey)
            } else {
                prefs[customCatalogTitlesKey] = encodeStringMap(updated)
            }
            written = true
        }
        return written
    }

    private suspend fun updateCatalogKeys(
        key: Preferences.Key<String>,
        transform: (List<String>) -> List<String>?
    ): Boolean {
        val target = catalogWriteStore(profileManager.activeProfileId.value) ?: return false
        var written = false
        target.edit { prefs ->
            val stored = parseCatalogKeys(prefs.getStringOrMigrateSet(key))
            val normalizedKeys = transform(stored)?.let(::normalizeCatalogOrderKeys) ?: return@edit
            if (normalizedKeys.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = encodeStringList(normalizedKeys)
            }
            written = true
        }
        return written
    }

    suspend fun setModernLandscapePostersEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernLandscapePostersEnabledKey] = enabled
        }
    }

    suspend fun setDiscoverLocation(location: DiscoverLocation) {
        store().edit { prefs ->
            prefs[discoverLocationKey] = location.name
            if (location != DiscoverLocation.OFF) {
                prefs[lastNonOffDiscoverLocationKey] = location.name
            }
            prefs.remove(legacySearchDiscoverEnabledKey)
        }
    }

    suspend fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogTypeSuffixEnabledKey] = enabled
        }
    }

    /** Writes both stored halves of [mode]; Off resets mute as the old switch's Off did. */
    suspend fun setTrailerAutoplayMode(mode: TrailerAutoplayMode) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerEnabledKey] = mode != TrailerAutoplayMode.OFF
            prefs[trailerCardPreviewKey] = mode == TrailerAutoplayMode.CARD
            if (mode == TrailerAutoplayMode.OFF) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setTrailerCardPreviewDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[trailerCardPreviewDelaySecondsKey] = seconds.coerceIn(TrailerPreviewDelay.MIN_S, TrailerPreviewDelay.MAX_S)
        }
    }

    suspend fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerMutedKey] = muted
        }
    }

    suspend fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurUnwatchedEpisodesKey] = enabled
        }
    }

    suspend fun setHomeImdbRatingsVisibility(visibility: HomeImdbRatingsVisibility) {
        store().edit { prefs ->
            prefs[homeImdbRatingsVisibilityKey] = visibility.name
        }
    }

    suspend fun setDetailImdbRatingsVisibility(visibility: DetailImdbRatingsVisibility) {
        store().edit { prefs ->
            prefs[detailImdbRatingsVisibilityKey] = visibility.asEpisodeVisibility().name
        }
    }

    suspend fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useEpisodeThumbnailsInCwKey] = enabled
        }
    }

    suspend fun setContinueWatchingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[continueWatchingEnabledKey] = enabled
        }
    }

    suspend fun setContinueWatchingCardStyle(style: ContinueWatchingCardStyle) {
        store().edit { prefs ->
            prefs[continueWatchingCardStyleKey] = style.name
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }

    suspend fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[nextUpFromFurthestEpisodeKey] = enabled
        }
    }

    suspend fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurContinueWatchingNextUpKey] = enabled
        }
    }

    suspend fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        store().edit { prefs ->
            prefs[continueWatchingSortModeKey] = mode.name
        }
    }

    suspend fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailPageTrailerButtonEnabledKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
        }
    }

    suspend fun setHideUnreleasedContent(enabled: Boolean) {
        store().edit { prefs ->
            prefs[hideUnreleasedContentKey] = enabled
        }
    }

    suspend fun setShowFullReleaseDate(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showFullReleaseDateKey] = enabled
        }
    }

    private fun parseCatalogKeys(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return decodeStringList(json)?.let(::normalizeCatalogOrderKeys) ?: emptyList()
    }

    private fun normalizeCatalogOrderKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun parseCustomTitles(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return decodeStringMap(json) ?: emptyMap()
    }

    // Sync-facing reads/writes take the synced profile explicitly so a profile switch
    // during a pull cannot redirect them (L9-26/67).
    internal suspend fun getHomeCatalogSettingsState(
        profileId: Int = profileManager.activeProfileId.value
    ): LocalHomeCatalogSettingsState {
        return readHomeCatalogSettingsState(catalogReadStore(profileId).data.first())
    }

    internal suspend fun exportCatalogSettingsToSyncPayload(
        addons: List<Addon>,
        collections: List<Collection>,
        profileId: Int = profileManager.activeProfileId.value
    ): SyncHomeCatalogPayload {
        return buildHomeCatalogSyncPayload(
            addons = addons,
            collections = collections,
            localState = getHomeCatalogSettingsState(profileId)
        )
    }

    suspend fun applyCatalogSettingsFromRemote(
        payload: SyncHomeCatalogPayload,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val sortedItems = payload.items.sortedBy { it.order }
        val orderKeys = sortedItems.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val disabledKeys = sortedItems.filter { !it.enabled }.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val titles = sortedItems.associate { item ->
            val key = if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
            key to item.customTitle
        }.filterValues { it.isNotBlank() }

        val target = catalogWriteStore(profileId) ?: return
        target.edit { prefs ->
            prefs[hideUnreleasedContentKey] = payload.hideUnreleasedContent
            if (orderKeys.isNotEmpty()) {
                prefs[homeCatalogOrderKeysKey] = encodeStringList(orderKeys)
            } else {
                prefs.remove(homeCatalogOrderKeysKey)
            }
            if (disabledKeys.isNotEmpty()) {
                prefs[disabledHomeCatalogKeysKey] = encodeStringList(disabledKeys)
            } else {
                prefs.remove(disabledHomeCatalogKeysKey)
            }
            if (titles.isNotEmpty()) {
                prefs[customCatalogTitlesKey] = encodeStringMap(titles)
            } else {
                prefs.remove(customCatalogTitlesKey)
            }
        }
    }

    private fun readHomeCatalogSettingsState(prefs: Preferences): LocalHomeCatalogSettingsState {
        return LocalHomeCatalogSettingsState(
            orderKeys = parseCatalogKeys(prefs.getStringOrMigrateSet(homeCatalogOrderKeysKey)),
            disabledKeys = parseCatalogKeys(prefs.getStringOrMigrateSet(disabledHomeCatalogKeysKey)).toSet(),
            customTitles = parseCustomTitles(prefs.getStringOrMigrateSet(customCatalogTitlesKey)),
            hideUnreleasedContent = prefs[hideUnreleasedContentKey] ?: false
        )
    }
}

internal val legacySearchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
internal val discoverLocationKey = stringPreferencesKey("discover_location")
internal val lastNonOffDiscoverLocationKey = stringPreferencesKey("last_non_off_discover_location")
