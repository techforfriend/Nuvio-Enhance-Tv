package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbImages
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.WatchedArt
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 118 R1: watched-only titles get art, it is kept across sync writes, and never pushed. */
class WatchedArtworkHydratorTest {
    private val stores = mutableMapOf<Int, TestPreferencesDataStore>()
    private val preferences: WatchedItemsPreferences
    private val metaRepository = mockk<MetaRepository>()
    private val tmdbService = mockk<TmdbService>()
    private val activeProfile = MutableStateFlow(1)
    private val installedAddons = MutableStateFlow(listOf(mockk<Addon>(relaxed = true)))
    private val hydrator: WatchedArtworkHydrator

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        preferences = WatchedItemsPreferences(factory, profileManager)
        every { metaRepository.getMetaFromPrimaryAddon(any(), any()) } returns
            flowOf(NetworkResult.Error("no addon"))
        coEvery { tmdbService.fetchImdbImages(any(), any()) } returns null
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns installedAddons
        hydrator = WatchedArtworkHydrator(preferences, metaRepository, tmdbService, profileManager, addonRepository)
    }

    @Test
    fun `planner takes art-less unclaimed titles, newest first, one item each, capped`() {
        val items = (0 until 25).map { item("tt$it", watchedAt = it.toLong()) } +
            item("ttEp", season = 1, episode = 1, watchedAt = 100L) +
            item("ttEp", season = 1, episode = 2, watchedAt = 101L) +
            item("ttArt", watchedAt = 200L).copy(poster = "p") +
            item("ttArt", season = 1, episode = 1, watchedAt = 201L) +
            item("ttBackOnly", watchedAt = 300L).copy(backdrop = "b")

        val batch = planWatchedArtworkBatch(
            items, claims = setOf(watchedArtworkClaim(PROFILE, "tt24")), profileId = PROFILE
        )

        assertEquals(WATCHED_ARTWORK_HYDRATION_BATCH, batch.size)
        // A backdrop alone is not a poster: still planned.
        assertEquals("ttBackOnly", batch.first().contentId)
        assertEquals("ttEp", batch[1].contentId)
        assertEquals(2, batch[1].episode)
        assertEquals("tt23", batch[2].contentId)
        assertTrue(batch.none { it.contentId == "tt24" || it.contentId == "ttArt" })
    }

    @Test
    fun `another profile's claim does not block this profile's title`() {
        val batch = planWatchedArtworkBatch(
            listOf(item("tt1")), claims = setOf(watchedArtworkClaim(PROFILE, "tt1")), profileId = 3
        )
        assertEquals(listOf("tt1"), batch.map { it.contentId })
    }

    @Test
    fun `a local mark in the active profile is hydrated without any sync`() = runBlocking {
        activeProfile.value = PROFILE
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttLocal") } returns
            flowOf(NetworkResult.Success(meta("ttLocal", poster = "local", backdrop = null)))

        preferences.markAsWatched(item("ttLocal"), profileId = PROFILE)

        val deadline = System.currentTimeMillis() + WATCHED_ARTWORK_SETTLE_MS + 5_000L
        while (preferences.getAllItems(PROFILE).single().poster == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("local", preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `an addon backdrop without a poster still asks TMDB for the poster`() = runTest {
        preferences.markAsWatched(item("ttBack"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttBack") } returns
            flowOf(NetworkResult.Success(meta("ttBack", poster = null, backdrop = "addon-b")))
        coEvery { tmdbService.fetchImdbImages("ttBack", "movie") } returns TmdbImages("tmdb-b", "tmdb-p")

        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE).single()
        assertEquals("tmdb-p" to "addon-b", stored.poster to stored.backdrop)
    }

    @Test
    fun `a poster is never stored as the backdrop`() = runTest {
        preferences.markAsWatched(item("ttPortrait"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttPortrait") } returns
            flowOf(NetworkResult.Success(meta("ttPortrait", poster = "p", backdrop = null)))

        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE).single()
        assertEquals("p" to null, stored.poster to stored.backdrop)
    }

    @Test
    fun `a prefixed id and the tv type are tried after the stored ones`() = runTest {
        preferences.markAsWatched(item("tmdb:42", season = 1, episode = 1), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("tv", "42") } returns
            flowOf(NetworkResult.Success(meta("42", poster = "tv-p", backdrop = null)))

        hydrator.hydrate(PROFILE)

        assertEquals("tv-p", preferences.getAllItems(PROFILE).single().poster)
        verify { metaRepository.getMetaFromPrimaryAddon("series", "tmdb:42") }
        verify { metaRepository.getMetaFromPrimaryAddon("series", "42") }
        verify { metaRepository.getMetaFromPrimaryAddon("tv", "tmdb:42") }
    }

    @Test
    fun `one run works through every batch`() = runTest {
        val count = WATCHED_ARTWORK_HYDRATION_BATCH * 2 + 5
        preferences.markAsWatchedBatch((0 until count).map { item("tt$it", watchedAt = it.toLong()) }, PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", any()) } answers {
            flowOf(NetworkResult.Success(meta(secondArg(), poster = "p-${secondArg<String>()}", backdrop = null)))
        }

        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE)
        assertEquals(count, stored.size)
        assertTrue(stored.all { it.poster == "p-${it.contentId}" })
    }

    @Test
    fun `a title no source has art for is fetched once per process`() = runTest {
        preferences.markAsWatched(item("ttNone"), profileId = PROFILE)

        hydrator.hydrate(PROFILE)
        hydrator.hydrate(PROFILE)

        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", "ttNone") }
        coVerify(exactly = 1) { tmdbService.fetchImdbImages("ttNone", "movie") }
    }

    @Test
    fun `one title's failure does not stop the others`() = runTest {
        preferences.markAsWatchedBatch(listOf(item("ttBad", watchedAt = 2L), item("ttGood", watchedAt = 1L)), PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttBad") } throws IllegalStateException("boom")
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttGood") } returns
            flowOf(NetworkResult.Success(meta("ttGood", poster = "good", backdrop = null)))

        hydrator.hydrate(PROFILE)

        assertEquals(
            mapOf("ttBad" to null, "ttGood" to "good"),
            preferences.getAllItems(PROFILE).associate { it.contentId to it.poster }
        )
        // D5: a failed fetch stays claimed for the process, like a miss.
        hydrator.hydrate(PROFILE)
        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", "ttBad") }
    }

    @Test
    fun `the first run waits for the installed addons before fetching anything`() = runTest {
        installedAddons.value = emptyList()
        preferences.markAsWatched(item("ttCold"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttCold") } returns
            flowOf(NetworkResult.Success(meta("ttCold", poster = "cold", backdrop = null)))

        val run = launch { hydrator.hydrate(PROFILE) }
        runCurrent()
        verify(exactly = 0) { metaRepository.getMetaFromPrimaryAddon(any(), any()) }

        installedAddons.value = listOf(mockk(relaxed = true))
        run.join()

        assertEquals("cold", preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `the addon wait happens once per process`() = runTest {
        hydrator.hydrate(PROFILE)
        installedAddons.value = emptyList()
        preferences.markAsWatched(item("ttLater"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttLater") } returns
            flowOf(NetworkResult.Success(meta("ttLater", poster = "later", backdrop = null)))

        val run = launch { hydrator.hydrate(PROFILE) }
        runCurrent()

        assertTrue(run.isCompleted)
        assertEquals("later", preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `a timed-out fetch stays claimed for the process`() = runTest {
        preferences.markAsWatched(item("ttSlow"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttSlow") } returns
            flow { emit(NetworkResult.Loading); awaitCancellation() }

        hydrator.hydrate(PROFILE)
        hydrator.hydrate(PROFILE)

        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", "ttSlow") }
        assertNull(preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `with no addons at all the first run goes on after the wait`() = runTest {
        installedAddons.value = emptyList()
        preferences.markAsWatched(item("ttKeyOnly"), profileId = PROFILE)
        coEvery { tmdbService.fetchImdbImages("ttKeyOnly", "movie") } returns TmdbImages(null, "tmdb-only")

        val run = launch { hydrator.hydrate(PROFILE) }
        advanceTimeBy(WATCHED_ARTWORK_ADDONS_WAIT_MS + 1)
        run.join()

        assertEquals("tmdb-only", preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `a backdrop alone is stored and the title not refetched in this process`() = runTest {
        preferences.markAsWatched(item("ttWide"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttWide") } returns
            flowOf(NetworkResult.Success(meta("ttWide", poster = null, backdrop = "wide-b")))

        hydrator.hydrate(PROFILE)
        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE).single()
        assertEquals(null to "wide-b", stored.poster to stored.backdrop)
        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", "ttWide") }
    }

    @Test
    fun `a backdrop from an earlier answer is kept with a later poster`() = runTest {
        preferences.markAsWatched(item("tmdb:7", season = 1, episode = 1), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("series", "tmdb:7") } returns
            flowOf(NetworkResult.Success(meta("tmdb:7", poster = null, backdrop = "first-b")))
        every { metaRepository.getMetaFromPrimaryAddon("series", "7") } returns
            flowOf(NetworkResult.Success(meta("7", poster = "later-p", backdrop = null)))

        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE).single()
        assertEquals("later-p" to "first-b", stored.poster to stored.backdrop)
    }

    @Test
    fun `a title unmarked and marked again in the same process is hydrated again`() = runTest {
        preferences.markAsWatched(item("ttAgain"), profileId = PROFILE)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "ttAgain") } returns
            flowOf(NetworkResult.Success(meta("ttAgain", poster = "again", backdrop = null)))
        hydrator.hydrate(PROFILE)
        preferences.unmarkAsWatched("ttAgain", profileId = PROFILE)
        preferences.markAsWatched(item("ttAgain"), profileId = PROFILE)
        assertNull(preferences.getAllItems(PROFILE).single().poster)

        hydrator.hydrate(PROFILE)

        assertEquals("again", preferences.getAllItems(PROFILE).single().poster)
    }

    @Test
    fun `addon art fills every item of the title, once per process`() = runTest {
        preferences.markAsWatchedBatch(
            listOf(item("tt1", season = 1, episode = 1), item("tt1", season = 1, episode = 2)),
            profileId = PROFILE
        )
        every { metaRepository.getMetaFromPrimaryAddon("series", "tt1") } returns
            flowOf(NetworkResult.Success(meta("tt1", poster = "addon-p", backdrop = "addon-b")))

        hydrator.hydrate(PROFILE)
        hydrator.hydrate(PROFILE)

        val stored = preferences.getAllItems(PROFILE)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.poster == "addon-p" && it.backdrop == "addon-b" })
        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("series", "tt1") }
        coVerify(exactly = 0) { tmdbService.fetchImdbImages(any(), any()) }
    }

    @Test
    fun `TMDB stands in when no addon has an image, in the hydrated profile only`() = runTest {
        preferences.markAsWatched(item("tt2", type = "movie"), profileId = PROFILE)
        preferences.markAsWatched(item("tt2", type = "movie"), profileId = OTHER)
        every { metaRepository.getMetaFromPrimaryAddon("movie", "tt2") } returns
            flowOf(NetworkResult.Success(meta("tt2", poster = null, backdrop = null)))
        coEvery { tmdbService.fetchImdbImages("tt2", "movie") } returns TmdbImages("tmdb-b", "tmdb-p")

        hydrator.hydrate(PROFILE)

        assertEquals("tmdb-p", preferences.getAllItems(PROFILE).single().poster)
        assertNull(preferences.getAllItems(OTHER).single().poster)
    }

    @Test
    fun `merge fills only missing fields and never resurrects a removed item`() = runTest {
        preferences.markAsWatchedBatch(
            listOf(item("tt3", season = 1, episode = 1).copy(poster = "own"), item("tt4")),
            profileId = PROFILE
        )
        preferences.unmarkAsWatched("tt4", profileId = PROFILE)

        preferences.mergeArtwork(mapOf("tt3" to WatchedArt("new", "new-b"), "tt4" to WatchedArt("new", null)), PROFILE)

        // The poster it had is kept; only the missing backdrop is filled.
        assertEquals(
            listOf(Triple("tt3", "own", "new-b")),
            preferences.getAllItems(PROFILE).map { Triple(it.contentId, it.poster, it.backdrop) }
        )
    }

    @Test
    fun `sync writes and new marks keep the title's hydrated art`() = runTest {
        preferences.markAsWatched(item("tt5", season = 1, episode = 1), profileId = PROFILE)
        preferences.mergeArtwork(mapOf("tt5" to WatchedArt("p5", "b5")), PROFILE)

        // Snapshot: the remote copy of the same episode carries no art.
        preferences.replaceWithRemoteItems(listOf(item("tt5", season = 1, episode = 1)), profileId = PROFILE)
        // Delta: a new episode from another device, then a local mark of a third.
        preferences.applyRemoteChanges(listOf(item("tt5", season = 1, episode = 2)), emptyList(), PROFILE)
        preferences.markAsWatched(item("tt5", season = 1, episode = 3), profileId = PROFILE)
        preferences.markAsWatchedBatch(listOf(item("tt5", season = 1, episode = 4)), profileId = PROFILE)

        val stored = preferences.getAllItems(PROFILE)
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.poster == "p5" && it.backdrop == "b5" })
    }

    private fun item(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        watchedAt: Long = 100L,
        type: String = if (season != null) "series" else "movie"
    ) = WatchedItem(
        contentId = contentId,
        contentType = type,
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = watchedAt
    )

    private fun meta(id: String, poster: String?, backdrop: String?) = Meta(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        links = emptyList(),
        country = null,
        awards = null,
        language = null
    )

    private companion object {
        const val PROFILE = 2
        const val OTHER = 4
    }
}
