package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.assessment.NetworkRow
import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import com.nuvio.tv.core.network.NetworkTransportReader
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.stream.ServiceLimitFigure
import com.nuvio.tv.core.stream.StreamLimitInputs
import com.nuvio.tv.core.stream.StreamLimitMode
import com.nuvio.tv.core.stream.StreamLimitSettings
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Spec 33 D5: a network row changed by hand in Settings turns Automatic network off (the
 * ViewModel handlers, not the store setters the applier shares); buffer rows and turning
 * Custom network on do not, and an unchanged table is not rewritten. Spec 51 P7: the group's
 * state carries the stream list limit.
 */
class PlaybackSettingsThisTvTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private var tablesJson: String? = null
    private var tableWrites = 0
    private val store = mockk<PlayerSettingsDataStore>(relaxed = true)
    private lateinit var vm: PlaybackSettingsViewModel

    @Before
    fun setUp() {
        mockkObject(SeekThumbnailPreferences)
        every { SeekThumbnailPreferences.enabledFlow(any()) } returns flowOf(false)
        every { store.playerSettings } returns flowOf(PlayerSettings())
        every { store.hideSourceNames } returns flowOf(false)
        coEvery { store.updateServiceNetworkTablesJson(any()) } answers {
            val next = firstArg<(String?) -> String?>()(tablesJson)
            if (next != tablesJson) tableWrites++
            tablesJson = next
        }
        tablesJson = ServiceNetworkTables(automatic = true).encode()
        val addons = mockk<AddonRepository>(relaxed = true)
        every { addons.getInstalledAddons() } returns flowOf(emptyList())
        vm = PlaybackSettingsViewModel(mockk<Context>(relaxed = true), store, addons, mockk<TorrentSettings>(relaxed = true))
    }

    @After
    fun tearDown() = unmockkObject(SeekThumbnailPreferences)

    private fun automatic() = ServiceNetworkTables.decode(tablesJson).automatic

    @Test
    fun everyNetworkRowChangedByHandTurnsAutomaticOff() = runTest {
        val handlers: List<suspend () -> Unit> = listOf(
            { vm.setEnableHttp2(true) },
            { vm.setParallelNetworkEnabled(false) },
            { vm.setUseParallelConnections(true) },
            { vm.setParallelConnectionCount(3) },
            { vm.setParallelChunkSizeKb(8192) },
            { vm.resetNetworkSettingsToDefaults() }
        )
        handlers.forEachIndexed { i, handler ->
            tablesJson = ServiceNetworkTables(automatic = true).encode()
            handler()
            assertFalse("handler $i", automatic())
        }
    }

    @Test
    fun bufferRowsAndCustomNetworkOnLeaveAutomaticOn() = runTest {
        vm.setParallelNetworkEnabled(true)
        vm.setBufferTargetSizeMb(200)
        vm.setBufferEngineEnabled(true)
        vm.setBufferBudgetManaged(false)
        vm.setNuvioPerformanceModeEnabled(true)
        vm.resetBufferSettingsToDefaults()
        assertTrue(automatic())
        assertEquals(0, tableWrites)
    }

    /** Spec 51 P7: the Stream list limit row reads the limit and the services that count on this link. */
    @Test
    fun thisTvStateCarriesTheStreamLimitAndTheServicesCountingOnThisLink() = runTest {
        mockkObject(NetworkTransportReader)
        try {
            every { NetworkTransportReader.current(any()) } returns NetworkTransport.WIFI
            val wifi = ServiceTable(NetworkTransport.WIFI, rows = listOf(NetworkRow(4, 16, 132.4)))
            val tables = ServiceNetworkTables(
                tables = mapOf(
                    "real-debrid.com" to wifi,
                    "premiumize.me" to wifi.copy(transport = NetworkTransport.ETHERNET)
                )
            )
            val limit = StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40)
            every { store.streamLimitInputs } returns flowOf(StreamLimitInputs(limit, tables))
            every { store.assessmentRevertSnapshot } returns flowOf(null)
            every { store.deviceSetupRecordJson } returns flowOf(null)
            val torrent = mockk<TorrentSettings>(relaxed = true)
            every { torrent.settings } returns flowOf(TorrentSettingsData())
            val addons = mockk<AddonRepository>(relaxed = true)
            every { addons.getInstalledAddons() } returns flowOf(emptyList())
            val thisTv = PlaybackSettingsViewModel(mockk<Context>(relaxed = true), store, addons, torrent)
                .uiState.first { it != null }!!.thisTv
            assertEquals(tables, thisTv.tables)
            assertEquals(limit, thisTv.streamLimit)
            assertEquals(listOf(ServiceLimitFigure("real-debrid.com", 106, 132)), thisTv.limitFigures)
        } finally {
            unmockkObject(NetworkTransportReader)
        }
    }

    @Test
    fun anUnchangedTableIsNotRewritten() = runTest {
        tablesJson = ServiceNetworkTables(automatic = false).encode()
        val before = tablesJson
        vm.setEnableHttp2(false)
        assertSame(before, tablesJson)
        assertEquals(0, tableWrites)
    }
}
