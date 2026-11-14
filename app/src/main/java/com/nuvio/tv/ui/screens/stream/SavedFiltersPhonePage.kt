package com.nuvio.tv.ui.screens.stream

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SavedFilterRow
import com.nuvio.tv.core.server.SavedFiltersConfigServer
import com.nuvio.tv.core.server.SavedFiltersWebPage
import com.nuvio.tv.core.server.withAppLocale
import com.nuvio.tv.core.stream.SavedFilterEdit
import com.nuvio.tv.core.stream.SavedStreamFilter
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.ui.components.sources.facetFilterLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** What the saved filters' phone dialog shows. */
internal sealed interface SavedFiltersPhoneState {
    /** The page runs: the QR of [url], on this TV's [host] (address and port, for the caption). */
    data class Open(val url: String, val host: String) : SavedFiltersPhoneState

    /** No page could start: the dialog says why ([message]). */
    data class Failed(@param:StringRes val message: Int) : SavedFiltersPhoneState
}

/** How a start ended: the running server with its address, or why there is none. */
internal sealed interface SavedFiltersPageStart {
    class Started(val server: SavedFiltersConfigServer, val url: String, val host: String) : SavedFiltersPageStart
    data object NoNetwork : SavedFiltersPageStart
    data object NoFreePort : SavedFiltersPageStart
}

/** Starts a saved-filters page on this TV's LAN address ([SavedFiltersConfigServer]), in the app's language. */
class SavedFiltersServerStarter @Inject constructor(@param:ApplicationContext private val context: Context) {
    internal fun start(
        rows: () -> List<SavedFilterRow>?,
        applyEdits: (List<SavedFilterEdit>, Set<String>) -> List<SavedFilterRow>?
    ): SavedFiltersPageStart {
        val ip = DeviceIpAddress.get(context) ?: return SavedFiltersPageStart.NoNetwork
        val server = SavedFiltersConfigServer.startOnAvailablePort(
            pageHtml = { SavedFiltersWebPage.html(context) },
            rows = rows,
            applyEdits = applyEdits
        ) ?: return SavedFiltersPageStart.NoFreePort
        return SavedFiltersPageStart.Started(server, server.url(ip), "$ip:${server.listeningPort}")
    }

    /** A saved filter's values in words, as the eyebrow names them ("4K · Atmos"). */
    internal fun summary(filter: SavedStreamFilter): String =
        facetFilterLabel(filter.filter, context.withAppLocale().getString(R.string.stream_facet_unknown)).orEmpty()
}

/**
 * The filters sheet's phone page (Spec 65 S9; the TmdbKeyPhonePage shape). [open] starts a page
 * (a new server: fresh token) and [state] shows its QR; [close] (Back, ON_STOP, the ViewModel
 * cleared) stops it, and the [lifetime] closes it on its own. [state] is null while no dialog
 * shows. Main thread only, except [rows] and [applyEdits], which run on the server's handler
 * threads and never on [scope]: a save the phone sent is stored even when the dialog closes under
 * it (only its answer is lost with the socket).
 */
internal class SavedFiltersPhonePage(
    private val scope: CoroutineScope,
    private val start: (
        rows: () -> List<SavedFilterRow>?,
        applyEdits: (List<SavedFilterEdit>, Set<String>) -> List<SavedFilterRow>?
    ) -> SavedFiltersPageStart,
    private val saved: suspend () -> SavedStreamFilters,
    private val store: suspend (List<SavedFilterEdit>, Set<String>) -> SavedStreamFilters,
    private val summary: (SavedStreamFilter) -> String,
    private val lifetime: Duration = LIFETIME
) {
    private var server: SavedFiltersConfigServer? = null
    private var lifetimeJob: Job? = null
    private val _state = MutableStateFlow<SavedFiltersPhoneState?>(null)
    val state: StateFlow<SavedFiltersPhoneState?> = _state.asStateFlow()

    /** Starts a page and shows its QR, ending any other first; shows why when none can start. */
    fun open() {
        close()
        when (val started = start(::rows, ::applyEdits)) {
            is SavedFiltersPageStart.Started -> {
                server = started.server
                _state.value = SavedFiltersPhoneState.Open(started.url, started.host)
                lifetimeJob = scope.launch {
                    delay(lifetime)
                    // Nothing suspends after this: close() cancelling this job is harmless.
                    if (server === started.server) close()
                }
            }
            SavedFiltersPageStart.NoNetwork ->
                _state.value = SavedFiltersPhoneState.Failed(R.string.error_network_required)
            SavedFiltersPageStart.NoFreePort ->
                _state.value = SavedFiltersPhoneState.Failed(R.string.error_server_ports_unavailable)
        }
    }

    /** Closes the dialog and stops the page; safe to call with none open. */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val closing = server
        server = null
        _state.value = null
        closing?.stop()
    }

    /** The saved filters as the page lists them; null when the store could not be read in time. */
    private fun rows(): List<SavedFilterRow>? = withStore { saved() }

    /** The page's list as stored after [order] and [deleted]; null when it could not be stored in time. */
    private fun applyEdits(order: List<SavedFilterEdit>, deleted: Set<String>): List<SavedFilterRow>? =
        withStore { store(order, deleted) }

    // runBlocking: a NanoHTTPD handler thread is a synchronous boundary, and the answer needs the result.
    private fun withStore(work: suspend () -> SavedStreamFilters): List<SavedFilterRow>? =
        try {
            runBlocking { withTimeoutOrNull(STORE_TIMEOUT) { work() } }?.rows()
        } catch (_: IOException) {
            null
        }

    private fun SavedStreamFilters.rows(): List<SavedFilterRow> =
        filters.map { SavedFilterRow(id = it.id, name = it.name, summary = summary(it)) }

    companion object {
        /** Absolute, as the other phone pages': a QR left open is not reachable, nor the screen kept on, for long. */
        val LIFETIME: Duration = 10.minutes

        /** A DataStore read or write takes milliseconds; past this the phone is told it failed. */
        private val STORE_TIMEOUT: Duration = 5.seconds
    }
}
