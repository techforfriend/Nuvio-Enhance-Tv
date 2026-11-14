package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.model.EpgOverride
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import okio.Buffer
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private var ids = 0
    private val io = UnconfinedTestDispatcher()
    private lateinit var store: SourceStore
    private lateinit var channels: ChannelRepository
    private lateinit var guides: GuideRepository
    private lateinit var pack: PackStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = SourceStore(tmp.root, io)
        channels = ChannelRepository(tmp.root, { now }, io)
        guides = GuideRepository(tmp.root, io)
        pack = PackStore(tmp.root, io)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(maxTextBytes: Long = SourceFetcher.MAX_TEXT_BYTES) = LiveSourceManager(
        store,
        channels,
        guides,
        // Metadata is IptvMetadataRepositoryTest's subject: here it always fails fast (refused port).
        IptvMetadataRepository(tmp.root, SourceFetcher(OkHttpClient(), io), { now }, io, "http://127.0.0.1:1/"),
        SourceFetcher(OkHttpClient(), io, maxTextBytes),
        StremioTvAdapter(SourceFetcher(OkHttpClient(), io)),
        XmltvNowNextParser({ KXmlParser() }, maxProgrammesPerChannel = LiveSourceManager.DAY_WINDOW_MAX_PROGRAMMES),
        { File(PRESET_PATH).inputStream() },
        { now },
        { "s${++ids}" },
        io,
        // Not UTC (the usual CI zone): a window taken from the system zone instead would show.
        { ZoneOffset.ofHours(-6) },
        pack = pack,
        scans = PackScanStore(tmp.root, io)
    )

    private fun playlist(vararg ids: String) = buildString {
        append("#EXTM3U\n")
        for (id in ids) append("#EXTINF:-1 tvg-id=\"$id.in\",${id.uppercase()}\nhttps://example.org/$id.m3u8\n")
    }

    private fun url() = server.url("/list.m3u").toString()

    private suspend fun added(manager: LiveSourceManager, body: String): LiveSource {
        server.enqueue(MockResponse().setBody(body))
        val result = manager.addUrl(url())
        assertTrue("$result", result is LiveSourceManager.AddResult.Added)
        return (result as LiveSourceManager.AddResult.Added).source
    }

    @Test
    fun `adding a playlist stores the source and its channels`() = runTest {
        val source = added(manager(), playlist("a", "b"))
        assertEquals(SourceKind.M3U, source.kind)
        assertEquals(server.hostName, source.name)
        assertEquals(2, source.channelCount)
        assertEquals(listOf(source), store.sources.value)
        assertEquals(listOf("s1#id:a.in", "s1#id:b.in"), channels.channels.value.map { it.key })

        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals(listOf(source), reread.sources.value)
    }

    @Test
    fun `content that is not a playlist is refused and nothing is stored`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody("<html><body>Login</body></html>"))
        assertEquals(LiveSourceManager.AddResult.Refused("Not a playlist, guide or Stremio addon"), manager.addUrl(url()))
        server.enqueue(MockResponse().setBody("#EXTM3U\n"))
        assertEquals(LiveSourceManager.AddResult.Refused("No channels in this playlist"), manager.addUrl(url()))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(LiveSourceManager.AddResult.Refused("HTTP 403"), manager.addUrl(url()))
        assertEquals(LiveSourceManager.AddResult.Refused("Not a web address"), manager.addUrl("list.m3u"))
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())
    }

    @Test
    fun `the same URL is not added twice`() = runTest {
        val manager = manager()
        added(manager, playlist("a"))
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.addUrl(url()))
    }

    @Test
    fun `a check stores nothing, and adding it stores without fetching again`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody(playlist("a", "b", "c")))
        val ready = manager.check(url()) as LiveSourceManager.CheckResult.Ready
        assertEquals(SourceKind.M3U, ready.checked.source.kind)
        assertEquals(3, ready.checked.listed)
        assertTrue(store.sources.value.isEmpty())
        assertTrue(channels.channels.value.isEmpty())

        val added = manager.add(ready.checked) as LiveSourceManager.AddResult.Added
        assertEquals(1, server.requestCount)
        assertEquals(listOf(added.source), store.sources.value)
        assertEquals(3, channels.channels.value.size)
    }

    @Test
    fun `a checked URL added meanwhile is refused at add`() = runTest {
        val manager = manager()
        server.enqueue(MockResponse().setBody(playlist("a")))
        val first = (manager.check(url()) as LiveSourceManager.CheckResult.Ready).checked
        server.enqueue(MockResponse().setBody(playlist("a")))
        val second = (manager.check(url()) as LiveSourceManager.CheckResult.Ready).checked
        assertTrue(manager.add(first) is LiveSourceManager.AddResult.Added)
        assertEquals(LiveSourceManager.AddResult.Refused("Already added"), manager.add(second))
        assertEquals(1, store.sources.value.size)
        assertEquals(1, channels.channels.value.size)
    }

    @Test
    fun `rename trims, persists, and refuses blank names and unknown sources`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a"))
        assertTrue(manager.rename(source.id, "  My playlist "))
        assertEquals("My playlist", store.sources.value.single().name)
        assertEquals(false, manager.rename(source.id, "   "))
        assertEquals(false, manager.rename("missing", "X"))
        assertEquals("My playlist", store.sources.value.single().name)

        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals("My playlist", reread.sources.value.single().name)
    }

    @Test
    fun `a refresh merges the fresh listing and marks dropped channels lost`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a", "b"))
        now += 5_000
        server.enqueue(MockResponse().setBody(playlist("b", "c")))
        val refreshed = manager.refresh(source.id)!!
        assertEquals(now, refreshed.lastRefreshMs)
        assertNull(refreshed.lastError)
        assertEquals(2, refreshed.channelCount)
        val byKey = channels.channels.value.associateBy { it.key }
        assertEquals(setOf("s1#id:a.in", "s1#id:b.in", "s1#id:c.in"), byKey.keys)
        assertEquals(now, byKey.getValue("s1#id:a.in").lostAtMs)
        assertNull(byKey.getValue("s1#id:c.in").lostAtMs)
    }

    @Test
    fun `a failed refresh keeps the old channels and records the reason`() = runTest {
        val manager = manager(maxTextBytes = 400)
        val source = added(manager, playlist("a", "b"))
        val before = channels.channels.value

        server.enqueue(MockResponse().setBody(playlist(*Array(20) { "x$it" })))
        val tooLarge = manager.refresh(source.id)!!
        assertEquals("Larger than 0 MiB", tooLarge.lastError)
        assertEquals(source.lastRefreshMs, tooLarge.lastRefreshMs)
        assertEquals(before, channels.channels.value)

        server.enqueue(MockResponse().setBody("#EXTM3U\n"))
        assertEquals("No channels in this playlist", manager.refresh(source.id)!!.lastError)
        server.enqueue(MockResponse().setBody("{\"id\":\"x\"}"))
        assertEquals("No longer an M3U playlist", manager.refresh(source.id)!!.lastError)
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("HTTP 500", manager.refresh(source.id)!!.lastError)
        assertEquals(before, channels.channels.value)

        server.enqueue(MockResponse().setBody(playlist("a")))
        assertNull(manager.refresh(source.id)!!.lastError)
    }

    @Test
    fun `the broadcaster preset parses to seven numbered channels`() = runTest {
        val preset = manager().addBroadcasters()
        assertEquals(LiveSourceManager.PRESET_ID, preset.id)
        assertEquals(7, preset.channelCount)
        assertNull(preset.lastError)
        val list = channels.channels.value
        assertEquals((1..7).map { it.toString() }, list.map { it.channelNumber })
        assertEquals(
            listOf("DW English", "France 24 English", "NHK World-Japan", "Al Jazeera English", "DD News", "DD Sports", "DD Bangla"),
            list.map { it.name }
        )
        assertEquals(2, list.first().streams.size)
        assertEquals(listOf("de", "fr", "jp", "qa", "in", "in", "in"), list.map { it.country })
        assertTrue(list.all { ch -> ch.streams.all { it.url.startsWith("https://") } })
        assertEquals(0, server.requestCount)

        // Spec 49 D27 F5: the India guides are Sources' suggestion now, never added with the preset.
        assertEquals(listOf(LiveSourceManager.PRESET_ID), store.sources.value.map { it.id })

        // Adding again refreshes the one preset source instead of duplicating it.
        manager().addBroadcasters()
        assertEquals(1, store.sources.value.size)
        assertEquals(7, channels.channels.value.size)
    }

    @Test
    fun `removing a source drops its channels`() = runTest {
        val manager = manager()
        val source = added(manager, playlist("a"))
        manager.addBroadcasters()
        assertTrue(manager.remove(source.id))
        assertEquals(LiveSourceManager.PRESET_ID, store.sources.value.first().id)
        assertTrue(store.sources.value.none { it.id == source.id })
        assertTrue(channels.channels.value.all { it.sourceId == LiveSourceManager.PRESET_ID })
        assertNotNull(manager.refresh(LiveSourceManager.PRESET_ID))
        assertNull(manager.refresh(source.id))
    }

    @Test
    fun `a failed channel write refuses the add and leaves no record`() = runTest {
        // A regular file where the channel cache directory should be: every channel write fails.
        channels = ChannelRepository(tmp.newFile("blocked"), { now }, io)
        server.enqueue(MockResponse().setBody(playlist("a")))
        val result = manager().addUrl(url())
        assertTrue("$result", (result as LiveSourceManager.AddResult.Refused).reason.startsWith("Could not save"))
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun `a failed channel write on refresh is recorded, not thrown`() = runTest {
        store.upsert(LiveSource(LiveSourceManager.PRESET_ID, SourceKind.M3U, "B", LiveSourceManager.PRESET_URL, 0))
        channels = ChannelRepository(tmp.newFile("blocked"), { now }, io)
        val refreshed = manager().refresh(LiveSourceManager.PRESET_ID)!!
        assertTrue(refreshed.lastError!!.startsWith("Could not save"))
        assertNull(refreshed.lastRefreshMs)
    }

    private fun epgBody() = Buffer().readFrom(javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!)

    private val guideAt = Instant.parse("2026-10-19T02:15:00Z").toEpochMilli()

    @Test
    fun `a guide is added, matched, cached and read back on a cold start`() = runTest {
        now = guideAt
        val manager = manager()
        added(manager, playlist("StarJalsha").replace("STARJALSHA", "Star Jalsha HD"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val result = manager.addUrl(server.url("/xmltv/epg_IN.xml.gz").toString())
        val guide = (result as LiveSourceManager.AddResult.Added).source
        assertEquals(SourceKind.XMLTV, guide.kind)
        assertEquals("in", guide.country)
        assertEquals(1, guide.channelCount) // Star Jalsha matched by name inside India
        assertNull(guide.lastError)
        val snapshot = guides.guides.value.getValue(guide.id)
        assertEquals("Bigg Boss Bangla", snapshot.nowNext("542998", now).first?.title)
        // Spec 67 G1c: today and tomorrow at UTC-6 (now is 20:15 on the 18th there), today's earlier
        // programmes kept for the day schedule.
        assertEquals(Instant.parse("2026-10-18T06:00:00Z").toEpochMilli(), snapshot.windowStartMs)
        assertEquals(Instant.parse("2026-10-20T06:00:00Z").toEpochMilli(), snapshot.windowEndMs)
        val first = snapshot.programmes.getValue("542998").first()
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").toEpochMilli(), first.startMs)
        assertTrue(first.stopMs <= now)

        val cold = GuideRepository(tmp.root, io)
        cold.load(listOf(guide.id))
        val (cur, next) = cold.guides.value.getValue(guide.id).nowNext("542998", now)
        assertEquals("Bigg Boss Bangla", cur?.title)
        assertEquals("Ajmeri Gems House", next?.title)

        assertTrue(manager.remove(guide.id))
        assertTrue(guides.guides.value.isEmpty())
        assertTrue(File(tmp.root, "epg-${guide.id}.json").exists().not())
    }

    @Test
    fun `a cut-short guide never replaces a complete one`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        val before = guides.guides.value.getValue(guide.id)

        now += 60_000
        server.enqueue(MockResponse().setBody(epgHalf()))
        val refreshed = manager.refresh(guide.id)!!
        assertEquals("Guide file incomplete; kept what was read", refreshed.lastError)
        assertEquals(guide.lastRefreshMs, refreshed.lastRefreshMs)
        assertEquals(before, guides.guides.value.getValue(guide.id))

        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,A\nhttp://x/a\n"))
        assertEquals("No longer an XMLTV guide", manager.refresh(guide.id)!!.lastError)
    }

    private fun epgHalf(): String {
        val full = javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!.use {
            java.util.zip.GZIPInputStream(it).readBytes().decodeToString()
        }
        return full.substring(0, full.length / 2)
    }

    @Test
    fun `a partial guide is kept until a complete one arrives, and beats an expired complete one`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgHalf()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml").toString()) as LiveSourceManager.AddResult.Added).source
        assertEquals("Guide file incomplete; kept what was read", guide.lastError)
        assertTrue(!guides.guides.value.getValue(guide.id).complete)

        // A partial over a partial is taken (nothing better is cached).
        now += 2L * 60 * 60 * 1000
        server.enqueue(MockResponse().setBody(epgHalf()))
        assertEquals(now, manager.refresh(guide.id)!!.lastRefreshMs)

        server.enqueue(MockResponse().setBody(epgBody()))
        val complete = manager.refresh(guide.id)!!
        assertNull(complete.lastError)
        assertTrue(guides.guides.value.getValue(guide.id).complete)

        // After local midnight the complete snapshot no longer reaches the end of tomorrow: a partial replaces it.
        now = Instant.parse("2026-10-19T06:30:00Z").toEpochMilli()
        server.enqueue(MockResponse().setBody(epgHalf()))
        val late = manager.refresh(guide.id)!!
        assertEquals(now, late.lastRefreshMs)
        assertTrue(!guides.guides.value.getValue(guide.id).complete)
    }

    @Test
    fun `removing a guide clears overrides that point at it and leaves channels alone`() = runTest {
        now = guideAt
        val manager = manager()
        added(manager, playlist("a"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        channels.setOverride("s1#id:a.in", EpgOverride(EpgRef(guide.id, "542998")))
        channels.setOverride("s1#id:b.in", EpgOverride(null))
        val before = channels.channels.value
        assertTrue(manager.remove(guide.id))
        assertEquals(mapOf("s1#id:b.in" to EpgOverride(null)), channels.overrides.value)
        assertEquals(before, channels.channels.value)
    }

    @Test
    fun `an unreadable guide cache is skipped and makes the guide stale`() = runTest {
        now = guideAt
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager().addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        File(tmp.root, "epg-${guide.id}.json").writeText("{not json")

        guides = GuideRepository(tmp.root, io)
        server.enqueue(MockResponse().setBody(epgBody()))
        val requests = server.requestCount
        manager().refreshStale()
        assertEquals(requests + 1, server.requestCount)
        assertTrue(guides.guides.value.getValue(guide.id).complete)
    }

    @Test
    fun `stale rule`() {
        val hour = 60L * 60 * 1000
        val m3u = LiveSource("a", SourceKind.M3U, "A", "https://x/a.m3u", 0, lastRefreshMs = 0)
        val utc = ZoneOffset.UTC
        assertTrue(LiveSourceManager.isStale(m3u.copy(lastRefreshMs = null), null, 0, utc))
        assertTrue(!LiveSourceManager.isStale(m3u, null, 23 * hour, utc))
        assertTrue(LiveSourceManager.isStale(m3u, null, 24 * hour, utc))

        val guide = m3u.copy(kind = SourceKind.XMLTV)
        assertTrue(LiveSourceManager.isStale(guide, null, hour, utc))
        // A pre-G1c 6 h window does not reach the end of tomorrow: stale at once.
        assertTrue(LiveSourceManager.isStale(guide, EpgSnapshot(emptyMap(), emptyMap(), 0, 6 * hour, complete = true), hour, utc))

        // Spec 67 G1c day window (refreshed 20:00): fresh until local midnight, stale after it.
        val day = 24 * hour
        val (start, end) = LiveSourceManager.dayWindow(20 * hour, utc)
        assertEquals(0L to 2 * day, start to end)
        val window = EpgSnapshot(emptyMap(), emptyMap(), start, end, complete = true)
        val evening = guide.copy(lastRefreshMs = 20 * hour)
        assertTrue(!LiveSourceManager.isStale(evening, window, day - 1, utc))
        assertTrue(LiveSourceManager.isStale(evening, window, day, utc))
        // A zone change west moves the end of tomorrow later: 20:00 UTC is 14:00 the same day at UTC-6.
        assertTrue(LiveSourceManager.isStale(evening, window, 20 * hour, ZoneOffset.ofHours(-6)))
        // Midnight is the device zone's: at UTC+05:30, 21:00 UTC is already the 2nd.
        assertTrue(LiveSourceManager.isStale(evening, window, 21 * hour, ZoneOffset.ofHoursMinutes(5, 30)))

        // A cut-short day window: retried hourly.
        val partial = window.copy(complete = false)
        assertTrue(!LiveSourceManager.isStale(evening, partial, 20 * hour + hour / 2, utc))
        assertTrue(LiveSourceManager.isStale(evening, partial, 21 * hour, utc))

        val stremio = m3u.copy(kind = SourceKind.STREMIO)
        assertTrue(!LiveSourceManager.isStale(stremio, null, 23 * hour, utc))
        assertTrue(LiveSourceManager.isStale(stremio, null, 24 * hour, utc))

        val pack = m3u.copy(kind = SourceKind.PACK)
        assertTrue(!LiveSourceManager.isStale(pack, null, 23 * hour, utc))
        assertTrue(LiveSourceManager.isStale(pack, null, 24 * hour, utc))
    }

    @Test
    fun `dayWindow is today and tomorrow as local calendar days`() {
        val chicago = java.time.ZoneId.of("America/Chicago")
        fun at(iso: String) = Instant.parse(iso).toEpochMilli()
        // 23:00 CDT on the 19th is already the 20th in UTC: the window starts at local midnight.
        assertEquals(
            at("2026-10-19T05:00:00Z") to at("2026-10-21T05:00:00Z"),
            LiveSourceManager.dayWindow(at("2026-10-20T04:00:00Z"), chicago)
        )
        val hour = 60L * 60 * 1000
        // DST ends 1 November 2026 (a 25 h day), starts 8 March 2026 (23 h).
        LiveSourceManager.dayWindow(at("2026-10-31T17:00:00Z"), chicago).let { (s, e) -> assertEquals(49 * hour, e - s) }
        LiveSourceManager.dayWindow(at("2026-03-07T18:00:00Z"), chicago).let { (s, e) -> assertEquals(47 * hour, e - s) }
    }

    @Test
    fun `after midnight a cut-short guide replaces yesterday's and is retried hourly, not on every open`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        val requests = server.requestCount
        val yesterday = guides.guides.value.getValue(guide.id)

        now = Instant.parse("2026-10-19T06:15:00Z").toEpochMilli() // 00:15 local: a new day
        server.enqueue(MockResponse().setBody(epgHalf()))
        manager.refreshStale()
        assertEquals(requests + 1, server.requestCount)
        val partial = guides.guides.value.getValue(guide.id)
        assertTrue(!partial.complete)
        // The cut fell before 542998's programmes: the cached ones not yet ended are kept, not blanked.
        val carried = yesterday.programmes.getValue("542998").filter { it.stopMs > partial.windowStartMs }
        assertTrue(carried.isNotEmpty())
        assertEquals(carried, partial.programmes.getValue("542998"))
        assertEquals(Instant.parse("2026-10-21T06:00:00Z").toEpochMilli(), partial.windowEndMs)
        assertEquals("Guide file incomplete; kept what was read", store.get(guide.id)!!.lastError)

        now += 30L * 60 * 1000
        manager.refreshStale()
        assertEquals(requests + 1, server.requestCount)

        now += 30L * 60 * 1000
        server.enqueue(MockResponse().setBody(epgBody()))
        manager.refreshStale()
        assertEquals(requests + 2, server.requestCount)
        assertTrue(guides.guides.value.getValue(guide.id).complete)
    }

    @Test
    fun `cut-short guides day after day never carry an ended list forward`() = runTest {
        now = guideAt
        val manager = manager()
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source

        // Day 1 (00:15 local): the cut falls before 542998's programmes, which run to 11:00Z: filled.
        now = Instant.parse("2026-10-19T06:15:00Z").toEpochMilli()
        server.enqueue(MockResponse().setBody(epgHalf()))
        manager.refreshStale()
        assertTrue("542998" in guides.guides.value.getValue(guide.id).programmes)

        // Day 2: cut short again. 542998's carried list has ended: dropped, not kept as a dead "live" list.
        now = Instant.parse("2026-10-20T06:15:00Z").toEpochMilli()
        server.enqueue(MockResponse().setBody(epgHalf()))
        val requests = server.requestCount
        manager.refreshStale()
        assertEquals(requests + 1, server.requestCount)
        val day2 = guides.guides.value.getValue(guide.id)
        assertTrue(!day2.complete)
        assertTrue("542998" !in day2.programmes)
    }

    @Test
    fun `fillFrom keeps only cached programmes that have not ended`() {
        fun p(start: Long, stop: Long, title: String) = EpgProgramme(start, stop, title)
        fun ch(id: String) = id to EpgChannel(id, listOf(id), null)
        val windowStart = 1_000L
        val old = EpgSnapshot(
            channels = mapOf(ch("ended"), ch("airing"), ch("cut"), ch("late")),
            programmes = mapOf(
                "ended" to listOf(p(0, 500, "E1"), p(500, 1_000, "E2")),
                "airing" to listOf(p(500, 1_500, "A1"), p(1_500, 2_000, "A2")),
                // CX straddles the partial list's end (a revised schedule): never appended as an overlap.
                "cut" to listOf(p(1_000, 1_100, "C1 old"), p(1_050, 1_150, "CX"), p(1_150, 1_200, "C2"), p(1_200, 1_300, "C3")),
                // Its cached programmes ended, but the partial has today's: its entry must stay.
                "late" to listOf(p(0, 900, "L0"))
            ),
            windowStartMs = 0, windowEndMs = 2_000, complete = true
        )
        val partial = EpgSnapshot(
            channels = mapOf("cut" to EpgChannel("cut", listOf("Cut renamed"), "icon"), ch("new")),
            programmes = mapOf(
                "cut" to listOf(p(1_000, 1_100, "C1")),
                "new" to listOf(p(1_000, 1_200, "N1")),
                "late" to listOf(p(1_000, 1_100, "L1"))
            ),
            windowStartMs = windowStart, windowEndMs = 3_000, complete = false
        )
        val filled = LiveSourceManager.fillFrom(partial, old)
        assertEquals(setOf("airing", "cut", "new", "late"), filled.programmes.keys)
        assertEquals(listOf("A1", "A2"), filled.programmes.getValue("airing").map { it.title })
        // The partial's own list wins; it gets the cached rest of its day from its end, no overlap.
        assertEquals(listOf("C1", "C2", "C3"), filled.programmes.getValue("cut").map { it.title })
        assertEquals(setOf("airing", "cut", "new", "late"), filled.channels.keys)
        assertEquals(listOf("Cut renamed"), filled.channels.getValue("cut").displayNames)
        // An hourly retry fills from the last fill: stable, nothing duplicated or grown.
        assertEquals(filled, LiveSourceManager.fillFrom(partial, filled))
        assertEquals(windowStart to 3_000L, filled.windowStartMs to filled.windowEndMs)
        assertTrue(!filled.complete)
    }

    @Test
    fun `refreshStale fetches only what is stale`() = runTest {
        now = guideAt
        val manager = manager()
        val list = added(manager, playlist("a"))
        server.enqueue(MockResponse().setBody(epgBody()))
        val guide = (manager.addUrl(server.url("/epg_IN.xml.gz").toString()) as LiveSourceManager.AddResult.Added).source
        val requests = server.requestCount

        now += 60L * 60 * 1000
        manager.refreshStale()
        assertEquals(requests, server.requestCount)

        // 23:30 here (already past midnight in CDT, UTC-5): only the injected zone counts.
        now = Instant.parse("2026-10-19T05:30:00Z").toEpochMilli()
        manager.refreshStale()
        assertEquals(requests, server.requestCount)

        now = Instant.parse("2026-10-19T06:15:00Z").toEpochMilli() // past local midnight, playlist still fresh
        server.enqueue(MockResponse().setBody(epgBody()))
        manager.refreshStale()
        assertEquals(requests + 1, server.requestCount)
        assertEquals(now, store.get(guide.id)!!.lastRefreshMs)
        assertEquals(list.lastRefreshMs, store.get(list.id)!!.lastRefreshMs)
    }

    @Test
    fun `the India guide is added once as a record, or kept as a copy a source already holds`() = runTest {
        val manager = manager()
        val guides = manager.addPresetGuides(listOf(preset("in")))!!
        assertEquals(listOf(LiveSourceManager.INDIA_GUIDES.single().copy(addedAtMs = now)), guides)
        assertTrue(guides.all { it.kind == SourceKind.XMLTV && it.lastRefreshMs == null })
        assertEquals("in", guides.single().country)
        assertEquals(0, server.requestCount) // fetched by the caller's refresh, not here

        now += 1_000
        assertEquals(guides, manager.addPresetGuides(listOf(preset("in"))))
        assertEquals(1, store.sources.value.size)

        store.remove(guides.single().id)
        val mine = LiveSource("mine", SourceKind.XMLTV, "Mine", LiveSourceManager.INDIA_GUIDES.single().url, 0L)
        store.upsert(mine)
        assertEquals(listOf(mine), manager.addPresetGuides(listOf(preset("in"))))
        assertEquals(listOf(mine), store.sources.value)
    }

    @Test
    fun `load removes the retired epg pw India record with its cache and choices, once, and nothing else`() = runTest {
        val retired = LiveSource("preset-epg-pw-in", SourceKind.XMLTV, "epg.pw India", "https://epg.pw/xmltv/epg_IN.xml.gz", 0L)
        // A user's own record of the same URL is not the preset: it stays.
        val mine = LiveSource("mine", SourceKind.XMLTV, "Mine", retired.url, 0L)
        store.upsert(retired)
        store.upsert(mine)
        val snapshot = EpgSnapshot(emptyMap(), emptyMap(), 0, 1, complete = true)
        guides.put(retired.id, snapshot)
        guides.put(mine.id, snapshot)
        channels.load()
        channels.setOverride("a", EpgOverride(EpgRef(retired.id, "1")))
        channels.setOverride("b", EpgOverride(EpgRef(mine.id, "1")))

        manager().load()
        assertEquals(listOf(mine), store.sources.value)
        assertEquals(setOf(mine.id), guides.guides.value.keys)
        assertFalse(File(tmp.root, "epg-${retired.id}.json").exists())
        assertEquals(mapOf("b" to EpgOverride(EpgRef(mine.id, "1"))), channels.overrides.value)

        // Persisted, and a second load is a no-op.
        val reread = SourceStore(tmp.root, io)
        reread.load()
        assertEquals(listOf(mine), reread.sources.value)
        manager().load()
        assertEquals(listOf(mine), store.sources.value)
        assertEquals(LiveSourceManager.RETIRED_GUIDE_IDS, listOf(retired.id))
        assertTrue(LiveSourceManager.GUIDE_PRESETS.flatMap { it.guides }.none { it.id in LiveSourceManager.RETIRED_GUIDE_IDS })
    }

    @Test
    fun `a retired record whose removal fails stays, and the next load removes it`() = runTest {
        val retired = LiveSource("preset-epg-pw-in", SourceKind.XMLTV, "epg.pw India", "https://epg.pw/xmltv/epg_IN.xml.gz", 0L)
        store.upsert(retired)
        channels.load()
        channels.setOverride("a", EpgOverride(EpgRef(retired.id, "1")))
        // An unwritable folder fails the overrides write that remove() makes before the record goes.
        assertTrue(tmp.root.setWritable(false))
        try {
            manager().load()
        } finally {
            tmp.root.setWritable(true)
        }
        assertEquals(listOf(retired), store.sources.value)

        manager().load()
        assertEquals(emptyList<LiveSource>(), store.sources.value)
        assertEquals(emptyMap<String, EpgOverride>(), channels.overrides.value)
    }

    private fun preset(key: String) = LiveSourceManager.GUIDE_PRESETS.single { it.key == key }

    @Test
    fun `several presets are added in one write round, in preset then guide order`() = runTest {
        val manager = manager()
        val presets = listOf(preset("plex"), preset("au"), preset("samsung"))
        val guides = manager.addPresetGuides(presets)!!
        assertEquals(presets.flatMap { p -> p.guides.map { it.copy(addedAtMs = now) } }, guides)
        assertEquals(4, store.sources.value.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the Pluto guide is added once as a record, and not by Add broadcasters`() = runTest {
        val manager = manager()
        manager.addBroadcasters()
        assertTrue(store.sources.value.none { it.url == LiveSourceManager.PLUTO_GUIDE.url })

        val guide = manager.addPresetGuides(listOf(preset("pluto")))!!.single()
        assertEquals(LiveSourceManager.PLUTO_GUIDE.copy(addedAtMs = now), guide)
        assertEquals(SourceKind.XMLTV, guide.kind)
        assertNull(guide.country) // never matched by name
        assertNull(guide.lastRefreshMs) // fetched by the caller's refresh, not here
        assertEquals(0, server.requestCount)

        // A source already holding the URL (here the record itself) is returned, not duplicated.
        now += 1_000
        assertEquals(listOf(guide), manager.addPresetGuides(listOf(preset("pluto"))))
        assertEquals(1, store.sources.value.count { it.url == LiveSourceManager.PLUTO_GUIDE.url })
    }

    @Test
    fun `guide country comes from the file name`() {
        fun c(url: String) = LiveSourceManager.guessCountry(url.toHttpUrl())
        assertEquals("in", c("https://epg.pw/xmltv/epg_IN.xml.gz"))
        assertEquals("in", c("https://www.free-epg.de/api/epg/in.xml.gz"))
        assertEquals("ca", c("https://example.org/guide-ca.xml"))
        assertNull(c("https://example.org/guide.xml"))
        assertNull(c("https://example.org/epg"))
        // epgshare01: two letters and a feed digit (Spec 55 S1); service files read as none.
        assertEquals("uk", c("https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"))
        assertEquals("tr", c("https://epgshare01.online/epgshare01/epg_ripper_TR3.xml.gz"))
        assertNull(c("https://epgshare01.online/epgshare01/epg_ripper_PLEX1.xml.gz"))
        assertNull(c("https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS2.xml.gz"))
    }

    private fun epgshare(stem: String) = "https://epgshare01.online/epgshare01/epg_ripper_$stem.xml.gz"

    private fun chIn(country: String?, n: Int) = (1..n).map {
        com.nuvio.tv.livetv.model.Channel(key = "$country$it", sourceId = "p", name = "$country$it", country = country, streams = emptyList())
    }

    @Test
    fun `three or fewer header guides are all candidates, catch-alls and non-web addresses dropped`() {
        val urls = listOf(epgshare("ALL_SOURCES1"), "https://a.example/epg.xml", epgshare("DUMMY_CHANNELS"),
            "ftp://b.example/x.xml", epgshare("UK1"), "https://a.example/epg.xml", "not a url")
        assertEquals(
            listOf(listOf("https://a.example/epg.xml"), listOf(epgshare("UK1"))) to false,
            LiveSourceManager.headerCandidates(urls, emptyList())
        )
        assertNull(LiveSourceManager.headerCandidates(listOf(epgshare("ALL_SOURCES1")), emptyList()))
        assertNull(LiveSourceManager.headerCandidates(emptyList(), emptyList()))
    }

    @Test
    fun `more header guides give one group per channel country, largest first, files in header order`() {
        val urls = listOf(epgshare("AL1"), epgshare("IN1"), epgshare("PLEX1"), epgshare("UK1"), epgshare("IN4"),
            epgshare("ALL_SOURCES1"), epgshare("DE1"))
        val channels = chIn("uk", 2) + chIn("in", 3) + chIn("it", 9) + chIn(null, 5)
        assertEquals(
            listOf(listOf(epgshare("IN1"), epgshare("IN4")), listOf(epgshare("UK1"))) to true,
            LiveSourceManager.headerCandidates(urls, channels)
        )
        // No channel from any header country: nothing to offer.
        assertNull(LiveSourceManager.headerCandidates(urls, chIn("it", 2)))
    }

    @Test
    fun `a playlist's header guides are probed and recorded, gone or oversized files skipped`() = runTest {
        val big = LiveSourceManager.MAX_GUIDE_DOWNLOAD_BYTES + 1
        fun guide(stem: String) = server.url("/epg_ripper_$stem.xml.gz").toString()
        val header = listOf("IN1", "IN2", "UK1", "UK2", "DE1", "FR1").joinToString(",") { guide(it) }
        val body = "#EXTM3U x-tvg-url=\"$header\"\n" + listOf("a.in", "b.in", "c.uk", "d.de", "e.fr").joinToString("") {
            "#EXTINF:-1 tvg-id=\"$it\",$it\nhttps://example.org/$it.m3u8\n"
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/list.m3u" -> MockResponse().setBody(body)
                    request.method != "HEAD" -> MockResponse().setResponseCode(500)
                    "IN1" in path -> MockResponse().setResponseCode(404)
                    "UK1" in path -> MockResponse().setHeader("Content-Length", big)
                    "DE1" in path -> MockResponse().setResponseCode(410)
                    else -> MockResponse().setHeader("Content-Length", 1000)
                }
            }
        }
        val manager = manager()
        val added = (manager.addUrl(url()) as LiveSourceManager.AddResult.Added).source
        // in (2 channels) first; its 404 tries IN2; uk's oversized UK1 tries UK2; de has no other file.
        assertEquals(HeaderGuides(listOf(guide("IN2"), guide("UK2"), guide("FR1")), byCountry = true), added.headerGuides)
        assertEquals(added, store.get(added.id))
        assertTrue(server.requestCount >= 7)
    }

    @Test
    fun `a refresh re-probes the header guides`() = runTest {
        val manager = manager()
        val first = server.url("/epg_a.xml").toString()
        val second = server.url("/epg_b.xml").toString()
        fun list(url: String) = "#EXTM3U x-tvg-url=\"$url\"\n#EXTINF:-1 tvg-id=\"a.in\",A\nhttps://example.org/a.m3u8\n"
        server.enqueue(MockResponse().setBody(list(first)))
        server.enqueue(MockResponse())
        val added = (manager.addUrl(url()) as LiveSourceManager.AddResult.Added).source
        assertEquals(listOf(first), added.headerGuides?.urls)

        server.enqueue(MockResponse().setBody(list(second)))
        server.enqueue(MockResponse().setResponseCode(404))
        now += 1_000
        // The new header's only guide is gone: nothing to offer any more.
        assertNull(manager.refresh(added.id)!!.headerGuides)
        assertNull(store.get(added.id)!!.headerGuides)
    }

    @Test
    fun `a playlist's header attributes are kept on its record, updated by a refresh`() = runTest {
        val manager = manager()
        val guide = "http://127.0.0.1:1/epg.xml"
        val added = added(manager, "#EXTM3U url-tvg=\"$guide\" catchup=\"shift\" tvg-shift=\"1\"\n" + playlist("a").removePrefix("#EXTM3U\n"))
        assertEquals(mapOf("catchup" to "shift", "tvg-shift" to "1"), added.playlistAttrs)
        assertEquals(added, store.get(added.id))

        server.enqueue(MockResponse().setBody(playlist("a")))
        now += 1_000
        assertNull(manager.refresh(added.id)!!.playlistAttrs)
        assertNull(store.get(added.id)!!.playlistAttrs)
    }

    @Test
    fun `a probe that fails on the network keeps the guide`() = runTest {
        val dead = "http://127.0.0.1:1/epg_ripper_IN1.xml.gz"
        server.enqueue(MockResponse().setBody("#EXTM3U url-tvg=\"$dead\"\n#EXTINF:-1 tvg-id=\"a.in\",A\nhttps://example.org/a.m3u8\n"))
        val added = (manager().addUrl(url()) as LiveSourceManager.AddResult.Added).source
        assertEquals(HeaderGuides(listOf(dead), byCountry = false), added.headerGuides)
    }

    @Test
    fun `header guides are added once as named records with the file's country`() = runTest {
        val manager = manager()
        val uk = epgshare("UK1")
        val mine = LiveSource("mine", SourceKind.XMLTV, "Mine", "https://a.example/epg.xml", 0L)
        store.upsert(mine)
        val guides = manager.addHeaderGuides(listOf(uk to "epgshare01 United Kingdom", mine.url to "a.example"))!!
        assertEquals(
            listOf(LiveSource("s1", SourceKind.XMLTV, "epgshare01 United Kingdom", uk, now, country = "uk"), mine),
            guides
        )
        assertEquals(0, server.requestCount) // fetched by the caller's refresh, not here
        assertEquals(guides.take(1), manager.addHeaderGuides(listOf(uk to "again")))
        assertEquals(2, store.sources.value.size)
    }

    private companion object {
        /** Unit tests run with the module directory as the working directory. */
        const val PRESET_PATH = "src/main/assets/livetv/broadcasters.m3u"
    }
}
