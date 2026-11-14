package com.nuvio.tv.livetv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.localDayStartMs
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeProgress
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.screens.detail.formatWeekdayDayMonth
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.ceil

// Live TV's Guide and Compact views (Spec 67 G3c, D7; sandbox `spec-67-guide-grid` v2): the band over the grid.

/**
 * Where focus is in the grid: the focused row's channel [key] and the strip + slot ([move]). The screen
 * owns it, one per showing of the grid. The row changes only on explicit events (Spec 67 G3c-Q1): the grid
 * opens on List's focused row, a new filter starts on its first row, a refresh or local midnight settles in
 * place (a row that went gives its place to the row now there), D-pad moves, and the screen's refocus after
 * a zap, full screen or the options panel ([focus]). Entering the grid from the filters or day chips always
 * lands on [key]. Every move goes through the G3a rules in [LiveTvGuideGrid]; the time is kept throughout.
 */
@Stable
internal class GuideCursor {
    var key: String? by mutableStateOf(null)
        private set
    var move: GuideMove? by mutableStateOf(null)
    private var lastIndex = 0

    // A new filter came while it listed nothing (the grid is not composed then): its rows start on the first.
    private var restartPending = false

    /**
     * The screen's filter changed (its one home for that fact, which runs whether or not the grid is
     * composed): the first row at the kept time, or, while the filter lists nothing, once its rows come.
     */
    fun filterChanged(rows: List<LiveRow>, guide: GuideRows, nowMs: Long) {
        restartPending = rows.isEmpty()
        if (!restartPending) focus(rows.first().channel.key, rows, guide, nowMs)
    }

    /** Focus [key]'s row at the kept time cursor (the strip at now the first time); false when it is not shown. */
    fun focus(key: String, rows: List<LiveRow>, guide: GuideRows, nowMs: Long): Boolean {
        val index = rows.indexOfFirst { it.channel.key == key }
        if (index < 0) return false
        val slots = guide.row(key)
        val bounds = guide.bounds
        val m = move
        move = if (m == null) {
            LiveTvGuideGrid.nowMove(slots, nowMs, bounds)
        } else {
            val strip = LiveTvGuideGrid.clampStrip(m.stripStartMs, bounds)
            val cursor = m.focus.cursorMs.coerceIn(strip, strip + LiveTvGuideGrid.STRIP_MS - 1)
            GuideMove(strip, LiveTvGuideGrid.vertical(slots, cursor, strip))
        }
        this.key = key
        lastIndex = index
        return true
    }

    /**
     * The rows or the guide changed (or the grid just opened): a pending new filter ([filterChanged]) starts on
     * its first row; the opening goes to [openKey]'s row (List's focused one) else the first; otherwise a row
     * that went gives its place to the row now there (else the last) and the focus settles
     * ([LiveTvGuideGrid.settle]). Idempotent: the screen also calls it before entering, so the first frame has a row.
     */
    fun sync(rows: List<LiveRow>, guide: GuideRows, nowMs: Long, openKey: String?) {
        if (rows.isEmpty()) return
        if (restartPending) {
            filterChanged(rows, guide, nowMs)
            return
        }
        if (key == null) {
            if (openKey == null || !focus(openKey, rows, guide, nowMs)) focus(rows.first().channel.key, rows, guide, nowMs)
            return
        }
        val index = rows.indexOfFirst { it.channel.key == key }
        val shown = if (index >= 0) index else lastIndex.coerceAtMost(rows.lastIndex)
        val shownKey = rows[shown].channel.key
        val m = move
        if (shownKey != key || m == null) {
            focus(shownKey, rows, guide, nowMs)
        } else {
            move = LiveTvGuideGrid.settle(guide.row(shownKey), m, guide.bounds)
            lastIndex = shown
        }
    }

    /** The focused programme of [guide]'s focused row; null on a row focus (or before any). */
    fun slot(guide: GuideRows): GuideSlot? {
        val start = move?.focus?.slotStartMs ?: return null
        return guide.row(key ?: return null).firstOrNull { it.startMs == start }
    }

    /** Up/Down ([delta] ±1) or a page (CH ±) keeping the time; false at the list's end. */
    fun step(rows: List<LiveRow>, guide: GuideRows, delta: Int): Boolean {
        val m = move ?: return false
        val from = rows.indexOfFirst { it.channel.key == key }
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, rows.lastIndex)
        if (to == from) return false
        val toKey = rows[to].channel.key
        move = GuideMove(m.stripStartMs, LiveTvGuideGrid.vertical(guide.row(toKey), m.focus.cursorMs, m.stripStartMs))
        key = toKey
        lastIndex = to
        return true
    }
}

/**
 * The band (`.s67band`): beside the picture (the screen draws it at the band's picture size; this adds
 * its chrome and the tuned channel's caption) the focused programme, or the focused channel when its
 * row shows none: eyebrow "CH n · channel · category", title, times (the airing one with progress and
 * minutes left), the key hints for what OK and Back do there (Q3, Q5).
 */
@Composable
internal fun LiveTvGuideBand(
    rows: List<LiveRow>,
    guide: GuideRows,
    cursor: GuideCursor,
    tune: LiveTuneState,
    tunedName: String?,
    tunedNumber: String?,
    nowMs: Long,
    timeFormat: DateFormat,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    Row(
        modifier = modifier.height(live.guideBandHeight),
        horizontalArrangement = Arrangement.spacedBy(live.guideBandGap)
    ) {
        Box(modifier = Modifier.size(live.guideBandPictureWidth, live.guideBandHeight)) {
            if (tunedName != null && tune is LiveTuneState.Playing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to NuvioPrimitives.black.copy(alpha = 0.75f)))
                )
                Text(
                    text = listOfNotNull(tunedName, tunedNumber?.let { stringResource(R.string.live_tv_channel_number, it) })
                        .joinToString(FACT_SEPARATOR),
                    style = NuvioTheme.textStyles.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                )
            }
            LiveTvPictureHead(tune = tune, channelKey = tune.channel?.key, radius = live.guideBandRadius, modifier = Modifier.fillMaxSize())
        }
        // Read here, not in the screen: a move recomposes the band and the grid only.
        val row = rows.firstOrNull { it.channel.key == cursor.key } ?: return@Row
        val slot = cursor.slot(guide)
        val move = cursor.move
        val atNow = move == null || LiveTvGuideGrid.atNow(guide.row(row.channel.key), move, nowMs, guide.bounds)
        val noGuide = guide.row(row.channel.key).isEmpty()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            val p = slot?.programme
            MarqueeEyebrow(
                text = listOfNotNull(
                    row.number.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.live_tv_channel_number, it) },
                    row.channel.shownName,
                    p?.category ?: row.category
                ).joinToString(FACT_SEPARATOR),
                accent = { live.accent }
            )
            Text(
                text = p?.title ?: row.channel.shownName,
                style = NuvioTheme.textStyles.panelStateTitle,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val airing = slot != null && LiveTvGuideGrid.airing(slot, nowMs)
            when {
                // The grid draws this row's "No guide" cell (no programmes in the bounds count as none).
                slot == null && noGuide -> Text(
                    text = stringResource(R.string.live_tv_no_guide),
                    style = NuvioTheme.textStyles.meta,
                    color = marquee.onImage3,
                    maxLines = 1
                )
                slot == null -> Unit
                airing -> GuideAiringLine(slot, nowMs, timeFormat)
                else -> Text(
                    text = guideTimesText(slot, nowMs, timeFormat),
                    style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (LiveTvGuideGrid.okWatches(slot, nowMs)) {
                    LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_ok)), stringResource(R.string.live_tv_hint_watch))
                }
                LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_hold_ok)), stringResource(R.string.live_tv_hint_options))
                // Only where Back moves (a hole at now leaves focus on its nearest programme: Back acts as in List).
                if (!atNow) LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_back)), stringResource(R.string.live_tv_now))
            }
        }
    }
}

@Composable
private fun GuideAiringLine(slot: GuideSlot, nowMs: Long, timeFormat: DateFormat) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val left = ceil((slot.endMs - nowMs).coerceAtLeast(0L) / 60_000.0).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(marquee.metaGap)) {
        Text(
            text = timeFormat.format(Date(slot.startMs)) + "\u2013" + timeFormat.format(Date(slot.endMs)),
            style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
            color = marquee.onImage2,
            maxLines = 1
        )
        MarqueeProgress(
            fraction = ((nowMs - slot.startMs).toFloat() / (slot.endMs - slot.startMs).coerceAtLeast(1)).coerceIn(0f, 1f),
            label = pluralStringResource(R.plurals.live_tv_min_left, left, left),
            accent = { live.accent },
            width = live.guideBandProgressWidth
        )
    }
}

/** "[Mon 9 Nov · ]02:00–02:30 · 30 min · starts in 1 h 46 min" (or "· ended"); the day only when it is not today. */
@Composable
private fun guideTimesText(slot: GuideSlot, nowMs: Long, timeFormat: DateFormat): String {
    val zone = ZoneId.systemDefault()
    val day = if (localDayStartMs(slot.startMs, zone) != localDayStartMs(nowMs, zone)) {
        formatWeekdayDayMonth(Instant.ofEpochMilli(slot.startMs).atZone(zone).toLocalDate())
    } else null
    val timing = if (slot.endMs <= nowMs) stringResource(R.string.live_tv_guide_ended)
    else stringResource(R.string.live_tv_guide_starts_in, guideDuration(slot.startMs - nowMs))
    return listOfNotNull(
        day,
        timeFormat.format(Date(slot.startMs)) + "\u2013" + timeFormat.format(Date(slot.endMs)),
        guideDuration(slot.endMs - slot.startMs),
        timing
    ).joinToString(FACT_SEPARATOR)
}

/** "30 min", "2 h", "1 h 46 min" (rounded up to the minute). */
@Composable
private fun guideDuration(ms: Long): String {
    val total = ceil(ms.coerceAtLeast(0L) / 60_000.0).toInt()
    val h = total / 60
    val m = total % 60
    return when {
        h == 0 -> pluralStringResource(R.plurals.live_tv_schedule_minutes, m, m)
        m == 0 -> stringResource(R.string.live_tv_guide_hours, h)
        else -> stringResource(R.string.live_tv_guide_hours_minutes, h, m)
    }
}

/**
 * The grid (`.s67g`): the ruler (day chips, half-hour marks, the now marker) over the shown filter's
 * rows, the strip two hours wide (Q9) with the amber now-line. Focus is one target ([gridRequester])
 * whose keys move [cursor] by the G3a rules: Left/Right by programme, Up/Down keeping the time, CH ±
 * a screen of rows, held keys at the list's throttle (Q4); Up from the top row reaches the day chip of
 * the shown day, else the filters ([onUpFromTop]); Back away from now returns to now (Q5). A day chip
 * (OK) moves the strip: Today to now, tomorrow to its 00:00; it dims and takes no focus when no shown
 * row has data that day (Q6); Down from the chips enters the cursor's row ([GuideCursor]). OK on the airing programme
 * or a row showing none watches its channel ([onWatch], Q3); hold OK opens its options ([onOptions]).
 * The picture follows the focused row through [onRowFocused] (the list's settle; never on moves along
 * a row, Q2). [compact] = 32 dp rows, titles only (Q8).
 */
@Composable
internal fun LiveTvGuide(
    rows: List<LiveRow>,
    guide: GuideRows,
    compact: Boolean,
    nowMs: Long,
    cursor: GuideCursor,
    openKey: String?,
    listState: LazyListState,
    playingKey: String?,
    sourceNames: Map<String, String>,
    timeFormat: DateFormat,
    gridRequester: FocusRequester,
    contentRequester: FocusRequester,
    onRowFocused: (LiveRow) -> Unit,
    onFocusLeft: () -> Unit,
    onUpFromTop: () -> Unit,
    onWatch: (LiveRow) -> Unit,
    onOptions: (LiveRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val live = NuvioTheme.marquee.liveTv
    val rowHeight = if (compact) live.guideCompactRowHeight else live.guideRowHeight
    val zone = ZoneId.systemDefault()
    val bounds = guide.bounds
    var focused by remember { mutableStateOf(false) }
    val move = cursor.move
    val focusIndex = rows.indexOfFirst { it.channel.key == cursor.key }

    LaunchedEffect(rows, guide) { cursor.sync(rows, guide, nowMs, openKey) }
    // The picture follows the row focus rests on (Q2); moves along a row keep it.
    LaunchedEffect(cursor.key, focused) {
        if (focused) rows.firstOrNull { it.channel.key == cursor.key }?.let(onRowFocused)
    }
    // Keep the focused row on screen while the grid has focus (a filter change on the pills keeps its top).
    LaunchedEffect(focusIndex, focused) {
        if (focusIndex < 0 || !focused) return@LaunchedEffect
        val full = fullyVisible(listState)
        val first = full.firstOrNull()?.index ?: listState.firstVisibleItemIndex
        val last = full.lastOrNull()?.index ?: first
        when {
            focusIndex < first -> listState.scrollToItem(focusIndex)
            focusIndex > last -> listState.scrollToItem((focusIndex - (last - first)).coerceAtLeast(0))
        }
    }
    val cursorKey = cursor.key
    val atNow = move != null && cursorKey != null && LiveTvGuideGrid.atNow(guide.row(cursorKey), move, nowMs, bounds)
    BackHandler(enabled = focused && !atNow) {
        val key = cursor.key ?: return@BackHandler
        cursor.move = LiveTvGuideGrid.nowMove(guide.row(key), nowMs, bounds)
    }

    val strip = move?.stripStartMs ?: LiveTvGuideGrid.nowStrip(nowMs, bounds)
    val tomorrowStart = localDayStartMs(nowMs, zone, 1)
    val (todayData, tomorrowData) = remember(rows, guide, tomorrowStart) {
        val slots = rows.map { guide.row(it.channel.key) }
        LiveTvGuideGrid.hasData(slots, bounds.startMs, tomorrowStart) to LiveTvGuideGrid.hasData(slots, tomorrowStart, bounds.endMs)
    }
    val todayChip = remember { FocusRequester() }
    val tomorrowChip = remember { FocusRequester() }
    val onTomorrow = strip >= tomorrowStart
    val lastRepeat = remember { longArrayOf(0L) }
    val fastHorizontal = LocalFastHorizontalNavigationEnabled.current
    val longPress = rememberMarqueeLongPress()

    BoxWithConstraints(modifier = modifier) {
        val stripWidth = maxWidth - live.guideChannelWidth - live.guideGap
        val dpPerMs = stripWidth / LiveTvGuideGrid.STRIP_MS.toFloat()
        fun x(ms: Long): Dp = dpPerMs * (ms - strip).toFloat()

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(live.guideGap)) {
            // The ruler.
            Row(modifier = Modifier.height(live.guideRulerHeight), horizontalArrangement = Arrangement.spacedBy(live.guideGap)) {
                Row(
                    modifier = Modifier
                        .width(live.guideChannelWidth)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { event ->
                            val k = event.nativeKeyEvent
                            if (k.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
                            // Down enters the grid on the cursor's row (G3c-Q1), brought on screen once focused.
                            if (k.action == KeyEvent.ACTION_DOWN) {
                                cursor.sync(rows, guide, nowMs, openKey)
                                gridRequester.requestFocus()
                            }
                            true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GuideDayChip(
                        label = stringResource(R.string.live_tv_schedule_today),
                        selected = !onTomorrow,
                        enabled = todayData,
                        onClick = { cursor.key?.let { cursor.move = LiveTvGuideGrid.nowMove(guide.row(it), nowMs, bounds) } },
                        modifier = Modifier.focusRequester(todayChip)
                    )
                    val tomorrowLabel = remember(tomorrowStart) {
                        formatWeekdayDayMonth(Instant.ofEpochMilli(tomorrowStart).atZone(zone).toLocalDate())
                    }
                    GuideDayChip(
                        label = tomorrowLabel,
                        selected = onTomorrow,
                        enabled = tomorrowData,
                        onClick = {
                            val key = cursor.key ?: return@GuideDayChip
                            val s = LiveTvGuideGrid.tomorrowStrip(nowMs, zone, bounds)
                            cursor.move = GuideMove(s, LiveTvGuideGrid.vertical(guide.row(key), s, s))
                        },
                        modifier = Modifier.focusRequester(tomorrowChip)
                    )
                }
                GuideRulerMarks(
                    strip = strip,
                    nowMs = nowMs,
                    tomorrowStart = tomorrowStart,
                    zone = zone,
                    dpPerMs = dpPerMs,
                    width = stripWidth,
                    timeFormat = timeFormat
                )
            }

            // The rows: one focus target; the cells draw the cursor.
            Surface(
                onClick = {
                    // The cursor read now, not at composition: a key moved it before this frame.
                    longPress.click {
                        val row = rows.firstOrNull { it.channel.key == cursor.key } ?: return@click
                        if (LiveTvGuideGrid.okWatches(cursor.slot(guide), nowMs)) onWatch(row)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(gridRequester)
                    .focusRequester(contentRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) onFocusLeft()
                    }
                    .onPreviewKeyEvent { event ->
                        val k = event.nativeKeyEvent
                        val code = k.keyCode
                        val handled = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                            code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                        if (!handled) return@onPreviewKeyEvent false
                        if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                        // Held keys: the list's throttle (DpadThrottleModifier's gates), without its focus moves.
                        if (k.repeatCount > 0) {
                            val vertical = code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                            val gate = if (vertical) VERTICAL_GATE_MS else if (fastHorizontal) FAST_HORIZONTAL_GATE_MS else HORIZONTAL_GATE_MS
                            val t = SystemClock.uptimeMillis()
                            if (t - lastRepeat[0] < gate) return@onPreviewKeyEvent true
                            lastRepeat[0] = t
                        }
                        val key = cursor.key ?: return@onPreviewKeyEvent true
                        val m = cursor.move ?: return@onPreviewKeyEvent true
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val step = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                LiveTvGuideGrid.horizontal(guide.row(key), m.focus, m.stripStartMs, step, bounds)?.let { cursor.move = it }
                            }
                            // Up from the top row (Q4): the shown day's chip (else the other day's), else the filters.
                            KeyEvent.KEYCODE_DPAD_UP -> if (!cursor.step(rows, guide, -1)) {
                                val chips = if (onTomorrow) listOf(tomorrowChip to tomorrowData, todayChip to todayData)
                                else listOf(todayChip to todayData, tomorrowChip to tomorrowData)
                                chips.firstOrNull { it.second }?.first?.requestFocus() ?: onUpFromTop()
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> cursor.step(rows, guide, 1)
                            else -> {
                                val page = fullyVisible(listState).size
                                cursor.step(rows, guide, page.coerceAtLeast(1) * if (code == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                            }
                        }
                        true
                    }
                    .then(longPress.modifier { rows.firstOrNull { it.channel.key == cursor.key }?.let(onOptions) }),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    pressedContainerColor = Color.Transparent,
                    contentColor = NuvioPrimitives.white,
                    focusedContentColor = NuvioPrimitives.white,
                    pressedContentColor = NuvioPrimitives.white
                ),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (nowMs >= strip && nowMs < strip + LiveTvGuideGrid.STRIP_MS) {
                        Box(
                            modifier = Modifier
                                .offset(x = live.guideChannelWidth + live.guideGap + x(nowMs) - live.guideNowLineWidth / 2)
                                .width(live.guideNowLineWidth)
                                .fillMaxHeight()
                                .background(live.accent, RoundedCornerShape(1.dp))
                        )
                    }
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = false,
                        contentPadding = PaddingValues(bottom = live.fadeHeight),
                        verticalArrangement = Arrangement.spacedBy(live.guideGap)
                    ) {
                        items(rows, key = { it.channel.key }, contentType = { "guide_row" }) { row ->
                            val key = row.channel.key
                            val isFocusRow = focused && key == cursor.key
                            GuideRowView(
                                row = row,
                                slots = guide.row(key),
                                guideName = guide.guideId(key)?.let { sourceNames[it] ?: it },
                                strip = strip,
                                nowMs = nowMs,
                                focusedSlot = if (isFocusRow) move?.focus?.slotStartMs else null,
                                rowFocused = isFocusRow && move?.focus?.slotStartMs == null,
                                playing = key == playingKey,
                                compact = compact,
                                height = rowHeight,
                                dpPerMs = dpPerMs,
                                stripWidth = stripWidth,
                                timeFormat = timeFormat
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `.rl .day i`: a [guideChipHeight] day chip; selected = glass, focused = white; dimmed and unfocusable when not [enabled]. */
@Composable
private fun GuideDayChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(live.guideChipHeight)
            .alpha(if (enabled) 1f else EMPTY_DAY_ALPHA)
            .focusProperties { canFocus = enabled },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) marquee.glassFill else Color.Transparent,
            contentColor = if (selected) NuvioPrimitives.white else marquee.onImage3,
            focusedContainerColor = NuvioPrimitives.white,
            focusedContentColor = NuvioPrimitives.black,
            pressedContainerColor = NuvioPrimitives.white,
            pressedContentColor = NuvioPrimitives.black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = live.guideChipPaddingHorizontal), contentAlignment = Alignment.Center) {
            Text(text = label, style = rulerStyle(), maxLines = 1)
        }
    }
}

/**
 * The ruler's half-hour marks (`.rl span`; local midnight reads "Mon 00:00" on a brighter edge) and the
 * now marker (mockup `build.py` `grid`): an amber pill on the strip, else an outlined "◄ 20:14" in the
 * first mark's place while the strip is later today (Q5); none on tomorrow's strips or earlier ones.
 */
@Composable
private fun GuideRulerMarks(strip: Long, nowMs: Long, tomorrowStart: Long, zone: ZoneId, dpPerMs: Dp, width: Dp, timeFormat: DateFormat) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val stripEnd = strip + LiveTvGuideGrid.STRIP_MS
    val before = nowMs < strip && strip < tomorrowStart
    Box(modifier = Modifier.width(width).fillMaxHeight()) {
        var t = strip
        while (t < stripEnd) {
            val midnight = localDayStartMs(t, zone) == t
            if (!(before && t == strip)) {
                val time = timeFormat.format(Date(t))
                val label = if (midnight) {
                    stringResource(R.string.live_tv_guide_midnight, formatWeekday(t, zone), time)
                } else time
                Row(modifier = Modifier.offset(x = dpPerMs * (t - strip).toFloat()).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(if (midnight) live.guideMidnightEdge else live.guideMarkEdge)
                    )
                    Text(
                        text = label,
                        style = rulerStyle().copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                        color = if (midnight) NuvioPrimitives.white else marquee.onImage3,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp).align(Alignment.CenterVertically)
                    )
                }
            }
            t += LiveTvGuideGrid.SLOT_MS
        }
        val now = timeFormat.format(Date(nowMs))
        val pill = RoundedCornerShape(5.dp)
        when {
            before -> Text(
                text = "\u25C4 $now",
                style = rulerStyle().copy(fontWeight = FontWeight.Bold),
                color = live.accent,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .border(BorderStroke(1.dp, live.accent), pill)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
            nowMs >= strip && nowMs < stripEnd -> Text(
                text = now,
                style = rulerStyle().copy(fontWeight = FontWeight.Bold),
                color = NuvioPrimitives.black,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // Centred on now.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) { p.place((dpPerMs * (nowMs - strip).toFloat()).roundToPx() - p.width / 2, 0) }
                    }
                    .background(live.accent, pill)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * One grid row (`.row`): the channel cell (number, tile, name; glass with an amber number while it
 * plays, white while focus is on the row itself) and the strip's cells ([LiveTvGuideGrid.cells]): past
 * dimmed, airing lighter, a cut edge squared off with "‹ " before a title that started earlier, times
 * under the title when [compact] is off and the cell is wide enough; the hatched guide-end cell
 * naming [guideName]; a row without a guide one "No guide for this channel" cell (Q7).
 */
@Composable
private fun GuideRowView(
    row: LiveRow,
    slots: List<GuideSlot>,
    guideName: String?,
    strip: Long,
    nowMs: Long,
    focusedSlot: Long?,
    rowFocused: Boolean,
    playing: Boolean,
    compact: Boolean,
    height: Dp,
    dpPerMs: Dp,
    stripWidth: Dp,
    timeFormat: DateFormat
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    Row(modifier = Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(live.guideGap)) {
        // A row whose strip shows nothing marks the channel; one without a guide its "No guide" cell.
        val channelFocused = rowFocused && slots.isNotEmpty()
        Row(
            modifier = Modifier
                .width(live.guideChannelWidth)
                .fillMaxHeight()
                .background(
                    when {
                        channelFocused -> white
                        playing -> marquee.glassFill
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(10.dp)
                )
                .padding(start = 6.dp, end = 10.dp)
                .alpha(if (row.lost) live.lostAlpha else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = row.number,
                style = rulerStyle().copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                color = when {
                    channelFocused -> black.copy(alpha = SUFFIX_ALPHA)
                    playing -> live.accent
                    else -> marquee.onImage3
                },
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = live.guideNumberWidth)
            )
            val logo = if (compact) live.guideCompactLogoSize else live.guideLogoSize
            LiveTvChannelTile(channel = row.channel, size = logo, radius = logo / 4)
            Text(
                text = row.channel.shownName,
                style = NuvioTheme.textStyles.cardLabel,
                color = if (channelFocused) black else white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(modifier = Modifier.width(stripWidth).fillMaxHeight()) {
            if (slots.isEmpty()) {
                GuideNoneCell(focused = rowFocused, width = stripWidth)
                return@Box
            }
            for (cell in LiveTvGuideGrid.cells(slots, strip, nowMs)) {
                val x = dpPerMs * (cell.startMs - strip).toFloat()
                val w = dpPerMs * (cell.endMs - cell.startMs).toFloat()
                when (cell) {
                    is GuideCell.Programme -> GuideProgrammeCell(
                        cell = cell,
                        focused = cell.slot.startMs == focusedSlot,
                        compact = compact,
                        width = w - live.guideGap,
                        timeFormat = timeFormat,
                        modifier = Modifier.offset(x = x)
                    )
                    is GuideCell.GuideEnd -> GuideEndCell(
                        text = stringResource(R.string.live_tv_guide_ends_cell, guideName.orEmpty(), timeFormat.format(Date(cell.atMs))),
                        width = w,
                        modifier = Modifier.offset(x = x)
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideProgrammeCell(cell: GuideCell.Programme, focused: Boolean, compact: Boolean, width: Dp, timeFormat: DateFormat, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val past = cell.time == ScheduleTime.PAST
    val r = live.guideCellRadius
    val cut = live.guideCutRadius
    val shape = RoundedCornerShape(
        topStart = if (cell.cutStart) cut else r,
        bottomStart = if (cell.cutStart) cut else r,
        topEnd = if (cell.cutEnd) cut else r,
        bottomEnd = if (cell.cutEnd) cut else r
    )
    val fill = when {
        focused -> NuvioPrimitives.white
        cell.time == ScheduleTime.NOW -> live.guideAiringFill
        else -> live.guideCellFill
    }
    Column(
        modifier = modifier
            .width(width.coerceAtLeast(0.dp))
            .fillMaxHeight()
            .clip(shape)
            .background(fill)
            .padding(horizontal = if (compact) 9.dp else live.guideCellPaddingHorizontal),
        verticalArrangement = Arrangement.Center
    ) {
        if (width <= MIN_TITLE_WIDTH) return@Column
        val p = cell.slot.programme
        Text(
            text = (if (cell.cutStart) "\u2039 " else "") + p.title,
            style = NuvioTheme.textStyles.cardLabel.copy(
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (past && !focused) FontWeight.Medium else FontWeight.SemiBold
            ),
            color = when {
                focused -> NuvioPrimitives.black
                past -> marquee.onImage3
                else -> NuvioPrimitives.white
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact && width > MIN_TIMES_WIDTH) {
            Text(
                text = timeFormat.format(Date(cell.slot.startMs)) + "\u2013" + timeFormat.format(Date(cell.slot.endMs)),
                style = NuvioTheme.textStyles.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                color = if (focused) NuvioPrimitives.black.copy(alpha = FOCUSED_TIMES_ALPHA) else marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/** `.c.end`: the hatched, never-focused "<guide>'s guide ends at HH:MM" cell (Q7). */
@Composable
private fun GuideEndCell(text: String, width: Dp, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(live.guideCellRadius)),
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 12.dp.toPx()
            val stroke = 6.dp.toPx() / 1.414f
            clipRect {
                var x = -size.height
                while (x < size.width) {
                    drawLine(live.guideEndStripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
                    x += step
                }
            }
        }
        if (width > MIN_TITLE_WIDTH) {
            Text(
                text = text,
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = live.guideCellPaddingHorizontal)
            )
        }
    }
}

/** `.c.none`: a row without a guide is one cell across the strip, focusable as the row (Q7). */
@Composable
private fun GuideNoneCell(focused: Boolean, width: Dp) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val shape = RoundedCornerShape(live.guideCellRadius)
    Box(
        modifier = Modifier
            .width(width - live.guideGap)
            .fillMaxHeight()
            .then(
                if (focused) Modifier.background(NuvioPrimitives.white, shape)
                else Modifier.border(1.dp, live.guideNoneEdge, shape)
            )
            .padding(horizontal = live.guideCellPaddingHorizontal),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(R.string.live_tv_no_guide),
            style = NuvioTheme.textStyles.caption,
            color = if (focused) NuvioPrimitives.black.copy(alpha = 0.7f) else marquee.onImage3,
            maxLines = 1
        )
    }
}

/** The rows wholly on screen above the bottom padding (under the fade), the page CH ± moves. */
private fun fullyVisible(state: LazyListState) = state.layoutInfo.let { info ->
    val end = info.viewportEndOffset - info.afterContentPadding
    info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= end }
}

/** The weekday of [ms] as the ruler's midnight mark shows it ("Mon"). */
private fun formatWeekday(ms: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())

@Composable
private fun rulerStyle() = NuvioTheme.textStyles.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

private const val EMPTY_DAY_ALPHA = 0.4f
private const val FOCUSED_TIMES_ALPHA = 0.6f
private val MIN_TITLE_WIDTH = 26.dp
private val MIN_TIMES_WIDTH = 70.dp

// DpadThrottleModifier's repeat gates (the list's throttle, Q4).
private const val HORIZONTAL_GATE_MS = 80L
private const val FAST_HORIZONTAL_GATE_MS = 48L
private const val VERTICAL_GATE_MS = 112L
