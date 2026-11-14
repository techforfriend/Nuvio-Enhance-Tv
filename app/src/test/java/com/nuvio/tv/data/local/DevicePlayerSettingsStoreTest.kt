package com.nuvio.tv.data.local

import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.stream.ResolutionLimitMode
import com.nuvio.tv.core.stream.StreamLimitMode
import com.nuvio.tv.core.stream.StreamLimitSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation

/** Spec 33 D1: device-wide player store, its one-time seed from profile 1, and the merged read. */
class DevicePlayerSettingsStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val targetMb = intPreferencesKey("target_buffer_size_mb")
    private val backBufferMs = intPreferencesKey("back_buffer_duration_ms")
    private val tunneling = booleanPreferencesKey("tunneling_enabled")
    private val snapshot = stringPreferencesKey("assessment_revert_snapshot")
    private val diagnostics = stringPreferencesKey("last_playback_diagnostics_json")
    private val subtitleSize = intPreferencesKey("subtitle_size")
    private val vodCache = booleanPreferencesKey("vod_cache_enabled")
    private val budgetDone = booleanPreferencesKey("migration_back_buffer_budget_done")
    private val vodSplitDone = booleanPreferencesKey("migration_vod_cache_split_done")

    /** An upgraded profile 1: tuned device keys, every device migration already run there. */
    private val upgradedProfile1: Preferences = preferencesOf(
        targetMb to 300,
        backBufferMs to 15_000,
        tunneling to true,
        snapshot to "{\"targetBufferSizeMb\":150}",
        diagnostics to "{\"url\":\"tokenised\"}",
        subtitleSize to 120,
        vodCache to true,
        vodSplitDone to true
    ).toMutablePreferences().apply {
        DevicePlayerSettingsMigrations.flags.forEach { this[it] = true }
    }.toPreferences()

    private suspend fun seed(profile1: Preferences, current: Preferences = emptyPreferences()): Preferences {
        val migration = deviceSettingsSeedMigration { profile1 }
        assertTrue(migration.shouldMigrate(current))
        return migration.migrate(current)
    }

    @Test
    fun `seed copies device keys and migration flags only`() = runTest {
        val seeded = seed(upgradedProfile1)

        assertEquals(300, seeded[targetMb])
        assertEquals(true, seeded[tunneling])
        assertEquals(upgradedProfile1[snapshot], seeded[snapshot])
        assertEquals(true, seeded[budgetDone])
        assertEquals(true, seeded[DEVICE_STORE_SEEDED_KEY])
        assertNull("diagnostics stay per profile", seeded[diagnostics])
        assertNull("synced keys stay per profile", seeded[subtitleSize])
        assertNull("retired keys are not carried", seeded[vodCache])
        assertTrue(seeded.asMap().keys.all { it.name in DEVICE_PLAYER_KEYS || it.name.startsWith("migration_") })
    }

    @Test
    fun `seed runs once`() = runTest {
        val seeded = seed(upgradedProfile1)
        assertFalse(deviceSettingsSeedMigration { upgradedProfile1 }.shouldMigrate(seeded))
    }

    @Test
    fun `copied flags stop moved migrations re-running, an unmigrated profile still migrates once`() = runTest {
        val migrated = seed(upgradedProfile1)
        assertFalse(DevicePlayerSettingsMigrations.shouldMigrate(migrated))
        assertEquals("deliberate 15 s survives: budget flag came along", 15_000, migrated[backBufferMs])

        val legacy = DevicePlayerSettingsMigrations.migrate(seed(preferencesOf(backBufferMs to 15_000)))
        assertEquals(BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS, legacy[backBufferMs])
        assertFalse(DevicePlayerSettingsMigrations.shouldMigrate(legacy))
    }

    @Test
    fun `fresh install seeds nothing and gets the defaults`() = runTest {
        val fresh = DevicePlayerSettingsMigrations.migrate(seed(emptyPreferences()))
        assertEquals(BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB, fresh[targetMb])
        assertNull(fresh[tunneling])
    }

    @Test
    fun `real store seeds then migrates before the first read`() = runTest {
        val store = realStore(tmpFolder.newFile("device_player_settings.preferences_pb"), backgroundScope) {
            preferencesOf(backBufferMs to 10_000, tunneling to true)
        }
        val first = store.data.first()
        assertEquals(true, first[tunneling])
        assertEquals(BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS, first[backBufferMs])
        assertTrue(DevicePlayerSettingsMigrations.flags.all { first[it] == true })
    }

    @Test
    fun `a failing profile read seeds nothing and the next read retries`() = runTest {
        var fail = true
        val store = realStore(tmpFolder.newFile("retry.preferences_pb"), backgroundScope) {
            if (fail) throw IOException("profile 1 unreadable") else preferencesOf(tunneling to true)
        }
        assertTrue(runCatching { store.data.first() }.isFailure)
        fail = false
        val second = store.data.first()
        assertEquals(true, second[tunneling])
        assertEquals(true, second[DEVICE_STORE_SEEDED_KEY])
    }

    @Test
    fun `corrupt device file resets to defaults without re-copying profile 1`() = runTest {
        val file = tmpFolder.newFile("corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x61))
        val store = realStore(file, backgroundScope) { upgradedProfile1 }
        val read = store.data.first()
        assertNull("no stale snapshot restored", read[snapshot])
        assertNull(read[tunneling])
        assertEquals(BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB, read[targetMb])
    }

    @Test
    fun `merged read takes device keys from the device store only`() {
        val profile = preferencesOf(targetMb to 300, subtitleSize to 120, tunneling to true)
        val device = preferencesOf(targetMb to 200)
        val merged = mergeDevicePlayerPreferences(profile, device)
        assertEquals(200, merged[targetMb])
        assertNull("a stale profile copy never shows through", merged[tunneling])
        assertEquals(120, merged[subtitleSize])
    }

    @Test
    fun `device values hold across a profile switch, profile values follow the profile`() = runTest {
        val p1 = TestPreferencesDataStore()
        val p2 = TestPreferencesDataStore(preferencesOf(subtitleSize to 80))
        val active = MutableStateFlow(1)
        val settings = playerSettingsOver(p1, activeProfileId = active, profileStores = { if (it == 2) p2 else p1 })

        settings.setBufferTargetSizeMb(275)
        settings.setSubtitleSize(140)
        active.value = 2
        val onP2 = settings.playerSettings.first { it.subtitleStyle.size == 80 }
        assertEquals(275, onP2.bufferSettings.targetBufferSizeMb)
        assertEquals(140, p1.value[subtitleSize])
    }

    // Spec 51 P3: an empty store reads the defaults; a stored value from elsewhere reads coerced.
    @Test
    fun `stream limit reads its defaults and coerces a stored manual figure`() = runTest {
        val empty = playerSettingsOver(TestPreferencesDataStore())
        assertEquals(StreamLimitSettings(StreamLimitMode.NETWORK_TEST, 60), empty.streamLimitInputs.first().settings)
        assertEquals(ResolutionLimitMode.THIS_TV, empty.streamLimitInputs.first().resolution.mode)

        val stored = migratedDeviceStore(
            preferencesOf(stringPreferencesKey("stream_limit_mode") to "OFF", intPreferencesKey("stream_limit_manual_mbps") to 2)
        )
        val read = playerSettingsOver(TestPreferencesDataStore(), stored).streamLimitInputs.first().settings
        assertEquals(StreamLimitSettings(StreamLimitMode.OFF, StreamLimitSettings.MIN_MANUAL_MBPS), read)
    }

    // Spec 51 P4: the limit's inputs carry the network tables the device store holds.
    @Test
    fun `stream limit inputs decode the stored network tables`() = runTest {
        val tables = ServiceNetworkTables(
            tables = mapOf(
                "real-debrid.com" to ServiceTable(NetworkTransport.WIFI, 1L, true, listOf(NetworkRow(4, 16, 133.0)))
            )
        )
        val stored = migratedDeviceStore(preferencesOf(stringPreferencesKey("service_network_tables") to tables.encode()))
        assertEquals(tables, playerSettingsOver(TestPreferencesDataStore(), stored).streamLimitInputs.first().tables)
    }

    @Test
    fun `every setter writes its own side and the read reflects device writes`() = runTest {
        val profile = TestPreferencesDataStore()
        val device = migratedDeviceStore()
        val settings = playerSettingsOver(profile, device)
        val called = mutableSetOf<String>()
        suspend fun call(name: String, block: suspend PlayerSettingsDataStore.() -> Unit) {
            called += name
            settings.block()
        }

        // Device side.
        call("setInternalPlayerEngine") { setInternalPlayerEngine(InternalPlayerEngine.AUTO) }
        call("setAutoSwitchInternalPlayerOnError") { setAutoSwitchInternalPlayerOnError(true) }
        call("setDecoderPriority") { setDecoderPriority(2) }
        call("setDownmixEnabled") { setDownmixEnabled(true) }
        call("setAudioOutputChannels") { setAudioOutputChannels(AudioOutputChannels.CHANNELS_5_1) }
        call("setMaintainOriginalAudioOnDownmix") { setMaintainOriginalAudioOnDownmix(false) }
        call("setTunnelingEnabled") { setTunnelingEnabled(true) }
        call("setForceOpticalPassthrough") { setForceOpticalPassthrough(true) }
        call("setAllowAc3Passthrough") { setAllowAc3Passthrough(false) }
        call("setAllowEac3Passthrough") { setAllowEac3Passthrough(false) }
        call("setAllowTrueHdPassthrough") { setAllowTrueHdPassthrough(false) }
        call("setAllowDtsPassthrough") { setAllowDtsPassthrough(false) }
        call("setAllowDtsHdPassthrough") { setAllowDtsHdPassthrough(false) }
        call("recordAudioRejection") { recordAudioRejection("type:hdmi|name:avr::AC3") }
        call("setMatPassthroughEnabled") { setMatPassthroughEnabled(true) }
        call("setAudioAmplificationDb") { setAudioAmplificationDb(4) }
        call("setCenterMixLevelDb") { setCenterMixLevelDb(3) }
        call("setPersistAudioAmplification") { setPersistAudioAmplification(true, 5, 2) }
        call("setRememberAudioDelayPerDevice") { setRememberAudioDelayPerDevice(false) }
        call("setFrameRateMatchingMode") { setFrameRateMatchingMode(FrameRateMatchingMode.START_STOP) }
        call("setEnableBufferLogs") { setEnableBufferLogs(true) }
        call("setResolutionMatchingEnabled") { setResolutionMatchingEnabled(true) }
        call("setAfrSettleHoldSeconds") { setAfrSettleHoldSeconds(5) }
        call("setMpvHardwareDecodeMode") { setMpvHardwareDecodeMode(MpvHardwareDecodeMode.DISABLED) }
        call("setUseLibass") { setUseLibass(true) }
        call("setDv5ToDv81Enabled") { setDv5ToDv81Enabled(true) }
        call("setDv7HandlingMode") { setDv7HandlingMode(Dv7HandlingMode.STRIP_DV) }
        call("setDeniedCodecHandling") { setDeniedCodecHandling(DeniedCodecHandling.TRANSCODE_AC3) }
        call("setDv7LibdoviModeOverride") { setDv7LibdoviModeOverride(2) }
        call("setStripHdr10PlusSei") { setStripHdr10PlusSei(true) }
        call("setInjectHdr10MetadataOnStrip") { setInjectHdr10MetadataOnStrip(true) }
        call("resetBufferSettingsToDefaults") { resetBufferSettingsToDefaults() }
        call("resetNetworkSettingsToDefaults") { resetNetworkSettingsToDefaults() }
        call("setNuvioPerformanceModeEnabled") { setNuvioPerformanceModeEnabled(false) }
        call("setBufferMinBufferMs") { setBufferMinBufferMs(20_000) }
        call("setBufferMaxBufferMs") { setBufferMaxBufferMs(90_000) }
        call("setBufferForPlaybackMs") { setBufferForPlaybackMs(4_000) }
        call("setBufferForPlaybackAfterRebufferMs") { setBufferForPlaybackAfterRebufferMs(6_000) }
        call("setBufferBackBufferDurationMs") { setBufferBackBufferDurationMs(10_000) }
        call("setBufferRetainBackBufferFromKeyframe") { setBufferRetainBackBufferFromKeyframe(false) }
        call("setEnableHttp2") { setEnableHttp2(true) }
        call("setUseParallelConnections") { setUseParallelConnections(true) }
        call("setBufferEngineEnabled") { setBufferEngineEnabled(true) }
        call("setParallelNetworkEnabled") { setParallelNetworkEnabled(true) }
        call("setAllowLargeTargetBuffer") { setAllowLargeTargetBuffer(true) }
        call("setBufferBudgetManaged") { setBufferBudgetManaged(false) }
        call("setParallelConnectionCount") { setParallelConnectionCount(3) }
        call("setParallelChunkSizeKb") { setParallelChunkSizeKb(8192) }
        call("updateMemorySettings") { updateMemorySettings(targetBufferSizeMb = 250) }
        call("setBufferTargetSizeMb") { setBufferTargetSizeMb(260) }
        call("setAssessmentRevertSnapshot") { setAssessmentRevertSnapshot("{}") }
        call("setServiceNetworkTablesJson") { setServiceNetworkTablesJson("{}") }
        call("updateServiceNetworkTablesJson") { updateServiceNetworkTablesJson { "$it+" } }
        call("setDeviceSetupRecordJson") { setDeviceSetupRecordJson("{}") }
        call("setStreamLimitMode") { setStreamLimitMode(StreamLimitMode.MANUAL) }
        call("setStreamLimitManualMbps") { setStreamLimitManualMbps(150) }
        call("setResolutionLimitMode") { setResolutionLimitMode(ResolutionLimitMode.UP_TO_720P) }

        // Profile side.
        call("setSkipSilence") { setSkipSilence(true) }
        call("setPreferredAudioLanguage") { setPreferredAudioLanguage("fr") }
        call("setSecondaryPreferredAudioLanguage") { setSecondaryPreferredAudioLanguage("de") }
        call("setPauseOverlayEnabled") { setPauseOverlayEnabled(true) }
        call("setOsdClockEnabled") { setOsdClockEnabled(false) }
        call("setSkipIntroEnabled") { setSkipIntroEnabled(false) }
        call("setParentalGuideEnabled") { setParentalGuideEnabled(false) }
        call("setAutoSkipSegmentTypeEnabled") { setAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.INTRO, true) }
        call("setLoadingOverlayEnabled") { setLoadingOverlayEnabled(false) }
        call("setShowPlayerLoadingStatus") { setShowPlayerLoadingStatus(false) }
        call("setStreamAutoPlayMode") { setStreamAutoPlayMode(StreamAutoPlayMode.FIRST_STREAM) }
        call("setStreamAutoPlaySource") { setStreamAutoPlaySource(StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) }
        call("setStreamAutoPlaySelectedAddons") { setStreamAutoPlaySelectedAddons(setOf("a")) }
        call("setStreamAutoPlayRegex") { setStreamAutoPlayRegex("2160p") }
        call("setStreamAutoPlayNextEpisodeEnabled") { setStreamAutoPlayNextEpisodeEnabled(true) }
        call("setStreamAutoPlayNextEpisodeFallbackEnabled") { setStreamAutoPlayNextEpisodeFallbackEnabled(false) }
        call("setStreamAutoPlayPreferBingeGroupForNextEpisode") { setStreamAutoPlayPreferBingeGroupForNextEpisode(false) }
        call("setStreamAutoPlayReuseBingeGroup") { setStreamAutoPlayReuseBingeGroup(false) }
        call("setStreamAutoPlayTimeoutSeconds") { setStreamAutoPlayTimeoutSeconds(5) }
        call("setStreamAutoPlayEagerReadyEnabled") { setStreamAutoPlayEagerReadyEnabled(false) }
        call("setStillWatchingEnabled") { setStillWatchingEnabled(true) }
        call("setStillWatchingEpisodeThreshold") { setStillWatchingEpisodeThreshold(4) }
        call("setNextEpisodeThresholdMode") { setNextEpisodeThresholdMode(NextEpisodeThresholdMode.MINUTES_BEFORE_END) }
        call("setNextEpisodeThresholdPercent") { setNextEpisodeThresholdPercent(98f) }
        call("setNextEpisodeThresholdMinutesBeforeEnd") { setNextEpisodeThresholdMinutesBeforeEnd(1f) }
        call("setStreamReuseLastLinkEnabled") { setStreamReuseLastLinkEnabled(true) }
        call("setStreamReuseLastLinkCacheHours") { setStreamReuseLastLinkCacheHours(12) }
        call("setAddonSubtitlesEnabled") { setAddonSubtitlesEnabled(true) }
        call("setSubtitlePreferredLanguage") { setSubtitlePreferredLanguage("es") }
        call("setSubtitleSecondaryLanguage") { setSubtitleSecondaryLanguage("it") }
        call("setSubtitleSize") { setSubtitleSize(130) }
        call("setSubtitleVerticalOffset") { setSubtitleVerticalOffset(8) }
        call("setSubtitleBold") { setSubtitleBold(true) }
        call("setSubtitleTextColor") { setSubtitleTextColor(1) }
        call("setSubtitleBackgroundColor") { setSubtitleBackgroundColor(2) }
        call("setSubtitleEdgeStyle") { setSubtitleEdgeStyle(SubtitleEdgeStyle.DROP_SHADOW) }
        call("setSubtitleOutlineColor") { setSubtitleOutlineColor(3) }
        call("setSubtitleOutlineWidth") { setSubtitleOutlineWidth(4) }
        call("setSubtitleHdrStyleEnabled") { setSubtitleHdrStyleEnabled(true) }
        call("setSubtitleHdrTextColor") { setSubtitleHdrTextColor(5) }
        call("setSubtitleHdrBrightnessPercent") { setSubtitleHdrBrightnessPercent(50) }
        call("setSubtitleHdrDimPlayerControls") { setSubtitleHdrDimPlayerControls(true) }
        call("setUseForcedSubtitles") { setUseForcedSubtitles(true) }
        call("setSubtitleShowOnlyPreferredLanguages") { setSubtitleShowOnlyPreferredLanguages(true) }
        call("setSubtitleStripSdh") { setSubtitleStripSdh(true) }
        call("setLastPlaybackDiagnostics") { setLastPlaybackDiagnostics(LastPlaybackDiagnostics.EMPTY) }

        // Drift guard: a new public writer must join this test (and pick its store).
        val writers = PlayerSettingsDataStore::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filter { it.parameterTypes.lastOrNull() == Continuation::class.java }
            .map { it.name }
            .filter { it.startsWith("set") || it.startsWith("reset") || it.startsWith("record") || it.startsWith("update") }
            .toSet()
        assertEquals(emptySet<String>(), writers - called)

        val leakedToProfile = profile.value.asMap().keys.map { it.name }.filter { it in DEVICE_PLAYER_KEYS }
        assertEquals("device keys written to the profile file", emptyList<String>(), leakedToProfile)
        val leakedToDevice = device.value.asMap().keys.map { it.name }
            .filterNot { it in DEVICE_PLAYER_KEYS || it.startsWith("migration_") }
        assertEquals("profile keys written to the device file", emptyList<String>(), leakedToDevice)

        val read = settings.playerSettings.first()
        assertEquals(InternalPlayerEngine.AUTO, read.internalPlayerEngine)
        assertTrue(read.tunnelingEnabled)
        assertEquals(Dv7HandlingMode.STRIP_DV, read.dv7HandlingMode)
        assertEquals(FrameRateMatchingMode.START_STOP, read.frameRateMatchingMode)
        assertEquals(5, read.afrSettleHoldSeconds)
        assertEquals(setOf("type:hdmi|name:avr::AC3"), read.audioRejectionsSeen)
        assertEquals(260, read.bufferSettings.targetBufferSizeMb)
        assertEquals(90_000, read.bufferSettings.maxBufferMs)
        assertEquals(3, read.parallelConnectionCount)
        assertEquals(8192, read.parallelChunkSizeKb)
        assertTrue(read.enableHttp2)
        assertEquals(130, read.subtitleStyle.size)
        assertTrue(settings.useLibass.first())
        assertEquals("{}", settings.assessmentRevertSnapshot.first())
        assertEquals("{}+", settings.serviceNetworkTablesJson.first())
        assertEquals("{}", settings.deviceSetupRecordJson.first())
        assertEquals(
            StreamLimitSettings(StreamLimitMode.MANUAL, StreamLimitSettings.MAX_MANUAL_MBPS),
            settings.streamLimitInputs.first().settings
        )
        assertEquals(ResolutionLimitMode.UP_TO_720P, settings.streamLimitInputs.first().resolution.mode)
        settings.updateServiceNetworkTablesJson { null }
        assertEquals(null, settings.serviceNetworkTablesJson.first())
        settings.setServiceNetworkTablesJson(null)
        assertEquals(null, settings.serviceNetworkTablesJson.first())
    }

    private fun realStore(file: File, scope: CoroutineScope, readProfile1: suspend () -> Preferences) =
        devicePlayerSettingsDataStore(produceFile = { file }, readProfile1 = readProfile1, scope = scope)
}
