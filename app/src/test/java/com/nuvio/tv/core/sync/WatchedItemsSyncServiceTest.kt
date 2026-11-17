package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TestPreferencesDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.WatchedArtworkHydrator
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * L9-01: a push for a captured profile must read THAT profile's watched items, not the
 * active profile's (startup pull racing the profile picker).
 * L9-10/L9-32 + hybrid ruling: stamps are per profile; incremental stamps wait for a full push
 * after any failed or skipped push.
 */
class WatchedItemsSyncServiceTest {
    private val activeProfileId = MutableStateFlow(1)
    private val stores = mutableMapOf<Int, TestPreferencesDataStore>()
    private val rpcCalls = mutableListOf<Pair<String, JsonObject>>()
    @Volatile private var rpcFails = false
    @Volatile private var rpcCancels = false
    // Non-null: rpc returns a real result with this body, so pulls can decode and succeed.
    @Volatile private var rpcBody: String? = null

    private val preferences: WatchedItemsPreferences
    private val service: WatchedItemsSyncService
    private val hydrator = mockk<WatchedArtworkHydrator>(relaxed = true)

    init {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) { stores.getOrPut(firstArg()) { TestPreferencesDataStore() } }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        preferences = WatchedItemsPreferences(factory, profileManager)

        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any<String>(), any<JsonObject>(), any()) } answers {
            if (rpcFails) throw IllegalStateException("rpc failed")
            if (rpcCancels) throw CancellationException("scope cancelled")
            synchronized(rpcCalls) { rpcCalls += firstArg<String>() to secondArg<JsonObject>() }
            rpcBody?.let { PostgrestResult(it, Headers.Empty, postgrest) } ?: mockk<PostgrestResult>(relaxed = true)
        }
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-test-client-id"
        service = WatchedItemsSyncService(
            authManager = mockk<AuthManager>(relaxed = true),
            postgrest = postgrest,
            watchedItemsPreferences = preferences,
            profileManager = profileManager,
            syncClientIdentity = identity,
            pushHealth = SyncPushHealth(),
            artworkHydrator = hydrator
        )
    }

    @Test
    fun `full push sends the captured profile's items while another profile is active`() = runTest {
        preferences.markAsWatched(item("active-only"), profileId = 1)
        preferences.markAsWatched(item("captured-1"), profileId = 2)
        preferences.markAsWatched(item("captured-2"), profileId = 2)
        activeProfileId.value = 1

        val result = service.pushToRemote(profileId = 2)

        assertTrue(result.isSuccess)
        val (function, params) = rpcCalls.single()
        assertEquals("sync_push_watched_items", function)
        assertEquals(2, params.getValue("p_profile_id").jsonPrimitive.int)
        val pushedIds = params.getValue("p_items").jsonArray
            .map { it.jsonObject.getValue("content_id").jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("captured-1", "captured-2"), pushedIds)
    }

    @Test
    fun `full push stamps only the pushed profile's watermark with its pre-read time`() = runTest {
        preferences.markAsWatched(item("captured"), profileId = 2)
        val beforePush = System.currentTimeMillis()

        service.pushToRemote(profileId = 2).getOrThrow()

        val stamped = preferences.getLastSuccessfulPushMs(profileId = 2)
        assertTrue(stamped in beforePush..System.currentTimeMillis())
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 1))
    }

    @Test
    fun `a failed push blocks later batch stamps until a full push confirms`() = runTest {
        rpcFails = true
        preferences.markAsWatched(item("unpushed"), profileId = 2)
        assertTrue(service.pushItemsToRemote(listOf(item("unpushed")), profileId = 2).isFailure)
        assertTrue(service.pushToRemote(profileId = 2).isFailure)
        rpcFails = false

        service.pushItemsToRemote(listOf(item("batch")), profileId = 2).getOrThrow()
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 2))

        service.pushToRemote(profileId = 2).getOrThrow()
        val afterFull = preferences.getLastSuccessfulPushMs(profileId = 2)
        assertTrue(afterFull > 0L)

        Thread.sleep(2)
        service.pushItemsToRemote(listOf(item("next")), profileId = 2).getOrThrow()
        assertTrue(preferences.getLastSuccessfulPushMs(profileId = 2) > afterFull)
        assertEquals(0L, preferences.getLastSuccessfulPushMs(profileId = 1))
    }

    @Test
    fun `a cancelled push rethrows cancellation and leaves the profile unconfirmed`() = runTest {
        service.pushToRemote(profileId = 2).getOrThrow()
        val afterFull = preferences.getLastSuccessfulPushMs(profileId = 2)
        rpcCancels = true

        try {
            service.pushItemsToRemote(listOf(item("cancelled")), profileId = 2)
            fail("cancellation became a Result")
        } catch (_: CancellationException) {
            // B5: the service's catch (e: Exception) must not see it.
        }
        rpcCancels = false

        Thread.sleep(2)
        service.pushItemsToRemote(listOf(item("next")), profileId = 2).getOrThrow()
        assertEquals(afterFull, preferences.getLastSuccessfulPushMs(profileId = 2))
    }

    @Test
    fun `snapshot apply with a never-stamped watermark keeps local-only items`() = runTest {
        preferences.markAsWatched(item("local-only", watchedAt = 50L), profileId = 2)

        val preserved = preferences.replaceWithRemoteItems(
            remoteItems = listOf(item("remote")),
            lastSuccessfulPushMs = 0L,
            profileId = 2
        )

        assertTrue(preserved)
        assertEquals(
            setOf("local-only", "remote"),
            preferences.getAllItems(profileId = 2).map { it.contentId }.toSet()
        )
    }

    @Test
    fun `pushes never carry the locally hydrated art`() = runTest {
        preferences.markAsWatched(item("art").copy(poster = "p", backdrop = "b"), profileId = 2)

        service.pushToRemote(profileId = 2).getOrThrow()

        val pushed = rpcCalls.single().second.getValue("p_items").jsonArray.single().jsonObject
        assertEquals(setOf("content_id", "content_type", "title", "season", "episode", "watched_at"), pushed.keys)
    }

    @Test
    fun `a failed pull requests no artwork hydration`() = runTest {
        rpcFails = true

        assertTrue(service.syncDeltaFromRemote(profileId = 2).isFailure)
        assertTrue(service.syncSnapshotFromRemote(profileId = 2).isFailure)

        verify(exactly = 0) { hydrator.request(any()) }
        assertFalse(preferences.isDeltaInitialized(profileId = 2))
    }

    @Test
    fun `a successful snapshot or delta pull requests hydration for its profile`() = runTest {
        // "[]": an empty remote page; the cursor rpc cannot decode it, so both take the snapshot path.
        rpcBody = "[]"

        assertTrue(service.syncSnapshotFromRemote(profileId = 2).isSuccess)
        assertTrue(service.syncDeltaFromRemote(profileId = 3).isSuccess)

        verify(exactly = 1) { hydrator.request(2) }
        verify(exactly = 1) { hydrator.request(3) }
    }

    private fun item(contentId: String, watchedAt: Long = 100L) = WatchedItem(
        contentId = contentId,
        contentType = "movie",
        title = contentId,
        season = null,
        episode = null,
        watchedAt = watchedAt
    )
}
