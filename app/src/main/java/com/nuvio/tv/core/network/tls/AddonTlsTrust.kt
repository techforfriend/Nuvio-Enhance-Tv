package com.nuvio.tv.core.network.tls

import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The authType P2 hands the platform manager when it re-checks a session's chain. Spec 68 S0
 * (Android 11, Conscrypt): GENERIC, RSA and ECDHE_RSA gave the same verdict on every chain.
 */
private const val SESSION_AUTH_TYPE = "GENERIC"

/**
 * Spec 68 X7: validated TLS for the addon clients (`@Api`, `@Download`), with per-host trust on
 * first use. A certificate passes when the platform accepts its chain and it names the host, or
 * when its leaf is pinned for that host (any port; D9). Replaces the old trust-all.
 */
@Singleton
class AddonTlsTrust internal constructor(platform: X509TrustManager, pins: () -> AddonCertPins) {

    @Inject
    constructor(store: AddonCertPinStore) : this(platformTrustManager(), store::pinsBlocking)

    internal val policy = AddonTrustPolicy(platform, pins)
    val trustManager: X509TrustManager = AddonTrustManager(platform, pins)
    val hostnameVerifier: HostnameVerifier = AddonHostnameVerifier(policy)

    /** One context for both clients: they share its TLS session cache. */
    val sslSocketFactory: SSLSocketFactory = SSLContext.getInstance("TLS")
        .apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        .socketFactory
}

/**
 * The platform's default trust manager. Spec 68 S0: on Android 11 this is `RootTrustManager` and it
 * carries `network_security_config.xml`'s extra anchors (ISRG Root X2, absent from the system store).
 * Its two-argument `checkServerTrusted` throws when that config gains a `<domain-config>`
 * (AOSP android11-release RootTrustManager), which `NetworkSecurityConfigTest` guards.
 */
internal fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

/** P2's one decision, shared by the hostname verifier and (S2) the probe so the two cannot disagree. */
internal class AddonTrustPolicy(
    private val platform: X509TrustManager,
    private val pins: () -> AddonCertPins
) {
    /**
     * Platform chain and name first (a valid host never reads the pin store), else the leaf pinned
     * for this host on any port. A pinned leaf counts past its expiry: the user saw its dates.
     */
    fun isTrusted(host: String, chain: List<X509Certificate>): Boolean {
        val leaf = chain.firstOrNull() ?: return false
        if (platformAccepts(chain) && OkHostnameVerifier.verify(host, leaf)) return true
        return pins().isPinnedForHost(host, leafSha256(leaf))
    }

    /** A platform failure of any kind (not only CertificateException) reads as "not accepted", as in P1. */
    fun platformAccepts(chain: List<X509Certificate>): Boolean = try {
        platform.checkServerTrusted(chain.toTypedArray(), SESSION_AUTH_TYPE)
        true
    } catch (e: CertificateException) {
        false
    } catch (e: RuntimeException) {
        false
    }
}

/**
 * P1: host-blind, it only decides whether the handshake may finish: the platform accepts the chain,
 * or the leaf is pinned for some host (P2 then binds it to the right one). Only
 * [CertificateException] leaves it; a pin-store failure of any kind (an Error from a first
 * DataStore use included) counts as "not pinned", so nothing else can kill OkHttp's thread.
 */
internal class AddonTrustManager(
    private val platform: X509TrustManager,
    private val pins: () -> AddonCertPins
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!isPinnedLeaf(chain)) throw e
        } catch (e: RuntimeException) {
            if (!isPinnedLeaf(chain)) throw CertificateException(e)
        }
    }

    // OkHttp builds its certificate chain cleaner from these; the platform's issuers keep it working
    // for valid chains. A pinned self-signed leaf's cleaned chain is empty (S0: 0, no throw): our code
    // never reads Handshake.peerCertificates, and OkHttp's cache just stores the empty list.
    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private fun isPinnedLeaf(chain: Array<out X509Certificate>?): Boolean {
        val leaf = chain?.firstOrNull() ?: return false
        return try {
            pins().containsFingerprint(leafSha256(leaf))
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * P2: runs on every connection, resumed ones included (S0: a resumed session skips the trust
 * manager), so it is the complete check. Anything thrown inside it (Errors included: the first pin
 * read initialises DataStore) returns false rather than escaping onto OkHttp's dispatcher thread.
 */
internal class AddonHostnameVerifier(private val policy: AddonTrustPolicy) : HostnameVerifier {
    override fun verify(host: String, session: SSLSession): Boolean = try {
        val certificates = session.peerCertificates
        val chain = certificates.filterIsInstance<X509Certificate>()
        chain.size == certificates.size && policy.isTrusted(host, chain)
    } catch (t: Throwable) {
        false
    }
}
