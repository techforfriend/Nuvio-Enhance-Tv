package com.nuvio.tv.core.network.tls

import android.util.Log
import com.nuvio.tv.core.di.Api
import com.nuvio.tv.core.di.Download
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonCertPinKeeper"

/**
 * Spec 68 X7: every change to the trusted addon certificates goes through here, under one mutex:
 * Trust (D9, [trust]), the forget rule (P8, [prune]) and sign-out ([clear]). Whenever a pin goes,
 * both addon clients' idle pooled connections are closed (S1-d1), so a connection that pin vouched
 * for is not reused; a connection busy at that moment stays until its call ends (OkHttp's evictAll).
 */
@Singleton
class AddonCertPinKeeper internal constructor(
    private val store: AddonCertPinStore,
    private val profileIds: suspend () -> List<Int>,
    /** The addon list stored in that profile's own store (no usesPrimaryAddons mapping). */
    private val storedAddonUrls: suspend (Int) -> List<String>,
    private val evictConnections: () -> Unit,
    private val io: CoroutineDispatcher
) {
    @Inject
    internal constructor(
        store: AddonCertPinStore,
        profileDataStore: ProfileDataStore,
        addonPreferences: AddonPreferences,
        @Api apiClient: dagger.Lazy<OkHttpClient>,
        @Download downloadClient: dagger.Lazy<OkHttpClient>
    ) : this(
        store = store,
        // ProfileDataStore, never ProfileManager.profiles.value (its initial value is a placeholder profile 1).
        profileIds = { profileDataStore.profilesList.first().map { it.id } },
        storedAddonUrls = addonPreferences::getStoredAddonUrls,
        evictConnections = {
            apiClient.get().connectionPool.evictAll()
            downloadClient.get().connectionPool.evictAll()
        },
        io = Dispatchers.IO
    )

    private val mutex = Mutex()

    /** Hosts with an install attempt in flight, counted (two installs may share a host). */
    private val installing = ConcurrentHashMap<String, Int>()

    /** Stores the certificate the user trusted ([pin]) by D9's rule. */
    internal suspend fun trust(pin: AddonCertPin) {
        mutex.withLock { if (store.put(pin)) evict() }
    }

    /**
     * Runs one install attempt for [addonUrl] with its host exempt from [prune], so a pin the user
     * trusts during the install survives a prune that runs before the addon is in a stored list.
     * Released however the attempt ends.
     */
    suspend fun <T> withInstallExemption(addonUrl: String, block: suspend () -> T): T {
        val host = addonServerOf(addonUrl)?.host?.let(::pinHost) ?: return block()
        installing.merge(host, 1, Int::plus)
        try {
            return block()
        } finally {
            installing.computeIfPresent(host) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    /**
     * P8: drops every pin whose host no profile's stored addon list uses, except hosts with an
     * install in flight. A read that fails drops nothing. Never throws (cancellation aside).
     */
    suspend fun prune() {
        try {
            mutex.withLock {
                // Snapshot first: an install that ends after this point has already written its list.
                val used = HashSet(installing.keys)
                for (profileId in (profileIds() + 1).distinct()) {
                    storedAddonUrls(profileId).mapNotNullTo(used) { url -> addonServerOf(url)?.host?.let(::pinHost) }
                }
                if (store.removeWhere { it.host !in used }) evict()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Prune skipped: ${e.javaClass.simpleName}")
        }
    }

    /** Sign-out: forgets every trusted certificate. */
    suspend fun clear() {
        mutex.withLock {
            store.clear()
            evict()
        }
    }

    private suspend fun evict() = withContext(io) { evictConnections() }
}
