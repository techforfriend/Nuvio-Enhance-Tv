package com.nuvio.tv.core.server

import com.nuvio.tv.core.tmdb.tmdbKeyInput
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** How a phone send ended (Spec 52 S3): the status the page reads, and a word for anyone else. */
enum class TmdbKeyAnswer(val status: NanoHTTPD.Response.IStatus, val word: String) {
    SAVED(NanoHTTPD.Response.Status.OK, "saved"),
    /** This panel already saved a key (the TV field's, or an earlier send): the page shows it as saved. */
    ALREADY_SAVED(NanoHTTPD.Response.Status.CONFLICT, "saved"),
    REJECTED(UnprocessableEntity, "rejected"),
    UNREACHABLE(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "unreachable"),
    /** The panel or this page closed before the send was checked; nothing was sent to TMDB. */
    CLOSED(NanoHTTPD.Response.Status.GONE, "closed")
}

/** NanoHTTPD 2.3.1 has no 422; the status line is `HTTP/1.1 ` + [getDescription]. */
private object UnprocessableEntity : NanoHTTPD.Response.IStatus {
    override fun getDescription(): String = "422 Unprocessable Entity"
    override fun getRequestStatus(): Int = 422
}

/**
 * The LAN page behind the TMDB key panel's QR (Spec 52 S3, sandbox `spec-52-public-build-ui` stage
 * t1-phone). One instance is one panel session: its own 128-bit [token] (every route lives under
 * `/k/<token>/`). Routes: `GET /k/<t>/` the page ([pageHtml]); `POST /k/<t>/key` the key as a text
 * body of at most [MAX_KEY_BODY_BYTES], whitespace dropped as the TV field does. The POST answer
 * waits for [submit] (the shared TMDB check, run on this handler thread), so the phone shows the
 * outcome ([TmdbKeyAnswer]).
 *
 * [close] never cuts an answer (NanoHTTPD's stop() closes every client socket): POSTs from then on
 * answer 410 at once, and a daemon thread stops the server once the POSTs in flight have answered,
 * plus [stopGraceMs] for the last bytes, after [stopWaitMs] at most (a send that outlives it loses its answer,
 * never its save).
 */
class TmdbKeyServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val submit: (TmdbKeyServer, String) -> TmdbKeyAnswer,
    hostname: String?,
    port: Int,
    private val stopGraceMs: Long = STOP_GRACE_MS,
    private val stopWaitMs: Long = STOP_WAIT_MS
) : NanoHTTPD(hostname, port) {

    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger()

    /** True once [close] ran: a send not yet checked is answered 410 without one. */
    val isClosed: Boolean get() = closed.get()

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    /** Ends the session and stops the server once nothing is in flight; safe to call more than once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "TmdbKeyServer-stop") {
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
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND, NOT_FOUND) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.POST && path == "key" -> receive(session)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", PAGE_CSP)
        }

    private fun receive(session: IHTTPSession): Response {
        // Counted before reading closed: close() either waits for this POST or this POST sees closed.
        inFlight.incrementAndGet()
        try {
            if (closed.get()) return session.earlyAnswer(TmdbKeyAnswer.CLOSED.status, TmdbKeyAnswer.CLOSED.word)
            val bytes = when (val body = session.readCappedBytes(MAX_KEY_BODY_BYTES)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return plain(Response.Status.LENGTH_REQUIRED, BAD_REQUEST)
                CappedBody.TooLarge -> return plain(Response.Status.PAYLOAD_TOO_LARGE, BAD_REQUEST)
                CappedBody.Short -> return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
            }
            val key = tmdbKeyInput(String(bytes, StandardCharsets.UTF_8))
            if (key.isEmpty()) return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
            val answer = submit(this, key)
            return plain(answer.status, answer.word)
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun IHTTPSession.earlyAnswer(status: Response.IStatus, body: String): Response {
        drainDeclaredBody(MAX_KEY_BODY_BYTES)
        return plain(status, body)
    }

    private fun plain(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        /** First port tried: clear of AddonConfigServer (8080-8089), TorrServer (8091), StreamBadgeConfigServer (8092-8101) and PhotoUploadServer (8110-8119). */
        const val DEFAULT_PORT = 8120

        /** A 32-character key, with room for the whitespace a paste carries. */
        const val MAX_KEY_BODY_BYTES = 256

        /** After the last answer, for its bytes to leave before stop() closes the socket. */
        internal const val STOP_GRACE_MS = 1_000L

        /** The longest a close waits for a check in flight: TMDB's 10 s check, the save (its state wait is up to 1 s), margin. */
        internal const val STOP_WAIT_MS = 15_000L

        private const val POLL_MS = 50L
        private const val PATH_PREFIX = "/k/"
        private const val BAD_REQUEST = "bad request"
        private const val NOT_FOUND = "Not found"

        /** The page is inline only and posts back to itself; the TMDB link is a plain navigation. */
        private const val PAGE_CSP =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        fun startOnAvailablePort(
            pageHtml: () -> String,
            submit: (TmdbKeyServer, String) -> TmdbKeyAnswer,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): TmdbKeyServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            TmdbKeyServer(newSessionToken(), pageHtml, submit, hostname = null, port = port)
        }
    }
}
