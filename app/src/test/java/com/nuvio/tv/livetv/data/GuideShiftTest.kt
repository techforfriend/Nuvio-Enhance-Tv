package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.parse.EpgChannel
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Spec 94 T2: tvg-shift parsing, precedence and the shifted guide reads. */
class GuideShiftTest {

    private val hour = 3_600_000L

    @Test
    fun `parses plain decimal hours and rejects anything else`() {
        assertEquals(2 * hour, GuideShift.parseMs("2"))
        assertEquals(2 * hour, GuideShift.parseMs(" +2 "))
        assertEquals(-(4 * hour + hour / 2), GuideShift.parseMs("-4.5"))
        assertEquals(hour * 5 + 45 * 60_000L, GuideShift.parseMs("5.75"))
        assertEquals(0L, GuideShift.parseMs("0"))
        assertEquals(24 * hour, GuideShift.parseMs("24"))
        assertEquals(-24 * hour, GuideShift.parseMs("-24"))
        // Kodi's atof forms: a bare fraction, a trailing point, long decimals.
        assertEquals(hour / 2, GuideShift.parseMs(".5"))
        assertEquals(2 * hour, GuideShift.parseMs("2."))
        assertEquals(hour + hour / 4, GuideShift.parseMs("1.250000"))
        for (bad in listOf(null, "", " ", ".", "+", "24.5", "-24.25", "25", "-30", "NaN", "Infinity", "3d", "0x10", "1e2", "2h", "1,5", "123")) {
            assertNull(bad, GuideShift.parseMs(bad))
        }
    }

    private fun ch(attrs: Map<String, String>?) = Channel(key = "k", sourceId = "s", name = "n", streams = emptyList(), attrs = attrs)

    @Test
    fun `a channel's own value wins over the header, an explicit zero included`() {
        assertEquals(-hour, GuideShift.of(ch(mapOf("tvg-shift" to "-1")), 3 * hour))
        assertEquals(0L, GuideShift.of(ch(mapOf("tvg-shift" to "0")), 3 * hour))
        assertEquals(3 * hour, GuideShift.of(ch(null), 3 * hour))
        assertEquals(3 * hour, GuideShift.of(ch(mapOf("tvg-shift" to "junk")), 3 * hour))
        assertEquals(
            mapOf("a" to 2 * hour),
            GuideShift.headerShifts(mapOf("a" to mapOf("tvg-shift" to "2"), "b" to mapOf("tvg-shift" to "0"), "c" to null, "d" to emptyMap()))
        )
    }

    @Test
    fun `the snapshot reads a channel's programmes and now-next shifted`() {
        val first = EpgProgramme(0, hour, "First")
        val second = EpgProgramme(hour, 2 * hour, "Second")
        val s = EpgSnapshot(
            channels = mapOf("g" to EpgChannel("g", emptyList(), null)),
            programmes = mapOf("g" to listOf(first, second)),
            windowStartMs = 0,
            windowEndMs = 6 * hour,
            complete = true
        )
        assertSame(s.programmes["g"], s.programmesOf("g"))
        assertEquals(listOf(hour to 2 * hour, 2 * hour to 3 * hour), s.programmesOf("g", hour)!!.map { it.startMs to it.stopMs })
        assertNull(s.programmesOf("other", hour))
        // At 1:30 with +1 h, the guide's 0:30 is read: First (now 1:00-2:00), Second next (2:00-3:00).
        val (now, next) = s.nowNext("g", hour + hour / 2, hour)
        assertEquals(first.copy(startMs = hour, stopMs = 2 * hour), now)
        assertEquals(second.copy(startMs = 2 * hour, stopMs = 3 * hour), next)
        assertEquals(first to second, s.nowNext("g", hour / 2))
    }
}
