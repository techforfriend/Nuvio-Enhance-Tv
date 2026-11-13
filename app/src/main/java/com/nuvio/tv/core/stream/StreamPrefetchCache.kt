package com.nuvio.tv.core.stream

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PrefetchedSelection
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S4a: run the addon scrape while the details page is open, so pressing Play
 * does not start it from cold.
 *
 * Measured 24 Jul 2026 (Xiaomi S905X5M, auto-play "Best quality", 4K TB
 * Instant): `streams_load_start -> sources_ready` is 1,949-2,896 ms and is paid
 * on every play. It cannot begin any earlier today because
 * StreamRepositoryImpl.getStreamsFromAllAddons() is a cold flow with no cache
 * of any kind, and it is first collected from StreamScreenViewModel.init{} —
 * i.e. only once the stream screen exists, which is after the Play press.
 *
 * The details page already knows the exact target before the press: the hero
 * button resolves NextToWatch ("Next S1 E2" / "Resume ..."), and the episode
 * list reports focus through an existing callback. Both are handed here.
 *
 * Design notes:
 * - Single-flight, and at most ONE prefetch in flight. A new request cancels
 *   the previous one, so scanning an episode list cannot fan out N concurrent
 *   scrapes across every addon.
 * - Completed results are kept (2 entries, LRU, 5 min TTL), so moving back to
 *   an episode already prefetched is free.
 * - [streamsFor] substitutes the flow rather than bypassing the consumer. A hit
 *   emits Loading then one Success then completes, which is what a very fast
 *   scrape looks like: StreamScreenViewModel's existing collect applies it with
 *   isAllLoaded=false, and its post-collect line then applies isAllLoaded=true.
 *   Auto-select timing and binge-group matching are untouched.
 * - A join that times out, is superseded, or yields nothing falls through to
 *   the live flow, so the worst case is today's behaviour.
 *
 * TTL rationale: entries carry addon-resolved links, which are time-sensitive.
 * Five minutes is short enough that a stale link is unlikely and long enough to
 * cover reading a details page.
 */
object StreamPrefetchCache {

    private const val TAG = "StreamPrefetch"
    private const val TTL_MS = 5L * 60L * 1000L
    private const val MAX_ENTRIES = 2
    private const val JOIN_TIMEOUT_MS = 20_000L

    /** Outlives any ViewModel: the details screen may be gone before this finishes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(
        val streams: List<AddonStreams>,
        val atMs: Long,
        /** R2: winner ranked at prefetch time, null when no ranker was supplied. */
        val selection: PrefetchedSelection?,
        /**
         * True when this pool was cut short by the prefetch completion cap
         * (see collectFinal). The scrape kept running on the session cache's
         * own scope and holds the full set, so a hit on a capHit entry must
         * re-collect the session to fill the manual list rather than treating
         * this subset as final.
         */
        val capHit: Boolean = false
    )

    private val lock = Any()
    private val completed = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var inFlightKey: String? = null
    private var inFlightJob: Deferred<List<AddonStreams>>? = null

    /**
     * True when the in-flight job was started by a background warmer.
     * Consulted only while [inFlightJob] is active, so a stale value after the
     * job clears itself is harmless. Guarded by [lock] like its siblings.
     */
    private var inFlightBackground: Boolean = false

    /**
     * F-7: the late uncapped re-rank started when a hero prefetch hit its cap
     * with nothing arrived. Kept outside the in-flight slot so a [streamsFor]
     * join never waits on it; cancelled by the next ui-owned prefetch of any
     * kind (a newer target, or a dedup onto one). Guarded by [lock].
     */
    private var lateRankJob: Job? = null

    fun keyOf(type: String, videoId: String, season: Int?, episode: Int?): String {
        return type + "|" + videoId + "|" + (season ?: -1) + "|" + (episode ?: -1)
    }

    /** Caller must hold [lock]. Prunes and returns null for an expired entry. */
    private fun freshEntryLocked(key: String): Entry? {
        val entry = completed[key] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
            completed.remove(key)
            return null
        }
        return entry
    }

    /** Test seam: cancel any in-flight prefetch and drop all state. */
    internal fun resetForTest() {
        synchronized(lock) {
            inFlightJob?.cancel()
            inFlightJob = null
            inFlightKey = null
            inFlightBackground = false
            lateRankJob?.cancel()
            lateRankJob = null
            completed.clear()
        }
    }

    /** Caller must hold [lock]. */
    private fun putLocked(
        key: String,
        streams: List<AddonStreams>,
        selection: PrefetchedSelection?,
        capHit: Boolean
    ) {
        completed[key] = Entry(streams, SystemClock.elapsedRealtime(), selection, capHit)
        while (completed.size > MAX_ENTRIES) {
            val eldest = completed.keys.firstOrNull() ?: break
            completed.remove(eldest)
        }
    }

    /**
     * Start (or keep) a prefetch for this target. Cheap and idempotent: a fresh
     * completed entry or an identical in-flight job is a no-op.
     */
    fun prefetch(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        /** The title, for sources that resolve by search instead of by id. */
        title: String? = null,
        /** Which producer started this prefetch, for log attribution. */
        source: String,
        /**
         * S4a-3b lane fix: a background warmer (cw, cw_focus, binge_lookahead)
         * must never cancel a ui-owned scrape. The details page's hero/episode
         * prefetches drive the visible source line; the pre-fix behaviour let a
         * Continue Watching reshuffle (e.g. a progress sync landing while the
         * details page is open on the back stack) cancel that scrape before its
         * ranker ran, so the uiSignal never published and the line sat on
         * SEARCHING with nothing behind it. A background caller finding a
         * ui-owned job in flight for a DIFFERENT key now yields (no-op) instead
         * of cancelling; background-vs-background keeps today's
         * cancel-and-replace so scanning the CW row stays single-flight.
         * Ui-owned callers (default) cancel anything, exactly as before.
         */
        background: Boolean = false,
        /**
         * P2 completion cap. When non-null, the scrape collection stops waiting
         * after this many ms and ranks/prewarms on whatever arrived, rather
         * than blocking on the slowest source. Null reproduces pre-P2 behaviour
         * (wait for full completion). The rank and prewarm are unchanged;
         * only the size of the pool they see can be smaller under the cap.
         * A hero caller whose cap hits with nothing arrived also gets a late
         * uncapped re-rank ([lateRankJob], F-7).
         */
        capMs: Long? = null,
        /**
         * R2: optional ranker, invoked on the prefetch's own IO coroutine
         * once the scrape completes. Null reproduces pre-R2 behaviour
         * exactly, which is how the S4a details-page path stays untouched.
         * The cache stays ignorant of settings storage: it invokes an opaque
         * supplier and stores an opaque result.
         */
        rank: (suspend (List<AddonStreams>) -> PrefetchedSelection?)? = null,
        /**
         * Opt-in: re-run [rank] to publish THIS caller's uiSignal when the
         * prefetch is deduped (fresh cache hit or in-flight). Only callers that
         * own a hero uiKey (details_hero) need it; background warmers such as
         * binge_lookahead and cw call prefetch() per tick and must leave this
         * false so their repeat calls stay dedup no-ops (no republish storm,
         * and a no-uiKey warmer can never clobber the hero signal).
         */
        republishOnDedup: Boolean = false
    ) {
        if (type.isBlank() || videoId.isBlank()) return
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            // F-7: any ui-owned prefetch, dedup or not, retires a late re-rank.
            // Background warmers yield to ui work, so they never do.
            if (!background) {
                lateRankJob?.cancel()
                lateRankJob = null
            }
            val cached = freshEntryLocked(key)?.streams
            if (cached != null) {
                // A fresh entry already exists (Continue-Watching, or a previous
                // open of this same detail page, pre-warmed this episode within
                // the TTL). The scrape is done, but THIS caller's ranker has not
                // run, so its uiSignal -- keyed on the caller's uiKey -- is never
                // published, and a details_hero caller's hero source line stays
                // stuck in SEARCHING. Re-invoke the supplied ranker on the cached
                // groups (no re-scrape) so this caller's uiSignal is published.
                if (republishOnDedup && rank != null) {
                    launchRank(rank, "cache-republish", source, key, logGroups = true) { cached }
                }
                return
            }
            val existing = inFlightJob
            if (inFlightKey == key && existing != null && existing.isActive) {
                // A prefetch for this key is already in flight (e.g. binge_lookahead
                // warming the next episode during playback). That job runs ITS OWN
                // ranker/uiKey (binge passes none), so THIS caller's uiSignal is
                // never published -- a details_hero caller returning to the detail
                // page while the lookahead is still scraping would sit in SEARCHING.
                // Chain this caller's ranker onto the in-flight result so its
                // uiSignal is published once that scrape completes (no re-scrape).
                //
                // A ui caller now depends on this job, so a later background
                // warmer must yield to it rather than cancel it.
                if (!background) inFlightBackground = false
                if (republishOnDedup && rank != null) {
                    launchRank(rank, "inflight-republish", source, key, logGroups = true) {
                        try {
                            existing.await()
                        } catch (e: CancellationException) {
                            if (awaitedJobCancelled(existing)) emptyList() else throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                return
            }
            if (background && existing != null && existing.isActive && !inFlightBackground) {
                // Never cancel a ui-owned scrape from a background warmer: the
                // ui caller's ranker/uiSignal would be lost and its source line
                // stuck. The warm is best-effort; skipping it is the safe side.
                Log.i(
                    TAG,
                    "PREFETCH yield source=$source key=$key: " +
                        "ui-owned prefetch in flight key=$inFlightKey"
                )
                return
            }
            existing?.cancel()
            inFlightKey = key
            inFlightBackground = background
            // LAZY so the body's cleanup can compare the slot against this exact
            // job; it is started below while still under the lock.
            lateinit var job: Deferred<List<AddonStreams>>
            val rankReturned = AtomicBoolean(false)
            job = scope.async(start = CoroutineStart.LAZY) {
                val (result, capHit) = collectFinal(repository, type, videoId, season, episode, capMs)
                val rankT0 = SystemClock.elapsedRealtime()
                // Fork (B2): rank even when the result is empty, as the dedup
                // and superseded paths (launchRank) do too. rankAndPrewarm
                // publishes a terminal EMPTY uiSignal for this caller's uiKey
                // when it has nothing to pick, so the hero source line hides
                // instead of spinning on SEARCHING forever; likewise a
                // cancelled AWAITED scrape (awaitedJobCancelled) must still
                // yield that terminal signal.
                val selection = if (rank != null) {
                    try {
                        rank(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "PREFETCH rank failed: ${e.message}")
                        null
                    }
                } else {
                    null
                }
                rankReturned.set(true)
                if (rank != null) {
                    val winnerLabel = if (selection != null) "yes" else "none"
                    Log.i(
                        TAG,
                        // total_ms brackets the SUPPLIED ranker, which for the
                        // S4a/S4a-2/S4a-3 callers is rankAndPrewarm -- so it
                        // covers rank AND, except for the focus callers (T30:
                        // rank only), the prewarm. The rank-only component is
                        // logged separately by the supplier (PREFETCH rank_only).
                        "PREFETCH rank groups=${result.size} " +
                            "winner=$winnerLabel " +
                            "total_ms=${SystemClock.elapsedRealtime() - rankT0}"
                    )
                }
                synchronized(lock) {
                    if (result.isNotEmpty()) putLocked(key, result, selection, capHit)
                    // Identity, not key: a cancelled job still finishing a
                    // non-suspending rank must not clear a newer job's slot.
                    if (inFlightJob === job) {
                        inFlightKey = null
                        inFlightJob = null
                        // F-7: the cap cut the collector before any group
                        // arrived, so the rank above published a terminal
                        // EMPTY. The scrape runs on in the session cache;
                        // re-collect it uncapped and rank the full pool so a
                        // slow addon's result still reaches the hero line.
                        // Not superseded (identity check): no newer prefetch
                        // has started a scrape since this one.
                        if (capHit && result.isEmpty() && republishOnDedup && rank != null) {
                            lateRankJob = launchRank(rank, "late-rank", source, key, logGroups = true) {
                                collectFinal(repository, type, videoId, season, episode, capMs = null).streams
                            }
                        }
                    }
                }
                result
            }
            if (republishOnDedup && rank != null) {
                // Superseded (cancelled by a newer prefetch) before its ranker
                // returned: rank the empty pool so this caller's uiKey gets a
                // terminal EMPTY instead of spinning on SEARCHING. A ranker that
                // did return already published its own signal; keep it.
                job.invokeOnCompletion { cause ->
                    if (cause is CancellationException && !rankReturned.get()) {
                        launchRank(rank, "superseded", source, key, logGroups = false) { emptyList() }
                    }
                }
            }
            inFlightJob = job
            job.start()
        }
        Log.i(TAG, "PREFETCH start source=$source key=$key")
    }

    /**
     * Runs [rank] on [pool] in the cache's own scope, logging
     * "PREFETCH <label> source=.. key=..[ groups=..]" on success or
     * "PREFETCH <label> rank failed: .." on failure. [pool] handles its own errors.
     * Returns the launched job.
     */
    private fun launchRank(
        rank: suspend (List<AddonStreams>) -> PrefetchedSelection?,
        label: String,
        source: String,
        key: String,
        logGroups: Boolean,
        pool: suspend () -> List<AddonStreams>
    ): Job =
        scope.launch {
            val streams = pool()
            try {
                rank(streams)
                val groups = if (logGroups) " groups=${streams.size}" else ""
                Log.i(TAG, "PREFETCH $label source=$source key=$key$groups")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "PREFETCH $label rank failed: ${e.message}")
            }
        }

    /**
     * True when a CancellationException from awaiting [job] means [job] itself
     * was cancelled (superseded) while the awaiting coroutine is still live;
     * false means the awaiter is being cancelled and must rethrow.
     */
    private suspend fun awaitedJobCancelled(job: Job): Boolean =
        job.isCancelled && currentCoroutineContext().isActive

    private data class CollectFinalResult(
        val streams: List<AddonStreams>,
        val capHit: Boolean
    )

    private suspend fun collectFinal(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        capMs: Long?
    ): CollectFinalResult {
        var last: List<AddonStreams> = emptyList()
        var capHit = false
        // Each Success emission from the repository carries the FULL accumulated
        // pool, so `last` after the collect (or after the cap cancels it) always
        // holds the most complete set seen. Under the cap, withTimeoutOrNull
        // cancels only THIS collector; the scrape itself runs on the session
        // cache's own scope and continues to completion, so the full pool stays
        // re-collectable from the session (that is what a capHit hit does).
        val collectBlock: suspend () -> Unit = {
            repository.getStreamsFromAllAddons(type, videoId, season, episode, title = title).collect { result ->
                if (result is NetworkResult.Success) last = result.data
            }
        }
        try {
            if (capMs != null) {
                if (withTimeoutOrNull(capMs) { collectBlock() } == null) {
                    capHit = true
                    Log.i(TAG, "PREFETCH cap-hit ms=$capMs groups=${last.size}")
                }
            } else {
                collectBlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PREFETCH failed: ${e.message}")
            return CollectFinalResult(emptyList(), false)
        }
        Log.i(TAG, "PREFETCH done groups=${last.size}")
        return CollectFinalResult(last, capHit)
    }

    /**
     * R2: the winner ranked during the prefetch, or null.
     *
     * Read at presentation time rather than at [streamsFor] time, so a prefetch
     * that completes during the join is still usable. A join that falls through
     * to the live flow returns null here and the caller ranks live.
     */
    fun selectionFor(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): PrefetchedSelection? {
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            return freshEntryLocked(key)?.selection
        }
    }

    /**
     * True when the completed prefetch for this target was cut short by the
     * completion cap. Read at presentation time by the ViewModel to decide
     * whether still-loading source chips are genuinely dead or merely late
     * (alive in the session and re-collectable). No TTL prune here; an evicted
     * entry simply yields a conservative false.
     */
    fun capHitFor(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Boolean {
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            return completed[key]?.capHit ?: false
        }
    }

    /**
     * The stream list for this target: a completed prefetch, a join onto one in
     * flight, or the live repository flow. Drop-in for the repository call.
     */
    fun streamsFor(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
        /** The title, for sources that resolve by search instead of by id. */
        title: String? = null
    ): Flow<NetworkResult<List<AddonStreams>>> {
        // 0.8.5 source-refresh: a user refresh must bypass BOTH caches -- this
        // prefetch layer (hit/join below) AND the repository session cache
        // (forwarded via getStreamsFromAllAddons). Skipping the hit/join here
        // means a refresh always reaches a live scrape rather than replaying a
        // warmed or in-flight prefetch.
        if (forceRefresh) {
            return repository.getStreamsFromAllAddons(type, videoId, season, episode, forceRefresh = true, title = title)
        }
        val key = keyOf(type, videoId, season, episode)
        var hit: List<AddonStreams>? = null
        var join: Deferred<List<AddonStreams>>? = null
        synchronized(lock) {
            hit = freshEntryLocked(key)?.streams
            if (hit == null && inFlightKey == key) {
                val running = inFlightJob
                if (running != null && running.isActive) join = running
            }
        }

        val hitData = hit
        if (hitData != null) {
            Log.i(TAG, "PREFETCH hit key=$key groups=${hitData.size}")
            return flow {
                emit(NetworkResult.Loading)
                emit(NetworkResult.Success(hitData))
            }
        }

        val joinJob = join
        if (joinJob != null) {
            Log.i(TAG, "PREFETCH join key=$key")
            return flow {
                emit(NetworkResult.Loading)
                val joined = try {
                    withTimeoutOrNull(JOIN_TIMEOUT_MS) { joinJob.await() }
                } catch (e: CancellationException) {
                    // A superseded joined prefetch falls through to the live scrape below.
                    if (awaitedJobCancelled(joinJob)) null else throw e
                } catch (e: Exception) {
                    null
                }
                if (joined != null && joined.isNotEmpty()) {
                    emit(NetworkResult.Success(joined))
                } else {
                    Log.i(TAG, "PREFETCH join empty; falling back to live scrape")
                    emitAll(repository.getStreamsFromAllAddons(type, videoId, season, episode, title = title))
                }
            }
        }

        Log.i(TAG, "PREFETCH miss key=$key")
        return repository.getStreamsFromAllAddons(type, videoId, season, episode, title = title)
    }
}
