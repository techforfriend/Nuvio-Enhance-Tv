package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.parse.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LiveTvScheduleTest {
    private val zone = ZoneId.of("America/Edmonton")
    private fun at(day: Int, h: Int, m: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun p(day: Int, h: Int, m: Int, mins: Int, title: String) =
        EpgProgramme(at(day, h, m), at(day, h, m) + mins * 60_000L, title)

    // Sun 8 Nov, now 20:14 local (Spec 67 G2 mockup, WION / CBS News 24/7).
    private val now = at(8, 20, 14)

    @Test
    fun `identical programmes show once and No Data filler is a gap`() {
        val list = listOf(
            p(8, 21, 0, 30, "CBS Weekend News"), p(8, 21, 0, 30, "CBS Weekend News"),
            p(8, 20, 0, 60, "60 Minutes"), p(8, 20, 0, 60, "60 Minutes"),
            p(8, 21, 30, 30, " no data ")
        )
        assertEquals(listOf("60 Minutes", "CBS Weekend News"), LiveTvSchedule.cleaned(list).map { it.title })
    }

    @Test
    fun `same start but a different programme is kept`() {
        val list = listOf(p(8, 20, 0, 30, "World DNA"), p(8, 20, 0, 60, "World DNA"), p(8, 20, 0, 30, "Other"))
        assertEquals(3, LiveTvSchedule.cleaned(list).size)
    }

    @Test
    fun `today opens on the airing programme with two earlier rows above`() {
        val list = (0 until 48).map { i -> p(8, i / 2, (i % 2) * 30, 30, "P$i") }
        val today = LiveTvSchedule.day(list, now, zone, 0)
        assertEquals(48, today.items.size)
        assertEquals(40, today.focusIndex) // 20:00-20:30
        assertEquals(38, today.firstVisible)
        val airing = today.items[40]
        assertEquals(ScheduleTime.NOW, airing.time)
        assertEquals(14f / 30, airing.progress!!, 0.001f)
        assertTrue(today.items.take(40).all { it.time == ScheduleTime.PAST && it.progress == null })
        assertTrue(today.items.drop(41).all { it.time == ScheduleTime.LATER })
        assertNull(today.guideEndsMs) // data runs to midnight
    }

    @Test
    fun `tomorrow starts at local midnight, nothing past, focus on its first row`() {
        val list = listOf(p(8, 23, 0, 60, "Face the Nation"), p(9, 0, 0, 60, "Roundup 1"), p(9, 1, 0, 60, "Roundup 2"), p(9, 2, 0, 60, "Roundup 3"))
        val tomorrow = LiveTvSchedule.day(list, now, zone, 1)
        assertEquals(at(9, 0), tomorrow.startMs)
        assertEquals(listOf("Roundup 1", "Roundup 2", "Roundup 3"), tomorrow.items.map { it.programme.title })
        assertTrue(tomorrow.items.all { it.time == ScheduleTime.LATER })
        assertEquals(0, tomorrow.focusIndex)
        assertEquals(0, tomorrow.firstVisible)
        assertEquals(at(9, 3), tomorrow.guideEndsMs) // Pluto stops at 03:00 (stage 5)
    }

    @Test
    fun `a guide with nothing for tomorrow gives an empty, dimmed day without an end line`() {
        val tomorrow = LiveTvSchedule.day(listOf(p(8, 20, 0, 60, "Now")), now, zone, 1)
        assertTrue(tomorrow.empty)
        assertNull(tomorrow.guideEndsMs)
    }

    @Test
    fun `today keeps a programme airing across midnight from yesterday, not one that ended`() {
        val late = at(9, 0, 30)
        val list = listOf(p(8, 23, 0, 20, "Ended before"), p(8, 23, 50, 35, "Crossed and ended"),
            p(8, 23, 0, 120, "Across midnight"), p(9, 1, 0, 30, "After"))
        val today = LiveTvSchedule.day(list, late, zone, 0)
        assertEquals(listOf("Across midnight", "After"), today.items.map { it.programme.title })
        assertEquals(ScheduleTime.NOW, today.items[0].time)
        assertEquals(0, today.focusIndex)
    }

    @Test
    fun `a programme crossing midnight is listed under the day it starts, not the next`() {
        val list = listOf(p(8, 23, 0, 120, "Late film"), p(9, 1, 0, 30, "After"))
        assertEquals(listOf("Late film"), LiveTvSchedule.day(list, now, zone, 0).items.map { it.programme.title })
        assertEquals(listOf("After"), LiveTvSchedule.day(list, now, zone, 1).items.map { it.programme.title })
    }

    @Test
    fun `now in a gap opens on the first programme still to come`() {
        val list = listOf(p(8, 19, 0, 60, "Before"), p(8, 21, 0, 30, "After the gap"), p(8, 21, 30, 30, "Later"))
        val today = LiveTvSchedule.day(list, now, zone, 0)
        assertEquals(1, today.focusIndex)
        assertEquals(ScheduleTime.LATER, today.items[1].time)
        assertTrue(today.items.none { it.time == ScheduleTime.NOW })
    }

    @Test
    fun `overlapping programmes airing at once are both now, focus on the earlier`() {
        val list = listOf(p(8, 20, 0, 60, "Long"), p(8, 20, 10, 20, "Short"))
        val today = LiveTvSchedule.day(list, now, zone, 0)
        assertEquals(listOf(ScheduleTime.NOW, ScheduleTime.NOW), today.items.map { it.time })
        assertEquals(0, today.focusIndex)
    }

    @Test
    fun `when every programme has ended the list opens on the last and says where the guide ends`() {
        val list = listOf(p(8, 18, 0, 60, "A"), p(8, 19, 0, 60, "B"))
        val today = LiveTvSchedule.day(list, now, zone, 0)
        assertEquals(1, today.focusIndex)
        assertEquals(at(8, 20), today.guideEndsMs)
    }

    @Test
    fun `no programmes at all`() {
        val today = LiveTvSchedule.day(emptyList(), now, zone, 0)
        assertTrue(today.empty)
        assertEquals(0, today.focusIndex)
        assertNull(today.guideEndsMs)
    }

    @Test
    fun `days follow the zone across a DST change (Toronto falls back 1 Nov 2026)`() {
        // Not Edmonton: current tzdata keeps Alberta on UTC-6 from 2026, so it has no fall-back.
        val toronto = ZoneId.of("America/Toronto")
        val sun = LocalDateTime.of(2026, 11, 1, 12, 0).atZone(toronto).toInstant().toEpochMilli()
        val day = LiveTvSchedule.day(emptyList(), sun, toronto, 0)
        assertEquals(25L * 60 * 60 * 1000, day.endMs - day.startMs)
    }

    private fun sched(openId: Int): LiveSchedule {
        val day = LiveTvSchedule.day(emptyList(), now, zone, 0)
        val row = LiveRow(com.nuvio.tv.livetv.model.Channel(key = "k", sourceId = "a", name = "K", streams = emptyList()), "1", null, null, true, null)
        return LiveSchedule(openId, row, null, day, day, now)
    }

    @Test
    fun `open state shows only its own open, closes on data loss once shown, cancels a pending open`() {
        val st = ScheduleOpenState()
        assertNull(st.shown(sched(1)))                     // nothing opened: a stale derivation never shows
        st.opened(2, "k")
        assertNull(st.shown(sched(1)))                     // an older open's data
        assertTrue(st.pendingOn("k", shown = false))       // focus left its row before data: cancel
        assertFalse(st.pendingOn("other", shown = false))
        assertFalse(st.lostData(shown = false))            // still pending: not a loss
        assertEquals(2, st.shown(sched(2))!!.openId)
        assertFalse(st.lostData(shown = true))
        assertFalse(st.pendingOn("k", shown = true))       // shown: the row losing focus to the column is normal
        assertTrue(st.lostData(shown = false))             // shown, then gone: close
        assertEquals("k", st.close())
        assertFalse(st.isOpen)
        assertFalse(st.lostData(shown = false))
        st.opened(3, "k")
        assertFalse(st.lostData(shown = false))            // a new open starts pending again
    }

    @Test
    fun `refocus after the focused row left goes by start time, else last row, else the chip`() {
        val list = listOf(p(8, 20, 0, 30, "A"), p(8, 20, 30, 30, "B"), p(8, 21, 0, 30, "C"))
        val day = LiveTvSchedule.day(list, now, zone, 0)
        // B had focus and went: the row now starting at or after it (C) takes focus.
        val withoutB = LiveTvSchedule.day(list - list[1], now, zone, 0)
        assertEquals(1, LiveTvSchedule.refocusIndex(withoutB, at(8, 20, 30)))
        // Rows above leaving do not move the target: C stays C.
        assertEquals(1, LiveTvSchedule.refocusIndex(LiveTvSchedule.day(list.drop(1), now, zone, 0), at(8, 21)))
        assertEquals(2, LiveTvSchedule.refocusIndex(day, at(8, 23)))          // past the end: last row
        assertEquals(day.focusIndex, LiveTvSchedule.refocusIndex(day, null))  // never focused: opening row
        assertEquals(-1, LiveTvSchedule.refocusIndex(LiveTvSchedule.day(emptyList(), now, zone, 0), at(8, 20)))
    }
}
