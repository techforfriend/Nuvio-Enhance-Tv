package com.nuvio.tv.core.server

import com.nuvio.tv.core.stream.SavedFilterEdit
import com.nuvio.tv.core.stream.SavedStreamFilters
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One saved filter as the phone page lists it; [summary] is its values in words ("4K · Atmos"). */
@Serializable
data class SavedFilterRow(val id: String, val name: String, val summary: String)

/** What the page posts: its rows in order under their names, and the ids it deleted. */
@Serializable
private data class SavedFilterEdits(
    val order: List<SavedFilterEdit> = emptyList(),
    val deleted: List<String> = emptyList()
)

/**
 * The LAN page behind the filters sheet's phone button (Spec 65 S9, D14; sandbox
 * `spec-65-stream-filters` stages 8, 9). One instance is one open QR: its own 128-bit [token]
 * (every route lives under `/f/<token>/`), stopped when the QR closes. Routes: `GET /f/<t>/` the
 * page ([pageHtml]); `GET /f/<t>/filters` the saved filters ([rows]) as a JSON array;
 * `POST /f/<t>/save` a JSON body of at most [MAX_EDIT_BODY_BYTES], `{order:[{id,name}],
 * deleted:[id]}`, handed to [applyEdits] on this handler thread and answered with the list as
 * stored after it. Null from [rows] or [applyEdits] (the store could not be read or written)
 * answers 500.
 *
 * The page renames, reorders and deletes; it cannot add a filter or set one's values, and the
 * model ignores an id it does not hold ([SavedStreamFilters.edited]). Names are bounded there.
 */
class SavedFiltersConfigServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val rows: () -> List<SavedFilterRow>?,
    private val applyEdits: (order: List<SavedFilterEdit>, deleted: Set<String>) -> List<SavedFilterRow>?,
    hostname: String?,
    port: Int
) : NanoHTTPD(hostname, port) {

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            // A save from a page whose QR has closed or reopened: drained, so its browser reads "expired".
            ?: return notFound(session)
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.GET && path == "filters" -> rowsResponse(rows())
            session.method == Method.POST && path == "save" -> save(session)
            else -> notFound(session)
        }
    }

    private fun notFound(session: IHTTPSession): Response {
        if (session.method == Method.POST) session.drainDeclaredBody(MAX_EDIT_BODY_BYTES)
        return notFoundResponse()
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", PAGE_CSP)
        }

    private fun save(session: IHTTPSession): Response {
        val body = try {
            session.readCappedUtf8Body(MAX_EDIT_BODY_BYTES)
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        } ?: return plain(Response.Status.PAYLOAD_TOO_LARGE, "too large")
        val edits = try {
            configServerJson.decodeFromString(SavedFilterEdits.serializer(), body)
        } catch (_: IllegalArgumentException) {
            // SerializationException included. Its message quotes the body: not logged, not echoed.
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        }
        // The page lists what the TV holds, so it never sends more than the cap.
        if (edits.order.size > SavedStreamFilters.MAX_FILTERS || edits.deleted.size > SavedStreamFilters.MAX_FILTERS) {
            return plain(Response.Status.BAD_REQUEST, BAD_REQUEST)
        }
        return rowsResponse(applyEdits(edits.order, edits.deleted.toSet()))
    }

    private fun rowsResponse(rows: List<SavedFilterRow>?): Response =
        if (rows == null) {
            plain(Response.Status.INTERNAL_ERROR, "store unavailable")
        } else {
            jsonResponse(Response.Status.OK, configServerJson.encodeToString(ROWS, rows))
        }

    private fun plain(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        /** First port tried: clear of the other phone pages (8080-8089, 8092-8101, 8110-8119, 8120-8129) and TorrServer (8091). */
        const val DEFAULT_PORT = 8130

        /**
         * [SavedStreamFilters.MAX_FILTERS] rows of a UUID and a name of
         * [SavedStreamFilters.MAX_NAME_LENGTH] characters come to under 6 KB as JSON.
         */
        const val MAX_EDIT_BODY_BYTES = 16 * 1024

        private const val PATH_PREFIX = "/f/"
        private const val BAD_REQUEST = "bad request"
        private val ROWS = ListSerializer(SavedFilterRow.serializer())

        /** The page is inline only and talks to this server alone. */
        private const val PAGE_CSP =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        fun startOnAvailablePort(
            pageHtml: () -> String,
            rows: () -> List<SavedFilterRow>?,
            applyEdits: (order: List<SavedFilterEdit>, deleted: Set<String>) -> List<SavedFilterRow>?,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): SavedFiltersConfigServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            SavedFiltersConfigServer(newSessionToken(), pageHtml, rows, applyEdits, hostname = null, port = port)
        }
    }
}
