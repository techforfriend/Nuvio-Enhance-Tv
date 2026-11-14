package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.R
import com.nuvio.tv.core.server.FAKE_HOST
import com.nuvio.tv.core.server.FakeGetSession
import com.nuvio.tv.core.server.FakePostSession
import com.nuvio.tv.core.server.SavedFiltersConfigServer
import com.nuvio.tv.core.server.bodyText
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.FacetGroup
import com.nuvio.tv.core.stream.FacetValue
import com.nuvio.tv.core.stream.SavedStreamFilters
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 65 S9: the phone page per open QR (start, failure, close, lifetime) and what its server reads and stores. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedFiltersPhonePageTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private var held = SavedStreamFilters.NONE
        .withSaved("a", "Atmos", FacetFilter.NONE.toggled(FacetValue(FacetGroup.AUDIO, "ATMOS")))
        .withSaved("b", "4K", FacetFilter.NONE.toggled(FacetValue(FacetGroup.RESOLUTION, "P2160")))
    private var storeFails = false
    private var startResult: SavedFiltersPageStart? = null
    private val servers = mutableListOf<SavedFiltersConfigServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    private fun TestScope.page() = SavedFiltersPhonePage(
        scope = backgroundScope,
        start = { rows, applyEdits ->
            startResult ?: run {
                val server = SavedFiltersConfigServer(token, { "page" }, rows, applyEdits, "127.0.0.1", 0)
                server.start()
                servers += server
                SavedFiltersPageStart.Started(server, server.url("127.0.0.1"), "127.0.0.1:${server.listeningPort}")
            }
        },
        saved = { if (storeFails) throw IOException("disk") else held },
        store = { order, deleted ->
            if (storeFails) throw IOException("disk")
            held.edited(order, deleted).also { held = it }
        },
        summary = { filter -> filter.values.values.flatten().joinToString() }
    )

    private val server get() = servers.last()

    @Test
    fun `open shows the QR of a running page and close stops it`() = runTest {
        val page = page()
        assertNull(page.state.value)
        page.open()
        val open = page.state.value as SavedFiltersPhoneState.Open
        assertTrue(open.url.endsWith("/f/$token/"))
        assertEquals("127.0.0.1:${server.listeningPort}", open.host)
        assertTrue(server.isAlive)

        page.close()
        assertNull(page.state.value)
        assertFalse(server.isAlive)
        page.close()
    }

    @Test
    fun `opening again replaces the page`() = runTest {
        val page = page()
        page.open()
        val first = server
        page.open()
        assertNotSame(first, server)
        assertFalse(first.isAlive)
        assertTrue(server.isAlive)
        page.close()
    }

    @Test
    fun `no network or no free port shows why, and closes like a page`() = runTest {
        val page = page()
        startResult = SavedFiltersPageStart.NoNetwork
        page.open()
        assertEquals(SavedFiltersPhoneState.Failed(R.string.error_network_required), page.state.value)
        startResult = SavedFiltersPageStart.NoFreePort
        page.open()
        assertEquals(SavedFiltersPhoneState.Failed(R.string.error_server_ports_unavailable), page.state.value)
        page.close()
        assertNull(page.state.value)
    }

    @Test
    fun `the page closes on its own when its lifetime ends, and a replaced page's timer closes nothing`() = runTest {
        val page = page()
        page.open()
        advanceTimeBy(9.minutes)
        page.open()
        val second = server
        advanceTimeBy(2.minutes)
        runCurrent()
        assertTrue(second.isAlive)
        assertTrue(page.state.value is SavedFiltersPhoneState.Open)
        // 11 minutes in: the second page opened at 9 and lives until 19.
        advanceTimeBy(8.minutes - 1.milliseconds)
        runCurrent()
        assertTrue(second.isAlive)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertNull(page.state.value)
        assertFalse(second.isAlive)
    }

    @Test
    fun `the server lists what is saved and stores the phone's list`() = runTest {
        val page = page()
        page.open()
        val listed = server.serve(FakeGetSession("/f/$token/filters", FAKE_HOST))
        assertEquals(
            """[{"id":"a","name":"Atmos","summary":"ATMOS"},{"id":"b","name":"4K","summary":"P2160"}]""",
            listed.bodyText()
        )
        val saved = server.serve(
            FakePostSession("""{"order":[{"id":"b","name":"Movie night"}],"deleted":["a"]}""", "/f/$token/save")
        )
        assertEquals(Status.OK, saved.status)
        assertEquals("""[{"id":"b","name":"Movie night","summary":"P2160"}]""", saved.bodyText())
        assertEquals(listOf("b" to "Movie night"), held.filters.map { it.id to it.name })
        page.close()
    }

    @Test
    fun `a store that fails answers 500 and keeps the page up`() = runTest {
        val page = page()
        page.open()
        storeFails = true
        assertEquals(Status.INTERNAL_ERROR, server.serve(FakeGetSession("/f/$token/filters", FAKE_HOST)).status)
        assertEquals(
            Status.INTERNAL_ERROR,
            server.serve(FakePostSession("""{"order":[{"id":"b","name":"x"}]}""", "/f/$token/save")).status
        )
        assertEquals(listOf("a" to "Atmos", "b" to "4K"), held.filters.map { it.id to it.name })
        assertTrue(page.state.value is SavedFiltersPhoneState.Open)
        page.close()
    }
}
