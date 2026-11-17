package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 31 S7d: rows wait for stored values; the placeholders equal the stores' defaults. */
class LayoutSettingsLoadStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** A store whose data never emits: its bindings never load. */
    private object SilentStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { awaitCancellation() }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("not written in this test")
    }

    private val sync = mockk<HomeCatalogSettingsSyncService>(relaxed = true)

    private fun viewModel(
        silentFeature: String? = null,
        badgeSettings: Flow<StreamBadgeSettings> = flowOf(StreamBadgeSettings())
    ): LayoutSettingsViewModel {
        // The stores' stateIn flows call the factory from Dispatchers.IO.
        val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            val feature = secondArg<String>()
            stores.computeIfAbsent("${firstArg<Int>()}/$feature") {
                if (feature == silentFeature) SilentStore else TestPreferencesDataStore()
            }
        }
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val badges = mockk<StreamBadgeSettingsDataStore>(relaxed = true)
        every { badges.settings } returns badgeSettings
        val context = mockk<Context>(relaxed = true)
        // A relaxed InputStream reads 0 bytes forever; the VM swallows this and has no logo.
        every { context.resources.openRawResource(any()) } throws RuntimeException("no resources in JVM tests")
        return LayoutSettingsViewModel(
            context = context,
            layoutPreferenceDataStore = LayoutPreferenceDataStore(factory, profileManager),
            streamBadgeSettingsDataStore = badges,
            trailerSettingsDataStore = TrailerSettingsDataStore(factory, profileManager),
            metaRepository = mockk(relaxed = true),
            homeCatalogSettingsSyncService = sync
        )
    }

    @Test
    fun `loads once every preference has emitted`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun `one silent store keeps the screen unloaded`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(silentFeature = "trailer_settings")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loaded)
    }

    @Test
    fun `silent stream badge settings keep the screen unloaded`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(badgeSettings = emptyFlow())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loaded)
    }

    @Test
    fun `the trailer mode and preview delay events reach the store and come back`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(LayoutSettingsEvent.SetTrailerAutoplayMode(TrailerAutoplayMode.CARD))
        vm.onEvent(LayoutSettingsEvent.SetTrailerCardPreviewDelaySeconds(7))
        advanceUntilIdle()
        assertEquals(TrailerAutoplayMode.CARD, vm.uiState.value.trailerAutoplayMode)
        assertEquals(7, vm.uiState.value.trailerCardPreviewDelaySeconds)
    }

    @Test
    fun `synchronous first values during init still load exactly once init ends`() {
        // Main.immediate-like: every collector runs inside bindTo, before later bindings register.
        // The class rule's finished() resets Main afterwards.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        assertTrue(viewModel().uiState.value.loaded)
        assertFalse(viewModel(silentFeature = "trailer_settings").uiState.value.loaded)
    }

    @Test
    fun `placeholders equal what empty stores read`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(LayoutSettingsUiState(), vm.uiState.value.copy(loaded = false))
    }

    @Test
    fun `toggling hide-unreleased pushes the home-catalog sync blob`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!vm.uiState.value.hideUnreleasedContent))
        advanceUntilIdle()

        verify(exactly = 1) { sync.triggerPush() }
    }
}
