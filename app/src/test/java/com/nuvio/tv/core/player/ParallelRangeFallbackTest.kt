package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.Allocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/** Spec 91 K1 (single-connection fallback) and K3 (re-resolve an expired CDN URL). */
class ParallelRangeFallbackTest {

    private lateinit var allocateBefore: (Int) -> Allocation?

    @Before
    fun setUp() {
        ParallelRangeDataSource.releaseRetainedSession()
        allocateBefore = ParallelRangeDataSource.allocateChunk
        // Heap chunk buffers: no native library in unit tests.
        ParallelRangeDataSource.allocateChunk = { null }
    }

    @After
    fun tearDown() {
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.allocateChunk = allocateBefore
    }

    @Test
    fun `failure classes one connection survives`() {
        val spec = DataSpec(mockk<Uri>(relaxed = true))
        fun http(code: Int) = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

        assertEquals(
            "rate-limit",
            ParallelRangeDataSource.singleConnectionFailureClass(
                java.util.concurrent.ExecutionException(IOException("still rate-limited", http(429)))
            )
        )
        assertEquals("rate-limit", ParallelRangeDataSource.singleConnectionFailureClass(http(503)))
        for (code in listOf(403, 404, 410, 416, 500)) {
            assertNull("$code", ParallelRangeDataSource.singleConnectionFailureClass(IOException("x", http(code))))
        }
        assertNull(ParallelRangeDataSource.singleConnectionFailureClass(IOException("x", SocketException("reset"))))
        assertNull(ParallelRangeDataSource.singleConnectionFailureClass(java.util.concurrent.TimeoutException()))
    }

    @Test
    fun `a short chunk marks the stream and the next open takes one plain connection`() {
        val uri = mockk<Uri>(relaxed = true)
        val shortChunk = chunkSource { _, _ -> 10 }
        var shortReads = 0
        every { shortChunk.read(any(), any(), any()) } answers { if (shortReads++ % 2 == 0) 10 else C.RESULT_END_OF_INPUT }
        val plain = mockk<OkHttpDataSource>(relaxed = true)
        val plainSpec = slot<DataSpec>()
        every { plain.open(capture(plainSpec)) } returns TOTAL - 100L
        every { plain.responseCode } returns 206
        every { plain.responseHeaders } returns
            mapOf("Content-Range" to listOf("bytes 100-${TOTAL - 1}/$TOTAL"))
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returnsMany listOf(shortChunk, shortChunk, plain)
        val source = warmSource(factory, uri, resolved = null)

        assertThrows(IOException::class.java) { drain(source) }
        assertTrue(ParallelRangeDataSource.singleConnectionMarkedForTest(uri))

        source.close()
        val reopen = DataSpec.Builder().setUri(uri).setPosition(100L).build()
        assertEquals(TOTAL - 100L, source.open(reopen))
        assertSame(uri, plainSpec.captured.uri)
        assertEquals(100L, plainSpec.captured.position)
        verify(exactly = 3) { factory.createDataSource() }
    }

    @Test
    fun `a marked open serving another file fails fast`() {
        val uri = mockk<Uri>(relaxed = true)
        val shortChunk = chunkSource { _, _ -> C.RESULT_END_OF_INPUT }
        val plain = mockk<OkHttpDataSource>(relaxed = true)
        every { plain.open(any()) } returns 50L
        every { plain.responseCode } returns 206
        every { plain.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-49/50"))
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returnsMany listOf(shortChunk, shortChunk, plain)
        val source = warmSource(factory, uri, resolved = null)
        assertThrows(IOException::class.java) { drain(source) }
        source.close()

        assertThrows(InvalidRangeResponseException::class.java) { source.open(DataSpec(uri)) }
        verify(exactly = 1) { plain.close() }
    }

    @Test
    fun `a network failure does not mark the stream`() {
        val uri = mockk<Uri>(relaxed = true)
        val reset = mockk<OkHttpDataSource>(relaxed = true)
        every { reset.open(any()) } throws SocketException("connection reset")
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns reset
        val source = warmSource(factory, uri, resolved = null)

        assertThrows(IOException::class.java) { drain(source) }
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(uri))
    }

    // Spec 91 D1/D2 (Arshad): a network failure counts only when a connection open since before the failing
    // download keeps receiving data 200 ms to 5 s after the failure.
    @Test
    fun `network evidence window edges`() {
        val downloadStart = 1_000L
        val failedAt = 10_000_000_000L
        val older = downloadStart - 1L
        val from = ParallelRangeDataSource.NETWORK_EVIDENCE_FROM_NS
        val until = ParallelRangeDataSource.NETWORK_EVIDENCE_UNTIL_NS
        fun evidence(connectedAt: Long, sinceFailure: Long) =
            ParallelRangeDataSource.isNetworkEvidence(connectedAt, downloadStart, failedAt, failedAt + sinceFailure)

        assertFalse("just before 200 ms", evidence(older, from - 1L))
        assertTrue("at 200 ms", evidence(older, from))
        assertTrue("at 5 s", evidence(older, until))
        assertFalse("just after 5 s", evidence(older, until + 1L))
        assertFalse("before the failure", evidence(older, -1L))
        assertFalse("opened as the download began", evidence(downloadStart, from))
        assertFalse("opened during the failing download", evidence(failedAt - 1L, from))
        assertFalse("opened after the failure", evidence(failedAt + 1L, from))
        // nanoTime may wrap between the connection opening and the download starting: comparisons are by
        // difference (a plain connectedAt < downloadStart is false here).
        assertTrue(
            ParallelRangeDataSource.isNetworkEvidence(Long.MAX_VALUE - 10L, Long.MIN_VALUE + 5L, Long.MIN_VALUE + 10L, Long.MIN_VALUE + 10L + from)
        )
    }

    @Test
    fun `new bytes on a connection older than the failing download mark the stream`() {
        val run = TwoChunkRun(Chunk1.DELIVERS)
        run.start()

        assertTrue("marked", run.awaitMarked())
        run.finish()
    }

    @Test
    fun `every connection failing at once does not mark the stream`() {
        val run = TwoChunkRun(Chunk1.FAILS)
        run.start()
        run.finish()

        assertTrue(ParallelRangeDataSource.networkFailureStampedForTest())
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(run.uri))
    }

    @Test
    fun `a connection opened after the failure delivering does not mark the stream`() {
        val run = TwoChunkRun(Chunk1.OPENS_AFTER)
        run.start()
        run.finish()

        assertTrue("chunk 1 delivered", run.chunk1Delivered)
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(run.uri))
    }

    // Round 2 (84f83d32): at outage recovery a sibling reconnects while a straggling download is still in its
    // connect attempt, which then times out; the sibling's bytes in the window must not mark.
    @Test
    fun `a late connect timeout with a sibling that reconnected during it does not mark the stream`() {
        val run = TwoChunkRun(Chunk1.OPENS_DURING)
        run.start()
        run.finish()

        assertTrue(ParallelRangeDataSource.networkFailureStampedForTest())
        assertTrue("chunk 1 delivered", run.chunk1Delivered)
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(run.uri))
    }

    @Test
    fun `a connect-phase failure is not stamped, a reset is`() {
        for ((error, stamped) in listOf(
            java.net.ConnectException("ENETUNREACH") to false,
            java.net.UnknownHostException("cdn") to false,
            java.net.NoRouteToHostException("no route") to false,
            SocketException("connection reset") to true
        )) {
            ParallelRangeDataSource.releaseRetainedSession()
            val uri = mockk<Uri>(relaxed = true)
            val failing = mockk<OkHttpDataSource>(relaxed = true)
            every { failing.open(any()) } throws error
            val factory = mockk<OkHttpDataSource.Factory>()
            every { factory.createDataSource() } returns failing
            val source = warmSource(factory, uri, resolved = null)

            assertThrows(IOException::class.java) { drain(source) }
            assertEquals("$error", stamped, ParallelRangeDataSource.networkFailureStampedForTest())
        }
    }

    /**
     * DELIVERS / FAILS: chunk 1's connection is open (reading) before chunk 0's download starts.
     * OPENS_AFTER: chunk 1 connects after the failure is stamped. OPENS_DURING: chunk 1 connects while
     * chunk 0's download is in its first attempt, which then times out.
     */
    private enum class Chunk1 { DELIVERS, FAILS, OPENS_AFTER, OPENS_DURING }

    /**
     * Chunk 0 and chunk 1 of one session download at once (two readers on the warm session). Chunk 0's
     * download fails with a network error; chunk 1 gets new bytes 300 ms after the failure is stamped
     * (inside the evidence window), or resets too (FAILS).
     */
    private inner class TwoChunkRun(private val chunk1: Chunk1) {
        val uri = mockk<Uri>(relaxed = true)
        @Volatile var chunk1Delivered = false
        // A gate that timed out inside a download worker (an assert there would kill the worker thread).
        @Volatile var gateTimedOut = false
        // Counted down in chunk 1's read(): production has taken its connectedAtNs by then.
        private val chunk1Reading = java.util.concurrent.CountDownLatch(1)
        // Counted down in chunk 1's second read(), which production makes only after noteProgress has
        // weighed the first one's bytes, so a no-mark assertion after it is not racing the worker.
        private val chunk1ProgressNoted = java.util.concurrent.CountDownLatch(1)
        private val chunk1Reads = java.util.concurrent.atomic.AtomicInteger()
        private val chunk0Started = java.util.concurrent.CountDownLatch(1)
        private val readers = mutableListOf<Thread>()
        private val factory = mockk<OkHttpDataSource.Factory>()

        init {
            // Each source acts by the range it is opened for, so any download may take any of them.
            every { factory.createDataSource() } returnsMany List(8) { upstream() }
        }

        private fun upstream(): OkHttpDataSource {
            val ds = mockk<OkHttpDataSource>(relaxed = true)
            every { ds.open(any()) } answers {
                if (firstArg<DataSpec>().position == 0L) {
                    if (chunk1 == Chunk1.OPENS_DURING) {
                        chunk0Started.countDown()
                        gate(chunk1Reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw java.net.SocketTimeoutException("connect timed out")
                    }
                    throw SocketException("connection reset")
                }
                when (chunk1) {
                    Chunk1.OPENS_AFTER -> gate(awaitStamp())
                    Chunk1.OPENS_DURING -> gate(chunk0Started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    else -> Unit
                }
                CHUNK
            }
            every { ds.read(any(), any(), any()) } answers {
                if (chunk1Reads.getAndIncrement() > 0) {
                    chunk1ProgressNoted.countDown()
                    return@answers thirdArg<Int>()
                }
                chunk1Reading.countDown()
                gate(awaitStamp())
                Thread.sleep(300)
                if (chunk1 == Chunk1.FAILS) throw SocketException("connection reset")
                chunk1Delivered = true
                // A short first read, so the worker reads again after noteProgress.
                1
            }
            return ds
        }

        private fun gate(passed: Boolean) {
            if (!passed) {
                gateTimedOut = true
                throw IOException("test gate timed out")
            }
        }

        private fun awaitStamp(): Boolean {
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                if (ParallelRangeDataSource.networkFailureStampedForTest()) return true
                Thread.sleep(5)
            }
            return false
        }

        fun start() {
            val first = warmSource(factory, uri, resolved = null)
            val second = ParallelRangeDataSource(
                upstreamFactory = factory,
                parallelConnections = 2,
                chunkSize = CHUNK,
                shouldAllowBackgroundPrefetch = { false },
                allowContinuationReopen = false
            )
            second.open(DataSpec.Builder().setUri(uri).setPosition(CHUNK).build())
            val firstReader = Thread { runCatching { first.read(ByteArray(16), 0, 16) } }
            val secondReader = Thread { runCatching { second.read(ByteArray(16), 0, 16) } }
            readers += listOf(firstReader, secondReader)
            when (chunk1) {
                Chunk1.DELIVERS, Chunk1.FAILS -> {
                    secondReader.start()
                    assertTrue(chunk1Reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    firstReader.start()
                }
                Chunk1.OPENS_AFTER -> {
                    secondReader.start()
                    firstReader.start()
                }
                Chunk1.OPENS_DURING -> {
                    firstReader.start()
                    secondReader.start()
                }
            }
        }

        fun awaitMarked(timeoutMs: Long = 5_000L): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (ParallelRangeDataSource.singleConnectionMarkedForTest(uri)) return true
                Thread.sleep(20)
            }
            return ParallelRangeDataSource.singleConnectionMarkedForTest(uri)
        }

        fun finish() {
            readers.forEach { it.join(10_000) }
            assertFalse("a reader is still running", readers.any { it.isAlive })
            assertFalse("a test gate timed out", gateTimedOut)
            if (chunk1Delivered) {
                assertTrue(
                    "chunk 1's worker weighed its bytes",
                    chunk1ProgressNoted.await(5, java.util.concurrent.TimeUnit.SECONDS)
                )
            }
        }
    }

    @Test
    fun `MP4 session mode is never marked`() {
        val uri = mockk<Uri>(relaxed = true)
        val shortChunk = chunkSource { _, _ -> C.RESULT_END_OF_INPUT }
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns shortChunk
        val source = warmSource(factory, uri, resolved = null, connections = 1)

        assertThrows(IOException::class.java) { drain(source) }
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(uri))
    }

    @Test
    fun `a dead link on the CDN URL re-resolves once and the chunk is fetched from the fresh URL`() {
        val uri = mockk<Uri>(relaxed = true)
        val stale = mockk<Uri>(relaxed = true)
        val fresh = mockk<Uri>(relaxed = true)
        val expired = mockk<OkHttpDataSource>(relaxed = true)
        every { expired.open(any()) } answers { throw deadLink(403, firstArg()) }
        val probe = mockk<OkHttpDataSource>(relaxed = true)
        val probeSpec = slot<DataSpec>()
        every { probe.open(capture(probeSpec)) } returns 1L
        every { probe.uri } returns fresh
        every { probe.responseCode } returns 206
        every { probe.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-0/$TOTAL"))
        val good = chunkSource { _, length -> length }
        val goodSpec = slot<DataSpec>()
        every { good.open(capture(goodSpec)) } returns CHUNK
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returnsMany listOf(expired, probe, good)
        val source = warmSource(factory, uri, resolved = stale)

        assertEquals(16, source.read(ByteArray(16), 0, 16))

        // One stale attempt (no same-URL retry), one probe on the request URI, one fresh download.
        verify(exactly = 1) { expired.open(any()) }
        assertSame(uri, probeSpec.captured.uri)
        assertEquals(1L, probeSpec.captured.length)
        assertSame(fresh, goodSpec.captured.uri)
        verify(exactly = 3) { factory.createDataSource() }
        // A clean delivery with no network failure stamped must not mark the stream.
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(uri))
    }

    @Test
    fun `a re-resolve answered with 200 surfaces the dead link`() {
        val uri = mockk<Uri>(relaxed = true)
        val stale = mockk<Uri>(relaxed = true)
        val expired = mockk<OkHttpDataSource>(relaxed = true)
        every { expired.open(any()) } answers { throw deadLink(410, firstArg()) }
        val probe = mockk<OkHttpDataSource>(relaxed = true)
        every { probe.open(any()) } returns TOTAL
        every { probe.uri } returns mockk<Uri>(relaxed = true)
        every { probe.responseCode } returns 200
        every { probe.responseHeaders } returns emptyMap()
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returnsMany listOf(expired, probe)
        val source = warmSource(factory, uri, resolved = stale)

        val error = assertThrows(IOException::class.java) { drain(source) }
        assertEquals(410, error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode)
        verify(exactly = 1) { probe.close() }
        verify(exactly = 2) { factory.createDataSource() }
        assertFalse(ParallelRangeDataSource.singleConnectionMarkedForTest(uri))
    }

    @Test
    fun `a dead link on the fresh URL too surfaces without a second re-resolve`() {
        val uri = mockk<Uri>(relaxed = true)
        val stale = mockk<Uri>(relaxed = true)
        val fresh = mockk<Uri>(relaxed = true)
        val expired = mockk<OkHttpDataSource>(relaxed = true)
        every { expired.open(any()) } answers { throw deadLink(403, firstArg()) }
        val probe = mockk<OkHttpDataSource>(relaxed = true)
        every { probe.open(any()) } returns 1L
        every { probe.uri } returns fresh
        every { probe.responseCode } returns 206
        every { probe.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-0/$TOTAL"))
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returnsMany listOf(expired, probe, expired)
        val source = warmSource(factory, uri, resolved = stale)

        val error = assertThrows(IOException::class.java) { drain(source) }
        assertEquals(403, error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode)
        verify(exactly = 1) { probe.open(any()) }
        verify(exactly = 1) { expired.open(match { it.uri === fresh }) }
        verify(exactly = 3) { factory.createDataSource() }
    }

    @Test
    fun `a dead link on the request URI itself is not re-resolved`() {
        val uri = mockk<Uri>(relaxed = true)
        val expired = mockk<OkHttpDataSource>(relaxed = true)
        every { expired.open(any()) } answers { throw deadLink(403, firstArg()) }
        val factory = mockk<OkHttpDataSource.Factory>()
        every { factory.createDataSource() } returns expired
        val source = warmSource(factory, uri, resolved = null)

        assertThrows(IOException::class.java) { drain(source) }
        // The request URI keeps its same-URL retry; no bytes=0-0 re-resolve probe.
        verify(exactly = 2) { expired.open(match { it.uri === uri && it.length == CHUNK }) }
        verify(exactly = 2) { factory.createDataSource() }
    }

    private fun deadLink(code: Int, spec: DataSpec) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

    /** A chunk download source whose reads answer [read] (offset, length). */
    private fun chunkSource(read: (Int, Int) -> Int): OkHttpDataSource {
        val ds = mockk<OkHttpDataSource>(relaxed = true)
        every { ds.open(any()) } returns CHUNK
        every { ds.read(any(), any(), any()) } answers { read(secondArg(), thirdArg()) }
        return ds
    }

    /** Opened on a warm bootstrap entry: no probe, session length known, [resolved] as the CDN URL. */
    private fun warmSource(
        factory: OkHttpDataSource.Factory,
        uri: Uri,
        resolved: Uri?,
        connections: Int = 2
    ): ParallelRangeDataSource {
        val source = ParallelRangeDataSource(
            upstreamFactory = factory,
            parallelConnections = connections,
            chunkSize = CHUNK,
            shouldAllowBackgroundPrefetch = { false },
            consumeBootstrapCache = {
                ParallelRangeDataSource.BootstrapCacheEntry(
                    requestUri = uri,
                    startPosition = 0L,
                    resolvedUri = resolved,
                    openLength = TOTAL,
                    totalFileLength = TOTAL,
                    bootstrapData = ByteArray(0),
                    bootstrapSize = 0,
                    createdAtUptimeMs = 0L
                )
            },
            allowContinuationReopen = false
        )
        source.open(DataSpec(uri))
        return source
    }

    /** Reads until the source throws (or ends, which fails the test). */
    private fun drain(source: ParallelRangeDataSource) {
        val buffer = ByteArray(64)
        repeat(1_000) {
            if (source.read(buffer, 0, buffer.size) == C.RESULT_END_OF_INPUT) return
        }
    }

    private companion object {
        const val CHUNK = 4_096L
        const val TOTAL = CHUNK * 4
    }
}
