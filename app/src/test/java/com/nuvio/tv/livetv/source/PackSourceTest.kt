package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.zip.GZIPOutputStream

/** The channel pack through [LiveSourceManager] (Spec 73 S2b): add, refresh, remove, summary. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val io = UnconfinedTestDispatcher()
    private lateinit var store: SourceStore
    private lateinit var channels: ChannelRepository
    private lateinit var pack: PackStore
    private lateinit var scans: PackScanStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = SourceStore(tmp.root, io)
        channels = ChannelRepository(tmp.root, { now }, io)
        pack = PackStore(tmp.root, io)
        scans = PackScanStore(tmp.root, io)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager() = LiveSourceManager(
        store,
        channels,
        GuideRepository(tmp.root, io),
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { now }, io, "http://127.0.0.1:1/"),
        SourceFetcher(OkHttpClient(), io),
        StremioTvAdapter(SourceFetcher(OkHttpClient(), io)),
        XmltvNowNextParser({ KXmlParser() }),
        { error("no preset here") },
        { now },
        { "id" },
        io,
        pack = pack,
        scans = scans,
        packUrls = LiveSourceManager.PackUrls(
            state = server.url("/monitor/state.tsv").toString(),
            streams = server.url("/pool/streams.tsv").toString(),
            summary = server.url("/monitor/pack.json").toString()
        )
    )

    private val stateTsv = listOf(
        "url\tstate\tsince\tclass\tslots",
        "https://a/1.m3u8\talive\t2026-11-08\tok\t10000000",
        "https://a/2.m3u8\tblocked\t2026-11-08\tblocked\t00000000",
        "https://a/3.m3u8\talive\t2026-11-08\tok\t10000000"
    ).joinToString("\n")

    private fun streamsTsv(vararg rows: Pair<String, String>) = (
        listOf("name\turl\tcountry\tcategory\theaders\tchannel") +
            rows.map { (name, url) -> "$name\t$url\tCA\tnews\t-\t" }
        ).joinToString("\n")

    private fun gzip(text: String) = Buffer().apply {
        GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) }
    }

    private fun enqueuePack(streams: String = streamsTsv("One" to "https://a/1.m3u8", "Two" to "https://a/2.m3u8")) {
        server.enqueue(MockResponse().setBody(stateTsv))
        server.enqueue(MockResponse().setBody(gzip(streams)))
    }

    @Test
    fun `adding the pack stores the record, the whole pack and the alive listing`() = runTest {
        enqueuePack()
        val result = manager().addPack()
        val source = (result as LiveSourceManager.AddResult.Added).source
        assertEquals(SourceKind.PACK, source.kind)
        assertEquals(ChannelPack.SOURCE_ID, source.id)
        assertEquals(2, source.channelCount)
        assertEquals(listOf(source), store.sources.value)
        assertEquals(listOf("pack#nc:one|ca"), channels.channels.value.map { it.key })
        assertEquals(listOf("One", "Two"), pack.read().map { it.name })
        assertEquals(listOf("/monitor/state.tsv", "/pool/streams.tsv"), List(2) { server.takeRequest().path })
    }

    @Test
    fun `packSaves counts a pack download only once its file is there, never a failed one`() = runTest {
        val manager = manager()
        val seen = mutableListOf<Pair<Int, Int>>() // count -> pack channels on disk at that moment
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.packSaves.collect { seen += it to pack.read().size }
        }
        server.enqueue(MockResponse().setResponseCode(404))
        manager.addPack()
        enqueuePack()
        manager.addPack()
        enqueuePack()
        manager.refresh(ChannelPack.SOURCE_ID)
        assertEquals(listOf(0 to 0, 1 to 2, 2 to 2), seen)
    }

    @Test
    fun `a failed download adds nothing, a second add is refused`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(LiveSourceManager.AddResult.Refused("HTTP 404"), manager.addPack())
        server.enqueue(MockResponse().setBody(stateTsv))
        server.enqueue(MockResponse().setBody("name\turl\n"))
        assertEquals(LiveSourceManager.AddResult.Refused(ChannelPack.FORMAT_CHANGED), manager.addPack())
        enqueuePack(streamsTsv("Gone" to "https://a/9.m3u8"))
        assertEquals(LiveSourceManager.AddResult.Refused("No channels in the channel pack"), manager.addPack())
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
        assertTrue(pack.read().isEmpty())

        enqueuePack()
        assertTrue(manager.addPack() is LiveSourceManager.AddResult.Added)
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.addPack())
    }

    @Test
    fun `a refresh replaces the pack, a failed or cut-short one keeps it`() = runTest {
        val manager = manager()
        enqueuePack()
        manager.addPack()

        now += LiveSourceManager.MAX_AGE_MS
        server.enqueue(MockResponse().setBody(stateTsv))
        // A gzip body cut mid-stream: the inflater hits the end early.
        val cut = gzip(streamsTsv("Three" to "https://a/3.m3u8")).readByteArray().let { it.copyOf(it.size - 8) }
        server.enqueue(MockResponse().setBody(Buffer().write(cut)))
        val failed = manager.refresh(ChannelPack.SOURCE_ID)
        assertNotNull(failed?.lastError)
        assertEquals(listOf("One", "Two"), pack.read().map { it.name })
        assertEquals(listOf("pack#nc:one|ca"), channels.channels.value.map { it.key })

        enqueuePack(streamsTsv("Three" to "https://a/3.m3u8"))
        manager.refreshStale()
        assertEquals(listOf("Three"), pack.read().map { it.name })
        val keys = channels.channels.value.associate { it.key to it.lost }
        // A channel the refresh dropped reads Source lost, as a playlist's.
        assertEquals(mapOf("pack#nc:one|ca" to true, "pack#nc:three|ca" to false), keys)
        assertNull(store.get(ChannelPack.SOURCE_ID)?.lastError)
        assertEquals(now, store.get(ChannelPack.SOURCE_ID)?.lastRefreshMs)
    }

    @Test
    fun `a file detected as a guide or playlist is refused before it is read`() = runTest {
        server.enqueue(MockResponse().setBody("<tv>\turl\tstate\tclass\nhttps://a/1.m3u8\talive\tok"))
        assertEquals(LiveSourceManager.AddResult.Refused(ChannelPack.FORMAT_CHANGED), manager().addPack())
        server.enqueue(MockResponse().setBody(stateTsv))
        server.enqueue(MockResponse().setBody("#EXTM3U\nname\turl\tcountry\tcategory\theaders\tchannel"))
        assertEquals(LiveSourceManager.AddResult.Refused(ChannelPack.FORMAT_CHANGED), manager().addPack())
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun `the pack record survives a restart`() = runTest {
        enqueuePack()
        manager().addPack()
        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals(SourceKind.PACK, reread.sources.value.single().kind)
    }

    @Test
    fun `a failed listing write drops the record and the pack file`() = runTest {
        // A non-empty directory where the channel cache goes: its write fails.
        File(tmp.root, "channels.json").apply { mkdirs(); File(this, "x").writeText("x") }
        enqueuePack()
        val manager = manager()
        val result = manager.addPack()
        // A save failure, not a download one: the pack file was written, then dropped.
        assertTrue("$result", (result as LiveSourceManager.AddResult.Refused).reason.startsWith("Could not save"))
        assertTrue(store.sources.value.isEmpty())
        assertFalse(File(tmp.root, "pack-channels.json").exists())
        assertEquals(0, manager.packSaves.value) // not a completed download (S3c)
    }

    @Test
    fun `a failed pack file delete keeps the record to retry from`() = runTest {
        val manager = manager()
        enqueuePack()
        manager.addPack()
        val file = File(tmp.root, "pack-channels.json")
        file.delete()
        file.mkdirs()
        File(file, "x").writeText("x")
        assertFalse(manager.remove(ChannelPack.SOURCE_ID))
        assertEquals(1, store.sources.value.size)
    }

    @Test
    fun `removing the pack drops its channels and its file`() = runTest {
        val manager = manager()
        enqueuePack()
        manager.addPack()
        assertTrue(manager.remove(ChannelPack.SOURCE_ID))
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
        assertTrue(pack.read().isEmpty())
    }

    private fun record() = PackScanRecord(listOf("ca"), startedMs = 1, channels = 2)

    @Test
    fun `scan results list a proved channel, hide a failed one, survive a refresh by URL and go with the pack`() = runTest {
        val manager = manager()
        assertFalse(manager.applyScan(emptyMap(), emptySet(), record()))
        enqueuePack()
        manager.addPack()
        val one = ChannelPack.scanKey("https://a/1.m3u8")
        val two = ChannelPack.scanKey("https://a/2.m3u8")
        assertTrue(manager.applyScan(mapOf(one to PackScanResult(false, now), two to PackScanResult(true, now)), emptySet(), record()))
        assertEquals(listOf("pack#nc:two|ca"), channels.channels.value.filterNot { it.lost }.map { it.key })
        assertEquals(record(), scans.read().last)

        now += LiveSourceManager.MAX_AGE_MS
        enqueuePack(streamsTsv("Two" to "https://a/2.m3u8", "Three" to "https://a/3.m3u8"))
        manager.refreshStale()
        // Two stays listed by its result (blocked by the monitor); One's result went with its URL.
        assertEquals(setOf(two), scans.read().results.keys)
        assertEquals(
            listOf("pack#nc:three|ca", "pack#nc:two|ca"),
            channels.channels.value.filterNot { it.lost }.map { it.key }.sorted()
        )

        assertTrue(manager.remove(ChannelPack.SOURCE_ID))
        assertFalse(File(tmp.root, "pack-scan.json").exists())
        assertFalse(manager.applyScan(mapOf(two to PackScanResult(true, now)), emptySet(), record()))
        assertTrue(channels.channels.value.isEmpty())
    }

    @Test
    fun `a later scan's results go over the stored ones, cleared ones back to the monitor's state`() = runTest {
        val manager = manager()
        enqueuePack()
        manager.addPack()
        val one = ChannelPack.scanKey("https://a/1.m3u8")
        val two = ChannelPack.scanKey("https://a/2.m3u8")
        manager.applyScan(mapOf(one to PackScanResult(false, 1), two to PackScanResult(true, 1)), emptySet(), record())
        // One is also cleared (a later stream of another channel): this scan's own result wins.
        manager.applyScan(mapOf(one to PackScanResult(true, 2)), setOf(one, two), record())
        assertEquals(mapOf(one to PackScanResult(true, 2)), scans.read().results)
        // Two (blocked by the monitor) is hidden again; One plays.
        assertEquals(listOf("pack#nc:one|ca"), channels.channels.value.filterNot { it.lost }.map { it.key })
    }

    @Test
    fun `summary reads pack json, missing or oversized gives null`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody("""{"version":1,"channels":21037,"countries":177,"bytes":1600000}"""))
        assertEquals(PackSummary(21037, 177, 1_600_000), manager.packSummary())
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(manager.packSummary())
        // Valid JSON at the 4 KiB cap reads; one byte past it, only the cap refuses it.
        fun padded(size: Int): String {
            val head = "{\"version\":1,\"channels\":1,\"countries\":1,\"bytes\":1"
            return head + " ".repeat(size - head.length - 1) + "}"
        }
        server.enqueue(MockResponse().setBody(padded(4096)))
        assertEquals(PackSummary(1, 1, 1), manager.packSummary())
        server.enqueue(MockResponse().setBody(padded(4097)))
        assertNull(manager.packSummary())
        assertTrue(store.sources.value.isEmpty())
    }
}
