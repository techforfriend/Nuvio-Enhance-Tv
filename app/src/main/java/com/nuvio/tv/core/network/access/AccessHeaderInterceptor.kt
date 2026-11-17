package com.nuvio.tv.core.network.access

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Spec 97 A4: the one source every client's [AccessHeaderInterceptor] reads, so the playback
 * clients (plain objects, outside Hilt) and the injected ones see the same store. Set by the app's
 * [AccessHeaderStore] when Hilt builds it, the first field `NuvioApplication` injects, so before any
 * request; unset means no headers.
 */
object AccessHeaders {
    @Volatile
    var source: (() -> List<AccessSite>)? = null

    internal fun sites(): List<AccessSite> = source?.invoke().orEmpty()
}

/**
 * The saved site [url] gets headers from: https only, exact canonical host and port (D6). [sites]
 * is read only for https, so a plain-http request never waits on the store's first load.
 */
internal fun accessSiteFor(url: HttpUrl, sites: () -> List<AccessSite>): AccessSite? =
    if (url.isHttps) sites().firstOrNull { it.matches(url.host, url.port) } else null

/**
 * Spec 97 A3 + A8, a NETWORK interceptor: it runs once per hop, after the connection is made, so
 * each redirect hop is matched by its own host and a hop off a saved site gets nothing (an
 * application interceptor's header would ride every redirect follow-up). Register it only on
 * clients that validate TLS (the plan's invariant); a request whose TLS fails never reaches it.
 *
 * Any failure while matching sends the request without headers. A saved site's response loses
 * `Set-Cookie` and any `Vary` token naming a saved header, before OkHttp's cache can store either
 * (a session cookie or the varied request header would sit in plain text on disk).
 */
object AccessHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var site: AccessSite? = null
        val withHeaders = try {
            site = accessSiteFor(request.url, AccessHeaders::sites)
            site?.let { saved -> request.newBuilder().apply { saved.headers.forEach { header(it.name, it.value) } }.build() }
        } catch (_: Throwable) {
            null
        }
        val matched = site
        if (withHeaders == null || matched == null) return chain.proceed(request)
        return stripStoredSecrets(chain.proceed(withHeaders), matched)
    }

    private fun stripStoredSecrets(response: Response, site: AccessSite): Response {
        val vary = response.headers.values("Vary")
        if (vary.isEmpty() && response.header("Set-Cookie") == null) return response
        val names = site.headers.mapTo(HashSet()) { it.name.lowercase() }
        val kept = vary.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in names }
        return response.newBuilder()
            .removeHeader("Set-Cookie")
            .removeHeader("Vary")
            .apply { if (kept.isNotEmpty()) header("Vary", kept.joinToString(", ")) }
            .build()
    }
}
