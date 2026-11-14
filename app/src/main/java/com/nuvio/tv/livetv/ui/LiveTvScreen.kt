@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalHideTopBar
import com.nuvio.tv.R
import com.nuvio.tv.livetv.player.LiveTuneState
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePickerOption
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.marqueeSkeletonBar
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.abs

/**
 * Live TV, layout A "List + preview" (approved sandbox `u34-live-tv-marquee` v3; Spec 34 D16,
 * L7b): filter pills at y 76 (select on focus), the channel list left, the preview and the info
 * block right; the static skeleton while the stores load and the "Add a channel source" empty
 * state. Always a top-bar tab: content starts at the bar's `contentTop`. OK on a row goes full
 * screen (L7c, [LiveTvFullScreen]; the bar hides; nothing on a lost row, L8-D4); hold OK opens its
 * side panel (L7d, [LiveTvOptionsPanel]); CH +/- zap and focus follows (L8-D6). The preview plays
 * only while the screen is started (another tab or the background stops it). [onOpenSources]
 * opens Live TV › Sources (L7e): false from the Sources pill, true from the empty state's "Add
 * source URL" (its dialog opens too).
 */
@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    onOpenSources: (addSource: Boolean, scan: Boolean) -> Unit = { _, _ -> },
    openCountry: String? = null,
    onCountryOpened: () -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsState()
    val packOffer by viewModel.packOffer.collectAsState()
    // Spec 73 D3: the pack added from the empty state opens Sources on the scan's country list.
    LaunchedEffect(viewModel) { viewModel.packAdded.collect { onOpenSources(false, true) } }
    // Spec 73 S3b: back from a scan's "Open Live TV": the list filtered to its country (blank = All).
    LaunchedEffect(openCountry) {
        val country = openCountry ?: return@LaunchedEffect
        viewModel.selectFilter(LiveTvFilter.Browse(country = country.ifEmpty { null }))
        onCountryOpened()
    }
    LifecycleStartEffect(viewModel) {
        viewModel.resumePreview()
        onStopOrDispose { viewModel.stopPreview() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        when {
            ui.loading -> LiveTvSkeleton()
            !ui.hasChannelSources -> LiveTvEmpty(
                offer = packOffer,
                onShown = viewModel::loadPackSummary,
                onAddPack = viewModel::addPack,
                onAddBroadcasters = viewModel::addBroadcasters,
                onAddSourceUrl = { onOpenSources(true, false) }
            )
            else -> LiveTvContent(viewModel = viewModel, ui = ui, onOpenSources = { onOpenSources(false, false) })
        }
    }
}

@Composable
private fun LiveTvContent(viewModel: LiveTvViewModel, ui: LiveTvUiState, onOpenSources: () -> Unit) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val tune by viewModel.player.state.collectAsState()
    val player by viewModel.player.player.collectAsState()
    val info by viewModel.info.collectAsState()
    val focusedKey by viewModel.focusedKey.collectAsState()
    val sourceNames by viewModel.sourceNames.collectAsState()
    val schedule by viewModel.schedule.collectAsState()
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val contentRequester = LocalContentFocusRequester.current
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val requesterFor = { key: String -> rowRequesters.getOrPut(key) { FocusRequester() } }
    val pillRequesters = remember { mutableMapOf<LiveTvFilter, FocusRequester>() }
    val selectedPill = pillRequesters.getOrPut(if (ui.filter is LiveTvFilter.Browse) PICKERS_PILL else ui.filter) {
        FocusRequester()
    }
    val retryRequester = remember { FocusRequester() }
    val rows = ui.rows
    val rowsEmpty = rows.isEmpty()
    // The Guide and Compact views (Spec 67 G3c): the band over the grid in place of list + preview.
    val guide = ui.guide
    // Both live only while the grid shows: reopened from the View picker it starts afresh on List's row.
    val gridShown = guide != null
    // Seeded on List's row, a few rows down like the list (a state made once per showing: a grid back
    // from full screen or an empty filter keeps its scroll).
    val gridListState = remember(gridShown) {
        LazyListState((rows.indexOfFirst { it.channel.key == viewModel.focusedKey.value } - live.focusRowsAbove).coerceAtLeast(0))
    }
    val guideCursor = remember(gridShown) { GuideCursor() }
    val gridRequester = remember { FocusRequester() }
    var fullScreen by remember { mutableStateOf(false) }
    // The row whose hold-OK side panel is open (L7d) and its index then; null when closed.
    var panelRow by remember { mutableStateOf<LiveRow?>(null) }
    var panelIndex by remember { mutableIntStateOf(-1) }
    val hideTopBar = LocalHideTopBar.current
    DisposableEffect(fullScreen, hideTopBar) {
        hideTopBar(fullScreen)
        onDispose { hideTopBar(false) }
    }

    val playingKey = tune.channel?.key.takeIf { tune !is LiveTuneState.Unavailable }
    val unavailableKey = (tune as? LiveTuneState.Unavailable)?.channel?.key

    /**
     * Focus [key]'s row (scrolled to sit at most `focusRowsAbove` down; in a grid view its grid row at
     * the kept time), else the selected pill.
     */
    val focusRowOrPill = { key: String? ->
        scope.launch {
            val index = rows.indexOfFirst { it.channel.key == key }
            if (key != null && index >= 0 && guide != null) {
                guideCursor.focus(key, rows, guide, ui.nowMs)
                gridRequester.requestFocusAfterFrames()
            } else if (key != null && index >= 0) {
                listState.scrollToItem((index - live.focusRowsAbove).coerceAtLeast(0))
                requesterFor(key).requestFocusAfterFrames()
            } else {
                selectedPill.requestFocusAfterFrames()
            }
        }
    }

    // The day schedule (Spec 67 G2, D5/D6): the screen owns which open shows ([ScheduleOpenState],
    // like the options panel's panelRow), so closing removes the column in the same frame and the row
    // refocus that follows is never cancelled by the column's containment. The VM only derives the
    // data. Left or Back return to its channel's row (Q2); its channel or guide going away while
    // shown closes it back to the row too (unless full screen, whose exit focuses the row).
    val scheduleOpen = remember { ScheduleOpenState() }
    val shownSchedule = scheduleOpen.shown(schedule)
    val closeSchedule: (refocus: Boolean) -> Unit = { refocus ->
        val key = scheduleOpen.close() ?: viewModel.focusedKey.value
        viewModel.closeSchedule()
        if (refocus) focusRowOrPill(key)
    }
    LaunchedEffect(shownSchedule != null, scheduleOpen.openId) {
        if (scheduleOpen.lostData(shownSchedule != null)) closeSchedule(!fullScreen)
    }
    BackHandler(enabled = scheduleOpen.isOpen && !fullScreen) { closeSchedule(true) }

    // Initial focus (Q1), once: the last-focused channel's row, else the first row; the selected
    // pill when the filter lists nothing (Down enters the list once it has rows).
    LaunchedEffect(Unit) {
        if (rowsEmpty) selectedPill.requestFocusAfterFrames()
        else focusRowOrPill(focusedKey?.takeIf { k -> rows.any { it.channel.key == k } } ?: rows.first().channel.key)
    }
    // Down pressed on a pill before its filter's rows were derived: entered once they land.
    var pendingEnter by remember { mutableStateOf(false) }
    // A new filter starts at its top (Down from the pills enters its first row).
    var shownFilter by remember { mutableStateOf(ui.filter) }
    LaunchedEffect(ui.filter) {
        if (ui.filter == shownFilter) return@LaunchedEffect
        shownFilter = ui.filter
        // Requested, not awaited: a list never laid out (the view not shown) would hold the effect forever.
        listState.requestScrollToItem(0)
        gridListState.requestScrollToItem(0)
        // The grid's cursor learns of every filter change here, empty filters included (G3c-Q1).
        guide?.let { guideCursor.filterChanged(rows, it, ui.nowMs) }
        if (pendingEnter && viewModel.selectedFilter == ui.filter) {
            pendingEnter = false
            rows.firstOrNull()?.let { focusRowOrPill(it.channel.key) }
        }
    }

    // One picture for preview and full screen (O1 (a)): this call site never moves, only its size
    // changes, so the PlayerView and its surface survive the switch.
    // The schedule column shrinks it to its head (Spec 67 G2), the same view at another size.
    Box(
        modifier = when {
            fullScreen -> Modifier.fillMaxSize()
            guide != null -> Modifier
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = live.guideBandTop)
                .size(live.guideBandPictureWidth, live.guideBandHeight)
                .background(live.previewFill)
            shownSchedule != null -> Modifier
                .padding(start = live.previewStart, top = live.previewTop)
                .size(live.scheduleHeadWidth, live.scheduleHeadHeight)
                .background(live.previewFill)
            else -> Modifier
                .padding(start = live.previewStart, top = live.previewTop)
                .size(live.previewWidth, live.previewHeight)
                .background(live.previewFill)
        }
    ) {
        player?.let {
            LiveTvPlayerView(
                player = it,
                keepScreenOn = tune is LiveTuneState.Tuning || tune is LiveTuneState.Playing,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (fullScreen) {
        LiveTvFullScreen(
            viewModel = viewModel,
            ui = ui,
            tune = tune,
            info = info,
            timeFormat = timeFormat,
            onExit = {
                fullScreen = false
                focusRowOrPill(viewModel.focusedKey.value)
            }
        )
        return
    }

    LiveTvPillRow(
        selected = ui.filter,
        browse = ui.browse,
        pickers = ui.pickers,
        categoryLabels = ui.categoryLabels,
        counts = ui.counts,
        view = ui.view,
        pillRequesters = pillRequesters,
        selectedPill = selectedPill,
        // Down from the bar lands on the pills only while the list is empty (TopBarScaffold).
        contentRequester = contentRequester.takeIf { rowsEmpty },
        onSelect = viewModel::selectFilter,
        onSelectView = viewModel::selectView,
        onOpenSources = onOpenSources,
        onDown = {
            when {
                // The rows shown are another filter's: enter once the selected one's land.
                viewModel.selectedFilter != ui.filter -> {
                    pendingEnter = true
                    true
                }
                !rowsEmpty -> {
                    if (guide != null) {
                        // The grid enters on its cursor's row (G3c-Q1); synced here so the first frame has one.
                        guideCursor.sync(rows, guide, ui.nowMs, viewModel.focusedKey.value)
                        focusRowOrPill(guideCursor.key ?: rows.first().channel.key)
                    } else {
                        focusRowOrPill(rows.first().channel.key)
                    }
                    true
                }
                else -> false
            }
        },
        modifier = Modifier.padding(
            start = NuvioTheme.layout.tvSafeHorizontal,
            end = NuvioTheme.layout.tvSafeHorizontal,
            top = marquee.topBar.contentTop + live.pillsTop
        )
    )

    if (guide != null && !rowsEmpty) {
        val tuned = tune.channel
        LiveTvGuideBand(
            rows = rows,
            guide = guide,
            cursor = guideCursor,
            tune = tune,
            tunedName = tuned?.shownName,
            tunedNumber = tuned?.key?.let(viewModel::number),
            nowMs = ui.nowMs,
            timeFormat = timeFormat,
            modifier = Modifier.padding(
                start = NuvioTheme.layout.tvSafeHorizontal,
                end = NuvioTheme.layout.tvSafeHorizontal,
                top = live.guideBandTop
            )
        )
        LiveTvGuide(
            rows = rows,
            guide = guide,
            compact = ui.view == LiveTvView.COMPACT,
            nowMs = ui.nowMs,
            cursor = guideCursor,
            openKey = focusedKey,
            listState = gridListState,
            playingKey = playingKey,
            sourceNames = sourceNames,
            timeFormat = timeFormat,
            gridRequester = gridRequester,
            contentRequester = contentRequester,
            onRowFocused = { viewModel.onRowFocused(it.channel) },
            onFocusLeft = viewModel::cancelPendingPreview,
            onUpFromTop = { scope.launch { selectedPill.requestFocusAfterFrames() } },
            onWatch = { if (viewModel.tuneNow(it.channel)) fullScreen = true },
            onOptions = { row ->
                panelRow = row
                panelIndex = rows.indexOfFirst { it.channel.key == row.channel.key }
            },
            modifier = Modifier
                .padding(start = NuvioTheme.layout.tvSafeHorizontal, end = NuvioTheme.layout.tvSafeHorizontal, top = live.guideTop)
                .fillMaxSize()
        )
    }

    if (guide == null) CompositionLocalProvider(LocalBringIntoViewSpec provides rememberLiveRowBringIntoViewSpec(listState)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(start = live.listStart, top = live.listTop)
                .width(live.listWidth)
                .fillMaxHeight()
                // Down from the bar returns to the list's last-focused row (stage 2).
                .then(if (!rowsEmpty) Modifier.focusRequester(contentRequester) else Modifier)
                // A preview still waiting for focus to rest never starts once focus leaves (Q2).
                .onFocusChanged { if (!it.hasFocus) viewModel.cancelPendingPreview() }
                // CH +/- zap from the tuned channel and focus follows (L8-D6); one zap per press.
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val step = when (key.keyCode) {
                        KeyEvent.KEYCODE_CHANNEL_UP -> 1
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        viewModel.zap(step)?.let { focusRowOrPill(it) }
                    }
                    true
                }
                .focusRestorer()
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = live.fadeHeight),
            verticalArrangement = Arrangement.spacedBy(live.rowGap)
        ) {
            items(rows, key = { it.channel.key }, contentType = { "live_row" }) { row ->
                val rowKey = row.channel.key
                LiveTvRow(
                    row = row,
                    selected = rowKey == focusedKey,
                    playing = rowKey == playingKey,
                    muted = rowKey == unavailableKey,
                    timeFormat = timeFormat,
                    sourceName = sourceNames[row.channel.sourceId],
                    // OK: full screen on this channel, tuned at once (a lost channel: nothing, L8-D4).
                    onClick = { if (viewModel.tuneNow(row.channel)) fullScreen = true },
                    onLongPress = {
                        panelRow = row
                        panelIndex = rows.indexOfFirst { it.channel.key == row.channel.key }
                    },
                    modifier = Modifier
                        .focusRequester(requesterFor(rowKey))
                        // Right on a row with a guide opens its day schedule (Spec 67 D3 Q1); not on
                        // the channel that gave up, whose Right reaches Retry (stage 6).
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            if (key.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || !row.hasSchedule || rowKey == unavailableKey) {
                                return@onPreviewKeyEvent false
                            }
                            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                                viewModel.openSchedule(row.channel)?.let { id -> scheduleOpen.opened(id, rowKey) }
                            }
                            true
                        }
                        .focusProperties { if (rowKey == unavailableKey) right = retryRequester }
                        .onFocusChanged {
                            if (it.isFocused) viewModel.onRowFocused(row.channel)
                            // Moved off before its schedule arrived: the pending open is dropped (D6).
                            else if (scheduleOpen.pendingOn(rowKey, shownSchedule != null)) closeSchedule(false)
                        }
                )
            }
        }
    }

    // Q-L7b (sandbox v4 stage 17): the selected filter lists nothing; focus stays on its pill.
    // rows and ui.filter come from one state, so the name always matches the rows shown.
    if (rowsEmpty) {
        LiveTvEmptyFilter(
            filter = ui.filter,
            categoryLabels = ui.categoryLabels,
            // In a grid view under the band's picture, where the grid would start.
            modifier = Modifier
                .padding(start = live.listStart, top = if (guide != null) live.guideTop else live.listTop)
                .width(live.listWidth)
        )
    }

    shownSchedule?.let { open ->
        LiveTvScheduleColumn(
            schedule = open,
            tune = tune,
            number = viewModel.number(open.row.channel.key),
            timeFormat = timeFormat,
            onClose = { closeSchedule(true) },
            // OK on the airing programme (Q3): its channel is already tuned (openSchedule).
            onFullScreen = {
                closeSchedule(false)
                fullScreen = true
            },
            modifier = Modifier.padding(start = live.previewStart, top = live.previewTop)
        )
    }
    if (shownSchedule == null && guide == null) LiveTvPreview(
        tune = tune,
        number = viewModel::number,
        timeFormat = timeFormat,
        retryRequester = retryRequester,
        onRetry = {
            // The buttons leave with the Unavailable state: focus goes back to the channel's row.
            focusRowOrPill(unavailableKey)
            viewModel.retry()
        },
        onNextChannel = { focusRowOrPill(viewModel.zap(+1)) },
        modifier = Modifier.padding(start = live.previewStart, top = live.previewTop)
    )
    info?.takeIf { shownSchedule == null && guide == null }?.let { row ->
        LiveTvInfo(
            row = row,
            nowMs = ui.nowMs,
            unavailable = row.channel.key == unavailableKey,
            timeFormat = timeFormat,
            modifier = Modifier.padding(start = live.previewStart, top = live.infoTop),
            sourceName = sourceNames[row.channel.sourceId]
        )
    }
    LiveTvBottomFade()

    panelRow?.let { row ->
        val key = row.channel.key
        LiveTvOptionsPanel(
            viewModel = viewModel,
            row = row,
            tune = tune,
            timeFormat = timeFormat,
            onDismiss = {
                panelRow = null
                // Unfavorited in Favorites, the row left the list: the one now at its place, else the last.
                val stillListed = rows.any { it.channel.key == key }
                val neighbour = rows.getOrNull(panelIndex) ?: rows.lastOrNull()
                focusRowOrPill(if (stillListed) key else neighbour?.channel?.key)
            },
            onHidden = {
                // The row leaves the list: focus the one after it, else the one before.
                val index = rows.indexOfFirst { it.channel.key == key }
                val neighbour = rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
                panelRow = null
                focusRowOrPill(neighbour?.channel?.key.takeIf { index >= 0 })
            }
        )
    }
}

/**
 * The pill row (`.lvpills`, Spec 38 stage 1): Favorites and Recents with their counts (select on
 * focus, D16, as Library's tabs), the Country, Category and Language pickers (OK opens one and
 * makes them the shown filter; while Favorites or Recents show they keep their values, dimmed),
 * then at the row's end the View picker ([view], value only, Spec 67 D7 Q1)
 * and Sources. Entering it lands on [selectedPill] (the Country picker for a
 * Browse). [onDown] handles Down from any pill and says whether it consumed the key.
 */
@Composable
private fun LiveTvPillRow(
    selected: LiveTvFilter,
    browse: LiveTvFilter.Browse,
    pickers: Pickers?,
    categoryLabels: Map<String, String>,
    counts: Map<LiveTvFilter, Int>,
    view: LiveTvView,
    pillRequesters: MutableMap<LiveTvFilter, FocusRequester>,
    selectedPill: FocusRequester,
    contentRequester: FocusRequester?,
    onSelect: (LiveTvFilter) -> Unit,
    onSelectView: (LiveTvView) -> Unit,
    onOpenSources: () -> Unit,
    onDown: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentRequester != null) Modifier.focusRequester(contentRequester) else Modifier)
            .focusRestorer(selectedPill)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                // Focus moves on key-down only; the key-up needs no answer.
                key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && key.action == KeyEvent.ACTION_DOWN && onDown()
            },
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.liveTv.pillGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(LiveTvFilter.Favorites, LiveTvFilter.Recents).forEach { filter ->
            key(filter) {
                LiveTvPill(
                    label = liveTvFilterLabel(filter),
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    count = counts[filter],
                    modifier = Modifier
                        .focusRequester(pillRequesters.getOrPut(filter) { FocusRequester() })
                        .onFocusChanged { if (it.isFocused) onSelect(filter) }
                )
            }
        }
        val active = selected is LiveTvFilter.Browse
        val all = stringResource(R.string.live_tv_picker_all)
        val (country, category, language) = liveTvBrowseLabels(browse, categoryLabels)
        // The panel's eyebrow names what its counts are inside: "Category · Canada".
        fun eyebrow(title: String, vararg inside: String?) = (listOf(title) + inside.filterNotNull()).joinToString(FACT_SEPARATOR)

        val countryTitle = stringResource(R.string.live_tv_picker_country)
        LiveTvPicker(
            label = countryTitle,
            value = country ?: all,
            eyebrow = eyebrow(countryTitle, category, language),
            active = active,
            all = browse.country == null,
            options = {
                liveTvPickerOptions(pickers?.country, stringResource(R.string.live_tv_filter_all_countries), numbers) {
                    liveTvCountryLabel(it)
                }
            },
            selectedValue = browse.country ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(country = v.ifEmpty { null })) },
            modifier = Modifier.focusRequester(pillRequesters.getOrPut(PICKERS_PILL) { FocusRequester() })
        )
        val categoryTitle = stringResource(R.string.live_tv_picker_category)
        LiveTvPicker(
            label = categoryTitle,
            value = category ?: all,
            eyebrow = eyebrow(categoryTitle, country, language),
            active = active,
            all = browse.category == null,
            options = {
                liveTvPickerOptions(pickers?.category, stringResource(R.string.live_tv_all_categories), numbers) {
                    liveTvCategoryLabel(it, categoryLabels)
                }
            },
            selectedValue = browse.category ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(category = v.ifEmpty { null })) }
        )
        val languageTitle = stringResource(R.string.live_tv_picker_language)
        LiveTvPicker(
            label = languageTitle,
            value = language ?: all,
            eyebrow = eyebrow(languageTitle, country, category),
            active = active,
            all = browse.language == null,
            options = {
                liveTvPickerOptions(pickers?.language, stringResource(R.string.live_tv_all_languages), numbers) {
                    LiveTvModel.languageName(it)
                }
            },
            selectedValue = browse.language ?: PICKER_ALL,
            onOpen = { onSelect(browse) },
            onSelect = { v -> onSelect(browse.copy(language = v.ifEmpty { null })) }
        )
        Spacer(modifier = Modifier.weight(1f))
        val viewTitle = stringResource(R.string.live_tv_view)
        LiveTvPicker(
            label = viewTitle,
            value = liveTvViewLabel(view),
            eyebrow = viewTitle,
            // Always a value, never a filter: glass like a picker holding one (sandbox `on view`).
            active = true,
            all = false,
            options = {
                LiveTvView.entries.map { v ->
                    MarqueePickerOption(liveTvViewLabel(v), v.id, detail = liveTvViewDetail(v))
                }
            },
            selectedValue = view.id,
            onOpen = {},
            onSelect = { id -> onSelectView(LiveTvView.fromId(id)) },
            showLabel = false,
            alignEnd = true
        )
        LiveTvPill(
            label = stringResource(R.string.live_tv_sources),
            selected = false,
            onClick = onOpenSources,
            // Sandbox icon `lv_ant`.
            icon = Icons.Filled.LiveTv,
            modifier = Modifier.focusProperties { right = FocusRequester.Cancel }
        )
    }
}

// The View picker's label and detail string ids, one when per view.
private fun liveTvViewStrings(view: LiveTvView): Pair<Int, Int> = when (view) {
    LiveTvView.LIST -> R.string.live_tv_view_list to R.string.live_tv_view_list_detail
    LiveTvView.GUIDE -> R.string.live_tv_view_guide to R.string.live_tv_view_guide_detail
    LiveTvView.COMPACT -> R.string.live_tv_view_compact to R.string.live_tv_view_compact_detail
}

@Composable
private fun liveTvViewLabel(view: LiveTvView): String = stringResource(liveTvViewStrings(view).first)

@Composable
private fun liveTvViewDetail(view: LiveTvView): String = stringResource(liveTvViewStrings(view).second)

/**
 * The list keeps the focused row at most `focusRowsAbove` rows down (D16; the sandbox's window):
 * every focus move scrolls the row to that slot, clamped by the list's bounds.
 */
@Composable
internal fun rememberLiveRowBringIntoViewSpec(listState: LazyListState): BringIntoViewSpec {
    val live = NuvioTheme.marquee.liveTv
    val density = LocalDensity.current
    val defaultSpec = LocalBringIntoViewSpec.current
    return remember(density, defaultSpec, listState, live) {
        val slotPx = with(density) { ((live.rowHeight + live.rowGap) * live.focusRowsAbove).toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val distance = offset - slotPx
                if (abs(distance) < 1f) return 0f
                // Never fight the scroll bounds (the first rows sit above the slot).
                if (distance < 0f && !listState.canScrollBackward) return 0f
                return distance
            }
        }
    }
}

/** `.lvfade`: the bottom 56 dp fade to black over everything. */
@Composable
private fun LiveTvBottomFade() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.marquee.liveTv.fadeHeight)
                .background(Brush.verticalGradient(listOf(Color.Transparent, NuvioPrimitives.black)))
        )
    }
}

/**
 * No channel source yet (Spec 73 D2, sandbox `spec-73-live-pack` stage 1; was Q7 stage 13): "Add
 * channel pack" is the focused primary, then "Add broadcasters · 7 channels" (bundled: plays when
 * GitHub cannot be reached) and "Add source URL". The pack's figures ([PackOffer.summary], read
 * as the screen opens, [onShown]) fill the body and the facts line; without them the body names
 * none and the facts line is not shown.
 */
@Composable
private fun LiveTvEmpty(
    offer: PackOffer,
    onShown: () -> Unit,
    onAddPack: () -> Unit,
    onAddBroadcasters: () -> Unit,
    onAddSourceUrl: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val requester = LocalContentFocusRequester.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val summary = offer.summary
    LaunchedEffect(requester) { requester.requestFocusAfterFrames() }
    LaunchedEffect(Unit) { onShown() }
    Column(
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = live.emptyTop)
            .width(live.emptyWidth),
        verticalArrangement = Arrangement.spacedBy(live.emptyGap)
    ) {
        MarqueeEyebrow(text = stringResource(R.string.nav_live_tv), accent = { live.accent })
        Text(
            text = stringResource(R.string.live_tv_empty_title),
            style = NuvioTheme.textStyles.signInTitle,
            color = NuvioPrimitives.white
        )
        Text(
            text = if (summary != null) {
                stringResource(R.string.live_tv_empty_body_pack, numbers.format(summary.channels), numbers.format(summary.countries))
            } else {
                stringResource(R.string.live_tv_empty_body_pack_unknown) // Spec 73 D11 (Q1): no figures
            },
            style = NuvioTheme.textStyles.signInIntro,
            color = marquee.onImage2
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Spec 73 D11 (Q3): Adding… while it downloads.
            MarqueePrimaryButton(
                label = stringResource(if (offer.adding) R.string.live_tv_adding else R.string.live_tv_add_pack),
                icon = null,
                onClick = onAddPack,
                focusRequester = requester
            )
            MarqueeGlassTextButton(label = stringResource(R.string.live_tv_add_broadcasters), onClick = onAddBroadcasters)
            MarqueeGlassTextButton(label = stringResource(R.string.live_tv_add_source_url), onClick = onAddSourceUrl)
        }
        if (summary != null) {
            Text(
                text = stringResource(
                    R.string.live_tv_pack_facts,
                    numbers.format(summary.channels),
                    packSize(summary.bytes)
                ),
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage2
            )
        }
        // Spec 73 D11 (Q3): a failed add shows its reason under the buttons.
        offer.error?.let { reason ->
            Text(text = reason, style = NuvioTheme.textStyles.caption, color = NuvioPrimitives.errorOnImage)
        }
        Text(
            text = stringResource(R.string.live_tv_empty_note),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Stage 16: the static skeleton in the final geometry (white .06, no shimmer), sandbox `s_loading`. */
@Composable
private fun LiveTvSkeleton() {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val fill = marquee.skeletonFill
    val pillsTop = marquee.topBar.contentTop + live.pillsTop
    val left = NuvioTheme.layout.tvSafeHorizontal
    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_PILLS) { k ->
            marqueeSkeletonBar(fill, left + 92.dp * k, pillsTop, 80.dp, live.pillHeight, live.pillHeight / 2)
        }
        val pitch = live.rowHeight + live.rowGap
        val textX = 126.dp
        repeat(SKELETON_ROWS) { k ->
            val y = live.listTop + pitch * k
            marqueeSkeletonBar(fill, 72.dp, y + 11.dp, live.logoSize, live.logoSize, live.logoRadius)
            marqueeSkeletonBar(fill, textX, y + 14.dp, 180.dp - 24.dp * (k % 3), 14.dp, 7.dp)
            marqueeSkeletonBar(fill, textX, y + 36.dp, 240.dp - 50.dp * (k % 2), 11.dp, 6.dp)
        }
        val x = live.previewStart
        marqueeSkeletonBar(fill, x, live.previewTop, live.previewWidth, live.previewHeight, live.previewRadius)
        marqueeSkeletonBar(fill, x, 384.dp, 120.dp, 11.dp, 6.dp)
        marqueeSkeletonBar(fill, x, 406.dp, 260.dp, 24.dp, 8.dp)
        marqueeSkeletonBar(fill, x, 442.dp, 300.dp, 11.dp, 6.dp)
    }
}

/** The pill-requester key of the Country picker: a Browse filter's pill, whatever its values. */
private val PICKERS_PILL: LiveTvFilter = LiveTvFilter.ALL

private const val SKELETON_PILLS = 7
private const val SKELETON_ROWS = 6

/** An empty filter's line at the list top (Q-L7b), with how to fill Favorites or Recents. */
@Composable
private fun LiveTvEmptyFilter(filter: LiveTvFilter, categoryLabels: Map<String, String>, modifier: Modifier = Modifier) {
    val hint = when (filter) {
        LiveTvFilter.Favorites -> stringResource(R.string.live_tv_empty_favorites_hint)
        LiveTvFilter.Recents -> stringResource(R.string.live_tv_empty_recents_hint)
        else -> null
    }
    Column(
        // Sandbox stage 17: 14 dp above, 10 dp in, 4 dp between.
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.live_tv_empty_filter, liveTvFilterLabel(filter, categoryLabels)),
            style = NuvioTheme.textStyles.rowTitle,
            color = NuvioPrimitives.white
        )
        if (hint != null) {
            Text(text = hint, style = NuvioTheme.textStyles.meta, color = NuvioTheme.marquee.onImage3)
        }
    }
}
