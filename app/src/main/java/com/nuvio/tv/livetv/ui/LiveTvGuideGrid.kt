package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.parse.EpgSnapshot
import java.time.ZoneId

/** How far the strip can scroll (Spec 67 D7 Q4/Q6): local midnight today to the end of tomorrow (the D2 window). */
data class GuideBounds(val startMs: Long, val endMs: Long)

/**
 * One programme as a grid row draws it: [startMs] is the programme's start, moved past the previous
 * programme's end when a guide overlaps them ([LiveTvGuideGrid.layout]); a row's slots never overlap
 * and their starts strictly increase, so a start identifies a slot.
 */
data class GuideSlot(val programme: EpgProgramme, val startMs: Long, val endMs: Long)

/** What one row draws inside the strip (D7 Q7); [startMs]/[endMs] are clipped to the strip. */
sealed interface GuideCell {
    val startMs: Long
    val endMs: Long

    /** [cutStart]/[cutEnd]: the slot runs on past the strip's left/right edge. */
    data class Programme(
        val slot: GuideSlot,
        val time: ScheduleTime,
        override val startMs: Long,
        override val endMs: Long,
        val cutStart: Boolean,
        val cutEnd: Boolean
    ) : GuideCell

    /** The quiet "guide ends at [atMs]" cell after a guide's last programme; never focused. */
    data class GuideEnd(override val startMs: Long, override val endMs: Long, val atMs: Long) : GuideCell
}

/**
 * Focus inside the grid: [slotStartMs] names the focused slot of the row, null = the row itself (a
 * channel without a guide, or a row whose strip holds no programme: OK watches it). [cursorMs] is the
 * time Up/Down keep (D7 Q4), so a walk down a column does not drift through long programmes.
 */
data class GuideFocus(val slotStartMs: Long?, val cursorMs: Long)

/** A move's result: the strip's new start and the focus. */
data class GuideMove(val stripStartMs: Long, val focus: GuideFocus)

/** One channel's grid row: the guide it comes from ([guideId], a source id) and its [slots]. */
data class GuideRow(val guideId: String, val slots: List<GuideSlot>)

/**
 * The grid's rows for the shown filter (Spec 67 G3b): [bounds] and each channel's [layout][LiveTvGuideGrid.layout]
 * by channel key, holding only rows with something to draw; [row] is empty for the rest (no guide).
 */
data class GuideRows(val bounds: GuideBounds, private val rows: Map<String, GuideRow>) {
    fun row(key: String): List<GuideSlot> = rows[key]?.slots.orEmpty()

    /** The guide [key]'s row comes from (its guide-end cell names it); null without one. */
    fun guideId(key: String): String? = rows[key]?.guideId
}

/**
 * Pure rules of the guide grid (Spec 67 G3, D7): the strip's window, a row's cells, and D-pad moves.
 * Rows come in as [GuideSlot] lists from [layout]; an empty list is a row without a guide.
 */
object LiveTvGuideGrid {

    const val SLOT_MS = 30 * 60_000L

    /** The strip shows two hours (Q9). */
    const val STRIP_MS = 4 * SLOT_MS

    fun bounds(nowMs: Long, zone: ZoneId): GuideBounds =
        GuideBounds(localDayStartMs(nowMs, zone), localDayStartMs(nowMs, zone, 2))

    /**
     * The half hour holding [ms], counted from local midnight (not the epoch): a +5:45 zone still
     * gets :00/:30 marks, and a DST change (a whole hour) keeps them.
     */
    fun slotFloor(ms: Long, bounds: GuideBounds): Long =
        bounds.startMs + Math.floorDiv(ms - bounds.startMs, SLOT_MS) * SLOT_MS

    fun clampStrip(startMs: Long, bounds: GuideBounds): Long =
        startMs.coerceIn(bounds.startMs, (bounds.endMs - STRIP_MS).coerceAtLeast(bounds.startMs))

    /** Where the strip opens and Back returns to (Q5, Q9): the half hour holding now. */
    fun nowStrip(nowMs: Long, bounds: GuideBounds): Long = clampStrip(slotFloor(nowMs, bounds), bounds)

    /** Where the tomorrow chip puts the strip (Q6): 00:00 tomorrow. */
    fun tomorrowStrip(nowMs: Long, zone: ZoneId, bounds: GuideBounds): Long =
        clampStrip(localDayStartMs(nowMs, zone, 1), bounds)

    /**
     * [programmes] as one grid row: [LiveTvSchedule.cleaned] (duplicates once, "No Data" a gap), only
     * those inside [bounds], each started no earlier than the previous one ends; a programme an earlier
     * one covers entirely, or one pushed out of [bounds] by it, is dropped (it could never be drawn or
     * focused). Equal starts: the longer one first, then by title, so the result does not depend on the feed's order.
     */
    fun layout(programmes: List<EpgProgramme>, bounds: GuideBounds): List<GuideSlot> {
        val slots = ArrayList<GuideSlot>()
        var lastEnd = Long.MIN_VALUE
        val ordered = LiveTvSchedule.cleaned(programmes).sortedWith(compareBy({ it.startMs }, { -it.stopMs }, { it.title }))
        for (p in ordered) {
            if (p.stopMs <= bounds.startMs) continue
            val start = maxOf(p.startMs, lastEnd)
            if (start >= bounds.endMs || p.stopMs <= start) continue
            slots += GuideSlot(p, start, p.stopMs)
            lastEnd = p.stopMs
        }
        return slots
    }

    /**
     * Every one of [channels]' rows laid out for [bounds], reading each channel's programmes where the
     * day schedule does (`guides[ref.epgSourceId].programmes[ref.epgChannelId]`).
     */
    fun rows(
        channels: List<Channel>,
        refs: Map<String, EpgRef>,
        guides: Map<String, EpgSnapshot>,
        bounds: GuideBounds
    ): GuideRows {
        val rows = HashMap<String, GuideRow>()
        for (ch in channels) {
            val ref = refs[ch.key] ?: continue
            val programmes = guides[ref.epgSourceId]?.programmes?.get(ref.epgChannelId) ?: continue
            layout(programmes, bounds).takeIf { it.isNotEmpty() }?.let { rows[ch.key] = GuideRow(ref.epgSourceId, it) }
        }
        return GuideRows(bounds, rows)
    }

    /** The cells [row] draws in the strip starting at [stripStartMs], at [nowMs] (past dims, airing lighter). */
    fun cells(row: List<GuideSlot>, stripStartMs: Long, nowMs: Long): List<GuideCell> {
        val stripEnd = stripStartMs + STRIP_MS
        val cells = ArrayList<GuideCell>()
        for (slot in row) {
            if (slot.endMs <= stripStartMs || slot.startMs >= stripEnd) continue
            val time = when {
                slot.endMs <= nowMs -> ScheduleTime.PAST
                slot.startMs <= nowMs -> ScheduleTime.NOW
                else -> ScheduleTime.LATER
            }
            cells += GuideCell.Programme(
                slot = slot,
                time = time,
                startMs = maxOf(slot.startMs, stripStartMs),
                endMs = minOf(slot.endMs, stripEnd),
                cutStart = slot.startMs < stripStartMs,
                cutEnd = slot.endMs > stripEnd
            )
        }
        val dataEnd = row.lastOrNull()?.endMs
        if (dataEnd != null && dataEnd < stripEnd) {
            cells += GuideCell.GuideEnd(maxOf(dataEnd, stripStartMs), stripEnd, dataEnd)
        }
        return cells
    }

    /**
     * Left ([step] -1) or Right (+1) from [focus] (Q4): to the previous or next programme, the strip
     * stepping by half hours so the new programme's start is in view, within [bounds]. With no programme
     * that way the strip steps one half hour while the focused programme stays in view (the rest of a
     * long one, the guide-end cell); on a row focus (no guide, or nothing in the strip) it just steps.
     * Null at the edge (focus stays, the key is still consumed).
     */
    fun horizontal(row: List<GuideSlot>, focus: GuideFocus, stripStartMs: Long, step: Int, bounds: GuideBounds): GuideMove? {
        require(step == 1 || step == -1) { "step is -1 or 1" }
        val from = focus.slotStartMs ?: stripStartMs
        val target = if (step > 0) row.firstOrNull { it.startMs > from } else row.lastOrNull { it.startMs < from }
        target ?: return stepStrip(row, focus, stripStartMs, step, bounds)
        val strip = clampStrip(
            when {
                target.startMs < stripStartMs -> slotFloor(target.startMs, bounds)
                target.startMs >= stripStartMs + STRIP_MS -> slotFloor(target.startMs, bounds) - STRIP_MS + SLOT_MS
                else -> stripStartMs
            },
            bounds
        )
        return GuideMove(strip, GuideFocus(target.startMs, maxOf(target.startMs, strip)))
    }

    // No programme that way: the strip moves a half hour; a focused slot must stay drawn.
    private fun stepStrip(row: List<GuideSlot>, focus: GuideFocus, stripStartMs: Long, step: Int, bounds: GuideBounds): GuideMove? {
        val strip = clampStrip(stripStartMs + step * SLOT_MS, bounds)
        if (strip == stripStartMs) return null
        val focused = focus.slotStartMs ?: return GuideMove(strip, GuideFocus(null, strip))
        val slot = row.firstOrNull { it.startMs == focused } ?: return null
        if (slot.endMs <= strip || slot.startMs >= strip + STRIP_MS) return null
        // The cursor stays on the focused cell's drawn part, so Up/Down leave from where it shows.
        val cursor = focus.cursorMs.coerceIn(maxOf(slot.startMs, strip), minOf(slot.endMs, strip + STRIP_MS) - 1)
        return GuideMove(strip, GuideFocus(focused, cursor))
    }

    /**
     * Focus on [row] at [cursorMs] (Up/Down, page, entering the grid; Q4): the programme covering the
     * cursor, else the nearest one in the strip (on a tie the later one), else the row itself. The
     * cursor is kept.
     */
    fun vertical(row: List<GuideSlot>, cursorMs: Long, stripStartMs: Long): GuideFocus {
        val stripEnd = stripStartMs + STRIP_MS
        val visible = row.filter { it.endMs > stripStartMs && it.startMs < stripEnd }
        val target = visible.firstOrNull { cursorMs >= it.startMs && cursorMs < it.endMs }
            ?: visible.minByOrNull { if (cursorMs < it.startMs) it.startMs - cursorMs else cursorMs - it.endMs + 1 }
        return GuideFocus(target?.startMs, cursorMs)
    }

    /** Back away from now, the Today chip and the grid's opening focus (Q5): the strip at now, [row]'s airing programme. */
    fun nowMove(row: List<GuideSlot>, nowMs: Long, bounds: GuideBounds): GuideMove {
        val strip = nowStrip(nowMs, bounds)
        return GuideMove(strip, vertical(row, nowMs, strip))
    }

    fun airing(slot: GuideSlot, nowMs: Long): Boolean = slot.startMs <= nowMs && nowMs < slot.endMs

    /** OK watches (Q3) on the airing programme or a row focus ([slot] null); past and later programmes do nothing. */
    fun okWatches(slot: GuideSlot?, nowMs: Long): Boolean = slot == null || airing(slot, nowMs)

    /** Whether [move] on [row] is where Back returns to (Q5): the strip at now and the row's airing programme. */
    fun atNow(row: List<GuideSlot>, move: GuideMove, nowMs: Long, bounds: GuideBounds): Boolean {
        val now = nowMove(row, nowMs, bounds)
        return move.stripStartMs == now.stripStartMs && move.focus.slotStartMs == now.focus.slotStartMs
    }

    /**
     * [move] after [row] or [bounds] changed under it (a guide refresh, local midnight while open; G3a deferred
     * items): the strip clamped into [bounds]; the focused slot kept while it is still drawn, else the one at
     * the cursor (kept inside the strip), as Up/Down would pick it.
     */
    fun settle(row: List<GuideSlot>, move: GuideMove, bounds: GuideBounds): GuideMove {
        val strip = clampStrip(move.stripStartMs, bounds)
        val stripEnd = strip + STRIP_MS
        val focused = move.focus.slotStartMs
        val kept = focused?.let { start -> row.firstOrNull { it.startMs == start && it.endMs > strip && it.startMs < stripEnd } }
        if (kept != null && strip == move.stripStartMs) return move
        val cursor = move.focus.cursorMs.coerceIn(strip, stripEnd - 1)
        return GuideMove(strip, if (kept != null) GuideFocus(kept.startMs, cursor) else vertical(row, cursor, strip))
    }

    /** A day chip dims when none of the shown [rows] has a programme in [startMs, endMs) (Q6). */
    fun hasData(rows: Iterable<List<GuideSlot>>, startMs: Long, endMs: Long): Boolean =
        rows.any { row -> row.any { it.endMs > startMs && it.startMs < endMs } }
}
