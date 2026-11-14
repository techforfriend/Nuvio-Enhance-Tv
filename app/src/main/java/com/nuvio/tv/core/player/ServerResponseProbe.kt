package com.nuvio.tv.core.player

import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * What a server sent instead of video, read by [ServerResponseProbe] (Spec 75 D1.1). [status] and
 * [contentType] are the final answer's; [sizeBytes] the whole body when known (Content-Range total,
 * Content-Length, or a body that ended inside the read cap); [title] a web page's title as plain,
 * cleaned text; [loginHostPath] the sign-in page a redirect landed on ("host/path").
 */
data class ServerResponseFinding(
    val kind: Kind,
    val status: Int,
    val contentType: String?,
    val sizeBytes: Long?,
    val title: String? = null,
    val loginHostPath: String? = null
) {
    enum class Kind { HTTP_ERROR, LOGIN_PAGE, WEB_PAGE, TEXT, TINY_FILE }
}

/**
 * Spec 75 D1.1, D3 (4): one bounded look at what a link serves when playback failed vaguely (a
 * container the player could not read): a ranged GET of the first [MAX_BYTES], [TIMEOUT_MS] for the
 * whole call (redirects and body read included), so the error box can say "the server sent a web
 * page" instead of "could not be read". Everything it reads is untrusted: the body is only sniffed
 * with fixed-offset checks and linear scans (no backtracking regex), and the one thing kept from it,
 * the page title, is reduced to short plain text ([cleanTitle]).
 */
internal object ServerResponseProbe {
    const val MAX_BYTES = 64 * 1024
    const val TIMEOUT_MS = 3_000L

    /** Below this a whole body is a "tiny file", not a film (the mockup's 2 KB case). */
    const val TINY_FILE_BYTES = 1024L * 1024L

    private const val TITLE_MAX_CHARS = 80

    /** Most combining marks kept on one base character (a stacked "Zalgo" title would overflow the line). */
    private const val MAX_MARKS_PER_BASE = 2

    internal val client: OkHttpClient by lazy {
        PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Not a playback connection: keep it out of the NET_CONN trace and the serving host.
            .eventListener(EventListener.NONE)
            // The playback client's only app interceptor retries an SSLException on its trust-all
            // client: a second call outside this call's 3 s cap. A self-signed host is simply not
            // probed (the reason stays "could not be read").
            .apply { interceptors().clear() }
            .build()
    }

    /**
     * The finding for [url], or null when the probe learned nothing (timeout, video bytes, an
     * error). The call runs on OkHttp's dispatcher (request, redirects and the body read all under
     * the call timeout); cancelling the caller cancels the call at once.
     */
    suspend fun probe(url: String, headers: Map<String, String>): ServerResponseFinding? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val request = try {
            probeRequestBuilder(url, headers).header("Range", "bytes=0-${MAX_BYTES - 1}").build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val call = client.newCall(request)
        // The call timeout starts when the dispatcher runs the call and cannot stop a blocking DNS
        // lookup; this outer cap holds the caller to the same 3 s from here (cancel -> call.cancel).
        return withTimeoutOrNull(TIMEOUT_MS) { awaitFinding(call) }
    }

    private suspend fun awaitFinding(call: Call): ServerResponseFinding? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Whatever the untrusted answer makes the read or the judge throw, the caller
                    // gets "learned nothing" rather than waiting on a continuation never resumed.
                    val finding = try {
                        read(call, response)
                    } catch (_: Exception) {
                        null
                    }
                    continuation.resume(finding)
                }
            })
        }

    /** Reads at most [MAX_BYTES] of [response] and judges it. */
    private fun read(call: Call, response: Response): ServerResponseFinding? = response.use {
        val body = ByteArray(MAX_BYTES)
        var read = 0
        var ended = false
        val source = response.body.source()
        while (read < MAX_BYTES) {
            val n = source.read(body, read, MAX_BYTES - read)
            if (n < 0) {
                ended = true
                break
            }
            read += n
        }
        // A server that ignored the Range is sending the whole film: drop the connection rather
        // than let closing the body drain it.
        if (!ended) call.cancel()
        val finalUrl = response.request.url
        judge(
            status = response.code,
            contentType = response.header("Content-Type"),
            totalBytes = totalBytes(
                status = response.code,
                contentRange = response.header("Content-Range"),
                contentLength = response.header("Content-Length")?.toLongOrNull(),
                bytesRead = read,
                bodyEnded = ended
            ),
            body = body.copyOf(read),
            redirected = response.priorResponse != null,
            finalHost = finalUrl.host,
            finalPath = finalUrl.encodedPath
        )
    }

    /**
     * The whole body's size: a 206's Content-Range total, a 200's Content-Length, else the bytes
     * read when the body ended inside the cap; null when unknown.
     */
    fun totalBytes(status: Int, contentRange: String?, contentLength: Long?, bytesRead: Int, bodyEnded: Boolean): Long? {
        if (status == 206) {
            contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
        } else if (status in 200..299 && contentLength != null && contentLength >= 0) {
            return contentLength
        }
        return if (bodyEnded) bytesRead.toLong() else null
    }

    /** Pure: what the answer was, or null when it looks like media (or nothing can be said). */
    fun judge(
        status: Int,
        contentType: String?,
        totalBytes: Long?,
        body: ByteArray,
        redirected: Boolean,
        finalHost: String,
        finalPath: String
    ): ServerResponseFinding? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        fun finding(kind: ServerResponseFinding.Kind, title: String? = null, login: String? = null) =
            ServerResponseFinding(kind, status, type, totalBytes, title, login)

        if (status >= 400) return finding(ServerResponseFinding.Kind.HTTP_ERROR)
        // Media first: a signed CDN path may well contain an /auth/ segment.
        if (looksLikeMedia(body) || type in MEDIA_PLAYLIST_TYPES) return null
        if (redirected && isLoginPath(finalPath)) {
            return finding(ServerResponseFinding.Kind.LOGIN_PAGE, login = cleanTitle("$finalHost$finalPath"))
        }
        if (type?.contains("html") == true || looksLikeHtml(body)) {
            return finding(ServerResponseFinding.Kind.WEB_PAGE, title = pageTitle(body, contentType))
        }
        if (type != null && isTextType(type)) {
            return finding(ServerResponseFinding.Kind.TEXT)
        }
        // An untyped readable body is text whatever its size; only a binary one is a "tiny file".
        if (type == null && looksLikeText(body)) return finding(ServerResponseFinding.Kind.TEXT)
        if (totalBytes != null && totalBytes < TINY_FILE_BYTES) return finding(ServerResponseFinding.Kind.TINY_FILE)
        return null
    }

    private fun isTextType(type: String): Boolean =
        type.startsWith("text/") || type == "application/json" || type == "application/xml" ||
            type == "application/javascript" || type.endsWith("+json") || type.endsWith("+xml")

    private val LOGIN_SEGMENTS = setOf("login", "log-in", "signin", "sign-in", "sign_in", "auth")

    /** A path segment naming a sign-in page: /login, /signin, /users/sign_in, /auth/..., login.php. */
    private fun isLoginPath(path: String): Boolean =
        path.lowercase(Locale.ROOT).split('/').any { it.substringBefore(';').substringBefore('.') in LOGIN_SEGMENTS }

    private val MEDIA_PLAYLIST_TYPES = setOf(
        "application/dash+xml", "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl"
    )

    /** Common media and playlist starts: Matroska/WebM, MP4/MOV, MPEG-TS, PS, FLV, Ogg, FLAC, RIFF, ID3, HLS, DASH. */
    fun looksLikeMedia(body: ByteArray): Boolean {
        fun at(offset: Int, vararg bytes: Int) =
            body.size >= offset + bytes.size && bytes.indices.all { body[offset + it].toInt() and 0xFF == bytes[it] }
        return at(0, 0x1A, 0x45, 0xDF, 0xA3) ||
            at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) ||
            at(4, 'm'.code, 'o'.code, 'o'.code, 'v'.code) ||
            (at(0, 0x47) && at(188, 0x47)) ||
            (at(4, 0x47) && at(196, 0x47)) || // M2TS: 192-byte packets, a 4-byte timestamp first
            at(0, 0x00, 0x00, 0x01, 0xBA) ||
            at(0, 'F'.code, 'L'.code, 'V'.code) ||
            at(0, 'O'.code, 'g'.code, 'g'.code, 'S'.code) ||
            at(0, 'f'.code, 'L'.code, 'a'.code, 'C'.code) ||
            at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) ||
            at(0, 'I'.code, 'D'.code, '3'.code) ||
            looksLikePlaylist(body)
    }

    /** The first KB without a BOM (UTF-8 bytes read as Latin-1, or U+FEFF) and leading whitespace. */
    private fun trimmedHead(body: ByteArray): String =
        String(body, 0, minOf(body.size, 1024), Charsets.ISO_8859_1)
            .trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\t', '\r', '\n')

    /** An HLS or DASH playlist. */
    private fun looksLikePlaylist(body: ByteArray): Boolean {
        val start = trimmedHead(body)
        return start.startsWith("#EXTM3U") || start.startsWith("<MPD") ||
            (start.startsWith("<?xml") && start.contains("<MPD"))
    }

    private fun looksLikeHtml(body: ByteArray): Boolean {
        val start = trimmedHead(body).lowercase(Locale.ROOT)
        return start.startsWith("<!doctype html") || start.startsWith("<html") ||
            (start.startsWith("<") && (start.contains("<head") || start.contains("<body") || start.contains("<title")))
    }

    /** No NUL and at least 95% printable in the first KB. */
    private fun looksLikeText(body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val sample = body.copyOf(minOf(body.size, 1024))
        if (sample.any { it.toInt() == 0 }) return false
        val printable = sample.count { val b = it.toInt() and 0xFF; b >= 0x20 || b == 0x09 || b == 0x0A || b == 0x0D }
        return printable * 100 >= sample.size * 95
    }

    /** The page's `<title>` as clean text, or null. Decoded with the declared charset, else UTF-8. */
    fun pageTitle(body: ByteArray, contentType: String?): String? {
        val charset = declaredCharset(contentType)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        val text = String(body, charset)
        // ASCII-only folding keeps indices aligned with [text]; indexOf scans are linear.
        val lower = String(CharArray(text.length) { val c = text[it]; if (c in 'A'..'Z') c + 32 else c })
        var open = lower.indexOf("<title")
        while (open >= 0) {
            val after = lower.getOrNull(open + 6)
            if (after == null || after == '>' || after == '/' || after.isWhitespace()) break
            open = lower.indexOf("<title", open + 6)
        }
        if (open < 0) return null
        val contentStart = lower.indexOf('>', open).takeIf { it >= 0 }?.plus(1) ?: return null
        val close = lower.indexOf("</title", contentStart).takeIf { it >= 0 } ?: return null
        return cleanTitle(text.substring(contentStart, close))
    }

    /** The charset parameter of a Content-Type (`charset=x`, any case, spaces and quotes allowed), or null. */
    fun declaredCharset(contentType: String?): String? {
        if (contentType == null) return null
        val at = contentType.indexOf("charset", ignoreCase = true).takeIf { it >= 0 } ?: return null
        val rest = contentType.substring(at + "charset".length).trimStart()
        if (!rest.startsWith("=")) return null
        return rest.drop(1).trimStart().trimStart('"', '\'')
            .takeWhile { it.isLetterOrDigit() || it in "._:-" }
            .takeIf { it.isNotEmpty() }
    }

    /** The longest entity name decoded (`#1114111`, `#x10FFFF`): the `;` is looked for no further than this. */
    private const val MAX_ENTITY_CHARS = 8

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026", "middot" to "\u00B7",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122"
    )

    /** Tags dropped (each to a space) and entities decoded in one left-to-right pass: nothing is decoded twice. */
    private fun stripMarkup(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '<') {
                val end = raw.indexOf('>', i + 1)
                if (end < 0) break // an unclosed tag runs to the end: nothing after it is text
                out.append(' ')
                i = end + 1
                continue
            }
            if (c == '&') {
                // Bounded look-ahead: an unbounded indexOf per '&' is quadratic on a body of '&'s.
                var end = -1
                var j = i + 1
                while (j < raw.length && j <= i + 1 + MAX_ENTITY_CHARS) {
                    if (raw[j] == ';') {
                        end = j
                        break
                    }
                    j++
                }
                val decoded = if (end >= i + 2) decodeEntity(raw.substring(i + 1, end)) else null
                if (decoded != null) {
                    out.append(decoded)
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun decodeEntity(name: String): String? {
        if (name.startsWith("#")) {
            val hex = name.length > 1 && (name[1] == 'x' || name[1] == 'X')
            val code = if (hex) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
            return if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else " "
        }
        return NAMED_ENTITIES[name.lowercase(Locale.ROOT)]
    }

    /**
     * Untrusted text made safe to show: tags and entities resolved, control, format (bidi
     * overrides, zero-width; the joiner U+200D kept for emoji sequences) and private-use characters
     * dropped, at most [MAX_MARKS_PER_BASE] combining marks per character, whitespace collapsed, at
     * most [TITLE_MAX_CHARS] characters (an ellipsis when cut); null when nothing is left.
     */
    fun cleanTitle(raw: String): String? {
        val text = stripMarkup(raw)
        val kept = StringBuilder()
        var marks = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (kept.isNotEmpty() && kept[kept.length - 1] != ' ') kept.append(' ')
                marks = 0
                continue
            }
            val type = Character.getType(cp)
            val drop = type == Character.CONTROL.toInt() ||
                (type == Character.FORMAT.toInt() && cp != 0x200D) ||
                type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt()
            if (drop) continue
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            if (mark) {
                if (marks >= MAX_MARKS_PER_BASE) continue
                marks++
            } else if (cp != 0x200D) {
                // A joiner starts no new base character: marks after it still count against the last one.
                marks = 0
            }
            kept.appendCodePoint(cp)
        }
        val collapsed = kept.toString().trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.codePointCount(0, collapsed.length) <= TITLE_MAX_CHARS) return collapsed
        val cut = collapsed.offsetByCodePoints(0, TITLE_MAX_CHARS - 1)
        return collapsed.substring(0, cut).trimEnd() + "\u2026"
    }
}
