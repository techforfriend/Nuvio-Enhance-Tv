package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.update.DownloadState
import com.nuvio.tv.core.update.KnownRelease
import com.nuvio.tv.core.update.UpdateController
import com.nuvio.tv.core.update.UpdateState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Spec 52 S8: the update panel opens on a known release, follows the controller, and closing it stops a download. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val release = KnownRelease(
        versionName = "0.9.0-ak51",
        versionCode = 1392,
        abi = "armeabi-v7a",
        downloadUrl = "https://github.com/Arshad-Kamal/Nuvio-AK/releases/download/v/a.apk",
        sizeBytes = 41_000_000L,
        sha256 = "ab".repeat(32),
        notes = ""
    )
    private val state = MutableStateFlow(UpdateState(supported = true, available = release))
    private val controller = mockk<UpdateController>(relaxed = true) {
        every { state } returns this@UpdateViewModelTest.state
        every { notice } returns MutableStateFlow(true)
    }

    @Test
    fun `the panel opens on the known release, and not without one`() = runTest {
        val viewModel = UpdateViewModel(controller)
        viewModel.openPanel()
        assertEquals(release, viewModel.panelRelease.value)

        viewModel.closePanel()
        state.update { it.copy(available = null) }
        runCurrent()
        viewModel.openPanel()
        assertNull(viewModel.panelRelease.value)
    }

    @Test
    fun `closing the panel stops the download`() = runTest {
        val viewModel = UpdateViewModel(controller)
        viewModel.openPanel()
        state.update { it.copy(download = DownloadState.Running(10L, 41L)) }
        runCurrent()
        viewModel.closePanel()
        assertNull(viewModel.panelRelease.value)
        verify(exactly = 1) { controller.cancelDownload() }
    }

    @Test
    fun `an open panel keeps a dropped release until the download ends, a closed one never reopens`() = runTest {
        val viewModel = UpdateViewModel(controller)
        runCurrent()
        viewModel.openPanel()
        state.update { it.copy(available = null, download = DownloadState.Failed) }
        runCurrent()
        assertEquals(release, viewModel.panelRelease.value)

        state.update { it.copy(download = DownloadState.Idle) }
        runCurrent()
        assertNull(viewModel.panelRelease.value)

        state.update { it.copy(available = release) }
        runCurrent()
        assertNull(viewModel.panelRelease.value)
    }
}
