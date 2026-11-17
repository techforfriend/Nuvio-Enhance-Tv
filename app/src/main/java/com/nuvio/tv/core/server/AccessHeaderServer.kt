package com.nuvio.tv.core.server

import com.nuvio.tv.core.network.access.AccessHeader
import com.nuvio.tv.core.network.access.AccessSaveResult
import com.nuvio.tv.core.network.access.AccessSite
import com.nuvio.tv.core.network.access.AccessSiteCheck
import com.nuvio.tv.core.network.access.AccessSiteRules
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The LAN page behind the Access headers panel's QR (Spec 97 X4 A5, sandbox `s097-x4-x5-network`
 * stage 3). One instance is one panel session with its own 128-bit [token] (every route lives under
 * `/h/<token>/`). Routes: `GET /h/<t>/` the page ([pageHtml]); `POST /h/<t>/site` one site as JSON
 * `{"site": "https://…", "headers": [{"name": …, "value": …}]}`, at most [MAX_SITE_BODY_BYTES],
 * checked by [AccessSiteRules] and then handed to [save] (the store's `put`, which replaces the site
 * with the same host:port), on this handler thread.
 *
 * Answers (JSON, never a saved value: no route reads one back):
 * 200 `{"host", "headers"}` saved (the canonical host and the header count); 422 `{"problem",
 * "header"?}` the site fails A1 (the header index, 0-based, when one is at fault); 409 the TV holds
 * [AccessSiteRules.MAX_SITES] already; 503 the saved list can't be read now (D15: nothing saved);
 * 500 the write failed; 410 the panel closed; 404 another session's token; 400/411/413 a body that
 * isn't one.
 *
 * [close] never cuts an answer (TmdbKeyServer's rule): POSTs from then on answer 410 at once, and a
 * daemon thread stops the server once the POSTs in flight have answered, plus [stopGraceMs], after
 * [stopWaitMs] at most.
 */
class AccessHeaderServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val save: (AccessSite) -> AccessSaveResult,
    hostname: String?,
    port: Int,
    private val stopGraceMs: Long = STOP_GRACE_MS,
    private val stopWaitMs: Long = STOP_WAIT_MS
) : NanoHTTPD(hostname, port) {

    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger()

    /** True once [close] ran. */
    val isClosed: Boolean get() = closed.get()

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    /** Ends the session and stops the server once nothing is in flight; safe to call more than once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "AccessHeaderServer-stop") {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopWaitMs)
            // A POST counted after this loop saw 0 reads closed and answers 410 at once: the grace covers it.
            while (inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            Thread.sleep(stopGraceMs.coerceAtMost(left).coerceAtLeast(0))
            stop()
        }
    }

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            // A send from a page whose panel has closed or reopened: drained, so its browser reads "expired".
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.POST && path == "site" -> receive(session)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", INLINE_PAGE_CSP)
        }

    private fun receive(session: IHTTPSession): Response {
        // Counted before reading closed: close() either waits for this POST or this POST sees closed.
        inFlight.incrementAndGet()
        try {
            if (closed.get()) return session.earlyAnswer(Response.Status.GONE)
            val bytes = when (val body = session.readCappedBytes(MAX_SITE_BODY_BYTES)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return answer(Response.Status.LENGTH_REQUIRED)
                CappedBody.TooLarge -> return answer(Response.Status.PAYLOAD_TOO_LARGE)
                CappedBody.Short -> return answer(Response.Status.BAD_REQUEST)
            }
            val form = try {
                configServerJson.decodeFromString(SiteForm.serializer(), String(bytes, StandardCharsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                // kotlinx's SerializationException (malformed JSON, a missing field, a wrong type) is one.
                return answer(Response.Status.BAD_REQUEST)
            }
            val site = when (val check = AccessSiteRules.check(form.site, form.headers)) {
                is AccessSiteCheck.Valid -> check.site
                is AccessSiteCheck.Invalid -> return answer(UnprocessableEntity, buildJsonObject {
                    put("problem", check.problem.name)
                    check.headerIndex?.let { put("header", it) }
                }.toString())
            }
            // A close that landed while the body was read: nothing saved, the page reads "expired".
            if (closed.get()) return answer(Response.Status.GONE)
            return when (save(site)) {
                AccessSaveResult.SAVED -> answer(Response.Status.OK, buildJsonObject {
                    put("host", site.host)
                    put("headers", site.headers.size)
                }.toString())
                AccessSaveResult.TOO_MANY_SITES -> answer(Response.Status.CONFLICT)
                AccessSaveResult.UNREADABLE -> answer(Response.Status.SERVICE_UNAVAILABLE)
                // INVALID can't follow a passed check; both mean nothing was saved.
                AccessSaveResult.INVALID, AccessSaveResult.FAILED -> answer(Response.Status.INTERNAL_ERROR)
            }
        } catch (_: IOException) {
            return answer(Response.Status.BAD_REQUEST)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun IHTTPSession.earlyAnswer(status: Response.IStatus): Response {
        drainDeclaredBody(MAX_SITE_BODY_BYTES)
        return answer(status)
    }

    private fun answer(status: Response.IStatus, body: String = "{}"): Response = jsonResponse(status, body)

    /** The page's send. Unknown keys are ignored; a missing field is a malformed body (400). */
    @Serializable
    private class SiteForm(val site: String, val headers: List<AccessHeader>)

    companion object {
        /** First port tried (A5): clear of TmdbKeyServer (8120-8129) and SavedFiltersConfigServer (8130-8139). */
        const val DEFAULT_PORT = 8140

        /**
         * Four headers at A1's caps (64-char names, 4096-char values) plus the address, even with
         * every value character JSON-escaped to two bytes (a value is visible ASCII, so never more).
         */
        const val MAX_SITE_BODY_BYTES = 64 * 1024

        /** After the last answer, for its bytes to leave before stop() closes the socket. */
        internal const val STOP_GRACE_MS = 1_000L

        /**
         * The longest a close waits for a save in flight: the store's first-load wait (2 s), a Keystore
         * encrypt and a synced write, with margin. A save that outlives it loses its answer, never its save.
         */
        internal const val STOP_WAIT_MS = 10_000L

        private const val POLL_MS = 50L
        private const val PATH_PREFIX = "/h/"


        fun startOnAvailablePort(
            pageHtml: () -> String,
            save: (AccessSite) -> AccessSaveResult,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): AccessHeaderServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            AccessHeaderServer(newSessionToken(), pageHtml, save, hostname = null, port = port)
        }
    }
}
