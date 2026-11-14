package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets

/** Host names that always mean "this device" for the config servers. */
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

/**
 * Request gate run at the top of every phone-page server's serve() (the two
 * config servers, PhotoUploadServer, TmdbKeyServer and SavedFiltersConfigServer), for every method. Returns the 403 to send, or null when the request may proceed.
 *
 * 1. Host allow-list (DNS rebinding, CWE-346): a rebinding page reaches this
 *    server under the attacker's hostname, so its Host header carries that
 *    name. Only this device's own IPv4 addresses (the QR code URL is the raw
 *    IP from [DeviceIpAddress]) and loopback are accepted. This also covers
 *    GETs, which expose addon URLs (bearer credentials) via /api/state.
 * 2. POST same-origin check (drive-by CSRF): see [isSameOriginOrNonBrowserPost].
 */
internal fun IHTTPSession.rejectUntrustedRequest(): Response? {
    val ownAddresses = localIpv4Addresses().mapNotNull { it.hostAddress?.lowercase() }.toSet()
    if (!isAllowedConfigHost(headers["host"], ownAddresses)) {
        return forbidden("Host not allowed")
    }
    if (method == NanoHTTPD.Method.POST && !isSameOriginOrNonBrowserPost(ownAddresses)) {
        return forbidden("Cross-origin request rejected")
    }
    return null
}

/**
 * True when [host] (a Host header value or URI host, optional `:port`) names
 * this device: loopback or one of [ownAddresses]. A missing Host is rejected:
 * HTTP/1.1 clients (every browser) always send one.
 */
internal fun isAllowedConfigHost(host: String?, ownAddresses: Set<String>): Boolean {
    val name = host?.trim()?.substringBefore(':')?.lowercase()
    if (name.isNullOrEmpty()) return false
    return name in LOOPBACK_HOSTS || name in ownAddresses
}

/**
 * Drive-by CSRF gate for POSTs (2026-08-26 review).
 *
 * Browsers send Origin (or Referer) on POSTs; non-browser clients (curl, the
 * app itself) send neither and are allowed through. A hostile page makes the
 * victim's browser POST from its own origin, so the Origin/Referer host must
 * name this device (see [isAllowedConfigHost]); the served phone page is
 * loaded from the device's own IP, so same-origin use passes.
 */
internal fun IHTTPSession.isSameOriginOrNonBrowserPost(ownAddresses: Set<String>): Boolean {
    val origin = (headers["origin"] ?: headers["referer"])?.trim().orEmpty()
    if (origin.isEmpty()) return true // non-browser client; browser drive-by always sends one
    val originHost = runCatching { URI(origin).host }.getOrNull()
        ?: return false // malformed Origin header: reject
    return isAllowedConfigHost(originHost, ownAddresses)
}

private fun forbidden(message: String): Response =
    NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, message)

/** Max accepted POST body, regardless of the client-declared Content-Length
 *  (CWE-400: NanoHTTPD buffers non-multipart bodies per the declared length). */
internal const val MAX_POST_BODY_BYTES = 2_000_000

/**
 * Reads a non-multipart POST body capped at [maxBytes]. Returns null when the
 * declared Content-Length exceeds the cap, so the caller can reject with 413
 * before allocating; an over-long body never streams fully into memory.
 */
internal fun IHTTPSession.readCappedUtf8Body(maxBytes: Int = MAX_POST_BODY_BYTES): String? {
    val length = declaredBodyLength() ?: return ""
    if (length == 0L) return ""
    if (length > maxBytes) return null
    val buffer = ByteArray(length.toInt())
    var offset = 0
    while (offset < buffer.size) {
        val read = inputStream.read(buffer, offset, buffer.size - offset)
        if (read <= 0) break
        offset += read
    }
    return String(buffer, 0, offset, StandardCharsets.UTF_8)
}

/** The client-declared Content-Length, or null when it is missing, not a number or negative. */
internal fun IHTTPSession.declaredBodyLength(): Long? =
    headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

/**
 * Reads and drops a declared body of at most [maxBytes] before an early answer (one decided before
 * the body is read: a stale token, a busy or closed session), so the phone's browser gets the answer
 * instead of a reset from closing over unread bytes. A larger or undeclared body is left unread: the
 * caller's answer closes the connection.
 */
internal fun IHTTPSession.drainDeclaredBody(maxBytes: Int) {
    val length = declaredBodyLength() ?: return
    if (length > maxBytes) return
    try {
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        var left = length
        while (left > 0) {
            val read = inputStream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read <= 0) break
            left -= read
        }
    } catch (_: IOException) {
        // The connection closes after the answer anyway.
    }
}

private const val DRAIN_BUFFER_BYTES = 8 * 1024

/** A raw (binary) POST body read by [readCappedBytes]. */
internal sealed interface CappedBody {
    class Bytes(val bytes: ByteArray) : CappedBody
    /** No usable Content-Length: the caller answers 411. */
    data object NoLength : CappedBody
    /** Declared length over the cap, refused before allocating: 413. */
    data object TooLarge : CappedBody
    /** The stream ended before the declared length: 400. */
    data object Short : CappedBody
}

/**
 * Reads exactly the declared Content-Length of a raw POST body (an upload), at most [maxBytes].
 * Unlike [readCappedUtf8Body] a missing length is an error, not an empty body, and nothing is
 * decoded as text.
 */
internal fun IHTTPSession.readCappedBytes(maxBytes: Int): CappedBody {
    val length = declaredBodyLength() ?: return CappedBody.NoLength
    if (length > maxBytes) return CappedBody.TooLarge
    val buffer = ByteArray(length.toInt())
    var offset = 0
    while (offset < buffer.size) {
        val read = inputStream.read(buffer, offset, buffer.size - offset)
        if (read <= 0) return CappedBody.Short
        offset += read
    }
    return CappedBody.Bytes(buffer)
}
