package com.nuvio.tv.livetv.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.data.HiddenChannelStore
import com.nuvio.tv.livetv.data.LiveTvUserStore
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.player.LivePlayer
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.livetv.player.StreamHealthStore
import com.nuvio.tv.livetv.source.IptvMetadataRepository
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.PackSummary
import com.nuvio.tv.livetv.source.SourceFetcher
import com.nuvio.tv.livetv.source.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec 34 L8a: the opening filter and focus (Q1, L8-D3), the saved filter, Recents after 10 s of
 * play (L8-D2), lost channels never tuned (L8-D4). The minute ticker never idles: time moves by
 * [runCurrent] / [advanceTimeBy] only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = UnconfinedTestDispatcher()
    private val sources = MutableStateFlow(
        listOf(LiveSource("a", SourceKind.M3U, "My playlist", "https://example.org/a.m3u", 0L))
    )
    private val manager = mockk<LiveSourceManager>().also {
        every { it.sources } returns sources
        coEvery { it.load() } just runs
        coEvery { it.refreshStale() } just runs
        coEvery { it.packSummary() } returns null
    }
    private val prefs = TestPreferencesDataStore()
    private val factory = mockk<ProfileDataStoreFactory>().also {
        every { it.get(any(), any()) } returns prefs as DataStore<Preferences>
    }
    private val profiles = mockk<ProfileManager>().also { every { it.activeProfileId } returns MutableStateFlow(1) }
    private val userStore = LiveTvUserStore(factory, profiles)
    private val tuneState = MutableStateFlow<LiveTuneState>(LiveTuneState.Idle)
    private val player = mockk<LivePlayer>(relaxed = true).also { every { it.state } returns tuneState }
    private val channels by lazy { ChannelRepository(tmp.root, { 0L }, io) }

    private fun ch(key: String, groups: List<String> = emptyList()) =
        Channel(key = key, sourceId = "a", name = key, groups = groups, streams = listOf(LiveStream("https://example.org/$key.m3u8")))

    private val a1 = ch("a1", listOf("Sports"))
    private val a2 = ch("a2")
    private val a3 = ch("a3")

    /** a1, a3 listed; a2 lost (the source's second refresh stopped listing it). */
    private suspend fun seedChannels() {
        channels.replaceSource("a", listOf(a1, a2, a3))
        channels.replaceSource("a", listOf(a1, a3))
    }

    private val guides by lazy { GuideRepository(tmp.root, io) }

    private fun TestScope.viewModel(): LiveTvViewModel {
        val vm = LiveTvViewModel(
            manager,
            channels,
            guides,
            IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { 0L }, io, "http://127.0.0.1:1/"),
            HiddenChannelStore(tmp.root, io),
            StreamHealthStore(tmp.root, io),
            userStore,
            mockk<ScreensaverController>(relaxed = true),
            player,
            mainDispatcherRule.dispatcher
        )
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.info.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `the empty state's pack offer reads figures once, clears a stale failure and adds one at a time`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pack = LiveSource("pack", SourceKind.PACK, "Channel pack", "https://example.org/", 0L)
            val gate = CompletableDeferred<LiveSourceManager.AddResult>()
            coEvery { manager.packSummary() } returns null andThen PackSummary(21037, 177, 1_600_000)
            var adds = 0
            coEvery { manager.addPack() } coAnswers {
                when (++adds) {
                    1 -> LiveSourceManager.AddResult.Refused("HTTP 404")
                    2 -> LiveSourceManager.AddResult.Added(pack)
                    else -> gate.await()
                }
            }
            val vm = viewModel()
            vm.loadPackSummary()
            runCurrent()
            assertEquals(PackOffer(), vm.packOffer.value) // offline: no figures
            vm.loadPackSummary() // shown again: a failed read is retried
            runCurrent()
            vm.loadPackSummary() // read: not again
            runCurrent()
            coVerify(exactly = 2) { manager.packSummary() }
            val figures = PackSummary(21037, 177, 1_600_000)
            assertEquals(PackOffer(figures), vm.packOffer.value)

            vm.addPack()
            runCurrent()
            assertEquals(PackOffer(figures, error = "HTTP 404"), vm.packOffer.value)
            vm.loadPackSummary() // coming back to the empty state drops the old failure
            assertEquals(PackOffer(figures), vm.packOffer.value)

            // An add that succeeds while no screen collects opens nothing later.
            vm.addPack()
            runCurrent()
            assertFalse(vm.packOffer.value.adding)
            val opened = mutableListOf<Unit>()
            backgroundScope.launch { vm.packAdded.collect { opened += it } }
            runCurrent()
            vm.addPack()
            runCurrent()
            assertTrue(vm.packOffer.value.adding)
            vm.addPack() // ignored while the first runs
            gate.complete(LiveSourceManager.AddResult.Added(pack))
            runCurrent()
            assertEquals(1, opened.size)
            assertFalse(vm.packOffer.value.adding)
            coVerify(exactly = 3) { manager.addPack() }
        }

    @Test
    fun `first open goes to Favorites when one is listed, focus on the last-watched channel`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.toggleFavorite("a2")
            userStore.recordWatched("a3", 1)
            val vm = viewModel()
            val ui = vm.uiState.value
            assertFalse(ui.loading)
            assertEquals(LiveTvFilter.Favorites, ui.filter)
            // The lost favorite is listed there, dimmed (L8-D4).
            assertEquals(listOf("a2"), ui.rows.map { it.channel.key })
            assertTrue(ui.rows.single().lost && ui.rows.single().favorite)
            assertEquals(1, ui.counts[LiveTvFilter.Recents])
            assertEquals("a3", vm.focusedKey.value)
        }

    @Test
    fun `no favorite and nothing saved opens All`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        assertEquals(LiveTvFilter.ALL, viewModel().uiState.value.filter)
    }

    @Test
    fun `a saved filter wins, and the pick focus settles on is saved after 500 ms`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.setLastFilter("sports")
            val vm = viewModel()
            assertEquals(LiveTvFilter.Browse(category = "sports"), vm.uiState.value.filter)
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            advanceTimeBy(300)
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            advanceTimeBy(499)
            runCurrent()
            assertEquals("sports", userStore.state.first().lastFilter) // Recents was passed, never saved
            advanceTimeBy(2)
            runCurrent()
            assertEquals("all", userStore.state.first().lastFilter)
        }

    @Test
    fun `Recents keeps its order while shown and takes the new order when selected again`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.recordWatched("a1", 1)
            userStore.recordWatched("a3", 2)
            val vm = viewModel()
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            assertEquals(listOf("a3", "a1"), vm.uiState.value.rows.map { it.channel.key })
            userStore.recordWatched("a1", 3)
            runCurrent()
            assertEquals(listOf("a3", "a1"), vm.uiState.value.rows.map { it.channel.key })
            vm.selectFilter(LiveTvFilter.ALL)
            vm.selectFilter(LiveTvFilter.Recents)
            runCurrent()
            assertEquals(listOf("a1", "a3"), vm.uiState.value.rows.map { it.channel.key })
        }

    @Test
    fun `Favorites and Recents keep the picker values, a Browse replaces them`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            userStore.setLastFilter("sports")
            val vm = viewModel()
            val sports = LiveTvFilter.Browse(category = "sports")
            assertEquals(sports, vm.uiState.value.browse)
            assertEquals(listOf("a1"), vm.uiState.value.rows.map { it.channel.key })
            // Inside Sports: a1 only (no country), so All = 1 and No country = 1.
            assertEquals(
                listOf(PickerValue(null, 1), PickerValue(LiveTvFilter.NO_COUNTRY, 1)),
                vm.uiState.value.pickers!!.country.values
            )
            vm.selectFilter(LiveTvFilter.Favorites)
            runCurrent()
            assertEquals(LiveTvFilter.Favorites, vm.uiState.value.filter)
            assertEquals(sports, vm.uiState.value.browse)
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            assertEquals(LiveTvFilter.ALL, vm.uiState.value.browse)
            assertEquals(listOf("a1", "a3"), vm.uiState.value.rows.map { it.channel.key })
        }

    @Test
    fun `toggling a favorite twice adds then removes it`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        val vm = viewModel()
        vm.toggleFavorite("a1")
        runCurrent()
        assertEquals(1, vm.uiState.value.counts[LiveTvFilter.Favorites])
        vm.toggleFavorite("a1")
        runCurrent()
        assertEquals(0, vm.uiState.value.counts[LiveTvFilter.Favorites])
    }

    @Test
    fun `a channel enters Recents after 10 s of play`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        viewModel()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(userStore.state.first().recents.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals("a1", userStore.state.first().recents.single().key)
    }

    @Test
    fun `a play interrupted before 10 s starts the count again`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        viewModel()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(6_000)
        tuneState.value = LiveTuneState.Tuning(a1, 1, 1)
        runCurrent()
        tuneState.value = LiveTuneState.Playing(a1)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(userStore.state.first().recents.isEmpty())
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals("a1", userStore.state.first().recents.single().key)
    }

    @Test
    fun `a lost channel is never tuned by focus, OK or zapping`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        listOf("a1", "a2", "a3").forEach { userStore.toggleFavorite(it) }
        val vm = viewModel()
        val lost = vm.uiState.value.rows.single { it.lost }.channel
        vm.onRowFocused(lost)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(vm.tuneNow(lost))
        verify(exactly = 0) { player.tune(any()) }

        // Favorites in number order: a1, a3, then lost a2 (moved to its source's end). From a3 the zap wraps to a1.
        tuneState.value = LiveTuneState.Playing(a3)
        assertEquals("a1", vm.zap(+1))
        verify(exactly = 1) { player.tune(match { it.key == "a1" }) }
    }

    @Test
    fun `Back to a lost last channel and a return to a focused lost channel tune nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            seedChannels()
            listOf("a1", "a2", "a3").forEach { userStore.toggleFavorite(it) }
            val vm = viewModel()
            val lost = vm.uiState.value.rows.single { it.lost }.channel
            // Tuned the lost channel's key before it went lost: now it is the last channel.
            tuneState.value = LiveTuneState.Playing(lost.copy(lostAtMs = null))
            vm.tuneNow(a1)
            tuneState.value = LiveTuneState.Playing(a1)
            assertFalse(vm.lastChannel())
            assertEquals("a1", vm.focusedKey.value)

            vm.onRowFocused(lost)
            tuneState.value = LiveTuneState.Idle
            vm.resumePreview()
            runCurrent()
            verify(exactly = 0) { player.tune(match { it.key == lost.key }) }
        }

    @Test
    fun `openSchedule tunes a guided channel and derives its schedule, closeSchedule stops it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val guided = ch("a1").copy(tvgId = "One.us")
            channels.replaceSource("a", listOf(guided, a3))
            sources.value = sources.value + LiveSource("g", SourceKind.XMLTV, "Pluto TV", "https://example.org/g.xml", 0L)
            // Real wall clock: the view model's minute ticker reads it.
            val now = System.currentTimeMillis()
            val airing = EpgProgramme(now - 600_000, now + 1_200_000, "Airing")
            val next = EpgProgramme(now + 1_200_000, now + 3_000_000, "Next")
            guides.put(
                "g",
                EpgSnapshot(
                    channels = mapOf("One.us" to EpgChannel("One.us", listOf("One"), null)),
                    // Listed twice, as i.mjh.nz's Pluto guide does: shown once.
                    programmes = mapOf("One.us" to listOf(airing, airing, next)),
                    windowStartMs = now, windowEndMs = now + 6 * 3_600_000L, complete = true
                )
            )
            val vm = viewModel()
            backgroundScope.launch { vm.schedule.collect {} }
            runCurrent()
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            val rows = vm.uiState.value.rows.associateBy { it.channel.key }
            assertTrue(rows.getValue(guided.key).hasSchedule)
            assertFalse(rows.getValue(a3.key).hasSchedule)

            val firstId = vm.openSchedule(rows.getValue(guided.key).channel)
            runCurrent()
            verify(exactly = 1) { player.tune(match { it.key == guided.key }) }
            val open = vm.schedule.value!!
            assertEquals("Pluto TV", open.guideName)
            val titles = (open.today.items + open.tomorrow.items).map { it.programme.title }
            assertEquals(listOf("Airing", "Next"), titles)
            assertEquals(ScheduleTime.NOW, (open.today.items + open.tomorrow.items).first().time)

            // A reopen of the same channel is a new open (the column refocuses), even with no null seen between.
            assertEquals(firstId, open.openId)
            vm.closeSchedule()
            val reopenId = vm.openSchedule(rows.getValue(guided.key).channel)
            runCurrent()
            assertTrue(reopenId!! > firstId!!)
            assertEquals(reopenId, vm.schedule.value!!.openId)

            vm.closeSchedule()
            runCurrent()
            assertEquals(null, vm.schedule.value)

            // Its guide removed while open: the derived schedule turns null; the screen, which owns
            // the open, sees its shown schedule go and closes the column (LiveTvScreen, D5).
            vm.openSchedule(rows.getValue(guided.key).channel)
            runCurrent()
            assertTrue(vm.schedule.value != null)
            guides.remove("g")
            runCurrent()
            assertEquals(null, vm.schedule.value)
        }

    @Test
    fun `a lost channel opens no schedule`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        listOf("a1", "a2", "a3").forEach { userStore.toggleFavorite(it) }
        val vm = viewModel()
        backgroundScope.launch { vm.schedule.collect {} }
        val lost = vm.uiState.value.rows.single { it.lost }
        assertFalse(lost.hasSchedule)
        // The guard itself, whatever the row says: a lost channel gets no open id and no tune.
        assertEquals(null, vm.openSchedule(lost.channel))
        runCurrent()
        assertEquals(null, vm.schedule.value)
        verify(exactly = 0) { player.tune(any()) }
    }

    @Test
    fun `the saved view opens, a picked one is saved, and only a grid view lays out the shown rows`() =
        runTest(mainDispatcherRule.dispatcher) {
            val guided = ch("a1").copy(tvgId = "One.us")
            channels.replaceSource("a", listOf(guided, a3))
            sources.value = sources.value + LiveSource("g", SourceKind.XMLTV, "Pluto TV", "https://example.org/g.xml", 0L)
            val now = System.currentTimeMillis()
            fun snapshot(title: String) = EpgSnapshot(
                channels = mapOf("One.us" to EpgChannel("One.us", listOf("One"), null)),
                programmes = mapOf("One.us" to listOf(EpgProgramme(now - 600_000, now + 1_200_000, title))),
                windowStartMs = now, windowEndMs = now + 6 * 3_600_000L, complete = true
            )
            guides.put("g", snapshot("Airing"))
            userStore.setLastView("guide")
            val vm = viewModel()
            val opened = vm.uiState.value
            assertEquals(LiveTvView.GUIDE, opened.view)
            val grid = opened.guide!!
            assertEquals(listOf("Airing"), grid.row(guided.key).map { it.programme.title })
            assertTrue(grid.row(a3.key).isEmpty())

            // A new minute alone lays nothing out again (the ticker reads the real clock, so its value
            // may or may not differ; either way the layout is the same object).
            advanceTimeBy(61_000)
            runCurrent()
            assertSame(grid, vm.uiState.value.guide)
            // A guide change does.
            guides.put("g", snapshot("Changed"))
            runCurrent()
            assertEquals(listOf("Changed"), vm.uiState.value.guide!!.row(guided.key).map { it.programme.title })
            // Only the shown filter's rows are laid out.
            vm.selectFilter(LiveTvFilter.Browse(category = "nothing"))
            runCurrent()
            assertTrue(vm.uiState.value.guide!!.row(guided.key).isEmpty())
            vm.selectFilter(LiveTvFilter.ALL)
            runCurrent()
            // The refs alone changing (No guide chosen) lays out again.
            vm.chooseGuide(guided.key, null)
            runCurrent()
            assertTrue(vm.uiState.value.guide!!.row(guided.key).isEmpty())
            vm.chooseGuide(guided.key, EpgRef("g", "One.us"))
            runCurrent()
            val beforeList = vm.uiState.value.guide!!
            assertEquals(listOf("Changed"), beforeList.row(guided.key).map { it.programme.title })

            vm.selectView(LiveTvView.LIST)
            runCurrent()
            assertEquals(LiveTvView.LIST, vm.uiState.value.view)
            assertNull(vm.uiState.value.guide)
            assertEquals("list", userStore.state.first().lastView)
            vm.selectView(LiveTvView.COMPACT)
            runCurrent()
            assertEquals("compact", userStore.state.first().lastView)
            // List let the layout go: back in a grid it is made again.
            assertNotSame(beforeList, vm.uiState.value.guide)
            assertEquals(beforeList, vm.uiState.value.guide)
        }

    @Test
    fun `nothing saved opens List`() = runTest(mainDispatcherRule.dispatcher) {
        seedChannels()
        val ui = viewModel().uiState.value
        assertEquals(LiveTvView.LIST, ui.view)
        assertNull(ui.guide)
    }
}
