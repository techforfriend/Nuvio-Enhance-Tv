package com.nuvio.tv.core.network.tls

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.network.AddonHostGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Spec 68 X7 P8 + D9 + S1-d1: the one place pins change. */
class AddonCertPinKeeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lists = mutableMapOf(1 to listOf("https://nas.lan:8443/key"), 2 to listOf("https://box.lan/other"))
    private var readFails = false
    private var evictions = 0

    /** Runs once, inside the first list read (after the read took its value). */
    private var duringFirstRead: (suspend () -> Unit)? = null
    private val store by lazy {
        AddonCertPinStore(PreferenceDataStoreFactory.create { File(tmp.root, "addon_tls/pins.preferences_pb") })
    }
    private val keeper by lazy {
        AddonCertPinKeeper(
            store = store,
            profileIds = { lists.keys.toList() },
            storedAddonUrls = { id ->
                if (readFails) throw IOException("disk")
                val read = lists[id].orEmpty()
                duringFirstRead?.let { duringFirstRead = null; it() }
                read
            },
            evictConnections = { evictions++ },
            io = Dispatchers.Unconfined
        )
    }

    private fun pin(host: String, port: Int = 443, sha: String = "AA") = AddonCertPin(host, port, sha, 1L)

    private suspend fun hosts() = store.current().pins.map { it.host }.toSet()

    @Test
    fun `prune keeps every host some profile's list uses and drops the rest`() = runBlocking {
        listOf(pin("nas.lan", 8443), pin("box.lan"), pin("gone.lan")).forEach { keeper.trust(it) }
        assertEquals(0, evictions)
        keeper.prune()
        assertEquals(setOf("nas.lan", "box.lan"), hosts())
        assertEquals(1, evictions)
        keeper.prune() // nothing to drop: no eviction
        assertEquals(1, evictions)
    }

    @Test
    fun `a throwing read prunes nothing`() = runBlocking {
        keeper.trust(pin("gone.lan"))
        readFails = true
        keeper.prune()
        assertEquals(setOf("gone.lan"), hosts())
        assertEquals(0, evictions)
    }

    @Test
    fun `a renewal evicts pooled connections, a first trust does not`() = runBlocking {
        keeper.trust(pin("nas.lan", 8443, "OLD"))
        keeper.trust(pin("nas.lan", 9443, "OTHER"))
        assertEquals(0, evictions)
        keeper.trust(pin("nas.lan", 8443, "NEW"))
        assertEquals(1, evictions)
    }

    @Test
    fun `sign-out clears every pin and evicts`() = runBlocking {
        keeper.trust(pin("nas.lan", 8443))
        keeper.clear()
        assertTrue(store.current().pins.isEmpty())
        assertEquals(1, evictions)
    }

    @Test
    fun `a prune racing an install keeps the pin the user just trusted`() = runBlocking {
        // The worst interleaving: the prune reads a list without the addon, then the install writes
        // it and ends (exemption released) before the prune decides. Only an exemption snapshot taken
        // before the reads keeps the pin.
        val url = "https://new.lan/key"
        val pinStored = CompletableDeferred<Unit>()
        val listWritten = CompletableDeferred<Unit>()
        val install = launch(Dispatchers.Default) {
            keeper.withInstallExemption(url) {
                keeper.trust(pin("new.lan"))
                pinStored.complete(Unit)
                listWritten.await()
                lists[1] = lists.getValue(1) + url // addAddon
            }
        }
        pinStored.await()
        duringFirstRead = {
            listWritten.complete(Unit)
            install.join()
        }
        keeper.prune()
        assertTrue("new.lan" in hosts())
        keeper.prune()
        assertTrue("new.lan" in hosts())
    }

    @Test
    fun `a failed or cancelled install releases its exemption`() = runBlocking {
        val url = "https://new.lan/key"
        runCatching { keeper.withInstallExemption(url) { keeper.trust(pin("new.lan")); throw IOException("manifest") } }
        keeper.prune()
        assertTrue("new.lan" !in hosts())

        val started = CompletableDeferred<Unit>()
        val install = async(Dispatchers.Default) {
            keeper.withInstallExemption(url) {
                keeper.trust(pin("new.lan"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        keeper.prune()
        assertTrue("new.lan" in hosts())
        install.cancel()
        assertTrue(runCatching { install.await() }.exceptionOrNull() is CancellationException)
        yield()
        keeper.prune()
        assertTrue("new.lan" !in hosts())
    }
}

class AddonCertTrustActionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `Trust stores the probed pin, resets the breaker and flag, and re-fetches that server's addons`() = runBlocking {
        val store = AddonCertPinStore(PreferenceDataStoreFactory.create { File(tmp.root, "pins.preferences_pb") })
        val keeper = AddonCertPinKeeper(store, { listOf(1) }, { emptyList() }, {}, Dispatchers.Unconfined)
        val guard = AddonHostGuard(nowMs = { 0L }, wallNowMs = { 0L })
        val key = "nas.lan:8443"
        repeat(AddonHostGuard.FAILURES_TO_OPEN) { guard.onCertificateFailure(key, probe = false) }
        val fetched = mutableListOf<String>()
        val stateAtFetch = mutableListOf<Boolean>()
        val installed = listOf(
            "https://nas.lan:8443/one", "https://NAS.lan:8443/two?cfg=1", "https://nas.lan:9443/other-port",
            "https://box.lan/other-host"
        )
        val action = AddonCertTrustAction(keeper, guard, { installed }, { url ->
            // The re-fetch must find the pin stored and the breaker closed, or it is skipped.
            stateAtFetch += store.current().pins.isNotEmpty() && guard.admit(key) == AddonHostGuard.Admission.ALLOW
            fetched += url
        }, { 42L })
        val leaf = X7TlsFixtures.leaf("a")
        val probed = AddonCertProbeResult.Untrusted(
            host = "nas.lan", port = 8443, leaf = leaf, sha256 = leafSha256(leaf),
            reason = AddonCertProbeResult.Reason.SELF_SIGNED, shownName = "addon-a.test", previous = null
        )

        action.trust(probed)

        assertEquals(listOf(AddonCertPin("nas.lan", 8443, leafSha256(leaf), 42L)), store.current().pins)
        assertEquals(AddonHostGuard.Admission.ALLOW, guard.admit(key))
        assertTrue(guard.certificateFailures.value.isEmpty())
        assertEquals(listOf("https://nas.lan:8443/one", "https://NAS.lan:8443/two?cfg=1"), fetched)
        assertEquals(listOf(true, true), stateAtFetch)
    }
}
