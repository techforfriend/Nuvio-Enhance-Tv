package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.source.PackScanStore
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.livetv.source.PackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceFetcher
import com.nuvio.tv.livetv.source.SourceKind
import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.PackSummary
import com.nuvio.tv.livetv.source.PackChannel
import com.nuvio.tv.livetv.source.PackState
import com.nuvio.tv.livetv.source.PackStream
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 34 L7e Add source dialog: check-then-add state machine (Idle, Checking, Ready, Refused, Adding). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSourcesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = UnconfinedTestDispatcher()
    private val sourceList = MutableStateFlow<List<LiveSource>>(emptyList())
    private val packSaves = MutableStateFlow(0)
    private val manager = mockk<LiveSourceManager>().also {
        every { it.sources } returns sourceList
        every { it.packSaves } returns packSaves
        coEvery { it.load() } just runs
        coEvery { it.packSummary() } returns null
    }
    private val source = LiveSource("s1", SourceKind.M3U, "example.org", "https://example.org/list.m3u", 0L, 0L, channelCount = 3)
    private val checked = LiveSourceManager.Checked(source, 3, LiveSourceManager.Loaded.Channels(emptyList()))

    /** A real scanner over this test's folder that never probes (S3b's tests drive the scan UI). */
    private fun scanner() = PackScanner(
        PackStore(tmp.root, io),
        PackScanStore(tmp.root, io),
        { _, _, _ -> true },
        { _, _ -> false },
        flowOf(false),
        CoroutineScope(SupervisorJob() + io),
        { 0L }
    )

    private fun viewModel() = LiveTvSourcesViewModel(
        manager,
        ChannelRepository(tmp.root, { 0L }, io),
        GuideRepository(tmp.root, io),
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
        HiddenChannelStore(tmp.root, io),
        scanner(),
        PackStore(tmp.root, io),
        PackScanStore(tmp.root, io)
    )

    @Test
    fun `a passed check enables Add, and editing the URL drops it`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
        val vm = viewModel()
        vm.checkAdd()
        assertEquals(AddCheck.Idle, vm.addCheck.value) // nothing typed: no check
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        assertEquals(AddCheck.Checking, vm.addCheck.value)
        advanceUntilIdle()
        assertEquals(AddCheck.Ready(checked), vm.addCheck.value)
        vm.onAddUrlChange(source.url + "x")
        assertEquals(AddCheck.Idle, vm.addCheck.value)
    }

    @Test
    fun `an add in progress survives edits and closing, then reports Added`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.add(checked) } coAnswers { gate.await() }
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        vm.confirmAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Adding, vm.addCheck.value)
        vm.onAddUrlChange("https://other.example/")
        vm.closeAdd()
        vm.openAdd()
        assertEquals(AddCheck.Adding, vm.addCheck.value)
        assertEquals(source.url, vm.addUrl.value)

        gate.complete(LiveSourceManager.AddResult.Added(source))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added("s1", "example.org"), vm.event.first())
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        assertEquals("", vm.addUrl.value)
    }

    @Test
    fun `a refused check or add shows the reason and can be checked again`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.check(any()) } returns LiveSourceManager.CheckResult.Refused("HTTP 403") andThen
            LiveSourceManager.CheckResult.Ready(checked)
        coEvery { manager.add(checked) } returns LiveSourceManager.AddResult.Refused("Already added")
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("HTTP 403"), vm.addCheck.value)
        vm.confirmAdd() // Add is dimmed: nothing happens
        assertEquals(AddCheck.Refused("HTTP 403"), vm.addCheck.value)
        vm.checkAdd()
        advanceUntilIdle()
        vm.confirmAdd()
        advanceUntilIdle()
        assertEquals(AddCheck.Refused("Already added"), vm.addCheck.value)
    }

    private val packSource = LiveSource(ChannelPack.SOURCE_ID, SourceKind.PACK, ChannelPack.NAME, ChannelPack.BASE_URL, 0L, 0L)

    @Test
    fun `the pack is offered with its figures until it is added`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.packSummary() } returns PackSummary(21037, 177, 1_600_000)
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        assertEquals(PackOffer(PackSummary(21037, 177, 1_600_000)), vm.packOffer.value)
        sourceList.value = listOf(packSource)
        advanceUntilIdle()
        assertNull(vm.packOffer.value)
        // Removed here: offered again, figures already read.
        sourceList.value = emptyList()
        advanceUntilIdle()
        assertEquals(PackOffer(PackSummary(21037, 177, 1_600_000)), vm.packOffer.value)
        coVerify(exactly = 1) { manager.packSummary() }
    }

    @Test
    fun `the pack already added is not offered and its figures are not fetched`() = runTest(mainDispatcherRule.dispatcher) {
        sourceList.value = listOf(packSource)
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        assertNull(vm.packOffer.value)
        coVerify(exactly = 0) { manager.packSummary() }
    }

    @Test
    fun `Add pack reports Added or the refusal, one download at a time`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.AddResult>()
        coEvery { manager.addPack() } coAnswers { gate.await() } andThen LiveSourceManager.AddResult.Refused("HTTP 404")
        val vm = viewModel()
        backgroundScope.launch { vm.packOffer.collect {} }
        advanceUntilIdle()
        vm.addPack()
        advanceUntilIdle()
        assertTrue(vm.packOffer.value!!.adding)
        vm.addPack() // ignored while the first runs
        gate.complete(LiveSourceManager.AddResult.Added(packSource))
        advanceUntilIdle()
        assertEquals(SourcesEvent.Added(ChannelPack.SOURCE_ID, ChannelPack.NAME), vm.event.first())
        assertEquals(false, vm.packOffer.value!!.adding)
        vm.addPack()
        advanceUntilIdle()
        assertEquals(SourcesEvent.PackAddFailed("HTTP 404"), vm.event.first())
        coVerify(exactly = 2) { manager.addPack() }
    }

    @Test
    fun `the scan facts wait for the pack's file, not its record, and follow each download`() = runTest(mainDispatcherRule.dispatcher) {
        val saves = MutableStateFlow(0)
        val running = MutableStateFlow(false)
        var onDisk = emptyList<PackChannel>() // the pack file as the read sees it
        val reads = mutableListOf<Int>()
        val facts = MutableStateFlow<PackScanFacts?>(null)
        // Not backgroundScope: advanceUntilIdle does not run a queue of background tasks alone.
        val collecting = launch {
            packScanFacts(sourceList, saves, running) {
                reads += saves.value
                PackScanModel.facts(onDisk, emptyMap(), null, null)
            }.collect { facts.value = it }
        }
        advanceUntilIdle()
        assertNull(facts.value)
        // An add writes the record, then the file, then counts the download (S3c: keyed on the
        // record alone, the list read the missing file once and opened empty for good).
        sourceList.value = listOf(packSource)
        advanceUntilIdle()
        assertNull(facts.value)
        onDisk = listOf(packChannel("a", "ca"))
        saves.value = 1
        advanceUntilIdle()
        assertEquals(setOf<String?>("ca"), facts.value!!.world)
        // A refresh's completed download and a scan's start and end are read again; nothing else is.
        onDisk = onDisk + packChannel("b", "us")
        saves.value = 2
        advanceUntilIdle()
        assertEquals(setOf<String?>("ca", "us"), facts.value!!.world)
        running.value = true
        advanceUntilIdle()
        running.value = false
        advanceUntilIdle()
        sourceList.value = listOf(packSource.copy(lastRefreshMs = 5L))
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 2, 2), reads)
        // Removed: no facts.
        sourceList.value = emptyList()
        advanceUntilIdle()
        assertNull(facts.value)
        collecting.cancel()
    }

    private fun packChannel(key: String, country: String) =
        PackChannel("pack#ch:$key", key, country = country, streams = listOf(PackStream("https://x/$key", PackState.ALIVE)))

    @Test
    fun `a scan that ends while Sources is open is reported for its result panel`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.startScan(setOf("ca")))
        advanceUntilIdle()
        val event = vm.event.first()
        assertTrue("$event", event is SourcesEvent.ScanEnded && event.record.countries == listOf("ca") && !event.record.cancelled)
    }

    private val pluto = LiveSourceManager.GUIDE_PRESETS.single { it.key == "pluto" }
    private val india = LiveSourceManager.GUIDE_PRESETS.single { it.key == "in" }

    /** A view model whose playlists offer the Pluto and India presets. */
    private suspend fun offering(): LiveTvSourcesViewModel {
        val playlist = LiveSource("p", SourceKind.M3U, "List", "https://example.org/p.m3u", 0L, 1L)
        every { manager.sources } returns MutableStateFlow(listOf(playlist))
        val repo = ChannelRepository(tmp.root, { 0L }, io)
        repo.replaceSource(
            "p",
            listOf(
                Channel("p#a", "p", "A", streams = listOf(LiveStream("https://jmp2.uk/plu-5ee92e72fb286e0007285fec.m3u8"))),
                Channel("p#b", "p", "B", country = "in", streams = listOf(LiveStream("https://example.org/b.m3u8")))
            )
        )
        return LiveTvSourcesViewModel(
            manager,
            repo,
            GuideRepository(tmp.root, io),
            IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
            HiddenChannelStore(tmp.root, io),
            scanner(),
            PackStore(tmp.root, io),
            PackScanStore(tmp.root, io)
        )
    }

    @Test
    fun `Add guides stores the chosen offered presets, reports the first as Added, then refreshes each`() =
        runTest(mainDispatcherRule.dispatcher) {
            val guides = (pluto.guides + india.guides).map { it.copy(addedAtMs = 1L) }
            coEvery { manager.addPresetGuides(listOf(pluto, india)) } returns guides
            guides.forEach { coEvery { manager.refresh(it.id) } returns it }
            val vm = offering()
            // uiState derives on Dispatchers.Default: wait for the offer, not for virtual time.
            assertEquals(listOf("pluto", "in"), vm.uiState.first { it.presets.isNotEmpty() }.presets.map { it.preset.key })
            // A key not on offer is ignored.
            vm.addPresets(listOf("in", "pluto", "plex"))
            advanceUntilIdle()
            assertEquals(SourcesEvent.Added(guides[0].id, guides[0].name), vm.event.first())
            guides.forEach { coVerify(exactly = 1) { manager.refresh(it.id) } }
        }

    @Test
    fun `nothing on offer adds nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.addPresets(listOf("pluto"))
        advanceUntilIdle()
        coVerify(exactly = 0) { manager.addPresetGuides(any()) }
    }

    @Test
    fun `a failed preset guide write reports it and refreshes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { manager.addPresetGuides(any()) } returns null
        val vm = offering()
        vm.uiState.first { it.presets.isNotEmpty() }
        vm.addPresets(listOf("pluto"))
        advanceUntilIdle()
        assertEquals(SourcesEvent.PresetAddFailed, vm.event.first())
        coVerify(exactly = 0) { manager.refresh(any()) }
    }

    @Test
    fun `closing during a check drops it`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<LiveSourceManager.CheckResult>()
        coEvery { manager.check(any()) } coAnswers { gate.await() }
        val vm = viewModel()
        vm.onAddUrlChange(source.url)
        vm.checkAdd()
        advanceUntilIdle()
        vm.closeAdd()
        gate.complete(LiveSourceManager.CheckResult.Ready(checked))
        advanceUntilIdle()
        assertEquals(AddCheck.Idle, vm.addCheck.value)
        assertTrue(vm.addUrl.value.isNotEmpty())
    }
}
