package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T5 addon durability (ysosrs 7bb4e446f + 0.9.0 relay), ported from upstream's
 * AddonManifestPlaceholderTest: an installed addon whose manifest cannot be
 * fetched must still be emitted, so it stays visible in the addon manager and
 * can be removed. Before the placeholder fallback it resolved to null and was
 * dropped by filterNotNull(), leaving the URL installed but unreachable from
 * the UI.
 *
 * The placeholder tests use runBlocking and Dispatchers.IO, matching production
 * scheduling. The refresh-clock test injects a test dispatcher and a settable
 * clock instead, so the TTL policy is asserted without waiting on real time.
 *
 * Each repository built here leaks its stateIn collector: syncScope is a
 * SupervisorJob the class never cancels, and there is no close/dispose.
 * Harmless for a handful of instances in a unit-test JVM. The 30 s first-step
 * failed-manifest retry ladder likewise never fires inside a test's lifetime.
 */
class AddonManifestPlaceholderTest {

    private val addonUrl = "https://addon.example"

    private companion object {
        const val REAL_NAME = "Test Addon"
        const val REAL_VERSION = "1.0.0"

        /** A placeholder carries no version; a resolved manifest does. */
        const val PLACEHOLDER_VERSION = ""

        /** Mirrors AddonRepositoryImpl.MANIFEST_CACHE_TTL_MS, which is private. */
        const val MANIFEST_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    }

    @Test
    fun `unreachable addon is emitted as a placeholder`() = runBlocking {
        val harness = newRepository()

        val addons = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }

        assertEquals(1, addons.size)
        val addon = addons.single()
        assertEquals(addonUrl, addon.baseUrl)
        assertTrue(addon.enabled)
        // placeholderAddon derives a name from the URL's last path segment, which is
        // what the addon manager renders until a real manifest arrives.
        assertEquals("addon.example", addon.displayName)
        assertEquals(null, addon.logo)
    }

    @Test
    fun `placeholder can be removed`() = runBlocking {
        val harness = newRepository()

        val addon = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()

        // The addon manager drives removal from the emitted row's baseUrl, so this
        // is the property the placeholder exists to preserve: before the fix there
        // was no row to remove from.
        harness.repository.removeAddon(addon.baseUrl)

        coVerify(exactly = 1) { harness.preferences.removeAddon(addonUrl) }
    }

    @Test
    fun `remove, phone-page splice and a changed sync list each prune trusted certificates`() = runBlocking {
        val prunes = AtomicInteger()
        val harness = newRepository(pruneCertPins = { prunes.incrementAndGet() })
        val prefs = harness.preferences
        coEvery { prefs.spliceAddonOrder(any(), any()) } returns true
        coEvery { prefs.getInstalledAddonUrls(1) } returns listOf(addonUrl)
        coEvery { prefs.setAddonOrder(any(), 1) } returns true

        harness.repository.removeAddon(addonUrl)
        assertEquals(1, prunes.get())
        harness.repository.spliceAddonOrder(listOf(addonUrl), emptyList())
        assertEquals(2, prunes.get())
        harness.repository.reconcileWithRemoteAddonUrls(listOf(addonUrl), profileId = 1) // unchanged: no prune
        assertEquals(2, prunes.get())
        harness.repository.reconcileWithRemoteAddonUrls(listOf("https://other.example"), profileId = 1)
        assertEquals(3, prunes.get())
        coEvery { prefs.removeAddon(any()) } returns false // nothing removed: no prune
        harness.repository.removeAddon(addonUrl)
        assertEquals(3, prunes.get())
    }

    /**
     * A failed manifest fetch is retried whenever the flow recomputes, because the
     * placeholder is never written to the cache, so the addon stays a cache miss.
     * That is deliberate: the TTL throttle only governs manifests already cached,
     * so an addon added while offline can recover without waiting out a six-hour
     * window. Pinned so it is not mistaken for a retry storm and optimised away.
     */
    @Test
    fun `failed manifest fetch is retried when the flow recomputes`() = runBlocking {
        val userSetNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val harness = newRepository(userSetNames = userSetNames)

        withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }
        val before = harness.manifestCalls.get()
        assertTrue(before >= 1)

        // Renaming the addon recomputes installedAddonsFlow. Comparing the call
        // count across the mutation ties the extra fetch to the recomputation,
        // rather than merely asserting that two fetches happened at some point.
        userSetNames.value = mapOf(addonUrl to "Renamed")
        withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.singleOrNull()?.displayName == "Renamed" }
        }

        assertTrue(harness.manifestCalls.get() > before)
    }

    @Test
    fun `placeholder carries no resources or catalogs`() = runBlocking {
        val harness = newRepository()

        val addon = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()

        // The placeholder has no resources and no catalogs, which supplies the
        // inputs StreamRepositoryImpl uses to exclude it from stream and catalog
        // processing. That predicate is private to StreamRepositoryImpl, so this
        // asserts its inputs rather than the exclusion itself.
        assertTrue(addon.resources.isEmpty())
        assertTrue(addon.catalogs.isEmpty())
    }

    /**
     * The placeholder must be a temporary stand-in, not a sticky one: once the
     * manifest becomes reachable, the real addon has to replace it. The
     * placeholder is never written to the cache, so the next recomputation
     * refetches and the resolved manifest wins.
     */
    @Test
    fun `placeholder is replaced once the manifest becomes available`() = runBlocking {
        val userSetNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val harness = newRepository(userSetNames = userSetNames)

        val placeholder = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()
        assertEquals(PLACEHOLDER_VERSION, placeholder.version)

        harness.reachable.set(true)
        // Names a different key, so the recomputation is triggered without renaming
        // this addon.
        userSetNames.value = mapOf("https://other.example" to "Other")

        val resolved = withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.singleOrNull()?.version == REAL_VERSION }
        }.single()
        assertEquals(REAL_NAME, resolved.name)
        assertEquals(addonUrl, resolved.baseUrl)
    }

    /**
     * The bug (0.9.0 relay): the refresh timestamp was only advanced when a fetch
     * succeeded, so a sweep that failed for every addon left isCacheStale() true and
     * the next recomputation of installedAddonsFlow scheduled another full sweep.
     * Any addon rename, enable or disable while offline re-armed it indefinitely.
     *
     * Deterministic because the dispatcher and the clock are injected: the
     * unconfined test dispatcher runs the disk load, the flow and the sweep to
     * completion at their launch points, so a manifest-call count sampled after an
     * emission is stable.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an all-failed sweep does not re-arm on the next recomputation`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val now = AtomicLong(0L)
        val names = MutableStateFlow(emptyMap<String, String>())
        val harness = newRepository(
            userSetNames = names,
            reachable = true,
            dispatcher = dispatcher,
            clock = { now.get() }
        )

        // Resolve the manifest once so it is cached. While it is not, the sole URL is a
        // miss, so the stale check has no cached manifest to refresh and records no attempt.
        harness.repository.getInstalledAddons().first { list ->
            list.singleOrNull()?.version == REAL_VERSION
        }
        val afterCaching = harness.manifestCalls.get()

        // Server goes away, clock moves past the TTL. The cache is now stale with no
        // cache miss, so the next recomputation schedules a sweep, and every fetch
        // in it fails.
        harness.reachable.set(false)
        now.set(MANIFEST_CACHE_TTL_MS + 1)
        names.value = mapOf(addonUrl to "renamed once")
        val afterFailedSweep = harness.manifestCalls.get()
        assertEquals(
            "the stale cache should have produced exactly one sweep",
            afterCaching + 1,
            afterFailedSweep
        )

        // The sweep failed, but it was still an attempt. Further recomputations must
        // not re-arm it.
        names.value = mapOf(addonUrl to "renamed twice")
        names.value = mapOf(addonUrl to "renamed three times")
        assertEquals(
            "a failed sweep must not re-arm on later recomputations",
            afterFailedSweep,
            harness.manifestCalls.get()
        )

        // Once the TTL has elapsed again the sweep is eligible, which is what makes
        // the assertion above a throttle rather than a permanent stop.
        now.set(now.get() + MANIFEST_CACHE_TTL_MS + 1)
        names.value = mapOf(addonUrl to "renamed four times")
        assertEquals(
            "a new TTL window should allow one further sweep",
            afterFailedSweep + 1,
            harness.manifestCalls.get()
        )
    }

    /**
     * Wave C S4 C4-5: with several cold misses each successful fetch bumps the manifest-cache
     * revision, flatMapLatest restarts the evaluation (cancelling sibling fetches) and the
     * restarted pass re-fetches what is still missing. Pinned: the list still converges on every
     * real manifest, in install (URL) order.
     */
    @Test
    fun `several cold misses converge on every real manifest in url order`() = runBlocking {
        val urls = listOf("https://one.example", "https://two.example", "https://three.example")
        val harness = newRepository(reachable = true, urls = urls)

        val addons = withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.size == urls.size && list.all { it.version == REAL_VERSION } }
        }

        assertEquals(urls, addons.map { it.baseUrl })
    }

    /**
     * C4-5: the post-fetch emit is what publishes a round in which a fetch failed. The success
     * reaches the list through the revision restart, and the failure only as the placeholder of
     * that fresh list, so the list ends with both.
     */
    @Test
    fun `mixed success and failure ends with the real manifest and a placeholder`() = runBlocking {
        val urls = listOf("https://good.example", "https://down.example")
        val harness = newRepository(reachable = true, urls = urls, failingHosts = setOf("down.example"))

        val addons = withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.size == 2 && list.any { it.version == REAL_VERSION } }
        }

        assertEquals(urls, addons.map { it.baseUrl })
        assertEquals(listOf(REAL_VERSION, PLACEHOLDER_VERSION), addons.map { it.version })
    }

    /**
     * Spec 21 C4-R1: an enabled addon whose manifest never loads keeps every pass on the
     * cache-miss branch. The TTL refresh used to run only on a pass without a miss, so the other
     * cached manifests were never refreshed again. Now the stale pass refreshes the cached ones,
     * and not the miss, which that pass has just fetched. The refreshed manifest changes (new
     * version), so its cache bump restarts the pass: the miss is fetched once more, and the
     * attempt stamped at the refresh's start keeps the restarted pass from refreshing again.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a permanent manifest miss does not block the ttl refresh of cached manifests`() = runTest {
        val now = AtomicLong(0L)
        val names = MutableStateFlow(emptyMap<String, String>())
        val urls = listOf("https://good.example", "https://down.example")
        val harness = newRepository(
            userSetNames = names,
            reachable = true,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            clock = { now.get() },
            urls = urls,
            failingHosts = setOf("down.example"),
            manifestVersion = { if (now.get() > MANIFEST_CACHE_TTL_MS) "2.0.0" else REAL_VERSION }
        )
        harness.repository.getInstalledAddons().first { list ->
            list.size == 2 && list.first().version == REAL_VERSION
        }
        val goodBefore = harness.callsTo("good.example")
        val downBefore = harness.callsTo("down.example")

        now.set(MANIFEST_CACHE_TTL_MS + 1)
        names.value = mapOf("https://good.example" to "recompute")

        assertEquals("2.0.0", harness.repository.getInstalledAddons().first().first().version)
        assertEquals("the stale pass refreshes the cached manifest once", goodBefore + 1, harness.callsTo("good.example"))
        assertEquals(
            "the miss branch, then the pass restarted by the refreshed manifest",
            downBefore + 2,
            harness.callsTo("down.example")
        )

        // Same TTL window, another recompute on a pass with a miss: the throttle holds.
        names.value = mapOf("https://good.example" to "again")
        assertEquals(goodBefore + 1, harness.callsTo("good.example"))
    }

    /**
     * L5-68 / FR-22: a move pushes the addon list to the account only when the stored order
     * actually changed (AddonPreferences.moveAddon returned true).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `moveAddon pushes to remote only when the stored order changed`() = runTest {
        val harness = newRepository(
            reachable = true,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            authenticated = true
        )
        coEvery { harness.preferences.moveAddon(addonUrl, -1) } returns false
        coEvery { harness.preferences.moveAddon(addonUrl, 1) } returns true

        harness.repository.moveAddon(addonUrl, -1)
        advanceUntilIdle()
        coVerify(exactly = 0) { harness.addonSyncService.pushToRemote(any()) }

        harness.repository.moveAddon(addonUrl, 1)
        advanceUntilIdle()
        coVerify(exactly = 1) { harness.addonSyncService.pushToRemote(any()) }
    }

    private fun newContext(): Context = mockk(relaxed = true)

    private data class Harness(
        val repository: AddonRepositoryImpl,
        val preferences: AddonPreferences,
        val addonSyncService: AddonSyncService,
        val manifestCalls: AtomicInteger,
        val reachable: AtomicBoolean,
        val callsByHost: ConcurrentHashMap<String, AtomicInteger>
    ) {
        fun callsTo(host: String): Int = callsByHost[host]?.get() ?: 0
    }

    private fun newRepository(
        userSetNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = flowOf(emptyMap()),
        reachable: Boolean = false,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
        authenticated: Boolean = false,
        urls: List<String> = listOf(addonUrl),
        failingHosts: Set<String> = emptySet(),
        manifestVersion: () -> String = { REAL_VERSION },
        pruneCertPins: suspend () -> Unit = {}
    ): Harness {
        val manifestCalls = AtomicInteger()
        val callsByHost = ConcurrentHashMap<String, AtomicInteger>()
        val isReachable = AtomicBoolean(reachable)
        val api = mockk<AddonApi>()
        coEvery { api.getManifest(any()) } coAnswers {
            manifestCalls.incrementAndGet()
            val manifestUrl = firstArg<String>()
            callsByHost.getOrPut(java.net.URI(manifestUrl).host) { AtomicInteger() }.incrementAndGet()
            if (!isReachable.get() || failingHosts.any { it in manifestUrl }) throw IOException("offline")
            Response.success(
                AddonManifestDto(id = "test-addon", name = REAL_NAME, version = manifestVersion())
            )
        }

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns flowOf(urls)
        every { preferences.userSetNames } returns userSetNames
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())
        coEvery { preferences.removeAddon(any()) } returns true

        val addonSyncService = mockk<AddonSyncService>(relaxed = true)
        coEvery { addonSyncService.pushToRemote(any()) } returns Result.success(Unit)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns authenticated

        return Harness(
            repository = AddonRepositoryImpl(
                api = api,
                preferences = preferences,
                addonSyncService = addonSyncService,
                authManager = authManager,
                context = newContext(),
                dispatcher = dispatcher,
                clock = clock,
                pruneCertPins = pruneCertPins
            ),
            preferences = preferences,
            addonSyncService = addonSyncService,
            manifestCalls = manifestCalls,
            reachable = isReachable,
            callsByHost = callsByHost
        )
    }
}
