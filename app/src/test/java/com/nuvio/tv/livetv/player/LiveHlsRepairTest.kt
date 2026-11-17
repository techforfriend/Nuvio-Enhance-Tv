package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.nuvio.tv.core.player.findCause
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Spec 90 L2: forgiving live HLS playlists, and the TS-body marker. */
class LiveHlsRepairTest {

    private val uri: Uri = mockk(relaxed = true) { every { this@mockk.toString() } returns "http://host.example/live/index.m3u8" }

    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"

    private fun ts(size: Int = 600) = ByteArray(size).also { bytes -> for (i in 0 until size step 188) bytes[i] = 0x47 }

    @Test
    fun `the AAR's own parser refuses a BOM playlist, the repaired one reads`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + media.toByteArray()
        assertThrows(ParserException::class.java) { HlsPlaylistParser().parse(uri, ByteArrayInputStream(bom)) }
        val parsed = LenientHlsPlaylistParserFactory().createPlaylistParser().parse(uri, ByteArrayInputStream(bom))
        assertEquals(2, (parsed as HlsMediaPlaylist).segments.size)
    }

    @Test
    fun `repairs only what the parser rejects`() {
        assertEquals(media, HlsRepair.fix(media))
        assertEquals("#EXTM3U\n#EXTINF:6.0,\nseg1.ts", HlsRepair.fix("\n#EXTINF:6.0,\nseg1.ts"))
        assertEquals(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhd.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nsd.m3u8",
            HlsRepair.fix("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nhd.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nsd.m3u8")
        )
        // AVERAGE-BANDWIDTH is not BANDWIDTH.
        assertEquals(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=1\na.m3u8",
            HlsRepair.fix("#EXTM3U\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1\na.m3u8")
        )
        assertEquals(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\",NAME=\"Main\",GROUP-ID=\"default\"",
            HlsRepair.fix("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\"")
        )
        // A value Media3 cannot read gets a readable one in front (BANDWIDTH) or after (NAME), first match wins.
        assertEquals(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,BANDWIDTH=high\na.m3u8",
            HlsRepair.fix("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=high\na.m3u8")
        )
        assertEquals(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,BANDWIDTH=5000k\na.m3u8",
            HlsRepair.fix("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000k\na.m3u8")
        )
        assertEquals(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=main,NAME=\"Main\"",
            HlsRepair.fix("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=main")
        )
        assertNull(HlsRepair.fix("<html>Not found</html>"))
    }

    @Test
    fun `a multivariant playlist without BANDWIDTH reads after the repair`() {
        // CODECS set: under isReturnDefaultValues the TextUtils stub sends a null CODECS into trim().
        val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS=\"avc1.64001f,mp4a.40.2\"\nhd.m3u8\n"
        assertThrows(ParserException::class.java) { HlsPlaylistParser().parse(uri, ByteArrayInputStream(playlist.toByteArray())) }
        val parsed = LenientHlsPlaylistParserFactory().createPlaylistParser().parse(uri, ByteArrayInputStream(playlist.toByteArray()))
        assertEquals(1, (parsed as HlsMultivariantPlaylist).variants.size)
    }

    @Test
    fun `a TS body is marked on the primary load only`() {
        assertTrue(HlsRepair.isTs(ts()))
        assertFalse(HlsRepair.isTs(media.toByteArray()))
        assertFalse(HlsRepair.isTs(ByteArray(400).also { it[0] = 0x47 }))
        assertFalse(HlsRepair.isTs("Gone".toByteArray()))
        val factory = LenientHlsPlaylistParserFactory()
        val primary = assertThrows(ParserException::class.java) { factory.createPlaylistParser().parse(uri, ByteArrayInputStream(ts())) }
        assertNotNull(primary.findCause<HlsBodyIsTs>())
        val multivariant = factory.createPlaylistParser()
            .parse(uri, ByteArrayInputStream("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.64001f\"\nv.m3u8\n".toByteArray())) as HlsMultivariantPlaylist
        val variant = assertThrows(ParserException::class.java) {
            factory.createPlaylistParser(multivariant, null).parse(uri, ByteArrayInputStream(ts()))
        }
        assertNull(variant.findCause<HlsBodyIsTs>())
    }

    @Test
    fun `an endless TS body is refused after its head`() {
        var read = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = (if (read++ % 188 == 0L) 0x47 else 0)
        }
        assertThrows(ParserException::class.java) { LenientHlsPlaylistParserFactory().createPlaylistParser().parse(uri, endless) }
        assertTrue(read <= 512)
    }
}
