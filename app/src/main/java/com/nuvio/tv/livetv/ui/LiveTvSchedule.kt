package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.parse.EpgProgramme
import java.time.ZoneId

/** Where a schedule row sits against now (Spec 67 G2): past rows dim, the airing one shows its progress. */
enum class ScheduleTime { PAST, NOW, LATER }

/** One schedule row: [progress] (0..1) only for the airing programme. */
data class ScheduleItem(val programme: EpgProgramme, val time: ScheduleTime, val progress: Float? = null)

/**
 * One day chip's list (Spec 67 D3 Q4): the programmes starting in [startMs, endMs) local, plus on
 * Today the one still airing from the day before (one that crossed midnight and ended is yesterday's:
 * not listed). Overlapping programmes airing at once are all NOW. [focusIndex] is where the list opens: the
 * first airing programme (else the first one still to come, else the last); [firstVisible] puts two earlier rows
 * above it (Q5). [guideEndsMs] is set when the guide's data stops before the day does (stage 5's line).
 */
data class ScheduleDay(
    val startMs: Long,
    val endMs: Long,
    val items: List<ScheduleItem>,
    val focusIndex: Int,
    val firstVisible: Int,
    val guideEndsMs: Long?
) {
    /** A chip with nothing in it is dimmed (Q4). */
    val empty: Boolean get() = items.isEmpty()
}

/**
 * The schedule column's state (Spec 67 G2): the channel's [row], its guide's name ([guideName], the
 * guide-ends line), its [today] and [tomorrow] lists, derived at [nowMs] (the airing row's minutes left).
 * [openId] is new for every open, so a reopen of the same channel focuses the column again.
 */
data class LiveSchedule(
    val openId: Int,
    val row: LiveRow,
    val guideName: String?,
    val today: ScheduleDay,
    val tomorrow: ScheduleDay,
    val nowMs: Long
)

/** Pure rules of the per-channel day schedule (Spec 67 G2, D3): today and tomorrow from a guide window. */
object LiveTvSchedule {

    /** Earlier rows shown above the opening one (Q5). */
    const val ROWS_ABOVE = 2

    /**
     * [programmes] as the schedule lists them: sorted by start, each identical (start, stop, title)
     * once (i.mjh.nz's Pluto guide lists every programme twice), and free-epg.de's "No Data" filler
     * left out as a gap.
     */
    fun cleaned(programmes: List<EpgProgramme>): List<EpgProgramme> {
        val seen = HashSet<Triple<Long, Long, String>>()
        return programmes
            .filter { !it.title.trim().equals(NO_DATA, ignoreCase = true) }
            .sortedBy { it.startMs }
            .filter { seen.add(Triple(it.startMs, it.stopMs, it.title)) }
    }

    /** Today ([dayOffset] 0) or tomorrow (1) of [programmes] at [nowMs] in [zone]. */
    fun day(programmes: List<EpgProgramme>, nowMs: Long, zone: ZoneId, dayOffset: Int): ScheduleDay {
        val start = localDayStartMs(nowMs, zone, dayOffset.toLong())
        val end = localDayStartMs(nowMs, zone, dayOffset + 1L)
        val list = cleaned(programmes)
        val inDay = list.filter { p ->
            p.startMs in start until end || (dayOffset == 0 && p.startMs < start && p.stopMs > nowMs)
        }
        val items = inDay.map { p ->
            when {
                p.stopMs <= nowMs -> ScheduleItem(p, ScheduleTime.PAST)
                // stopMs > nowMs >= startMs here, so the span is positive.
                p.startMs <= nowMs -> ScheduleItem(p, ScheduleTime.NOW, (nowMs - p.startMs).toFloat() / (p.stopMs - p.startMs))
                else -> ScheduleItem(p, ScheduleTime.LATER)
            }
        }
        val focus = items.indexOfFirst { it.time != ScheduleTime.PAST }.let { if (it < 0) (items.size - 1).coerceAtLeast(0) else it }
        val dataEnd = list.maxOfOrNull { it.stopMs }
        return ScheduleDay(
            startMs = start,
            endMs = end,
            items = items,
            focusIndex = focus,
            firstVisible = (focus - ROWS_ABOVE).coerceAtLeast(0),
            guideEndsMs = dataEnd?.takeIf { it < end && items.isNotEmpty() }
        )
    }

    /**
     * Where focus goes when the focused row left the list (Spec 67 D6): the first row starting at or
     * after the one that had focus ([lastStartMs]; null = the day's opening row), else the last row;
     * -1 when the day is empty (the Today chip takes it). By start time, not index, so rows leaving
     * above it do not shift the target.
     */
    fun refocusIndex(day: ScheduleDay, lastStartMs: Long?): Int = when {
        day.items.isEmpty() -> -1
        lastStartMs == null -> day.focusIndex
        else -> day.items.indexOfFirst { it.programme.startMs >= lastStartMs }.let { if (it < 0) day.items.size - 1 else it }
    }

    private const val NO_DATA = "No Data"
}

/**
 * Which schedule open the screen shows (Spec 67 D5, D6): the screen owns it, like the options panel's
 * row, so a close removes the column in the same frame. An open is pending until the derived
 * [LiveSchedule] carries its id ([shown]); a pending open is cancelled when focus leaves its row
 * ([pendingOn]); a shown open whose data goes is closed by the screen ([lostData]).
 */
@Stable
internal class ScheduleOpenState {
    var openId by mutableStateOf<Int?>(null)
        private set
    var openKey by mutableStateOf<String?>(null)
        private set
    private var shownOnce = false

    val isOpen: Boolean get() = openId != null

    fun opened(id: Int, key: String) {
        openId = id
        openKey = key
        shownOnce = false
    }

    /** [schedule] when it belongs to this open, else null (pending, closed, or a stale derivation). */
    fun shown(schedule: LiveSchedule?): LiveSchedule? = schedule?.takeIf { openId != null && it.openId == openId }

    /** Each time the shown schedule appears or goes: true when a shown open lost its data (close it). */
    fun lostData(shown: Boolean): Boolean {
        if (shown) {
            shownOnce = true
            return false
        }
        return openId != null && shownOnce
    }

    /** Focus left [key]'s row: true when that row's open is still pending (cancel it). */
    fun pendingOn(key: String, shown: Boolean): Boolean = !shown && openId != null && openKey == key

    /** Clears the open; returns its channel key (the row to refocus). */
    fun close(): String? {
        val key = openKey
        openId = null
        openKey = null
        shownOnce = false
        return key
    }
}
