package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The cause a primary playlist load carries when its body is an MPEG-TS stream (Spec 90 L2): the
 * provider serves plain TS on an HLS-typed link. `LivePlayer` reads it (via `findCause`) to retry
 * the stream once as progressive; no other parse failure takes that rung.
 */
internal class HlsBodyIsTs : IOException("The playlist link sent an MPEG-TS stream")

/**
 * Reads live HLS playlists the forgiving way other IPTV players do (Spec 90 L2; after
 * homelessbrian/NuvioPlusIPTV `LiveHlsRepair.kt`). Media3's parser rejects a playlist with a
 * UTF-8 BOM (its reader decodes it to U+FEFF, which its header check does not skip), without
 * `#EXTM3U`, with a variant line lacking BANDWIDTH or a rendition lacking NAME/GROUP-ID; those
 * are put right ([HlsRepair.fix]) before it reads. A TS body is refused after 512 bytes, before an
 * endless stream is read, and only the primary playlist load marks it [HlsBodyIsTs]: a media
 * playlist sending TS is a broken variant, not a TS link.
 */
@OptIn(UnstableApi::class)
internal class LenientHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val inner = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(inner.createPlaylistParser(), primary = true)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), primary = false)

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>, primary: Boolean) =
        ParsingLoadable.Parser { uri: Uri, input: InputStream ->
            val head = readUpTo(input, HEAD_BYTES)
            if (HlsRepair.isTs(head)) {
                throw ParserException.createForMalformedManifest("Not a playlist (an MPEG-TS stream)", if (primary) HlsBodyIsTs() else null)
            }
            val rest = readUpTo(input, MAX_BYTES - head.size + 1)
            if (head.size + rest.size > MAX_BYTES) {
                throw ParserException.createForMalformedManifest("Not a playlist (over ${MAX_BYTES / (1024 * 1024)} MiB)", null)
            }
            val text = HlsRepair.fix(String(head + rest, Charsets.UTF_8))
                ?: throw ParserException.createForMalformedManifest("Not a playlist (no #EXTM3U)", null)
            parser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val HEAD_BYTES = 512
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}

/** The playlist repairs, plain text in and out (table-tested). */
internal object HlsRepair {
    private const val TS_PACKET = 188
    private const val TS_SYNC = 0x47.toByte()

    /** An MPEG-TS stream: sync bytes at 0 and 188 (a short body starting with 'G' is text). */
    fun isTs(head: ByteArray): Boolean = head.size > TS_PACKET && head[0] == TS_SYNC && head[TS_PACKET] == TS_SYNC

    /** [playlist] put right, or null when it is no HLS playlist at all. */
    fun fix(playlist: String): String? {
        var text = playlist.removePrefix("\uFEFF")
        if (!text.trimStart().startsWith("#EXTM3U")) {
            if (!text.contains("#EXTINF") && !text.contains("#EXT-X-")) return null
            text = "#EXTM3U\n" + text.trimStart()
        }
        if (!text.contains("#EXT-X-STREAM-INF") && !text.contains("#EXT-X-I-FRAME-STREAM-INF") && !text.contains("#EXT-X-MEDIA:")) {
            return text
        }
        return text.lineSequence().joinToString("\n", transform = ::fixLine)
    }

    private fun fixLine(line: String): String {
        val l = line.trimEnd()
        return when {
            (l.startsWith("#EXT-X-STREAM-INF:") || l.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) && !HAS_BANDWIDTH.containsMatchIn(l) -> {
                val colon = l.indexOf(':')
                val attributes = l.substring(colon + 1)
                l.substring(0, colon + 1) + "BANDWIDTH=$DEFAULT_BANDWIDTH" + (if (attributes.isNotEmpty()) ",$attributes" else "")
            }
            l.startsWith("#EXT-X-MEDIA:") -> {
                var out = l
                if (!HAS_NAME.containsMatchIn(out)) out += ",NAME=\"Main\""
                if (!HAS_GROUP_ID.containsMatchIn(out)) out += ",GROUP-ID=\"default\""
                out
            }
            else -> line
        }
    }

    // What Media3's parser accepts (`[^-]BANDWIDTH=(\d+)`, `NAME="(.+?)"`, `GROUP-ID="(.+?)"`): an
    // unusable value gets a usable one too, and the parser reads the first match.
    private val HAS_BANDWIDTH = Regex("""[:,]\s*BANDWIDTH=\d+\b""")
    private val HAS_NAME = Regex("""[:,]\s*NAME="[^"]""")
    private val HAS_GROUP_ID = Regex("""[:,]\s*GROUP-ID="[^"]""")
    private const val DEFAULT_BANDWIDTH = 5_000_000
}
