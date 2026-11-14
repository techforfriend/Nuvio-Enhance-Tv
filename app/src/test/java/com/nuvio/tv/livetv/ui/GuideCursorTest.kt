package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.parse.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Spec 67 G3c: the grid's focus across row moves, rows going away, refreshes and the opening row. */
class GuideCursorTest {
    private val zone = ZoneId.of("America/Edmonton")
    private fun at(h: Int, m: Int = 0): Long = LocalDateTime.of(2026, 11, 8, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun p(h: Int, m: Int, mins: Int, title: String) = EpgProgramme(at(h, m), at(h, m) + mins * 60_000L, title)
    private val now = at(20, 14)
    private val bounds = LiveTvGuideGrid.bounds(now, zone)

    private fun row(key: String) =
        LiveRow(Channel(key = key, sourceId = "a", name = key, streams = emptyList()), key, null, null, hasGuide = true, progress = null)

    private fun guide(vararg rows: Pair<String, List<EpgProgramme>>) =
        GuideRows(bounds, rows.associate { (k, ps) -> k to GuideRow("g", LiveTvGuideGrid.layout(ps, bounds)) })

    private val halfHours = listOf(p(20, 0, 30, "A"), p(20, 30, 30, "B"), p(21, 0, 30, "C"))
    private val hours = listOf(p(20, 0, 60, "X"), p(21, 0, 60, "Y"))
    private val rows = listOf(row("a"), row("b"), row("c"))
    private val grid = guide("a" to halfHours, "b" to hours, "c" to halfHours)

    @Test
    fun `the first focus is the row's airing programme with the strip at now, later ones keep the time`() {
        val cursor = GuideCursor()
        assertTrue(cursor.focus("a", rows, grid, now))
        assertEquals(GuideMove(at(20), GuideFocus(at(20), now)), cursor.move)
        cursor.move = LiveTvGuideGrid.horizontal(grid.row("a"), cursor.move!!.focus, at(20), 1, bounds)
        assertEquals(at(20, 30), cursor.move!!.focus.slotStartMs)
        // Down keeps 20:30: b's 20:00-21:00 covers it.
        assertTrue(cursor.step(rows, grid, 1))
        assertEquals("b", cursor.key)
        assertEquals(GuideFocus(at(20), at(20, 30)), cursor.move!!.focus)
        assertTrue(cursor.step(rows, grid, 1))
        assertEquals(at(20, 30), cursor.move!!.focus.slotStartMs) // back on c's 20:30, no drift
        assertFalse(cursor.step(rows, grid, 1))
        assertFalse(cursor.focus("gone", rows, grid, now))
    }

    @Test
    fun `a page clamps to the list's ends`() {
        val cursor = GuideCursor()
        cursor.focus("a", rows, grid, now)
        assertTrue(cursor.step(rows, grid, 7))
        assertEquals("c", cursor.key)
        assertTrue(cursor.step(rows, grid, -7))
        assertEquals("a", cursor.key)
    }

    @Test
    fun `a removed row gives focus to the row now in its place, else the last`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "b") // the grid opens on b
        cursor.sync(listOf(row("a"), row("c")), grid, now, null)
        assertEquals("c", cursor.key)
        cursor.sync(listOf(row("a")), grid, now, null)
        assertEquals("a", cursor.key)
    }

    @Test
    fun `a refresh that moves the focused programme refocuses at the cursor`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "a")
        cursor.move = GuideMove(at(20), GuideFocus(at(21), at(21, 10)))
        cursor.sync(rows, guide("a" to listOf(p(20, 0, 50, "A"), p(20, 50, 40, "C2")), "b" to hours, "c" to halfHours), now, null)
        assertEquals(GuideFocus(at(20, 50), at(21, 10)), cursor.move!!.focus)
        // The guide went: the row itself.
        cursor.sync(rows, guide("b" to hours), now, null)
        assertNull(cursor.move!!.focus.slotStartMs)
    }

    @Test
    fun `the grid opens on List's focused row, else its first`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "c")
        assertEquals("c", cursor.key)
        // Later syncs of the same filter keep the row the D-pad chose.
        cursor.step(rows, grid, -1)
        cursor.sync(rows, grid, now, "c")
        assertEquals("b", cursor.key)
        val fresh = GuideCursor()
        fresh.sync(rows, grid, now, "not listed")
        assertEquals("a", fresh.key)
    }

    @Test
    fun `a new filter starts on its first row at the kept time`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "a")
        cursor.step(rows, grid, 2)
        cursor.move = LiveTvGuideGrid.horizontal(grid.row("c"), cursor.move!!.focus, at(20), 1, bounds) // c 20:30
        val news = listOf(row("x"), row("y"), row("z"))
        val newsGuide = guide("x" to halfHours, "y" to hours, "z" to halfHours)
        cursor.filterChanged(news, newsGuide, now)
        assertEquals("x", cursor.key)
        assertEquals(GuideFocus(at(20, 30), at(20, 30)), cursor.move!!.focus)
        // The new rows' own sync after it settles in place.
        cursor.step(news, newsGuide, 1)
        cursor.sync(news, newsGuide, now, "a")
        assertEquals("y", cursor.key)
    }

    @Test
    fun `a filter round trip through an empty filter starts on the first row when rows come back`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "c")
        cursor.filterChanged(emptyList(), grid, now) // an empty Recents: the grid leaves composition
        assertEquals("c", cursor.key)
        // Back on the first filter: the screen's filter effect sees its rows.
        cursor.filterChanged(rows, grid, now)
        assertEquals("a", cursor.key)
        cursor.step(rows, grid, 1)
        cursor.sync(rows, grid, now, "c") // its rows' sync then settles in place
        assertEquals("b", cursor.key)
    }

    @Test
    fun `a restart pending from an empty filter is taken by the next rows' sync, once`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "c")
        cursor.filterChanged(emptyList(), grid, now)
        cursor.sync(rows, grid, now, "c") // the rows' sync came first
        assertEquals("a", cursor.key)
        cursor.step(rows, grid, 1)
        cursor.sync(rows, grid, now, "c") // a later refresh
        assertEquals("b", cursor.key)
    }

    @Test
    fun `a refocus clamps the strip into the bounds and keeps the time`() {
        val cursor = GuideCursor()
        cursor.sync(rows, grid, now, "a")
        cursor.move = GuideMove(bounds.endMs, GuideFocus(null, bounds.endMs + 60_000)) // past the end
        assertTrue(cursor.focus("b", rows, grid, now))
        assertEquals(LiveTvGuideGrid.clampStrip(bounds.endMs, bounds), cursor.move!!.stripStartMs)
        assertEquals(bounds.endMs - 1, cursor.move!!.focus.cursorMs)
    }

    @Test
    fun `slot is the focused programme, null on a row focus or before any focus`() {
        val cursor = GuideCursor()
        assertNull(cursor.slot(grid))
        cursor.focus("a", rows, grid, now)
        assertEquals("A", cursor.slot(grid)!!.programme.title)
        cursor.move = GuideMove(at(20), GuideFocus(null, now))
        assertNull(cursor.slot(grid))
        // A start the row no longer has (a refresh before sync) reads as none, never another programme.
        cursor.move = GuideMove(at(20), GuideFocus(at(20, 10), now))
        assertNull(cursor.slot(grid))
    }
}
