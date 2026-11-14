package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.core.network.tls.AddonCertProbeResult

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installedAddons: List<Addon> = emptyList(),
    val healthByUrl: Map<String, AddonHealthLevel> = emptyMap(),
    /** Spec 40: `addonUrlMatchKey`s of the addons searched only on Enter (this profile, this TV). */
    val searchOnEnterKeys: Set<String> = emptySet(),
    val error: String? = null,
    val transientMessage: String? = null,
    val transientMessageIsError: Boolean = false,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null,
    /** Spec 68 X7 P6: `host:port` keys whose last addon call failed on its certificate (row: CERTIFICATE, PAUSED). */
    val certificateFailureKeys: Set<String> = emptySet(),
    /** The certificate panel on screen (sandbox `s068-x7-tls-confirm` stages 1, 4, 5), or null. */
    val certificatePanel: AddonCertificatePanelState? = null
)

/**
 * A probed certificate waiting for the user's Trust or Cancel. [addonName] / [addonUrl]: the
 * installed addon under review (stage 4's "stays paused" line; focus returns to its row); both null
 * on Install (focus returns to the Install button). [changed]: this host:port had another
 * certificate pinned (stage 4).
 */
data class AddonCertificatePanelState(
    val probed: AddonCertProbeResult.Untrusted,
    val addonName: String?,
    val addonUrl: String? = null
) {
    val changed: Boolean get() = probed.previousSha256 != null
}

data class PendingChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val catalogsReordered: Boolean = false,
    val disabledCatalogNames: List<String> = emptyList(),
    val enabledCatalogNames: List<String> = emptyList(),
    val addedNames: Map<String, String> = emptyMap(),
    val removedNames: Map<String, String> = emptyMap(),
    val collectionsChanged: Boolean = false,
    val proposedCollectionsJson: String? = null,
    val proposedCollectionCount: Int = 0,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    val isApplying: Boolean = false
)
