package com.nuvio.tv.core.stream

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamPrefetchCache is a process-wide object whose scope runs on
 * Dispatchers.IO, so these tests use runBlocking and wait on explicit
 * completion signals (bounded by [WAIT_MS]) rather than virtual time.
 */
class StreamPrefetchCacheTest {

    private class FakeRepo(
        private val respond: (videoId: String, call: Int) -> Flow<NetworkResult<List<AddonStreams>>>
    ) : StreamRepository {
        private val calls = ConcurrentHashMap<String, AtomicInteger>()

        override fun getStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
            forceRefresh: Boolean,
            title: String?
        ): Flow<NetworkResult<List<AddonStreams>>> =
            respond(videoId, calls.getOrPut(videoId) { AtomicInteger() }.getAndIncrement())
    }

    private fun groups(name: String) = listOf(AddonStreams(name, null, emptyList()))

    private val never: Flow<NetworkResult<List<AddonStreams>>> = flow { awaitCancellation() }

    @Before
    fun setUp() = StreamPrefetchCache.resetForTest()

    @After
    fun tearDown() = StreamPrefetchCache.resetForTest()

    @Test
    fun `join onto a superseded prefetch falls back to the live scrape`() = runBlocking {
        val live = groups("live")
        val prefetchCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "join-a" && call == 0 -> never.also { prefetchCalled.complete(Unit) }
                videoId == "join-a" -> flowOf(NetworkResult.Success(live))
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(repo, "movie", "join-a", null, null, source = "test")
        // Call 0 must be the prefetch's: superseding before its IO body runs would hand
        // call 0 (never) to the live fallback and hang (39/200 runs, 2026-10-06).
        withTimeout(WAIT_MS) { prefetchCalled.await() }
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "join-a", null, null)
        val emissions = async { joined.toList() }
        yield() // let the collector reach the join's await

        // A newer ui prefetch cancels the joined job.
        StreamPrefetchCache.prefetch(repo, "movie", "join-b", null, null, source = "test")

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(live)),
            withTimeout(WAIT_MS) { emissions.await() }
        )
    }

    @Test
    fun `empty scrape still runs the ranker`() = runBlocking {
        val ranked = CompletableDeferred<List<AddonStreams>>()
        val repo = FakeRepo { _, _ -> flowOf(NetworkResult.Success(emptyList())) }

        StreamPrefetchCache.prefetch(
            repo, "movie", "empty", null, null, source = "test",
            rank = { groups -> ranked.complete(groups); null }
        )

        assertEquals(emptyList<AddonStreams>(), withTimeout(WAIT_MS) { ranked.await() })
    }

    @Test
    fun `ui dedup onto a background job stops a later background warmer cancelling it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val warmed = groups("warmed")
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "bg-1" && call == 0 -> flow {
                    gate.await()
                    emit(NetworkResult.Success(warmed))
                }
                videoId == "bg-1" -> flowOf(NetworkResult.Success(groups("live")))
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(repo, "movie", "bg-1", null, null, source = "cw", background = true)
        // A ui caller dedups onto the background job and now depends on it.
        StreamPrefetchCache.prefetch(repo, "movie", "bg-1", null, null, source = "details_hero")
        // A later background warmer for another key must yield, not cancel.
        StreamPrefetchCache.prefetch(repo, "movie", "bg-2", null, null, source = "cw_focus", background = true)

        // Still a join onto bg-1 (a cancelled job would make this a live miss).
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "bg-1", null, null)
        val emissions = async { joined.toList() }
        gate.complete(Unit)

        assertEquals(
            listOf(NetworkResult.Loading, NetworkResult.Success(warmed)),
            withTimeout(WAIT_MS) { emissions.await() }
        )
    }

    @Test
    fun `superseded ui prefetch ranks an empty pool so its line terminates`() = runBlocking {
        val ranked = CompletableDeferred<List<AddonStreams>>()
        val repo = FakeRepo { _, _ -> never }

        StreamPrefetchCache.prefetch(
            repo, "movie", "hero-a", null, null, source = "details_hero",
            republishOnDedup = true,
            rank = { groups -> ranked.complete(groups); null }
        )
        StreamPrefetchCache.prefetch(repo, "movie", "hero-b", null, null, source = "details_hero")

        assertEquals(emptyList<AddonStreams>(), withTimeout(WAIT_MS) { ranked.await() })
    }

    @Test
    fun `superseding after the ranker returned keeps its signal`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val warmed = groups("warmed")
        val repo = FakeRepo { videoId, call ->
            when {
                videoId == "rank-a" && call == 0 -> flow {
                    gate.await()
                    emit(NetworkResult.Success(warmed))
                }
                videoId == "rank-a" -> flowOf(NetworkResult.Success(groups("live")))
                else -> never
            }
        }
        StreamPrefetchCache.prefetch(
            repo, "movie", "rank-a", null, null, source = "details_hero",
            republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit)
                // Supersede at the ranker's tail: nothing suspends after this, so
                // the ranker returns normally and the job then completes cancelled.
                StreamPrefetchCache.prefetch(repo, "movie", "rank-b", null, null, source = "details_hero")
                null
            }
        )
        val joined = StreamPrefetchCache.streamsFor(repo, "movie", "rank-a", null, null)
        val emissions = async { joined.toList() }
        yield()
        gate.complete(Unit)

        // The join resumes only after the job completed, i.e. after its completion handler ran.
        withTimeout(WAIT_MS) { emissions.await() }
        assertNull(withTimeoutOrNull(GRACE_MS) { sawEmpty.await() })
        assertEquals(listOf(warmed), pools.toList())
    }

    @Test
    fun `hero cap hit with no groups re-ranks the full pool late`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val sawFull = CompletableDeferred<Unit>()
        val lateCollecting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val full = groups("slow-addon")
        // Call 0 is the capped prefetch (sees nothing); call 1 the late re-collect.
        val repo = FakeRepo { id, call ->
            when {
                id == "late-a" && call == 0 -> never
                id == "late-a" -> flow {
                    lateCollecting.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(full))
                }
                else -> never
            }
        }

        StreamPrefetchCache.prefetch(
            repo, "movie", "late-a", null, null, source = "details_hero",
            capMs = CAP_MS, republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit) else sawFull.complete(Unit)
                null
            }
        )
        withTimeout(WAIT_MS) { sawEmpty.await() }
        // Wait for the late rank itself: earlier, the warmer could just yield to the hero job.
        withTimeout(WAIT_MS) { lateCollecting.await() }
        // A background warmer yields to ui work: it must not retire the late rank.
        StreamPrefetchCache.prefetch(repo, "movie", "late-bg", null, null, source = "cw", background = true)
        gate.complete(Unit)

        withTimeout(WAIT_MS) { sawFull.await() }
        assertEquals(listOf(emptyList(), full), pools.toList())
    }

    @Test
    fun `a newer ui prefetch cancels the late re-rank`() = runBlocking {
        val pools = Collections.synchronizedList(mutableListOf<List<AddonStreams>>())
        val sawEmpty = CompletableDeferred<Unit>()
        val lateCollecting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo { id, call ->
            when {
                id == "late-x" && call == 0 -> never
                id == "late-x" -> flow {
                    lateCollecting.complete(Unit)
                    gate.await()
                    emit(NetworkResult.Success(groups("slow-addon")))
                }
                else -> never
            }
        }

        StreamPrefetchCache.prefetch(
            repo, "movie", "late-x", null, null, source = "details_hero",
            capMs = CAP_MS, republishOnDedup = true,
            rank = { pool ->
                pools += pool
                if (pool.isEmpty()) sawEmpty.complete(Unit)
                null
            }
        )
        withTimeout(WAIT_MS) { sawEmpty.await() }
        withTimeout(WAIT_MS) { lateCollecting.await() }
        // The user moved to another target: the left one must not rank (or prewarm) late.
        StreamPrefetchCache.prefetch(repo, "movie", "late-y", null, null, source = "details_hero")
        gate.complete(Unit)

        delay(GRACE_MS)
        assertEquals(listOf(emptyList<AddonStreams>()), pools.toList())
    }

    @Test
    fun `cancelling a joined collector does not start a live scrape`() = runBlocking {
        val calls = AtomicInteger()
        val prefetchCalled = CompletableDeferred<Unit>()
        val repo = FakeRepo { _, _ ->
            calls.incrementAndGet()
            prefetchCalled.complete(Unit)
            never
        }
        StreamPrefetchCache.prefetch(repo, "movie", "cancel-join", null, null, source = "test")
        withTimeout(WAIT_MS) { prefetchCalled.await() }

        val atJoin = CompletableDeferred<Unit>()
        val collector = launch {
            StreamPrefetchCache.streamsFor(repo, "movie", "cancel-join", null, null)
                .collect { atJoin.complete(Unit) }
        }
        withTimeout(WAIT_MS) { atJoin.await() }
        yield() // collector suspends at the join's await
        collector.cancelAndJoin()

        assertEquals(1, calls.get())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        /** Grace for a wrongly launched rank to reach the IO dispatcher. */
        const val GRACE_MS = 200L
        /** Prefetch completion cap for the F-7 tests; the capped collect sees nothing. */
        const val CAP_MS = 50L
    }
}
