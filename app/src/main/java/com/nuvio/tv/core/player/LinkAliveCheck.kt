package com.nuvio.tv.core.player

import okhttp3.OkHttpClient

/** Answers that positively say a remembered link is gone; anything else (405 and 429 included) is alive. */
private val GONE_STATUSES = setOf(400, 401, 403, 404, 410)

/**
 * Fail-open HEAD check for a remembered stream link (2026-08-30): only a positive "gone" answer
 * invalidates it; a network error keeps it, so a flaky check or a host rate limit never blocks
 * playback. Sends the link's own headers (sanitized as every side probe), so a link that needs them
 * is not misread as gone.
 *
 * Spec 97 A6: on [ServerResponseProbe.client] (the playback client's TLS and access headers, a 3 s
 * call timeout, no playback trace, no trust-all retry), redirects followed. A host behind Cloudflare
 * Access now answers as itself; a self-signed host's HEAD fails and so reads as alive. Blocking.
 */
internal fun isHttpLinkAlive(
    url: String,
    headers: Map<String, String>,
    client: OkHttpClient = ServerResponseProbe.client
): Boolean = try {
    client.newCall(probeRequestBuilder(url, headers).head().build()).execute().use { it.code !in GONE_STATUSES }
} catch (_: Exception) {
    true
}
