package com.nuvio.tv.livetv.resolve

import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/** Spec 72 A0.1: URL matching, Pluto/Plex resolves, token cache and expiry, fallback to the stored URL. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamResolverTest {

    private val plutoId = "5bd833b41843b56328bac189"
    private val plexKey = "647f8d0a1b2c3d4e5f607182-5e20b730f2f8d5003d739db7"
    private val plutoStored = LiveStream("https://jmp2.uk/plu-$plutoId.m3u8", mapOf("User-Agent" to "Kodi/21"))
    private val plexStored = LiveStream("https://jmp2.uk/plex-$plexKey.m3u8")

    /** A fake network: answers each request from [route] without a socket and records it. */
    private class FakeHttp(val route: (Request) -> Response) : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return route(request)
        }
        fun count(host: String) = requests.count { it.url.host == host }
    }

    private fun respond(request: Request, code: Int, body: String = "", finalUrl: String? = null): Response =
        Response.Builder()
            .request(finalUrl?.let { request.newBuilder().url(it).build() } ?: request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody())
            .build()

    private val time = TestTimeSource()

    private fun resolver(fake: FakeHttp, budgetMs: Long = StreamResolver.RESOLVE_BUDGET_MS, io: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher()): StreamResolver {
        val http = ResolverHttp(OkHttpClient.Builder().addInterceptor(fake).build(), io)
        return StreamResolver(PlutoResolver(http, time), PlexResolver(http, time), budgetMs)
    }

    private fun jwt(lifetimeSec: Long): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val payload = b64.encode("""{"exp":${1_000_000 + lifetimeSec},"iat":1000000,"clientIP":"x"}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.c2ln"
    }

    private fun boot(token: String = jwt(86_400), stitcher: String = "https://cfd-v4-stitcher.prd.pluto.tv", refreshInSec: Long = 28_800) =
        """{"sessionToken":"$token","servers":{"stitcher":"$stitcher"},"stitcherParams":"appName=web&sid=abc","refreshInSec":$refreshInSec,"extra":[1,2]}"""

    private fun plutoFake(body: () -> String = { boot() }, code: Int = 200) =
        FakeHttp { request ->
            assertEquals("boot.pluto.tv", request.url.host)
            respond(request, code, body())
        }

    // --- URL matching ---

    @Test
    fun `matches Pluto and Plex stored URLs, leaves the rest alone`() {
        assertEquals(StreamResolver.Target.Pluto(plutoId), StreamResolver.targetOf(plutoStored.url))
        assertEquals(StreamResolver.Target.Pluto(plutoId), StreamResolver.targetOf("http://jmp2.uk/plu-$plutoId.m3u8"))
        assertEquals(
            StreamResolver.Target.Pluto("5f4e8aac1f86be0007e50601"),
            StreamResolver.targetOf("https://service-stitcher.clusters.pluto.tv/stitch/hls/channel/5f4e8aac1f86be0007e50601/master.m3u8?jwt=old")
        )
        assertEquals(StreamResolver.Target.Plex(plexKey), StreamResolver.targetOf(plexStored.url))
        assertNull(StreamResolver.targetOf("https://jmp2.uk/stvp-GBBC19000017V"))
        assertNull(StreamResolver.targetOf("https://jmp2.uk/plu-$plutoId.m3u8.evil.com/x"))
        assertNull(StreamResolver.targetOf("https://evil.com/?u=https://jmp2.uk/plu-$plutoId.m3u8"))
        assertNull(StreamResolver.targetOf("https://pluto.tv.evil.com/channel/$plutoId/master.m3u8"))
        assertNull(StreamResolver.targetOf("https://example.com/live/index.m3u8"))
    }

    @Test
    fun `an unmatched stream plays as stored without a request`() = runTest {
        val fake = FakeHttp { error("no request expected") }
        val stored = LiveStream("https://example.com/live.m3u8", mapOf("Referer" to "https://example.com/"))
        assertSame(stored, resolver(fake).resolve(stored, fresh = false).stream)
        assertTrue(fake.requests.isEmpty())
    }

    // --- Pluto ---

    @Test
    fun `Pluto fills the stitcher template with the session and drops the stored headers`() = runTest {
        val token = jwt(86_400)
        val fake = plutoFake({ boot(token) })
        val resolution = resolver(fake).resolve(plutoStored, fresh = false)
        val url = resolution.stream.url.toHttpUrl()
        assertEquals("cfd-v4-stitcher.prd.pluto.tv", url.host)
        assertTrue(url.isHttps)
        assertEquals("/v2/stitch/hls/channel/$plutoId/master.m3u8", url.encodedPath)
        assertEquals("web", url.queryParameter("appName"))
        assertEquals("abc", url.queryParameter("sid"))
        assertEquals(token, url.queryParameter("jwt"))
        assertEquals("true", url.queryParameter("masterJWTPassthrough"))
        assertTrue(resolution.stream.headers.isEmpty())
        assertFalse(resolution.fromCache)
        val bootRequest = fake.requests.single()
        assertTrue(bootRequest.header("User-Agent")!!.startsWith("Mozilla/5.0"))
        assertTrue(bootRequest.url.queryParameter("clientID")!!.isNotBlank())
    }

    @Test
    fun `Pluto reuses one boot across channels until refreshInSec minus the margin`() = runTest {
        val fake = plutoFake()
        val resolver = resolver(fake)
        resolver.resolve(plutoStored, fresh = false)
        val second = resolver.resolve(LiveStream("https://jmp2.uk/plu-5f4e8aac1f86be0007e50601.m3u8"), fresh = false)
        assertTrue(second.fromCache)
        assertEquals(1, fake.count("boot.pluto.tv"))
        time += 8.hours - 11.minutes
        assertTrue(resolver.resolve(plutoStored, fresh = false).fromCache)
        time += 2.minutes
        assertFalse(resolver.resolve(plutoStored, fresh = false).fromCache)
        assertEquals(2, fake.count("boot.pluto.tv"))
    }

    @Test
    fun `Pluto's JWT lifetime caps the session when shorter than refreshInSec`() = runTest {
        val fake = plutoFake({ boot(token = jwt(3_600), refreshInSec = 28_800) })
        val resolver = resolver(fake)
        resolver.resolve(plutoStored, fresh = false)
        time += 51.minutes
        assertFalse(resolver.resolve(plutoStored, fresh = false).fromCache)
        assertEquals(2, fake.count("boot.pluto.tv"))
    }

    @Test
    fun `concurrent Pluto resolves share one boot`() = runTest {
        val fake = plutoFake()
        // A queued IO dispatcher: the first resolve holds the boot open while the second arrives.
        val resolver = resolver(fake, io = StandardTestDispatcher(testScheduler))
        val first = async { resolver.resolve(plutoStored, fresh = false) }
        val second = async { resolver.resolve(plutoStored, fresh = false) }
        val results = awaitAll(first, second)
        assertEquals(1, fake.count("boot.pluto.tv"))
        assertEquals(listOf(false, true), results.map { it.fromCache })
    }

    @Test
    fun `a fresh resolve boots again`() = runTest {
        val fake = plutoFake()
        val resolver = resolver(fake)
        resolver.resolve(plutoStored, fresh = false)
        assertFalse(resolver.resolve(plutoStored, fresh = true).fromCache)
        assertEquals(2, fake.count("boot.pluto.tv"))
    }

    @Test
    fun `Pluto falls back to the stored URL on a bad boot`() = runTest {
        val bad = listOf(
            plutoFake(code = 500),
            plutoFake({ boot(stitcher = "https://stitcher.evil.com") }),
            plutoFake({ boot(stitcher = "http://cfd.prd.pluto.tv") }),
            plutoFake({ boot(token = "abc.def.ghi&evil=1") }),
            plutoFake({ "<html>maintenance</html>" }),
            plutoFake({ """{"sessionToken":"${jwt(10)}","pad":"${"x".repeat(300 * 1024)}"}""" })
        )
        for (fake in bad) {
            assertSame(plutoStored, resolver(fake).resolve(plutoStored, fresh = false).stream)
        }
    }

    @Test
    fun `a failed boot is not cached`() = runTest {
        var code = 503
        val fake = FakeHttp { respond(it, code, boot()) }
        val resolver = resolver(fake)
        assertSame(plutoStored, resolver.resolve(plutoStored, fresh = false).stream)
        code = 200
        assertFalse(resolver.resolve(plutoStored, fresh = false).stream === plutoStored)
    }

    @Test
    fun `reads the JWT lifetime, null when unreadable`() {
        assertEquals(86_400L, PlutoResolver.jwtLifetimeSec(jwt(86_400)))
        assertNull(PlutoResolver.jwtLifetimeSec("a.%%%.c"))
        assertNull(PlutoResolver.jwtLifetimeSec("a.b"))
        assertNull(PlutoResolver.jwtLifetimeSec("nodots"))
    }

    // --- Plex ---

    private val mediaTailor = "https://abc.mediatailor.us-east-1.amazonaws.com/v1/master/x/y/master.m3u8"

    /** Sign-in answers [signIn] (token per call: t1, t2, ...); parts answer [parts] for the token. */
    private fun plexFake(signIn: (Int) -> Pair<Int, String> = { 201 to """{"id":1,"authToken":"t$it"}""" }, parts: (String) -> Int = { 200 }): FakeHttp {
        var signIns = 0
        return FakeHttp { request ->
            when (request.url.host) {
                "clients.plex.tv" -> {
                    assertEquals("POST", request.method)
                    val (code, body) = signIn(++signIns)
                    respond(request, code, body)
                }
                "epg.provider.plex.tv" -> {
                    assertEquals("/library/parts/$plexKey.m3u8", request.url.encodedPath)
                    val code = parts(request.url.queryParameter("X-Plex-Token")!!)
                    respond(request, code, finalUrl = mediaTailor.takeIf { code == 200 })
                }
                else -> error("unexpected host ${request.url.host}")
            }
        }
    }

    @Test
    fun `Plex signs in, checks the parts URL and plays where it led`() = runTest {
        val fake = plexFake()
        val resolution = resolver(fake).resolve(plexStored, fresh = false)
        assertEquals(mediaTailor, resolution.stream.url)
        assertFalse(resolution.fromCache)
        assertEquals(1, fake.count("clients.plex.tv"))
        assertTrue(fake.requests.first().url.queryParameter("X-Plex-Client-Identifier")!!.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `Plex reads an XML token and reuses it for five hours`() = runTest {
        val fake = plexFake(signIn = { 201 to """<?xml version="1.0"?><user id="1" authToken="x$it" />""" })
        val resolver = resolver(fake)
        resolver.resolve(plexStored, fresh = false)
        resolver.resolve(plexStored, fresh = false)
        assertEquals(1, fake.count("clients.plex.tv"))
        assertEquals("x1", fake.requests.last().url.queryParameter("X-Plex-Token"))
        time += 5.hours
        resolver.resolve(plexStored, fresh = false)
        assertEquals(2, fake.count("clients.plex.tv"))
    }

    @Test
    fun `a fresh Plex resolve signs in again`() = runTest {
        val fake = plexFake()
        val resolver = resolver(fake)
        resolver.resolve(plexStored, fresh = false)
        resolver.resolve(plexStored, fresh = true)
        assertEquals(2, fake.count("clients.plex.tv"))
        assertEquals("t2", fake.requests.last().url.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `a cached Plex token refused gets one fresh token`() = runTest {
        val fake = plexFake(parts = { token -> if (token == "t1") 401 else 200 })
        val resolver = resolver(fake)
        // t1 is fresh on the first resolve: refused with a fresh token -> stored URL, no second sign-in.
        assertSame(plexStored, resolver.resolve(plexStored, fresh = false).stream)
        assertEquals(1, fake.count("clients.plex.tv"))
        // t1 is now cached: refused again -> t2 -> playable.
        assertEquals(mediaTailor, resolver.resolve(plexStored, fresh = false).stream.url)
        assertEquals(2, fake.count("clients.plex.tv"))
    }

    @Test
    fun `Plex 404 and 429 fall back to the stored URL without a retry`() = runTest {
        for (code in listOf(404, 429)) {
            val fake = plexFake(parts = { code })
            val resolver = resolver(fake)
            resolver.resolve(plexStored, fresh = false) // caches the token
            assertSame(plexStored, resolver.resolve(plexStored, fresh = false).stream)
            assertEquals(1, fake.count("clients.plex.tv"))
            assertEquals(2, fake.count("epg.provider.plex.tv"))
        }
    }

    @Test
    fun `Plex sign-in failures fall back to the stored URL`() = runTest {
        val bad = listOf<(Int) -> Pair<Int, String>>(
            { 429 to "" },
            { 201 to """{"authToken":"bad&token"}""" },
            { 201 to """{"id":1}""" },
            { 201 to "not json, no token" }
        )
        for (signIn in bad) {
            val fake = plexFake(signIn = signIn)
            assertSame(plexStored, resolver(fake).resolve(plexStored, fresh = false).stream)
            assertEquals(0, fake.count("epg.provider.plex.tv"))
        }
    }

    // --- Budget ---

    @Test
    fun `a resolve slower than its budget plays the stored URL`() = runBlocking {
        // Real IO thread: the fake blocks like a slow host would; the budget cancels the call.
        val fake = FakeHttp { request ->
            Thread.sleep(300)
            respond(request, 200, boot())
        }
        assertSame(plutoStored, resolver(fake, budgetMs = 50, io = Dispatchers.IO).resolve(plutoStored, fresh = false).stream)
    }
}
