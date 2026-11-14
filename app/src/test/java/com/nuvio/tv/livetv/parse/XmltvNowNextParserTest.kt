package com.nuvio.tv.livetv.parse

import com.nuvio.tv.data.local.localStoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.zip.GZIPOutputStream

class XmltvNowNextParserTest {
    private val parser = XmltvNowNextParser(newParser = { KXmlParser() })
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()

    // epg.pw style: numeric ids, +0000 offsets. Programmes deliberately unsorted (free-epg.de is not sorted).
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="test">
          <channel id="542998"><display-name lang="bn">Star Jalsha</display-name><display-name>Star Jalsha HD</display-name><icon src="https://example.org/sj.png"/></channel>
          <channel id="DDBangla.in"><display-name>DD Bangla</display-name></channel>
          <programme start="20261019130000 +0000" stop="20261019140000 +0000" channel="542998"><title>Next show</title><category>Drama</category><live/></programme>
          <programme start="20261019113000 +0000" stop="20261019130000 +0000" channel="542998"><title lang="bn">Current show</title><desc>About it</desc></programme>
          <programme start="20261019080000 +0000" stop="20261019090000 +0000" channel="542998"><title>Past</title></programme>
          <programme start="20261020120000 +0000" stop="20261020130000 +0000" channel="542998"><title>Tomorrow</title></programme>
          <programme start="20261019173000 +0530" stop="20261019183000 +0530" channel="DDBangla.in"><title>News at 5:30 IST</title></programme>
        </tv>
    """.trimIndent()

    @Test
    fun `a snapshot cached with desc still decodes (Spec 38 S1 dropped it)`() {
        val old = """{"channels":{},"programmes":{"1":[{"startMs":0,"stopMs":1,"title":"T","desc":"D","category":"C","live":true}]},""" +
            """"windowStartMs":0,"windowEndMs":1,"complete":true}"""
        val s = localStoreJson.decodeFromString(EpgSnapshot.serializer(), old)
        assertEquals(listOf(EpgProgramme(0, 1, "T", category = "C", live = true)), s.programmes["1"])
    }

    @Test
    fun `unknown and nested programme children are skipped without losing the title or live flag`() {
        val doc = """
            <tv>
              <programme start="20261019113000 +0000" stop="20261019130000 +0000" channel="c">
                <credits><actor>A <b>bold</b> name</actor><director/></credits>
                <icon src="https://example.org/i.png"/>
                <title>Kept</title><title>Second title ignored</title>
                <desc>Dropped <i>text</i></desc>
                <live/>
              </programme>
            </tv>
        """.trimIndent()
        val s = parser.parse(doc.byteInputStream(), now)
        assertTrue(s.complete)
        assertEquals(listOf(EpgProgramme(Instant.parse("2026-10-19T11:30:00Z").toEpochMilli(),
            Instant.parse("2026-10-19T13:00:00Z").toEpochMilli(), "Kept", live = true)), s.programmes["c"])
    }

    @Test
    fun `channels and the look-ahead window are kept, sorted, outside programmes dropped`() {
        val s = parser.parse(xml.byteInputStream(), now)
        assertTrue(s.complete)
        assertEquals(listOf("Star Jalsha", "Star Jalsha HD"), s.channels.getValue("542998").displayNames)
        assertEquals("https://example.org/sj.png", s.channels.getValue("542998").icon)
        // "Tomorrow" is the first programme after the 6 h window; "Past" ended before now.
        assertEquals(listOf("Current show", "Next show", "Tomorrow"), s.programmes.getValue("542998").map { it.title })
        val (cur, next) = s.nowNext("542998", now)
        assertEquals("Current show", cur?.title)
        assertEquals("Next show", next?.title)
        assertEquals("Drama", next?.category)
        assertTrue(next!!.live)
        assertFalse(cur!!.live)
    }

    @Test
    fun `offsets are honoured`() {
        // 17:30 +0530 = 12:00 UTC.
        val s = parser.parse(xml.byteInputStream(), now)
        assertEquals("News at 5:30 IST", s.nowNext("DDBangla.in", now).first?.title)
    }

    @Test
    fun `now-next re-derives from the window as time passes`() {
        val s = parser.parse(xml.byteInputStream(), now)
        val later = Instant.parse("2026-10-19T13:30:00Z").toEpochMilli()
        val (cur, next) = s.nowNext("542998", later)
        assertEquals("Next show", cur?.title)
        assertEquals("Tomorrow", next?.title)
    }

    @Test
    fun `keepChannel filters programmes but not channels`() {
        val s = parser.parse(xml.byteInputStream(), now, keepChannel = { it == "DDBangla.in" })
        assertEquals(setOf("DDBangla.in"), s.programmes.keys)
        assertEquals(2, s.channels.size)
    }

    @Test
    fun `gzip detected by magic bytes, plain xml passes through`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }
        val s = parser.parse(ByteArrayInputStream(gz.toByteArray()), now)
        assertEquals(2, s.channels.size)
        assertTrue(s.complete)
    }

    @Test(expected = IOException::class)
    fun `inflated size cap throws`() {
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("<a>".repeat(64 * 1024).toByteArray()) }
        }
        XmltvNowNextParser.openGuarded(ByteArrayInputStream(gz.toByteArray()), maxBytes = 1024).readBytes()
    }

    @Test
    fun `size cap ends the parse incomplete, gzip and plain alike, keeping what was read`() {
        val big = buildString {
            append("<tv><channel id=\"a\"><display-name>A</display-name></channel>")
            repeat(5_000) { append("<programme channel=\"a\" start=\"20261019113000\" stop=\"20261019130000\"><title>T</title></programme>") }
            append("</tv>")
        }
        val capped = XmltvNowNextParser({ KXmlParser() }, maxInputBytes = big.length / 2L)
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(big.toByteArray()) } }
        for (input in listOf(big.byteInputStream(), ByteArrayInputStream(gz.toByteArray()))) {
            val s = capped.parse(input, now)
            assertFalse(s.complete)
            assertEquals(setOf("a"), s.channels.keys)
            assertTrue(s.programmes.getValue("a").isNotEmpty())
        }
        assertTrue(XmltvNowNextParser({ KXmlParser() }, maxInputBytes = big.length.toLong()).parse(big.byteInputStream(), now).complete)
    }

    @Test
    fun `a giant text node is refused before the parser materialises it`() {
        val huge = "<tv><channel id=\"x\"><display-name>" + "a".repeat(XmltvNowNextParser.MAX_TEXT_RUN_BYTES + 1) +
            "</display-name></channel></tv>"
        val s = parser.parse(huge.byteInputStream(), now)
        assertFalse(s.complete)
        assertTrue(s.channels.isEmpty())
    }

    @Test
    fun `mixed content in title is read as text and does not abort the file`() {
        val mixed = """
            <tv>
              <programme start="20261019113000 +0000" stop="20261019130000 +0000" channel="c"><title>Live <b>now</b> on air</title></programme>
              <channel id="after"><display-name>After</display-name></channel>
            </tv>
        """.trimIndent()
        val s = parser.parse(mixed.byteInputStream(), now)
        assertTrue(s.complete)
        assertEquals("Live now on air", s.nowNext("c", now).first?.title)
        assertTrue("after" in s.channels)
    }

    @Test
    fun `the first programme after the window is kept so the last in-window one has a next`() {
        // Window ends 12:30: "Current show" runs past it and "Next show" starts at 13:00.
        val s = parser.parse(xml.byteInputStream(), now, lookAheadMs = 30L * 60 * 1000)
        assertEquals(listOf("Current show", "Next show"), s.programmes.getValue("542998").map { it.title })
        assertEquals("Next show", s.nowNext("542998", now).second?.title)
    }

    @Test
    fun `per-channel cap keeps the earliest programmes of an unsorted feed`() {
        val cap = XmltvNowNextParser.MAX_PROGRAMMES_PER_CHANNEL
        val body = buildString {
            append("<tv>")
            // Latest first: a file-order cap would keep the wrong end and lose the current slot.
            for (i in cap + 9 downTo 0) {
                val start = now + i * 60_000L
                append("<programme channel=\"c\" start=\"${xt(start)}\" stop=\"${xt(start + 60_000)}\"><title>P$i</title></programme>")
            }
            append("</tv>")
        }
        val list = parser.parse(body.byteInputStream(), now).programmes.getValue("c")
        assertEquals(cap, list.size)
        assertEquals("P0", list.first().title)
        assertEquals("P${cap - 1}", list.last().title)
    }

    @Test
    fun `a larger cap binds past the default and a capped channel keeps no first-after-window`() {
        val slot = 30 * 60_000L
        val body = buildString {
            append("<tv>")
            for (i in 0 until 100) {
                val start = now + i * slot
                append("<programme channel=\"c\" start=\"${xt(start)}\" stop=\"${xt(start + slot)}\"><title>P$i</title></programme>")
            }
            append("</tv>")
        }
        // 96 half-hour slots in the 48 h window; a cap of 60 (above the default 48) binds.
        val day = XmltvNowNextParser(newParser = { KXmlParser() }, maxProgrammesPerChannel = 60)
        val list = day.parse(body.byteInputStream(), now, lookAheadMs = 48 * 60 * 60_000L).programmes.getValue("c")
        // P96 (first after the window) would read as P59's next across the dropped tail.
        assertEquals((0 until 60).map { "P$it" }, list.map { it.title })

        fun titles(order: List<Int>): List<String> {
            val xml = buildString {
                append("<tv>")
                for (i in order) {
                    val start = now + i * slot
                    append("<programme channel=\"c\" start=\"${xt(start)}\" stop=\"${xt(start + slot)}\"><title>P$i</title></programme>")
                }
                append("</tv>")
            }
            return day.parse(xml.byteInputStream(), now, lookAheadMs = 48 * 60 * 60_000L).programmes.getValue("c").map { it.title }
        }
        // Exactly the cap in the window is not capped: the next after it stays.
        assertEquals((0 until 60).map { "P$it" } + "P96", titles((0 until 60) + 96))
        // After-window first, then the cap binds by eviction (latest first): dropped all the same.
        assertEquals((0 until 60).map { "P$it" }, titles((99 downTo 0).toList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive cap is refused at construction`() {
        XmltvNowNextParser(newParser = { KXmlParser() }, maxProgrammesPerChannel = 0)
    }

    @Test
    fun `distinct programme channel keys are capped`() {
        val body = buildString {
            append("<tv>")
            repeat(XmltvNowNextParser.MAX_CHANNELS + 5) {
                append("<programme channel=\"c$it\" start=\"20261019113000\" stop=\"20261019130000\"/>")
            }
            append("</tv>")
        }
        assertEquals(XmltvNowNextParser.MAX_CHANNELS, parser.parse(body.byteInputStream(), now).programmes.size)
    }

    @Test
    fun `boundaries - ended at now, zero length, starting at window end`() {
        val body = """
            <tv>
              <programme channel="c" start="20261019110000" stop="20261019120000"><title>Ended</title></programme>
              <programme channel="c" start="20261019120000" stop="20261019120000"><title>Zero</title></programme>
              <programme channel="c" start="20261019120000" stop="20261019130000"><title>Now</title></programme>
              <programme channel="c" start="20261019190000" stop="20261019200000"><title>Later</title></programme>
              <programme channel="c" start="20261019180000" stop="20261019190000"><title>AtEnd</title></programme>
            </tv>
        """.trimIndent()
        val list = parser.parse(body.byteInputStream(), now).programmes.getValue("c")
        assertEquals(listOf("Now", "AtEnd"), list.map { it.title })
    }

    @Test
    fun `real epg pw file, gzip, trimmed to two channels`() {
        val at = Instant.parse("2026-10-19T02:15:00Z").toEpochMilli()
        val s = javaClass.getResourceAsStream("/livetv/epg-pw-in-trimmed.xml.gz")!!.use { parser.parse(it, at) }
        assertTrue(s.complete)
        assertEquals(listOf("ZEE BANGLA HD"), s.channels.getValue("404001").displayNames)
        assertEquals(listOf("STAR JALSHA"), s.channels.getValue("542998").displayNames)
        assertNull(s.channels.getValue("542998").icon) // epg.pw ships icon src=""
        val (cur, next) = s.nowNext("542998", at)
        assertEquals("Bigg Boss Bangla", cur?.title)
        assertEquals("Ajmeri Gems House", next?.title)
        assertTrue(s.nowNext("404001", at).first != null)
    }

    private fun xt(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(ms)) + " +0000"

    @Test
    fun `truncated file keeps what was read`() {
        val cut = xml.substring(0, xml.indexOf("<programme start=\"20261019080000"))
        val s = parser.parse(cut.byteInputStream(), now)
        assertFalse(s.complete)
        assertEquals(2, s.programmes.getValue("542998").size)
    }

    @Test
    fun `an entity declaration ends the parse before any parser sees it`() {
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE tv [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]>
            <tv><channel id="x"><display-name>&c;</display-name></channel></tv>
        """.trimIndent()
        // The guard sits in the input stream, so this holds for Android's libcore parser too.
        val s = parser.parse(bomb.byteInputStream(), now)
        assertFalse(s.complete)
        assertTrue(s.channels.isEmpty())
    }

    @Test
    fun `an external DOCTYPE without entities still parses`() {
        val withDoctype = xml.replace("<tv ", "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv ")
        assertTrue(parser.parse(withDoctype.byteInputStream(), now).complete)
    }

    @Test
    fun `xmltv time forms`() {
        val base = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("20261019120000 +0000"))
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("20261019120000"))
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("202610191200"))
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("20261019070000 -0500"))
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("20261019173000 +05:30"))
        assertNull(XmltvNowNextParser.parseXmltvTime("20261319120000")) // month 13
        assertEquals(base, XmltvNowNextParser.parseXmltvTime("20261019120000 GMT")) // unreadable offset = UTC
        assertNull(XmltvNowNextParser.parseXmltvTime("garbage"))
        assertNull(XmltvNowNextParser.parseXmltvTime(null))
    }
}
