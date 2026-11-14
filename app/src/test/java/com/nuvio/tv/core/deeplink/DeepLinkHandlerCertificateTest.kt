package com.nuvio.tv.core.deeplink

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.tls.AddonCertPinKeeper
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 68 P8/P9, D6, D11 Q4: link install prunes first, never offers Trust, names only the host. */
class DeepLinkHandlerCertificateTest {

    @Test
    fun `a certificate failure names the server, never the address with its keys`() = runBlocking {
        val url = "https://nas.lan:8443/secret-key/manifest.json"
        val repository = mockk<AddonRepository>(relaxed = true)
        coEvery { repository.fetchAddon(url) } returns NetworkResult.Error("cert", certificate = true)
        val keeper = mockk<AddonCertPinKeeper>(relaxed = true)
        val context = mockk<Context> {
            every { getString(R.string.addon_install_link_certificate, "nas.lan:8443") } returns "toast nas.lan:8443"
        }

        val result = DeepLinkHandler(repository, keeper, context).installAddon(url)

        assertEquals(DeepLinkInstallResult.Error("toast nas.lan:8443"), result)
        coVerifyOrder {
            keeper.prune()
            repository.fetchAddon(url)
        }
        coVerify(exactly = 0) { repository.addAddon(any()) }
    }
}
