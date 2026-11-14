package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.Download
import com.nuvio.tv.core.network.executeCancellable
import com.nuvio.tv.core.network.tls.isAddonCertificateFailure
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A source fetch that failed for a reason worth showing on the Sources route; [message] is user-facing. */
class SourceFetchException(message: String) : IOException(message)

/**
 * Downloads a Live TV source over the `@Download` client (Spec 34 D10: no body cap there, so this
 * class enforces one) and detects what it serves from its first bytes. A gzip body is recognised
 * by its magic bytes, whatever the URL or headers say, and inflated; the cap counts inflated
 * bytes: [MAX_TEXT_BYTES] for playlists and manifests, [XmltvNowNextParser.MAX_INPUT_BYTES] for
 * guides (whose parser guards the stream again with its entity and text-run checks).
 */
@Singleton
class SourceFetcher internal constructor(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val maxTextBytes: Long = MAX_TEXT_BYTES
) {
    @Inject
    constructor(@Download client: OkHttpClient) : this(client, Dispatchers.IO)

    /**
     * GETs [url] and hands [read] the detected kind (null when unrecognised) and the capped,
     * inflated body; [read] runs on the IO dispatcher and the body closes when it returns. The
     * whole call, body read included, is bounded by [CALL_TIMEOUT_MINUTES] (`@Download` sets no
     * call timeout, so a slow-drip host could otherwise hold a refresh open indefinitely).
     * Throws [SourceFetchException] on a non-2xx status or an exceeded cap, IOException on
     * network failure.
     */
    suspend fun <T> fetch(url: String, read: (SourceKind?, InputStream) -> T): T = withContext(io) {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            throw SourceFetchException("Not a web address")
        }
        val call = client.newCall(request).apply { timeout().timeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
        withCertificateRefusalAsReason {
            call.executeCancellable { response ->
                if (!response.isSuccessful) throw SourceFetchException("HTTP ${response.code}")
                val (kind, body) = sniff(response.body.byteStream(), maxTextBytes)
                read(kind, body)
            }
        }
    }

    /** What a HEAD request answered: [length] = its Content-Length, null when it sent none. */
    data class Probe(val code: Int, val length: Long?)

    /**
     * HEADs [url] (redirects followed), bounded by [PROBE_TIMEOUT_SECONDS]: the status and size a
     * guide would have, without its body (Spec 55 S1). Throws IOException on network failure or
     * a URL OkHttp refuses ([SourceFetchException]).
     */
    suspend fun probe(url: String): Probe = withContext(io) {
        val request = try {
            Request.Builder().url(url).head().build()
        } catch (e: IllegalArgumentException) {
            throw SourceFetchException("Not a web address")
        }
        val call = client.newCall(request).apply { timeout().timeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        withCertificateRefusalAsReason {
            call.executeCancellable { response ->
                Probe(response.code, response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 })
            }
        }
    }

    /**
     * Spec 68 P9: a host whose certificate the `@Download` client refuses fails with a fixed reason
     * the Sources route and the player can show (a Live TV host cannot be trusted until X8).
     */
    private inline fun <T> withCertificateRefusalAsReason(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e !is SourceFetchException && e.isAddonCertificateFailure()) throw SourceFetchException(CERTIFICATE_REFUSED)
        throw e
    }

    internal companion object {
        // Spec 68 D11 Q7: English like this class's other fixed reasons.
        const val CERTIFICATE_REFUSED = "Certificate not trusted"

        /** Cap on an inflated playlist or manifest (iptv-org `index.m3u`, ~11k channels, is ~2 MiB). */
        const val MAX_TEXT_BYTES = 32L * 1024 * 1024
        /** Whole-call bound; a 50 MB guide at a slow 2 Mbit/s still fits. */
        const val CALL_TIMEOUT_MINUTES = 5L
        /** One [probe]'s bound: a HEAD carries no body, so a slow answer means a struggling host. */
        const val PROBE_TIMEOUT_SECONDS = 10L
        private const val HEAD_BYTES = 4096
        private val TV_ROOT = Regex("""<tv[\s>/]""")

        /** Inflates [raw] when gzip, detects the kind from its head and caps it by that kind. */
        fun sniff(raw: InputStream, maxTextBytes: Long = MAX_TEXT_BYTES): Pair<SourceKind?, InputStream> {
            val outer = BufferedInputStream(raw)
            val inflated = if (startsWithGzipMagic(outer)) BufferedInputStream(GZIPInputStream(outer, 64 * 1024)) else outer
            inflated.mark(HEAD_BYTES)
            val head = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < HEAD_BYTES) {
                val r = inflated.read(head, n, HEAD_BYTES - n)
                if (r < 0) break
                n += r
            }
            inflated.reset()
            val kind = detectKind(String(head, 0, n, Charsets.UTF_8))
            val cap = if (kind == SourceKind.XMLTV) XmltvNowNextParser.MAX_INPUT_BYTES else maxTextBytes
            return kind to CappedInputStream(inflated, cap)
        }

        /**
         * `#EXTM3U` (or a header-less `#EXTINF`) = playlist; XML with a `<tv` root = guide; a JSON
         * object = Stremio manifest candidate (whether it declares `tv` is the adapter's check).
         * Null for anything else (HTML error pages, raw video, images).
         */
        fun detectKind(head: String): SourceKind? {
            val text = head.removePrefix("\uFEFF").trimStart()
            return when {
                text.startsWith("#EXTM3U", ignoreCase = true) -> SourceKind.M3U
                text.startsWith("#EXTINF", ignoreCase = true) -> SourceKind.M3U
                text.startsWith("<") && TV_ROOT.containsMatchIn(text) -> SourceKind.XMLTV
                text.startsWith("{") -> SourceKind.STREMIO
                else -> null
            }
        }

        private fun startsWithGzipMagic(input: BufferedInputStream): Boolean {
            input.mark(2)
            val b0 = input.read()
            val b1 = input.read()
            input.reset()
            return b0 == 0x1f && b1 == 0x8b
        }
    }
}

/** Throws [SourceFetchException] once more than [max] bytes were read. */
internal class CappedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && ++count > max) throw tooLarge()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw tooLarge()
        }
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        count += skipped
        if (count > max) throw tooLarge()
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun tooLarge() = SourceFetchException("Larger than ${max / (1024 * 1024)} MiB")
}
