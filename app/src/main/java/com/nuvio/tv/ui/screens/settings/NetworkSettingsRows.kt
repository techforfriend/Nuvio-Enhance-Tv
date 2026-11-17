package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.network.dns.PrivateDnsProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Spec 97 S6 stage 1: the Network section of Advanced › General, between Navigation and Diagnostics. */
internal fun LazyListScope.networkItems(network: NetworkSettingsViewModel) {
    item(key = "network_section") {
        SettingsSectionLabel(text = stringResource(R.string.advanced_section_network))
    }
    item(key = "access_headers") {
        val sites by network.sites.collectAsStateWithLifecycle()
        SettingsValueRow(
            title = stringResource(R.string.access_headers_title),
            subtitle = stringResource(R.string.access_headers_subtitle),
            value = accessSitesValue(sites),
            onClick = network::openPanel
        )
    }
    item(key = "private_dns") {
        val provider by network.dnsProvider.collectAsStateWithLifecycle()
        PrivateDnsRow(provider = provider, onSelect = network::setDnsProvider)
    }
}

/** The row value: None / N sites / Can't read; none until the first read. */
@Composable
private fun accessSitesValue(sites: AccessSitesView): String? = when (sites) {
    AccessSitesView.Loading -> null
    AccessSitesView.Unreadable -> stringResource(R.string.access_headers_cant_read)
    is AccessSitesView.Sites -> if (sites.sites.isEmpty()) {
        stringResource(R.string.access_headers_none)
    } else {
        pluralStringResource(R.plurals.access_headers_sites, sites.sites.size, sites.sites.size)
    }
}

/**
 * The Access headers panel while open, hosted by the pane (outside the lazy list, so scrolling
 * never disposes it). Home (ON_STOP) or leaving the pane closes it, and with it the phone page.
 */
@Composable
internal fun NetworkSettingsPanels(network: NetworkSettingsViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { network.closePanel() }
    DisposableEffect(network) {
        onDispose { network.closePanel() }
    }
    val open by network.panelOpen.collectAsStateWithLifecycle()
    if (!open) return
    val sites by network.sites.collectAsStateWithLifecycle()
    val confirm by network.confirm.collectAsStateWithLifecycle()
    val phoneUrl by network.phoneUrl.collectAsStateWithLifecycle()
    AccessHeadersSidePanel(
        sites = sites,
        confirm = confirm,
        phoneUrl = phoneUrl,
        onAskRemoveSite = network::askRemoveSite,
        onAskRemoveAll = network::askRemoveAll,
        onConfirmRemove = network::confirmRemove,
        onCancelConfirm = network::cancelConfirm,
        onDismiss = network::closePanel
    )
}

/** X5 stages 1, 6 and 7: the row shows the provider; OK opens the picker with its note. */
@Composable
private fun PrivateDnsRow(provider: PrivateDnsProvider?, onSelect: (PrivateDnsProvider) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val options = PrivateDnsProvider.entries.map { entry ->
        SettingsPickerOption(
            value = entry,
            title = privateDnsLabel(entry),
            description = entry.url?.toHttpUrlOrNull()?.host ?: stringResource(R.string.private_dns_off_sub)
        )
    }
    SettingsValueRow(
        title = stringResource(R.string.private_dns_title),
        subtitle = stringResource(R.string.private_dns_subtitle),
        value = provider?.let { privateDnsLabel(it) },
        onClick = { if (provider != null) showPicker = true }
    )
    if (showPicker && provider != null) {
        SettingsPickerPanel(
            title = stringResource(R.string.private_dns_title),
            options = options,
            selectedValue = provider,
            onSelect = onSelect,
            onDismiss = { showPicker = false },
            footnote = stringResource(R.string.private_dns_note)
        )
    }
}

@Composable
private fun privateDnsLabel(provider: PrivateDnsProvider): String = stringResource(
    when (provider) {
        PrivateDnsProvider.OFF -> R.string.private_dns_off
        PrivateDnsProvider.CLOUDFLARE -> R.string.private_dns_cloudflare
        PrivateDnsProvider.GOOGLE -> R.string.private_dns_google
        PrivateDnsProvider.QUAD9 -> R.string.private_dns_quad9
    }
)
