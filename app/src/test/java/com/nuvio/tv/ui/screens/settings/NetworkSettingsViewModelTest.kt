package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.access.AccessHeader
import com.nuvio.tv.core.network.access.AccessHeaderStore
import com.nuvio.tv.core.network.access.AccessSaveResult
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.core.network.dns.PrivateDnsProvider
import com.nuvio.tv.core.network.dns.PrivateDnsSettings
import com.nuvio.tv.core.server.AccessHeaderServer
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Spec 97 S6: when the Access headers phone page runs, and what the panel's actions do. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val site = AccessSite("watch.kamal.ca", 443, listOf(AccessHeader("CF-Access-Client-Id", "client-id-value"), AccessHeader("CF-Access-Client-Secret", "s3cret")))

    /** What the store reads: a list, or null (unreadable). */
    private var stored: List<AccessSite>? = emptyList()
    private val store = mockk<AccessHeaderStore>(relaxed = true).also {
        every { it.sitesOrNull() } answers { stored }
        every { it.put(any()) } answers {
            val list = stored ?: return@answers AccessSaveResult.UNREADABLE
            stored = list + firstArg<AccessSite>()
            AccessSaveResult.SAVED
        }
        every { it.remove(any(), any()) } answers { stored = stored.orEmpty().filterNot { s -> s.host == firstArg<String>() }; AccessSaveResult.SAVED }
        every { it.removeAll() } answers { stored = emptyList(); AccessSaveResult.SAVED }
    }
    private val dns = mockk<PrivateDnsSettings>(relaxed = true).also {
        every { it.provider } returns flowOf(PrivateDnsProvider.OFF)
    }

    private val servers = mutableListOf<AccessHeaderServer>()
    private var phoneSave: ((AccessSite) -> AccessSaveResult)? = null
    private var network = true

    private fun viewModel() = NetworkSettingsViewModel(
        store = store,
        privateDns = dns,
        startPhone = { save ->
            if (!network) null else {
                phoneSave = save
                val server = mockk<AccessHeaderServer>(relaxed = true)
                servers += server
                StartedAccessServer(server, "http://192.0.2.119:8140/h/token${servers.size}/")
            }
        },
        io = main.dispatcher,
        phoneLifetime = 10.minutes,
        unreadableRetry = 30.seconds
    )

    private fun idle() = main.dispatcher.scheduler.runCurrent()

    @Test
    fun `the phone page runs only while the panel is open on a readable list`() {
        val vm = viewModel()
        idle()
        assertEquals(AccessSitesView.Sites(emptyList()), vm.sites.value)
        assertTrue("no page with the panel closed", servers.isEmpty())

        vm.openPanel()
        idle()
        assertEquals("http://192.0.2.119:8140/h/token1/", vm.phoneUrl.value)

        vm.closePanel()
        assertNull(vm.phoneUrl.value)
        verify { servers.single().close() }
    }

    @Test
    fun `an unreadable list shows no page, and the open panel reads it again on the retry pace`() {
        stored = null
        val vm = viewModel()
        vm.openPanel()
        idle()
        assertEquals(AccessSitesView.Unreadable, vm.sites.value)
        assertTrue(servers.isEmpty())

        stored = listOf(site)
        main.dispatcher.scheduler.advanceTimeBy(29.seconds)
        idle()
        assertEquals(AccessSitesView.Unreadable, vm.sites.value)
        main.dispatcher.scheduler.advanceTimeBy(2.seconds)
        idle()
        assertEquals(AccessSitesView.Sites(listOf(AccessSiteSummary("watch.kamal.ca", 443, listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")))), vm.sites.value)
        assertEquals(1, servers.size)
    }

    @Test
    fun `a send from the phone is saved and the panel shows it`() {
        val vm = viewModel()
        vm.openPanel()
        idle()
        assertEquals(AccessSaveResult.SAVED, phoneSave!!(site))
        idle()
        assertEquals(1, (vm.sites.value as AccessSitesView.Sites).sites.size)
        assertEquals("one page, still the same", 1, servers.size)
    }

    @Test
    fun `removing a site or every site asks first, then reads the list again`() {
        stored = listOf(site)
        val vm = viewModel()
        idle()
        vm.askRemoveSite(site.summaryForTest())
        assertNull("no confirm with the panel closed", vm.confirm.value)

        vm.openPanel()
        idle()
        vm.askRemoveSite(site.summaryForTest())
        vm.cancelConfirm()
        assertNull(vm.confirm.value)
        verify(exactly = 0) { store.remove(any(), any()) }

        vm.askRemoveSite(site.summaryForTest())
        vm.confirmRemove()
        idle()
        verify { store.remove("watch.kamal.ca", 443) }
        assertNull(vm.confirm.value)
        assertEquals(AccessSitesView.Sites(emptyList()), vm.sites.value)

        stored = null
        vm.closePanel()
        vm.openPanel()
        idle()
        assertNull("no page on an unreadable list", vm.phoneUrl.value)
        vm.askRemoveAll()
        assertEquals(AccessConfirm.All, vm.confirm.value)
        vm.confirmRemove()
        idle()
        verify { store.removeAll() }
        assertEquals(AccessSitesView.Sites(emptyList()), vm.sites.value)
        assertTrue("the QR is back", vm.phoneUrl.value != null)
    }

    @Test
    fun `the page ends on its own after its lifetime, and with no network there is none`() {
        val vm = viewModel()
        vm.openPanel()
        idle()
        main.dispatcher.scheduler.advanceTimeBy(10.minutes + 1.seconds)
        idle()
        assertNull(vm.phoneUrl.value)
        verify { servers.single().close() }

        vm.askRemoveSite(AccessSiteSummary("x", 443, emptyList()))
        vm.confirmRemove()
        idle()
        assertNull("a later read in the same panel session doesn't restart it", vm.phoneUrl.value)
        assertEquals(1, servers.size)

        network = false
        vm.closePanel()
        vm.openPanel()
        idle()
        assertNull(vm.phoneUrl.value)
    }

    @Test
    fun `a list that stops reading while the panel is open ends the page, and one that reads again drops D23's confirm`() {
        val vm = viewModel()
        vm.openPanel()
        idle()
        stored = null
        vm.askRemoveSite(AccessSiteSummary("x", 443, emptyList()))
        vm.cancelConfirm()
        assertEquals("D15: refused while unreadable", AccessSaveResult.UNREADABLE, phoneSave!!(site))
        idle()
        assertEquals(AccessSitesView.Unreadable, vm.sites.value)
        assertNull(vm.phoneUrl.value)
        verify { servers.single().close() }

        vm.askRemoveAll()
        stored = emptyList()
        main.dispatcher.scheduler.advanceTimeBy(31.seconds)
        idle()
        assertNull(vm.confirm.value)
        assertEquals(2, servers.size)
    }

    @Test
    fun `the DNS choice is saved through the setting`() {
        val vm = viewModel()
        idle()
        assertEquals(PrivateDnsProvider.OFF, vm.dnsProvider.value)
        vm.setDnsProvider(PrivateDnsProvider.QUAD9)
        idle()
        coVerify { dns.set(PrivateDnsProvider.QUAD9) }
    }

    @Test
    fun `a site's summary carries header names only, and its port only when not 443`() {
        val text = site.summaryForTest().toString() + site.summaryForTest().label
        assertTrue(text, "s3cret" !in text && "client-id-value" !in text)
        assertEquals("watch.kamal.ca", site.summaryForTest().label)
        assertEquals("nas.local:8443", AccessSiteSummary("nas.local", 8443, emptyList()).label)
    }

    private fun AccessSite.summaryForTest() = summary()
}
