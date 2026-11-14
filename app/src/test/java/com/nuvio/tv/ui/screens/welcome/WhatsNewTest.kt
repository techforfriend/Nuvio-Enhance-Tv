package com.nuvio.tv.ui.screens.welcome

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.update.ChangelogRepository
import com.nuvio.tv.core.update.SeenBuild
import com.nuvio.tv.core.update.UpdateStore
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/** Spec 80 S5 (D1, D18): when a start offers the What's new card, what it records, and the rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val installed = SeenBuild(1411, "1.0.0")
    private val store = UpdateStore(TestPreferencesDataStore())
    private var calls = 0
    private var answer: Response<List<GitHubReleaseDto>> = Response.success(emptyList())

    private val api = object : GitHubReleaseApi {
        override suspend fun latestRelease(owner: String, repo: String) = error("not used")
        override suspend fun releases(owner: String, repo: String, perPage: Int): Response<List<GitHubReleaseDto>> {
            calls++
            return answer
        }
    }

    private fun viewModel(updatedSinceInstall: Boolean = true) =
        WhatsNewViewModel(store, ChangelogRepository(api, "o", "r"), installed, { updatedSinceInstall })

    private fun release(code: Int, body: String) = GitHubReleaseDto(
        name = "Nuvio KE $code",
        body = body,
        assets = listOf(GitHubAssetDto(name = "NuvioKE-1.0.0-$code-armeabi-v7a.apk"))
    )

    private val whatsNewBody = "## What's new\n- **Live TV**: Xtream playlists list channels only\n- Profiles\n\n## Fixes\n- a fix"

    @Test
    fun `a fresh install records this build and shows nothing`() = runTest(main.dispatcher) {
        val vm = viewModel(updatedSinceInstall = false)
        vm.start()
        advanceUntilIdle()
        assertNull(vm.card.value)
        assertEquals(0, calls)
        assertEquals(installed, store.seenBuild())
    }

    @Test
    fun `the same build again does nothing`() = runTest(main.dispatcher) {
        store.setSeenBuild(installed)
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        assertNull(vm.card.value)
        assertEquals(0, calls)
    }

    @Test
    fun `an update to a published release with What's new shows the card, recorded only once shown`() =
        runTest(main.dispatcher) {
            store.setSeenBuild(SeenBuild(1392, "0.9.0-ke52"))
            answer = Response.success(listOf(release(1412, whatsNewBody), release(1411, whatsNewBody)))
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            val card = vm.card.value!!
            assertEquals(1411, card.release.versionCode)
            assertEquals("0.9.0-ke52", card.previousName)
            assertEquals(
                listOf(WhatsNewRow("Live TV", "Xtream playlists list channels only"), WhatsNewRow("Profiles", "")),
                card.rows
            )
            assertEquals(1392, store.seenBuild()?.versionCode)
            vm.shown()
            advanceUntilIdle()
            assertEquals(installed, store.seenBuild())
        }

    @Test
    fun `an update from a build that recorded nothing still looks it up, with no previous name`() =
        runTest(main.dispatcher) {
            answer = Response.success(listOf(release(1411, whatsNewBody)))
            val vm = viewModel(updatedSinceInstall = true)
            vm.start()
            advanceUntilIdle()
            assertNull(vm.card.value!!.previousName)
        }

    @Test
    fun `a build not on GitHub, or a release without What's new, records and shows nothing`() =
        runTest(main.dispatcher) {
            store.setSeenBuild(SeenBuild(1392, "0.9.0-ke52"))
            answer = Response.success(listOf(release(1410, whatsNewBody)))
            viewModel().apply { start() }.also { advanceUntilIdle() }.let { assertNull(it.card.value) }
            assertEquals(installed, store.seenBuild())

            store.setSeenBuild(SeenBuild(1392, "0.9.0-ke52"))
            answer = Response.success(listOf(release(1411, "## Fixes\n- a fix")))
            viewModel().apply { start() }.also { advanceUntilIdle() }.let { assertNull(it.card.value) }
            assertEquals(installed, store.seenBuild())
        }

    @Test
    fun `a failed lookup records nothing, so the next start tries again`() = runTest(main.dispatcher) {
        store.setSeenBuild(SeenBuild(1392, "0.9.0-ke52"))
        answer = Response.error(500, "".toResponseBody())
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        assertNull(vm.card.value)
        assertEquals(1392, store.seenBuild()?.versionCode)
    }

    @Test
    fun `whatsNewStart decides by the recorded build`() {
        assertEquals(WhatsNewStart.Record, whatsNewStart(null, 5, updatedSinceInstall = false))
        assertEquals(WhatsNewStart.Lookup(null), whatsNewStart(null, 5, updatedSinceInstall = true))
        assertEquals(WhatsNewStart.Same, whatsNewStart(SeenBuild(5, "a"), 5, updatedSinceInstall = true))
        assertEquals(WhatsNewStart.Lookup("a"), whatsNewStart(SeenBuild(4, "a"), 5, updatedSinceInstall = false))
        assertEquals(WhatsNewStart.Lookup(null), whatsNewStart(SeenBuild(4, ""), 5, updatedSinceInstall = false))
        // A downgrade (adb install -d) records, no card for the older build.
        assertEquals(WhatsNewStart.Record, whatsNewStart(SeenBuild(6, "b"), 5, updatedSinceInstall = true))
    }

    @Test
    fun `rows are plain text, split at the first colon`() {
        assertEquals(
            listOf(
                WhatsNewRow("HDR subtitles", "Their own style: dimmer"),
                WhatsNewRow("See docs", ""),
                WhatsNewRow("Nested", "")
            ),
            whatsNewRows("* **HDR subtitles**: Their own style: dimmer\n\n- [See docs](https://x.y)\n  - Nested\n")
        )
    }
}
