package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.json.JsonObject

// Response / lifecycle plumbing shared by the phone-page servers.

private const val JSON_MIME = "application/json; charset=utf-8"

internal fun jsonResponse(status: Response.IStatus, body: String): Response =
    NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, body)

internal fun notFoundResponse(): Response =
    NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")

/** Serves the app logo PNG, or 404 when none is available. */
internal fun pngOrNotFound(bytes: ByteArray?): Response =
    if (bytes != null) {
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    } else {
        notFoundResponse()
    }

/**
 * Reads the capped POST body and parses it as a JSON object before handing it
 * to [handle]: 413 when the declared length exceeds [MAX_POST_BODY_BYTES], 400
 * (`{"error": invalidBodyMessage()}`) when the body is empty, malformed, or not
 * an object. A bad body never reaches a handler as "no fields".
 */
internal inline fun IHTTPSession.withJsonBody(
    invalidBodyMessage: () -> String,
    handle: (JsonObject) -> Response
): Response {
    val body = readCappedUtf8Body()
        ?: return NanoHTTPD.newFixedLengthResponse(
            Response.Status.PAYLOAD_TOO_LARGE,
            NanoHTTPD.MIME_PLAINTEXT,
            "Request body too large"
        )
    val parsed = parseJsonBody(body)
        ?: return jsonResponse(Response.Status.BAD_REQUEST, jsonMessage("error", invalidBodyMessage()))
    return handle(parsed)
}

/** A fresh 128-bit token for a phone page's path (every phone-page server), hex. */
internal fun newSessionToken(): String = ByteArray(16).also(SecureRandom()::nextBytes).toHexString()

/**
 * The path after `<prefix><token>/` ("" for the page, also without the last slash); null for any
 * other path or token. The token compare is constant-time.
 */
internal fun pathAfterToken(uri: String, prefix: String, token: String): String? {
    if (!uri.startsWith(prefix)) return null
    val rest = uri.substring(prefix.length)
    val presented = rest.substringBefore('/')
    if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) return null
    return rest.substringAfter('/', missingDelimiterValue = "")
}

/**
 * Headers for every answer of a server whose token rides in the path: no copy of it in a cache or a
 * Referer (the config pages load Google Fonts). A POST closes its connection: on keep-alive
 * NanoHTTPD 2.3.1 parses a body left unread (an early answer) as the next request (smoke-tested in
 * Spec 44d, PhotoUploadServer review m4).
 */
internal fun Response.withTokenPathHeaders(method: NanoHTTPD.Method): Response = apply {
    addHeader("Cache-Control", "no-store")
    addHeader("Referrer-Policy", "no-referrer")
    if (method == NanoHTTPD.Method.POST) addHeader("Connection", "close")
}

/**
 * The 404 for a path under no live token (a page left open from an earlier session). A POST's body
 * is drained first, so the phone's browser reads the answer instead of a reset.
 */
internal fun IHTTPSession.notFoundOutsideSession(): Response {
    if (method == NanoHTTPD.Method.POST) drainDeclaredBody(MAX_POST_BODY_BYTES)
    return notFoundResponse()
}

/**
 * Starts the server built by [create] on the first free port in
 * [startPort] until startPort + [maxAttempts]; null when every port is taken.
 */
internal fun <S : NanoHTTPD> startOnFirstAvailablePort(
    startPort: Int,
    maxAttempts: Int,
    create: (port: Int) -> S
): S? {
    for (port in startPort until startPort + maxAttempts) {
        val server = create(port)
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            return server
        } catch (_: IOException) {
            // Port in use, try next
        }
    }
    return null
}

/**
 * This context re-localized to the app's chosen language (LocaleCache, hydrated
 * from the app_locale prefs at startup and updated on change), so the served web
 * pages match the TV UI; unchanged when the system language is in use.
 */
internal fun Context.withAppLocale(): Context {
    val tag = LocaleCache.localeTag.takeIf { it.isNotEmpty() && it != LocaleCache.UNSET }
        ?: return this
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(config)
}

/**
 * Text for a single-quoted JS string literal in the served pages (L10-42): escapes the
 * backslash, the quote and newlines. One home for AddonWebPage and StreamBadgeWebPage.
 */
internal fun String.jsEscaped(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
