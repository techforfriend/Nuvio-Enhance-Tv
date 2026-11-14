package com.nuvio.tv.core.network.tls

import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * P6: thrown instead of a real call when the host's breaker is open and its last failure was a
 * certificate, so the caller gets the certificate error rather than the guard's generic 503. A
 * subclass of [SSLPeerUnverifiedException], so [isAddonCertificateFailure] reads it like a real one.
 * Its message is for logs; `safeApiCall` shows `network_error_addon_certificate`.
 */
internal class AddonCertificateSkipException :
    SSLPeerUnverifiedException("Skipped: the addon server's last failure was its certificate")

/**
 * P6 (shapes from S0, OkHttp 5.3.2 on Conscrypt): a wrong name is an [SSLPeerUnverifiedException]
 * (our verifier said no); an untrusted, self-signed or expired chain is an [SSLHandshakeException]
 * caused by a [CertificateException]. With several addresses the certificate failure may sit in
 * `suppressed` behind another route's error, so the cause chain and `suppressed` are both walked.
 */
internal fun Throwable.isAddonCertificateFailure(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>().apply { add(this@isAddonCertificateFailure) }
    var handshake = false
    var certificate = false
    while (pending.isNotEmpty() && seen.size < MAX_WALKED) {
        val t = pending.removeFirst()
        if (!seen.add(t)) continue
        when (t) {
            is SSLPeerUnverifiedException -> return true
            is SSLHandshakeException -> handshake = true
            is CertificateException -> certificate = true
        }
        t.cause?.let(pending::addLast)
        t.suppressed.forEach(pending::addLast)
    }
    return handshake && certificate
}

/** Bounds the walk on a pathological exception graph. */
private const val MAX_WALKED = 32
