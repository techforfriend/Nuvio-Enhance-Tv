package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.ui.LiveTvGuideGrid.SLOT_MS
import com.nuvio.tv.livetv.ui.LiveTvGuideGrid.STRIP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LiveTvGuideGridTest {
    private val zone = ZoneId.of("America/Edmonton")
    private fun at(day: Int, h: Int, m: Int = 0, z: ZoneId = zone): Long =
        LocalDateTime.of(2026, 11, day, h, m).atZone(z).toInstant().toEpochMilli()
    private fun p(day: Int, h: Int, m: Int, mins: Int, title: String) =
        EpgProgramme(at(day, h, m), at(day, h, m) + mins * 60_000L, title)

    // Sun 8 Nov, now 20:14 local (Spec 67 G3 mockup v2).
    private val now = at(8, 20, 14)
    private val bounds = LiveTvGuideGrid.bounds(now, zone)

    // WION, free-epg.de India, Sun 19:00 -> Mon 00:30 (mockup data).
    private val wionRaw =
        listOf(
            p(8, 19, 0, 30, "Top Stories"), p(8, 19, 30, 30, "Top Stories"), p(8, 20, 0, 30, "World DNA"),
            p(8, 20, 30, 30, "World DNA"), p(8, 21, 0, 30, "Speed News"), p(8, 21, 30, 30, "Top Stories"),
            p(8, 22, 0, 30, "World Business Watch"), p(8, 22, 30, 30, "Top Stories"), p(8, 23, 0, 30, "The West Asia Post"),
            p(8, 23, 30, 30, "Newspoint"), p(9, 0, 0, 30, "Speed News")
        )
    private val wion = LiveTvGuideGrid.layout(wionRaw, bounds)

    @Test
    fun `bounds run from local midnight today to the end of tomorrow, the strip opens on now's half hour`() {
        assertEquals(GuideBounds(at(8, 0), at(10, 0)), bounds)
        assertEquals(at(8, 20), LiveTvGuideGrid.nowStrip(now, bounds))
        assertEquals(at(9, 0), LiveTvGuideGrid.tomorrowStrip(now, zone, bounds))
    }

    @Test
    fun `half hours count from local midnight in a 45-minute zone and across a DST day`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // +5:45: epoch half hours fall at :15/:45 local
        val k = LiveTvGuideGrid.bounds(at(8, 20, 14, kathmandu), kathmandu)
        assertEquals(at(8, 20, 0, kathmandu), LiveTvGuideGrid.nowStrip(at(8, 20, 14, kathmandu), k))
        // New York falls back on Sun 1 Nov 2026: a 25 h day, the window is 49 h, 20:00 stays 20:00.
        val ny = ZoneId.of("America/New_York")
        val dst = LiveTvGuideGrid.bounds(at(1, 20, 14, ny), ny)
        assertEquals(49 * 60 * 60_000L, dst.endMs - dst.startMs)
        assertEquals(at(1, 20, 0, ny), LiveTvGuideGrid.nowStrip(at(1, 20, 14, ny), dst))
        // Spring forward, Sun 8 Mar 2026: a 23 h day, a 47 h window, still :00/:30.
        val march = { h: Int, m: Int -> LocalDateTime.of(2026, 3, 8, h, m).atZone(ny).toInstant().toEpochMilli() }
        val spring = LiveTvGuideGrid.bounds(march(20, 14), ny)
        assertEquals(47 * 60 * 60_000L, spring.endMs - spring.startMs)
        assertEquals(march(20, 0), LiveTvGuideGrid.nowStrip(march(20, 14), spring))
        assertEquals(march(1, 30), LiveTvGuideGrid.slotFloor(march(1, 59), spring))
        assertEquals(march(3, 0), LiveTvGuideGrid.slotFloor(march(3, 10), spring)) // 02:00-03:00 never happens
    }

    @Test
    fun `the strip never leaves the bounds`() {
        // Late tomorrow (bounds kept from today): the last strip ends at the window's end.
        assertEquals(at(9, 22), LiveTvGuideGrid.nowStrip(at(9, 23, 50), bounds))
        assertEquals(bounds.startMs, LiveTvGuideGrid.clampStrip(bounds.startMs - SLOT_MS, bounds))
    }

    @Test
    fun `layout drops what is outside the bounds and lets no programme overlap`() {
        val slots = LiveTvGuideGrid.layout(
            listOf(
                p(7, 22, 0, 60, "Yesterday"), p(7, 23, 30, 60, "Across midnight"),
                p(8, 20, 0, 60, "A"), p(8, 20, 30, 60, "B"), p(8, 20, 40, 10, "Inside B"),
                p(8, 20, 0, 60, "A"), p(10, 0, 0, 30, "After tomorrow")
            ),
            bounds
        )
        assertEquals(listOf("Across midnight", "A", "B"), slots.map { it.programme.title })
        val b = slots[2]
        assertEquals(at(8, 21), b.startMs) // starts when A ends
        assertEquals(at(8, 21, 30), b.endMs)
    }

    @Test
    fun `layout drops programmes ending at the window start, empty ones, and ones pushed past its end`() {
        val zero = EpgProgramme(at(8, 12), at(8, 12), "Zero")
        val negative = EpgProgramme(at(8, 13), at(8, 12), "Backwards")
        val slots = LiveTvGuideGrid.layout(
            listOf(
                p(7, 23, 0, 60, "Ends at midnight"), zero, negative,
                // Overlapping across the window's end: Y would start Tue 02:00, outside the bounds.
                p(9, 23, 0, 180, "X"), p(9, 23, 30, 210, "Y")
            ),
            bounds
        )
        assertEquals(listOf("X"), slots.map { it.programme.title })
        // Right from X: no next programme (Y is gone) and 22:00 is already the last strip, so focus stays on X.
        val move = LiveTvGuideGrid.horizontal(slots, GuideFocus(at(9, 23), at(9, 23)), at(9, 22), 1, bounds)
        assertNull(move)
    }

    @Test
    fun `a programme longer than the strip scrolls through both ways with the cursor on its drawn part`() {
        val row = LiveTvGuideGrid.layout(listOf(p(8, 20, 0, 180, "Marathon")), bounds)
        fun walk(step: Int): List<Pair<Long, Long>> {
            var move = GuideMove(at(8, 20), GuideFocus(at(8, 20), at(8, 20)))
            val seen = mutableListOf<Pair<Long, Long>>()
            while (true) {
                move = LiveTvGuideGrid.horizontal(row, move.focus, move.stripStartMs, step, bounds) ?: break
                assertEquals(at(8, 20), move.focus.slotStartMs)
                seen += move.stripStartMs to move.focus.cursorMs
            }
            return seen
        }
        // Right: the cursor follows the strip's start (inside the drawn part), up to the strip that still shows its end.
        assertEquals(listOf(20 to 30, 21 to 0, 21 to 30, 22 to 0, 22 to 30).map { (h, m) -> at(8, h, m) to at(8, h, m) }, walk(1))
        // Left: no earlier programme, the strip steps back while the start is still drawn; the cursor stays at 20:00.
        assertEquals(listOf(at(8, 19, 30), at(8, 19), at(8, 18, 30)).map { it to at(8, 20) }, walk(-1))
    }

    @Test
    fun `equal starts keep the longer programme whatever the feed order`() {
        val long = p(8, 20, 0, 60, "Long")
        val short = p(8, 20, 0, 30, "Short")
        for (feed in listOf(listOf(long, short), listOf(short, long))) {
            val slots = LiveTvGuideGrid.layout(feed, bounds)
            assertEquals(listOf("Long"), slots.map { it.programme.title })
            assertEquals(at(8, 20), slots.single().startMs)
        }
        val twinA = p(8, 21, 0, 30, "Twin A")
        val twinB = p(8, 21, 0, 30, "Twin B")
        for (feed in listOf(listOf(twinA, twinB), listOf(twinB, twinA))) {
            assertEquals(listOf("Twin A"), LiveTvGuideGrid.layout(feed, bounds).map { it.programme.title })
        }
    }

    @Test
    fun `cells clip to the strip and mark past, airing and later`() {
        val cells = LiveTvGuideGrid.cells(wion, at(8, 20), now).map { it as GuideCell.Programme }
        assertEquals(listOf("World DNA", "World DNA", "Speed News", "Top Stories"), cells.map { it.slot.programme.title })
        assertEquals(listOf(ScheduleTime.NOW, ScheduleTime.LATER, ScheduleTime.LATER, ScheduleTime.LATER), cells.map { it.time })
        val shifted = LiveTvGuideGrid.cells(wion, at(8, 19, 45), now).map { it as GuideCell.Programme }
        assertEquals(ScheduleTime.PAST, shifted.first().time)
        assertTrue(shifted.first().cutStart)
        assertEquals(at(8, 19, 45), shifted.first().startMs)
        assertTrue(shifted.last().cutEnd)
        assertEquals(at(8, 21, 45), shifted.last().endMs)
        // now exactly on a boundary: the programme starting then airs, the one ending then is past.
        val atBoundary = LiveTvGuideGrid.cells(wion, at(8, 20), at(8, 20, 30)).map { it as GuideCell.Programme }
        assertEquals(listOf(ScheduleTime.PAST, ScheduleTime.NOW), atBoundary.take(2).map { it.time })
    }

    @Test
    fun `a guide that stops before the strip ends gets an end cell, gaps get nothing`() {
        // CBS News 24/7 (Pluto) ends Mon 03:00; a hole 01:00-02:00.
        val cbs = LiveTvGuideGrid.layout(listOf(p(9, 0, 0, 60, "Roundup"), p(9, 2, 0, 60, "Roundup")), bounds)
        val cells = LiveTvGuideGrid.cells(cbs, at(9, 1, 30), now)
        assertEquals(2, cells.size)
        assertEquals(at(9, 2), cells[0].startMs)
        val end = cells[1] as GuideCell.GuideEnd
        assertEquals(at(9, 3), end.atMs)
        assertEquals(at(9, 3), end.startMs)
        assertEquals(at(9, 3, 30), end.endMs)
        // Data reaching the strip's end exactly: no end cell.
        assertTrue(LiveTvGuideGrid.cells(cbs, at(9, 1), now).none { it is GuideCell.GuideEnd })
        // The whole strip past the data is one end cell.
        val after = LiveTvGuideGrid.cells(cbs, at(9, 4), now).single() as GuideCell.GuideEnd
        assertEquals(at(9, 4), after.startMs)
        assertTrue(LiveTvGuideGrid.cells(emptyList(), at(8, 20), now).isEmpty())
    }

    @Test
    fun `Right walks by programme and steps the strip half an hour past its edge`() {
        var strip = at(8, 20)
        var focus = GuideFocus(at(8, 21, 30), at(8, 21, 30)) // Top Stories, last in view
        val move = LiveTvGuideGrid.horizontal(wion, focus, strip, 1, bounds)!!
        assertEquals(at(8, 20, 30), move.stripStartMs) // mockup stage 4: 20:30-22:30
        assertEquals(GuideFocus(at(8, 22), at(8, 22)), move.focus)
        strip = move.stripStartMs
        focus = move.focus
        val back = LiveTvGuideGrid.horizontal(wion, focus, strip, -1, bounds)!!
        assertEquals(strip, back.stripStartMs) // 21:30 is still in view
        assertEquals(at(8, 21, 30), back.focus.slotStartMs)
    }

    @Test
    fun `Left onto a programme starting before the strip brings its start into view`() {
        val move = LiveTvGuideGrid.horizontal(wion, GuideFocus(at(8, 20), at(8, 20)), at(8, 20), -1, bounds)!!
        assertEquals(at(8, 19, 30), move.stripStartMs)
        assertEquals(at(8, 19, 30), move.focus.slotStartMs)
    }

    @Test
    fun `Left onto a programme from yesterday keeps the strip at today's midnight`() {
        val row = LiveTvGuideGrid.layout(listOf(p(7, 23, 30, 60, "Across midnight"), p(8, 0, 30, 30, "Next")), bounds)
        val move = LiveTvGuideGrid.horizontal(row, GuideFocus(at(8, 0, 30), at(8, 0, 30)), bounds.startMs, -1, bounds)!!
        assertEquals(GuideMove(bounds.startMs, GuideFocus(at(7, 23, 30), bounds.startMs)), move)
    }

    @Test
    fun `past the row's last programme the strip steps while it stays drawn, then stops`() {
        val last = wion.last() // Mon 00:00-00:30
        var focus = GuideFocus(last.startMs, last.startMs)
        var strip = at(8, 23)
        val seen = mutableListOf<Long>()
        while (true) {
            val move = LiveTvGuideGrid.horizontal(wion, focus, strip, 1, bounds) ?: break
            assertEquals(last.startMs, move.focus.slotStartMs)
            assertTrue(move.focus.cursorMs in maxOf(last.startMs, move.stripStartMs) until last.endMs)
            strip = move.stripStartMs
            focus = move.focus
            seen += strip
        }
        assertEquals(listOf(at(8, 23, 30), at(9, 0)), seen) // a step more would leave it undrawn
        // A guide that has ended (row focus) keeps stepping, so its end cell scrolls into view.
        val ended = LiveTvGuideGrid.layout(listOf(p(8, 18, 0, 60, "Gone")), bounds)
        assertEquals(GuideMove(at(8, 20, 30), GuideFocus(null, at(8, 20, 30))),
            LiveTvGuideGrid.horizontal(ended, GuideFocus(null, at(8, 21)), at(8, 20), 1, bounds))
    }

    @Test
    fun `a long jump lands the next start in the strip's last half hour`() {
        val sparse = LiveTvGuideGrid.layout(listOf(p(8, 20, 0, 30, "Now"), p(9, 6, 0, 60, "Morning")), bounds)
        val move = LiveTvGuideGrid.horizontal(sparse, GuideFocus(at(8, 20), at(8, 20)), at(8, 20), 1, bounds)!!
        assertEquals(at(9, 4, 30), move.stripStartMs)
        assertEquals(at(9, 6), move.focus.slotStartMs)
    }

    @Test
    fun `from a row focus Left and Right reach the programmes either side of the strip`() {
        val sparse = LiveTvGuideGrid.layout(listOf(p(8, 18, 0, 30, "Before"), p(8, 23, 0, 30, "After")), bounds)
        val focus = GuideFocus(null, at(8, 20))
        assertEquals(at(8, 23), LiveTvGuideGrid.horizontal(sparse, focus, at(8, 20), 1, bounds)!!.focus.slotStartMs)
        assertEquals(at(8, 18), LiveTvGuideGrid.horizontal(sparse, focus, at(8, 20), -1, bounds)!!.focus.slotStartMs)
    }

    @Test
    fun `a row without a guide steps the strip and stops at the bounds`() {
        val move = LiveTvGuideGrid.horizontal(emptyList(), GuideFocus(null, at(8, 20)), at(8, 20), 1, bounds)!!
        assertEquals(GuideMove(at(8, 20, 30), GuideFocus(null, at(8, 20, 30))), move)
        assertNull(LiveTvGuideGrid.horizontal(emptyList(), GuideFocus(null, bounds.startMs), bounds.startMs, -1, bounds))
        val lastStrip = bounds.endMs - STRIP_MS
        assertNull(LiveTvGuideGrid.horizontal(emptyList(), GuideFocus(null, lastStrip), lastStrip, 1, bounds))
    }

    @Test
    fun `Up and Down keep the time through a long programme`() {
        // Times Now 22:30-01:00 between WION rows: walking down at 23:30 onto it and back keeps 23:30.
        val timesNow = LiveTvGuideGrid.layout(listOf(p(8, 21, 30, 60, "Newshour"), p(8, 22, 30, 150, "Times Now Live")), bounds)
        val strip = at(8, 22)
        val down = LiveTvGuideGrid.vertical(timesNow, at(8, 23, 30), strip)
        assertEquals(GuideFocus(at(8, 22, 30), at(8, 23, 30)), down) // Times Now Live, cursor kept
        assertEquals(at(8, 23, 30), LiveTvGuideGrid.vertical(wion, down.cursorMs, strip).slotStartMs)
    }

    @Test
    fun `Up and Down take the nearest programme in the strip, else the row`() {
        val strip = at(8, 20)
        val hole = LiveTvGuideGrid.layout(listOf(p(8, 20, 0, 30, "Early"), p(8, 21, 30, 30, "Late")), bounds)
        assertEquals(at(8, 21, 30), LiveTvGuideGrid.vertical(hole, at(8, 21, 15), strip).slotStartMs)
        assertEquals(at(8, 20), LiveTvGuideGrid.vertical(hole, at(8, 20, 45), strip).slotStartMs)
        assertEquals(at(8, 21, 30), LiveTvGuideGrid.vertical(hole, at(8, 21), strip).slotStartMs) // a tie goes later
        val ended = LiveTvGuideGrid.layout(listOf(p(8, 18, 0, 60, "Gone")), bounds)
        assertEquals(GuideFocus(null, at(8, 21)), LiveTvGuideGrid.vertical(ended, at(8, 21), strip))
        assertEquals(GuideFocus(null, at(8, 21)), LiveTvGuideGrid.vertical(emptyList(), at(8, 21), strip))
    }

    @Test
    fun `the now move puts the strip at now and focus on the airing programme`() {
        assertEquals(GuideMove(at(8, 20), GuideFocus(at(8, 20), now)), LiveTvGuideGrid.nowMove(wion, now, bounds))
    }

    @Test
    fun `a day chip has data when any shown row has a programme that day`() {
        val today = at(8, 0) to at(9, 0)
        val tomorrow = at(9, 0) to at(10, 0)
        val todayOnly = LiveTvGuideGrid.layout(listOf(p(8, 20, 0, 30, "Tonight")), bounds)
        assertTrue(LiveTvGuideGrid.hasData(listOf(emptyList(), todayOnly), today.first, today.second))
        assertFalse(LiveTvGuideGrid.hasData(listOf(emptyList(), todayOnly), tomorrow.first, tomorrow.second))
        assertTrue(LiveTvGuideGrid.hasData(listOf(wion), tomorrow.first, tomorrow.second))
    }

    @Test
    fun `rows lay out each shown channel from its guide, keeping only rows with something to draw`() {
        fun ch(key: String) = Channel(key = key, sourceId = "a", name = key, streams = emptyList())
        val guide = EpgSnapshot(
            channels = emptyMap(),
            programmes = mapOf(
                "WION.in" to listOf(p(8, 20, 0, 30, "World DNA"), p(8, 20, 0, 30, "World DNA")),
                "Old.in" to listOf(p(7, 20, 0, 30, "Yesterday"))
            ),
            windowStartMs = bounds.startMs, windowEndMs = bounds.endMs, complete = true
        )
        val refs = mapOf(
            "wion" to EpgRef("g", "WION.in"),
            "old" to EpgRef("g", "Old.in"),
            "gone" to EpgRef("missing", "WION.in"),
            "unlisted" to EpgRef("g", "Nope.in")
        )
        val channels = listOf("wion", "old", "gone", "unlisted", "noref").map(::ch)
        val rows = LiveTvGuideGrid.rows(channels, refs, mapOf("g" to guide), bounds)
        assertEquals(bounds, rows.bounds)
        assertEquals(listOf("World DNA"), rows.row("wion").map { it.programme.title })
        assertEquals("g", rows.guideId("wion"))
        assertNull(rows.guideId("old"))
        // Only yesterday's data, a guide not cached, a channel the guide lacks, no ref: no guide row.
        listOf("old", "gone", "unlisted", "noref").forEach { assertTrue(it, rows.row(it).isEmpty()) }
        // A channel not shown is not laid out.
        assertTrue(LiveTvGuideGrid.rows(channels.drop(1), refs, mapOf("g" to guide), bounds).row("wion").isEmpty())
    }

    @Test
    fun `a saved view id opens that view, anything else List`() {
        LiveTvView.entries.forEach { assertEquals(it, LiveTvView.fromId(it.id)) }
        assertEquals(listOf("list", "guide", "compact"), LiveTvView.entries.map { it.id }) // persisted, never renamed
        assertEquals(LiveTvView.LIST, LiveTvView.fromId(null))
        assertEquals(LiveTvView.LIST, LiveTvView.fromId("grid"))
        assertFalse(LiveTvView.LIST.isGrid)
        assertTrue(LiveTvView.GUIDE.isGrid && LiveTvView.COMPACT.isGrid)
    }

    @Test
    fun `Back's target is the strip at now on the row's airing programme`() {
        val now20 = LiveTvGuideGrid.nowMove(wion, now, bounds)
        assertTrue(LiveTvGuideGrid.atNow(wion, now20, now, bounds))
        val right = LiveTvGuideGrid.horizontal(wion, now20.focus, now20.stripStartMs, 1, bounds)!!
        assertFalse(LiveTvGuideGrid.atNow(wion, right, now, bounds)) // 20:30 World DNA, same strip
        // A row without a guide is at now whenever the strip is.
        assertTrue(LiveTvGuideGrid.atNow(emptyList(), GuideMove(at(8, 20), GuideFocus(null, at(8, 21))), now, bounds))
        assertFalse(LiveTvGuideGrid.atNow(emptyList(), GuideMove(at(8, 20, 30), GuideFocus(null, at(8, 21))), now, bounds))
    }

    @Test
    fun `settle keeps a focused slot that is still drawn and refocuses at the cursor when it went`() {
        val move = GuideMove(at(8, 20), GuideFocus(at(8, 21), at(8, 21, 10)))
        assertEquals(move, LiveTvGuideGrid.settle(wion, move, bounds))
        // A refresh replaced 21:00-21:30 with 20:50-21:20: focus goes to the programme at the cursor.
        val refreshed = LiveTvGuideGrid.layout(
            listOf(p(8, 20, 0, 50, "World DNA"), p(8, 20, 50, 30, "Breaking"), p(8, 21, 20, 40, "Speed News")),
            bounds
        )
        assertEquals(GuideMove(at(8, 20), GuideFocus(at(8, 20, 50), at(8, 21, 10))), LiveTvGuideGrid.settle(refreshed, move, bounds))
        // The guide went: row focus, cursor kept.
        assertEquals(GuideMove(at(8, 20), GuideFocus(null, at(8, 21, 10))), LiveTvGuideGrid.settle(emptyList(), move, bounds))
        // A row focus whose row gained programmes takes the one at the cursor.
        val rowFocus = GuideMove(at(8, 20), GuideFocus(null, at(8, 20, 40)))
        assertEquals(at(8, 20, 30), LiveTvGuideGrid.settle(wion, rowFocus, bounds).focus.slotStartMs)
    }

    @Test
    fun `settle after local midnight clamps the strip into the new day's bounds`() {
        // Open since Sunday at 01:00 Sunday's strip; after midnight the bounds start Monday 00:00.
        val early = GuideMove(at(8, 1), GuideFocus(null, at(8, 1, 10)))
        val monday = LiveTvGuideGrid.bounds(at(9, 0, 5), zone)
        val settled = LiveTvGuideGrid.settle(LiveTvGuideGrid.layout(wionRaw, monday), early, monday)
        assertEquals(at(9, 0), settled.stripStartMs)
        assertEquals(at(9, 0), settled.focus.slotStartMs) // Speed News 00:00, at the clamped cursor
        assertEquals(at(9, 0), settled.focus.cursorMs)
    }

    @Test
    fun `OK watches the airing programme and a row focus only`() {
        val airing = wion.first { it.programme.title == "World DNA" }
        assertTrue(LiveTvGuideGrid.okWatches(airing, now))
        assertTrue(LiveTvGuideGrid.okWatches(null, now))
        assertFalse(LiveTvGuideGrid.okWatches(wion.first(), now)) // past
        assertFalse(LiveTvGuideGrid.okWatches(wion.last(), now)) // later
        assertFalse(LiveTvGuideGrid.okWatches(airing, airing.endMs)) // ends exactly now
    }

    @Test
    fun `settle keeps a focused slot drawn in the clamped strip and refocuses one the clamp hid`() {
        val monday = LiveTvGuideGrid.bounds(at(9, 0, 5), zone)
        val laidOut = LiveTvGuideGrid.layout(wionRaw, monday)
        // 23:30 Sunday's strip, Speed News 00:00 focused: clamped to Monday 00:00, still drawn, kept.
        val late = GuideMove(at(8, 23, 30), GuideFocus(at(9, 0), at(9, 0, 10)))
        assertEquals(GuideMove(at(9, 0), GuideFocus(at(9, 0), at(9, 0, 10))), LiveTvGuideGrid.settle(laidOut, late, monday))
        // A focused slot the clamp leaves off the strip goes to the one at the clamped cursor.
        val hidden = GuideMove(at(8, 22), GuideFocus(at(8, 22), at(8, 22, 5)))
        val settled = LiveTvGuideGrid.settle(laidOut, hidden, monday)
        assertEquals(at(9, 0), settled.stripStartMs)
        assertEquals(at(9, 0), settled.focus.slotStartMs)
    }
}
