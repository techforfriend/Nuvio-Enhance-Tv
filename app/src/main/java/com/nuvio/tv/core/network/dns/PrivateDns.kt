package com.nuvio.tv.core.network.dns

import android.util.Log
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "PrivateDns"

/**
 * Spec 97 X5 N2, strict (D21): Off and local names go to the system resolver; every other name
 * goes only to the chosen provider over HTTPS, and whatever it throws is rethrown (no fallback, so
 * the network's DNS never sees an app lookup while Private DNS is on).
 *
 * [provider] returns the setting, or null when it can't be read just now: a non-local lookup then
 * fails (N4, fail closed) and the next lookup reads again. [doh] makes a provider's resolver.
 */
internal class PrivateDnsResolver(
    private val provider: () -> PrivateDnsProvider?,
    private val doh: (PrivateDnsProvider) -> Dns,
    private val system: Dns = Dns.SYSTEM,
    private val nowMs: () -> Long = System::currentTimeMillis
) : Dns {

    /** [owner] tags the answer with the provider that gave it (N2 generation tag). */
    private class Entry(val owner: Current, val addresses: List<InetAddress>, val expiresAtMs: Long)

    private class Current(val provider: PrivateDnsProvider, val dns: Dns)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var current: Current? = null

    @Volatile
    private var failing = false

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().removeSuffix(".")
        if (isLocalName(host)) return system.lookup(hostname)
        val chosen = provider() ?: throw UnknownHostException("$hostname: Private DNS setting unreadable")
        if (chosen == PrivateDnsProvider.OFF) return system.lookup(hostname)
        val active = activate(chosen)
        cache[host]?.let { if (it.owner === active && it.expiresAtMs > nowMs()) return it.addresses }
        val addresses = try {
            active.dns.lookup(host)
        } catch (e: UnknownHostException) {
            noteFailure(active.provider, e)
            throw e
        }
        noteSuccess(active.provider)
        // N2: an answer finished after a provider change is stored under the old provider's tag,
        // which no later lookup accepts (it is never served).
        if (cache.size >= CACHE_MAX_HOSTS) cache.entries.removeIf { it.value.expiresAtMs <= nowMs() }
        if (cache.size >= CACHE_MAX_HOSTS) cache.clear()
        cache[host] = Entry(active, addresses, nowMs() + CACHE_TTL_MS)
        return addresses
    }

    /** The resolver for [chosen]; a provider change clears the answer cache (pooled connections stay). */
    @Synchronized
    private fun activate(chosen: PrivateDnsProvider): Current {
        current?.let { if (it.provider == chosen) return it }
        cache.clear()
        val next = Current(chosen, doh(chosen))
        current = next
        return next
    }

    /** One line when lookups start and stop failing (no host names: they can be addon secrets). */
    private fun noteFailure(provider: PrivateDnsProvider, e: UnknownHostException) {
        if (!failing) {
            failing = true
            Log.w(TAG, "Lookups failing through $provider (${e.cause?.javaClass?.simpleName ?: "no answer"})")
        }
    }

    private fun noteSuccess(provider: PrivateDnsProvider) {
        if (failing) {
            failing = false
            Log.i(TAG, "Lookups answering again through $provider")
        }
    }

    internal companion object {
        const val CACHE_TTL_MS = 60_000L
        const val CACHE_MAX_HOSTS = 256

        /** Household suffixes no public resolver can answer (round 4 m2). */
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".localhost")

        /**
         * [host] lowercased, one trailing dot stripped. A name with no dot, a listed household
         * suffix, or an IP literal (some callers pass one; the system resolver returns it as is).
         */
        fun isLocalName(host: String): Boolean =
            '.' !in host ||
                LOCAL_SUFFIXES.any { host.endsWith(it) } ||
                ':' in host ||
                host.all { it.isDigit() || it == '.' }
    }
}

/**
 * The DoH client every provider shares (N2): platform TLS, connect 3 s, read 2 s (below the call
 * timeout, so a stalled HTTP/2 stream triggers OkHttp's degraded-connection ping and a dead pooled
 * connection is replaced), call 5 s, its own 64-slot dispatcher.
 */
internal fun privateDnsHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 64
    })
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

/**
 * A provider's resolver: GET, A records only (`includeIPv6(false)`: one query per lookup), the
 * bootstrap IPs, and `resolvePrivateAddresses(true)` so the library never consults the PSL (local
 * names never reach it). [url] and [bootstrap] are parameters so tests can point it at a fake server.
 */
internal fun dohResolver(
    client: OkHttpClient,
    url: String,
    bootstrap: List<InetAddress>
): Dns = DnsOverHttps.Builder()
    .client(client)
    .url(url.toHttpUrl())
    .bootstrapDnsHosts(bootstrap)
    .includeIPv6(false)
    .resolvePrivateAddresses(true)
    .post(false)
    .build()

/**
 * Spec 97 N3: the resolver for every app client (D1); playback keeps `IPv4FirstDns()` on system DNS.
 * Reads the setting through [PrivateDnsSettings.source], set by the app's settings store before any
 * request; unset (a process with no store, e.g. a unit test) reads as Off. That is safe because the
 * only app process injects `NuvioApplication` (which constructs the store first) in
 * `super.onCreate()`, before any code that can start a request. What runs earlier makes no app
 * request: the FileProvider and the androidx.startup initializers (Supabase's context capture,
 * OkHttp's platform, lifecycle, emoji, tracing, profile installer; merged manifest 2026-11-12).
 */
object AppDns : Dns {
    private val httpClient by lazy { privateDnsHttpClient() }

    private val resolver = IPv4FirstDns(
        PrivateDnsResolver(
            // No source: Off. A source answering null (unreadable): the resolver fails closed.
            provider = { PrivateDnsSettings.source.let { source -> if (source == null) PrivateDnsProvider.OFF else source() } },
            doh = { chosen -> dohResolver(httpClient, checkNotNull(chosen.url), chosen.bootstrapAddresses) }
        )
    )

    override fun lookup(hostname: String): List<InetAddress> = resolver.lookup(hostname)
}
