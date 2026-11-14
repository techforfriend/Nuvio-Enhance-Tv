package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.update.ChangelogRepository
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/** Spec 80 S3: the list's load states, reload and close, and the page it remembers. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangelogViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Each releases() call takes the next answer; an unset one suspends until completed. */
    private val answers = ArrayDeque<CompletableDeferred<Response<List<GitHubReleaseDto>>>>()

    private val api = object : GitHubReleaseApi {
        override suspend fun latestRelease(owner: String, repo: String) = error("not used")
        override suspend fun releases(owner: String, repo: String, perPage: Int) = answers.removeFirst().await()
    }

    private val viewModel = ChangelogViewModel(ChangelogRepository(api, "o", "r"))

    private fun ok(vararg names: String) = Response.success(names.map { GitHubReleaseDto(name = it) })
    private fun fail(): Response<List<GitHubReleaseDto>> = Response.error(500, "".toResponseBody())
    private fun next() = CompletableDeferred<Response<List<GitHubReleaseDto>>>().also(answers::addLast)

    @Test
    fun `opening loads afresh and shows Loading first`() = runTest(main.dispatcher) {
        val first = next()
        viewModel.openList()
        assertTrue(viewModel.listOpen.value)
        assertEquals(ChangelogListState.Loading, viewModel.list.value)
        first.complete(ok("Nuvio KE 1.0.0"))
        advanceUntilIdle()
        val loaded = viewModel.list.value as ChangelogListState.Loaded
        assertEquals(listOf("Nuvio KE 1.0.0"), loaded.releases.map { it.title })

        // D4: fetched each time the list opens.
        viewModel.closeList()
        next().complete(ok("Nuvio KE 1.0.1", "Nuvio KE 1.0.0"))
        viewModel.openList()
        assertEquals(ChangelogListState.Loading, viewModel.list.value)
        advanceUntilIdle()
        assertEquals(2, (viewModel.list.value as ChangelogListState.Loaded).releases.size)
    }

    @Test
    fun `a failure offers Try again, which loads again`() = runTest(main.dispatcher) {
        next().complete(fail())
        viewModel.openList()
        advanceUntilIdle()
        assertEquals(ChangelogListState.Failed, viewModel.list.value)
        next().complete(ok("Nuvio KE 1.0.0"))
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.list.value is ChangelogListState.Loaded)
    }

    @Test
    fun `closing cancels a running load, a stale answer never lands`() = runTest(main.dispatcher) {
        val slow = next()
        viewModel.openList()
        advanceUntilIdle()
        viewModel.closeList()
        assertFalse(viewModel.listOpen.value)
        slow.complete(ok("stale"))
        advanceUntilIdle()
        assertEquals(ChangelogListState.Loading, viewModel.list.value)
    }

    private fun withApk(code: Int) = GitHubReleaseDto(
        name = "Nuvio KE $code",
        assets = listOf(GitHubAssetDto(name = "NuvioKE-1.0.0-$code-armeabi-v7a.apk"))
    )

    @Test
    fun `Read notes opens its release's page once the list has it, else stays on the list`() = runTest(main.dispatcher) {
        next().complete(Response.success(listOf(withApk(1412), withApk(1411))))
        viewModel.openList(openRelease = 1411)
        advanceUntilIdle()
        assertEquals(1411, viewModel.page.value?.versionCode)
        assertEquals(1411, viewModel.lastOpened?.versionCode)
        assertFalse(viewModel.pageForUpdate.value)

        viewModel.closePage()
        viewModel.closeList()
        next().complete(Response.success(listOf(withApk(1412))))
        viewModel.openList(openRelease = 1411)
        advanceUntilIdle()
        assertNull(viewModel.page.value)
        assertTrue(viewModel.listOpen.value)

        // A failed load stays on the list; its Try again opens no page.
        viewModel.closeList()
        next().complete(fail())
        viewModel.openList(openRelease = 1411)
        advanceUntilIdle()
        next().complete(Response.success(listOf(withApk(1411))))
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.page.value)
    }

    @Test
    fun `the page remembers its release until the list closes`() = runTest(main.dispatcher) {
        next().complete(ok("Nuvio KE 1.0.0"))
        viewModel.openList()
        advanceUntilIdle()
        val release = (viewModel.list.value as ChangelogListState.Loaded).releases.single()
        viewModel.openPage(release)
        assertEquals(release, viewModel.page.value)
        viewModel.closePage()
        assertNull(viewModel.page.value)
        assertEquals(release, viewModel.lastOpened)
        viewModel.closeList()
        assertNull(viewModel.lastOpened)
    }
}
