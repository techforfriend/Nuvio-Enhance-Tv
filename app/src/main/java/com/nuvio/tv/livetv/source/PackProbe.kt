package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.Download
import com.nuvio.tv.core.player.PlayerMediaSourceFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Does a pack stream answer from this TV (Spec 73 S3a)? A cut-down port of the monitor's probe
 * (Spec 71, `harvest/monitor.py` Prober): GET the URL with the row's headers (the player's default
 * User-Agent unless the row sets one), playlists read to [PLAYLIST_CAP]; an HLS master leads to its
 * first variant (at most [MASTER_HOPS] masters), then the first [SEGMENT_BYTES] of the segment third
 * from the end, which must be bytes, not text; a DASH manifest or media bytes answer at once.
 * Reachability only (D10): an encrypted stream that answers is ok; there is no DRM or block-page
 * judgement, a failure is a failure. Every remote byte is untrusted: bodies are capped, header
 * values the request builder refuses fail the probe.
 *
 * The household LAN is never scanned: a host is looked up through [PublicDns] (any non-public
 * address refuses it), an address literal is checked before connecting, and redirects are followed
 * here ([MAX_REDIRECTS]) so each hop passes the same check; no proxy. Its own client from
 * `@Download` (the same TLS as Live TV sources): no cache, [SOCKET_TIMEOUT_S] per socket
 * operation, [PROBE_TIMEOUT_MS] of request time for the whole probe (time waiting for a host's
 * slot not counted). How many run at once: [PackScanner]'s [MAX_REQUESTS] workers, and per host
 * the scan's [HostSlots], each request counted against its own host.
 */
@Singleton
class PackProbe internal constructor(
    client: OkHttpClient,
    private val isPublic: (InetAddress) -> Boolean = ::publicAddress,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS
) {
    @Inject
    constructor(@Download client: OkHttpClient) : this(client, ::publicAddress)

    private val client: OkHttpClient = client.newBuilder()
        .dns(PublicDns(client.dns, isPublic))
        // Concurrency is the scanner's (its workers, its per-host slots); OkHttp's queue would spend the
        // probe's timeout, e.g. behind abandoned calls still in a blocking DNS lookup.
        .dispatcher(Dispatcher().apply { maxRequests = DISPATCHER_SLOTS; maxRequestsPerHost = DISPATCHER_SLOTS })
        // A system proxy would resolve names itself, past [PublicDns].
        .proxy(Proxy.NO_PROXY)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(SOCKET_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * True when [stream] answered as a stream within [probeTimeoutMs] of request time; false on any
     * failure. Each request takes a slot of its host in [hosts] (one scan's, shared by its workers).
     */
    suspend fun probe(stream: PackStream, hosts: HostSlots = HostSlots()): Boolean {
        val url = stream.url.toHttpUrlOrNull() ?: return false
        return Run(hosts, probeTimeoutMs).probe(stream, url)
    }

    /**
     * One probe: its host slots and its request time left ([leftMs]; its requests run one at a time).
     * [started] once its first request holds a slot: only that one waits at the scan's start gate.
     */
    private inner class Run(private val hosts: HostSlots, private var leftMs: Long) {
        private var started = false

        suspend fun probe(stream: PackStream, url: HttpUrl): Boolean {
            val headers = try {
                val rowHeaders = stream.headers.orEmpty()
                Headers.Builder().apply {
                    rowHeaders.forEach { (name, value) -> add(name, value) }
                    PlayerMediaSourceFactory.defaultUserAgentUnlessSet(rowHeaders)?.let { add("User-Agent", it) }
                }.build()
            } catch (_: IllegalArgumentException) {
                return false
            }
            return judge(url, headers)
        }

        private suspend fun judge(url: HttpUrl, headers: Headers): Boolean {
            val first = get(url, headers, PLAYLIST_CAP, segment = false) ?: return false
            return when {
                isHls(first.body) -> judgeHls(first, headers)
                first.text(DASH_HEAD).contains("<MPD") -> true
                else -> isMedia(first)
            }
        }

        private suspend fun judgeHls(master: Answer, headers: Headers): Boolean {
            var answer = master
            for (hop in 0..MASTER_HOPS) {
                if (!answer.text().contains("#EXT-X-STREAM-INF")) break
                val variant = uriLines(answer).firstOrNull() ?: return false
                if (hop == MASTER_HOPS) return false
                val next = answer.url.resolve(variant) ?: return false
                answer = get(next, headers, PLAYLIST_CAP, segment = false) ?: return false
                if (!isHls(answer.body)) return false
            }
            val segments = uriLines(answer)
            if (segments.isEmpty()) return false
            val segmentUrl = answer.url.resolve(segments[maxOf(0, segments.size - 3)]) ?: return false
            val segment = get(segmentUrl, headers, SEGMENT_BYTES, segment = true) ?: return false
            return stripLead(segment.body).isNotEmpty() && !isText(segment.body)
        }

        /**
         * A successful answer's first [cap] bytes after following redirects; null on any failure or
         * when the probe's request time is spent. Each hop holds its own host's slot (D15 Q3: a
         * redirector holds its slot only for the redirect); the time waiting for it is not counted.
         */
        private suspend fun get(url: HttpUrl, headers: Headers, cap: Int, segment: Boolean): Answer? {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!allowedHost(current)) return null
                val request = Request.Builder().url(current).headers(headers).apply {
                    // A Range request is never gzipped by OkHttp, so the cap counts raw bytes.
                    if (segment) header("Range", "bytes=0-${cap - 1}")
                }.build()
                if (leftMs <= 0) return null
                val first = !started
                started = true
                val fetched = hosts.withSlot(current.host, first) {
                    val startNs = System.nanoTime()
                    // Not the call timeout alone: a blocking DNS lookup outlasts it; this stops waiting.
                    val answer = withTimeoutOrNull(leftMs) { fetch(request, cap) } ?: Fetched.Failed
                    leftMs -= (System.nanoTime() - startNs) / 1_000_000
                    answer
                }
                when (fetched) {
                    is Fetched.Ok -> return fetched.answer
                    is Fetched.Redirect -> current = fetched.to
                    Fetched.Failed -> return null
                }
            }
            return null
        }
    }

    private suspend fun fetch(request: Request, cap: Int): Fetched {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resume(Fetched.Failed)

                override fun onResponse(call: Call, response: Response) {
                    val fetched = try {
                        response.use { read(it, cap) }
                    } catch (_: IOException) {
                        Fetched.Failed
                    }
                    continuation.resume(fetched)
                }
            })
        }
    }

    private fun read(response: Response, cap: Int): Fetched {
        if (response.code in REDIRECT_CODES) {
            val location = response.header("Location") ?: return Fetched.Failed
            return response.request.url.resolve(location)?.let { Fetched.Redirect(it) } ?: Fetched.Failed
        }
        if (!response.isSuccessful) return Fetched.Failed
        val source = response.body.source()
        val buffer = Buffer()
        while (buffer.size < cap) {
            if (source.read(buffer, cap - buffer.size) == -1L) break
        }
        val cut = buffer.size >= cap
        val contentType = response.header("Content-Type").orEmpty().trim().lowercase()
        return Fetched.Ok(Answer(response.request.url, contentType, buffer.readByteArray(), cut))
    }

    /**
     * An address literal must be public before anything connects to it; a name is checked by
     * [PublicDns]. Literal = what OkHttp connects to without a lookup: IPv6, or digits and dots
     * (short and decimal IPv4 forms such as `127.1` or `3232235777` included, as the system parses
     * them). A digits-and-dots host that is no IPv4 form (`1.2.3.4.5`) is refused: the system
     * would look it up, outside [PublicDns], once here and again to connect.
     */
    private fun allowedHost(url: HttpUrl): Boolean {
        val host = url.host
        if (':' !in host && !host.all { it.isDigit() || it == '.' }) return true
        if (':' !in host && !IPV4_FORM.matches(host)) return false
        return try {
            isPublic(InetAddress.getByName(host))
        } catch (_: UnknownHostException) {
            false
        }
    }

    private sealed interface Fetched {
        data class Ok(val answer: Answer) : Fetched
        data class Redirect(val to: HttpUrl) : Fetched
        data object Failed : Fetched
    }

    /** One answer: its final [url], lowercased [contentType], at most the cap's bytes; [cut] = the cap was reached. */
    private class Answer(val url: HttpUrl, val contentType: String, val body: ByteArray, val cut: Boolean) {
        fun text(limit: Int = body.size): String = String(body, 0, minOf(limit, body.size), Charsets.UTF_8)
    }

    /** Looks hosts up through [delegate]; a host with no address or any non-public one is refused. */
    internal class PublicDns(private val delegate: Dns, private val isPublic: (InetAddress) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val found = delegate.lookup(hostname)
            if (found.isEmpty() || !found.all(isPublic)) throw UnknownHostException("Not a public address")
            return found
        }
    }

    internal companion object {
        const val PLAYLIST_CAP = 256 * 1024
        const val SEGMENT_BYTES = 8 * 1024
        const val MASTER_HOPS = 2
        const val MAX_REDIRECTS = 5
        const val SOCKET_TIMEOUT_S = 10L
        const val PROBE_TIMEOUT_MS = 20_000L
        const val MAX_REQUESTS = 16
        const val MAX_PER_HOST = 4

        private const val DASH_HEAD = 4096
        private const val TEXT_PROBE = 64
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        /** 1 to 4 numeric parts: the IPv4 forms the system parses without a lookup. */
        private val IPV4_FORM = Regex("""\d+(\.\d+){0,3}""")
        private const val DISPATCHER_SLOTS = 64
        private val TEXT_START = listOf("<!", "<?xml", "<html", "<head", "<body", "<h1", "<title", "<script", "<error", "#extm3u")
            .map { it.toByteArray(Charsets.US_ASCII) }
        private val JSON_START = Regex("""^[\[{]\s*["\[{]""")
        private val FMP4_BOXES = listOf("ftyp", "styp", "moof", "sidx").map { it.toByteArray(Charsets.US_ASCII) }
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /**
         * Whether [address] is on the public internet: not loopback, the LAN (RFC 1918, IPv6 ULA),
         * link-local, carrier NAT, multicast, unspecified or reserved (Python's `is_global`, which
         * the monitor uses, minus the rarely seen special blocks).
         */
        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val b = address.address.map { it.toInt() and 0xff }
            return when (address) {
                is Inet4Address -> !(b[0] == 0 || b[0] >= 240 || (b[0] == 100 && b[1] in 64..127) ||
                    (b[0] == 192 && b[1] == 0 && b[2] == 0) || (b[0] == 198 && b[1] in 18..19))
                is Inet6Address -> (b[0] and 0xfe) != 0xfc
                else -> false
            }
        }

        /** The body without a leading UTF-8 BOM and whitespace. */
        private fun stripLead(body: ByteArray): ByteArray {
            var start = if (body.startsWith(UTF8_BOM)) UTF8_BOM.size else 0
            while (start < body.size && body[start].toInt().toChar() in " \t\r\n") start++
            return body.copyOfRange(start, body.size)
        }

        private fun isHls(body: ByteArray): Boolean = stripLead(body).startsWith("#EXTM3U".toByteArray(Charsets.US_ASCII))

        /** A playlist's URI lines (a cut body's partial last line dropped). */
        private fun uriLines(answer: Answer): List<String> {
            val lines = answer.text().lines().let { if (answer.cut) it.dropLast(1) else it }
            return lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        /** Two MPEG-TS packets, an fMP4 box, ID3-tagged or ADTS audio (a UTF-16 BOM, FF FE, is not). */
        private fun mediaBytes(body: ByteArray): Boolean {
            fun at(i: Int) = body[i].toInt() and 0xff
            return (body.size > 188 && at(0) == 0x47 && at(188) == 0x47) ||
                (body.size >= 8 && FMP4_BOXES.any { box -> box.indices.all { body[4 + it] == box[it] } }) ||
                body.startsWith("ID3".toByteArray(Charsets.US_ASCII)) ||
                (body.size > 1 && at(0) == 0xff && (at(1) and 0xf6) == 0xf0)
        }

        /** A page, playlist, JSON or printable UTF-8 opening: several bytes each, so ciphertext never reads as text. */
        private fun textShaped(body: ByteArray): Boolean {
            val stripped = stripLead(body)
            val head = stripped.copyOfRange(0, minOf(TEXT_PROBE, stripped.size))
            if (head.isEmpty()) return false
            val lower = String(head, Charsets.ISO_8859_1).lowercase().toByteArray(Charsets.ISO_8859_1)
            return TEXT_START.any { lower.startsWith(it) } ||
                JSON_START.containsMatchIn(String(head, Charsets.ISO_8859_1)) ||
                printable(head)
        }

        /** Printable UTF-8 (a character cut at the end of the probe allowed). */
        private fun printable(head: ByteArray): Boolean {
            for (cut in 0..3) {
                if (cut >= head.size) break
                val text = try {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(head, 0, head.size - cut)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    continue
                }
                return text.isNotEmpty() && text.all { it in "\t\r\n" || !Character.isISOControl(it) && Character.isDefined(it) }
            }
            return false
        }

        private fun isText(body: ByteArray): Boolean = !mediaBytes(body) && textShaped(body)

        private fun isMedia(answer: Answer): Boolean {
            val type = answer.contentType
            return mediaBytes(answer.body) ||
                ((type.startsWith("video/") || type.startsWith("audio/")) && "mpegurl" !in type &&
                    answer.body.isNotEmpty() && !textShaped(answer.body))
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

/**
 * One scan's request slots per host (Spec 73 S3a, D15 Q3): at most [perHost] requests at once to a
 * host, each request counted against its own host, so a redirector (`jmp2.uk`) holds its slot only
 * for the redirect and the hop it sends to counts against the host it lands on. A probe's first
 * request starts only while [idle] (the scanner: nothing plays): it waits holding no slot, takes
 * one, and gives it back to wait again if playback began meanwhile, so waiting probes never starve
 * a started one of its host's slots. A started probe runs its chain on (its playlists and signed
 * redirects would go stale over a pause). A probe's time budget counts neither wait.
 */
class HostSlots(
    private val perHost: Int = PackProbe.MAX_PER_HOST,
    private val idle: StateFlow<Boolean> = MutableStateFlow(true)
) {
    private val gates = ConcurrentHashMap<String, Semaphore>()

    /** Runs [block] holding a slot of [host]; [first] = a probe's first request, which starts only while [idle]. */
    suspend fun <T> withSlot(host: String, first: Boolean, block: suspend () -> T): T {
        val gate = gates.computeIfAbsent(host) { Semaphore(perHost) }
        if (first) {
            while (true) {
                idle.first { it }
                gate.acquire()
                if (idle.value) break
                gate.release()
            }
        } else {
            gate.acquire()
        }
        try {
            return block()
        } finally {
            gate.release()
        }
    }
}
