package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.access.AccessHeaderStore
import com.nuvio.tv.core.network.access.AccessSaveResult
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.core.network.dns.PrivateDnsProvider
import com.nuvio.tv.core.network.dns.PrivateDnsSettings
import com.nuvio.tv.core.server.AccessHeaderServer
import com.nuvio.tv.core.server.AccessHeaderWebPage
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved site as the TV shows it: never a header value (D4). */
data class AccessSiteSummary(val host: String, val port: Int, val headerNames: List<String>) {
    /** The host, with its port when it isn't https's own. */
    val label: String get() = if (port == HTTPS_PORT) host else "$host:$port"

    private companion object {
        const val HTTPS_PORT = 443
    }
}

internal fun AccessSite.summary() = AccessSiteSummary(host, port, headers.map { it.name })

/** The saved sites as the Advanced row and panel show them. */
sealed interface AccessSitesView {
    /** Not read yet: the row shows no value. */
    data object Loading : AccessSitesView

    /** D17: the saved list can't be read now. */
    data object Unreadable : AccessSitesView

    data class Sites(val sites: List<AccessSiteSummary>) : AccessSitesView
}

/** The confirm the Access headers panel shows in its place, or none. */
sealed interface AccessConfirm {
    data class Site(val site: AccessSiteSummary) : AccessConfirm

    /** D23 "Remove saved sites". */
    data object All : AccessConfirm
}

/** A running phone page and its QR address. */
internal class StartedAccessServer(val server: AccessHeaderServer, val url: String)

/** Starts the Access headers phone page on this TV's LAN address; null with no network or no free port. */
class AccessHeaderServerStarter @Inject constructor(@ApplicationContext private val context: Context) {
    internal fun start(save: (AccessSite) -> AccessSaveResult): StartedAccessServer? {
        val ip = DeviceIpAddress.get(context) ?: return null
        val server = AccessHeaderServer.startOnAvailablePort(
            pageHtml = { AccessHeaderWebPage.html(context) },
            save = save
        ) ?: return null
        return StartedAccessServer(server, server.url(ip))
    }
}

/**
 * Spec 97 S6: Advanced › General › Network (sandbox `s097-x4-x5-network` v5). The Access headers
 * row and panel (X4: the saved sites, their removal, the D17 state with D23's "Remove saved sites")
 * and the Private DNS row and picker (X5). The phone page (A5) runs only while the panel is open on
 * a readable list (D15: the TV refuses saves otherwise), at most [phoneLifetime]; a send from it
 * refreshes the panel. While the list can't be read the open panel reads it again every
 * [unreadableRetry] (the store's own retry pace). Main thread, except the phone's saves.
 */
@HiltViewModel
class NetworkSettingsViewModel internal constructor(
    private val store: AccessHeaderStore,
    private val privateDns: PrivateDnsSettings,
    private val startPhone: (save: (AccessSite) -> AccessSaveResult) -> StartedAccessServer?,
    private val io: CoroutineDispatcher,
    private val phoneLifetime: Duration,
    private val unreadableRetry: Duration
) : ViewModel() {

    @Inject
    constructor(
        store: AccessHeaderStore,
        privateDns: PrivateDnsSettings,
        starter: AccessHeaderServerStarter
    ) : this(store, privateDns, starter::start, Dispatchers.IO, PHONE_LIFETIME, UNREADABLE_RETRY)

    private val _sites = MutableStateFlow<AccessSitesView>(AccessSitesView.Loading)
    val sites: StateFlow<AccessSitesView> = _sites.asStateFlow()

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    private val _confirm = MutableStateFlow<AccessConfirm?>(null)
    val confirm: StateFlow<AccessConfirm?> = _confirm.asStateFlow()

    private val _phoneUrl = MutableStateFlow<String?>(null)
    val phoneUrl: StateFlow<String?> = _phoneUrl.asStateFlow()

    /** Null until the setting's first read. */
    val dnsProvider: StateFlow<PrivateDnsProvider?> =
        privateDns.provider.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var phoneServer: AccessHeaderServer? = null
    private var phoneLifetimeJob: Job? = null
    /** The page's lifetime ran out in this panel session: it doesn't come back until the panel reopens. */
    private var phoneExpired = false
    private var retryJob: Job? = null
    private var removeJob: Job? = null
    /** The latest read; a newer one cancels it, so an older read never overwrites a newer list. */
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun openPanel() {
        if (_panelOpen.value) return
        _panelOpen.value = true
        _confirm.value = null
        phoneExpired = false
        refresh()
    }

    /** Back or Done; also ON_STOP and leaving the pane: the phone page ends with the panel. */
    fun closePanel() {
        _panelOpen.value = false
        _confirm.value = null
        retryJob?.cancel()
        retryJob = null
        closePhone()
    }

    fun askRemoveSite(site: AccessSiteSummary) {
        if (_panelOpen.value) _confirm.value = AccessConfirm.Site(site)
    }

    fun askRemoveAll() {
        if (_panelOpen.value) _confirm.value = AccessConfirm.All
    }

    fun cancelConfirm() {
        _confirm.value = null
    }

    /** Runs the confirm shown; the panel then shows the list as read afterwards. */
    fun confirmRemove() {
        val confirm = _confirm.value ?: return
        if (removeJob?.isActive == true) return
        removeJob = viewModelScope.launch {
            // A failed removal leaves the site in the list read below (no separate error state is drawn).
            withContext(io) {
                when (confirm) {
                    is AccessConfirm.Site -> store.remove(confirm.site.host, confirm.site.port)
                    AccessConfirm.All -> store.removeAll()
                }
            }
            refreshJob?.cancel()
            readSites()
            // Only now, so the panel never shows the old list between the confirm and the new one.
            if (_confirm.value == confirm) _confirm.value = null
            syncPanel()
        }
    }

    fun setDnsProvider(provider: PrivateDnsProvider) {
        viewModelScope.launch { privateDns.set(provider) }
    }

    /** Reads the list again, then starts or ends the phone page to match. */
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            readSites()
            syncPanel()
        }
    }

    private suspend fun readSites() {
        val read = withContext(io) { store.sitesOrNull() }
        _sites.value = read?.let { list -> AccessSitesView.Sites(list.map { it.summary() }) } ?: AccessSitesView.Unreadable
    }

    private fun syncPanel() {
        val open = _panelOpen.value
        val readable = _sites.value is AccessSitesView.Sites
        // D23's confirm belongs to the unreadable state: a list that reads again drops it.
        if (readable && _confirm.value == AccessConfirm.All && removeJob?.isActive != true) _confirm.value = null
        if (open && readable && phoneServer == null && !phoneExpired) openPhone()
        if (!readable || !open) closePhone()
        if (open && _sites.value == AccessSitesView.Unreadable && retryJob?.isActive != true) {
            retryJob = viewModelScope.launch {
                delay(unreadableRetry)
                retryJob = null
                if (_panelOpen.value) refresh()
            }
        }
    }

    private fun openPhone() {
        val started = startPhone(::savedFromPhone) ?: return
        phoneServer = started.server
        _phoneUrl.value = started.url
        phoneLifetimeJob = viewModelScope.launch {
            delay(phoneLifetime)
            if (phoneServer === started.server) {
                phoneExpired = true
                closePhone()
            }
        }
    }

    private fun closePhone() {
        phoneLifetimeJob?.cancel()
        phoneLifetimeJob = null
        val closing = phoneServer
        phoneServer = null
        _phoneUrl.value = null
        closing?.close()
    }

    /** A phone send, on the server's handler thread: saved there (closing the panel never cuts it). */
    private fun savedFromPhone(site: AccessSite): AccessSaveResult {
        val result = store.put(site)
        viewModelScope.launch { refresh() }
        return result
    }

    // A send already being saved keeps its answer (AccessHeaderServer.close).
    override fun onCleared() {
        closePhone()
        super.onCleared()
    }

    companion object {
        /** Absolute, as the TMDB key page's: a page left open is not reachable for long. */
        val PHONE_LIFETIME: Duration = 10.minutes

        /** The store retries a failed read at most this often (AccessHeaderStore.RETRY_MS). */
        val UNREADABLE_RETRY: Duration = 30.seconds
    }
}
