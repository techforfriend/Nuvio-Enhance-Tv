package com.nuvio.tv.core.network.tls

import com.nuvio.tv.core.util.withAcceptedAddonScheme
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Spec 68 X7 P3: one leaf certificate the user trusted for one addon server, stored and shown per
 * `host:port` (D9). [sha256] is the leaf's DER SHA-256 in upper-case hex; [trustedAtMs] is shown
 * in the certificate-changed dialog.
 */
@Serializable
internal data class AddonCertPin(
    val host: String,
    val port: Int,
    val sha256: String,
    val trustedAtMs: Long
)

/** An immutable view of every pin, the form the TLS checks read (P2, P4). */
internal class AddonCertPins(val pins: List<AddonCertPin>) {
    private val fingerprints: Set<String> = pins.mapTo(HashSet()) { it.sha256 }

    /** P1 (host-blind): only lets a pinned leaf's handshake through; P2 binds it to a host. */
    fun containsFingerprint(sha256: String): Boolean = sha256 in fingerprints

    /** P2: the hostname verifier is never given the port, so a pin for this host on any port counts. */
    fun isPinnedForHost(host: String, sha256: String): Boolean {
        val key = pinHost(host)
        return pins.any { it.host == key && it.sha256 == sha256 }
    }

    companion object {
        val EMPTY = AddonCertPins(emptyList())
    }
}

/**
 * D9, the one rule for storing a trusted [pin]: a port with no pin only gains one (another service
 * on this host keeps its own); a port whose pin differs is a renewal, so its pin is replaced and
 * other ports of this host holding the same old fingerprint go too. Trusting the fingerprint a port
 * already holds changes nothing.
 */
internal fun List<AddonCertPin>.withTrusted(pin: AddonCertPin): List<AddonCertPin> {
    val keyed = pin.copy(host = pinHost(pin.host))
    val old = firstOrNull { it.host == keyed.host && it.port == keyed.port }
        ?: return this + keyed
    if (old.sha256 == keyed.sha256) return this
    return filterNot { it.host == keyed.host && (it.port == keyed.port || it.sha256 == old.sha256) } + keyed
}

/**
 * The server an addon URL's calls go to (`stremio://` read as `https://`), or null when it does not
 * parse. Its `host` and `port` are the pin key and, as `host:port`, `AddonHostGuard`'s key.
 */
internal fun addonServerOf(url: String): HttpUrl? = withAcceptedAddonScheme(url.trim())?.toHttpUrlOrNull()

/** P3: how a server is named to the user, `host`, or `host:port` when the port is not 443. */
internal fun addonServerLabel(host: String, port: Int): String = if (port == 443) host else "$host:$port"

/** [addonServerLabel] of an addon URL (host only: never its path or query, which carry keys); null when it does not parse. */
internal fun addonServerLabel(url: String): String? = addonServerOf(url)?.let { addonServerLabel(it.host, it.port) }

/** The host form pins are keyed by: OkHttp's `HttpUrl.host` (already lower-case punycode), lower-cased again defensively. */
internal fun pinHost(host: String): String = host.lowercase(Locale.ROOT)

/** Upper-case hex SHA-256 of [cert]'s DER encoding: the pinned value. */
internal fun leafSha256(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
