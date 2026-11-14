package com.nuvio.tv.livetv.source

import android.util.Log
import com.nuvio.tv.livetv.player.PlaybackActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the channel pack from this TV (Spec 73 S3a, D4/D5, 60/D11): every channel of the chosen
 * countries, [workers] at a time, each tried stream by stream ([PackProbe]) until one answers; the
 * streams after it keep the monitor's state; at most [PER_HOST] requests per host at once, each
 * request counted against its own host ([HostSlots], D15 Q3). Runs in its own app-lifetime scope,
 * so it goes on behind any screen and stops with the process (results to the last apply kept).
 * Waits before each probe while [playing] (Spec 56); a probe already running finishes. Every [APPLY_EVERY] new
 * results or [APPLY_INTERVAL_MS], and at the end or on [cancel], hands its results, the keys it
 * returned to the monitor's state and its record to [applyResults] ([LiveSourceManager.applyScan]:
 * stored, the pack listed anew); a cancelled scan keeps what it checked. Stops when
 * [applyResults] reports the pack removed. One scan at a time.
 */
@Singleton
class PackScanner internal constructor(
    private val packs: PackStore,
    private val scans: PackScanStore,
    private val applyResults: suspend (Map<String, PackScanResult>, Set<String>, PackScanRecord) -> Boolean,
    private val probe: suspend (PackStream, HostSlots) -> Boolean,
    private val playing: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val workers: Int = WORKERS
) {
    @Inject
    constructor(
        packs: PackStore,
        scans: PackScanStore,
        sources: LiveSourceManager,
        probe: PackProbe,
        activity: PlaybackActivity
    ) : this(
        packs,
        scans,
        sources::applyScan,
        probe::probe,
        activity.playing,
        // A bug in a scan ends that scan (its finally still applies), never the app.
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "Scan failed: ${e.javaClass.simpleName}") }
        ),
        System::currentTimeMillis
    )

    sealed interface State {
        data object Idle : State

        /**
         * A scan so far ([PackScanRecord.endedMs] null); [paused] while something plays (since
         * [pausedSinceMs]); [pausedMs] = the pauses that ended, for the time left (S3c: a pace
         * over paused time overstated it).
         */
        data class Running(
            val record: PackScanRecord,
            val paused: Boolean = false,
            val pausedMs: Long = 0,
            val pausedSinceMs: Long? = null
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _ended = MutableSharedFlow<PackScanRecord>(extraBufferCapacity = 1)

    /** Each scan's final record as it ends (done or cancelled), once stored; not replayed. */
    val ended: SharedFlow<PackScanRecord> = _ended.asSharedFlow()

    private var job: Job? = null

    /**
     * Starts a scan of the pack's channels in [countries] (app country codes, null = no country
     * listed); false when one runs already (a cancelled one too, until its last apply is done) or
     * [countries] is empty.
     */
    @Synchronized
    fun start(countries: Set<String?>): Boolean {
        if (job?.isCompleted == false || countries.isEmpty()) return false
        // Running at once (its channel count follows once the pack is read), so a caller that shows
        // the progress panel never finds the scan Idle.
        _state.value = State.Running(PackScanRecord(sortedCountries(countries), nowMs(), channels = 0))
        val launched = scope.launch { run(countries) }
        job = launched
        // The one owner of the scan's end (D13): however the job ends (done, cancelled before its
        // body ran, failed), its completion sets Idle, unless a newer scan is current by then.
        launched.invokeOnCompletion { endIfCurrent(launched) }
        return true
    }

    @Synchronized
    private fun endIfCurrent(ended: Job) {
        if (job === ended) _state.value = State.Idle
    }

    /** Cancels the running scan; what it checked is kept. */
    @Synchronized
    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(countries: Set<String?>) {
        val scan = currentCoroutineContext().job
        val before = scans.read().results
        val chosen = packs.read().filter { it.country in countries }
        val results = ConcurrentHashMap<String, PackScanResult>()
        val cleared: MutableSet<String> = ConcurrentHashMap.newKeySet()
        // A probe never starts beside playback (HostSlots); not idle until the first reading of [playing].
        val idle = MutableStateFlow(false)
        val hosts = HostSlots(PER_HOST, idle)
        _state.value = State.Running(PackScanRecord(sortedCountries(countries), nowMs(), channels = chosen.size))
        var completed = false
        try {
            coroutineScope {
                val pauses = launch {
                    playing.collect { paused ->
                        updateRunning { it.pausing(paused, nowMs()) }
                        idle.value = !paused
                    }
                }
                val applier = launch { applyLoop(results, cleared) { scan.cancel() } }
                val next = AtomicInteger()
                List(workers) { launch { work(chosen, next, before, results, cleared, hosts) } }.joinAll()
                pauses.cancel()
                applier.cancel()
            }
            completed = true
        } finally {
            withContext(NonCancellable) {
                (_state.value as? State.Running)?.record?.let { running ->
                    val ended = running.copy(endedMs = nowMs(), cancelled = !completed)
                    if (applyResults(HashMap(results), HashSet(cleared), ended)) _ended.tryEmit(ended)
                }
            }
        }
    }

    /**
     * Takes channels from [next] until none is left; each stops at its first stream that answers,
     * and the streams after it go back to the monitor's state ([cleared]: an older scan's result
     * for them is dropped).
     */
    private suspend fun work(
        chosen: List<PackChannel>,
        next: AtomicInteger,
        before: Map<String, PackScanResult>,
        results: MutableMap<String, PackScanResult>,
        cleared: MutableSet<String>,
        hosts: HostSlots
    ) {
        while (true) {
            val channel = chosen.getOrNull(next.getAndIncrement()) ?: return
            var plays = false
            for ((i, stream) in channel.streams.withIndex()) {
                plays = probe(stream, hosts)
                results[ChannelPack.scanKey(stream.url)] = PackScanResult(plays, nowMs())
                if (plays) {
                    channel.streams.drop(i + 1).mapTo(cleared) { ChannelPack.scanKey(it.url) }
                    break
                }
            }
            val isNew = plays && ChannelPack.visibleStreams(channel, before).isEmpty()
            updateRunning { it.copy(record = it.record.counted(plays, isNew, channel.name)) }
        }
    }

    /** Applies every [APPLY_EVERY] new results or each [APPLY_INTERVAL_MS] with new ones; [stop] when the pack is gone. */
    private suspend fun applyLoop(results: Map<String, PackScanResult>, cleared: Set<String>, stop: () -> Unit) {
        var applied = 0
        var waitedMs = 0L
        while (true) {
            delay(APPLY_POLL_MS)
            waitedMs += APPLY_POLL_MS
            val size = results.size
            if (size == applied) waitedMs = 0 // nothing new (paused): no write
            if (size - applied < APPLY_EVERY && waitedMs < APPLY_INTERVAL_MS) continue
            val record = (_state.value as? State.Running)?.record ?: return
            applied = size
            waitedMs = 0
            if (!applyResults(HashMap(results), HashSet(cleared), record)) stop()
        }
    }

    private fun sortedCountries(countries: Set<String?>) = countries.sortedWith(nullsLast(naturalOrder()))

    private fun updateRunning(change: (State.Running) -> State.Running) =
        _state.update { (it as? State.Running)?.let(change) ?: it }

    private fun State.Running.pausing(paused: Boolean, now: Long): State.Running = when {
        paused && pausedSinceMs == null -> copy(paused = true, pausedSinceMs = now)
        !paused && pausedSinceMs != null ->
            copy(paused = false, pausedMs = pausedMs + (now - pausedSinceMs).coerceAtLeast(0), pausedSinceMs = null)
        else -> copy(paused = paused)
    }

    private fun PackScanRecord.counted(plays: Boolean, isNew: Boolean, name: String) = copy(
        checked = checked + 1,
        playing = playing + if (plays) 1 else 0,
        hidden = hidden + if (plays) 0 else 1,
        added = added + if (isNew) 1 else 0,
        addedNames = if (isNew && addedNames.size < ADDED_NAMES) addedNames + name else addedNames
    )

    internal companion object {
        /** In flight at once: [PackProbe.MAX_REQUESTS]. */
        const val WORKERS = PackProbe.MAX_REQUESTS
        const val PER_HOST = PackProbe.MAX_PER_HOST
        const val APPLY_EVERY = 1_000
        const val APPLY_INTERVAL_MS = 30_000L
        const val APPLY_POLL_MS = 1_000L
        /** Names the result panel shows of the channels a scan added ("Among them ..."). */
        const val ADDED_NAMES = 2
        private const val TAG = "PackScanner"
    }
}
