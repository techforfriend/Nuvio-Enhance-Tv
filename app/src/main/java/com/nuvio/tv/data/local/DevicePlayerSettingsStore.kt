package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal const val DEVICE_PLAYER_SETTINGS_STORE_NAME = "device_player_settings"

/**
 * Player keys that belong to this TV, not to a profile (Spec 33 D1): read from and written to
 * [DevicePlayerSettingsStore], so a change on one profile applies to all. Every name is in
 * ProfileSettingsSyncService's localOnlyPlayerProfileSettingsKeys (never synced); that list also
 * holds retired keys (no reader) and last_playback_diagnostics_json (stays per profile: it carries
 * a tokenised stream URL and is wiped on sign-out). PlayerSettingsSyncClassificationTest pins the
 * partition. Legacy keys (downmix_normalization_enabled, map_dv7_to_hevc, frame_rate_matching,
 * parallel_chunk_size_mb) are in: the merged read falls back to them, and some setters still
 * write or remove them in step.
 */
internal val DEVICE_PLAYER_KEYS: Set<String> = setOf(
    "internal_player_engine",
    "auto_switch_internal_player_on_error",
    "use_libass",
    "libass_render_type",
    "decoder_priority",
    "downmix_enabled",
    "audio_output_channels",
    "maintain_original_audio_on_downmix",
    "downmix_normalization_enabled",
    "tunneling_enabled",
    "audio_amplification_db",
    "center_mix_level_db",
    "persist_audio_amplification",
    "remember_audio_delay_per_device",
    "force_optical_passthrough",
    "allow_ac3_passthrough",
    "allow_eac3_passthrough",
    "allow_truehd_passthrough",
    "allow_dts_passthrough",
    "allow_dts_hd_passthrough",
    "denied_codec_handling",
    "audio_rejections_seen",
    "audio_rejections_confirmed",
    "mat_passthrough_enabled",
    "experimental_dv5_to_dv81_enabled",
    "dv7_handling_mode",
    "map_dv7_to_hevc",
    "dv7_libdovi_mode_override",
    "strip_hdr10plus_sei",
    "inject_hdr10_metadata_on_strip",
    "mpv_hardware_decode_mode",
    "frame_rate_matching",
    "frame_rate_matching_mode",
    "resolution_matching_enabled",
    "afr_settle_hold_seconds",
    "external_player_send_skip_segments",
    "use_parallel_connections",
    "buffer_engine_enabled",
    "parallel_network_enabled",
    "allow_large_target_buffer",
    "buffer_budget_managed",
    "parallel_connection_count",
    "parallel_chunk_size_mb",
    "parallel_chunk_size_kb",
    "enable_http2",
    "enable_buffer_logs",
    "min_buffer_ms",
    "max_buffer_ms",
    "buffer_for_playback_ms",
    "buffer_for_playback_after_rebuffer_ms",
    "target_buffer_size_mb",
    "back_buffer_duration_ms",
    "retain_back_buffer_from_keyframe",
    "nuvio_performance_mode_enabled",
    "assessment_revert_snapshot",
    "service_network_tables",
    "device_setup_record",
    "stream_limit_mode",
    "stream_limit_manual_mbps",
    "resolution_limit_mode"
)

/** A migration_* key in player settings is a per-device flag: the seed copies it, sync skips it. */
internal const val PLAYER_MIGRATION_KEY_PREFIX = "migration_"

/** Set once the one-time copy from profile 1 has run (or a corruption reset chose defaults). */
internal val DEVICE_STORE_SEEDED_KEY = booleanPreferencesKey("migration_device_store_seeded_done")

/**
 * Device-wide player settings (Spec 33 D1; decisions Q1/Q2 2026-10-18). Device-scoped by intent:
 * one standalone file, retained on sign-out (ProfileDataStoreFactory.retainedStandaloneDataStoreNames),
 * never touched by profile deletion. Seeded once from profile 1's player_settings file, the copies
 * there are left in place (an older build still reads them, frozen at upgrade time).
 *
 * Migrations run in order before the first read: the seed, then the device-key migrations that
 * used to run per profile. A corrupt file resets to "seeded, no values" so the device migrations
 * restore defaults instead of re-copying stale profile-1 values (incl. an old revert snapshot).
 */
@Singleton
class DevicePlayerSettingsStore internal constructor(
    internal val store: DataStore<Preferences>
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        factory: ProfileDataStoreFactory
    ) : this(
        devicePlayerSettingsDataStore(
            produceFile = { context.preferencesDataStoreFile(DEVICE_PLAYER_SETTINGS_STORE_NAME) },
            readProfile1 = { factory.get(1, PlayerSettingsDataStore.FEATURE).data.first() }
        )
    )
}

/** The store's whole configuration, one home for the app and its real-file tests. */
internal fun devicePlayerSettingsDataStore(
    produceFile: () -> File,
    readProfile1: suspend () -> Preferences,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = loggedResetCorruptionHandler(
        DEVICE_PLAYER_SETTINGS_STORE_NAME,
        resetTo = preferencesOf(DEVICE_STORE_SEEDED_KEY to true)
    ),
    migrations = listOf(deviceSettingsSeedMigration(readProfile1), DevicePlayerSettingsMigrations),
    scope = scope,
    produceFile = produceFile
)

/**
 * The one read view: the profile's own entries, with every DEVICE_PLAYER_KEYS entry taken from
 * the device store (a stale copy left in the profile file never shows through).
 */
internal fun mergeDevicePlayerPreferences(profile: Preferences, device: Preferences): Preferences {
    val merged = profile.toMutablePreferences()
    merged.asMap().keys.filter { it.name in DEVICE_PLAYER_KEYS }.forEach { merged.remove(it) }
    merged += device
    return merged.toPreferences()
}

/**
 * Copies profile 1's device keys and every migration_* flag (the flags record which migrations
 * already ran over those values; the retired vod-cache flag rides along harmlessly). A read error
 * propagates: DataStore keeps no flag and retries the whole migration on the next read.
 */
internal fun deviceSettingsSeedMigration(
    readProfile1: suspend () -> Preferences
): DataMigration<Preferences> = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[DEVICE_STORE_SEEDED_KEY] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val copied = readProfile1().toMutablePreferences()
        copied.asMap().keys
            .filterNot { it.name in DEVICE_PLAYER_KEYS || it.name.startsWith(PLAYER_MIGRATION_KEY_PREFIX) }
            .forEach { copied.remove(it) }
        copied += currentData
        copied[DEVICE_STORE_SEEDED_KEY] = true
        return copied.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * The device-key migrations, moved from PlayerSettingsDataStore.migrateProfile (Spec 33 D1) with
 * their flags and bodies unchanged. Key names are written out here on purpose: a migration tests
 * the values that were current when it was authored, and the names are the persisted contract.
 */
internal object DevicePlayerSettingsMigrations : DataMigration<Preferences> {
    private val minBufferMs = intPreferencesKey("min_buffer_ms")
    private val maxBufferMs = intPreferencesKey("max_buffer_ms")
    private val bufferForPlaybackMs = intPreferencesKey("buffer_for_playback_ms")
    private val bufferForPlaybackAfterRebufferMs = intPreferencesKey("buffer_for_playback_after_rebuffer_ms")
    private val targetBufferSizeMb = intPreferencesKey("target_buffer_size_mb")
    private val backBufferDurationMs = intPreferencesKey("back_buffer_duration_ms")
    private val retainBackBufferFromKeyframe = booleanPreferencesKey("retain_back_buffer_from_keyframe")
    private val audioRejectionsSeen = stringSetPreferencesKey("audio_rejections_seen")
    private val audioRejectionsConfirmed = stringSetPreferencesKey("audio_rejections_confirmed")

    private val loadControlDefaultsAlignedDone = booleanPreferencesKey("migration_load_control_defaults_aligned_done")
    private val loadControlDefaultsRetunedDone = booleanPreferencesKey("migration_load_control_defaults_retuned_done")
    private val loadControlMinBufferRetunedDone = booleanPreferencesKey("migration_load_control_min_buffer_retuned_done")
    private val backBufferDurationBumpedDone = booleanPreferencesKey("migration_back_buffer_duration_bumped_done")
    private val retainBackBufferWiredDone = booleanPreferencesKey("migration_retain_back_buffer_wired_done")
    private val maxBufferBumpedDone = booleanPreferencesKey("migration_max_buffer_bumped_done")
    private val targetBufferSizeBumpedDone = booleanPreferencesKey("migration_target_buffer_size_bumped_done")
    private val afterRebufferLoweredDone = booleanPreferencesKey("migration_after_rebuffer_lowered_done")
    private val backBufferDurationReducedDone = booleanPreferencesKey("migration_back_buffer_duration_reduced_done")
    private val backBufferBudgetDone = booleanPreferencesKey("migration_back_buffer_budget_done")
    private val targetBufferSizeReducedDone = booleanPreferencesKey("migration_target_buffer_size_reduced_done")
    private val audioRejectionsResetDone = booleanPreferencesKey("migration_audio_rejections_reset_done")

    internal val flags = listOf(
        loadControlDefaultsAlignedDone, loadControlDefaultsRetunedDone, loadControlMinBufferRetunedDone,
        backBufferDurationBumpedDone, retainBackBufferWiredDone, maxBufferBumpedDone,
        targetBufferSizeBumpedDone, afterRebufferLoweredDone, backBufferDurationReducedDone,
        backBufferBudgetDone, targetBufferSizeReducedDone, audioRejectionsResetDone
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        if (flags.any { currentData[it] != true }) return true
        val min = currentData[minBufferMs]
        val max = currentData[maxBufferMs]
        return min != null && max != null && max < min
    }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().also(::runAll).toPreferences()

    override suspend fun cleanUp() = Unit

    private fun runAll(prefs: MutablePreferences) {
        val loadControlMigrated = prefs[loadControlDefaultsAlignedDone] ?: false
        if (!loadControlMigrated) {
            val currentMin = prefs[minBufferMs]
            val currentMax = prefs[maxBufferMs]
            val legacyDefaultsDetected = (currentMin == null && currentMax == null) || (currentMin == 15_000 && currentMax == 25_000)
            if (legacyDefaultsDetected) {
                prefs[minBufferMs] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                prefs[maxBufferMs] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            }
            prefs[loadControlDefaultsAlignedDone] = true
        }

        val loadControlRetuned = prefs[loadControlDefaultsRetunedDone] ?: false
        if (!loadControlRetuned) {
            val currentMin = prefs[minBufferMs]
            val currentMax = prefs[maxBufferMs]
            val currentPlayback = prefs[bufferForPlaybackMs]
            val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMs]
            val currentTargetBuffer = prefs[targetBufferSizeMb]

            val previousDefaultsDetected = currentMin == 50_000 && currentMax == 50_000 && currentPlayback == 2_500 && currentPlaybackAfterRebuffer == 5_000 && currentTargetBuffer == 0
            val olderDefaultsDetected = currentMin == 15_000 && currentMax == 25_000

            if (previousDefaultsDetected || olderDefaultsDetected) {
                prefs[minBufferMs] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                prefs[maxBufferMs] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                prefs[bufferForPlaybackMs] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                prefs[bufferForPlaybackAfterRebufferMs] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                prefs[targetBufferSizeMb] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            }
            prefs[loadControlDefaultsRetunedDone] = true
        }

        val minBufferRetuned = prefs[loadControlMinBufferRetunedDone] ?: false
        if (!minBufferRetuned) {
            val currentMin = prefs[minBufferMs]
            val currentMax = prefs[maxBufferMs]
            val currentPlayback = prefs[bufferForPlaybackMs]
            val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMs]
            val currentTargetBuffer = prefs[targetBufferSizeMb]
            val currentBackBuffer = prefs[backBufferDurationMs]
            val currentRetainBackBuffer = prefs[retainBackBufferFromKeyframe]

            // Clauses below deliberately test LITERALS, not BufferSettings
            // constants: this predicate must keep detecting the defaults that were
            // current when the migration was authored. Comparing against a live
            // constant means any later default change silently narrows the match
            // and strands users on legacy values.
            val previousRetunedDefaultsDetected = currentMin == 50_000 && currentMax == 50_000 && currentPlayback == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS && currentPlaybackAfterRebuffer == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS && currentTargetBuffer == BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB && (currentBackBuffer == null || currentBackBuffer == 15_000) && (currentRetainBackBuffer == null || !currentRetainBackBuffer)

            if (previousRetunedDefaultsDetected) prefs[minBufferMs] = BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[loadControlMinBufferRetunedDone] = true
        }

        // Back buffer bump from prior 10s default.
        val backBufferBumped = prefs[backBufferDurationBumpedDone] ?: false
        if (!backBufferBumped) {
            val currentBackBuffer = prefs[backBufferDurationMs]
            if (currentBackBuffer == null || currentBackBuffer == 10_000) {
                prefs[backBufferDurationMs] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            }
            prefs[backBufferDurationBumpedDone] = true
        }

        // Retain-from-keyframe wiring: pre-wiring builds hardcoded true at
        // every LoadControl site while this key sat unread, so any persisted
        // false is an artifact (reset-defaults was the only writer). Normalize
        // it once, so the now-honored setting starts every user from the
        // behavior they actually experienced; later false writes
        // (assessment/preset apply) are intentional and honored.
        val retainBackBufferWired = prefs[retainBackBufferWiredDone] ?: false
        if (!retainBackBufferWired) {
            if (prefs[retainBackBufferFromKeyframe] == false) {
                prefs[retainBackBufferFromKeyframe] = true
            }
            prefs[retainBackBufferWiredDone] = true
        }

        // Max buffer bump from prior 30s default.
        val maxBufferBumped = prefs[maxBufferBumpedDone] ?: false
        if (!maxBufferBumped) {
            val currentMax = prefs[maxBufferMs]
            if (currentMax == null || currentMax == 30_000) {
                prefs[maxBufferMs] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            }
            prefs[maxBufferBumpedDone] = true
        }

        // Target buffer bump from prior 100MB default.
        val targetBufferBumped = prefs[targetBufferSizeBumpedDone] ?: false
        if (!targetBufferBumped) {
            val currentTarget = prefs[targetBufferSizeMb]
            if (currentTarget == null || currentTarget == 100) {
                prefs[targetBufferSizeMb] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            }
            prefs[targetBufferSizeBumpedDone] = true
        }

        // After-rebuffer threshold lowered from prior 5s default for faster resume.
        val afterRebufferLowered = prefs[afterRebufferLoweredDone] ?: false
        if (!afterRebufferLowered) {
            val currentAfter = prefs[bufferForPlaybackAfterRebufferMs]
            if (currentAfter == null || currentAfter == 5_000) {
                prefs[bufferForPlaybackAfterRebufferMs] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            }
            prefs[afterRebufferLoweredDone] = true
        }

        // Back buffer reduced from interim 30s due to heap pressure on high-bitrate content.
        val backBufferReduced = prefs[backBufferDurationReducedDone] ?: false
        if (!backBufferReduced) {
            val currentBack = prefs[backBufferDurationMs]
            if (currentBack == null || currentBack == 30_000) {
                prefs[backBufferDurationMs] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            }
            prefs[backBufferDurationReducedDone] = true
        }

        // Back buffer reduced 15s -> 5s: back-buffer bytes compete with the
        // forward buffer inside one allocator budget, so at remux bitrates the
        // 15s value could consume the entire byte target. Value-gated on the
        // exact prior default, so a deliberately chosen value is left alone.
        val backBufferBudgetFixed = prefs[backBufferBudgetDone] ?: false
        if (!backBufferBudgetFixed) {
            val currentBackBudget = prefs[backBufferDurationMs]
            if (currentBackBudget == null || currentBackBudget == 15_000) {
                prefs[backBufferDurationMs] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            }
            prefs[backBufferBudgetDone] = true
        }

        // Corrects users from a 125 interim build back to the 150 default.
        val targetBufferCorrected = prefs[targetBufferSizeReducedDone] ?: false
        if (!targetBufferCorrected) {
            val currentTarget = prefs[targetBufferSizeMb]
            if (currentTarget == 125) {
                prefs[targetBufferSizeMb] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            }
            prefs[targetBufferSizeReducedDone] = true
        }

        // F3 entries learned before T12 (Register A2 P1) counted 5002 write failures,
        // PCM refusals under a compressed renderer format and HDMI outages, and nothing
        // showed or cleared them. Forget them once; a genuine refusal relearns in two
        // sessions under the outage guard.
        if (prefs[audioRejectionsResetDone] != true) {
            prefs.remove(audioRejectionsSeen)
            prefs.remove(audioRejectionsConfirmed)
            prefs[audioRejectionsResetDone] = true
        }

        val min = prefs[minBufferMs]
        val max = prefs[maxBufferMs]
        if (min != null && max != null && max < min) prefs[maxBufferMs] = min
    }
}
