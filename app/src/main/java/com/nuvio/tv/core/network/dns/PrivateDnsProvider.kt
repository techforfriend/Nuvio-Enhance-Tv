package com.nuvio.tv.core.network.dns

import java.net.InetAddress

/**
 * Spec 97 X5 N1: the Private DNS choices (D7: no Custom). Each provider's RFC 8484 endpoint and its
 * bootstrap IPs, so the DoH host itself is never looked up through the network's DNS (each pair
 * checked 2026-11-12: GET over h2 -> 200 `application/dns-message`). [key] is what the setting stores.
 */
enum class PrivateDnsProvider(val key: String, val url: String?, bootstrap: List<String>) {
    OFF("off", null, emptyList()),
    CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    QUAD9("quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"));

    /** IP literals: getByName parses them without a lookup. */
    val bootstrapAddresses: List<InetAddress> by lazy { bootstrap.map(InetAddress::getByName) }

    companion object {
        /** An unknown or missing key reads as Off. */
        fun fromKey(key: String?): PrivateDnsProvider = entries.firstOrNull { it.key == key } ?: OFF
    }
}
