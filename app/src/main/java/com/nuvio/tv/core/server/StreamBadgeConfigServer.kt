package com.nuvio.tv.core.server

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeRulesParser
import com.nuvio.tv.core.torrent.TorrServerBinary
import com.nuvio.tv.core.util.stringField
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * LAN server behind the stream-badge QR page ([StreamBadgeWebPage]). Request
 * fields accepted here are exactly the ones that page sends: import/active/delete
 * POSTs {sourceUrl}. GET /api/settings feeds the page's badge list. The page
 * edits badge rules only, so rules are all this server reads and saves. One instance is one QR
 * session: every route lives under `/b/<token>/` (Spec 68 X3).
 */
class StreamBadgeConfigServer(
    private val token: String,
    private val currentRulesProvider: () -> StreamBadgeRules,
    private val onRulesChanged: (StreamBadgeRules) -> Unit,
    private val context: Context? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            ?: return session.notFoundOutsideSession()
        return when {
            session.method == Method.GET && path.isEmpty() -> serveWebPage()
            session.method == Method.GET && path == "logo.png" -> pngOrNotFound(logoProvider?.invoke())
            session.method == Method.GET && path == "api/settings" -> serveSettings()
            session.method == Method.POST && path == "api/badges/import" -> handleBadgeImport(session)
            session.method == Method.POST && path == "api/badges/active" -> handleBadgeActive(session)
            session.method == Method.POST && path == "api/badges/delete" -> handleBadgeDelete(session)
            else -> notFoundResponse()
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            StreamBadgeWebPage.html(context)
        )

    private fun serveSettings(): Response {
        val rulesJson = configServerJson.encodeToString(StreamBadgeRules.serializer(), currentRulesProvider().normalized())
        val responseJson = """{"settings":{"streamBadgeRules":$rulesJson}}"""
        return jsonResponse(Response.Status.OK, responseJson)
    }

    private fun handleBadgeImport(session: IHTTPSession): Response =
        session.withJsonBody(::invalidRequestBodyMessage) { parsed ->
            val sourceUrl = parsed.stringField("sourceUrl").orEmpty().trim()
            importUrlError(sourceUrl)?.let { return@withJsonBody errorResponse(it) }

            // The URL is arbitrary LAN input (CWE-918/209): the client only ever
            // sees the generic failure, so error text cannot probe what the TV
            // can reach. The URL itself is not logged (it may carry a token).
            val parsedImport = try {
                StreamBadgeRulesParser.parse(sourceUrl = sourceUrl, payload = fetchText(sourceUrl))
            } catch (error: IOException) {
                Log.w(TAG, "Badge import fetch failed: ${error::class.java.simpleName}: ${error.message}")
                return@withJsonBody errorResponse(badgeImportFailedMessage())
            } catch (error: IllegalArgumentException) {
                // Parser messages can embed payload snippets; log the type only.
                Log.w(TAG, "Badge import rejected: ${error::class.java.simpleName}")
                return@withJsonBody errorResponse(badgeImportFailedMessage())
            }

            // Re-read after the slow fetch: rule changes made meanwhile (another
            // import, a delete) must not be reverted by a stale snapshot.
            val rules = currentRulesProvider().normalized().upsert(parsedImport, activate = true)
            onRulesChanged(rules)
            rulesResponse("imported", rules)
        }

    /** The message to show when [sourceUrl] may not be imported; null when it may. */
    private fun importUrlError(sourceUrl: String): String? = when {
        sourceUrl.isBlank() ->
            context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_url_required) ?: "Enter a badge JSON URL."
        !sourceUrl.startsWith("https://", ignoreCase = true) &&
            !sourceUrl.startsWith("http://", ignoreCase = true) ->
            context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_url_scheme) ?: "Badge URL must start with http:// or https://."
        importLimitReached(sourceUrl) ->
            context?.getString(com.nuvio.tv.R.string.web_stream_badge_error_import_limit, STREAM_BADGE_IMPORT_LIMIT) ?: "You can import up to $STREAM_BADGE_IMPORT_LIMIT badge URLs."
        else -> null
    }

    /** Re-importing an existing source is always allowed; a new one needs a free slot. */
    private fun importLimitReached(sourceUrl: String): Boolean {
        val currentRules = currentRulesProvider().normalized()
        val isExistingImport = currentRules.imports.any { import ->
            import.sourceUrl.equals(sourceUrl, ignoreCase = true)
        }
        return !isExistingImport && currentRules.imports.size >= STREAM_BADGE_IMPORT_LIMIT
    }

    private fun handleBadgeActive(session: IHTTPSession): Response =
        updateRules(session, "saved", StreamBadgeRules::setActiveSource)

    private fun handleBadgeDelete(session: IHTTPSession): Response =
        updateRules(session, "deleted", StreamBadgeRules::removeSource)

    /** Applies [update] for the posted {sourceUrl} to the current rules, saves, and echoes them. */
    private fun updateRules(
        session: IHTTPSession,
        status: String,
        update: StreamBadgeRules.(sourceUrl: String) -> StreamBadgeRules
    ): Response =
        session.withJsonBody(::invalidRequestBodyMessage) { parsed ->
            val sourceUrl = parsed.stringField("sourceUrl").orEmpty()
            val rules = currentRulesProvider().normalized().update(sourceUrl)
            onRulesChanged(rules)
            rulesResponse(status, rules)
        }

    private fun rulesResponse(status: String, rules: StreamBadgeRules): Response {
        val rulesJson = configServerJson.encodeToString(StreamBadgeRules.serializer(), rules)
        return jsonResponse(Response.Status.OK, """{"status":"$status","streamBadgeRules":$rulesJson}""")
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            // Bounded read (2026-08-27): the URL is arbitrary, so a huge or
            // misbehaving body must not stream into memory unbounded. An
            // over-cap payload truncates and fails JSON parsing below.
            connection.inputStream.use { input ->
                val buffer = ByteArray(MAX_IMPORT_PAYLOAD_BYTES)
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                String(buffer, 0, offset, StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun badgeImportFailedMessage(): String =
        context?.getString(com.nuvio.tv.R.string.web_stream_badge_import_error) ?: "Badge import failed."

    private fun invalidRequestBodyMessage(): String =
        context?.getString(com.nuvio.tv.R.string.web_error_invalid_request_body) ?: "Invalid request body"

    private fun errorResponse(message: String): Response =
        jsonResponse(Response.Status.BAD_REQUEST, jsonMessage("error", message))

    companion object {
        private const val TAG = "StreamBadgeConfigServer"
        private const val PATH_PREFIX = "/b/"

        /** First port tried; above TorrServer's, clear of AddonConfigServer's 8080 range. */
        const val DEFAULT_PORT = TorrServerBinary.PORT + 1

        /** Badge payloads are small JSON; a bigger remote body is truncated and
         *  fails JSON parsing downstream instead of streaming into memory. */
        private const val MAX_IMPORT_PAYLOAD_BYTES = 2_000_000

        fun startOnAvailablePort(
            currentRulesProvider: () -> StreamBadgeRules,
            onRulesChanged: (StreamBadgeRules) -> Unit,
            context: Context? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): StreamBadgeConfigServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            StreamBadgeConfigServer(
                token = newSessionToken(),
                currentRulesProvider = currentRulesProvider,
                onRulesChanged = onRulesChanged,
                context = context,
                logoProvider = logoProvider,
                port = port
            )
        }
    }
}
