package com.nuvio.tv.core.deeplink

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.tls.AddonCertPinKeeper
import com.nuvio.tv.core.network.tls.addonServerLabel
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class DeepLinkHandler @Inject constructor(
    private val addonRepository: AddonRepository,
    private val certPinKeeper: AddonCertPinKeeper,
    @param:ApplicationContext private val context: Context
) {
    /**
     * Spec 68 P8/P9: first forgets certificates no addon list uses; never offers Trust here (a link
     * must not put trust one OK press away, D6): a certificate failure points to Settings › Addons.
     */
    suspend fun installAddon(manifestUrl: String): DeepLinkInstallResult {
        certPinKeeper.prune()
        return when (val result = addonRepository.fetchAddon(manifestUrl)) {
            is NetworkResult.Success -> {
                addonRepository.addAddon(manifestUrl)
                val addonName = result.data.displayName.ifBlank { result.data.baseUrl }
                DeepLinkInstallResult.Success(
                    context.getString(R.string.addon_install_success, addonName)
                )
            }
            is NetworkResult.Error -> DeepLinkInstallResult.Error(
                if (result.certificate) {
                    // Host only: the address carries the addon's keys.
                    context.getString(R.string.addon_install_link_certificate, addonServerLabel(manifestUrl).orEmpty())
                } else {
                    result.message
                }
            )
            NetworkResult.Loading -> DeepLinkInstallResult.Error(context.getString(R.string.addon_error_invalid_url))
        }
    }
}

sealed interface DeepLinkInstallResult {
    val message: String

    data class Success(
        override val message: String
    ) : DeepLinkInstallResult

    data class Error(
        override val message: String
    ) : DeepLinkInstallResult
}
