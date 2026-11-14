package com.nuvio.tv.core.network.tls

import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.addonGuardKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/** P7: what a certificate probe of an addon server found. */
sealed interface AddonCertProbeResult {

    /** No certificate came back: unreachable, or not an https address. Nothing to trust; the flag stays. */
    data object NoCertificate : AddonCertProbeResult

    /** The certificate passes the checks now (platform, or a pin for this host). The flag is cleared. */
    data object VerifiesNow : AddonCertProbeResult

    /** The server's addresses presented different certificates: no single fingerprint to trust. */
    data object Inconsistent : AddonCertProbeResult

    /**
     * The certificate fails the checks. [previous] is the pin stored for exactly this `host:port`
     * (D9): non-null means the certificate changed. [shownName] is the leaf's first DNS or IP
     * subject-alternative name, shown for a wrong name.
     */
    data class Untrusted internal constructor(
        val host: String,
        val port: Int,
        val leaf: X509Certificate,
        val sha256: String,
        val reason: Reason,
        val shownName: String?,
        internal val previous: AddonCertPin?
    ) : AddonCertProbeResult {
        val previousSha256: String? get() = previous?.sha256
        val previousTrustedAtMs: Long? get() = previous?.trustedAtMs
    }

    /** Spec 68 D11 Q11: an untrusted chain is SELF_SIGNED (leaf issuer = subject) or from an UNKNOWN_ISSUER. */
    enum class Reason { SELF_SIGNED, UNKNOWN_ISSUER, EXPIRED, WRONG_NAME }
}

/**
 * Spec 68 X7 P7: reads an addon server's certificate without sending it anything. Runs only on
 * Install or Review certificate. Each probe builds a fresh client (DNS and timeouts as `@Api`; no
 * cache, no interceptors, its own pool and its own [SSLContext], so the host guard cannot answer
 * for it and no TLS session is resumed) whose trust manager records the chain and always throws:
 * the handshake never completes, so no request byte is written.
 */
@Singleton
class AddonCertProbe internal constructor(
    private val policy: AddonTrustPolicy,
    private val pins: suspend () -> AddonCertPins,
    private val guard: AddonHostGuard,
    private val dns: Dns,
    private val io: CoroutineDispatcher,
    private val nowMs: () -> Long
) {
    @Inject
    internal constructor(addonTls: AddonTlsTrust, store: AddonCertPinStore, guard: AddonHostGuard) : this(
        policy = addonTls.policy,
        pins = store::current,
        guard = guard,
        dns = IPv4FirstDns(),
        io = Dispatchers.IO,
        nowMs = System::currentTimeMillis
    )

    suspend fun probe(addonUrl: String): AddonCertProbeResult = withContext(io) {
        val server = addonServerOf(addonUrl)
        if (server == null || !server.isHttps) return@withContext AddonCertProbeResult.NoCertificate
        val recorder = ChainRecorder()
        // Scheme, host and port only: the addon's path and query carry its keys.
        val origin = server.newBuilder().encodedPath("/").query(null).fragment(null).build()
        capture(origin, recorder)
        val chains = recorder.chains
        val chain = chains.firstOrNull() ?: return@withContext AddonCertProbeResult.NoCertificate
        if (chains.mapTo(HashSet()) { leafSha256(it.first()) }.size > 1) {
            return@withContext AddonCertProbeResult.Inconsistent
        }
        val host = server.host
        val port = server.port
        // A pin-store failure reads as "not pinned", as on the real call.
        if (runCatching { policy.isTrusted(host, chain) }.getOrDefault(false)) {
            guard.clearCertificateFailure(addonGuardKey(host, port))
            return@withContext AddonCertProbeResult.VerifiesNow
        }
        val leaf = chain.first()
        val previous = try {
            pins().pins.firstOrNull { it.host == pinHost(host) && it.port == port }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // the store failed: shown as a first trust; Trust still applies D9
        }
        AddonCertProbeResult.Untrusted(
            host = host,
            port = port,
            leaf = leaf,
            sha256 = leafSha256(leaf),
            reason = when {
                // The chain is trusted, so the name is what failed.
                policy.platformAccepts(chain) -> AddonCertProbeResult.Reason.WRONG_NAME
                leaf.notAfter.time < nowMs() -> AddonCertProbeResult.Reason.EXPIRED
                isSelfSigned(leaf) -> AddonCertProbeResult.Reason.SELF_SIGNED
                else -> AddonCertProbeResult.Reason.UNKNOWN_ISSUER
            },
            shownName = firstSubjectAltName(leaf),
            previous = previous
        )
    }

    private suspend fun capture(origin: okhttp3.HttpUrl, recorder: ChainRecorder) {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(recorder), null) }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .sslSocketFactory(context.socketFactory, recorder)
            .hostnameVerifier { _, _ -> false } // never reached: the trust manager always throws
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(origin).build())
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = continuation.resume(Unit)
                    override fun onResponse(call: Call, response: Response) {
                        response.close() // unreachable while the recorder throws; never read
                        continuation.resume(Unit)
                    }
                })
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Records every server chain offered, first kept first; accepts nothing. */
    private class ChainRecorder : X509TrustManager {
        val chains = CopyOnWriteArrayList<List<X509Certificate>>()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!chain.isNullOrEmpty()) chains += chain.toList()
            throw CertificateException("Certificate probe: capture only")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Certificate probe: capture only")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * The leaf names itself as its issuer and its signature checks with its own key (D11 Q11: the panel
 * says "self-signed" only then; a home CA whose name equals the leaf's subject stays "unknown issuer").
 */
internal fun isSelfSigned(leaf: X509Certificate): Boolean =
    leaf.issuerX500Principal == leaf.subjectX500Principal &&
        runCatching { leaf.verify(leaf.publicKey) }.isSuccess

/** First DNS (2) or IP (7) subject-alternative name, or null. */
private fun firstSubjectAltName(leaf: X509Certificate): String? = runCatching {
    leaf.subjectAlternativeNames
        ?.firstOrNull { (it.getOrNull(0) as? Int) == 2 || (it.getOrNull(0) as? Int) == 7 }
        ?.getOrNull(1) as? String
}.getOrNull()
