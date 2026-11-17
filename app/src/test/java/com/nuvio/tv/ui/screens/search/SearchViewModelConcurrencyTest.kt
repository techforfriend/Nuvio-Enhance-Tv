package com.nuvio.tv.ui.screens.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.isPlaceholderItemId
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelConcurrencyTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `older search outer job cannot leave newer multi-word search loading`() = runTest {
        val firstAddonLookup = CompletableDeferred<Unit>()
        val addon = searchableAddon()
        val addonRepository = GatedAddonRepository(addon, firstAddonLookup)
        val catalogRepository = ImmediateCatalogRepository(addon)
        val viewModel = newViewModel(addonRepository, catalogRepository)

        // This is the Fire TV keyboard/voice sequence: the field receives the text and the
        // submitted query immediately follows it.
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()

        // Q1 is suspended in the addon lookup; before the fix, the outer coroutine owning this
        // lookup was untracked and could resume after Q2.
        viewModel.onEvent(SearchEvent.QueryChanged("Deep Cover"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()
        advanceUntilIdle()

        // Q2 has completed and is no longer loading before the stale Q1 outer coroutine resumes.
        assertEquals("Deep Cover", viewModel.uiState.value.submittedQuery)
        assertFalse(viewModel.uiState.value.isSearching)
        assertTrue(catalogRepository.queries.contains("Deep Cover"))

        // Completing the stale Q1 lookup must not revive work for the old generation.
        firstAddonLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals("Deep Cover", viewModel.uiState.value.submittedQuery)
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(listOf("Deep Cover"), catalogRepository.queries.distinct())
    }

    @Test
    fun `moving from text input to live-search results remembers the query`() = runTest {
        val addon = searchableAddon()
        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = ImmediateCatalogRepository(addon),
            history = history
        )

        viewModel.onEvent(SearchEvent.QueryChanged("Deep Cover"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
        advanceUntilIdle()

        coVerify(exactly = 1) { history.saveRecentSearch("Deep Cover", 8) }
    }

    @Test
    fun `late load-more page from a superseded query never merges`() = runTest {
        val pageGate = CompletableDeferred<Unit>()
        val addon = searchableAddon()
        val catalogRepository = PagedCatalogRepository(addon, pageGate)
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = catalogRepository
        )

        viewModel.onEvent(SearchEvent.QueryChanged("deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onEvent(
            SearchEvent.LoadMoreCatalog(catalogId = "top", addonId = "addon", type = "movie")
        )
        advanceUntilIdle()

        // Page 2 for "deep" is parked at the gate with the row flagged loading.
        assertEquals(listOf(100), catalogRepository.gatedSkips)
        assertTrue(viewModel.uiState.value.catalogRows.single().isLoading)

        // A different query supersedes it and rebuilds the rows.
        viewModel.onEvent(SearchEvent.QueryChanged("cover"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        pageGate.complete(Unit)
        advanceUntilIdle()

        val row = viewModel.uiState.value.catalogRows.single()
        assertEquals(listOf("cover-p1"), row.items.map { it.id })
        assertFalse(row.isLoading)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `re-submitting the same query heals a load-more row parked mid-flight`() = runTest {
        val pageGate = CompletableDeferred<Unit>()
        val addon = searchableAddon()
        val catalogRepository = PagedCatalogRepository(addon, pageGate)
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = catalogRepository
        )

        viewModel.onEvent(SearchEvent.QueryChanged("deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        viewModel.onEvent(
            SearchEvent.LoadMoreCatalog(catalogId = "top", addonId = "addon", type = "movie")
        )
        advanceUntilIdle()
        assertEquals(listOf(100), catalogRepository.gatedSkips)
        assertTrue(viewModel.uiState.value.catalogRows.single().isLoading)

        // Re-submitting the SAME query takes the already-satisfied early return,
        // which bumps the generation but leaves the accumulator (and the flagged
        // row) in place — exactly the path healLoadingFlag exists for.
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        pageGate.complete(Unit)
        advanceUntilIdle()

        val healed = viewModel.uiState.value.catalogRows.single()
        assertFalse(healed.isLoading)
        // The superseded page never merged.
        assertEquals(listOf("deep-p1"), healed.items.map { it.id })
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `query nav arg prefills and runs the search`() = runTest {
        val addon = searchableAddon()
        val catalogRepository = ImmediateCatalogRepository(addon)
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = catalogRepository,
            // Already decoded by Navigation; a literal '+' must survive untouched.
            savedStateHandle = SavedStateHandle(mapOf(Screen.Search.QUERY_ARG to "  Kelly+Reilly "))
        )
        advanceUntilIdle()

        assertEquals("Kelly+Reilly", viewModel.uiState.value.submittedQuery)
        assertEquals("Kelly+Reilly", viewModel.uiState.value.query)
        assertEquals(listOf("Kelly+Reilly"), catalogRepository.queries)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `live search sends one request per catalog and derives suggestions from the first addon`() = runTest {
        val catalogRepository = NamedResultsCatalogRepository(
            namesByAddon = mapOf(
                "first" to listOf("Zeta Deep", "deep blue", "Abyss"),
                "second" to listOf("Deep Second")
            )
        )
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(
                listOf(
                    searchableAddon(id = "first", baseUrl = "https://first.test"),
                    searchableAddon(id = "second", baseUrl = "https://second.test")
                )
            ),
            catalogRepository = catalogRepository
        )

        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        // One request per addon catalog: no separate suggestion fetch for the same URL.
        assertEquals(2, catalogRepository.requests.size)
        assertEquals(setOf("first" to "Deep", "second" to "Deep"), catalogRepository.requests.toSet())
        // First addon only; query-prefix matches first, otherwise the addon's order (T31, P41).
        assertEquals(listOf("deep blue", "Zeta Deep", "Abyss"), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `suggestions clear when the query drops below the minimum length`() = runTest {
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(listOf(searchableAddon())),
            catalogRepository = NamedResultsCatalogRepository(
                namesByAddon = mapOf("addon" to listOf("Deep Cover"))
            )
        )
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()
        assertEquals(listOf("Deep Cover"), viewModel.uiState.value.suggestions)

        viewModel.onEvent(SearchEvent.QueryChanged("D"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `no query nav arg leaves the root search idle`() = runTest {
        val addon = searchableAddon()
        val catalogRepository = ImmediateCatalogRepository(addon)
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = catalogRepository
        )
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.query)
        assertTrue(catalogRepository.queries.isEmpty())
    }

    // -- Spec 36/S7 (B4-4/B4-6) --

    @Test
    fun `a catalog that fails for the new query drops the previous query's row`() = runTest {
        val catalogRepository = ScriptedCatalogRepository { addonId, query ->
            if (addonId == "second" && query == "Cover") NetworkResult.Error("down") else null
        }
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(
                listOf(
                    searchableAddon(id = "first", baseUrl = "https://first.test"),
                    searchableAddon(id = "second", baseUrl = "https://second.test")
                )
            ),
            catalogRepository = catalogRepository
        )
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()
        assertEquals(setOf("first:Deep", "second:Deep"), itemIds(viewModel))

        viewModel.onEvent(SearchEvent.QueryChanged("Cover"))
        advanceUntilIdle()

        assertEquals(setOf("first:Cover"), itemIds(viewModel))
    }

    @Test
    fun `a catalog listed twice is searched once`() = runTest {
        val addon = searchableAddon().let { it.copy(catalogs = it.catalogs + it.catalogs) }
        val catalogRepository = ImmediateCatalogRepository(addon.copy(catalogs = addon.catalogs.take(1)))
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(listOf(addon)),
            catalogRepository = catalogRepository
        )

        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        assertEquals(listOf("Deep"), catalogRepository.queries)
        assertEquals(1, viewModel.uiState.value.catalogRows.size)
    }

    // -- Spec 95 M3 --

    @Test
    fun `a result row's first page puts exact then prefix title matches first`() = runTest {
        fun item(name: String) = MetaPreview(
            id = name, type = ContentType.MOVIE, name = name, poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        )
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(listOf(searchableAddon(id = "first", baseUrl = "https://first.test"))),
            catalogRepository = ScriptedCatalogRepository { addonId, query ->
                NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId, addonName = "First", addonBaseUrl = "https://first.test",
                        catalogId = "top", catalogName = "Top", type = ContentType.MOVIE,
                        items = listOf("Dune Fan Edit", "Children of Dune", "Dune: Part Two", "dune ").map(::item)
                    )
                ).takeIf { query == "Dune" }
            }
        )

        viewModel.onEvent(SearchEvent.QueryChanged("Dune"))
        advanceUntilIdle()

        assertEquals(
            listOf("dune ", "Dune Fan Edit", "Dune: Part Two", "Children of Dune"),
            viewModel.uiState.value.catalogRows.single().items.map { it.name }
        )
    }

    private fun twoAddons() = ListAddonRepository(
        listOf(
            searchableAddon(id = "first", baseUrl = "https://first.test"),
            searchableAddon(id = "second", baseUrl = "https://second.test")
        )
    )

    @Test
    fun `while a search runs an unanswered catalog keeps its old row and a failed one drops it`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val viewModel = newViewModel(
            addonRepository = twoAddons(),
            catalogRepository = ScriptedCatalogRepository { addonId, query ->
                when {
                    query != "Cover" -> null
                    addonId == "first" -> { firstGate.await(); null }
                    else -> NetworkResult.Error("down")
                }
            }
        )
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged("Cover"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSearching)
        assertEquals(setOf("first:Deep"), itemIds(viewModel))

        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("first:Cover"), itemIds(viewModel))
    }

    @Test
    fun `first rows render at once and later answers wait at most one throttle window`() = runTest {
        val secondGate = CompletableDeferred<Unit>()
        // A third catalog stays out so the run does not settle (settling renders at once).
        val thirdGate = CompletableDeferred<Unit>()
        val viewModel = newViewModel(
            addonRepository = ListAddonRepository(
                listOf(
                    searchableAddon(id = "first", baseUrl = "https://first.test"),
                    searchableAddon(id = "second", baseUrl = "https://second.test"),
                    searchableAddon(id = "third", baseUrl = "https://third.test")
                )
            ),
            catalogRepository = ScriptedCatalogRepository { addonId, _ ->
                if (addonId == "second") secondGate.await()
                if (addonId == "third") thirdGate.await()
                null
            }
        )
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()
        assertEquals(setOf("first:Deep"), itemIds(viewModel))

        secondGate.complete(Unit)
        runCurrent()
        assertEquals(setOf("first:Deep"), itemIds(viewModel))
        advanceTimeBy(149)
        runCurrent()
        assertEquals(setOf("first:Deep"), itemIds(viewModel))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(setOf("first:Deep", "second:Deep"), itemIds(viewModel))

        thirdGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("first:Deep", "second:Deep", "third:Deep"), itemIds(viewModel))
    }

    // -- Spec 40: search on Enter --

    private fun onEnterSetup(
        gate: CompletableDeferred<Unit>? = null
    ): Triple<ListAddonRepository, MutableList<Pair<String, String>>, ScriptedCatalogRepository> {
        val repo = ListAddonRepository(
            listOf(
                searchableAddon(id = "ai", baseUrl = "https://ai.test"),
                searchableAddon(id = "meta", baseUrl = "https://meta.test")
            ),
            // Stored canonical, matched case-insensitively.
            onEnterUrls = MutableStateFlow(setOf("https://AI.test"))
        )
        val requests = mutableListOf<Pair<String, String>>()
        val catalogs = ScriptedCatalogRepository { addonId, query ->
            requests += addonId to query
            if (addonId == "meta") gate?.await()
            null
        }
        return Triple(repo, requests, catalogs)
    }

    @Test
    fun `live search leaves out on-Enter addons and lists them as waiting`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)

        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        assertEquals(listOf("meta" to "Deep"), requests)
        assertEquals(setOf("meta:Deep"), itemIds(viewModel))
        assertEquals(listOf(SearchOnEnterWait("Top", "Addon")), viewModel.uiState.value.searchOnEnterWaits)
    }

    @Test
    fun `submit asks on-Enter addons in manifest order and clears the waiting lines`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(listOf("meta" to "Deep", "ai" to "Deep", "meta" to "Deep"), requests)
        assertEquals(listOf("ai", "meta"), viewModel.uiState.value.catalogRows.map { it.addonId })
        assertTrue(viewModel.uiState.value.searchOnEnterWaits.isEmpty())

        // Editing the query goes back to live: the on-Enter addon waits again.
        viewModel.onEvent(SearchEvent.QueryChanged("Deeper"))
        advanceUntilIdle()
        assertEquals("meta" to "Deeper", requests.last())
        assertEquals(1, requests.count { it.second == "Deeper" })
        assertEquals(1, viewModel.uiState.value.searchOnEnterWaits.size)
    }

    @Test
    fun `submit during a live run of the same query restarts it with on-Enter addons`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (repo, requests, catalogs) = onEnterSetup(gate)
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceTimeBy(400)
        runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.onEvent(SearchEvent.SubmitSearch)
        gate.complete(Unit)
        advanceUntilIdle()

        // The on-Enter addon is asked exactly once (the cancelled live run never asked it).
        assertEquals(1, requests.count { it == "ai" to "Deep" })
        assertEquals(setOf("ai:Deep", "meta:Deep"), itemIds(viewModel))
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `submit with no on-Enter addons reuses the finished live run`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        repo.onEnterUrls.value = emptySet()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(2, requests.size)
        assertTrue(viewModel.uiState.value.searchOnEnterWaits.isEmpty())
    }

    @Test
    fun `submit with no on-Enter addons reuses the active live run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (repo, requests, catalogs) = onEnterSetup(gate)
        repo.onEnterUrls.value = emptySet()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceTimeBy(400)
        runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.onEvent(SearchEvent.SubmitSearch)
        gate.complete(Unit)
        advanceUntilIdle()

        // The live run already asked every addon: no restart, no second request.
        assertEquals(listOf("ai" to "Deep", "meta" to "Deep"), requests)
        assertEquals(setOf("ai:Deep", "meta:Deep"), itemIds(viewModel))
    }

    @Test
    fun `query nav arg and Retry after a live run leave on-Enter addons out`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(
            repo,
            catalogs,
            savedStateHandle = SavedStateHandle(mapOf(Screen.Search.QUERY_ARG to "Deep"))
        )
        advanceUntilIdle()
        assertEquals(listOf("meta" to "Deep"), requests)
        assertEquals(1, viewModel.uiState.value.searchOnEnterWaits.size)

        viewModel.onEvent(SearchEvent.Retry)
        advanceUntilIdle()
        assertEquals(listOf("meta" to "Deep", "meta" to "Deep"), requests)
    }

    @Test
    fun `Retry after a live run whose lookup failed stays live after a submit`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        assertTrue("ai" to "Deep" in requests)

        repo.failLookups = true
        viewModel.onEvent(SearchEvent.QueryChanged("Deeper"))
        advanceUntilIdle()
        assertEquals("store down", viewModel.uiState.value.error)

        repo.failLookups = false
        viewModel.onEvent(SearchEvent.Retry)
        advanceUntilIdle()
        assertEquals(listOf("meta" to "Deeper"), requests.filter { it.second == "Deeper" })
    }

    @Test
    fun `a double submit asks the on-Enter addon once`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(listOf("ai" to "Deep", "meta" to "Deep"), requests)
    }

    @Test
    fun `a satisfied run refreshes the waiting lines when a marked addon is no longer asked`() = runTest {
        val first = searchableAddon(id = "ai", baseUrl = "https://ai.test")
        val second = searchableAddon(id = "meta", baseUrl = "https://meta.test")
        val installed = MutableStateFlow(listOf(first, second))
        val repo = object : AddonRepository by ListAddonRepository(emptyList()) {
            override fun getInstalledAddons(): Flow<List<Addon>> = installed
            override fun getSearchOnSubmitUrls(): Flow<Set<String>> = flowOf(setOf("https://ai.test"))
        }
        val (_, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.searchOnEnterWaits.size)

        // The marked addon leaves; submit asks the same catalogs the live run did.
        installed.value = listOf(second)
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(listOf("meta" to "Deep"), requests)
        assertTrue(viewModel.uiState.value.searchOnEnterWaits.isEmpty())
    }

    @Test
    fun `changing a mark re-keys the next live run`() = runTest {
        val (repo, requests, catalogs) = onEnterSetup()
        val viewModel = newViewModel(repo, catalogs)
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()

        repo.onEnterUrls.value = emptySet()
        viewModel.onEvent(SearchEvent.QueryChanged("Deep "))
        advanceUntilIdle()

        assertEquals(listOf("meta" to "Deep", "ai" to "Deep", "meta" to "Deep"), requests)
        assertTrue(viewModel.uiState.value.searchOnEnterWaits.isEmpty())
    }

    @Test
    fun `only on-Enter search catalogs settle with no error until submit`() = runTest {
        val repo = ListAddonRepository(
            listOf(searchableAddon(id = "ai", baseUrl = "https://ai.test")),
            onEnterUrls = MutableStateFlow(setOf("https://ai.test"))
        )
        val catalogs = ImmediateCatalogRepository(searchableAddon(id = "ai", baseUrl = "https://ai.test"))
        val viewModel = newViewModel(repo, catalogs)

        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        advanceUntilIdle()
        assertTrue(catalogs.queries.isEmpty())
        assertEquals(null, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(1, viewModel.uiState.value.searchOnEnterWaits.size)

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()
        assertEquals(listOf("Deep"), catalogs.queries)

        // Retry repeats the submitted kind of run.
        viewModel.onEvent(SearchEvent.Retry)
        advanceUntilIdle()
        assertEquals(listOf("Deep", "Deep"), catalogs.queries)
    }

    @Test
    fun `waiting lines merge an addon's same-named catalogs`() {
        val addon = searchableAddon()
        val movie = addon.catalogs.single()
        val series = movie.copy(type = ContentType.SERIES, id = "top-series")
        val other = movie.copy(id = "ai", name = "ai picks")
        val untitled = movie.copy(id = "untitled", name = " ")
        val waits = searchOnEnterWaits(listOf(addon to movie, addon to series, addon to other, addon to untitled))
        assertEquals(
            listOf(SearchOnEnterWait("Top", "Addon"), SearchOnEnterWait("Ai picks", "Addon"), SearchOnEnterWait("", "Addon")),
            waits
        )
        // A title-less catalog's line is titled by its addon.
        assertEquals("Addon", waits.last().title)
    }

    private fun itemIds(viewModel: SearchViewModel): Set<String> =
        viewModel.uiState.value.catalogRows
            .flatMap { row -> row.items.map { it.id } }
            .filterNot { it.isPlaceholderItemId() }
            .toSet()

    /** Answers `addonId:query` rows unless [override] returns a result for that request. */
    private class ScriptedCatalogRepository(
        private val override: suspend (addonId: String, query: String) -> NetworkResult<CatalogRow>?
    ) : CatalogRepository {
        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs.getValue("search")
            emit(NetworkResult.Loading)
            emit(
                override(addonId, query) ?: NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.MOVIE,
                        items = listOf(
                            MetaPreview(
                                id = "$addonId:$query",
                                type = ContentType.MOVIE,
                                name = query,
                                poster = null,
                                posterShape = PosterShape.POSTER,
                                background = null,
                                logo = null,
                                description = null,
                                releaseInfo = null,
                                imdbRating = null,
                                genres = emptyList()
                            )
                        )
                    )
                )
            )
        }

        override suspend fun clearCaches() = Unit
    }

    private fun newViewModel(
        addonRepository: AddonRepository,
        catalogRepository: CatalogRepository,
        history: SearchHistoryDataStore = mockk(relaxed = true),
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(com.nuvio.tv.domain.model.DiscoverLocation.OFF)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.modernLandscapePostersEnabled } returns flowOf(false)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaPrefetcher = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            savedStateHandle = savedStateHandle
        )
    }

    private class GatedAddonRepository(
        private val addon: Addon,
        private val firstLookupGate: CompletableDeferred<Unit>
    ) : AddonRepository {
        private val lookups = AtomicInteger()

        override fun getInstalledAddons(): Flow<List<Addon>> = flow {
            if (lookups.getAndIncrement() == 0) {
                firstLookupGate.await()
            }
            emit(listOf(addon))
        }

        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>) = error("unused")
        override suspend fun moveAddon(url: String, direction: Int) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private class ListAddonRepository(
        private val addons: List<Addon>,
        val onEnterUrls: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    ) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(addons)
        /** Set to make the next mark lookups throw (an addon-store failure). */
        var failLookups = false
        override fun getSearchOnSubmitUrls(): Flow<Set<String>> =
            if (failLookups) flow { error("store down") } else onEnterUrls
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun spliceAddonOrder(urls: List<String>, removedUrls: Collection<String>) = error("unused")
        override suspend fun moveAddon(url: String, direction: Int) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    /** Serves a fixed list of item names per addon and records each (addonId, query) request. */
    private class NamedResultsCatalogRepository(
        private val namesByAddon: Map<String, List<String>>
    ) : CatalogRepository {
        val requests = mutableListOf<Pair<String, String>>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            requests += addonId to extraArgs.getValue("search")
            emit(NetworkResult.Loading)
            emit(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.MOVIE,
                        items = namesByAddon.getValue(addonId).map { name ->
                            MetaPreview(
                                id = "$addonId:$name",
                                type = ContentType.MOVIE,
                                name = name,
                                poster = null,
                                posterShape = PosterShape.POSTER,
                                background = null,
                                logo = null,
                                description = null,
                                releaseInfo = null,
                                imdbRating = null,
                                genres = emptyList()
                            )
                        }
                    )
                )
            )
        }

        override suspend fun clearCaches() = Unit
    }

    private class ImmediateCatalogRepository(
        private val addon: Addon
    ) : CatalogRepository {
        val queries = mutableListOf<String>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs.getValue("search")
            queries += query
            emit(NetworkResult.Loading)
            emit(NetworkResult.Success(row(addon, query)))
        }

        override suspend fun clearCaches() = Unit

        private fun row(addon: Addon, query: String): CatalogRow = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = addon.catalogs.single().id,
            catalogName = addon.catalogs.single().name,
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = query,
                    type = ContentType.MOVIE,
                    name = query,
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            )
        )
    }

    /**
     * Serves page 1 immediately and parks every follow-up page (skip > 0) on a
     * gate, so a LoadMore can be left mid-flight across later events.
     */
    private class PagedCatalogRepository(
        private val addon: Addon,
        private val pageGate: CompletableDeferred<Unit>
    ) : CatalogRepository {
        val gatedSkips = mutableListOf<Int>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs.getValue("search")
            if (skip == 0) {
                emit(NetworkResult.Loading)
                emit(NetworkResult.Success(page(query, page = 1)))
            } else {
                gatedSkips += skip
                pageGate.await()
                emit(NetworkResult.Success(page(query, page = 2)))
            }
        }

        override suspend fun clearCaches() = Unit

        private fun page(query: String, page: Int): CatalogRow = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = addon.catalogs.single().id,
            catalogName = addon.catalogs.single().name,
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "$query-p$page",
                    type = ContentType.MOVIE,
                    name = "$query-p$page",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            ),
            hasMore = page == 1,
            currentPage = page - 1,
            supportsSkip = false,
            skipStep = 100
        )
    }

    private fun searchableAddon(
        id: String = "addon",
        baseUrl: String = "https://example.test"
    ): Addon {
        val catalog = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "top",
            name = "Top",
            extra = listOf(CatalogExtra(name = "search"))
        )
        return Addon(
            id = id,
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = listOf(catalog),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )
    }
}
