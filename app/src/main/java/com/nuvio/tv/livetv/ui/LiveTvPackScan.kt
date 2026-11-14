package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.PickerSection
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** A scan's countries in words (Spec 73 S3b; D12 Q1: [ScanName]). */
@Composable
internal fun scanName(countries: Collection<String?>, world: Set<String?>): String =
    scanName(PackScanModel.name(countries, world))

@Composable
private fun scanName(name: ScanName): String =
    when (name) {
        is ScanName.One -> LiveTvModel.countryName(name.country)
        ScanName.NoCountry -> stringResource(R.string.live_tv_scan_name_no_country)
        is ScanName.Several -> pluralStringResource(R.plurals.live_tv_scan_name_several, name.count, name.count)
        ScanName.World -> stringResource(R.string.live_tv_scan_name_world)
    }

/** A country list row's name: the country's, or No country listed. */
@Composable
private fun countryRowName(country: String?): String =
    country?.let(LiveTvModel::countryName) ?: stringResource(R.string.live_tv_scan_no_country)

/**
 * "today 20:09", else "6 Nov 20:09" (stage 9: the last scan's date and time once older than today).
 */
@Composable
internal fun scanWhen(atMs: Long, timeFormat: DateFormat): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val time = timeFormat.format(Date(atMs))
    if (day == Instant.now().atZone(zone).toLocalDate()) return stringResource(R.string.live_tv_ago_today, time)
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")
    return "${java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date(atMs))} $time"
}

/**
 * The pack row's scan part (stages 3, 6, 8, 9): "scanning Canada, 412 of 911 checked", "scanned
 * Canada today 20:09", "scan of Canada cancelled today 20:07" (D12 Q8: also a scan the app
 * closed during, at its start time), else "not scanned yet".
 */
@Composable
internal fun packScanPhrase(
    scan: PackScanner.State,
    facts: PackScanFacts?,
    timeFormat: DateFormat,
    numbers: NumberFormat
): String {
    val world = facts?.world.orEmpty()
    if (scan is PackScanner.State.Running) {
        val r = scan.record
        return stringResource(
            R.string.live_tv_scan_row_running,
            scanName(r.countries, world),
            numbers.format(r.checked),
            numbers.format(r.channels)
        )
    }
    val last = facts?.last ?: return stringResource(R.string.live_tv_scan_row_never)
    val ended = last.endedMs
    return if (ended == null || last.cancelled) {
        stringResource(R.string.live_tv_scan_row_cancelled, scanName(last.countries, world), scanWhen(ended ?: last.startedMs, timeFormat))
    } else {
        stringResource(R.string.live_tv_scan_row_done, scanName(last.countries, world), scanWhen(ended, timeFormat))
    }
}

/**
 * The focused pack row's second line (stage 9): "Canada: 882 play here, 29 hidden until the next
 * scan · 1,626 channels elsewhere wait for a scan of their country"; before any scan only the
 * waiting part (D13). Null when there is nothing to say.
 */
@Composable
internal fun packScanSummary(facts: PackScanFacts?, numbers: NumberFormat): String? {
    facts ?: return null
    val last = facts.last
    val waiting = facts.waiting.takeIf { it > 0 }?.let { n ->
        pluralStringResource(
            if (last != null) R.plurals.live_tv_scan_waiting_elsewhere else R.plurals.live_tv_scan_waiting,
            n,
            numbers.format(n)
        )
    }
    val summary = last?.let {
        stringResource(
            R.string.live_tv_scan_summary,
            scanName(it.countries, facts.world),
            numbers.format(it.playing),
            numbers.format(it.hidden)
        )
    }
    return listOfNotNull(summary, waiting).joinToString(FACT_SEPARATOR).ifEmpty { null }?.sentenceCase()
}

/** A line that opens with a lower-case scan name ("the whole world: …") starts with a capital. */
private fun String.sentenceCase(): String = replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** The row's status while a scan runs (stage 6): "SCANNING 45%", or "PAUSED" while something plays. */
@Composable
internal fun packScanStatus(running: PackScanner.State.Running): String =
    if (running.paused) stringResource(R.string.live_tv_scan_paused)
    else stringResource(R.string.live_tv_scan_status, PackScanModel.percent(running.record))

/** Bold in the scan panels' green (`.s73cp .s73l b`). */
@Composable
private fun AnnotatedString.Builder.good(text: String) {
    withStyle(SpanStyle(color = NuvioTheme.marquee.readyText, fontWeight = FontWeight.SemiBold)) { append(text) }
}

@Composable
private fun ScanBig(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.panelValueLarge,
        color = NuvioPrimitives.white,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun ScanLine(text: AnnotatedString, quiet: Boolean = false) {
    Text(
        text = text,
        style = if (quiet) NuvioTheme.textStyles.caption else NuvioTheme.textStyles.panelBody,
        color = if (quiet) NuvioTheme.marquee.onImage3 else NuvioTheme.marquee.stream.dialogBody,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun ScanLine(text: String, quiet: Boolean = false) = ScanLine(AnnotatedString(text), quiet)

/**
 * Stage 3: the country list (the Spec 55 multi-select panel, [scanPickWidth] wide). THIS TV first
 * (ticked when [ticked] starts empty), then every country by channel count, No country listed
 * last. Footer: the ticked channels' estimate, Whole world ([onWorld], the stage 4 confirmation),
 * Scan (focused when something is ticked; D12 Q6: dimmed and inert with nothing ticked; every
 * country ticked reads "Scan the whole world" and opens [onWorld]'s confirmation, D17 Q4).
 * Back calls [onDismiss] (D3: the scan is skipped, the pack kept).
 */
@Composable
internal fun PackScanPickPanel(
    facts: PackScanFacts,
    initial: Set<String?>,
    numbers: NumberFormat,
    onScan: (Set<String?>) -> Unit,
    onWorld: (ticked: Set<String?>) -> Unit,
    onDismiss: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    var ticked by rememberSaveable { mutableStateOf(initial.map { it ?: NO_COUNTRY }) }
    val selected = ticked.mapTo(HashSet()) { it.takeIf { key -> key != NO_COUNTRY } }
    val scanButton = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    fun toggle(country: String?) {
        val key = country ?: NO_COUNTRY
        ticked = if (key in ticked) ticked - key else ticked + key
    }

    @Composable
    fun CountryRow(row: ScanCountry, focus: FocusRequester?) {
        PlayerPanelRow(
            title = countryRowName(row.country),
            selected = row.country in selected,
            selectedFill = false,
            titleEnd = pluralStringResource(R.plurals.live_tv_scan_channels, row.channels, numbers.format(row.channels)),
            focusRequester = focus,
            onClick = { toggle(row.country) }
        )
    }

    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.guidesPickVeil,
        width = live.scanPickWidth,
        // D12 Q7: nothing ticked (no THIS TV) starts on the first country row.
        initialFocus = remember { if (selected.isNotEmpty()) scanButton else firstRow },
        color = live.guidesPickFill
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_scan_eyebrow))
        Text(
            text = stringResource(R.string.live_tv_scan_note),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage3,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)
        )
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            facts.thisTv?.let { here ->
                PickerSection(stringResource(R.string.live_tv_scan_this_tv))
                CountryRow(here, firstRow)
            }
            PickerSection(stringResource(R.string.live_tv_scan_all_countries))
            facts.countries.forEachIndexed { index, row ->
                key(row.country ?: NO_COUNTRY) {
                    CountryRow(row, if (index == 0 && facts.thisTv == null) firstRow else null)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .drawBehind { drawLine(SCAN_RULE, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                .padding(start = 12.dp, top = 12.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val channels = facts.channelsIn(selected)
            // Every country ticked (Scan again after a Whole world scan, D4) is the whole world: named so
            // (D12 Q1) and started through its second confirmation (60/D11), never on one OK (D17 Q4).
            val name = PackScanModel.name(selected, facts.world)
            val world = name == ScanName.World
            Text(
                text = if (selected.isEmpty()) "" else stringResource(
                    R.string.live_tv_scan_estimate,
                    scanName(name),
                    PackScanModel.estimateMinutes(channels)
                ).sentenceCase(),
                style = NuvioTheme.textStyles.caption,
                color = NuvioTheme.marquee.onImage3,
                // Two lines: "The whole world · about 38 min" beside both buttons was cut to "The whole" (D17 Q4).
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.live_tv_scan_whole_world),
                onClick = { onWorld(selected) },
                height = live.buttonHeight,
                horizontalPadding = live.sourcesButtonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall
            )
            MarqueePrimaryButton(
                label = when {
                    selected.isEmpty() -> stringResource(R.string.live_tv_scan_go_none)
                    name is ScanName.Several -> pluralStringResource(R.plurals.live_tv_scan_go_several, name.count, name.count)
                    else -> stringResource(R.string.live_tv_scan_go_one, scanName(name))
                },
                icon = null,
                onClick = {
                    when {
                        world -> onWorld(selected)
                        selected.isNotEmpty() -> onScan(selected)
                    }
                },
                modifier = Modifier.alpha(if (selected.isEmpty()) DIM_ALPHA else 1f),
                focusRequester = scanButton,
                compact = true
            )
        }
    }
}

/** Stage 4: Whole world's second confirmation (60/D11), Cancel focused; figures from [PackScanModel.CHANNELS_PER_MINUTE] / [PackScanModel.KB_PER_CHANNEL]. */
@Composable
internal fun PackScanWorldPanel(facts: PackScanFacts, numbers: NumberFormat, onScan: () -> Unit, onDismiss: () -> Unit) {
    val live = NuvioTheme.marquee.liveTv
    val cancel = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.addVeil,
        width = live.scanCardWidth,
        initialFocus = cancel,
        color = live.guidesPickFill
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_scan_world_eyebrow))
        Box(Modifier.height(10.dp))
        ScanBig(stringResource(R.string.live_tv_scan_world_big, numbers.format(facts.channels), numbers.format(facts.countryCount)))
        ScanLine(
            stringResource(
                R.string.live_tv_scan_world_body,
                PackScanModel.estimateMinutes(facts.channels),
                PackScanModel.estimateMb(facts.channels)
            )
        )
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancel)
            )
            MarqueeGlassTextButton(label = stringResource(R.string.live_tv_scan_world_go), onClick = onScan)
        }
    }
}

/**
 * Stage 5: the running scan. Eyebrow + percent, "412 of 911 checked", the bar, "396 play here · 16
 * don't · less than a minute left" (D12 Q5; "Paused while a channel plays" while paused),
 * the note, Hide (focused, also Back: the scan goes on) and Cancel scan (keeps what was checked;
 * the cancelled panel follows when it has stopped).
 */
@Composable
internal fun PackScanProgressPanel(
    running: PackScanner.State.Running,
    world: Set<String?>,
    numbers: NumberFormat,
    onHide: () -> Unit,
    onCancel: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    val record = running.record
    val hide = remember { FocusRequester() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    val percent = PackScanModel.percent(record)
    MarqueeCentredPanel(
        onDismissRequest = onHide,
        veil = live.guidesPickVeil,
        width = live.scanCardWidth,
        initialFocus = hide,
        color = live.guidesPickFill
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            PanelEyebrowText(text = stringResource(R.string.live_tv_scan_progress_eyebrow, scanName(record.countries, world)))
            Text(
                text = stringResource(R.string.live_tv_scan_percent, percent),
                style = NuvioTheme.textStyles.button,
                color = NuvioTheme.marquee.stream.dialogBody
            )
        }
        PanelRule()
        Box(Modifier.height(10.dp))
        ScanBig(stringResource(R.string.live_tv_scan_checked, numbers.format(record.checked), numbers.format(record.channels)))
        Box(
            modifier = Modifier
                .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp)
                .fillMaxWidth()
                .height(live.scanBarHeight)
                .clip(RoundedCornerShape(live.scanBarHeight / 2))
                .background(live.scanBarTrack)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(percent / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(live.scanBarHeight / 2))
                    .background(NuvioPrimitives.white)
            )
        }
        val left = when {
            running.paused -> stringResource(R.string.live_tv_scan_paused_line)
            else -> when (val minutes = PackScanModel.minutesLeft(record, nowMs, running.pausedMs)) {
                null -> null
                0 -> stringResource(R.string.live_tv_scan_under_minute)
                else -> stringResource(R.string.live_tv_scan_minutes_left, minutes)
            }
        }
        val playHere = stringResource(R.string.live_tv_scan_play_here, numbers.format(record.playing))
        val dont = stringResource(R.string.live_tv_scan_dont, numbers.format(record.hidden))
        ScanLine(
            buildAnnotatedString {
                good(playHere)
                append(FACT_SEPARATOR)
                append(dont)
                left?.let {
                    append(FACT_SEPARATOR)
                    append(it)
                }
            }
        )
        ScanLine(stringResource(R.string.live_tv_scan_progress_note), quiet = true)
        MarqueePanelActions {
            MarqueeGlassTextButton(
                label = stringResource(R.string.live_tv_scan_hide),
                onClick = onHide,
                modifier = Modifier.focusRequester(hide)
            )
            MarqueeGlassTextButton(label = stringResource(R.string.live_tv_scan_cancel), onClick = onCancel)
        }
    }
}

/**
 * Stages 7 (done) and 8 (cancelled): what the scan found. Done: "882 of 911 channels play on this
 * TV", the hidden line, the "N more now in Live TV" line with up to two names (D12 Q2: a
 * line whose count is 0 is left out); Open Live TV (focused) / Done. Cancelled: "412 of 911
 * channels checked", play here / hidden, the rest keep the monitor's result; Done (focused) / Scan
 * again.
 */
@Composable
internal fun PackScanResultPanel(
    record: PackScanRecord,
    world: Set<String?>,
    numbers: NumberFormat,
    onOpenLiveTv: () -> Unit,
    onScanAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    val first = remember { FocusRequester() }
    val name = scanName(record.countries, world)
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.guidesPickVeil,
        width = live.scanCardWidth,
        initialFocus = first,
        color = live.guidesPickFill
    ) {
        if (record.cancelled) {
            PanelEyebrow(text = stringResource(R.string.live_tv_scan_cancelled_eyebrow, name))
            Box(Modifier.height(10.dp))
            ScanBig(
                stringResource(R.string.live_tv_scan_cancelled_big, numbers.format(record.checked), numbers.format(record.channels))
            )
            val playHere = stringResource(R.string.live_tv_scan_play_here, numbers.format(record.playing))
            val hidden = stringResource(R.string.live_tv_scan_cancelled_hidden, numbers.format(record.hidden))
            val hiddenOnly = stringResource(R.string.live_tv_scan_cancelled_hidden_only, numbers.format(record.hidden))
            // D12 Q2: a zero count is left out.
            when {
                record.playing > 0 -> ScanLine(
                    buildAnnotatedString {
                        good(playHere)
                        if (record.hidden > 0) append(hidden) else append(".")
                    }
                )
                record.hidden > 0 -> ScanLine(hiddenOnly)
            }
            val rest = record.channels - record.checked
            if (rest > 0) {
                ScanLine(pluralStringResource(R.plurals.live_tv_scan_cancelled_rest, rest, numbers.format(rest)), quiet = true)
            }
            MarqueePanelActions {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.live_tv_scan_close),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(first)
                )
                MarqueeGlassTextButton(label = stringResource(R.string.live_tv_scan_again), onClick = onScanAgain)
            }
        } else {
            PanelEyebrow(text = stringResource(R.string.live_tv_scan_done_eyebrow, name))
            Box(Modifier.height(10.dp))
            ScanBig(stringResource(R.string.live_tv_scan_done_big, numbers.format(record.playing), numbers.format(record.channels)))
            if (record.hidden > 0) {
                ScanLine(pluralStringResource(R.plurals.live_tv_scan_done_hidden, record.hidden, numbers.format(record.hidden)))
            }
            if (record.added > 0) {
                val more = stringResource(R.string.live_tv_scan_done_more, numbers.format(record.added))
                val added = stringResource(R.string.live_tv_scan_done_added)
                val names = when (record.addedNames.size) {
                    0 -> null
                    1 -> record.addedNames.single()
                    else -> stringResource(R.string.live_tv_scan_done_among_two, record.addedNames[0], record.addedNames[1])
                }
                val among = names?.let { stringResource(R.string.live_tv_scan_done_among, it) }
                ScanLine(
                    buildAnnotatedString {
                        good(more)
                        append(added)
                        among?.let { append(" "); append(it) }
                    }
                )
            }
            MarqueePanelActions {
                MarqueeGlassTextButton(
                    label = stringResource(R.string.live_tv_scan_open_live),
                    onClick = onOpenLiveTv,
                    modifier = Modifier.focusRequester(first)
                )
                MarqueeGlassTextButton(label = stringResource(R.string.live_tv_scan_close), onClick = onDismiss)
            }
        }
    }
}

/** The ticked-set key of No country listed (rememberSaveable keeps strings, not nulls). */
private const val NO_COUNTRY = "#none"
private const val TICK_MS = 5_000L
private const val DIM_ALPHA = 0.45f
private val SCAN_RULE = Color.White.copy(alpha = 0.12f)
