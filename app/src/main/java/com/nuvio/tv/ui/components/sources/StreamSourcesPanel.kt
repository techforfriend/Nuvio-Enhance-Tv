package com.nuvio.tv.ui.components.sources

import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DecodeCapability
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.MissingUpstreamsTemplates
import com.nuvio.tv.core.stream.HiddenStream
import com.nuvio.tv.core.stream.ResolutionVerdict
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamPin
import com.nuvio.tv.core.stream.StreamLimitVerdict
import com.nuvio.tv.core.stream.resolutionLabel
import com.nuvio.tv.core.stream.resolutionVerdict
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.core.stream.limitVerdict
import com.nuvio.tv.core.stream.missingUpstreamsText
import com.nuvio.tv.core.stream.sourceChipAddonNames
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.CompactPanel
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlinx.coroutines.launch as coroutineLaunch

/**
 * Sources panel (approved sandbox `u5-stream-screen-marquee` stages 1, 3-7; the player's Sources
 * panel, `u6-player-panels-marquee` stage 1; the stream limit, `spec-51-network-stream-limit` v3;
 * the row and sheets, `spec-65-stream-filters` v5 stages 1-7, 10): the compact-panel shell at
 * the screen's end, the eyebrow ([SourcesEyebrow]), the row of circles and pinned chips
 * ([SourcesCircleRow]; [saved] holds the profile's saved filters and pins, changed through
 * [onSaveFilter] and [onTogglePin] from the sheets), the
 * hidden line when [view] lists hidden streams, a rule, then [streamPanelBody]: skeleton rows, the
 * empty / error block, or the [StreamSourceRow] list running to the panel edge. [view] is the
 * derived list (Spec 51 P4): its rows, effective selection, counts and filter values. [sheet] is
 * the sheet drawn over the panel ([SourcesSheet]); the caller holds it ([onSheetChange]) and closes
 * it on Back. While one is open focus cannot enter the panel under it; on closing, focus returns
 * to the sheet's circle. [markedIndex] (-1 = none) is the row carrying [marker]; [failureMarkFor]
 * gives a row's Spec 75 failure mark (reason + age; it also covers this session's dead sources),
 * null for none; [contextParts] (the player's episode sources, u6 stage 3: "S1 E4", the title) show
 * under the eyebrow. The list's focus model is the as-built one: the target row
 * ([sourcePanelInitialFocusIndex]: the first result, or the marked row when [focusMarkedRow],
 * window note N6) takes focus as results arrive until the user moves, Up from row 1 to the sources
 * circle, F-5 restore on Back from the player, and a re-derive that drops the focused row focuses
 * the first ([rowFocusAfterRederive]). Left/Right on a row do nothing (Spec 65 D19: views change
 * in the sheets). Entering the empty or error state decides focus once (U4d lesson): the sources
 * circle, or Retry, unless the row of circles already holds focus. [onPreferSource] (Spec 65 P6,
 * stages 12-14; null = no row menu: a film, or a list of another kind): hold OK on a row opens
 * [SourceRowMenu], not in a view listing hidden streams; it gets the stream to prefer for the show,
 * or null to stop. The view's leading [StreamListView.preferredCount] rows match the preference:
 * the first says "Preferred" ([sourceRowMarker]), and the menu on any of them offers to stop.
 */
@Composable
internal fun StreamSourcesPanel(
    isLoading: Boolean,
    error: String?,
    view: StreamListView,
    availableAddons: List<String>,
    sourceChips: List<SourceChipItem>,
    saved: SavedStreamFilters,
    showFileSizeBadges: Boolean,
    useAddonFormatting: Boolean,
    accent: () -> Color,
    sheet: SourcesPanelSheet?,
    onSheetChange: (SourcesPanelSheet?) -> Unit,
    onListSelected: (StreamListSelection) -> Unit,
    onFilterChange: (FacetFilter) -> Unit,
    /** Saves the filter's values under the name given (the values' own label). */
    onSaveFilter: (FacetFilter, String) -> Unit,
    onTogglePin: (StreamPin) -> Unit,
    onRefresh: () -> Unit,
    onStreamSelected: (Stream) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    marker: SourceRowMarker? = null,
    markedIndex: Int = -1,
    focusMarkedRow: Boolean = false,
    contextParts: List<String> = emptyList(),
    failureMarkFor: (Stream) -> SourceFailureMark? = { null },
    /** Upstreams a gateway reported as failed; named in a foot under the list (Spec 42 D7). */
    missingUpstreams: List<String> = emptyList(),
    // F-5 (Stream screen): restore focus to the played row ([Stream.limitKey]) on Back from the player.
    focusedStreamKey: String? = null,
    shouldRestoreFocusedStream: Boolean = false,
    onRestoreFocusedStreamHandled: () -> Unit = {},
    /** False while the panel is leaving (the player's exit slide): a sheet cleared then returns no focus. */
    returnFocusOnSheetClose: Boolean = true,
    /** Opens the saved filters' phone page from the filters sheet; null (the player's panels) = no phone button. */
    onEditFiltersOnPhone: (() -> Unit)? = null,
    onPreferSource: ((Stream?) -> Unit)? = null
) {
    val streams = view.streams
    val selection = view.selection
    // Spec 65 P8: what this device cannot play as released, for the rows' decode line. Known from
    // the first frame once the process has queried its decoders (auto-play does, before a list
    // shows); else nothing to say until the query, off the main thread, answers.
    val context = LocalContext.current.applicationContext
    val decode by produceState(
        remember(context) { DecodeCapability.currentIfProbed(context) } ?: DecodeCapability.UNKNOWN,
        context
    ) {
        value = DecodeCapability.current(context)
    }
    var enter by remember { mutableStateOf(false) }
    var targetFocusRequestId by remember { mutableStateOf(0) }
    // The last request the list carried out: a list that comes back (a filter that listed nothing
    // switched off from a pinned chip) must not replay it and pull focus off the row of circles.
    var servedTargetFocusRequestId by remember { mutableStateOf(0) }
    var listHasFocus by remember { mutableStateOf(false) }
    var userMovedFromTarget by remember { mutableStateOf(shouldRestoreFocusedStream) }
    var rowMenu by remember { mutableStateOf<SourceRowMenuTarget?>(null) }
    // Counts the prefer / stop choices: the list keeps the row they were made on in view as it moves.
    var followRowRequestId by remember { mutableStateOf(0) }
    var targetFocusAssigned by remember { mutableStateOf(shouldRestoreFocusedStream) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val orderedAddonNames = remember(availableAddons, sourceChips) {
        sourceChipAddonNames(availableAddons, sourceChips)
    }
    val streamKeys = remember(streams) { streamRowKeys(streams) }
    val targetIndex = sourcePanelInitialFocusIndex(markedIndex, focusMarkedRow)
    val targetStreamKey = streamKeys.getOrNull(targetIndex)
    val circleFocus = remember { SourcesCircleFocus() }
    /** Up from row 1: the sources circle takes focus, and the user has moved. */
    fun focusSourcesCircle() {
        userMovedFromTarget = true
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            circleFocus.sources.requestFocusAfterFrames(frames = 1)
        }
    }
    // The hidden sheet closes with its circle (nothing is hidden any more).
    LaunchedEffect(sheet, view.hiddenCount) {
        if (sheet == SourcesPanelSheet.HIDDEN && view.hiddenCount == 0) onSheetChange(null)
    }
    // A sheet that closed hands focus back to its circle; the sources circle when that one left.
    var previousSheet by remember { mutableStateOf(sheet) }
    val returnFocus by rememberUpdatedState(returnFocusOnSheetClose)
    LaunchedEffect(sheet) {
        val closed = if (sheet == null) previousSheet else null
        previousSheet = sheet
        if (closed == null || !returnFocus) return@LaunchedEffect
        // The hidden circle left with its sheet when nothing is hidden any more.
        val circleGone = closed == SourcesPanelSheet.HIDDEN && view.hiddenCount == 0
        if (circleGone || !circleFocus.of(closed).requestFocusAfterFrames(frames = 1)) {
            circleFocus.sources.requestFocusAfterFrames(frames = if (circleGone) 1 else 0)
        }
    }

    LaunchedEffect(Unit) {
        enter = true
    }
    LaunchedEffect(shouldRestoreFocusedStream) {
        if (shouldRestoreFocusedStream) {
            userMovedFromTarget = true
        }
    }
    LaunchedEffect(isLoading, targetStreamKey, userMovedFromTarget, targetFocusAssigned) {
        if (!isLoading && targetStreamKey != null && !userMovedFromTarget && !targetFocusAssigned) {
            targetFocusAssigned = true
            targetFocusRequestId += 1
        }
    }
    // On the All view, while the user has not moved, a target change as results arrive (a new
    // first item, or the marked row from a slower addon) moves focus to the new target.
    var trackedTargetStreamKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(targetStreamKey, selection, listHasFocus) {
        if (selection != StreamListSelection.All) {
            trackedTargetStreamKey = targetStreamKey
            return@LaunchedEffect
        }
        if (targetStreamKey != null && trackedTargetStreamKey != null &&
            targetStreamKey != trackedTargetStreamKey &&
            listHasFocus && !userMovedFromTarget
        ) {
            targetFocusRequestId += 1
        }
        trackedTargetStreamKey = targetStreamKey
    }
    var circlesHaveFocus by remember { mutableStateOf(false) }
    val body = streamPanelBody(isLoading = isLoading, hasError = error != null, hasStreams = streams.isNotEmpty())
    val retryFocusRequester = remember { FocusRequester() }
    // Focus is decided once per entry into a message state (U4d lesson): the circles keep it
    // when they hold it. The latch the list uses for first-result focus is left alone, so
    // results that follow still take focus.
    LaunchedEffect(body) {
        when (body) {
            StreamPanelBody.ERROR -> if (!circlesHaveFocus) retryFocusRequester.requestFocusAfterFrames()
            StreamPanelBody.EMPTY -> if (!circlesHaveFocus) circleFocus.sources.requestFocusAfterFrames(frames = 1)
            StreamPanelBody.LOADING, StreamPanelBody.LIST -> Unit
        }
    }

    val panel = NuvioTheme.marquee.stream
    val showHiddenLine = body == StreamPanelBody.LIST && view.listsHidden &&
        (view.limitVerdict != null || view.resolutionVerdict != null)
    val sheetRows = remember(orderedAddonNames, availableAddons, sourceChips, view) {
        sourcesSheetRows(orderedAddonNames, availableAddons, sourceChips, view)
    }
    val pins = remember(saved, view, availableAddons) { pinnedChips(saved, view, availableAddons) }
    Box(
        modifier = modifier
            .width(panel.panelWidth)
            .fillMaxHeight()
    ) {
        CompactPanel(
            modifier = Modifier
                .fillMaxSize()
                // Under an open sheet nothing takes focus: not by the D-pad, not a row's own request.
                .focusProperties { onEnter = { if (sheet != null) cancelFocusChange() } }
                .focusGroup(),
            contentPadding = PaddingValues(
                start = panel.panelPaddingHorizontal,
                top = panel.panelPaddingTop,
                end = panel.panelPaddingHorizontal
            )
        ) {
            SourcesEyebrow(
                view = view,
                isLoading = isLoading,
                accent = accent,
                modifier = Modifier.padding(horizontal = panel.eyebrowInset)
            )
            if (contextParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(panel.contextTop))
                PanelContextLine(parts = contextParts, modifier = Modifier.padding(horizontal = panel.eyebrowInset))
            }
            Spacer(modifier = Modifier.height(panel.panelGap))

            Box(
                modifier = Modifier
                    .height(panel.chipHeight)
                    .onFocusChanged { circlesHaveFocus = it.hasFocus }
            ) {
                SourcesCircleRow(
                    view = view,
                    mark = sourcesCircleMark(isLoading, sourceChips),
                    accent = accent,
                    focus = circleFocus,
                    onRefresh = {
                        userMovedFromTarget = false
                        targetFocusAssigned = false
                        onRefresh()
                    },
                    onOpenSheet = {
                        userMovedFromTarget = true
                        onSheetChange(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    pins = pins,
                    onPinClick = { chip ->
                        userMovedFromTarget = true
                        when (chip) {
                            is PinnedChip.Filter -> onFilterChange(chip.filterOnClick)
                            is PinnedChip.Addon -> onListSelected(chip.selectionOnClick)
                        }
                    }
                )
            }

            if (showHiddenLine) {
                Spacer(modifier = Modifier.height(panel.limitLineTop))
                HiddenStreamsLine(bitrate = view.limitVerdict, resolution = view.resolutionVerdict)
            }
            Spacer(modifier = Modifier.height(panel.panelGap))
            StreamRule()
            Spacer(modifier = Modifier.height(panel.panelGap))

            androidx.compose.animation.AnimatedVisibility(
                visible = enter,
                modifier = Modifier.weight(1f),
                enter = fadeIn(animationSpec = tween(260)) +
                    slideInHorizontally(
                        animationSpec = tween(260),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
                    ),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                // Content area: the panel is its surface.
                Box(modifier = Modifier.fillMaxSize()) {
                    when (body) {
                        StreamPanelBody.LOADING -> StreamSkeletonRows()
                        StreamPanelBody.ERROR -> StreamPanelMessage(
                            title = stringResource(R.string.stream_error_fetch_failed),
                            body = error,
                            // As Refresh: the rows that come back take focus again (Retry leaves).
                            onRetry = {
                                userMovedFromTarget = false
                                targetFocusAssigned = false
                                onRetry()
                            },
                            retryFocusRequester = retryFocusRequester
                        )
                        StreamPanelBody.EMPTY -> StreamPanelMessage(
                            title = stringResource(R.string.stream_no_streams),
                            // A filter emptied the view: the streams are there, the filter lists none.
                            body = stringResource(
                                if (view.filteredOut > 0) R.string.stream_no_streams_filtered else R.string.stream_no_streams_hint
                            )
                        )
                        StreamPanelBody.LIST -> StreamsList(
                            streams = streams,
                            streamKeys = streamKeys,
                            overLimitVerdicts = if (view.listsHidden) view.hiddenVerdicts else emptyMap(),
                            onStreamSelected = onStreamSelected,
                            focusedStreamKey = focusedStreamKey,
                            shouldRestoreFocusedStream = shouldRestoreFocusedStream,
                            onRestoreFocusedStreamHandled = onRestoreFocusedStreamHandled,
                            targetIndex = targetIndex,
                            targetStreamKey = targetStreamKey,
                            targetFocusRequestId =
                                targetFocusRequestId.takeIf { it != servedTargetFocusRequestId } ?: 0,
                            onTargetFocusRequestServed = { servedTargetFocusRequestId = it },
                            selection = selection,
                            showFileSizeBadges = showFileSizeBadges,
                            useAddonFormatting = useAddonFormatting,
                            marker = marker,
                            markedIndex = markedIndex,
                            failureMarkFor = failureMarkFor,
                            decode = decode,
                            accent = accent,
                            preferredCount = view.preferredCount,
                            followRowRequestId = followRowRequestId,
                            onRowMenu = if (onPreferSource != null && !view.listsHidden) {
                                { target ->
                                    // The viewer is acting on this row: results that follow leave focus on it.
                                    userMovedFromTarget = true
                                    rowMenu = target
                                }
                            } else {
                                null
                            },
                            onUpFromFirstRow = ::focusSourcesCircle,
                            onUserNavigatedFromTarget = {
                                userMovedFromTarget = true
                            },
                            onFocusChanged = { listHasFocus = it }
                        )
                    }
                }
            }
            if (body == StreamPanelBody.LIST) MissingUpstreamsFoot(missingUpstreams)
        }
        if (sheet != null) {
            SourcesSheet(
                sheet = sheet,
                view = view,
                rows = sheetRows,
                saved = saved,
                accent = accent,
                onListSelected = onListSelected,
                onFilterChange = onFilterChange,
                onSaveFilter = onSaveFilter,
                onTogglePin = onTogglePin,
                onClose = { onSheetChange(null) },
                onEditOnPhone = onEditFiltersOnPhone
            )
        }
    }
    val menu = rowMenu
    if (menu != null && onPreferSource != null) {
        SourceRowMenu(
            target = menu,
            accent = accent,
            onPlay = { stream ->
                rowMenu = null
                onStreamSelected(stream)
            },
            onPrefer = { stream ->
                rowMenu = null
                followRowRequestId += 1
                onPreferSource(stream)
            },
            onDismiss = { rowMenu = null }
        )
    }
}

/**
 * The panel's eyebrow (Spec 51 P6; Spec 65 stages 1, 3, 5, 7): "SOURCES · 14"; "19 OF 23" while
 * the limits hide streams or a filter is on, then the filter's values in the ambient [accent]
 * ("4 OF 16 · 4K · ATMOS"); "2 HIDDEN" in amber while [view] lists hidden streams. Its own
 * composable, so an animating accent recomposes only this line.
 */
@Composable
private fun SourcesEyebrow(
    view: StreamListView,
    isLoading: Boolean,
    accent: () -> Color,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.sources_title).uppercase()
    val shownOfTotal = eyebrowShownOfTotal(view)
    val count = when {
        isLoading -> "…"
        view.listsHidden -> stringResource(R.string.stream_eyebrow_hidden, view.streams.size)
        shownOfTotal != null ->
            stringResource(R.string.stream_sources_shown_of_total, shownOfTotal.first, shownOfTotal.second)
        else -> view.allCount.toString()
    }.uppercase()
    // Filters never apply to the hidden view, so it is not named over one.
    val filterName = facetFilterLabel(view.filter, stringResource(R.string.stream_facet_unknown))
        ?.takeIf { !view.listsHidden }
        ?.uppercase()
    val warn = NuvioTheme.marquee.stream.limitWarn
    val accentColor = if (filterName != null) accent() else Color.Unspecified
    val text = remember(title, count, filterName, view.listsHidden, isLoading, warn, accentColor) {
        buildAnnotatedString {
            append("$title · ")
            if (view.listsHidden && !isLoading) withStyle(SpanStyle(color = warn)) { append(count) } else append(count)
            if (filterName != null) {
                append(" · ")
                withStyle(SpanStyle(color = accentColor)) { append(filterName) }
            }
        }
    }
    PanelEyebrowText(text = text, modifier = modifier, maxLines = 1)
}

/** Centres the hidden line's 14 dp icon on its first 17 sp text line (the mockup's 1 px). */
private val HIDDEN_LINE_ICON_TOP = 1.dp

/**
 * The hidden view's line (Spec 51 P6, D7; Spec 58 D1/D3, sandbox `spec-58-stream-limit-follow-ups`
 * stages b-chip-open, b-mixed): a warning icon, then in white "Hidden: " and each reason present,
 * the resolution limit's first ("over your 1080p limit, or too fast to seek smoothly on this TV."),
 * then what the network test measured and the limit it keeps when [bitrate] came from it.
 * [bitrate] is [StreamListView.limitVerdict] (the hidden sheet's own figure and service), [resolution]
 * [StreamListView.resolutionVerdict]; at least one is set. Up to three lines (the approved line wraps
 * to three). Not focusable.
 */
@Composable
internal fun HiddenStreamsLine(bitrate: StreamLimitVerdict?, resolution: ResolutionVerdict?) {
    val tokens = NuvioTheme.marquee.stream
    val source = bitrate?.source
    val resolutionWhy = resolution?.let {
        val label = resolutionLabel(it.cap)
        if (it.fromTv) {
            stringResource(R.string.stream_limit_why_resolution_tv, label)
        } else {
            stringResource(R.string.stream_limit_why_resolution, label)
        }
    }
    val bitrateWhy = when {
        bitrate == null -> null
        source != null -> stringResource(R.string.stream_limit_why_network)
        else -> stringResource(R.string.stream_limit_why_manual, bitrate.figureMbps)
    }
    val lead = if (resolutionWhy != null && bitrateWhy != null) {
        stringResource(R.string.stream_limit_hidden_two, resolutionWhy, bitrateWhy)
    } else {
        stringResource(R.string.stream_limit_hidden_one, resolutionWhy ?: bitrateWhy.orEmpty())
    }
    val rest = if (bitrate != null && source != null) {
        val date = formatDayMonth(Instant.ofEpochMilli(source.testedAtMs).atZone(ZoneId.systemDefault()).toLocalDate())
        stringResource(R.string.stream_limit_hidden_network, date, source.measuredMbps, source.serviceKey, bitrate.figureMbps)
    } else {
        null
    }
    val text = remember(lead, rest) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = NuvioPrimitives.white, fontWeight = FontWeight.SemiBold)) { append(lead) }
            if (rest != null) append(" $rest")
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = tokens.eyebrowInset),
        horizontalArrangement = Arrangement.spacedBy(tokens.limitLineIconGap),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = tokens.limitWarn,
            modifier = Modifier
                .padding(top = HIDDEN_LINE_ICON_TOP)
                .size(tokens.limitIconSize)
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.addonText,
            color = NuvioTheme.marquee.onImage2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}


/**
 * The eyebrow's "shown of total" while the limit hides streams (Spec 51 P6: "19 OF 23"; "0 OF 23"
 * for a view listing only hidden streams), or a filter is on (Spec 65: "4 OF 16", All's rows under
 * the filter); null when neither (the eyebrow counts All).
 */
internal fun eyebrowShownOfTotal(view: StreamListView): Pair<Int, Int>? = when {
    // allCount counts hidden rows when nothing else is listed (deriveStreamList's count rule), never shown ones.
    !view.filter.isEmpty -> (if (view.total == view.hiddenCount) 0 else view.allCount) to view.total
    view.hiddenCount > 0 -> (view.total - view.hiddenCount) to view.total
    else -> null
}

/**
 * A hidden row's bitrate as shown (Spec 51 P6): rounded up, so a stream hidden for being over a
 * whole-Mbps figure never reads as that figure (133.2 over 133 shows 134).
 */
internal fun overLimitMbpsLabel(verdict: StreamLimitVerdict): Int = ceil(verdict.estimateMbps).toInt()

/**
 * The row to focus after a re-derive (Spec 51 P6, r3 / R4-5): when the list held focus on
 * [focusedRow], the effective [selection] is still [selectionBefore] and [streamKeys] no longer
 * hold that row (a late runtime, an addon filling an empty view, a network test writing tables),
 * the view's first row; else null. A changed selection (picked in a sheet) is not a re-derive:
 * focus returns to that sheet's circle. [rescuePending]: an earlier rescue was cut short by these
 * rows before focus landed, so the new first row takes it, under the same guards: a changed
 * selection or a focused row that survives (the user moved meanwhile) cancels it.
 */
internal fun rowFocusAfterRederive(
    focusedRow: String?,
    selectionBefore: StreamListSelection?,
    selection: StreamListSelection,
    streamKeys: List<String>,
    rescuePending: Boolean = false
): String? = when {
    selectionBefore != selection || (focusedRow != null && focusedRow in streamKeys) -> null
    rescuePending || focusedRow != null -> streamKeys.firstOrNull()
    else -> null
}

/**
 * The row F-5 restores (Spec 51 n3): the played stream, found by [Stream.limitKey] so a re-derive
 * that reorders the list cannot move it; null when the view no longer lists it (now hidden, or
 * gone), where the caller falls back to the first row.
 */
internal fun restoreRowIndex(streams: List<Stream>, focusedStreamKey: String?): Int? =
    focusedStreamKey?.let { key -> streams.indexOfFirst { it.limitKey() == key }.takeIf { it >= 0 } }

/** Upstream names the foot shows before "and N more" (Spec 42 D7). */
private const val FOOT_UPSTREAM_NAMES = 2

/**
 * The missing-upstreams foot (Spec 42 D7, sandbox `s42b-stream-upstreams` stage 4): a rule, then
 * "ComeTorz and Knaben didn't answer · results without them" in the warning colour behind a
 * warning icon. Not focusable; nothing when no gateway reported a failed upstream.
 */
@Composable
private fun MissingUpstreamsFoot(names: List<String>) {
    val templates = MissingUpstreamsTemplates(
        one = stringResource(R.string.stream_upstreams_missing_one),
        list = stringResource(R.string.stream_upstreams_missing_list),
        more = stringResource(R.string.stream_upstreams_missing_more)
    )
    val line = remember(names, templates) { missingUpstreamsText(names, FOOT_UPSTREAM_NAMES, templates) } ?: return
    val suffix = stringResource(R.string.stream_upstreams_missing_suffix)
    val panel = NuvioTheme.marquee.stream
    val dim = NuvioTheme.marquee.onImage3
    val text = remember(line, suffix, dim) {
        buildAnnotatedString {
            append(line)
            withStyle(SpanStyle(color = dim)) { append(" · $suffix") }
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        StreamRule()
        Row(
            modifier = Modifier.padding(
                start = panel.eyebrowInset,
                top = panel.missingFootTop,
                end = panel.eyebrowInset,
                bottom = panel.missingFootBottom
            ),
            horizontalArrangement = Arrangement.spacedBy(panel.missingIconGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = panel.missingText,
                modifier = Modifier.size(panel.missingIconSize)
            )
            Text(
                text = text,
                style = NuvioTheme.textStyles.caption,
                color = panel.missingText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The row the panel focuses as results arrive: [markedIndex] when [focusMarkedRow] (the player
 * opens on the playing source, window note N6), else the first result; the first when nothing
 * is marked.
 */
internal fun sourcePanelInitialFocusIndex(markedIndex: Int, focusMarkedRow: Boolean): Int =
    if (focusMarkedRow) markedIndex.coerceAtLeast(0) else 0

/** LazyColumn keys: [Stream.stableKey] with an occurrence count, so repeated streams stay distinct. */
private fun streamRowKeys(streams: List<Stream>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return streams.map { stream ->
        val base = stream.stableKey(0)
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        stream.stableKey(occurrence)
    }
}

/** The line under the eyebrow: [parts] in `rowTitle` white, joined by a dimmed separator (`.ctx`). */
@Composable
private fun PanelContextLine(parts: List<String>, modifier: Modifier = Modifier) {
    val separatorColor = NuvioTheme.marquee.onImage3
    val text = remember(parts, separatorColor) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) withStyle(SpanStyle(color = separatorColor, fontWeight = FontWeight.Medium)) { append(" · ") }
                append(part)
            }
        }
    }
    Text(
        text = text,
        style = NuvioTheme.textStyles.rowTitle,
        color = NuvioPrimitives.white,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** What the sources panel shows under its circles (stages 1, 4, 6, 7). */
internal enum class StreamPanelBody { LOADING, ERROR, EMPTY, LIST }

/** The as-built order: loading first, then a fetch error, then no streams, else the list. */
internal fun streamPanelBody(isLoading: Boolean, hasError: Boolean, hasStreams: Boolean): StreamPanelBody = when {
    isLoading -> StreamPanelBody.LOADING
    hasError -> StreamPanelBody.ERROR
    !hasStreams -> StreamPanelBody.EMPTY
    else -> StreamPanelBody.LIST
}

private const val SKELETON_ROW_COUNT = 4
private val SKELETON_BAR_RADIUS = 4.dp
/** (width as a fraction of the row's content width, height): the name, two description lines. */
private val SKELETON_LINES = listOf(0.62f to 16.dp, 0.88f to 11.dp, 0.70f to 11.dp)
private val SKELETON_BADGE_HEIGHT = 16.dp
private val SKELETON_BADGE_WIDTHS = listOf(28.dp, 84.dp, 96.dp)

/** Stage 4: static skeleton rows in the rows' geometry (no shimmer, the Home loading rule). */
@Composable
private fun StreamSkeletonRows() {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val fill = marquee.skeletonFill
    val padV = tokens.skeletonRowPaddingVertical
    val padH = tokens.rowPaddingHorizontal
    val gap = tokens.skeletonLineGap
    val rowHeight = padV * 2 + SKELETON_LINES.fold(0.dp) { sum, (_, height) -> sum + height + gap } + SKELETON_BADGE_HEIGHT
    Column(verticalArrangement = Arrangement.spacedBy(tokens.rowGap)) {
        repeat(SKELETON_ROW_COUNT) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(tokens.rowRadius))
                    .background(tokens.skeletonRowFill)
                    .drawBehind {
                        val contentWidth = size.width.toDp() - padH * 2
                        var y = padV
                        SKELETON_LINES.forEach { (fraction, height) ->
                            marqueeSkeletonBar(fill, padH, y, contentWidth * fraction, height, SKELETON_BAR_RADIUS)
                            y += height + gap
                        }
                        var x = padH
                        SKELETON_BADGE_WIDTHS.forEach { width ->
                            marqueeSkeletonBar(fill, x, y, width, SKELETON_BADGE_HEIGHT, SKELETON_BAR_RADIUS)
                            x += width + tokens.badgeGap
                        }
                    }
            )
        }
    }
}

/**
 * Stages 6-7: the in-panel message: "SOURCES" eyebrow, [title], [body], and Retry when [onRetry]
 * is set. [body] is skipped when blank or the same as the title (the repository's fallback error
 * text is the title string). The panel-sized sibling of MarqueeMessage, which is a full-screen
 * black layer with a larger title, another body colour and gap.
 */
@Composable
private fun StreamPanelMessage(
    title: String,
    body: String?,
    onRetry: (() -> Unit)? = null,
    retryFocusRequester: FocusRequester? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val styles = NuvioTheme.textStyles
    Column(
        modifier = Modifier.padding(
            horizontal = tokens.messagePaddingHorizontal,
            vertical = tokens.messagePaddingVertical
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.messageGap)
    ) {
        Text(
            text = stringResource(R.string.sources_title).uppercase(),
            style = styles.eyebrow,
            color = marquee.onImage3
        )
        Text(text = title, style = styles.panelStateTitle, color = NuvioPrimitives.white)
        if (!body.isNullOrBlank() && body != title) {
            Text(text = body, style = styles.bodyHome, color = tokens.messageBody)
        }
        if (onRetry != null) {
            Box(modifier = Modifier.padding(top = tokens.messageActionTop)) {
                MarqueePrimaryButton(
                    label = stringResource(R.string.stream_retry),
                    icon = Icons.Default.Refresh,
                    onClick = onRetry,
                    focusRequester = retryFocusRequester
                )
            }
        }
    }
}

/**
 * The list's focus as of the last focus callback and composition, in plain fields: written by
 * focus callbacks and after each composition, read when the rows change (no recomposition per
 * focus move).
 */
private class ListFocusMemo {
    var hasFocus = false
    var rowKey: String? = null
    var selection: StreamListSelection? = null
    /** A first-row rescue is under way; a restart by new rows keeps it (rowFocusAfterRederive). */
    var rescuePending = false
    /** The row a prefer / stop choice was made on: scrolled back into view once the list moves it. */
    var followRowKey: String? = null
}

/** A row's last layout position; read when its menu opens, never observed (rows scroll). */
private class RowAnchor {
    var coordinates: LayoutCoordinates? = null
}

/** Whether the item at [index] lies wholly inside the list's viewport. */
private fun LazyListState.showsWhole(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
}

/** The list's per-request focus attempts: one per frame, about half a second. */
private const val FOCUS_RETRY_FRAMES = 30

/**
 * Scrolls [index] into view, then requests [requester] once a frame until [focused] holds
 * (at most [FOCUS_RETRY_FRAMES]): an off-screen row has no focus target until it is composed.
 */
private suspend fun LazyListState.scrollAndFocusRow(
    index: Int,
    requester: () -> FocusRequester?,
    focused: () -> Boolean
) {
    scrollToItem(index)
    repeat(FOCUS_RETRY_FRAMES) {
        withFrameNanos { }
        if (focused()) return
        runCatching { requester()?.requestFocus() }
    }
}

@Composable
private fun StreamsList(
    streams: List<Stream>,
    streamKeys: List<String>,
    overLimitVerdicts: Map<String, HiddenStream>,
    onStreamSelected: (Stream) -> Unit,
    focusedStreamKey: String? = null,
    shouldRestoreFocusedStream: Boolean = false,
    onRestoreFocusedStreamHandled: () -> Unit = {},
    targetIndex: Int,
    targetStreamKey: String?,
    targetFocusRequestId: Int = 0,
    onTargetFocusRequestServed: (Int) -> Unit = {},
    selection: StreamListSelection,
    showFileSizeBadges: Boolean = true,
    useAddonFormatting: Boolean,
    marker: SourceRowMarker?,
    markedIndex: Int,
    failureMarkFor: (Stream) -> SourceFailureMark?,
    decode: DecodeCapability,
    accent: () -> Color,
    preferredCount: Int = 0,
    followRowRequestId: Int = 0,
    onRowMenu: ((SourceRowMenuTarget) -> Unit)? = null,
    onUpFromFirstRow: () -> Unit = {},
    onUserNavigatedFromTarget: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val tokens = NuvioTheme.marquee.stream
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val restoreFocusRequester = remember { FocusRequester() }
    val streamListState = rememberLazyListState()
    val streamFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    streamKeys.forEach { key ->
        streamFocusRequesters.getOrPut(key) { FocusRequester() }
    }
    var targetRowHasFocus by remember(targetStreamKey) { mutableStateOf(false) }
    // Reset scroll position to the top when the selection changes (#2538), not on entry: Back
    // from the player restores the list's saved scroll, and the played source must stay composed
    // for its restore (F-5).
    var lastScrolledSelection by remember { mutableStateOf(selection) }
    LaunchedEffect(selection) {
        if (selection == lastScrolledSelection) return@LaunchedEffect
        lastScrolledSelection = selection
        streamListState.scrollToItem(0)
    }

    LaunchedEffect(targetFocusRequestId) {
        val requestedKey = targetStreamKey
        if (targetFocusRequestId <= 0 || requestedKey == null) return@LaunchedEffect
        streamListState.scrollAndFocusRow(targetIndex, { streamFocusRequesters[requestedKey] }) { targetRowHasFocus }
        // Not reached when the list leaves first: the request is still owed when it returns.
        onTargetFocusRequestServed(targetFocusRequestId)
    }

    // A re-derive that drops the focused row (rowFocusAfterRederive): read the focus in the
    // composition that brings the new rows, before the apply disposes that row and the list
    // reports focus lost (R4-5), then focus the first row. Its own request: targetFocusRequestId
    // aims at the marked row in the player (focusMarkedRow).
    val focusMemo = remember { ListFocusMemo() }
    val rowFocusBefore = remember(streamKeys) {
        rowFocusAfterRederive(
            focusedRow = focusMemo.rowKey.takeIf { focusMemo.hasFocus },
            selectionBefore = focusMemo.selection,
            selection = selection,
            streamKeys = streamKeys,
            rescuePending = focusMemo.rescuePending
        )
    }
    SideEffect { focusMemo.selection = selection }
    LaunchedEffect(streamKeys) {
        val firstKey = rowFocusBefore ?: run {
            focusMemo.rescuePending = false
            return@LaunchedEffect
        }
        focusMemo.rescuePending = true
        streamListState.scrollAndFocusRow(0, { streamFocusRequesters[firstKey] }) {
            focusMemo.hasFocus && focusMemo.rowKey == firstKey
        }
        // Not reached when new rows cancel this effect: the restarted one takes the rescue over.
        focusMemo.rescuePending = false
    }

    // Spec 65 P6: a prefer / stop choice moves its row (to the top, or back down). The row keeps
    // focus (a focused item stays composed), so the list scrolls to it once it has moved out of view.
    LaunchedEffect(followRowRequestId) {
        if (followRowRequestId > 0) focusMemo.followRowKey = focusMemo.rowKey
    }
    LaunchedEffect(streamKeys) {
        val followKey = focusMemo.followRowKey ?: return@LaunchedEffect
        val index = streamKeys.indexOf(followKey)
        // The viewer moved on, or the row left the list: nothing to follow any more.
        if (focusMemo.rowKey != followKey || index < 0) {
            focusMemo.followRowKey = null
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (!streamListState.showsWhole(index)) {
            focusMemo.followRowKey = null
            streamListState.animateScrollToItem(index)
        }
    }

    val restoreRow = remember(streams, focusedStreamKey) { restoreRowIndex(streams, focusedStreamKey) }
    val restoreIndex = restoreRow ?: 0
    LaunchedEffect(shouldRestoreFocusedStream, restoreIndex, streams.size) {
        if (!shouldRestoreFocusedStream) return@LaunchedEffect
        if (streams.isEmpty()) {
            onRestoreFocusedStreamHandled()
            return@LaunchedEffect
        }
        // The played row is no longer listed: the first row, which the saved scroll may have off-screen.
        if (restoreRow == null) streamListState.scrollToItem(0)
        restoreFocusRequester.requestFocusAfterFrames()
        onRestoreFocusedStreamHandled()
    }

    LazyColumn(
        state = streamListState,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                focusMemo.hasFocus = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }
                // Up counts too: a target below row 1 can move up (the player's N6 row).
                if (event.key == Key.DirectionDown || event.key == Key.DirectionUp) {
                    onUserNavigatedFromTarget()
                }
                // Left/Right do nothing on a row (Spec 65 D19); consumed so focus never leaves
                // the list sideways.
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight
            },
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap),
        contentPadding = PaddingValues(bottom = tokens.panelPaddingHorizontal)
    ) {
        itemsIndexed(streams, key = { index, _ ->
            streamKeys[index]
        }) { index, stream ->
            val rowKey = streamKeys[index]
            val focusRequester = when {
                shouldRestoreFocusedStream && index == restoreIndex -> restoreFocusRequester
                else -> streamFocusRequesters.getValue(rowKey)
            }
            val overLimit = remember(stream, overLimitVerdicts) {
                if (overLimitVerdicts.isEmpty()) null else overLimitVerdicts[stream.limitKey()]
            }
            // No connection warm on focus (T25): focus is not consent, and a GET on a
            // resolve-style addon URL asks the debrid service for a file the user never
            // picked. The press warms (S1g, onStreamSelected).
            val anchor = remember { RowAnchor() }
            StreamSourceRow(
                stream = stream,
                showSize = showFileSizeBadges,
                useAddonFormatting = useAddonFormatting,
                marker = sourceRowMarker(index, marker, markedIndex, preferredCount),
                onLongPress = onRowMenu?.let { open ->
                    {
                        anchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let { bounds ->
                            open(SourceRowMenuTarget(stream, bounds, preferred = index < preferredCount))
                        }
                    }
                },
                failureMark = remember(stream, failureMarkFor) { failureMarkFor(stream) },
                overLimitMbps = overLimit?.bitrate?.let(::overLimitMbpsLabel),
                overResolutionCap = overLimit?.resolution?.cap,
                decode = decode,
                accent = accent,
                onClick = { onStreamSelected(stream) },
                modifier = Modifier
                    .then(if (onRowMenu != null) Modifier.onGloballyPositioned { anchor.coordinates = it } else Modifier)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) focusMemo.rowKey = rowKey
                        if (index == targetIndex) targetRowHasFocus = it.isFocused
                    }
                    .then(
                        if (index == 0) {
                            Modifier
                                .onKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.key == Key.DirectionUp) {
                                        onUpFromFirstRow()
                                        true
                                    } else false
                                }
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

/** The panel's and its sheets' 1 dp `stream.rule`. */
@Composable
internal fun StreamRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(NuvioTheme.marquee.stream.rule)
    )
}
