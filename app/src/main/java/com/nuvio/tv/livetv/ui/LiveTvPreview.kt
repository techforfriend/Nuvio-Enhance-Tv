package com.nuvio.tv.livetv.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.ChannelRepository
import com.nuvio.tv.livetv.player.DrmFailure
import com.nuvio.tv.livetv.player.FailureKind
import com.nuvio.tv.livetv.player.LiveDrm
import com.nuvio.tv.livetv.player.LiveFailure
import com.nuvio.tv.livetv.parse.EpgProgramme
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeProgress
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.ceil

// Live TV layout A preview and info block (sandbox u34-live-tv-marquee v3 `.lvpv`, `.lvnn`;
// Spec 34 D16 tuning / unavailable / no-guide states).

/** `.lvpv .g2`: dark at the top (LIVE) and bottom (channel label), clear in between. */
private val PREVIEW_SCRIM = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.35f),
    0.24f to Color.Transparent,
    0.58f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.8f)
)

/**
 * The preview's chrome (D16), drawn over the picture ([LiveTvPlayerView], placed by the screen so
 * full screen keeps the same view, O1 (a)): LIVE and the tuned channel; while tuning the last frame
 * dims under a spinner ("Tuning X", CH n · stream i of m); unavailable = the reason, the last
 * error and Retry / Next channel. [number] gives a channel's Q6 number.
 */
@Composable
internal fun LiveTvPreview(
    tune: LiveTuneState,
    number: (String) -> String?,
    timeFormat: DateFormat,
    retryRequester: FocusRequester,
    onRetry: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = NuvioTheme.marquee.liveTv
    Box(modifier = modifier.size(live.previewWidth, live.previewHeight)) {
        val channel = tune.channel.takeIf { tune !is LiveTuneState.Unavailable }
        when {
            tune is LiveTuneState.Unavailable -> LiveTvUnavailable(
                state = tune,
                number = number(tune.channel.key),
                timeFormat = timeFormat,
                retryRequester = retryRequester,
                onRetry = onRetry,
                onNextChannel = onNextChannel
            )
            channel != null -> {
                val ch = number(channel.key)?.let { stringResource(R.string.live_tv_channel_number, it) }
                Box(modifier = Modifier.fillMaxSize().background(PREVIEW_SCRIM))
                LiveTvLiveBadge(modifier = Modifier.padding(live.previewInset))
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = live.previewInset, end = live.previewInset, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveTvChannelTile(channel = channel, size = live.previewLogoSize, radius = live.logoRadius)
                    Text(
                        text = channel.shownName,
                        style = NuvioTheme.textStyles.cardLabel,
                        color = NuvioPrimitives.white,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (ch != null) {
                        Text(
                            text = "\u00b7 $ch",
                            style = NuvioTheme.textStyles.caption,
                            color = NuvioTheme.marquee.onImage2,
                            maxLines = 1
                        )
                    }
                }
                if (tune is LiveTuneState.Tuning) LiveTvTuning(tune, number(channel.key))
            }
        }
        LiveTvCornerMask(radius = live.previewRadius)
    }
}

/** A SurfaceView ignores Compose clipping: page-black corners of [radius] round the picture under it instead. */
@Composable
internal fun LiveTvCornerMask(radius: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val r = radius.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(size.toRect())
                    addRoundRect(RoundRect(size.toRect(), CornerRadius(r)))
                }
                drawPath(mask, NuvioPrimitives.black)
            }
    )
}

/** The tuning veil (`.lvpv .tune`): the last frame dimmed, a spinner, "Tuning X", CH [number] · stream i of m. */
@Composable
internal fun LiveTvTuning(tune: LiveTuneState.Tuning, number: String?, modifier: Modifier = Modifier) {
    val live = NuvioTheme.marquee.liveTv
    val ch = number?.let { stringResource(R.string.live_tv_channel_number, it) }
    Column(
        modifier = modifier.fillMaxSize().background(live.tuningVeil),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        MarqueeSpinner(size = live.tuningSpinnerSize)
        Text(
            text = stringResource(R.string.live_tv_tuning, tune.channel.shownName),
            style = NuvioTheme.textStyles.button,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val parts = listOfNotNull(
            ch,
            tune.streams.takeIf { it > 0 }?.let { stringResource(R.string.live_tv_stream_of, tune.stream, it) },
            tune.keySystem?.let(::keySystemLabel)
        )
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(FACT_SEPARATOR),
                style = NuvioTheme.textStyles.caption,
                color = NuvioTheme.marquee.onImage2,
                maxLines = 1
            )
        }
    }
}

/**
 * The live picture (one view for preview and full screen, O1 (a)): the screen keeps it at one
 * place in composition and only changes its size. The screen stays on while a channel tunes or plays.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvPlayerView(player: Player, keepScreenOn: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(context) {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // The last frame stays under the tuning veil while the next stream loads (D16).
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { it.keepScreenOn = keepScreenOn })
    DisposableEffect(view, player) {
        view.player = player
        onDispose {
            if (view.player === player) view.player = null
            view.keepScreenOn = false
        }
    }
}

@Composable
internal fun LiveTvUnavailable(
    state: LiveTuneState.Unavailable,
    number: String?,
    timeFormat: DateFormat,
    retryRequester: FocusRequester,
    onRetry: () -> Unit,
    onNextChannel: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val shape = RoundedCornerShape(live.previewRadius)
    val outcome = when {
        // The addon's stream lookup failed (its fixed reason is the last error).
        state.streams == 0 && state.failure.kind == FailureKind.NETWORK -> stringResource(R.string.live_tv_lookup_failed)
        state.streams == 0 -> stringResource(R.string.live_tv_no_streams)
        // Spec 74 D4: say how many needed a licence, so a licence refusal and a dead clear stream are told apart.
        state.tried >= state.streams && state.licensed >= state.streams ->
            pluralStringResource(R.plurals.live_tv_all_streams_need_licence, state.streams, state.streams)
        state.tried >= state.streams && state.licensed > 0 ->
            pluralStringResource(R.plurals.live_tv_all_streams_failed, state.streams, state.streams) + FACT_SEPARATOR +
                pluralStringResource(R.plurals.live_tv_streams_need_licence, state.licensed, state.licensed)
        state.tried >= state.streams -> pluralStringResource(R.plurals.live_tv_all_streams_failed, state.streams, state.streams)
        else -> stringResource(R.string.live_tv_streams_tried, state.tried, state.streams)
    }
    val head = listOfNotNull(
        state.channel.shownName,
        number?.let { stringResource(R.string.live_tv_channel_number, it) },
        outcome
    ).joinToString(FACT_SEPARATOR)
    val lastError = stringResource(
        R.string.live_tv_last_error,
        liveFailureText(state.failure),
        timeFormat.format(Date(state.atMs))
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(live.errorFill, shape)
            .border(1.dp, live.errorEdge, shape)
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = live.warn,
            modifier = Modifier.size(live.errorIconSize).padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.live_tv_unavailable_title),
            style = NuvioTheme.textStyles.osdTitle.copy(fontWeight = FontWeight.Bold),
            color = NuvioPrimitives.white,
            maxLines = 1
        )
        Text(
            text = head + "\n" + lastError,
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage2,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MarqueeGlassTextButton(
                label = stringResource(R.string.live_tv_retry),
                onClick = onRetry,
                height = live.buttonHeight,
                horizontalPadding = live.buttonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall,
                modifier = Modifier.focusRequester(retryRequester)
            )
            MarqueeGlassTextButton(
                label = stringResource(R.string.live_tv_next_channel),
                onClick = onNextChannel,
                height = live.buttonHeight,
                horizontalPadding = live.buttonPaddingHorizontal,
                textStyle = NuvioTheme.textStyles.panelButtonSmall
            )
        }
    }
}

/**
 * The unavailable box's "last error": a lookup's fixed reason, a licence failure (Spec 74 D5: who
 * refused and how; never the licence URL, keys or headers), an HTTP status, else the kind.
 */
@Composable
private fun liveFailureText(failure: LiveFailure): String {
    failure.detail?.let { return it }
    if (failure.kind == FailureKind.DRM) return licenceFailureText(failure)
    failure.httpCode?.let { return httpStatusText(it) }
    return stringResource(
        when (failure.kind) {
            FailureKind.NETWORK -> R.string.live_tv_error_network
            FailureKind.STALL -> R.string.live_tv_error_stall
            FailureKind.PARSE -> R.string.live_tv_error_parse
            FailureKind.DRM -> R.string.live_tv_error_licence_not_accepted
            FailureKind.BLOCKED, FailureKind.DEAD -> R.string.live_tv_error_network
        }
    )
}

@Composable
private fun httpStatusText(code: Int): String =
    HTTP_REASONS[code]?.let { "$code $it" } ?: stringResource(R.string.live_tv_error_http, code)

@Composable
private fun licenceFailureText(failure: LiveFailure): String {
    val code = failure.httpCode
    return when {
        failure.drm == DrmFailure.UNSUPPORTED -> LiveDrm.displayName(failure.keySystem)
            ?.let { stringResource(R.string.live_tv_error_no_drm, it) }
            ?: stringResource(R.string.live_tv_error_no_drm_unknown)
        failure.drm == DrmFailure.NO_LICENCE -> stringResource(R.string.live_tv_error_no_licence)
        failure.drm == DrmFailure.REFUSED && code != null ->
            stringResource(R.string.live_tv_error_licence_refused, httpStatusText(code))
        else -> stringResource(R.string.live_tv_error_licence_not_accepted)
    }
}

/** A key system as the tuning line and the options stream row name it (Spec 74 D6); "DRM" when it has no known name. */
internal fun keySystemLabel(keySystem: String): String = LiveDrm.displayName(keySystem) ?: "DRM"

/** RFC 9110 reason phrases for the statuses the live policy treats as fatal (D7). */
private val HTTP_REASONS = mapOf(
    400 to "Bad Request",
    401 to "Unauthorized",
    403 to "Forbidden",
    404 to "Not Found",
    410 to "Gone",
    451 to "Unavailable For Legal Reasons"
)

/**
 * The info block (`.lvnn`, D16): it follows list focus at once. Now: eyebrow "Now · facts",
 * the programme, time range + progress + minutes left, NEXT; no current programme: the facts and
 * the channel name, plus "No guide" and the Choose guide pointer when no guide covers it. Key hints
 * under it, or the skip note when this channel gave up ([unavailable]). A lost channel (L8-D4):
 * [LiveTvLostInfo].
 */
@Composable
internal fun LiveTvInfo(
    row: LiveRow,
    nowMs: Long,
    unavailable: Boolean,
    timeFormat: DateFormat,
    modifier: Modifier = Modifier,
    sourceName: String? = null
) {
    if (row.lost) {
        LiveTvLostInfo(row, nowMs, sourceName, timeFormat, modifier)
        return
    }
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val accent = live.accent
    val facts = LiveTvModel.facts(row).joinToString(FACT_SEPARATOR)
    val now = row.now
    Column(
        modifier = modifier.width(live.previewWidth),
        verticalArrangement = Arrangement.spacedBy(live.infoGap)
    ) {
        val eyebrow = listOfNotNull(
            now?.let { stringResource(R.string.live_tv_now) },
            facts.ifEmpty { null }
        ).joinToString(FACT_SEPARATOR)
        if (eyebrow.isNotEmpty()) MarqueeEyebrow(text = eyebrow, accent = { accent })
        Text(
            text = now?.title ?: row.channel.shownName,
            style = NuvioTheme.textStyles.panelStateTitle,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (now != null) {
            LiveTvTimeLine(row, nowMs, timeFormat, live.infoProgressWidth)
            row.next?.let { LiveTvNextLine(it, timeFormat) }
        }
        if (!row.hasGuide) {
            Text(
                text = stringResource(R.string.live_tv_no_guide),
                style = NuvioTheme.textStyles.meta,
                color = marquee.onImage3,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.live_tv_no_guide_hint),
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage3,
                maxLines = 1
            )
        }
        if (unavailable) {
            Text(
                text = stringResource(R.string.live_tv_unavailable_note),
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage3,
                maxLines = 2
            )
        } else {
            LiveTvKeyHints(schedule = row.hasSchedule, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Stage 12 (`s_lost`, L8-D4): the facts, the name, when the channel went from [sourceName], until
 * when Favorites and Recents keep it, and that it returns with its guide pick; no key hints (focus
 * here does not tune).
 */
@Composable
private fun LiveTvLostInfo(row: LiveRow, nowMs: Long, sourceName: String?, timeFormat: DateFormat, modifier: Modifier) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val lostAt = row.channel.lostAtMs ?: return
    val source = sourceName ?: stringResource(R.string.live_tv_lost_source_unknown)
    val ago = liveTvAgePhrase(LiveTvSourcesModel.refreshAge(lostAt, nowMs), timeFormat)
    val until = Instant.ofEpochMilli(lostAt + ChannelRepository.LOST_RETENTION_MS).atZone(ZoneId.systemDefault()).toLocalDate()
    Column(
        modifier = modifier.width(live.previewWidth),
        verticalArrangement = Arrangement.spacedBy(live.infoGap)
    ) {
        val facts = LiveTvModel.facts(row).joinToString(FACT_SEPARATOR)
        if (facts.isNotEmpty()) MarqueeEyebrow(text = facts, accent = { live.accent })
        Text(
            text = row.channel.shownName,
            style = NuvioTheme.textStyles.panelStateTitle,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.live_tv_lost_gone, source, ago),
            style = NuvioTheme.textStyles.meta,
            color = marquee.onImage3,
            maxLines = 2
        )
        Text(
            text = stringResource(R.string.live_tv_lost_kept, formatDayMonth(until)) + "\n" +
                stringResource(R.string.live_tv_lost_returns, source),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3,
            maxLines = 3
        )
    }
}

/**
 * `.lvpr`: the current programme's time range, a [barWidth] progress bar and the minutes left
 * (info block, full-screen OSD, zap banner). [row] must have a current programme.
 */
@Composable
internal fun LiveTvTimeLine(row: LiveRow, nowMs: Long, timeFormat: DateFormat, barWidth: Dp) {
    val now = row.now ?: return
    val marquee = NuvioTheme.marquee
    val accent = marquee.liveTv.accent
    val minutesLeft = ceil((now.stopMs - nowMs).coerceAtLeast(0L) / 60_000.0).toInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(marquee.metaGap)
    ) {
        Text(
            text = timeFormat.format(Date(now.startMs)) + "\u2013" + timeFormat.format(Date(now.stopMs)),
            style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
            color = marquee.onImage2,
            maxLines = 1
        )
        MarqueeProgress(
            fraction = row.progress ?: 0f,
            label = pluralStringResource(R.plurals.live_tv_min_left, minutesLeft, minutesLeft),
            accent = { accent },
            width = barWidth
        )
    }
}

/** `.lvnext`: NEXT, the start time and title of the next programme. */
@Composable
internal fun LiveTvNextLine(next: EpgProgramme, timeFormat: DateFormat) {
    val marquee = NuvioTheme.marquee
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(marquee.metaGap)
    ) {
        Text(
            text = stringResource(R.string.live_tv_next_label),
            style = NuvioTheme.textStyles.eyebrow,
            color = marquee.onImage3,
            maxLines = 1
        )
        Text(
            text = timeFormat.format(Date(next.startMs)) + FACT_SEPARATOR + next.title,
            style = NuvioTheme.textStyles.meta.copy(fontFeatureSettings = "tnum"),
            color = marquee.onImage2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** `.lvhint`: OK Full screen, HOLD OK Options, CH ± Zap (outlined keys), ► Schedule when [schedule] (Spec 67 D3 Q1). */
@Composable
private fun LiveTvKeyHints(schedule: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_ok)), stringResource(R.string.live_tv_hint_full_screen))
        LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_hold_ok)), stringResource(R.string.live_tv_hint_options))
        LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_ch)), stringResource(R.string.live_tv_hint_zap))
        if (schedule) {
            LiveTvKeyHint(listOf(stringResource(R.string.live_tv_hint_right)), stringResource(R.string.live_tv_hint_schedule))
        }
    }
}

/** One `.lvhint` part: outlined [keys] ("or" between them), then the [label]. */
@Composable
internal fun LiveTvKeyHint(keys: List<String>, label: String) {
    val marquee = NuvioTheme.marquee
    val shape = RoundedCornerShape(marquee.liveTv.badgeRadius)
    val or = stringResource(R.string.live_tv_hint_or)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEachIndexed { i, key ->
            if (i > 0) Text(text = or, style = NuvioTheme.textStyles.caption, color = marquee.onImage3, maxLines = 1)
            Text(
                text = key,
                style = NuvioTheme.textStyles.techBadge,
                color = marquee.onImage2,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, NuvioPrimitives.white.copy(alpha = TAG_EDGE_ALPHA), shape)
                    .padding(horizontal = 5.dp)
            )
        }
        Text(text = label, style = NuvioTheme.textStyles.caption, color = marquee.onImage3, maxLines = 1)
    }
}
