package com.nuvio.tv.livetv.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/** [PackScanner] with a fake probe and apply (Spec 73 S3a): order, stop-at-first-ok, cancel, pause, apply. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val probed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val applied: MutableList<Pair<Map<String, PackScanResult>, PackScanRecord>> =
        Collections.synchronizedList(mutableListOf())
    private val playing = MutableStateFlow(false)
    private val clearedApplied: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())
    private var packAdded = true
    private var applyGate: CompletableDeferred<Unit>? = null

    private fun stream(url: String, state: PackState = PackState.ALIVE) = PackStream(url, state)

    private fun channel(name: String, country: String?, vararg streams: PackStream) =
        PackChannel(key = "pack#nc:$name|${country.orEmpty()}", name = name, country = country, streams = streams.toList())

    private var applyFails = false

    private suspend fun TestScope.scanner(
        pack: List<PackChannel>,
        workers: Int = 1,
        stored: Map<String, PackScanResult> = emptyMap(),
        probe: suspend (PackStream) -> Boolean
    ): PackScanner {
        val io = UnconfinedTestDispatcher(testScheduler)
        val packs = PackStore(tmp.root, io).apply { save(pack) }
        val scans = PackScanStore(tmp.root, io).apply { save(PackScans(results = stored)) }
        return PackScanner(
            packs,
            scans,
            applyResults = { results, cleared, record ->
                if (record.cancelled) applyGate?.await()
                check(!applyFails) { "apply failed" }
                applied += results to record
                clearedApplied += cleared
                packAdded
            },
            // As PackProbe's first request does: the scan's slot for the stream's host and its start gate.
            probe = { stream, hosts ->
                hosts.withSlot(stream.url.toHttpUrl().host, first = true) {
                    probed += stream.url
                    probe(stream)
                }
            },
            playing = playing,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> }),
            nowMs = { testScheduler.currentTime },
            workers = workers
        )
    }

    @Test
    fun `each channel of the chosen countries stops at its first stream that answers`() = runTest {
        val pack = listOf(
            channel("A", "ca", stream("https://a/1"), stream("https://a/2", PackState.SUSPECT), stream("https://a/3")),
            channel("B", "uk", stream("https://b/1")),
            channel("C", null, stream("https://c/1"), stream("https://c/2"))
        )
        val scanner = scanner(pack) { it.url == "https://a/2" }
        assertTrue(scanner.start(setOf("ca", null)))
        advanceUntilIdle()

        assertEquals(listOf("https://a/1", "https://a/2", "https://c/1", "https://c/2"), probed)
        val (results, record) = applied.last()
        assertEquals(
            mapOf("https://a/1" to false, "https://a/2" to true, "https://c/1" to false, "https://c/2" to false),
            probed.associateWith { results.getValue(ChannelPack.scanKey(it)).ok }
        )
        assertEquals(listOf("ca", null), record.countries)
        assertEquals(2, record.channels)
        assertEquals(2, record.checked)
        assertEquals(1, record.playing)
        assertEquals(1, record.hidden)
        assertFalse(record.cancelled)
        assertEquals(testScheduler.currentTime, record.endedMs)
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        // The stream after the one that answered goes back to the monitor's state.
        assertEquals(setOf(ChannelPack.scanKey("https://a/3")), clearedApplied.last())
    }

    @Test
    fun `a cancelled scan refuses a new start until its last apply is done`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        applyGate = CompletableDeferred()
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { gate.await() }
        scanner.start(setOf("ca"))
        runCurrent()
        scanner.cancel()
        runCurrent()
        assertFalse(scanner.start(setOf("ca")))
        assertTrue(scanner.state.value is PackScanner.State.Running)
        applyGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertTrue(scanner.start(setOf("ca")))
        scanner.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `a thousand new results are applied before the interval`() = runTest {
        val pack = (1..1_200).map { channel("C$it", "ca", stream("https://c/$it")) }
        val scanner = scanner(pack) { delay(10); true }
        scanner.start(setOf("ca"))
        advanceTimeBy(PackScanner.APPLY_INTERVAL_MS / 2)
        val interim = applied.first()
        assertNull(interim.second.endedMs)
        assertTrue("${interim.first.size}", interim.first.size >= PackScanner.APPLY_EVERY)
        advanceUntilIdle()
    }

    @Test
    fun `a channel not listed before the scan and answering now counts as added`() = runTest {
        val pack = listOf(
            channel("Proved", "ca", stream("https://p/1", PackState.BLOCKED)),
            channel("Listed", "ca", stream("https://l/1")),
            channel("Rescued", "ca", stream("https://r/1"))
        )
        // Rescued failed the last scan, so it was hidden.
        val stored = mapOf(ChannelPack.scanKey("https://r/1") to PackScanResult(false, 0))
        val scanner = scanner(pack, stored = stored) { true }
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        val record = applied.last().second
        assertEquals(3, record.playing)
        assertEquals(2, record.added)
        assertEquals(listOf("Proved", "Rescued"), record.addedNames)
    }

    @Test
    fun `cancel keeps the results so far and marks the scan cancelled`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val pack = listOf(channel("A", "ca", stream("https://a/1")), channel("B", "ca", stream("https://b/1")))
        val scanner = scanner(pack) { if (it.url == "https://b/1") gate.await() else false }
        scanner.start(setOf("ca"))
        runCurrent()
        assertEquals(listOf("https://a/1", "https://b/1"), probed)
        assertFalse(scanner.start(setOf("ca")))
        scanner.cancel()
        advanceUntilIdle()

        val (results, record) = applied.single()
        assertEquals(setOf(ChannelPack.scanKey("https://a/1")), results.keys)
        assertTrue(record.cancelled)
        assertEquals(1, record.checked)
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertTrue(scanner.start(setOf("ca")))
        scanner.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `nothing is probed while something plays, and the state says paused`() = runTest {
        playing.value = true
        // The probe takes a moment, so the resumed scan is still running when the pause ends.
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { delay(1_000); true }
        scanner.start(setOf("ca"))
        advanceTimeBy(60_000)
        assertTrue(probed.isEmpty())
        assertTrue((scanner.state.value as PackScanner.State.Running).paused)
        // Paused with nothing new: no apply (no channel cache write beside playback).
        assertTrue(applied.isEmpty())

        playing.value = false
        runCurrent()
        // The time left's pace leaves the pause out (S3c).
        val resumed = scanner.state.value as PackScanner.State.Running
        assertEquals(false, resumed.paused)
        assertEquals(60_000L, resumed.pausedMs)
        assertNull(resumed.pausedSinceMs)
        // A second pause, during the probe, adds to the first.
        playing.value = true
        runCurrent()
        advanceTimeBy(500)
        playing.value = false
        runCurrent()
        assertEquals(60_500L, (scanner.state.value as PackScanner.State.Running).pausedMs)
        advanceUntilIdle()
        assertEquals(listOf("https://a/1"), probed)
        assertEquals(1, applied.last().second.playing)
    }

    @Test
    fun `results are applied while the scan runs, every interval with new ones`() = runTest {
        val pack = (1..4).map { channel("C$it", "ca", stream("https://c/$it")) }
        val scanner = scanner(pack) { delay(20_000); true }
        scanner.start(setOf("ca"))
        // Nothing new for the first 20 s (no apply), then 30 s counted from the first result.
        advanceTimeBy(55_000)
        val interim = applied.single()
        assertNull(interim.second.endedMs)
        assertEquals(2, interim.first.size)
        advanceUntilIdle()
        assertEquals(4, applied.last().first.size)
        assertEquals(4, applied.last().second.checked)
    }

    @Test
    fun `the scan stops when the pack is removed`() = runTest {
        val pack = (1..10).map { channel("C$it", "ca", stream("https://c/$it")) }
        val scanner = scanner(pack) { delay(20_000); true }
        packAdded = false
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        assertTrue(probed.size < pack.size)
        assertTrue(applied.last().second.cancelled)
        assertEquals(PackScanner.State.Idle, scanner.state.value)
    }

    @Test
    fun `workers share the channels, each probed once`() = runTest {
        val pack = (1..40).map { channel("C$it", "ca", stream("https://c/$it")) }
        val scanner = scanner(pack, workers = 16) { delay(1_000); it.url.endsWith("0") }
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        assertEquals(pack.map { it.streams.single().url }.sorted(), probed.sorted())
        assertEquals(4, applied.last().second.playing)
        assertEquals(36, applied.last().second.hidden)
    }

    @Test
    fun `at most four probes per host run at once, the rest in turn`() = runTest {
        val pack = (1..12).map { channel("A$it", "ca", stream("https://a.example/$it")) } +
            (1..4).map { channel("B$it", "ca", stream("https://b.example/$it")) }
        val inFlight = mutableMapOf<String, Int>()
        val peak = mutableMapOf<String, Int>()
        val scanner = scanner(pack, workers = 16) {
            val host = it.url.substringAfter("//").substringBefore('/')
            inFlight[host] = (inFlight[host] ?: 0) + 1
            peak[host] = maxOf(peak[host] ?: 0, inFlight.getValue(host))
            delay(1_000)
            inFlight[host] = inFlight.getValue(host) - 1
            true
        }
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        assertEquals(mapOf("a.example" to 4, "b.example" to 4), peak)
        assertEquals(16, applied.last().second.playing)
    }

    @Test
    fun `a probe waiting for its host's slot does not start while something plays`() = runTest {
        val pack = (1..5).map { channel("A$it", "ca", stream("https://a.example/$it")) }
        val scanner = scanner(pack, workers = 16) { delay(1_000); true }
        scanner.start(setOf("ca"))
        advanceTimeBy(500)
        assertEquals(4, probed.size)
        playing.value = true
        advanceTimeBy(5_000)
        assertEquals(4, probed.size)
        playing.value = false
        advanceUntilIdle()
        assertEquals(5, probed.size)
    }

    @Test
    fun `the scan ends idle even when its last apply throws`() = runTest {
        applyFails = true
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { true }
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertTrue(scanner.start(setOf("ca")))
        advanceUntilIdle()
    }

    @Test
    fun `a scan is Running from start, and its final record is announced once stored`() = runTest {
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { true }
        val ended = mutableListOf<PackScanRecord>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.ended.collect { ended += it } }
        assertTrue(scanner.start(setOf("ca")))
        assertTrue(scanner.state.value is PackScanner.State.Running)
        advanceUntilIdle()
        assertEquals(listOf(applied.last().second), ended)
        // Not announced when the pack was removed meanwhile (nothing stored).
        packAdded = false
        scanner.start(setOf("ca"))
        advanceUntilIdle()
        assertEquals(1, ended.size)
        collector.cancel()
    }

    @Test
    fun `a cancel right after start, before the scan first runs, still ends it`() = runTest {
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { true }
        assertTrue(scanner.start(setOf("ca")))
        scanner.cancel()
        advanceUntilIdle()
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertTrue(probed.isEmpty())
        assertTrue(applied.isEmpty())
        assertTrue(scanner.start(setOf("ca")))
        advanceUntilIdle()
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertEquals(listOf("https://a/1"), probed)
    }

    @Test
    fun `a cancel during the first store reads ends the scan Idle, with nothing stored or announced`() = runTest {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        PackStore(tmp.root, unconfined).save(listOf(channel("A", "ca", stream("https://a/1"))))
        // The scanner's stores read on a dispatcher this test advances by hand: the scan waits in its first read.
        val reads = StandardTestDispatcher()
        val ended = mutableListOf<PackScanRecord>()
        val scanner = PackScanner(
            PackStore(tmp.root, reads),
            PackScanStore(tmp.root, reads),
            applyResults = { results, _, record -> applied += results to record; true },
            probe = { stream, _ -> probed += stream.url; true },
            playing = playing,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            nowMs = { testScheduler.currentTime }
        )
        backgroundScope.launch(unconfined) { scanner.ended.collect { ended += it } }
        scanner.start(setOf("ca"))
        runCurrent()
        scanner.cancel()
        reads.scheduler.advanceUntilIdle()
        advanceUntilIdle()
        assertEquals(PackScanner.State.Idle, scanner.state.value)
        assertTrue(probed.isEmpty())
        assertTrue(applied.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `an empty country set starts nothing`() = runTest {
        val scanner = scanner(listOf(channel("A", "ca", stream("https://a/1")))) { true }
        assertFalse(scanner.start(emptySet()))
    }
}
