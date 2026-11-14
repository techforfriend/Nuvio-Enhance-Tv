package com.nuvio.tv.livetv.resolve

import com.nuvio.tv.core.network.executeCancellable
import com.nuvio.tv.core.player.PlayerMediaSourceFactory
import com.nuvio.tv.livetv.source.CappedInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A resolve step that failed; [message] is a fixed reason, never remote text, a URL or a token. */
internal class ResolveException(message: String) : IOException(message)

/**
 * The resolvers' HTTP (Spec 72 A0.1 caps): [REQUEST_TIMEOUT_MS] per call, bodies read through
 * [text] capped at [MAX_BODY_BYTES], no HTTP cache (token responses must never reach disk), and
 * no redirect between https and http, so a request that starts on https stays there. The base
 * client is the TLS-validating `@Validated` one, which carries no logging interceptor.
 */
internal class ResolverHttp(client: OkHttpClient, private val io: CoroutineDispatcher = Dispatchers.IO) {

    private val client = client.newBuilder()
        .cache(null)
        .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followSslRedirects(false)
        .build()

    /** Runs [request] and hands the response to [read] on the IO dispatcher; the response closes after. */
    suspend fun <T> execute(request: Request, read: (Response) -> T): T = withContext(io) {
        client.newCall(request).executeCancellable(read)
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val MAX_BODY_BYTES = 256L * 1024

        /** Platform endpoints answer a browser; Pluto's stitcher 403s a non-browser UA (smoke 2026-11-08). */
        const val BROWSER_USER_AGENT = PlayerMediaSourceFactory.DEFAULT_USER_AGENT

        /** The body as UTF-8, at most [MAX_BODY_BYTES] (beyond that: an IOException). */
        fun text(response: Response): String =
            CappedInputStream(response.body.byteStream(), MAX_BODY_BYTES).use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}
