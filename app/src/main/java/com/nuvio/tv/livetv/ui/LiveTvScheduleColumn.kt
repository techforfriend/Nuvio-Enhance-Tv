package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.screens.detail.formatWeekdayDayMonth
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.ceil

/**
 * The day schedule column (Spec 67 G2, sandbox `spec-67-day-schedule` v2, D3), in place of the
 * preview chrome and info block: the picture's head ([LiveTvScheduleHead]; the screen draws the
 * picture at the head's size), the channel, the Today / tomorrow chips (select on focus; an empty
 * day dims and takes no focus, Q4) and that day's programmes. It opens focused on the airing
 * programme with two earlier rows above (Q5); Up from the first row reaches the shown day's chip.
 * The column is one focus group no D-pad move can leave (Arshad 2026-11-09, after red-team rounds
 * found Up and then Down escapes): Left (but from the tomorrow chip, which moves to Today) or Back
 * ([onClose], the screen's BackHandler) closes it back to the channel row (Q2); OK on the airing
 * programme goes full screen ([onFullScreen], Q3). If the focused programme row goes (it ended
 * after midnight, the day rolled over, a guide refresh changed the list), focus moves to the nearest
 * remaining row, else the Today chip (D6); the column leaving composition cancels that request.
 */
@Composable
internal fun LiveTvScheduleColumn(
    schedule: LiveSchedule,
    tune: LiveTuneState,
    number: String?,
    timeFormat: DateFormat,
    onClose: () -> Unit,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val key = schedule.openId
    var dayIndex by remember(key) { mutableIntStateOf(0) }
    val day = if (dayIndex == 0) schedule.today else schedule.tomorrow
    val listState = rememberLazyListState()
    val rowRequesters = remember(key, dayIndex) { mutableMapOf<Int, FocusRequester>() }
    val requesterFor = { i: Int -> rowRequesters.getOrPut(i) { FocusRequester() } }
    val todayChip = remember { FocusRequester() }
    val tomorrowChip = remember { FocusRequester() }
    var tomorrowFocused by remember { mutableStateOf(false) }
    var hadFocus by remember(key) { mutableStateOf(false) }
    var lastFocusedStart by remember(key, dayIndex) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    // Each open and day switch scrolls to the day's opening slot; only the open moves focus (a day
    // switch keeps it on its chip; Down enters the list).
    var opened by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, dayIndex) {
        listState.scrollToItem(day.firstVisible)
        if (!opened) {
            opened = true
            if (day.items.isEmpty()) todayChip.requestFocusAfterFrames()
            else requesterFor(day.focusIndex).requestFocusAfterFrames()
        }
    }
    Column(
        modifier = modifier
            .width(live.previewWidth)
            .fillMaxHeight()
            .onFocusChanged {
                if (it.hasFocus) hadFocus = true
                else if (hadFocus) {
                    // Containment cancels every move, so this is removal: the focused row went, or
                    // the column itself is leaving (its scope is cancelled, the request with it).
                    hadFocus = false
                    scope.launch {
                        val target = LiveTvSchedule.refocusIndex(day, lastFocusedStart)
                        if (target >= 0) {
                            listState.scrollToItem((target - LiveTvSchedule.ROWS_ABOVE).coerceAtLeast(0))
                            requesterFor(target).requestFocusAfterFrames()
                        } else {
                            todayChip.requestFocusAfterFrames()
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                val k = event.nativeKeyEvent
                if (k.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || tomorrowFocused) return@onPreviewKeyEvent false
                if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) onClose()
                true
            }
            // Containment: every move out of the column is cancelled; Left closes it above instead.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(live.scheduleGap)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(live.scheduleHeadGap)
        ) {
            LiveTvPictureHead(
                tune = tune,
                channelKey = schedule.row.channel.key,
                radius = live.scheduleHeadRadius,
                modifier = Modifier.size(live.scheduleHeadWidth, live.scheduleHeadHeight)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MarqueeEyebrow(
                    text = listOfNotNull(
                        stringResource(R.string.live_tv_schedule),
                        number?.let { stringResource(R.string.live_tv_channel_number, it) }
                    ).joinToString(FACT_SEPARATOR),
                    accent = { live.accent }
                )
                Text(
                    text = schedule.row.channel.shownName,
                    style = NuvioTheme.textStyles.osdTitle,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(live.pillGap)) {
                    LiveTvPill(
                        label = stringResource(R.string.live_tv_schedule_today),
                        selected = dayIndex == 0,
                        onClick = { dayIndex = 0 },
                        modifier = Modifier
                            .focusRequester(todayChip)
                            .onFocusChanged { if (it.isFocused) dayIndex = 0 }
                    )
                    val tomorrowEmpty = schedule.tomorrow.empty
                    val tomorrowStart = schedule.tomorrow.startMs
                    val tomorrowLabel = remember(tomorrowStart) {
                        formatWeekdayDayMonth(Instant.ofEpochMilli(tomorrowStart).atZone(ZoneId.systemDefault()).toLocalDate())
                    }
                    LiveTvPill(
                        label = tomorrowLabel,
                        selected = dayIndex == 1,
                        onClick = { dayIndex = 1 },
                        modifier = Modifier
                            .focusRequester(tomorrowChip)
                            .alpha(if (tomorrowEmpty) EMPTY_DAY_ALPHA else 1f)
                            .focusProperties { canFocus = !tomorrowEmpty }
                            .onFocusChanged {
                                tomorrowFocused = it.isFocused
                                if (it.isFocused) dayIndex = 1
                            }
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = live.fadeHeight),
            verticalArrangement = Arrangement.spacedBy(live.scheduleListGap)
        ) {
            // cleaned() leaves one programme per (start, stop, title): a stable key as rows pass.
            itemsIndexed(day.items, key = { _, item -> item.programme.let { "${it.startMs}:${it.stopMs}:${it.title}" } }) { i, item ->
                LiveTvScheduleRow(
                    item = item,
                    nowMs = schedule.nowMs,
                    timeFormat = timeFormat,
                    onClick = { if (item.time == ScheduleTime.NOW) onFullScreen() },
                    modifier = Modifier
                        .focusRequester(requesterFor(i))
                        .onFocusChanged { if (it.isFocused) lastFocusedStart = item.programme.startMs }
                        .then(if (i == 0) Modifier.focusProperties { up = if (dayIndex == 0) todayChip else tomorrowChip } else Modifier)
                )
            }
            val ends = day.guideEndsMs
            if (ends != null) {
                item(key = "ends") {
                    Text(
                        text = stringResource(
                            R.string.live_tv_schedule_guide_ends,
                            schedule.guideName.orEmpty(),
                            timeFormat.format(Date(ends))
                        ),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage3,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = live.scheduleRowPaddingHorizontal, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * A small picture's chrome (the schedule column's head `.s67hd .pvs`, the guide band's `.s67band .pv`):
 * over the picture the screen draws at [modifier]'s size, LIVE while [channelKey] plays, the tuning veil
 * while it tunes, the warning when it gave up; corners masked at [radius].
 */
@Composable
internal fun LiveTvPictureHead(tune: LiveTuneState, channelKey: String?, radius: Dp, modifier: Modifier = Modifier) {
    val live = NuvioTheme.marquee.liveTv
    Box(modifier = modifier) {
        val mine = channelKey != null && tune.channel?.key == channelKey
        when {
            !mine -> Unit
            tune is LiveTuneState.Unavailable -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(live.errorFill),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = live.warn,
                    modifier = Modifier.size(live.errorIconSize)
                )
            }
            tune is LiveTuneState.Tuning -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(live.tuningVeil),
                contentAlignment = Alignment.Center
            ) { MarqueeSpinner(size = live.tuningSpinnerSize) }
            else -> LiveTvLiveBadge(modifier = Modifier.padding(8.dp))
        }
        LiveTvCornerMask(radius = radius)
    }
}

/**
 * One programme (`.s67r`): start time, title, then "n min · category"; the airing one reads
 * "Now · n min left · category" over its progress bar on [scheduleNowFill]; past ones dim.
 * Focus = white row, black text.
 */
@Composable
private fun LiveTvScheduleRow(
    item: ScheduleItem,
    nowMs: Long,
    timeFormat: DateFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val p = item.programme
    var focused by remember { mutableStateOf(false) }
    val past = item.time == ScheduleTime.PAST
    val airing = item.time == ScheduleTime.NOW
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = live.scheduleRowMinHeight)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(live.scheduleRowRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) live.scheduleNowFill else Color.Transparent,
            contentColor = if (past) marquee.onImage3 else white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        val secondary = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3
        Row(
            modifier = Modifier.padding(horizontal = live.scheduleRowPaddingHorizontal, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(live.scheduleRowInnerGap)
        ) {
            Text(
                text = timeFormat.format(Date(p.startMs)),
                style = NuvioTheme.textStyles.cardLabel.copy(fontFeatureSettings = "tnum"),
                color = when {
                    focused -> secondary
                    past -> marquee.onImage3
                    else -> marquee.onImage2
                },
                maxLines = 1,
                modifier = Modifier.widthIn(min = live.scheduleTimeWidth)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.title,
                    style = NuvioTheme.textStyles.button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val minutes = ((p.stopMs - p.startMs) / 60_000L).toInt()
                val left = ceil((p.stopMs - nowMs).coerceAtLeast(0L) / 60_000.0).toInt()
                Text(
                    text = listOfNotNull(
                        if (airing) stringResource(R.string.live_tv_now) else null,
                        if (airing) pluralStringResource(R.plurals.live_tv_min_left, left, left)
                        else pluralStringResource(R.plurals.live_tv_schedule_minutes, minutes, minutes),
                        p.category
                    ).joinToString(FACT_SEPARATOR),
                    style = NuvioTheme.textStyles.caption,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (airing) {
                    val ink = if (focused) black else live.accent
                    val track = if (focused) black.copy(alpha = FOCUSED_ROW_TRACK_ALPHA) else white.copy(alpha = ROW_TRACK_ALPHA)
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .width(live.scheduleProgressWidth)
                            .height(marquee.progressHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(track)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(item.progress ?: 0f)
                                .background(ink)
                        )
                    }
                }
            }
        }
    }
}

/** Q4: the tomorrow chip when its day has nothing. */
private const val EMPTY_DAY_ALPHA = 0.4f
