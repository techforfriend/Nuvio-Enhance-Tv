package com.nuvio.tv.ui.components.trailerpreview

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spec 96 PD4/PD6: stage 2 holds the screensaver's playbackActive; the screensaver cancels and resumes. */
class TrailerPreviewViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val screensaver = ScreensaverController(mockk(relaxed = true))
    private val candidate = TrailerPreviewCandidate("a", "tt1", "Spring", "2019", "movie", null)

    private fun viewModel(): TrailerPreviewViewModel {
        val service = mockk<TrailerService>()
        coEvery { service.getTrailerPlaybackSourceForItem(any(), any(), any(), any()) } returns
            TrailerPlaybackSource("https://v/spring")
        val prefs = mockk<LayoutPreferenceDataStore>()
        every { prefs.trailerAutoplayMode } returns flowOf(TrailerAutoplayMode.CARD)
        every { prefs.trailerCardPreviewDelaySeconds } returns flowOf(DELAY_S)
        val trailerSettings = mockk<TrailerSettingsDataStore>()
        every { trailerSettings.settings } returns flowOf(TrailerSettings())
        return TrailerPreviewViewModel(service, prefs, screensaver, trailerSettings).also { it.resolveDispatcher = main.dispatcher }
    }

    private fun TestScope.toPanel(vm: TrailerPreviewViewModel) {
        runCurrent()
        vm.machine.focus(candidate)
        advanceTimeBy(DELAY_MS + 1)
        runCurrent()
        vm.machine.onFirstFrame()
        advanceTimeBy(DELAY_MS + 1)
        runCurrent()
    }

    @Test
    fun `stage 2 holds playbackActive and releases it on close`() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        toPanel(vm)
        assertEquals(TrailerPreviewStage.PANEL, vm.machine.state.value?.stage)
        assertTrue(screensaver.playbackActive.value)
        vm.machine.onKeyDown(TrailerPreviewKey.BACK)
        runCurrent()
        assertFalse(screensaver.playbackActive.value)
    }

    @Test
    fun `playbackActive set by someone else is left alone`() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        screensaver.setPlaybackActive(true)
        toPanel(vm)
        vm.machine.onKeyDown(TrailerPreviewKey.BACK)
        runCurrent()
        assertTrue(screensaver.playbackActive.value)
    }

    @Test
    fun `the screensaver engaging closes a shown preview`() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        runCurrent()
        vm.machine.focus(candidate)
        advanceTimeBy(DELAY_MS + 1)
        runCurrent()
        vm.machine.onFirstFrame()
        assertEquals(TrailerPreviewStage.CARD, vm.machine.state.value?.stage)
        screensaver.maybeEngage(timeoutMs = 0)
        runCurrent()
        assertNull(vm.machine.state.value)
    }

    @Test
    fun `the screensaver ending starts a fresh dwell`() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        runCurrent()
        vm.machine.focus(candidate)
        advanceTimeBy(1_000)
        screensaver.maybeEngage(timeoutMs = 0)
        runCurrent()
        advanceTimeBy(60_000)
        assertNull(vm.machine.state.value) // nothing fetched under the screensaver
        screensaver.notifyWake()
        runCurrent()
        advanceTimeBy(DELAY_MS + 1)
        runCurrent()
        assertEquals(TrailerPreviewStage.LOADING, vm.machine.state.value?.stage)
    }

    @Test
    fun `the stored Preview delay times the wait`() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        runCurrent()
        vm.machine.focus(candidate)
        val settle = trailerPreviewSettleMs(DELAY_MS) // 666 ms; the 3 s default's would be 1 s
        advanceTimeBy(settle - 1)
        runCurrent()
        assertNull(vm.machine.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(TrailerPreviewStage.LOADING, vm.machine.state.value?.stage)
        assertEquals(true, vm.machine.state.value?.paused)
        advanceTimeBy(DELAY_MS - settle)
        runCurrent()
        assertEquals(false, vm.machine.state.value?.paused) // the 3 s default would still hold it
    }

    @Test
    fun `clearing the ViewModel releases a held playbackActive`() = runTest(main.dispatcher.scheduler) {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel() as T
        })[TrailerPreviewViewModel::class.java]
        toPanel(vm)
        assertTrue(screensaver.playbackActive.value)
        store.clear()
        assertFalse(screensaver.playbackActive.value)
    }
}

// Not the 3 s default, so the tests show the stored setting is read (D12).
private const val DELAY_S = 2
private const val DELAY_MS = DELAY_S * 1_000L
