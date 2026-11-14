package com.nuvio.tv.core.update

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Spec 80 S2: the What's new split, the entry and its video, and the list load over the real wire path. */
class ChangelogTest {

    private val apk = GitHubAssetDto(
        name = "NuvioKE-1.0.0-1411-armeabi-v7a.apk",
        state = "uploaded",
        contentType = "application/vnd.android.package-archive",
        size = 41_000_000L,
        digest = "sha256:" + "ab".repeat(32),
        browserDownloadUrl = "https://github.com/Arshad-Kamal/Nuvio-KE/releases/download/v1.0.0/NuvioKE-1.0.0-1411-armeabi-v7a.apk"
    )

    private fun video(
        name: String = "whats-new-1.0.0.mp4",
        contentType: String? = "video/mp4",
        state: String = "uploaded",
        size: Long? = 30_000_000L,
        url: String = "https://github.com/Arshad-Kamal/Nuvio-KE/releases/download/v1.0.0/$name"
    ) = GitHubAssetDto(name = name, state = state, contentType = contentType, size = size, browserDownloadUrl = url)

    private fun release(
        vararg assets: GitHubAssetDto,
        body: String? = "## What's new\n- One\n\n## Fixes\n- Two",
        name: String? = "Nuvio KE 1.0.0",
        tag: String? = "v1.0.0",
        prerelease: Boolean = false,
        draft: Boolean = false
    ) = GitHubReleaseDto(
        name = name, tagName = tag, prerelease = prerelease, draft = draft, body = body,
        publishedAt = "2026-11-14T10:00:00Z", assets = assets.toList()
    )

    @Test
    fun `a leading What's new heading splits off its section`() {
        val parts = splitReleaseBody("\n## What's new\n- Live TV\n- Profiles\n\n## Live TV\n- Xtream\n### Detail\nmore")
        assertEquals("- Live TV\n- Profiles", parts.whatsNew)
        assertEquals("## Live TV\n- Xtream\n### Detail\nmore", parts.notes)
    }

    @Test
    fun `the section keeps its deeper headings and ends at one as high`() {
        val parts = splitReleaseBody("## What’s new in 1.0.0\n### Live TV\n- A\n# Full notes\nB")
        assertEquals("### Live TV\n- A", parts.whatsNew)
        assertEquals("# Full notes\nB", parts.notes)
        assertEquals("- only", splitReleaseBody("# WHATS NEW\n- only").whatsNew)
        assertEquals("", splitReleaseBody("# WHATS NEW\n- only").notes)
    }

    @Test
    fun `CRLF bodies split like LF ones`() {
        val parts = splitReleaseBody("## What's new\r\n- A\r\n\r\n## Fixes\r\n- B\r\n")
        assertEquals(ReleaseBodyParts("- A", "## Fixes\n- B"), parts)
        assertEquals(ReleaseBodyParts("", "Plain\nnotes"), splitReleaseBody("Plain\r\nnotes\r\n"))
    }

    @Test
    fun `a hash line inside a code fence does not end the section`() {
        val parts = splitReleaseBody("## What's new\n- A\n```\n## not a heading\n```\n~~~\n# nor this\n~~~\n## Fixes\n- B")
        assertEquals("- A\n```\n## not a heading\n```\n~~~\n# nor this\n~~~", parts.whatsNew)
        assertEquals("## Fixes\n- B", parts.notes)
    }

    @Test
    fun `an empty What's new section is empty, the rest stays notes`() {
        assertEquals(ReleaseBodyParts("", "## Fixes\n- B"), splitReleaseBody("## What's new\n\n## Fixes\n- B"))
        // Setext headings are not read: such a body is all notes.
        assertEquals("", splitReleaseBody("What's new\n==========\n- A").whatsNew)
    }

    @Test
    fun `a body that does not start with What's new is all notes`() {
        val ke52 = "First public release of Nuvio KE.\n\n**Which APK**\n- armeabi-v7a"
        assertEquals(ReleaseBodyParts("", ke52), splitReleaseBody(ke52))
        val later = "## Fixes\n- A\n## What's new\n- B"
        assertEquals(ReleaseBodyParts("", later), splitReleaseBody(later))
        assertEquals(ReleaseBodyParts("", "What's new\n- not a heading"), splitReleaseBody("What's new\n- not a heading"))
        assertEquals(ReleaseBodyParts("", ""), splitReleaseBody(""))
    }

    @Test
    fun `an entry reads its version from the APK name and its video from the release`() {
        val entry = changelogRelease(release(apk, video()))!!
        assertEquals("Nuvio KE 1.0.0", entry.title)
        assertEquals("1.0.0", entry.versionName)
        assertEquals(1411, entry.versionCode)
        assertEquals("- One", entry.whatsNew)
        assertEquals("## Fixes\n- Two", entry.notes)
        assertEquals("2026-11-14T10:00:00Z", entry.publishedAt)
        assertEquals(ReleaseVideo(video().browserDownloadUrl!!, 30_000_000L), entry.video)
    }

    @Test
    fun `a video counts only as an uploaded https video file within the size cap`() {
        fun videoOf(asset: GitHubAssetDto) = changelogRelease(release(apk, asset))!!.video
        assertEquals("https://x.test/a.MKV", videoOf(video(name = "a.MKV", contentType = "video/x-matroska", url = "https://x.test/a.MKV"))?.url)
        assertEquals(MAX_VIDEO_BYTES, videoOf(video(size = MAX_VIDEO_BYTES))?.sizeBytes)
        assertNull(videoOf(video(size = MAX_VIDEO_BYTES + 1)))
        assertNull(videoOf(video(size = 0L)))
        assertNull(videoOf(video(size = null)))
        assertNull(videoOf(video(state = "open")))
        assertNull(videoOf(video(contentType = "application/octet-stream")))
        assertNull(videoOf(video(contentType = null)))
        assertNull(videoOf(video(name = "notes.txt", contentType = "video/mp4")))
        assertNull(videoOf(video(url = "http://github.com/x/whats-new.mp4")))
        assertNull(changelogRelease(release(apk))!!.video)
    }

    @Test
    fun `a release image asset becomes the video's poster`() {
        val poster = GitHubAssetDto(
            name = "poster.jpg", state = "uploaded", contentType = "image/jpeg", size = 200_000L,
            browserDownloadUrl = "https://github.com/x/poster.jpg"
        )
        assertEquals("https://github.com/x/poster.jpg", changelogRelease(release(apk, poster, video()))!!.video?.posterUrl)
        assertNull(changelogRelease(release(apk, video()))!!.video?.posterUrl)
        assertNull(changelogRelease(release(apk, poster))!!.video)
        fun posterOf(asset: GitHubAssetDto) = changelogRelease(release(apk, asset, video()))!!.video?.posterUrl
        assertNull(posterOf(poster.copy(size = MAX_POSTER_BYTES + 1)))
        assertNull(posterOf(poster.copy(contentType = "application/octet-stream")))
        assertNull(posterOf(poster.copy(name = "poster.gif")))
        assertNull(posterOf(poster.copy(state = "open")))
        assertNull(posterOf(poster.copy(browserDownloadUrl = "http://github.com/x/poster.jpg")))
    }

    @Test
    fun `drafts and pre-releases are skipped, a release without an APK keeps its title`() {
        assertNull(changelogRelease(release(apk, prerelease = true)))
        assertNull(changelogRelease(release(apk, draft = true)))
        val noApk = changelogRelease(release(name = null, tag = "v0.9.0-ke52"))!!
        assertEquals("v0.9.0-ke52", noApk.title)
        assertNull(noApk.versionName)
        assertNull(noApk.versionCode)
        assertEquals("1.0.0", changelogRelease(release(apk, name = " ", tag = null))!!.title)
        assertNull(changelogRelease(release(name = null, tag = null)))
    }

    @Test
    fun `the body is capped before it is split`() {
        val entry = changelogRelease(release(apk, body = "x".repeat(MAX_NOTES_CHARS + 50)))!!
        assertEquals(MAX_NOTES_CHARS, entry.notes.length)
        // The cap counts the whole body, the What's new section included.
        val head = "## What's new\n"
        val split = changelogRelease(release(apk, body = head + "y".repeat(MAX_NOTES_CHARS)))!!
        assertEquals(MAX_NOTES_CHARS - head.length, split.whatsNew.length)
        assertEquals("", split.notes)
    }

    // --- the load, over the real Retrofit + wire-Json path ---

    private val server = MockWebServer().apply { start() }
    private val repository = ChangelogRepository(
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
            .build()
            .create(GitHubReleaseApi::class.java),
        "Arshad-Kamal",
        "Nuvio-KE"
    )

    @After
    fun tearDown() = server.shutdown()

    private fun answer(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `a list answer loads every published release, newest first`() = runBlocking {
        answer(
            200,
            """[
              {"name":"Nuvio KE 1.0.0","tag_name":"v1.0.0","prerelease":false,"draft":false,"body":"## What's new\n- A",
               "published_at":"2026-11-14T10:00:00Z","unknown":1,
               "assets":[{"name":"NuvioKE-1.0.0-1411-armeabi-v7a.apk","state":"uploaded","size":1,"content_type":"application/vnd.android.package-archive",
                 "browser_download_url":"https://github.com/a.apk"}]},
              {"name":"beta","tag_name":"v1.1.0-beta","prerelease":true,"assets":[]},
              {"name":"Nuvio KE 0.9.0-ke52","tag_name":"v0.9.0-ke52","body":"First public release","assets":[]}
            ]"""
        )
        val load = repository.load() as ChangelogLoad.Loaded
        assertEquals(listOf("Nuvio KE 1.0.0", "Nuvio KE 0.9.0-ke52"), load.releases.map { it.title })
        assertEquals(1411, load.releases[0].versionCode)
        assertEquals("- A", load.releases[0].whatsNew)
        val request = server.takeRequest()
        assertEquals("/repos/Arshad-Kamal/Nuvio-KE/releases?per_page=$CHANGELOG_SIZE", request.path)
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun `404 is an empty changelog, other answers and no answer fail`() = runBlocking {
        answer(404, """{"message":"Not Found"}""")
        assertEquals(ChangelogLoad.Loaded(emptyList()), repository.load())
        answer(403, """{"message":"rate limit"}""")
        assertEquals(ChangelogLoad.Failed, repository.load())
        answer(200, "{not json")
        assertEquals(ChangelogLoad.Failed, repository.load())
        answer(200, """{"name":"an object, not a list"}""")
        assertEquals(ChangelogLoad.Failed, repository.load())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(repository.load() is ChangelogLoad.Failed)
    }

    @Test
    fun `a cancelled load is cancelled, not a failure`() {
        val cancelling = object : GitHubReleaseApi {
            override suspend fun latestRelease(owner: String, repo: String) = throw CancellationException("left")
            override suspend fun releases(owner: String, repo: String, perPage: Int) = throw CancellationException("left")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { ChangelogRepository(cancelling, "o", "r").load() }
        }
    }
}
