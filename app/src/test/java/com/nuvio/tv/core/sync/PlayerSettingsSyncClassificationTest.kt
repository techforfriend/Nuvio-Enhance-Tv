package com.nuvio.tv.core.sync

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DEVICE_PLAYER_KEYS
import com.nuvio.tv.data.local.DevicePlayerSettingsStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for profile-settings sync (Spec 16 §T26, docs/specs/016-upstream-ports-ak22.md): every key PlayerSettingsDataStore
 * declares is either local-only (shouldExcludePreferenceFromProfileSettingsSync) or listed here as
 * synced. A new key fails this test until it is classified: per-device keys (hardware, network,
 * audio chain, migration flags, diagnostics) go in ProfileSettingsSyncService's local list;
 * preferences the user expects on every TV go below.
 */
class PlayerSettingsSyncClassificationTest {

    private val syncedPlayerKeys = setOf(
        "addon_subtitles_enabled",
        "auto_skip_segment_types",
        "loading_overlay_enabled",
        "next_episode_threshold_minutes_before_end",
        "next_episode_threshold_minutes_before_end_v2",
        "next_episode_threshold_mode",
        "next_episode_threshold_percent",
        "next_episode_threshold_percent_v2",
        "osd_clock_enabled",
        "parental_guide_enabled",
        "pause_overlay_enabled",
        "preferred_audio_language",
        "secondary_preferred_audio_language",
        "show_player_loading_status",
        "skip_intro_enabled",
        "skip_silence",
        "still_watching_enabled",
        "still_watching_episode_threshold",
        "stream_auto_play_eager_ready_enabled",
        "stream_auto_play_mode",
        "stream_auto_play_next_episode_enabled",
        "stream_auto_play_next_episode_fallback_enabled",
        "stream_auto_play_prefer_bingegroup_next_episode",
        "stream_auto_play_regex",
        "stream_auto_play_reuse_binge_group",
        "stream_auto_play_selected_addons",
        "stream_auto_play_source",
        "stream_auto_play_timeout_seconds",
        "stream_reuse_last_link_cache_hours",
        "stream_reuse_last_link_enabled",
        "subtitle_background_color",
        "subtitle_bold",
        "subtitle_edge_style",
        "subtitle_organization_mode",
        "subtitle_outline_color",
        "subtitle_outline_enabled",
        "subtitle_outline_width",
        "subtitle_preferred_language",
        "subtitle_secondary_language",
        "subtitle_show_only_preferred_languages",
        "subtitle_size",
        "subtitle_strip_sdh",
        "subtitle_text_color",
        "subtitle_use_forced_subtitles",
        "subtitle_vertical_offset",
    )

    private fun declaredKeyNames(): Set<String> {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns TestPreferencesDataStore()
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val store = PlayerSettingsDataStore(
            factory, profileManager, mockk<Context>(relaxed = true), DevicePlayerSettingsStore(TestPreferencesDataStore())
        )
        return PlayerSettingsDataStore::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                (field.get(store) as Preferences.Key<*>).name
            }
            .toSet()
    }

    @Test
    fun `every declared player key is either local-only or listed as synced`() {
        val declared = declaredKeyNames()
        // reflection sanity: an empty or partial scan must not pass vacuously
        assertTrue("scanned ${declared.size} keys", declared.size >= 80 && "subtitle_size" in declared)

        val unclassified = declared.filter { name ->
            !shouldExcludePreferenceFromProfileSettingsSync("player_settings", name) && name !in syncedPlayerKeys
        }
        assertEquals("classify these keys (local-only list or syncedPlayerKeys)", emptyList<String>(), unclassified)
        val listedButLocal = syncedPlayerKeys.filter { shouldExcludePreferenceFromProfileSettingsSync("player_settings", it) }
        assertEquals(emptyList<String>(), listedButLocal)
    }

    /**
     * Spec 33 D1: the live local-only keys are exactly the device-wide set, except the per-profile
     * diagnostics (tokenised URL, wiped on sign-out); every device key is declared and local-only.
     */
    @Test
    fun `device keys are exactly the live local-only keys minus diagnostics`() {
        val declared = declaredKeyNames()
        val liveLocal = declared.filter {
            isLocalOnlyProfileSetting("player_settings", it) && !it.startsWith("migration_")
        }.toSet()
        assertEquals(liveLocal - "last_playback_diagnostics_json", DEVICE_PLAYER_KEYS)
        assertTrue("last_playback_diagnostics_json" in declared)
    }
}
