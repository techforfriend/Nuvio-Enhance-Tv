package com.nuvio.tv.livetv.source

import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.data.GuideRepository
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser

/** Real addon responses captured 2026-10-17 (derisk run 05): RTVE (unofficial) and Israeli TV. */
@OptIn(ExperimentalCoroutinesApi::class)
class StremioTvAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val io = UnconfinedTestDispatcher()
    private val fetcher = SourceFetcher(OkHttpClient(), io)
    private val adapter = StremioTvAdapter(fetcher)
    private val routes = HashMap<String, String>()

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/livetv/stremio/$name.json")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        routes["/rtve/manifest.json"] = fixture("rtve-manifest")
        routes["/rtve/catalog/TV/tv.json"] = fixture("rtve-catalog_TV_tv")
        routes["/rtve/stream/tv/tve:La1.TV.json"] = fixture("rtve-stream-tv-La1")
        routes["/cfg%2Fx/iltv/manifest.json"] = fixture("iltv-manifest")
        routes["/cfg%2Fx/iltv/catalog/tv/iltv.json"] = fixture("iltv-catalog_tv_iltv")
        routes["/cfg%2Fx/iltv/stream/tv/iltv_knessetchannelil.json"] = fixture("iltv-stream-tv-iltv_knessetchannelil")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    routes[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager() = LiveSourceManager(
        SourceStore(tmp.root, io),
        ChannelRepository(tmp.root, { 1L }, io),
        GuideRepository(tmp.root, io),
        IptvMetadataRepository(tmp.root, fetcher, { 1L }, io, "http://127.0.0.1:1/"),
        fetcher,
        adapter,
        XmltvNowNextParser({ KXmlParser() }),
        { error("no preset here") },
        { 1L },
        { "a1" },
        io,
        pack = PackStore(tmp.root, io),
        scans = PackScanStore(tmp.root, io)
    )

    @Test
    fun `RTVE upper-case TV catalog is used, radio and search are not`() {
        val manifest = adapter.readManifest(fixture("rtve-manifest").byteInputStream())
        assertEquals("RTVE", manifest.name)
        assertEquals(listOf("tv"), manifest.catalogs.map { it.id })
    }

    @Test
    fun `a manifest without a tv catalog is refused with that reason`() {
        val noTv = """{"id":"x","name":"Movies","version":"1","types":["movie"],"resources":["catalog"],
            "catalogs":[{"type":"movie","id":"top","name":"Top"}]}"""
        listOf(noTv, """{"id":"x","name":"Bad"}""", "{not json").forEachIndexed { i, body ->
            try {
                adapter.readManifest(body.byteInputStream())
                fail("expected a refusal")
            } catch (e: SourceFetchException) {
                val expected = if (i == 0) StremioTvAdapter.NO_TV_CATALOG else "Not a Stremio addon manifest"
                assertEquals(expected, e.message)
            }
        }
    }

    @Test
    fun `adding RTVE lists its channels, keeping both channels that share an id`() = runTest {
        val result = manager().addUrl(server.url("/rtve/manifest.json").toString())
        val source = (result as LiveSourceManager.AddResult.Added).source
        assertEquals(SourceKind.STREMIO, source.kind)
        assertEquals("RTVE", source.name)
        assertEquals(40, source.channelCount) // tve:RNE.TV is listed twice under two names (D9)
    }

    @Test
    fun `streams resolve at play time with proxy headers and an encoded id`() = runTest {
        val manager = manager()
        val url = server.url("/cfg%2Fx/iltv/manifest.json").toString()
        manager.addUrl(url)
        val channels = ChannelRepository(tmp.root, { 1L }, io).also { it.load() }.channels.value
        assertEquals(36, channels.size)
        val knesset = channels.single { it.providerId == "iltv_knessetchannelil" }
        assertTrue(knesset.streams.isEmpty())
        assertEquals(listOf("News"), channels.single { it.providerId == "iltv_channel9il" }.groups)

        val streams = manager.streamsFor(knesset)
        assertEquals(listOf("http://stream.mcquack.net/48/index.m3u8"), streams.map { it.url })
        assertTrue(streams.single().headers.getValue("User-Agent").startsWith("Mozilla/5.0"))
    }

    @Test
    fun `external links and placeholder-free direct URLs are told apart`() = runTest {
        val streams = adapter.streams(server.url("/rtve/manifest.json").toString(), "tve:La1.TV")
        // Two direct URLs (one with unfilled [DEVICE_ID] placeholders, kept: D10); the externalUrl is skipped.
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.headers.isEmpty() })
    }

    private fun metas(vararg ids: String) =
        ids.joinToString(",", "{\"metas\":[", "]}") { "{\"id\":\"$it\",\"type\":\"tv\",\"name\":\"$it\"}" }

    private fun manifest(extra: String) = adapter.readManifest(
        ("{\"id\":\"p\",\"name\":\"P\",\"version\":\"1\",\"types\":[\"tv\"],\"resources\":[\"catalog\"]," +
            "\"catalogs\":[{\"type\":\"tv\",\"id\":\"all\",\"name\":\"All\",\"extra\":[$extra]}]}").byteInputStream()
    )

    @Test
    fun `paging advances by skip, sends required extras and ends on an empty page, a repeat or an error`() = runTest {
        val m = manifest("{\"name\":\"skip\"},{\"name\":\"genre\",\"isRequired\":true,\"options\":[\"Live News\",\"Sports\"]}")
        routes["/p/catalog/tv/all/genre=Live%20News.json"] = metas("a", "b")
        routes["/p/catalog/tv/all/genre=Live%20News&skip=2.json"] = metas("c")
        routes["/p/catalog/tv/all/genre=Live%20News&skip=3.json"] = metas()
        val url = server.url("/p/manifest.json").toString()
        assertEquals(listOf("a", "b", "c"), adapter.channels("s", url, m).map { it.providerId })

        // A page past the end that errors (404) keeps what was read; a repeated page ends paging.
        routes.remove("/p/catalog/tv/all/genre=Live%20News&skip=3.json")
        assertEquals(3, adapter.channels("s", url, m).size)
        routes["/p/catalog/tv/all/genre=Live%20News&skip=2.json"] = metas("a", "b")
        assertEquals(2, adapter.channels("s", url, m).size)

        // A failing first page fails the listing (the refresh keeps the old channels).
        routes.remove("/p/catalog/tv/all/genre=Live%20News.json")
        try {
            adapter.channels("s", url, m)
            fail("expected the first page to fail")
        } catch (e: SourceFetchException) {
            assertEquals("HTTP 404", e.message)
        }
    }

    @Test
    fun `stream ids are encoded as one path segment`() = runTest {
        routes["/p/stream/tv/tve:Teledeporte%20(ES)%2Fa%C3%B1o.json"] = fixture("iltv-stream-tv-iltv_knessetchannelil")
        val streams = adapter.streams(server.url("/p/manifest.json").toString(), "tve:Teledeporte (ES)/año")
        assertEquals(1, streams.size)
    }

    @Test
    fun `addon roots keep encoded config segments and drop the manifest name`() {
        assertEquals(
            "https://a.example/cfg%2Fx/iltv?x=1",
            StremioTvAdapter.baseOf("https://a.example/cfg%2Fx/iltv/manifest.json?x=1").toString()
        )
        assertEquals("https://a.example/m.json", StremioTvAdapter.normalizeUrl(" stremio://a.example/m.json "))
    }
}
