package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.ByteString.Companion.toByteString

// Spec 90 L3: ported from NuvioMedia/NuvioTV dev (2026-11-10) `HlsParameterSetInit.kt` and
// `HlsParameterSetInitDataSource.kt`, one file here; the only change is the eligibility check in
// `HlsParameterSetInitDataSource.open` ahead of the worker thread. VOD only in effect: a playlist
// qualifies only when it carries #EXT-X-ENDLIST.

/**
 * Some HLS hosts cut H.264 transport streams so that only the first segment carries the SPS and
 * PPS, and every later segment opens straight on an IDR frame. Played from the start that works,
 * because the extractor keeps the parameters from segment 0. ExoPlayer starts a fresh extractor
 * whenever it jumps, on a seek or a resume from a saved position, and its H.264 reader drops every
 * sample until it has seen an SPS and a PPS. Audio carries on while video never gets a frame, so
 * playback sits in buffering for good.
 *
 * Such a playlist gets an EXT-X-MAP initialization segment built from segment 0: its PAT, its PMT
 * and one video PES holding only the SPS and PPS. ExoPlayer feeds the init segment into every fresh
 * extractor, so a segment from the middle starts with the parameters it needs. A playlist is only
 * changed when its second segment shows the problem, so well-formed streams are left alone.
 */
@OptIn(UnstableApi::class)
internal object HlsParameterSetInit {

    /** Most bytes read from the start of a segment. Reading normally stops far earlier, see [isSegmentHeadConclusive]. */
    const val SEGMENT_HEAD_BYTES = 64 * 1024

    private const val TS_PACKET_SIZE = 188
    private const val TS_PAYLOAD_SIZE = TS_PACKET_SIZE - 4
    private const val TS_SYNC_BYTE = 0x47
    private const val PAT_PID = 0
    private const val NULL_PID = 0x1FFF
    private const val STREAM_TYPE_H264 = 0x1B
    private const val NAL_TYPE_SPS = 7
    private const val NAL_TYPE_PPS = 8

    // TsExtractor only recognizes a transport stream after five packets in a row.
    private const val MIN_INIT_PACKETS = 5

    // Packets in a row that must start with a sync byte before data counts as a transport stream.
    private const val SYNC_CHECK_PACKETS = 3

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * What the start of a segment holds. [sps] and [pps] are the parameter sets seen before the
     * first video slice, and [reachedFirstSlice] tells whether the head got as far as that slice.
     */
    class SegmentHead(
        val patPacket: ByteArray,
        val pmtPacket: ByteArray,
        val videoPid: Int,
        val sps: ByteArray?,
        val pps: ByteArray?,
        val reachedFirstSlice: Boolean
    ) {
        val hasParameterSets: Boolean get() = sps != null && pps != null
    }

    /**
     * Returns [playlist] with an init segment carrying the first segment's SPS and PPS, or null
     * when it does not need one. [fetchHead] loads the first [SEGMENT_HEAD_BYTES] of a segment.
     */
    fun withParameterSetInit(
        playlist: String,
        baseUri: String,
        fetchHead: (uri: String) -> ByteArray?
    ): String? {
        val segmentUris = eligibleSegmentUris(playlist) ?: return null
        val second = fetchHead(UriUtil.resolve(baseUri, segmentUris[1]))?.let(::scanSegmentHead)
            ?: return null
        if (!second.reachedFirstSlice || second.hasParameterSets) return null
        val first = fetchHead(UriUtil.resolve(baseUri, segmentUris[0]))?.let(::scanSegmentHead)
            ?: return null
        if (!first.hasParameterSets || first.videoPid != second.videoPid) return null
        val init = buildInitSegment(first) ?: return null
        return insertInitSegment(playlist, "data:video/mp2t;base64," + init.toByteString().base64())
    }

    /**
     * Segment URIs of a playlist an init segment can be added to: a finished media playlist of at
     * least two clear segments with no init segment, byte ranges or discontinuities of its own.
     */
    fun eligibleSegmentUris(playlist: String): List<String>? {
        if (!playlist.trimStart().startsWith("#EXTM3U")) return null
        var ended = false
        val uris = ArrayList<String>()
        for (rawLine in playlist.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) {
                uris += line
                continue
            }
            when {
                line == "#EXT-X-ENDLIST" -> ended = true
                line == "#EXT-X-DISCONTINUITY" -> return null
                line.startsWith("#EXT-X-STREAM-INF") ||
                    line.startsWith("#EXT-X-MAP") ||
                    line.startsWith("#EXT-X-BYTERANGE") ||
                    line.startsWith("#EXT-X-DEFINE") -> return null
                line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") -> return null
            }
        }
        return uris.takeIf { ended && it.size >= 2 }
    }

    /** Puts an EXT-X-MAP for [initUri] in front of the first segment. */
    fun insertInitSegment(playlist: String, initUri: String): String? {
        val firstSegment = Regex("(?m)^#EXTINF").find(playlist)?.range?.first ?: return null
        return playlist.substring(0, firstSegment) +
            "#EXT-X-MAP:URI=\"$initUri\"\n" +
            playlist.substring(firstSegment)
    }

    /** Reads the tables and the parameter sets ahead of the first video slice, or null if not H.264 TS. */
    fun scanSegmentHead(data: ByteArray): SegmentHead? = scan(data).head

    /**
     * Whether [data], the start of a segment, already holds all [scanSegmentHead] can tell: it has
     * reached the first video slice, or it is clearly not an H.264 transport stream. The parameter
     * sets and the first slice usually sit in the first couple of KiB.
     */
    fun isSegmentHeadConclusive(data: ByteArray): Boolean = scan(data).conclusive

    private class Scan(val head: SegmentHead?, val conclusive: Boolean)

    private fun scan(data: ByteArray): Scan {
        val start = findSyncOffset(data)
            ?: return Scan(head = null, conclusive = data.size >= SYNC_CHECK_PACKETS * TS_PACKET_SIZE)
        var pmtPid = -1
        var videoPid = -1
        var patPacket: ByteArray? = null
        var pmtPacket: ByteArray? = null
        val videoData = java.io.ByteArrayOutputStream()
        var inVideoPes = false

        var offset = start
        while (offset + TS_PACKET_SIZE <= data.size) {
            val packetStart = offset
            offset += TS_PACKET_SIZE
            if (data.u8(packetStart) != TS_SYNC_BYTE) break
            val unitStart = data.u8(packetStart + 1) and 0x40 != 0
            val pid = ((data.u8(packetStart + 1) and 0x1F) shl 8) or data.u8(packetStart + 2)
            val adaptation = (data.u8(packetStart + 3) shr 4) and 0x3
            var payload = packetStart + 4
            if (adaptation and 0x2 != 0) payload += 1 + data.u8(packetStart + 4)
            val packetEnd = packetStart + TS_PACKET_SIZE
            if (adaptation and 0x1 == 0 || payload >= packetEnd) continue

            when {
                pid == PAT_PID && unitStart && patPacket == null -> {
                    pmtPid = parsePmtPid(data, payload, packetEnd) ?: continue
                    patPacket = data.copyOfRange(packetStart, packetEnd)
                }
                pid == pmtPid && unitStart && pmtPacket == null -> {
                    videoPid = parseH264Pid(data, payload, packetEnd) ?: return Scan(head = null, conclusive = true)
                    pmtPacket = data.copyOfRange(packetStart, packetEnd)
                }
                pid == videoPid && videoPid >= 0 -> {
                    if (unitStart) {
                        val esStart = pesPayloadStart(data, payload, packetEnd)
                        inVideoPes = esStart != null
                        if (esStart != null) videoData.write(data, esStart, packetEnd - esStart)
                    } else if (inVideoPes) {
                        videoData.write(data, payload, packetEnd - payload)
                    }
                }
            }
        }

        val pat = patPacket ?: return Scan(head = null, conclusive = false)
        val pmt = pmtPacket ?: return Scan(head = null, conclusive = false)
        val parameterSets = scanParameterSets(videoData.toByteArray())
        val head = SegmentHead(pat, pmt, videoPid, parameterSets.sps, parameterSets.pps, parameterSets.reachedFirstSlice)
        return Scan(head, conclusive = parameterSets.reachedFirstSlice)
    }

    /** The init segment: the PAT and PMT, one video PES with the SPS and PPS, padded for sniffing. */
    fun buildInitSegment(head: SegmentHead): ByteArray? {
        val sps = head.sps ?: return null
        val pps = head.pps ?: return null
        // No PTS and an unbounded length: the reader takes the parameter sets without a frame.
        val pesHeader = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0)
        val pes = pesHeader + START_CODE + sps + START_CODE + pps
        val packets = ArrayList<ByteArray>()
        packets += head.patPacket
        packets += head.pmtPacket
        packets += packetize(head.videoPid, pes)
        while (packets.size < MIN_INIT_PACKETS) packets += nullPacket()
        val out = java.io.ByteArrayOutputStream(packets.size * TS_PACKET_SIZE)
        packets.forEach(out::write)
        return out.toByteArray()
    }

    private class ParameterSets(val sps: ByteArray?, val pps: ByteArray?, val reachedFirstSlice: Boolean)

    private fun scanParameterSets(es: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var startCode = findStartCode(es, 0)
        while (startCode >= 0) {
            val nalStart = startCode + 3
            if (nalStart >= es.size) break
            val type = es.u8(nalStart) and 0x1F
            // Types 1 to 5 are the coded slices.
            if (type in 1..5) return ParameterSets(sps, pps, reachedFirstSlice = true)
            val next = findStartCode(es, nalStart)
            // A unit is only complete once the next one starts.
            if (next < 0) break
            var nalEnd = next
            // Zero bytes before a start code belong to it, never to the unit.
            while (nalEnd > nalStart && es[nalEnd - 1].toInt() == 0) nalEnd--
            when (type) {
                NAL_TYPE_SPS -> if (sps == null) sps = es.copyOfRange(nalStart, nalEnd)
                NAL_TYPE_PPS -> if (pps == null) pps = es.copyOfRange(nalStart, nalEnd)
            }
            startCode = next
        }
        return ParameterSets(sps, pps, reachedFirstSlice = false)
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return -1
    }

    private fun findSyncOffset(data: ByteArray): Int? =
        (0 until minOf(TS_PACKET_SIZE, data.size)).firstOrNull { candidate ->
            (0 until SYNC_CHECK_PACKETS).all { packet ->
                val index = candidate + packet * TS_PACKET_SIZE
                index < data.size && data.u8(index) == TS_SYNC_BYTE
            }
        }

    /** Start of a section in a packet payload, past its pointer field. */
    private fun sectionStart(data: ByteArray, payload: Int, end: Int): Int? {
        val section = payload + 1 + data.u8(payload)
        return section.takeIf { it + 3 <= end }
    }

    private fun sectionEnd(data: ByteArray, section: Int, end: Int): Int? {
        val length = ((data.u8(section + 1) and 0x0F) shl 8) or data.u8(section + 2)
        // Ends before the CRC. A table spread over several packets is not handled.
        return (section + 3 + length - 4).takeIf { section + 3 + length <= end }
    }

    private fun parsePmtPid(data: ByteArray, payload: Int, end: Int): Int? {
        val section = sectionStart(data, payload, end) ?: return null
        if (data.u8(section) != 0x00) return null
        val programsEnd = sectionEnd(data, section, end) ?: return null
        var i = section + 8
        while (i + 4 <= programsEnd) {
            val programNumber = (data.u8(i) shl 8) or data.u8(i + 1)
            if (programNumber != 0) return ((data.u8(i + 2) and 0x1F) shl 8) or data.u8(i + 3)
            i += 4
        }
        return null
    }

    private fun parseH264Pid(data: ByteArray, payload: Int, end: Int): Int? {
        val section = sectionStart(data, payload, end) ?: return null
        if (data.u8(section) != 0x02 || section + 12 > end) return null
        val streamsEnd = sectionEnd(data, section, end) ?: return null
        val programInfoLength = ((data.u8(section + 10) and 0x0F) shl 8) or data.u8(section + 11)
        var i = section + 12 + programInfoLength
        while (i + 5 <= streamsEnd) {
            val streamType = data.u8(i)
            val pid = ((data.u8(i + 1) and 0x1F) shl 8) or data.u8(i + 2)
            if (streamType == STREAM_TYPE_H264) return pid
            i += 5 + (((data.u8(i + 3) and 0x0F) shl 8) or data.u8(i + 4))
        }
        return null
    }

    private fun pesPayloadStart(data: ByteArray, payload: Int, end: Int): Int? {
        if (payload + 9 > end) return null
        if (data.u8(payload) != 0 || data.u8(payload + 1) != 0 || data.u8(payload + 2) != 1) return null
        return (payload + 9 + data.u8(payload + 8)).takeIf { it <= end }
    }

    private fun packetize(pid: Int, payload: ByteArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var continuity = 0
        while (offset < payload.size) {
            val chunk = minOf(TS_PAYLOAD_SIZE, payload.size - offset)
            val packet = ByteArray(TS_PACKET_SIZE)
            packet[0] = TS_SYNC_BYTE.toByte()
            packet[1] = ((if (offset == 0) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            var write = 4
            if (chunk == TS_PAYLOAD_SIZE) {
                packet[3] = (0x10 or continuity).toByte()
            } else {
                // The adaptation field pads the last packet: its length byte, flags, then stuffing.
                val adaptationLength = TS_PAYLOAD_SIZE - 1 - chunk
                packet[3] = (0x30 or continuity).toByte()
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = 0
                    packet.fill(0xFF.toByte(), 6, 5 + adaptationLength)
                }
                write = 5 + adaptationLength
            }
            payload.copyInto(packet, write, offset, offset + chunk)
            packets += packet
            offset += chunk
            continuity = (continuity + 1) and 0x0F
        }
        return packets
    }

    private fun nullPacket(): ByteArray = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }.also {
        it[0] = TS_SYNC_BYTE.toByte()
        it[1] = ((NULL_PID shr 8) and 0x1F).toByte()
        it[2] = (NULL_PID and 0xFF).toByte()
        it[3] = 0x10
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}

/**
 * Gives HLS media playlists the init segment from [HlsParameterSetInit] when their segments need
 * it. Playlists are read whole and handed on, rewritten or not. Everything else passes straight
 * through, including the `data:` init segment, which [upstream] must therefore be able to open
 * (a DefaultDataSource can).
 */
@OptIn(UnstableApi::class)
internal class HlsParameterSetInitDataSource(
    private val upstream: DataSource,
    private val createSegmentSource: () -> DataSource
) : DataSource {

    private var playlist: ByteArray? = null
    private var playlistReadPosition = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        playlist = null
        playlistReadPosition = 0
        val length = upstream.open(dataSpec)
        if (dataSpec.position != 0L || !isPlaylist(dataSpec.uri, upstream.responseHeaders)) return length
        // Spec 90 L3: a body a playlist never reaches (a segment mislabeled as one) streams through untouched.
        if (length != C.LENGTH_UNSET.toLong() && length > MAX_PLAYLIST_BYTES) return length

        val body = readAll(upstream)
        val baseUri = (upstream.uri ?: dataSpec.uri).toString()
        val text = body.toString(Charsets.UTF_8)
        // Spec 90 L3 (red team m6): only an eligible playlist (finished, clear, no init of its own) pays
        // for a worker thread; a live playlist refresh costs one in-memory scan.
        // Healthy streams pay for this check too, so a slow segment host must not hold up the start.
        val rewritten = if (HlsParameterSetInit.eligibleSegmentUris(text) == null) null else resultWithin(CHECK_TIMEOUT_MS) {
            HlsParameterSetInit.withParameterSetInit(text, baseUri, ::fetchSegmentHead)
        }
        // Host only: a VOD playlist URL can carry an access token (KT-SEC-02).
        if (rewritten != null) Log.i(TAG, "Added an SPS/PPS init segment to a playlist on ${Uri.parse(baseUri).host}")
        val served = rewritten?.toByteArray(Charsets.UTF_8) ?: body
        playlist = served
        return served.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = playlist ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val remaining = data.size - playlistReadPosition
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        data.copyInto(buffer, offset, playlistReadPosition, playlistReadPosition + count)
        playlistReadPosition += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        playlist = null
        upstream.close()
    }

    private fun fetchSegmentHead(uri: String): ByteArray? {
        val source = createSegmentSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse(uri))
                    .setLength(HlsParameterSetInit.SEGMENT_HEAD_BYTES.toLong())
                    .build()
            )
            // Some hosts throttle segments, so stop as soon as the start of the segment says enough.
            val head = ByteArrayOutputStream()
            val step = ByteArray(SEGMENT_HEAD_STEP_BYTES)
            while (head.size() < HlsParameterSetInit.SEGMENT_HEAD_BYTES) {
                val read = source.read(step, 0, minOf(step.size, HlsParameterSetInit.SEGMENT_HEAD_BYTES - head.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                head.write(step, 0, read)
                if (HlsParameterSetInit.isSegmentHeadConclusive(head.toByteArray())) break
            }
            head.toByteArray()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not read the start of $uri", e)
            null
        } finally {
            runCatching { source.close() }
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HlsParameterSetInitDataSource(upstreamFactory.createDataSource(), upstreamFactory::createDataSource)
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 3_000L
        const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
        const val SEGMENT_HEAD_STEP_BYTES = 4 * 1024

        fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
            return headers.entries.any { (name, values) ->
                name.equals("Content-Type", ignoreCase = true) && values.any { it.contains("mpegurl", ignoreCase = true) }
            }
        }

        fun readAll(source: DataSource): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = source.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }
}

private const val TAG = "HlsParameterSetInit"

/**
 * Runs [block] on its own thread and returns its result, or null when it fails or [timeoutMs]
 * passes first. A block that overruns is interrupted and left to finish in the background.
 */
internal fun <T : Any> resultWithin(timeoutMs: Long, block: () -> T?): T? {
    val task = FutureTask(Callable { block() })
    Thread(task, TAG).apply { isDaemon = true }.start()
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        Log.w(TAG, "Checking segments took over $timeoutMs ms, playlist left as is")
        null
    } catch (e: ExecutionException) {
        Log.w(TAG, "Checking segments failed, playlist left as is", e.cause)
        null
    } catch (e: InterruptedException) {
        task.cancel(true)
        Thread.currentThread().interrupt()
        throw InterruptedIOException()
    }
}
