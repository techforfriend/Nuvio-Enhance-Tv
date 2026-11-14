package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.EpgRef
import com.nuvio.tv.livetv.player.LiveDrm
import com.nuvio.tv.livetv.player.LiveStreamPick
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.livetv.player.StreamHealth
import com.nuvio.tv.livetv.player.StreamHealthRecord
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.net.URI
import java.text.DateFormat
import java.util.Date

private enum class PanelPage { OPTIONS, GUIDE }

/**
 * The hold-OK side panel of a channel row (sandbox v3 stages `lv-options` and `lv-guide-pick`;
 * Spec 34 D16, L7d, L8): the favorite toggle (OK adds or removes; the panel stays), Choose
 * guide (opens its own page; Back returns to it), Stream n of m (OK plays the next stream; never
 * on a lost channel, L8-D4), Hide channel. Always opened by a hold, so the hold's release never fires the first row. [onDismiss]: Back on
 * the options or a guide picked (the screen refocuses the row); [onHidden]: the channel was hidden
 * (the screen focuses a neighbour).
 */
@Composable
internal fun LiveTvOptionsPanel(
    viewModel: LiveTvViewModel,
    row: LiveRow,
    tune: LiveTuneState,
    timeFormat: DateFormat,
    onDismiss: () -> Unit,
    onHidden: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    val key = row.channel.key
    var page by remember(key) { mutableStateOf(PanelPage.OPTIONS) }
    val guide by produceState<LiveGuidePanel?>(null, key) { value = viewModel.guidePanel(key) }
    // Each page's first row takes focus when the page opens (the favorite toggle is the options' first);
    // Back from the guide page returns to Choose guide instead.
    val firstRow = remember(key, page) { FocusRequester() }
    val guideRow = remember(key, page) { FocusRequester() }
    var backFromGuide by remember(key) { mutableStateOf(false) }
    // In its own window, as the other top-bar tabs' panels: the veil dims the bar too.
    MarqueeSidePanelDialog(
        onDismissRequest = {
            if (page == PanelPage.GUIDE) {
                backFromGuide = true
                page = PanelPage.OPTIONS
            } else {
                onDismiss()
            }
        },
        width = if (page == PanelPage.GUIDE) live.guidePanelWidth else live.optionsPanelWidth,
        insetVertical = live.panelInsetVertical,
        initialFocus = if (page == PanelPage.OPTIONS && backFromGuide) guideRow else firstRow,
        fillHeight = false,
        openedByHold = true
    ) {
        when (page) {
            PanelPage.OPTIONS -> OptionsPage(
                viewModel = viewModel,
                row = row,
                tune = tune,
                guide = guide,
                timeFormat = timeFormat,
                firstRow = firstRow,
                guideRow = guideRow,
                onChooseGuide = { page = PanelPage.GUIDE },
                onHidden = onHidden
            )
            PanelPage.GUIDE -> GuidePage(
                row = row,
                guide = guide,
                firstRow = firstRow,
                onPick = { ref ->
                    viewModel.chooseGuide(key, ref)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun OptionsPage(
    viewModel: LiveTvViewModel,
    row: LiveRow,
    tune: LiveTuneState,
    guide: LiveGuidePanel?,
    timeFormat: DateFormat,
    firstRow: FocusRequester,
    guideRow: FocusRequester,
    onChooseGuide: () -> Unit,
    onHidden: () -> Unit
) {
    val key = row.channel.key
    val pick by viewModel.player.stream.collectAsState()
    val health by viewModel.streamHealth.collectAsState()
    val favorites by viewModel.favoriteKeys.collectAsState()
    PanelEyebrow(text = row.channel.shownName + FACT_SEPARATOR + stringResource(R.string.live_tv_channel_number, row.number))
    val favorite = key in favorites
    PlayerPanelRow(
        title = stringResource(if (favorite) R.string.live_tv_favorite_remove else R.string.live_tv_favorite_add),
        selected = false,
        trailingIcon = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
        focusRequester = firstRow,
        onClick = { viewModel.toggleFavorite(key) }
    )
    PlayerPanelRow(
        title = stringResource(R.string.live_tv_choose_guide),
        subtitle = guide?.let { guideSummary(it) },
        selected = false,
        enabled = guide != null,
        focusRequester = guideRow,
        onClick = onChooseGuide
    )
    // A lost channel is never tuned (L8-D4): its stream row has nothing to pick.
    val mine = pick?.takeIf { it.channelKey == key && !row.lost }
    val tunedHere = tune.channel?.key == key
    PlayerPanelRow(
        title = if (mine != null) stringResource(R.string.live_tv_stream_n_of_m, mine.position, mine.count)
        else stringResource(R.string.live_tv_stream),
        subtitle = when {
            mine != null -> streamSummary(mine, health, timeFormat)
            row.lost -> stringResource(R.string.live_tv_stream_none)
            tunedHere && tune is LiveTuneState.Tuning -> stringResource(R.string.live_tv_stream_finding)
            tunedHere -> stringResource(R.string.live_tv_stream_none)
            else -> stringResource(R.string.live_tv_stream_tune_first)
        },
        selected = false,
        trailingIcon = Icons.Filled.SwapHoriz,
        // Not tuned here: OK tunes it, and the row turns into "Stream n of m".
        enabled = if (mine != null) mine.count > 1 else !tunedHere && !row.lost,
        onClick = { if (mine != null) viewModel.nextStream() else viewModel.tuneNow(row.channel) }
    )
    PlayerPanelRow(
        title = stringResource(R.string.live_tv_hide_channel),
        subtitle = stringResource(R.string.live_tv_hide_channel_sub),
        selected = false,
        trailingIcon = Icons.Filled.VisibilityOff,
        onClick = {
            viewModel.hideChannel(key)
            onHidden()
        }
    )
    PanelNote(stringResource(R.string.live_tv_panel_note))
}

/** The Choose guide row's line: the guide in use ("source · now title"), else how many guides there are to search. */
@Composable
private fun guideSummary(guide: LiveGuidePanel): String {
    val current = guide.current
    return when {
        current != null -> listOfNotNull(
            guide.sourceNames[current.epgSourceId],
            guide.currentNow?.let { stringResource(R.string.live_tv_guide_now, it) }
        ).joinToString(FACT_SEPARATOR)
        guide.pickedNone -> pluralStringResource(R.plurals.live_tv_guide_none_picked, guide.guides, guide.guides)
        else -> pluralStringResource(R.plurals.live_tv_guide_none_yet, guide.guides, guide.guides)
    }
}

/**
 * "host · HLS · worked at 20:02" (D16): where the stream comes from, its type, and its last outcome;
 * a licensed stream names its key system in place of the type (Spec 74 D6, "host · Widevine · ...").
 */
@Composable
private fun streamSummary(pick: LiveStreamPick, health: Map<String, StreamHealthRecord>, timeFormat: DateFormat): String {
    val host = runCatching { URI(pick.stream.url.trim()).host }.getOrNull()?.removePrefix("www.")
    val type = LiveDrm.from(pick.stream.kodiProps)?.let { keySystemLabel(it.keySystem) } ?: when (pick.mimeType) {
        MimeTypes.APPLICATION_M3U8 -> "HLS"
        MimeTypes.APPLICATION_MPD -> "DASH"
        else -> null
    }
    val record = health[StreamHealth.key(pick.stream)]
    val played = record?.lastPlayedMs
    val failed = record?.lastFailedMs
    val outcome = when {
        played != null && played >= (failed ?: 0L) ->
            stringResource(R.string.live_tv_stream_worked_at, timeFormat.format(Date(played)))
        failed != null -> stringResource(R.string.live_tv_stream_failed_at, timeFormat.format(Date(failed)))
        else -> null
    }
    return listOfNotNull(host, type, outcome).joinToString(FACT_SEPARATOR)
}

@Composable
private fun ColumnScope.GuidePage(
    row: LiveRow,
    guide: LiveGuidePanel?,
    firstRow: FocusRequester,
    onPick: (EpgRef?) -> Unit
) {
    PanelEyebrow(text = stringResource(R.string.live_tv_choose_guide_title, row.channel.shownName))
    val candidates = guide?.candidates.orEmpty()
    val current = guide?.current
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        candidates.forEachIndexed { index, candidate ->
            PlayerPanelRow(
                title = candidate.name,
                subtitle = listOfNotNull(
                    guide?.sourceNames?.get(candidate.ref.epgSourceId),
                    candidate.now?.let { stringResource(R.string.live_tv_guide_now, it.title) }
                ).joinToString(FACT_SEPARATOR),
                selected = candidate.ref == current,
                focusRequester = if (index == 0) firstRow else null,
                onClick = { onPick(candidate.ref) }
            )
        }
        PlayerPanelRow(
            title = stringResource(R.string.live_tv_guide_off),
            subtitle = if (guide != null && current == null) stringResource(R.string.live_tv_guide_current) else null,
            selected = guide != null && current == null,
            focusRequester = if (candidates.isEmpty()) firstRow else null,
            onClick = { onPick(null) }
        )
    }
    PanelNote(stringResource(R.string.live_tv_choose_guide_note))
}

/** `.l8p .note`: a quiet caption under a panel's rows. */
@Composable
internal fun PanelNote(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)
    )
}
