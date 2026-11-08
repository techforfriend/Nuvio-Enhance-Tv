package com.nuvio.tv.core.update

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec 52 P6-P8: the controller over the real Retrofit + wire-Json path (MockWebServer), a fake
 * store file and a fixed clock: what each answer records, what it leaves, single-flight, the gate;
 * Download and install over the real downloader (temp folder, archive reader and installer faked).
 */
class UpdateControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = AtomicLong(100L * AUTO_CHECK_INTERVAL_MS)
    private val store = UpdateStore(TestPreferencesDataStore())

    private val api: GitHubReleaseApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build())
        .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
        .build()
        .create(GitHubReleaseApi::class.java)

    private val apk = ByteArray(150_000) { (it * 7).toByte() }
    private val apkSha256 = apk.toByteString().sha256().hex()
    private val updatesDir by lazy { File(tmp.root, UPDATES_DIR) }
    private val installed = mutableListOf<File>()
    @Volatile private var install: (File) -> Boolean = { file -> installed += file; true }
    @Volatile private var archiveIdentity: ApkIdentity? = ApkIdentity("com.nuvio.tv.ak", 1392)

    /** The asset URL is https (the offer's rule); this client sends every request to the mock server. */
    private val toServer = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }
        .build()

    private fun controller(supported: Boolean = true, installedCode: Int = 1391, over: UpdateStore = store) =
        UpdateController(
            api, over, UpdateFeed(supported, "Arshad-Kamal", "Nuvio-AK", installedCode, listOf("armeabi-v7a")), scope, now::get,
            ApkDownloader(toServer, updatesDir, "com.nuvio.tv.ak") { archiveIdentity },
            { file -> install(file) }
        )

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun releaseJson(code: Int = 1392, size: Long = 41_000_000, sha256: String = "ab".repeat(32)) = """
        {"name":"Nuvio AK","body":"Notes","published_at":"2026-11-03T10:00:00Z","draft":false,
         "assets":[{"name":"NuvioAK-0.9.0-ak51-$code-armeabi-v7a.apk","state":"uploaded","size":$size,
           "digest":"sha256:$sha256","content_type":"application/vnd.android.package-archive",
           "browser_download_url":"https://github.com/Arshad-Kamal/Nuvio-AK/releases/download/v/NuvioAK-0.9.0-ak51-$code-armeabi-v7a.apk"}]}
    """.trimIndent()

    private fun answer(code: Int, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    /** A latest release whose asset is [apk]; [sha256] other than its own = a file that does not match. */
    private fun downloadable(code: Int = 1392, sha256: String = apkSha256) =
        answer(200, releaseJson(code, apk.size.toLong(), sha256))

    private fun serveApk() = server.enqueue(MockResponse().setBody(Buffer().write(apk)))

    private suspend fun UpdateController.downloadState(predicate: (DownloadState) -> Boolean): DownloadState =
        withTimeout(5_000) { state.first { predicate(it.download) } }.download

    private suspend fun UpdateController.settled(predicate: (UpdateState) -> Boolean): UpdateState =
        withTimeout(5_000) { state.first { !it.checking && predicate(it) } }

    @Test
    fun `a newer release is known and available, asked at the path and with the headers GitHub expects`() = runBlocking<Unit> {
        answer(200, releaseJson())
        val controller = controller()

        assertEquals(1392, (controller.awaitCheck() as CheckOutcome.Answered).offer?.versionCode)
        val request = server.takeRequest()
        assertEquals("/repos/Arshad-Kamal/Nuvio-AK/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))

        val record = store.read()
        assertEquals(now.get(), record.lastOkCheckMs)
        assertEquals(now.get(), record.lastAttemptMs)
        val state = controller.settled { it.available != null }
        assertEquals(1392, state.available?.versionCode)
        assertFalse(state.lastCheckFailed)
    }

    @Test
    fun `a release no newer than this build is known but not available`() = runBlocking<Unit> {
        answer(200, releaseJson(code = 1391))
        val controller = controller(installedCode = 1391)
        controller.awaitCheck()
        assertEquals(1391, store.read().knownRelease?.versionCode)
        assertNull(controller.settled { it.lastOkCheckMs == now.get() }.available)
    }

    @Test
    fun `a 404 or a release with nothing for this TV drops the known release`() = runBlocking<Unit> {
        answer(200, releaseJson())
        answer(404)
        answer(200, releaseJson())
        answer(200, """{"name":"x","assets":[]}""")
        val controller = controller()

        controller.awaitCheck()
        assertEquals(CheckOutcome.NoRelease, controller.awaitCheck())
        assertNull(store.read().knownRelease)
        controller.settled { it.lastCheckFailed && it.available == null }

        controller.awaitCheck()
        assertEquals(CheckOutcome.Answered(null), controller.awaitCheck())
        assertNull(store.read().knownRelease)
        controller.settled { !it.lastCheckFailed && it.available == null }
    }

    @Test
    fun `a failed answer keeps the known release and counts as an attempt`() = runBlocking<Unit> {
        answer(200, releaseJson())
        val controller = controller()
        controller.awaitCheck()
        val okAt = now.get()

        now.addAndGet(1_000)
        answer(403)
        assertEquals(CheckOutcome.Failed, controller.awaitCheck())
        now.addAndGet(1_000)
        answer(200, """{"assets":"not a list"}""")
        assertEquals(CheckOutcome.Failed, controller.awaitCheck())

        val record = store.read()
        assertEquals(1392, record.knownRelease?.versionCode)
        assertEquals(okAt, record.lastOkCheckMs)
        assertEquals(now.get(), record.lastAttemptMs)
        controller.settled { it.lastCheckFailed && it.available?.versionCode == 1392 }
    }

    @Test
    fun `no answer at all leaves no attempt, so the next start retries`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val controller = controller()
        assertEquals(CheckOutcome.Unreached, controller.awaitCheck())
        assertEquals(UpdateRecord(), store.read())
        controller.settled { it.lastCheckFailed }
    }

    @Test
    fun `a store write that fails keeps nothing and counts as no answer`() = runBlocking<Unit> {
        val failing = object : TestPreferencesDataStore() {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        answer(200, releaseJson())
        val controller = controller(over = UpdateStore(failing))
        assertEquals(CheckOutcome.Unreached, controller.awaitCheck())
        val state = controller.settled { it.lastCheckFailed }
        assertNull(state.available)
        assertEquals(0L, state.lastOkCheckMs)
        assertFalse(controller.setAutoCheck(false))
        assertEquals(true, controller.settled { true }.autoCheck)
    }

    @Test
    fun `a store that cannot be read reads as the defaults, so the check still runs`() = runBlocking<Unit> {
        val unreadable = object : TestPreferencesDataStore() {
            override val data: Flow<Preferences> = flow { throw IOException("unreadable") }
        }
        assertEquals(UpdateRecord(), UpdateStore(unreadable).read())
        val controller = controller(over = UpdateStore(unreadable))
        answer(200, releaseJson())
        controller.autoCheckIfDue()!!.join()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a stored release that does not decode reads as none, unknown fields are skipped`() = runBlocking<Unit> {
        val bad = UpdateStore(TestPreferencesDataStore(preferencesOf(UpdateStore.KNOWN_RELEASE to "{not json")))
        assertNull(bad.read().knownRelease)
        val later = """{"versionName":"0.9.0-ak51","versionCode":1392,"abi":"armeabi-v7a","downloadUrl":"https://x/a.apk",
            "sizeBytes":5,"sha256":"ab","notes":"","addedLater":true}"""
        val newer = UpdateStore(TestPreferencesDataStore(preferencesOf(UpdateStore.KNOWN_RELEASE to later)))
        assertEquals(1392, newer.read().knownRelease?.versionCode)
    }

    @Test
    fun `a second request joins the running check`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson()).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val controller = controller()
        val first = async { controller.awaitCheck() }
        val second = async { controller.awaitCheck() }
        assertEquals(first.await(), second.await())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `auto check runs when due and on, skips within a day or when off`() = runBlocking<Unit> {
        val controller = controller()
        answer(200, releaseJson())
        controller.autoCheckIfDue()!!.join()
        val firstAt = now.get()
        assertEquals(firstAt, store.read().lastAttemptMs)

        now.addAndGet(AUTO_CHECK_INTERVAL_MS - 1)
        controller.autoCheckIfDue()!!.join()
        controller.setAutoCheck(false)
        now.addAndGet(AUTO_CHECK_INTERVAL_MS)
        controller.autoCheckIfDue()!!.join()
        assertEquals(1, server.requestCount)
        assertEquals(firstAt, store.read().lastAttemptMs)

        controller.setAutoCheck(true)
        answer(200, releaseJson())
        controller.autoCheckIfDue()!!.join()
        assertEquals(2, server.requestCount)
        assertEquals(now.get(), store.read().lastAttemptMs)
    }

    @Test
    fun `a build without the check never asks GitHub`() = runBlocking<Unit> {
        val controller = controller(supported = false)
        assertNull(controller.awaitCheck())
        assertNull(controller.autoCheckIfDue())
        controller.checkNow()
        assertFalse(controller.setAutoCheck(false))
        assertNull(controller.downloadAndInstall())
        assertEquals(0, server.requestCount)
        assertFalse(controller.state.value.supported)
    }

    @Test
    fun `download and install checks first, then downloads that answer's file and hands it to the installer`() =
        runBlocking<Unit> {
            downloadable()
            serveApk()
            val controller = controller()
            controller.downloadAndInstall()!!.join()

            assertEquals("/repos/Arshad-Kamal/Nuvio-AK/releases/latest", server.takeRequest().path)
            assertEquals(
                "/Arshad-Kamal/Nuvio-AK/releases/download/v/NuvioAK-0.9.0-ak51-1392-armeabi-v7a.apk",
                server.takeRequest().path
            )
            assertEquals(listOf("update-1392-armeabi-v7a.apk"), installed.map { it.name })
            assertArrayEquals(apk, installed.single().readBytes())
            // Running from the press until the end, so only the final state has both.
            controller.settled { it.lastOkCheckMs == now.get() && it.download == DownloadState.Idle }
        }

    @Test
    fun `a fresh answer with nothing newer, or no release, ends without a download`() = runBlocking<Unit> {
        downloadable(code = 1391)
        answer(404)
        val controller = controller(installedCode = 1391)

        controller.downloadAndInstall()!!.join()
        controller.settled { it.lastOkCheckMs == now.get() && !it.lastCheckFailed && it.download == DownloadState.Idle }
        controller.downloadAndInstall()!!.join()
        assertEquals(2, server.requestCount)
        assertTrue(installed.isEmpty())
        assertNull(controller.settled { it.lastCheckFailed && it.download == DownloadState.Idle }.available)
    }

    @Test
    fun `a fresh check without an answer fails the download before it starts`() = runBlocking<Unit> {
        answer(403)
        val controller = controller()
        controller.downloadAndInstall()!!.join()
        assertEquals(1, server.requestCount)
        controller.downloadState { it == DownloadState.Failed }
    }

    @Test
    fun `a file that does not match its digest, or is another build, fails and drops the known release`() =
        runBlocking<Unit> {
            for (digestMatches in listOf(false, true)) {
                archiveIdentity = if (digestMatches) ApkIdentity("com.nuvio.tv.ak", 1391) else ApkIdentity("com.nuvio.tv.ak", 1392)
                downloadable(sha256 = if (digestMatches) apkSha256 else "cd".repeat(32))
                serveApk()
                val controller = controller()
                controller.downloadAndInstall()!!.join()

                assertTrue(installed.isEmpty())
                assertNull(store.read().knownRelease)
                assertNull(controller.settled { it.download == DownloadState.Failed }.available)
                assertEquals(emptyList<String>(), updatesDir.list().orEmpty().toList())
            }
        }

    @Test
    fun `forgetting a release keeps a different one a later check stored`() = runBlocking<Unit> {
        answer(200, releaseJson(code = 1393))
        controller().awaitCheck()
        val stale = store.read().knownRelease!!.copy(versionCode = 1392)
        assertEquals(1393, store.forgetRelease(stale).knownRelease?.versionCode)
    }

    @Test
    fun `no installer answering fails the action`() = runBlocking<Unit> {
        install = { file -> installed += file; false }
        downloadable()
        serveApk()
        val controller = controller()
        controller.downloadAndInstall()!!.join()
        assertEquals(1, installed.size)
        controller.downloadState { it == DownloadState.Failed }
    }

    @Test
    fun `a handoff that throws fails the action and never reaches the scope`() = runBlocking<Unit> {
        install = { throw IllegalArgumentException("not a FileProvider path") }
        downloadable()
        serveApk()
        val controller = controller()
        val job = controller.downloadAndInstall()!!
        job.join()
        assertFalse(job.isCancelled)
        controller.downloadState { it == DownloadState.Failed }
        answer(200, releaseJson())
        assertTrue(controller.awaitCheck() is CheckOutcome.Answered)
    }

    @Test
    fun `a second press joins the running download, cancel stops it and leaves no file`() = runBlocking<Unit> {
        downloadable()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)).throttleBody(16_384, 200, TimeUnit.MILLISECONDS))
        val controller = controller()
        val first = controller.downloadAndInstall()
        assertSame(first, controller.downloadAndInstall())
        controller.downloadState { ((it as? DownloadState.Running)?.bytes ?: 0L) > 0L }

        controller.cancelDownload()
        first!!.join()
        controller.downloadState { it == DownloadState.Idle }
        assertEquals(2, server.requestCount)
        assertTrue(installed.isEmpty())
        assertEquals(emptyList<String>(), updatesDir.list().orEmpty().toList())
    }

    @Test
    fun `a press right after a cancel waits for the cancelled action's deletes`() = runBlocking<Unit> {
        downloadable()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)).throttleBody(16_384, 200, TimeUnit.MILLISECONDS))
        val controller = controller()
        val first = controller.downloadAndInstall()!!
        controller.downloadState { ((it as? DownloadState.Running)?.bytes ?: 0L) > 0L }

        downloadable()
        serveApk()
        controller.cancelDownload()
        val second = controller.downloadAndInstall()!!
        assertNotSame(first, second)
        first.join()
        second.join()
        // Same fixed file name: a late delete from the cancelled action would have removed it.
        assertArrayEquals(apk, installed.single().readBytes())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `closing the panel after a failure clears it, so the panel opens fresh`() = runBlocking<Unit> {
        answer(403)
        val controller = controller()
        controller.downloadAndInstall()!!.join()
        controller.downloadState { it == DownloadState.Failed }

        controller.cancelDownload()
        controller.downloadState { it == DownloadState.Idle }
    }

    @Test
    fun `the activity stopping under the installer leaves the handed-over file alone`() = runBlocking<Unit> {
        downloadable()
        serveApk()
        val controller = controller()
        controller.downloadAndInstall()!!.join()
        controller.cancelDownload()
        assertTrue(installed.single().exists())
        controller.downloadState { it == DownloadState.Idle }
    }

    @Test
    fun `app start empties the updates folder`() = runBlocking<Unit> {
        updatesDir.mkdirs()
        File(updatesDir, "update-1392-armeabi-v7a.apk").writeText("left by a past run")
        controller()
        withTimeout(5_000) { while (updatesDir.list().orEmpty().isNotEmpty()) delay(10) }
    }
}
