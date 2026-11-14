package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 64 K1c: Continue Watching under the active profile's cap. */
class ContinueWatchingRatingGateTest {
    private fun inProgress(id: String, type: String = "movie") = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = id, contentType = type, name = id, poster = null, backdrop = null, logo = null,
            videoId = id, season = null, episode = null, episodeTitle = null, position = 1, duration = 10,
            lastWatched = 1
        )
    )

    private fun nextUp(id: String) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
            videoId = "$id:1:2", season = 1, episode = 2, episodeTitle = null, thumbnail = null,
            lastWatched = 1, sortTimestamp = 1
        )
    )

    private val lookups = mutableListOf<String>()
    private var answers: Map<String, CwRatingLookup> = emptyMap()

    private fun CoroutineScope.gate(
        source: StateFlow<HomeUiState>,
        cap: suspend () -> ContentRatingCap?
    ): StateFlow<HomeUiState> =
        ContinueWatchingRatingGate(this, cap) { type, id ->
            lookups += "$type:$id"
            answers[id] ?: CwRatingLookup.Failed
        }.gate(source)

    private fun StateFlow<HomeUiState>.ids() =
        (value.continueWatchingItems + value.upcomingItems).map {
            when (it) {
                is ContinueWatchingItem.InProgress -> it.progress.contentId
                is ContinueWatchingItem.NextUp -> it.info.contentId
            }
        }

    @Test
    fun `uncapped profile passes the state through with no lookups`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        val gated = backgroundScope.gate(source) { null }
        runCurrent()
        assertSame(source.value, gated.value)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `continue watching stays hidden until the cap has loaded`() = runTest {
        val capRead = CompletableDeferred<ContentRatingCap?>()
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        val gated = backgroundScope.gate(source) { capRead.await() }
        runCurrent()
        assertTrue(gated.ids().isEmpty())
        capRead.complete(null)
        runCurrent()
        assertEquals(listOf("a"), gated.ids())
    }

    @Test
    fun `capped profile sees only titles whose looked-up rating is within the cap`() = runTest {
        answers = mapOf(
            "kid" to CwRatingLookup.Found("TV-Y7"),
            "adult" to CwRatingLookup.Found("TV-MA"),
            "unrated" to CwRatingLookup.Found(null),
            "soon" to CwRatingLookup.Found("PG"),
            "soon-teen" to CwRatingLookup.Found("TV-14")
        )
        val source = MutableStateFlow(
            HomeUiState(
                continueWatchingItems = listOf(inProgress("kid", "series"), nextUp("adult"), inProgress("unrated")),
                upcomingItems = listOf(nextUp("soon"), nextUp("soon-teen"))
            )
        )
        val gated = backgroundScope.gate(source) { ContentRatingCap.OLDER_KIDS }
        runCurrent()
        assertEquals(listOf("kid", "soon"), gated.ids())
    }

    @Test
    fun `a title already being looked up is not requested again`() = runTest {
        val answer = CompletableDeferred<CwRatingLookup>()
        var calls = 0
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        val gated = ContinueWatchingRatingGate(backgroundScope, { ContentRatingCap.ALL_AGES }) { _, _ ->
            calls++
            answer.await()
        }.gate(source)
        runCurrent()
        repeat(3) {
            source.value = source.value.copy(isLoading = !source.value.isLoading)
            runCurrent()
        }
        assertEquals(1, calls)
        answer.complete(CwRatingLookup.Found("G"))
        runCurrent()
        assertEquals(listOf("a"), gated.ids())
        assertEquals(1, calls)
    }

    @Test
    fun `a failed lookup is retried after the delay`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        val gated = backgroundScope.gate(source) { ContentRatingCap.ALL_AGES }
        runCurrent()
        assertTrue(gated.ids().isEmpty())
        answers = mapOf("a" to CwRatingLookup.Found("TV-G"))
        advanceTimeBy(ContinueWatchingRatingGate.FIRST_RETRY_DELAY_MS + 1)
        runCurrent()
        assertEquals(listOf("a"), gated.ids())
        assertEquals(2, lookups.size)
    }

    @Test
    fun `retries stop once the title has left continue watching`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        backgroundScope.gate(source) { ContentRatingCap.ALL_AGES }
        runCurrent()
        source.value = HomeUiState(continueWatchingItems = listOf(inProgress("b")))
        runCurrent()
        advanceTimeBy(ContinueWatchingRatingGate.MAX_RETRY_DELAY_MS * 4)
        runCurrent()
        // a: first try only; b: first try plus its back-off retries.
        assertEquals(1, lookups.count { it == "movie:a" })
        assertTrue(lookups.count { it == "movie:b" } > 1)
    }

    @Test
    fun `a title that returns after its retries stopped is looked up again`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        val gated = backgroundScope.gate(source) { ContentRatingCap.ALL_AGES }
        runCurrent()
        source.value = HomeUiState()
        advanceTimeBy(ContinueWatchingRatingGate.FIRST_RETRY_DELAY_MS + 1)
        runCurrent()
        answers = mapOf("a" to CwRatingLookup.Found("G"))
        source.value = HomeUiState(continueWatchingItems = listOf(inProgress("a")))
        runCurrent()
        assertEquals(listOf("movie:a", "movie:a"), lookups)
        assertEquals(listOf("a"), gated.ids())
    }

    @Test
    fun `retries back off from 15 s, doubling`() = runTest {
        val source = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(inProgress("a"))))
        backgroundScope.gate(source) { ContentRatingCap.ALL_AGES }
        runCurrent()
        assertEquals(1, lookups.size)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, lookups.size)
        advanceTimeBy(29_000); runCurrent()
        assertEquals(2, lookups.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, lookups.size)
    }

    @Test
    fun `meta lookup tries the bare tmdb id and treats a found meta as final`() = runTest {
        val metaRepository = mockk<MetaRepository> {
            every { getMetaFromAllAddons("movie", "tmdb:5", any()) } returns flowOf(NetworkResult.Error("missing"))
            every { getMetaFromAllAddons("movie", "5", any()) } returns
                flowOf(NetworkResult.Success(mockk<Meta> { every { ageRating } returns null }))
        }
        val lookup = ContinueWatchingRatingGate.metaRepositoryLookup(metaRepository)
        assertEquals(CwRatingLookup.Found(null), lookup("movie", "tmdb:5"))
        verify(exactly = 1) { metaRepository.getMetaFromAllAddons("movie", "tmdb:5", any()) }
        verify(exactly = 1) { metaRepository.getMetaFromAllAddons("movie", "5", any()) }
    }

    @Test
    fun `meta lookup that times out or throws fails for a retry`() = runTest {
        val metaRepository = mockk<MetaRepository> {
            every { getMetaFromAllAddons("movie", "slow", any()) } returns flow { awaitCancellation() }
            every { getMetaFromAllAddons("movie", "boom", any()) } returns flow { throw IllegalStateException("x") }
        }
        val lookup = ContinueWatchingRatingGate.metaRepositoryLookup(metaRepository)
        assertEquals(CwRatingLookup.Failed, lookup("movie", "slow"))
        assertEquals(CwRatingLookup.Failed, lookup("movie", "boom"))
    }

    @Test(expected = CancellationException::class)
    fun `meta lookup lets cancellation through`() = runTest {
        val metaRepository = mockk<MetaRepository> {
            every { getMetaFromAllAddons(any(), any(), any()) } returns flow { throw CancellationException("stop") }
        }
        ContinueWatchingRatingGate.metaRepositoryLookup(metaRepository)("movie", "tt1")
    }

    @Test
    fun `meta lookup with no meta anywhere fails for a retry`() = runTest {
        val metaRepository = mockk<MetaRepository> {
            every { getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Error("missing"))
        }
        val lookup = ContinueWatchingRatingGate.metaRepositoryLookup(metaRepository)
        assertEquals(CwRatingLookup.Failed, lookup("series", "tt1"))
        // series tries series and tv: the pipeline's type candidates.
        verify(exactly = 1) { metaRepository.getMetaFromAllAddons("tv", "tt1", any()) }
    }
}
