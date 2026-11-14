package com.nuvio.tv.core.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.core.player.PlayerPlaybackNetworking
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchSelectionSupplierTest {

    // Default settings are MANUAL auto-play, so every rank publishes EMPTY.
    private val settings = mockk<PlayerSettingsDataStore>(relaxed = true).also {
        every { it.playerSettings } returns flowOf(PlayerSettings())
        // A relaxed mock's flow never emits, so rank's .first() needs a real one (R5-5).
        every { it.streamLimitInputs } returns flowOf(StreamLimitInputs(StreamLimitSettings(StreamLimitMode.OFF)))
    }

    private val addons = mockk<AddonRepository>().also {
        every { it.getInstalledAddons() } returns flowOf(emptyList())
    }

    private val preferredSources = mockk<com.nuvio.tv.data.local.PreferredSourceDataStore>().also {
        coEvery { it.get(any()) } returns null
    }

    private val supplier = PrefetchSelectionSupplier(
        context = mockk(relaxed = true),
        playerSettingsDataStore = settings,
        addonRepository = addons,
        bingeGroupCacheDataStore = mockk(relaxed = true),
        preferredSourceDataStore = preferredSources,
        streamBadgePresentation = mockk(relaxed = true),
        failedReleases = FailedReleases(
            object : FailedReleaseStorage {
                override fun load(): String? = null
                override fun save(value: String) = Unit
            },
            kotlin.time.Clock.System
        )
    )

    private suspend fun publishEmpty(uiKey: String) {
        supplier.rankAndPrewarm(groups = emptyList(), contentId = null, runtimeMinutes = null, episode = false, film = false, uiKey = uiKey)
    }

    // Mirrors PrefetchSelectionSupplier.MAX_UI_SIGNALS (private).
    private val cap = 16
    private fun key(i: Int) = "k$i"

    @Test
    fun `signals are kept per uiKey and trimmed to the newest sixteen`() = runTest {
        (0..cap).forEach { publishEmpty(key(it)) }

        val signals = supplier.uiSignals.value
        assertEquals((1..cap).map(::key), signals.keys.toList())
        assertEquals(SourcePrefetchSignal(key(1), SourcePrefetchPhase.EMPTY, null), signals[key(1)])
    }

    @Test
    fun `a back stack deeper than four keeps every page's signal`() = runTest {
        (0..<cap).forEach { publishEmpty(key(it)) }

        assertEquals((0..<cap).map(::key), supplier.uiSignals.value.keys.toList())
    }

    @Test
    fun `republishing a key makes it the newest`() = runTest {
        (0..<cap).forEach { publishEmpty(key(it)) }
        publishEmpty(key(0))
        publishEmpty("new")

        assertEquals(
            (2..<cap).map(::key) + key(0) + "new",
            supplier.uiSignals.value.keys.toList()
        )
    }

    @Test
    fun `rank tolerates an empty pool outside manual mode`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )

        publishEmpty("k")

        assertEquals(SourcePrefetchPhase.EMPTY, supplier.uiSignals.value["k"]?.phase)
    }

    private val oneStream = listOf(
        AddonStreams(
            addonName = "A",
            addonLogo = null,
            streams = listOf(
                Stream(
                    name = "A 2160p", title = null, description = null, url = "https://host/file.mkv",
                    ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
                    behaviorHints = null, addonName = "A", addonLogo = null
                )
            )
        )
    )

    /** True when a winner was ranked; uiKey/prewarm as the production callers pass them. */
    private suspend fun ranksAWinner(uiKey: String?, prewarm: Boolean): Boolean {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        return supplier.rankAndPrewarm(oneStream, contentId = null, runtimeMinutes = null, episode = false, film = false, uiKey = uiKey, prewarm = prewarm) != null
    }

    @Test
    fun `focus callers rank without a prewarm GET, hero callers still warm the winner (T30)`() = runTest {
        mockkObject(PlayerPlaybackNetworking)
        try {
            every { PlayerPlaybackNetworking.prewarmPlaybackConnection(any(), any()) } returns Unit

            // episode_focus / cw_focus: no uiKey, prewarm off
            assertTrue(ranksAWinner(uiKey = null, prewarm = false))
            verify(exactly = 0) { PlayerPlaybackNetworking.prewarmPlaybackConnection(any(), any()) }

            // details_hero: uiKey, prewarm on
            assertTrue(ranksAWinner(uiKey = "k", prewarm = true))
            verify(exactly = 1) { PlayerPlaybackNetworking.prewarmPlaybackConnection("https://host/file.mkv", any()) }
            assertEquals(SourcePrefetchPhase.READY, supplier.uiSignals.value["k"]?.phase)
        } finally {
            unmockkObject(PlayerPlaybackNetworking)
        }
    }

    // Spec 51 P5: the prefetch ranks under the limit, with the runtime its caller knows.
    @Test
    fun `the rank skips a stream the limit hides and carries the set in its snapshot`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        every { settings.streamLimitInputs } returns flowOf(
            StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
        )
        fun sized(url: String, bytes: Long) = Stream(
            name = "A", title = null, description = null, url = url, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "A", addonLogo = null,
            streamData = AioStreamData(size = bytes, filename = null, cached = null, parsedFile = null)
        )
        // Over 60 minutes: 133 and 13 Mbit/s.
        val big = sized("https://host/big.mkv", 60_000_000_000L)
        val small = sized("https://host/small.mkv", 6_000_000_000L)
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(big, small)))

        val limited = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = 60, episode = false, film = false, prewarm = false)!!
        assertEquals(small.url, limited.winner.url)
        assertEquals(setOf(big.limitKey()), limited.snapshot.inputs.hiddenKeys)

        // No runtime known and no file length: nothing is estimated, so nothing is hidden (D8).
        val unknown = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = null, episode = false, film = false, prewarm = false)!!
        assertEquals(big.url, unknown.winner.url)
        assertEquals(emptySet<String>(), unknown.snapshot.inputs.hiddenKeys)
        // Spec 65 D4: the film flag reaches the snapshot the press compares.
        val film = supplier.rankAndPrewarm(groups, contentId = null, runtimeMinutes = null, episode = false, film = true, prewarm = false)!!
        assertEquals(true, film.snapshot.inputs.filmSizeFloors)
        assertEquals(false, unknown.snapshot.inputs.filmSizeFloors)
    }

    // Spec 65 P6: the prefetch reads the show's preferred source as the press does, so both pick it.
    @Test
    fun `the rank takes the show's preferred source and carries it in its snapshot`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        fun named(filename: String) = Stream(
            name = "A", title = null, description = null, url = "https://host/$filename", ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = filename
            ),
            addonName = "A", addonLogo = null
        )
        val first = named("Show.S01E03.2160p.WEB-DL-FLUX.mkv")
        val wanted = named("Show.S01E03.1080p.WEB-DL-NTb.mkv")
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(first, wanted)))
        val preferred = PreferredSource.of(named("Show.S01E02.1080p.WEB-DL-NTb.mkv"))
        coEvery { preferredSources.get("tt1") } returns preferred

        val ranked = supplier.rankAndPrewarm(groups, contentId = "tt1", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(wanted.url, ranked.winner.url)
        assertEquals(preferred, ranked.snapshot.inputs.preferred)
        // Another show: no preference, the mode's own pick.
        val other = supplier.rankAndPrewarm(groups, contentId = "tt2", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(first.url, other.winner.url)
        assertEquals(null, other.snapshot.inputs.preferred)
        // MANUAL mode (D20): the preferred match is still the winner; without one nothing ranks.
        every { settings.playerSettings } returns flowOf(PlayerSettings())
        val manual = supplier.rankAndPrewarm(groups, contentId = "tt1", runtimeMinutes = null, episode = true, film = false, prewarm = false)!!
        assertEquals(wanted.url, manual.winner.url)
        assertEquals(null, supplier.rankAndPrewarm(groups, contentId = "tt2", runtimeMinutes = null, episode = true, film = false, prewarm = false))
    }

    // Spec 51 r1: the binge lookahead passes the player's exemption, so its rank keeps the group.
    @Test
    fun `an exempt binge group's over-limit stream stays rankable for the lookahead`() = runTest {
        every { settings.playerSettings } returns flowOf(
            PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        )
        every { settings.streamLimitInputs } returns flowOf(
            StreamLimitInputs(StreamLimitSettings(StreamLimitMode.MANUAL, manualMbps = 40))
        )
        fun sized(url: String, bytes: Long, group: String) = Stream(
            name = "A", title = null, description = null, url = url, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null),
            addonName = "A", addonLogo = null,
            streamData = AioStreamData(size = bytes, filename = null, cached = null, parsedFile = null)
        )
        // Over 60 minutes: 133 Mbit/s in the playing group g1, 13 Mbit/s in g2.
        val sameGroup = sized("https://host/g1.mkv", 60_000_000_000L, "g1")
        val other = sized("https://host/g2.mkv", 6_000_000_000L, "g2")
        val groups = listOf(AddonStreams(addonName = "A", addonLogo = null, streams = listOf(sameGroup, other)))

        val exempt = supplier.rankAndPrewarm(
            groups, contentId = null, runtimeMinutes = 60, episode = true, film = false,
            bingeOverride = "g1", exemptBingeGroup = "g1", prewarm = false
        )!!
        assertEquals(sameGroup.url, exempt.winner.url)
        val notExempt = supplier.rankAndPrewarm(
            groups, contentId = null, runtimeMinutes = 60, episode = true, film = false, bingeOverride = "g1", prewarm = false
        )!!
        assertEquals(other.url, notExempt.winner.url)
    }
}
