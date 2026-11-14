package com.nuvio.tv.core.di

import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.AddonHostGuardInterceptor
import com.nuvio.tv.data.remote.api.AddonApi
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * W2.2 untrusted-response guard: the @Api client's responses from untrusted
 * addon hosts must be JSON-family on success and capped at
 * [UNTRUSTED_JSON_BODY_CAP_BYTES] counting DECOMPRESSED bytes — including
 * error bodies, which Retrofit 2.x would otherwise heap-buffer uncapped
 * before app code runs (Utils.buffer). Successful addon meta gets
 * [UntrustedBodyCap.META] instead (Spec 82), stream lists [UntrustedBodyCap.STREAMS]
 * (Spec 84 A5). A successful text/plain body is read as JSON (static addon hosts,
 * Spec 84 A1).
 */
class UntrustedResponseGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(untrustedResponseGuardInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun execute() =
        client.newCall(Request.Builder().url(server.url("/catalog")).build()).execute()

    /** JSON whitespace: passes the depth counter (which rejects NUL bytes), so only the byte cap fires. */
    private fun jsonWhitespace(size: Long) = ByteArray(size.toInt()) { ' '.code.toByte() }

    @Test
    fun `oversized json body is rejected mid-read`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000)))
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `error bodies are capped too, not just successes`() {
        // The Retrofit error-body vector: a hostile host answering 500 + huge
        // would be buffered into the heap before any catch ran.
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(ByteArray((UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000).toInt())))
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `non-json success responses are rejected`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>error page</body></html>")
        )
        val failure = runCatching { execute() }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("non-JSON"))
    }

    @Test
    fun `gzip responses are counted post-decompression`() {
        // ~2.5MB of spaces compresses to a few KB — the cap must fire on the
        // DECOMPRESSED size (application layer sees the unwrapped body).
        val gzipped = Buffer()
        GZIPOutputStream(gzipped.outputStream()).use {
            it.write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 500_000))
        }
        assertTrue("fixture must compress under the cap to prove the point", gzipped.size < UNTRUSTED_JSON_BODY_CAP_BYTES)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzipped)
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("cap"))
    }

    @Test
    fun `addon json served as text-plain is read like json`() = runBlocking {
        // raw.githubusercontent.com serves the USA TV Next addon as text/plain; charset=utf-8.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(
                    "{\"id\":\"community.usa-tv-next\",\"version\":\"2.1.0\",\"name\":\"USA TV Next\"," +
                        "\"catalogs\":[{\"name\":\"USA TV Next\",\"type\":\"tv\",\"id\":\"all\"}]," +
                        "\"resources\":[\"catalog\",\"stream\",\"meta\"],\"types\":[\"tv\"]}"
                )
        )
        val response = addonApi().getManifest(server.url("/manifest.json").toString())
        assertEquals("community.usa-tv-next", response.body()?.id)
    }

    @Test
    fun `text-plain success bodies keep the depth and charset guards`() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("{\"metas\":$deep}")
        )
        val nested = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $nested", nested is IOException)
        assertTrue(nested!!.message!!.contains("nests deeper"))

        // The charset guard's own message: the non-JSON rejection would also name the charset.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=Shift_JIS").setBody("{}"))
        val declared = runCatching { execute() }.exceptionOrNull()
        assertTrue("expected IOException, got $declared", declared is IOException)
        assertTrue(declared!!.message!!.contains("Rejected JSON in charset"))
    }

    @Test
    fun `untyped success bodies are read as json`() {
        // Hosts that omit Content-Type: the body passes, and still meets the depth guard.
        server.enqueue(MockResponse().setBody("{\"metas\":[]}"))
        execute().use { response ->
            assertEquals(null, response.body.contentType())
            assertEquals("{\"metas\":[]}", response.body.string())
        }
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        server.enqueue(MockResponse().setBody("{\"metas\":$deep}"))
        val nested = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $nested", nested is IOException)
        assertTrue(nested!!.message!!.contains("nests deeper"))
    }

    @Test
    fun `an html page sent as text-plain fails at the decoder`() {
        // SafeApiCall classes a SerializationException as a malformed response.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("<html><body>Not found</body></html>")
        )
        val failure = runCatching {
            runBlocking { addonApi().getManifest(server.url("/manifest.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected SerializationException, got $failure", failure is SerializationException)
    }

    @Test
    fun `json under the cap and vendor json types pass through`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"metas\":[]}")
        )
        execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("{\"metas\":[]}", response.body.string())
        }
        // +json vendor suffix (Stremio-style hosts) is allowed.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/ld+json; charset=utf-8")
                .setBody("{}")
        )
        execute().use { response ->
            assertEquals("{}", response.body.string())
        }
    }

    @Test
    fun `json nested past the depth cap is rejected before decoding`() {
        // Deep arrays overflow kotlinx's JsonElement reader (a StackOverflowError no fetch
        // path catches); the guard turns them into an IOException mid-read.
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"streams\":[{\"streamData\":$deep}]}")
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("nests deeper"))
    }

    @Test
    fun `depth counts structure only, not brackets inside strings`() {
        val atCap = "[".repeat(UNTRUSTED_JSON_MAX_DEPTH) + "]".repeat(UNTRUSTED_JSON_MAX_DEPTH)
        val bracketsInString = "{\"name\":\"" + "[{".repeat(5_000) + "\\\"[\"}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(atCap))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(bracketsInString))
        execute().use { assertEquals(atCap, it.body.string()) }
        execute().use { assertEquals(bracketsInString, it.body.string()) }
    }

    @Test
    fun `an escaped backslash ends the string before structural brackets`() {
        val deep = "[".repeat(UNTRUSTED_JSON_MAX_DEPTH + 1) + "]".repeat(UNTRUSTED_JSON_MAX_DEPTH + 1)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"a\":\"\\\\\",\"b\":$deep}")
        )
        val failure = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("nests deeper"))
    }

    @Test
    fun `utf-16 bodies cannot desync the depth counter`() {
        // U+2200 inside a string is bytes 00 22 in UTF-16LE: a byte-wise counter would read a
        // quote and stop counting while ResponseBody.string() decodes the BOM correctly.
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        val utf16 = Buffer().write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            .writeString("{\"a\":\"\u2200\",\"b\":$deep}", Charsets.UTF_16LE)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(utf16))
        val bom = runCatching { execute().use { it.body.string() } }.exceptionOrNull()
        assertTrue("expected IOException, got $bom", bom is IOException)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=Shift_JIS")
                .setBody("{}")
        )
        val declared = runCatching { execute() }.exceptionOrNull()
        assertTrue("expected IOException, got $declared", declared is IOException)
        assertTrue(declared!!.message!!.contains("Rejected JSON in charset"))

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=ISO-8859-1")
                .setBody("{\"metas\":[]}")
        )
        execute().use { assertEquals("{\"metas\":[]}", it.body.string()) }
    }

    /** The @Api client's interceptor order, so the tag must survive the requests they rebuild. */
    private fun addonApi(): AddonApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(
            OkHttpClient.Builder()
                .addInterceptor(AddonHostGuardInterceptor(AddonHostGuard(nowMs = System::currentTimeMillis)))
                .addInterceptor(identityHeadersInterceptor())
                .addInterceptor(untrustedResponseGuardInterceptor())
                .build()
        )
        .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
        .build()
        .create(AddonApi::class.java)

    /** A series meta shaped like AIOMetadata's (~520 B per video), [videos] episodes long. */
    private fun seriesMetaJson(videos: Int): String = buildString {
        append("{\"meta\":{\"id\":\"tt0159881\",\"type\":\"series\",\"name\":\"Jeopardy!\",\"videos\":[")
        for (i in 0 until videos) {
            if (i > 0) append(',')
            val season = i / 230 + 1
            val episode = i % 230 + 1
            append("{\"id\":\"tt0159881:$season:$episode\",\"title\":\"Episode $episode\",")
            append("\"season\":$season,\"episode\":$episode,\"released\":\"1990-01-01T00:00:00.000Z\",")
            append("\"overview\":\"").append("x".repeat(300)).append("\",")
            append("\"thumbnail\":\"https://image.tmdb.org/t/p/w500/abcdefghijklmnop$i.jpg\",")
            append("\"runtime\":\"22m\",\"available\":true}")
        }
        append("]}}")
    }

    @Test
    fun `addon meta over 2MB passes under its own cap`() = runBlocking {
        // Spec 82: Jeopardy!'s meta is 4.9 MB decompressed (9,656 videos).
        val json = seriesMetaJson(10_000)
        assertTrue("fixture must exceed the default cap", json.length > 2 * UNTRUSTED_JSON_BODY_CAP_BYTES)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
        val response = addonApi().getMeta(server.url("/meta/series/tt0159881.json").toString())
        assertEquals(10_000, response.body()?.meta?.videos?.size)
    }

    @Test
    fun `addon meta over 16MB is rejected`() {
        val body = Buffer().writeUtf8("{\"meta\":null}")
            .write(jsonWhitespace(UntrustedBodyCap.META.maxBytes + 100_000))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val failure = runCatching {
            runBlocking { addonApi().getMeta(server.url("/meta/series/tt1.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("16MB cap"))
    }

    @Test
    fun `addon meta of many empty values is rejected before it decodes`() {
        // ~3 bytes per element, every VideoDto field defaulted: 1.5 MB of `{}` would decode
        // into 500k objects; at 16 MB, millions (an uncaught OutOfMemoryError).
        val bomb = "{\"meta\":{\"id\":\"x\",\"type\":\"series\",\"name\":\"x\",\"videos\":[" +
            List(UntrustedBodyCap.META.maxValues.toInt()) { "{}" }.joinToString(",") + "]}}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(bomb))
        val failure = runCatching {
            runBlocking { addonApi().getMeta(server.url("/meta/series/tt1.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("more than ${UntrustedBodyCap.META.maxValues} values"))
    }

    @Test
    fun `addon meta values are counted at commas too, not only brackets`() {
        // A JsonElement field (cast) of bare scalars: one `[`, then only commas.
        val zeros = List(UntrustedBodyCap.META.maxValues.toInt() + 1) { "0" }.joinToString(",")
        val body = "{\"meta\":{\"id\":\"x\",\"type\":\"series\",\"name\":\"x\",\"cast\":[$zeros]}}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val failure = runCatching {
            runBlocking { addonApi().getMeta(server.url("/meta/series/tt1.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("more than ${UntrustedBodyCap.META.maxValues} values"))
    }

    @Test
    fun `untagged responses have no value limit`() = runBlocking {
        val zeros = List(UntrustedBodyCap.META.maxValues.toInt() + 100_000) { "0" }.joinToString(",")
        val body = "{\"metas\":[],\"extra\":[$zeros]}"
        assertTrue("fixture must stay under the default byte cap", body.length < UNTRUSTED_JSON_BODY_CAP_BYTES)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val response = addonApi().getCatalog(server.url("/catalog/series/top.json").toString())
        assertEquals(0, response.body()?.metas?.size)
    }

    @Test
    fun `value counter boundary, and separators inside strings are not counted`() {
        fun feed(counter: JsonNestingDepthCounter, json: String) =
            json.toByteArray().all { counter.accept(it) }

        val atLimit = JsonNestingDepthCounter(UNTRUSTED_JSON_MAX_DEPTH, maxValues = 3)
        assertTrue(feed(atLimit, "[1,2,3"))
        assertTrue(!feed(atLimit, ",4"))
        assertTrue(atLimit.tooManyValues)

        val strings = JsonNestingDepthCounter(UNTRUSTED_JSON_MAX_DEPTH, maxValues = 2)
        assertTrue(feed(strings, "[\"a,\\\",{[\",1]"))
        assertTrue(!strings.tooManyValues)
    }

    @Test
    fun `addon meta error bodies keep the 2MB cap`() {
        // Retrofit heap-buffers non-2xx bodies whole; only a successful meta needs 16 MB.
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(Buffer().write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000)))
        )
        val failure = runCatching {
            runBlocking { addonApi().getMeta(server.url("/meta/series/tt1.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("2MB cap"))
    }

    @Test
    fun `non-meta addon responses over 2MB are still rejected`() {
        val body = Buffer().writeUtf8("{\"metas\":[]}")
            .write(jsonWhitespace(UNTRUSTED_JSON_BODY_CAP_BYTES + 100_000))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val failure = runCatching {
            runBlocking { addonApi().getCatalog(server.url("/catalog/series/top.json").toString()) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("2MB cap"))
    }

    @Test
    fun `non-json error bodies are not depth-checked`() {
        // An HTML or plain-text error page keeps its HTTP status for the caller; only the byte cap applies.
        val page = "<script>" + "[".repeat(1_000) + "</script>"
        for (type in listOf("text/html", "text/plain; charset=windows-1252")) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", type)
                    .setBody(page)
            )
            execute().use { response ->
                assertEquals(404, response.code)
                assertEquals(page, response.body.string())
            }
        }
    }

    /** A stream as AIOStreams sends it to the AIO User-Agent (streamData shape of a real 2026-11-11 answer). */
    private fun aioStreamJson(i: Int): String = buildString {
        val file = "Show.S01E01.1080p.BluRay.x264-GROUP$i.mkv"
        append("{\"name\":\"[RD+] AIOStreams 1080p\",\"description\":\"").append("d".repeat(146)).append("\",")
        append("\"url\":\"https://aio.example/playback/").append("u".repeat(700)).append("/$i\",")
        append("\"behaviorHints\":{\"videoHash\":\"0123456789abcdef\",\"videoSize\":1234567890,")
        append("\"filename\":\"$file\",\"folderSize\":9876543210,\"bingeGroup\":\"aio|1080p|BluRay\"},")
        append("\"streamData\":{\"type\":\"debrid\",\"proxied\":false,\"age\":120,\"duration\":2700000,")
        append("\"library\":false,\"size\":1234567890,\"folderSize\":9876543210,\"torrent\":{\"infoHash\":\"")
        append("a".repeat(40)).append("\",\"file\":\"$file\",\"title\":\"").append("t".repeat(88))
        append("\",\"fileIdx\":$i,\"private\":false,\"freeleech\":false},\"addon\":\"Torrentio\",")
        append("\"filename\":\"$file\",\"folderName\":\"").append("f".repeat(88))
        append("\",\"service\":{\"id\":\"realdebrid\",\"cached\":true},\"parsedFile\":{\"title\":\"Show\",")
        append("\"folderSeasons\":[1,2,3,4,5],\"seasons\":[1],\"episodes\":[1],\"resolution\":\"1080p\",")
        append("\"quality\":\"BluRay\",\"encode\":\"AVC\",\"releaseGroup\":\"GROUP\",\"editions\":[],")
        append("\"repack\":false,\"container\":\"mkv\",\"visualTags\":[],\"audioTags\":[\"DTS-HD MA\",\"AC3\"],")
        append("\"audioChannels\":[\"5.1\",\"2.0\"],\"languages\":[\"English\",\"Spanish\",\"French\"],\"audioTracks\":[")
        repeat(7) { t ->
            if (t > 0) append(',')
            append("{\"lang\":\"English\",\"codec\":\"DTS\",\"title\":\"").append("a".repeat(20))
            append("\",\"tags\":[\"DTS-HD MA\"],\"channels\":\"5.1\",\"default\":${t == 0}}")
        }
        append("],\"duration\":2700.5,\"bitrate\":8000000},\"keywordMatched\":false,")
        append("\"rankedStreamExpressionsMatched\":[\"a1\",\"b2\",\"c3\",\"d4\"],\"regexScore\":5,\"id\":\"s$i\"}}")
    }

    @Test
    fun `addon stream list over 2MB passes under its own cap`() = runBlocking {
        // An AIOStreams list with no result limit (Spec 84 A5): ~3 KB per stream with streamData.
        val json = (0 until 1_000).joinToString(",", "{\"streams\":[", "]}") { aioStreamJson(it) }
        assertTrue("fixture must exceed the default cap", json.length > UNTRUSTED_JSON_BODY_CAP_BYTES)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
        val response = addonApi().getStreams(server.url("/stream/movie/tt1.json").toString(), aioStreamDataUserAgent())
        assertEquals(1_000, response.body()?.streams?.size)
    }

    @Test
    fun `addon stream list over 8MB is rejected`() {
        val body = Buffer().writeUtf8("{\"streams\":[]}")
            .write(jsonWhitespace(UntrustedBodyCap.STREAMS.maxBytes + 100_000))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val failure = runCatching {
            runBlocking { addonApi().getStreams(server.url("/stream/movie/tt1.json").toString(), null) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("8MB cap"))
    }

    @Test
    fun `addon stream list of many empty values is rejected before it decodes`() {
        // Every StreamDto field defaults: `[{},{},...]` would decode into one object per 3 bytes.
        val bomb = List(UntrustedBodyCap.STREAMS.maxValues.toInt()) { "{}" }.joinToString(",", "{\"streams\":[", "]}")
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(bomb))
        val failure = runCatching {
            runBlocking { addonApi().getStreams(server.url("/stream/movie/tt1.json").toString(), null) }
        }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("more than ${UntrustedBodyCap.STREAMS.maxValues} values"))
    }
}
