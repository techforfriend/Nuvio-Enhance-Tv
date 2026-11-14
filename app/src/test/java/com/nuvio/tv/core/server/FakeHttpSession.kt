package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Shared NanoHTTPD session fakes so server tests can call serve() directly. */

/** Host header the fakes send by default: loopback passes the servers' Host allow-list. */
internal const val FAKE_HOST = "127.0.0.1:8080"

internal class FakePostSession(
    body: String,
    private val uri: String,
    /** Overrides the declared Content-Length (413-guard tests send an inflated one). */
    private val declaredLength: Int? = null,
    private val host: String? = FAKE_HOST,
    private val origin: String? = null
) : NanoHTTPD.IHTTPSession {
    private val bytes = body.toByteArray(StandardCharsets.UTF_8)

    override fun execute() = Unit
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = buildMap {
        put("content-length", (declaredLength ?: bytes.size).toString())
        put("content-type", "application/json; charset=utf-8")
        host?.let { put("host", it) }
        origin?.let { put("origin", it) }
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST
    @Deprecated("Deprecated in NanoHTTPD")
    override fun getParms(): Map<String, String> = emptyMap()
    override fun getParameters(): Map<String, List<String>> = emptyMap()
    override fun getQueryParameterString(): String? = null
    override fun getUri(): String = uri
    @Deprecated("Deprecated in NanoHTTPD")
    override fun parseBody(files: MutableMap<String, String>) {
        error("parseBody should not be used")
    }
    override fun getRemoteIpAddress(): String = "127.0.0.1"
    override fun getRemoteHostName(): String = "localhost"
}

internal class FakeGetSession(
    private val uri: String,
    private val host: String? = FAKE_HOST
) : NanoHTTPD.IHTTPSession {
    override fun execute() = Unit
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> =
        if (host != null) mapOf("host" to host) else emptyMap()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.GET
    @Deprecated("Deprecated in NanoHTTPD")
    override fun getParms(): Map<String, String> = emptyMap()
    override fun getParameters(): Map<String, List<String>> = emptyMap()
    override fun getQueryParameterString(): String? = null
    override fun getUri(): String = uri
    @Deprecated("Deprecated in NanoHTTPD")
    override fun parseBody(files: MutableMap<String, String>) {
        error("parseBody should not be used")
    }
    override fun getRemoteIpAddress(): String = "127.0.0.1"
    override fun getRemoteHostName(): String = "localhost"
}

/**
 * A raw-body request (PhotoUploadServer, TmdbKeyServer): [declaredLength] null sends no Content-Length,
 * [kind] null no `kind` query parameter (the key page takes none), and [failAfter] makes the body stream throw after that many bytes (a
 * phone dropping off the Wi-Fi). [stream] shows how much of the body the server left unread.
 */
internal class FakeUploadSession(
    private val uri: String,
    private val method: NanoHTTPD.Method = NanoHTTPD.Method.POST,
    private val kind: String? = "avatar",
    body: ByteArray = ByteArray(0),
    private val declaredLength: String? = body.size.toString(),
    private val host: String = FAKE_HOST,
    private val origin: String? = null,
    failAfter: Int? = null
) : NanoHTTPD.IHTTPSession {
    val stream: InputStream = if (failAfter == null) {
        ByteArrayInputStream(body)
    } else {
        object : ByteArrayInputStream(body.copyOf(failAfter)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it <= 0) throw java.io.IOException("connection reset") }
        }
    }
    override fun execute() = Unit
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = buildMap {
        put("host", host)
        declaredLength?.let { put("content-length", it) }
        origin?.let { put("origin", it) }
    }
    override fun getInputStream(): InputStream = stream
    override fun getMethod(): NanoHTTPD.Method = method
    @Deprecated("Deprecated in NanoHTTPD")
    override fun getParms(): Map<String, String> = emptyMap()
    override fun getParameters(): Map<String, List<String>> =
        kind?.let { mapOf("kind" to listOf(it)) }.orEmpty()
    override fun getQueryParameterString(): String? = kind?.let { "kind=$it" }
    override fun getUri(): String = uri
    @Deprecated("Deprecated in NanoHTTPD")
    override fun parseBody(files: MutableMap<String, String>) = error("parseBody should not be used")
    override fun getRemoteIpAddress(): String = "127.0.0.1"
    override fun getRemoteHostName(): String = "localhost"
}

/**
 * Spec 68 X3: a token page must call its routes relative to its own path; an absolute same-origin
 * fetch / src / href ('/api/...', src="/logo.png") would miss the token and 404.
 */
internal fun assertNoAbsoluteSameOriginPath(html: String) {
    val absolute = Regex("""(fetch\(|fetchWithTimeout\(|src=|href=)\s*['"]/(?!/)""").findAll(html)
        .map { it.value }
        .toList()
    org.junit.Assert.assertTrue("absolute paths: $absolute", absolute.isEmpty())
}

/** Reads a NanoHTTPD response body as UTF-8 text. */
internal fun NanoHTTPD.Response.bodyText(): String =
    data.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
