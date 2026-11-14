package com.nuvio.tv.core.network.tls

import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.addonGuardKey
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spec 68 X7: the user pressed Trust on a probed certificate. Stores exactly the probed
 * fingerprint for its `host:port` (a different certificate on the real call stays refused),
 * resets that server's breaker and certificate flag, then re-fetches the manifest of every
 * installed addon on it (P4, P6).
 */
@Singleton
class AddonCertTrustAction internal constructor(
    private val keeper: AddonCertPinKeeper,
    private val guard: AddonHostGuard,
    private val installedAddonUrls: suspend () -> List<String>,
    private val fetchAddon: suspend (String) -> Unit,
    private val nowMs: () -> Long
) {
    @Inject
    internal constructor(
        keeper: AddonCertPinKeeper,
        guard: AddonHostGuard,
        preferences: AddonPreferences,
        addonRepository: AddonRepository
    ) : this(
        keeper = keeper,
        guard = guard,
        installedAddonUrls = { preferences.installedAddonUrls.first() },
        fetchAddon = { url -> addonRepository.fetchAddon(url) },
        nowMs = System::currentTimeMillis
    )

    suspend fun trust(probed: AddonCertProbeResult.Untrusted) {
        // The user pressed Trust: storing it and resetting the server finish even if the screen goes away.
        withContext(NonCancellable) {
            keeper.trust(AddonCertPin(probed.host, probed.port, probed.sha256, nowMs()))
            guard.onTrusted(addonGuardKey(probed.host, probed.port))
        }
        val host = pinHost(probed.host)
        installedAddonUrls()
            .filter { url -> addonServerOf(url)?.let { pinHost(it.host) == host && it.port == probed.port } == true }
            .forEach { url -> fetchAddon(url) }
    }
}
