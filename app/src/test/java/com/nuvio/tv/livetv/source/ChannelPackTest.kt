package com.nuvio.tv.livetv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ChannelPackTest {

    private fun reader(text: String) = BufferedReader(StringReader(text))

    private val stateHeader = "url\tstate\tsince\tclass\tslots"
    private val streamsHeader = "name\turl\tcountry\tlanguage\tcategory\tkind\tformat\theaders\tgeo_hint\t" +
        "source_page\tnotes\treason\tseen_in\tchannel"

    private fun stateRow(url: String, state: String, cls: String = "ok") = "$url\t$state\t2026-11-08\t$cls\t00000000"

    private fun streamRow(
        name: String,
        url: String,
        country: String = "-",
        category: String = "-",
        headers: String = "-",
        channel: String = ""
    ) = listOf(name, url, country, "-", category, "stream", "-", headers, "-", "-", "-", "iptv-org-url", "x", channel)
        .joinToString("\t")

    private fun state(vararg rows: String) = ChannelPack.readState(reader((listOf(stateHeader) + rows).joinToString("\n")))

    private fun streams(state: Map<String, PackState>, vararg rows: String) =
        ChannelPack.readStreams(reader((listOf(streamsHeader) + rows).joinToString("\n")), state)

    @Test
    fun `state keeps alive, suspect and blocked, drops down, gone, drm, unknown states, YouTube and non-http`() {
        val kept = state(
            stateRow("https://a/1.m3u8", "alive"),
            stateRow("https://a/2.m3u8", "suspect", "down"),
            stateRow("https://a/3.m3u8", "blocked", "blocked"),
            stateRow("https://a/4.m3u8", "down", "down"),
            stateRow("https://a/5.m3u8", "gone", "gone"),
            stateRow("https://a/6.m3u8", "alive", "drm"),
            stateRow("https://a/7.m3u8", "weird"),
            stateRow("https://www.youtube.com/watch?v=x", "alive"),
            stateRow("https://youtu.be/x", "alive"),
            stateRow("rtmp://a/live", "alive"),
            "https://a/8.m3u8\talive" // wrong width: skipped
        )
        assertEquals(
            mapOf(
                "https://a/1.m3u8" to PackState.ALIVE,
                "https://a/2.m3u8" to PackState.SUSPECT,
                "https://a/3.m3u8" to PackState.BLOCKED
            ),
            kept
        )
    }

    @Test
    fun `a missing column refuses the file, an added one is fine`() {
        val missing = runCatching { ChannelPack.readState(reader("url\tstate\n")) }.exceptionOrNull()
        assertEquals(ChannelPack.FORMAT_CHANGED, (missing as SourceFetchException).message)
        val empty = runCatching { ChannelPack.readStreams(reader(""), emptyMap()) }.exceptionOrNull()
        assertEquals(ChannelPack.FORMAT_CHANGED, (empty as SourceFetchException).message)
        val wider = ChannelPack.readState(reader("extra\turl\tstate\tclass\nx\thttps://a/1.m3u8\talive\tok"))
        assertEquals(mapOf("https://a/1.m3u8" to PackState.ALIVE), wider)
    }

    @Test
    fun `rows with no kept state, a URL twice in one channel and a symbols-only name are not added`() {
        val kept = state(stateRow("https://a/1.m3u8", "alive"), stateRow("https://a/2.m3u8", "alive"))
        val pack = streams(
            kept,
            streamRow("One", "https://a/1.m3u8"),
            streamRow("One", "https://a/1.m3u8"),
            streamRow("NoState", "https://a/9.m3u8"),
            streamRow("!!!", "https://a/2.m3u8")
        )
        assertEquals(listOf("One"), pack.map { it.name })
        assertEquals(1, pack.single().streams.size)
    }

    @Test
    fun `rows merge by channel cell (feeds apart), else by name and country`() {
        val urls = (1..6).associate { "https://a/$it.m3u8" to PackState.ALIVE }
        val pack = streams(
            urls,
            streamRow("CBC News", "https://a/1.m3u8", "CA", "news", channel = "CBCNews.ca@SD"),
            streamRow("CBC News HD", "https://a/2.m3u8", "CA", channel = "CBCNews.ca@SD"),
            streamRow("CBC News", "https://a/3.m3u8", "CA", channel = "CBCNews.ca@HD"),
            streamRow("Def Jam", "https://a/4.m3u8"),
            streamRow("DEF JAM!", "https://a/5.m3u8"),
            streamRow("Def Jam", "https://a/6.m3u8", "US")
        )
        assertEquals(
            listOf("pack#ch:cbcnews.ca@sd", "pack#ch:cbcnews.ca@hd", "pack#nc:defjam|", "pack#nc:defjam|us"),
            pack.map { it.key }
        )
        val first = pack.first()
        assertEquals("CBC News", first.name)
        assertEquals("CBCNews.ca@SD", first.tvgId)
        assertEquals("ca", first.country)
        assertEquals(listOf("news"), first.groups)
        assertEquals(listOf("https://a/1.m3u8", "https://a/2.m3u8"), first.streams.map { it.url })
        assertEquals(2, pack[2].streams.size)
    }

    @Test
    fun `provider ids are keys but not tvg-ids, GB and UK are one country`() {
        val urls = (1..3).associate { "https://a/$it.m3u8" to PackState.ALIVE }
        val pack = streams(
            urls,
            streamRow("Hot Ones", "https://a/1.m3u8", "US", channel = "samsung:us2900006iv"),
            streamRow("BBC", "https://a/2.m3u8", "GB"),
            streamRow("BBC", "https://a/3.m3u8", "UK")
        )
        assertNull(pack[0].tvgId)
        assertEquals("pack#ch:samsung:us2900006iv", pack[0].key)
        assertEquals(listOf("pack#nc:bbc|uk"), pack.drop(1).map { it.key })
        assertEquals("uk", pack[1].country)
    }

    @Test
    fun `headers keep only Referer, User-Agent and Origin as strings`() {
        val urls = (1..3).associate { "https://a/$it.m3u8" to PackState.ALIVE }
        val pack = streams(
            urls,
            streamRow("A", "https://a/1.m3u8", headers = """{"referer":"https://r/","User-Agent":"UA","Cookie":"x","Origin":5}"""),
            streamRow("B", "https://a/2.m3u8", headers = "not json"),
            streamRow("C", "https://a/3.m3u8", headers = """{"Cookie":"x"}""")
        )
        assertEquals(mapOf("Referer" to "https://r/", "User-Agent" to "UA"), pack[0].streams.single().headers)
        assertEquals(pack[0].streams.single().headers, ChannelPack.listing(pack)[0].streams.single().headers)
        assertNull(pack[1].streams.single().headers)
        assertNull(pack[2].streams.single().headers)
    }

    @Test
    fun `listing keeps alive streams only and leaves out channels with none`() {
        val pack = streams(
            mapOf(
                "https://a/1.m3u8" to PackState.SUSPECT,
                "https://a/2.m3u8" to PackState.ALIVE,
                "https://a/3.m3u8" to PackState.BLOCKED
            ),
            streamRow("One", "https://a/1.m3u8", channel = "One.ca"),
            streamRow("One", "https://a/2.m3u8", channel = "One.ca"),
            streamRow("Blocked", "https://a/3.m3u8", "CA")
        )
        assertEquals(2, pack.size)
        val listed = ChannelPack.listing(pack)
        assertEquals(listOf("pack#ch:one.ca"), listed.map { it.key })
        assertEquals(listOf("https://a/2.m3u8"), listed.single().streams.map { it.url })
        assertEquals(ChannelPack.SOURCE_ID, listed.single().sourceId)
    }

    @Test
    fun `summary reads version 1 numbers and nothing else`() {
        assertEquals(
            PackSummary(21037, 177, 1_600_000),
            ChannelPack.readSummary("""{"version":1,"channels":21037,"countries":177,"bytes":1600000,"extra":"x"}""")
        )
        assertNull(ChannelPack.readSummary("""{"version":2,"channels":1,"countries":1,"bytes":1}"""))
        assertNull(ChannelPack.readSummary("""{"version":1,"channels":"21037","countries":177,"bytes":1}"""))
        assertNull(ChannelPack.readSummary("""{"version":1,"channels":0,"countries":0,"bytes":1}"""))
        assertNull(ChannelPack.readSummary("<html>404</html>"))
        assertNull(ChannelPack.readSummary("{\"a\":" + "[".repeat(200) + "]".repeat(200) + "}"))
    }

    @Test
    fun `listing counts a scanned stream by its result, any other by its monitor state`() {
        val channel = PackChannel(
            key = "pack#ch:a.ca",
            name = "A",
            streams = listOf(
                PackStream("https://a/1.m3u8", PackState.ALIVE),
                PackStream("https://a/2.m3u8", PackState.SUSPECT),
                PackStream("https://a/3.m3u8", PackState.ALIVE)
            )
        )
        fun listed(results: Map<String, PackScanResult>) =
            ChannelPack.listing(listOf(channel), results).singleOrNull()?.streams?.map { it.url }
        assertEquals(listOf("https://a/1.m3u8", "https://a/3.m3u8"), listed(emptyMap()))
        val proved = mapOf(
            ChannelPack.scanKey("https://a/1.m3u8") to PackScanResult(false, 1),
            ChannelPack.scanKey("https://a/2.m3u8") to PackScanResult(true, 1)
        )
        assertEquals(listOf("https://a/2.m3u8", "https://a/3.m3u8"), listed(proved))
        val failed = channel.streams.associate { ChannelPack.scanKey(it.url) to PackScanResult(false, 1) }
        assertNull(listed(failed))
    }

    @Test
    fun `a scan key is the first 16 hex digits of the URL's SHA-256`() {
        assertEquals("ba7816bf8f01cfea", ChannelPack.scanKey("abc"))
    }
}
