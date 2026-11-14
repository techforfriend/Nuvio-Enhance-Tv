package com.nuvio.tv.core.tip

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.update.AUTO_CHECK_INTERVAL_MS
import com.nuvio.tv.core.update.TipRecord
import com.nuvio.tv.core.update.UpdateStore
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.remote.api.GitHubFileApi
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec 81 T1: the tip links over the real Retrofit + wire path (MockWebServer), an in-memory store
 * and a fixed clock: what each answer leaves, the daily limit, the cap, the fallback and the gate.
 */
class TipLinkRepositoryTest {

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = AtomicLong(100L * AUTO_CHECK_INTERVAL_MS)
    private val prefs = TestPreferencesDataStore()
    private val store = UpdateStore(prefs)

    private val api: GitHubFileApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build())
        .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
        .build()
        .create(GitHubFileApi::class.java)

    private val stripe = "https://buy.stripe.com/live_link"
    private val builtIn = "https://buy.stripe.com/built_in"
    private val inr = "https://buy.stripe.com/inr_link"

    private fun repo(
        fallback: String = builtIn,
        country: String = "US",
        over: UpdateStore = store
    ) = TipLinkRepository(api, over, TipSource("Arshad-Kamal", "Nuvio-KE", fallback), scope, now::get) { country }

    private fun answer(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    /** Runs one refresh to its end and fails the test if it ended in an exception (a crash on the device). */
    private fun TipLinkRepository.refreshed(): TipLinkRepository = apply {
        val job = refreshIfDue()
        runBlocking { job.join() }
        assertFalse("the refresh failed", job.isCancelled)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `a usable file sets the link and is stored`() {
        answer(200, """{"link":"$stripe"}""")
        assertEquals(stripe, repo().refreshed().link.value)
        val request = server.takeRequest()
        assertEquals("/repos/Arshad-Kamal/Nuvio-KE/contents/tip.json", request.path)
        assertEquals("application/vnd.github.raw+json", request.getHeader("Accept"))
        assertEquals(TipRecord(TipLinks(stripe), now.get()), runBlocking { store.tipRecord() })
    }

    @Test
    fun `a null link pauses tips, over the built-in link`() {
        answer(200, """{"link":null}""")
        assertNull(repo().refreshed().link.value)
        assertEquals(TipLinks(null), runBlocking { store.tipRecord() }.links)
    }

    @Test
    fun `a country override applies to that country only`() {
        answer(200, """{"link":"$stripe","links":{"IN":"$inr"}}""")
        assertEquals(inr, repo(country = "IN").refreshed().link.value)
        assertEquals(stripe, repo(country = "US").refreshed().link.value)
    }

    @Test
    fun `no file falls back to the built-in link and drops the stored answer`() {
        runBlocking { store.recordTipAnswer(TipLinks(stripe), 0L) }
        answer(404, """{"message":"Not Found"}""")
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals(TipRecord(null, now.get()), runBlocking { store.tipRecord() })
    }

    @Test
    fun `no file and no valid built-in link hide Tip`() {
        answer(404, "{}")
        assertNull(repo(fallback = "").refreshed().link.value)
        answer(404, "{}")
        now.addAndGet(AUTO_CHECK_INTERVAL_MS)
        assertNull(repo(fallback = "https://evil.example/pay").refreshed().link.value)
    }

    @Test
    fun `an unusable answer keeps the last good links and counts as the day's attempt`() {
        runBlocking { store.recordTipAnswer(TipLinks(stripe), 0L) }
        listOf(
            { answer(200, """{"link":"https://evil.example/pay"}""") },
            { answer(200, "not json") },
            { answer(500, """{"link":"https://buy.stripe.com/other"}""") },
            { answer(403, """{"message":"rate limited"}""") },
            { answer(200, """{"link":"$builtIn","pad":"${"x".repeat(TIP_FILE_MAX_BYTES)}"}""") },
            // Valid JSON within the first 4 KB: a fetch that parsed a truncated body would accept it.
            { server.enqueue(MockResponse().setChunkedBody("""{"link":"$builtIn"}""" + " ".repeat(TIP_FILE_MAX_BYTES), 256)) }
        ).forEach { enqueue ->
            enqueue()
            now.addAndGet(AUTO_CHECK_INTERVAL_MS)
            assertEquals(stripe, repo().refreshed().link.value)
            assertEquals(TipRecord(TipLinks(stripe), now.get()), runBlocking { store.tipRecord() })
        }
    }

    @Test
    fun `an unusable answer with nothing stored shows the built-in link`() {
        answer(200, "[]")
        assertEquals(builtIn, repo().refreshed().link.value)
    }

    @Test
    fun `no answer records nothing, so the next start tries again`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals(TipRecord(), runBlocking { store.tipRecord() })
        answer(200, """{"link":"$stripe"}""")
        assertEquals(stripe, repo().refreshed().link.value)
    }

    @Test
    fun `a body cut off midway is no answer`() {
        server.enqueue(
            MockResponse().setBody("""{"link":"$stripe"}""").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals(TipRecord(), runBlocking { store.tipRecord() })
    }

    @Test
    fun `at most one fetch a day, the stored answer shown meanwhile`() {
        answer(200, """{"link":"$stripe"}""")
        repo().refreshed()
        now.addAndGet(AUTO_CHECK_INTERVAL_MS - 1)
        assertEquals(stripe, repo().refreshed().link.value)
        assertEquals(1, server.requestCount)
        now.addAndGet(1)
        answer(200, """{"link":"$inr"}""")
        assertEquals(inr, repo().refreshed().link.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a store that cannot be read or written still shows the fetched link`() {
        val broken = UpdateStore(object : TestPreferencesDataStore() {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk")
        })
        answer(200, """{"link":"$stripe"}""")
        assertEquals(stripe, repo(over = broken).refreshed().link.value)
    }

    @Test
    fun `a store that failed to read is read again on the next refresh`() {
        var failRead = true
        val flaky = UpdateStore(object : TestPreferencesDataStore() {
            override val data: Flow<Preferences> = flow {
                if (failRead) throw IOException("disk")
                emitAll(super.data)
            }
        })
        runBlocking { flaky.recordTipAnswer(TipLinks(stripe), now.get()) }
        val repo = repo(over = flaky)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(builtIn, repo.refreshed().link.value)
        failRead = false
        assertEquals(stripe, repo.refreshed().link.value)
    }

    @Test
    fun `a failed write keeps this run's answer when the activity starts again`() {
        val readOnly = UpdateStore(object : TestPreferencesDataStore() {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        })
        answer(200, """{"link":"$stripe"}""")
        val repo = repo(over = readOnly).refreshed()
        assertEquals(stripe, repo.refreshed().link.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the freeze switch pauses an override country too`() {
        answer(200, """{"link":null,"links":{"IN":"$inr"}}""")
        assertNull(repo(country = "IN").refreshed().link.value)
    }

    @Test
    fun `two refreshes at once fetch once`() {
        server.enqueue(MockResponse().setBody("""{"link":"$stripe"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val repo = repo()
        val jobs = listOf(repo.refreshIfDue(), repo.refreshIfDue())
        runBlocking { jobs.forEach { it.join() } }
        assertFalse(jobs.any { it.isCancelled })
        assertEquals(1, server.requestCount)
        assertEquals(stripe, repo.link.value)
    }

    @Test
    fun `an oversize body without a length, or one that inflates past the cap, is unusable`() {
        server.enqueue(MockResponse().setChunkedBody("""{"link":"$stripe","pad":"${"x".repeat(TIP_FILE_MAX_BYTES)}"}""", 512))
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals(TipRecord(null, now.get()), runBlocking { store.tipRecord() })
        val zipped = Buffer()
        GzipSink(zipped).buffer().use { it.writeUtf8("""{"link":"$stripe","pad":"${"x".repeat(64 * 1024)}"}""") }
        server.enqueue(MockResponse().setBody(zipped).addHeader("Content-Encoding", "gzip"))
        now.addAndGet(AUTO_CHECK_INTERVAL_MS)
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals("an answer (unusable), not a lost connection", TipRecord(null, now.get()), runBlocking { store.tipRecord() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `stored links that no longer validate read as none`() {
        runBlocking {
            prefs.updateData { p ->
                p.toMutablePreferences().apply {
                    this[UpdateStore.TIP_LINKS] = """{"link":"https://evil.example/pay"}"""
                    this[UpdateStore.TIP_ATTEMPT_MS] = now.get()
                }
            }
        }
        assertEquals(builtIn, repo().refreshed().link.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the cap reads at most one byte past it`() {
        val exact = Buffer().writeUtf8("x".repeat(TIP_FILE_MAX_BYTES))
        assertEquals(TIP_FILE_MAX_BYTES, readCapped(exact.asResponseBody(null, -1))?.length)
        val huge = Buffer().writeUtf8("x".repeat(1024 * 1024))
        assertNull(readCapped(huge.asResponseBody(null, -1)))
        assertEquals(1024L * 1024 - (TIP_FILE_MAX_BYTES + 1), huge.size)
        val declared = Buffer().writeUtf8("{}")
        assertNull(readCapped(declared.asResponseBody(null, TIP_FILE_MAX_BYTES + 1L)))
        assertEquals("the declared length alone refuses it", 2L, declared.size)
    }
}
