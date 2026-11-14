package com.nuvio.tv.core.network.tls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 68 X7 P1 leans on the platform manager's two-argument `checkServerTrusted`, which Android's
 * RootTrustManager refuses outright once the config has per-domain entries (AOSP android11-release).
 * Adding a `<domain-config>` therefore needs an X509ExtendedTrustManager-aware rework of AddonTlsTrust.
 */
class NetworkSecurityConfigTest {

    @Test
    fun `network security config has no domain-config`() {
        // Unit tests run with the module directory as working directory.
        val config = File("src/main/res/xml/network_security_config.xml")
        assertTrue("config not found at ${config.absolutePath}", config.isFile)
        assertFalse(config.readText().contains("<domain-config"))
    }
}
