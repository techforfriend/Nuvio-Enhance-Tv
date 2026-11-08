package com.nuvio.tv.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec 52 P8: the APK download over MockWebServer into a temp folder, with the archive reader
 * faked: what reaches the installer, and that every other end leaves no file behind.
 */
class ApkDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val bytes = ByteArray(200_000) { (it * 31).toByte() }
    private var archive: ApkIdentity? = ApkIdentity("com.nuvio.tv.ak", 1392)

    private val dir by lazy { File(tmp.root, UPDATES_DIR) }
    private val downloader by lazy {
        ApkDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), dir, "com.nuvio.tv.ak") { archive }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(sizeBytes: Long = bytes.size.toLong(), sha256: String = bytes.toByteString().sha256().hex()) =
        KnownRelease(
            versionName = "0.9.0-ak51",
            versionCode = 1392,
            abi = "armeabi-v7a",
            downloadUrl = server.url("/releases/download/v/NuvioAK-0.9.0-ak51-1392-armeabi-v7a.apk").toString(),
            sizeBytes = sizeBytes,
            sha256 = sha256,
            notes = ""
        )

    private fun serve(body: ByteArray = bytes, chunked: Boolean = false) = server.enqueue(
        MockResponse().apply { if (chunked) setChunkedBody(Buffer().write(body), 8_192) else setBody(Buffer().write(body)) }
    )

    private fun assertNoFiles() = assertEquals(emptyList<String>(), dir.list().orEmpty().toList())

    @Test
    fun `a whole file with the promised digest and our next build's identity is handed out under a fixed name`() =
        runBlocking<Unit> {
            serve()
            val progress = AtomicLong()
            val result = downloader.download(release()) { progress.set(it) }

            val apk = (result as DownloadResult.Verified).apk
            assertEquals("update-1392-armeabi-v7a.apk", apk.name)
            assertArrayEquals(bytes, apk.readBytes())
            assertEquals(listOf(apk.name), dir.list()!!.toList())
            assertEquals(bytes.size.toLong(), progress.get())
            assertEquals("/releases/download/v/NuvioAK-0.9.0-ak51-1392-armeabi-v7a.apk", server.takeRequest().path)
        }

    @Test
    fun `a digest other than the promised one is rejected and deleted`() = runBlocking<Unit> {
        serve()
        assertEquals(DownloadResult.Rejected, downloader.download(release(sha256 = "ab".repeat(32))) {})
        assertNoFiles()
    }

    @Test
    fun `more bytes than reported stop the download, declared or streamed`() = runBlocking<Unit> {
        serve()
        val declared = AtomicLong()
        assertEquals(DownloadResult.Failed, downloader.download(release(sizeBytes = bytes.size - 1L)) { declared.set(it) })
        assertEquals("stopped on Content-Length, before reading", 0L, declared.get())
        assertNoFiles()

        serve(chunked = true)
        val progress = AtomicLong()
        assertEquals(DownloadResult.Failed, downloader.download(release(sizeBytes = bytes.size - 1L)) { progress.set(it) })
        assertTrue(progress.get() < bytes.size)
        assertNoFiles()
    }

    @Test
    fun `fewer bytes than reported, or an HTTP error, leave no file`() = runBlocking<Unit> {
        serve(chunked = true)
        assertEquals(DownloadResult.Failed, downloader.download(release(sizeBytes = bytes.size + 1L)) {})
        assertNoFiles()

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(DownloadResult.Failed, downloader.download(release()) {})
        assertNoFiles()
    }

    @Test
    fun `a connection dropped mid-body fails and leaves no file`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertEquals(DownloadResult.Failed, downloader.download(release()) {})
        assertNoFiles()
    }

    @Test
    fun `the download client does not follow a redirect that changes the scheme`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:1/x.apk"))
        val redirectsOnSameScheme = ApkDownloader(
            apkDownloadClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build()), dir, "com.nuvio.tv.ak"
        ) { archive }
        assertEquals(DownloadResult.Failed, redirectsOnSameScheme.download(release()) {})
        assertEquals(1, server.requestCount)
        assertNoFiles()
    }

    @Test
    fun `an archive of another app, another version or none at all is rejected and deleted`() = runBlocking<Unit> {
        for (identity in listOf(ApkIdentity("com.other.app", 1392), ApkIdentity("com.nuvio.tv.ak", 1391), null)) {
            archive = identity
            serve()
            assertEquals(DownloadResult.Rejected, downloader.download(release()) {})
            assertNoFiles()
        }
    }

    @Test
    fun `a cancelled download deletes the partial file`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(16_384, 200, TimeUnit.MILLISECONDS))
        val progress = AtomicLong()
        val running = async { downloader.download(release()) { progress.set(it) } }
        withTimeout(5_000) { while (progress.get() == 0L) delay(10) }
        assertTrue(File(dir, "update-1392-armeabi-v7a.apk.part").exists())

        running.cancel()
        try {
            running.await()
            fail("expected the download to be cancelled")
        } catch (_: CancellationException) {
        }
        assertNoFiles()
    }

    @Test
    fun `files a past download left are cleared before the next one and by clear`() = runBlocking<Unit> {
        dir.mkdirs()
        File(dir, "update-1391-armeabi-v7a.apk").writeText("old")
        serve()
        downloader.download(release()) {}
        assertEquals(listOf("update-1392-armeabi-v7a.apk"), dir.list()!!.toList())

        downloader.clear()
        assertNoFiles()
    }

    @Test
    fun `the download client is the validated one without a cache, a call timeout or a scheme-changing redirect`() {
        val validated = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder("http"), 1_024))
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { it.proceed(it.request()) }
            .build()
        val client = apkDownloadClient(validated)

        assertNull(client.cache)
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertFalse(client.followSslRedirects)
        assertTrue(client.followRedirects)
        assertEquals(validated.interceptors, client.interceptors)
        assertEquals(validated.connectionPool, client.connectionPool)
    }
}
