@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nuvio.tv.ui.components.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.FacetGroup
import com.nuvio.tv.core.stream.FacetOption
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamFacetMemo
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.core.stream.StreamPin
import com.nuvio.tv.core.stream.facetOptions
import com.nuvio.tv.core.stream.facetValueLabel
import com.nuvio.tv.core.stream.limitVerdict
import com.nuvio.tv.core.stream.resolutionLabel
import com.nuvio.tv.core.stream.resolutionVerdict
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row of the sources sheet: the view it shows ([selection]: All, or an addon), the streams
 * that view lists ([count]; null while its addon has none to count), the addon's [status] (All is
 * always SUCCESS: the circle carries the list's spinner) and whether it is the view showing.
 * Only a [selectable] row changes the view: an addon that is still asked or gave no stream has
 * nothing to show.
 */
internal data class SourcesSheetRow(
    val selection: StreamListSelection,
    val count: Int?,
    val status: SourceChipStatus,
    val selected: Boolean,
    val selectable: Boolean
)

/**
 * The sources sheet's rows (stage 6): All, then one per addon of [orderedNames]
 * ([com.nuvio.tv.core.stream.sourceChipAddonNames]), counted under [view]'s filter. An addon is
 * selectable once it is among [availableAddons] (it has streams) and its chip reads SUCCESS.
 */
internal fun sourcesSheetRows(
    orderedNames: List<String>,
    availableAddons: List<String>,
    sourceChips: List<SourceChipItem>,
    view: StreamListView
): List<SourcesSheetRow> {
    val statusByName = sourceChips.associate { it.name to it.status }
    val all = SourcesSheetRow(
        selection = StreamListSelection.All,
        count = view.allCount,
        status = SourceChipStatus.SUCCESS,
        selected = view.selection == StreamListSelection.All,
        selectable = true
    )
    return listOf(all) + orderedNames.map { name ->
        val status = statusByName[name] ?: SourceChipStatus.SUCCESS
        val selection = StreamListSelection.Addon(name)
        SourcesSheetRow(
            selection = selection,
            count = view.addonCounts[name].takeIf { status == SourceChipStatus.SUCCESS },
            status = status,
            selected = view.selection == selection,
            selectable = name in availableAddons && status == SourceChipStatus.SUCCESS
        )
    }
}

/** A limit the hidden sheet lists. */
internal enum class HiddenReason { BITRATE, RESOLUTION }

/** One entry of the hidden sheet: a limit and the streams it hides. */
internal data class HiddenSheetEntry(val reason: HiddenReason, val count: Int)

/**
 * The hidden sheet's entries (stage 4): one per limit hiding something, the bitrate limit first;
 * a stream over both counts in each. Counted per hidden key ([com.nuvio.tv.core.stream.limitKey]):
 * two rows of one addon with one URL count once here and twice in the circle's badge.
 */
internal fun hiddenSheetEntries(view: StreamListView): List<HiddenSheetEntry> {
    val verdicts = view.hiddenVerdicts.values
    return listOf(
        HiddenSheetEntry(HiddenReason.BITRATE, verdicts.count { it.bitrate != null }),
        HiddenSheetEntry(HiddenReason.RESOLUTION, verdicts.count { it.resolution != null })
    ).filter { it.count > 0 }
}

/** How the eyebrow names [filter] ("4K · Atmos"), its values in the sheet's order; null with none on. */
internal fun facetFilterLabel(filter: FacetFilter, unknownLabel: String): String? =
    filter.values.takeIf { it.isNotEmpty() }?.joinToString(" · ") { facetValueLabel(it) ?: unknownLabel }

/**
 * The open [sheet] over the Sources panel (Spec 65 D12, sandbox `spec-65-stream-filters` v5
 * stages 4, 6, 7). Sources: All and the addons ([rows]); OK shows that view and closes, hold OK
 * pins or unpins an addon ([onTogglePin]). Filters: the [saved] filters, then [view]'s groups with
 * live counts; OK switches a value or a saved filter and the list narrows behind the sheet
 * ([onFilterChange]), hold OK pins or unpins a saved filter; Save as filter ([onSaveFilter]) while
 * the values on are not saved yet; Clear all; with [onEditOnPhone] (the Stream screen only), a
 * phone button at the foot's end for the saved filters' phone page. A pin mark is drawn in the
 * ambient [accent].
 * Hidden: why, then one entry per limit; OK lists the hidden streams
 * (again: back to all) and closes. Each sheet takes focus when it opens and keeps it until
 * [onClose]; Back is the caller's.
 */
@Composable
internal fun SourcesSheet(
    sheet: SourcesPanelSheet,
    view: StreamListView,
    rows: List<SourcesSheetRow>,
    saved: SavedStreamFilters,
    accent: () -> Color,
    onListSelected: (StreamListSelection) -> Unit,
    onFilterChange: (FacetFilter) -> Unit,
    onSaveFilter: (FacetFilter, String) -> Unit,
    onTogglePin: (StreamPin) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onEditOnPhone: (() -> Unit)? = null
) {
    val initialFocus = remember(sheet) { FocusRequester() }
    LaunchedEffect(initialFocus) { initialFocus.requestFocusAfterFrames() }
    when (sheet) {
        SourcesPanelSheet.SOURCES -> SourcesListSheet(
            rows = rows,
            saved = saved,
            accent = accent,
            initialFocus = initialFocus,
            onSelected = {
                onListSelected(it)
                onClose()
            },
            onTogglePin = onTogglePin,
            modifier = modifier
        )
        SourcesPanelSheet.FILTERS -> FiltersSheet(
            view = view,
            saved = saved,
            accent = accent,
            initialFocus = initialFocus,
            onFilterChange = onFilterChange,
            onSaveFilter = onSaveFilter,
            onTogglePin = onTogglePin,
            onEditOnPhone = onEditOnPhone,
            modifier = modifier
        )
        SourcesPanelSheet.HIDDEN -> HiddenSheet(
            view = view,
            initialFocus = initialFocus,
            onToggleHiddenView = {
                val showing = view.selection == StreamListSelection.Hidden
                onListSelected(if (showing) StreamListSelection.All else StreamListSelection.Hidden)
                onClose()
            },
            modifier = modifier
        )
    }
}

/**
 * A sheet's shell: [eyebrow], a rule, then [content]; focus cannot leave it. Focus lost while the
 * sheet stands means the focused control left it (a value no row has any more, an addon's row
 * dropped, a limit that stopped hiding, Save as filter once saved): [fallbackFocus] takes it, a
 * control the sheet has by then (read when the focus is lost, not when the shell was composed).
 */
@Composable
private fun SheetShell(
    eyebrow: String,
    fallbackFocus: FocusRequester,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val scope = rememberCoroutineScope()
    val fallback by rememberUpdatedState(fallbackFocus)
    var hadFocus by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(tokens.sheetRadius))
            .background(tokens.sheetFill)
            .padding(
                start = tokens.sheetPaddingHorizontal,
                top = tokens.sheetPaddingTop,
                end = tokens.sheetPaddingHorizontal,
                bottom = tokens.sheetPaddingBottom
            )
            .onFocusChanged {
                if (hadFocus && !it.hasFocus) {
                    scope.launch { fallback.requestFocusAfterFrames(frames = 1) }
                }
                hadFocus = it.hasFocus
            }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(tokens.sheetGap)
    ) {
        PanelEyebrowText(text = eyebrow)
        StreamRule()
        content()
    }
}

@Composable
private fun SourcesListSheet(
    rows: List<SourcesSheetRow>,
    saved: SavedStreamFilters,
    accent: () -> Color,
    initialFocus: FocusRequester,
    onSelected: (StreamListSelection) -> Unit,
    onTogglePin: (StreamPin) -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val addonRows = rows.filter { it.selection is StreamListSelection.Addon }
    // Focus opens on the view showing; on All while the hidden view shows (no row is that view).
    val focusRow = rows.firstOrNull { it.selected } ?: rows.firstOrNull()
    // All is always listed: it takes the focus of an addon row that leaves under it.
    val allRowFocus = remember { FocusRequester() }
    SheetShell(
        fallbackFocus = allRowFocus,
        eyebrow = stringResource(
            R.string.stream_sheet_sources_eyebrow,
            addonRows.count { it.status == SourceChipStatus.SUCCESS },
            addonRows.size
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.sheetGap)
        ) {
            rows.forEach { row ->
                key(row.selection) {
                    val failed = row.status == SourceChipStatus.ERROR
                    // An addon's view can be pinned whatever it answered; All is always in reach.
                    val pin = (row.selection as? StreamListSelection.Addon)?.let { StreamPin.Addon(it.name) }
                    SheetRow(
                        label = when (val selection = row.selection) {
                            is StreamListSelection.Addon -> selection.name
                            else -> stringResource(R.string.stream_filter_all)
                        },
                        onClick = { if (row.selectable) onSelected(row.selection) },
                        modifier = Modifier
                            .then(if (row == focusRow) Modifier.focusRequester(initialFocus) else Modifier)
                            .then(
                                if (row.selection == StreamListSelection.All) Modifier.focusRequester(allRowFocus) else Modifier
                            ),
                        selected = row.selected,
                        restingColor = if (failed) tokens.limitWarn else null,
                        onLongClick = pin?.let { { onTogglePin(it) } }
                    ) { focused ->
                        val detailColor = when {
                            focused -> NuvioPrimitives.black.copy(alpha = SUFFIX_ALPHA)
                            failed -> tokens.limitChipCount
                            else -> marquee.onImage3
                        }
                        when (row.status) {
                            SourceChipStatus.LOADING -> MarqueeSpinner(
                                trackColor = detailColor.copy(alpha = ROW_SPINNER_TRACK_ALPHA),
                                arcColor = detailColor
                            )
                            SourceChipStatus.ERROR -> {
                                SheetRowDetail(stringResource(R.string.stream_sheet_addon_no_streams), detailColor)
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(tokens.sheetRowIconSize)
                                )
                            }
                            SourceChipStatus.SUCCESS -> {
                                row.count?.let { SheetRowDetail(it.toString(), detailColor) }
                                if (row.selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(tokens.sheetRowIconSize)
                                    )
                                }
                            }
                        }
                        if (pin != null && saved.isPinned(pin)) {
                            PinMark(
                                accent = accent,
                                size = tokens.sheetRowIconSize,
                                focusedTint = NuvioPrimitives.black.copy(alpha = SUFFIX_ALPHA).takeIf { focused }
                            )
                        }
                    }
                }
            }
        }
        if (addonRows.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(tokens.facetChipGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PinMark(accent = accent, size = tokens.pinMarkSize, described = false)
                Text(
                    text = stringResource(R.string.stream_sheet_pin_hint),
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage3,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The pin mark of a pinned row or saved filter, in the ambient [accent] ([focusedTint] on a focused
 * control). Its own composable, so an animating accent recomposes only the mark. [described]: false
 * where it only decorates a line of text.
 */
@Composable
private fun PinMark(accent: () -> Color, size: Dp, focusedTint: Color? = null, described: Boolean = true) {
    Icon(
        imageVector = Icons.Rounded.PushPin,
        contentDescription = if (described) stringResource(R.string.cd_stream_pinned) else null,
        tint = focusedTint ?: accent(),
        modifier = Modifier.size(size)
    )
}

@Composable
private fun FiltersSheet(
    view: StreamListView,
    saved: SavedStreamFilters,
    accent: () -> Color,
    initialFocus: FocusRequester,
    onFilterChange: (FacetFilter) -> Unit,
    onSaveFilter: (FacetFilter, String) -> Unit,
    onTogglePin: (StreamPin) -> Unit,
    onEditOnPhone: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val unknown = stringResource(R.string.stream_facet_unknown)
    // Worked out off the main thread (a first read parses every row); null until the first answer.
    // A switch keeps the values drawn until the new counts arrive.
    val facets by produceState<Map<FacetGroup, List<FacetOption>>?>(null, view.facetBase, view.filter) {
        value = withContext(Dispatchers.Default) {
            facetOptions(view.facetBase, view.filter, StreamFacetMemo::valuesOf)
        }
    }
    // Over hidden streams no value is offered: none would change the list (the note says so).
    val groups = facets.takeUnless { view.listsHidden }
    val firstValue = groups?.values?.firstOrNull()?.firstOrNull()?.value
    // The sheet opens on Clear all (always there); the first value takes focus once the values arrive.
    val firstValueFocus = remember { FocusRequester() }
    LaunchedEffect(firstValue != null) {
        if (firstValue != null) firstValueFocus.requestFocusAfterFrames(frames = 1)
    }
    // Over hidden streams saved filters are not offered either, nor saved.
    val savedFilters = saved.filters.takeUnless { view.listsHidden }.orEmpty()
    val savedOn = saved.matching(view.filter)
    val canSave = !view.listsHidden && saved.canSave(view.filter)
    // Save as filter leaves once its values are saved; the new saved filter takes its focus (stage 7).
    var justSaved by remember { mutableStateOf<FacetFilter?>(null) }
    val savedOnFocus = remember { FocusRequester() }
    SheetShell(
        fallbackFocus = if (savedOn != null && savedFilters.isNotEmpty() && justSaved == view.filter) {
            savedOnFocus
        } else {
            initialFocus
        },
        eyebrow = stringResource(
            R.string.stream_sheet_filters_eyebrow,
            view.streams.size,
            view.streams.size + view.filteredOut
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.sheetGap)
        ) {
            val note = when {
                view.listsHidden -> R.string.stream_sheet_filters_hidden
                groups?.isEmpty() == true && view.facetBase.isNotEmpty() -> R.string.stream_sheet_filters_none
                else -> null
            }
            if (note != null) {
                Text(
                    text = stringResource(note),
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage3
                )
            }
            if (savedFilters.isNotEmpty()) {
                FacetGroupBlock(title = stringResource(R.string.stream_sheet_filters_saved)) {
                    savedFilters.forEach { savedFilter ->
                        key(savedFilter.id) {
                            val on = savedFilter.id == savedOn?.id
                            val pin = StreamPin.Filter(savedFilter.id)
                            SavedFilterChip(
                                name = savedFilter.name,
                                on = on,
                                pinned = saved.isPinned(pin),
                                accent = accent,
                                // OK on the one that is on switches every value off again.
                                onClick = { onFilterChange(if (on) FacetFilter.NONE else savedFilter.filter) },
                                onLongClick = { onTogglePin(pin) },
                                modifier = if (on) {
                                    Modifier
                                        .focusRequester(savedOnFocus)
                                        // The handoff is done: later rescues go to Clear all again.
                                        .onFocusChanged { if (it.isFocused) justSaved = null }
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            }
            groups?.forEach { (group, options) ->
                key(group) {
                    FacetGroupBlock(title = stringResource(facetGroupTitle(group))) {
                        options.forEach { option ->
                            key(option.value.key) {
                                FacetChip(
                                    option = option,
                                    label = facetValueLabel(option.value) ?: unknown,
                                    onClick = { onFilterChange(view.filter.toggled(option.value)) },
                                    modifier = if (option.value == firstValue) {
                                        Modifier.focusRequester(firstValueFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.sheetGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canSave) {
                // Named from its values ("4K · Atmos"); the phone page renames it (D14).
                val name = facetFilterLabel(view.filter, unknown).orEmpty()
                MarqueeGlassTextButton(
                    label = stringResource(R.string.stream_sheet_filters_save),
                    onClick = {
                        justSaved = view.filter
                        onSaveFilter(view.filter, name)
                    },
                    height = tokens.sheetButtonHeight,
                    horizontalPadding = tokens.sheetButtonPadding,
                    textStyle = NuvioTheme.textStyles.panelButtonSmall,
                    icon = Icons.Rounded.Add
                )
            }
            MarqueeGlassTextButton(
                label = stringResource(R.string.stream_sheet_filters_clear),
                onClick = { onFilterChange(FacetFilter.NONE) },
                modifier = Modifier.focusRequester(initialFocus),
                height = tokens.sheetButtonHeight,
                horizontalPadding = tokens.sheetButtonPadding,
                textStyle = NuvioTheme.textStyles.panelButtonSmall
            )
            if (onEditOnPhone != null) {
                Spacer(modifier = Modifier.weight(1f))
                MarqueeGlassButton(
                    icon = Icons.Rounded.Smartphone,
                    contentDescription = stringResource(R.string.stream_sheet_filters_phone),
                    onClick = onEditOnPhone,
                    size = tokens.sheetButtonHeight
                )
            }
        }
    }
}

/** A group of the filters sheet: its [title] over its wrapping values. */
@Composable
private fun FacetGroupBlock(title: String, content: @Composable FlowRowScope.() -> Unit) {
    val tokens = NuvioTheme.marquee.stream
    Column(verticalArrangement = Arrangement.spacedBy(tokens.facetGroupGap)) {
        Text(
            text = title.uppercase(),
            style = NuvioTheme.textStyles.eyebrow,
            color = NuvioTheme.marquee.onImage3
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(tokens.facetChipGap),
            verticalArrangement = Arrangement.spacedBy(tokens.facetChipGap),
            content = content
        )
    }
}

private fun facetGroupTitle(group: FacetGroup): Int = when (group) {
    FacetGroup.RESOLUTION -> R.string.stream_facet_group_resolution
    FacetGroup.HDR -> R.string.stream_facet_group_hdr
    FacetGroup.AUDIO -> R.string.stream_facet_group_audio
    FacetGroup.SOURCE -> R.string.stream_facet_group_source
    FacetGroup.CODEC -> R.string.stream_facet_group_codec
}

/**
 * A pill of the filters sheet: [fill] and [contentColor] at rest; focused = white fill, black
 * content, no scale. [dimmed] draws it at `facetZeroAlpha` until focused. [content] is given
 * whether the pill is focused.
 */
@Composable
private fun SheetChip(
    onClick: () -> Unit,
    fill: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(tokens.facetChipHeight)
            .alpha(if (dimmed && !focused) tokens.facetZeroAlpha else 1f)
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = fill,
            contentColor = contentColor,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        contentPadding = PaddingValues(horizontal = tokens.facetChipPadding)
    ) {
        content(focused)
    }
}

/**
 * One filter value: [label] and the rows it would list. On = filled behind a check; a value that
 * would list nothing is dimmed (it can still be switched).
 */
@Composable
private fun FacetChip(
    option: FacetOption,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val white = NuvioPrimitives.white
    val description = stringResource(
        if (option.on) R.string.cd_stream_facet_on else R.string.cd_stream_facet_off,
        label,
        option.count
    )
    SheetChip(
        onClick = onClick,
        fill = if (option.on) tokens.circleOnFill else marquee.glassFill,
        contentColor = if (option.on) white else marquee.onImage2,
        modifier = modifier.semantics { contentDescription = description },
        dimmed = option.count == 0 && !option.on
    ) { focused ->
        if (option.on) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(tokens.facetCheckSize)
            )
            Spacer(modifier = Modifier.width(tokens.chipInnerGap))
        }
        Text(text = label, style = NuvioTheme.textStyles.panelPill, maxLines = 1)
        Spacer(modifier = Modifier.width(tokens.chipInnerGap))
        Text(
            text = option.count.toString(),
            style = NuvioTheme.textStyles.caption,
            color = when {
                focused -> NuvioPrimitives.black.copy(alpha = SUFFIX_ALPHA)
                option.on -> white.copy(alpha = ON_COUNT_ALPHA)
                else -> marquee.onImage3
            },
            maxLines = 1
        )
    }
}

/**
 * One saved filter (stage 7): its [name]; [on] (its values are the ones on) = the ambient [accent]
 * behind it; [pinned] = a pin mark after the name. OK is [onClick], hold OK [onLongClick].
 */
@Composable
private fun SavedFilterChip(
    name: String,
    on: Boolean,
    pinned: Boolean,
    accent: () -> Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val longPress = rememberMarqueeLongPress()
    SheetChip(
        onClick = { longPress.click(onClick) },
        // On: the accent is painted under a clear pill, read when drawn (an animating accent
        // repaints only this); the focused white fill covers it.
        fill = if (on) Color.Transparent else marquee.glassFill,
        contentColor = if (on) NuvioPrimitives.white else marquee.onImage2,
        modifier = modifier
            .then(longPress.modifier(onLongClick))
            .drawBehind {
                if (on) {
                    drawRoundRect(
                        color = accent().copy(alpha = tokens.savedOnAlpha),
                        cornerRadius = CornerRadius(size.height / 2)
                    )
                }
            }
    ) { focused ->
        Text(text = name, style = NuvioTheme.textStyles.panelPill, maxLines = 1)
        if (pinned) {
            Spacer(modifier = Modifier.width(tokens.chipInnerGap))
            PinMark(
                accent = accent,
                size = tokens.pinMarkSize,
                focusedTint = NuvioPrimitives.black.takeIf { focused }
            )
        }
    }
}

@Composable
private fun HiddenSheet(
    view: StreamListView,
    initialFocus: FocusRequester,
    onToggleHiddenView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val entries = hiddenSheetEntries(view)
    val bitrate = view.limitVerdict
    val resolution = view.resolutionVerdict
    SheetShell(
        fallbackFocus = initialFocus,
        eyebrow = pluralStringResource(R.plurals.stream_sheet_hidden_eyebrow, view.hiddenCount, view.hiddenCount),
        modifier = modifier
    ) {
        if (bitrate != null || resolution != null) HiddenStreamsLine(bitrate = bitrate, resolution = resolution)
        entries.forEachIndexed { index, entry ->
            key(entry.reason) {
                // The network test's limit names its figure beside the count; the others in the label.
                val fromNetworkTest = entry.reason == HiddenReason.BITRATE && bitrate?.source != null
                val label = when (entry.reason) {
                    HiddenReason.BITRATE -> if (fromNetworkTest) {
                        stringResource(R.string.stream_sheet_hidden_network)
                    } else {
                        stringResource(R.string.stream_sheet_hidden_manual, bitrate?.figureMbps ?: 0)
                    }
                    HiddenReason.RESOLUTION -> stringResource(
                        if (resolution?.fromTv == true) {
                            R.string.stream_sheet_hidden_resolution_tv
                        } else {
                            R.string.stream_sheet_hidden_resolution
                        },
                        resolution?.let { resolutionLabel(it.cap) }.orEmpty()
                    )
                }
                val detail = if (fromNetworkTest) {
                    stringResource(R.string.stream_sheet_hidden_network_detail, bitrate?.figureMbps ?: 0, entry.count)
                } else {
                    entry.count.toString()
                }
                SheetRow(
                    label = label,
                    onClick = onToggleHiddenView,
                    modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                    selected = view.selection == StreamListSelection.Hidden,
                    restingColor = tokens.limitChipText,
                    selectedRing = tokens.limitWarn,
                    leadingIcon = Icons.Filled.Warning,
                    leadingTint = tokens.limitWarn
                ) { focused ->
                    SheetRowDetail(
                        text = detail,
                        color = if (focused) NuvioPrimitives.black.copy(alpha = SUFFIX_ALPHA) else tokens.limitChipCount
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.stream_sheet_hidden_note),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3
        )
    }
}

/**
 * A sheet's list row: [label] in a `sheetRowHeight` card, [trailing] at its end (given whether the
 * row is focused). [selected] draws the [selectedRing] ring; [restingColor] replaces the plain
 * text colour (amber rows); [leadingIcon] in [leadingTint] at rest. Focused = white fill, black
 * content, no scale. [onLongClick]: hold OK, and the release that follows does not click.
 */
@Composable
private fun SheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    restingColor: Color? = null,
    selectedRing: Color = NuvioPrimitives.white,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = Color.Unspecified,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.(focused: Boolean) -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val shape = RoundedCornerShape(tokens.sheetRowRadius)
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberMarqueeLongPress()
    Surface(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.sheetRowHeight)
            .onFocusChanged { focused = it.isFocused }
            .then(longPress.modifier(onLongClick)),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = tokens.rowFill,
            contentColor = restingColor ?: if (selected) white else marquee.onImage2,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(tokens.chipSelectedRing, selectedRing), shape = shape) else Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = tokens.sheetRowPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.sheetRowInnerGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) black else leadingTint,
                    modifier = Modifier.size(tokens.sheetRowIconSize)
                )
            }
            Text(
                text = label,
                style = NuvioTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            trailing(focused)
        }
    }
}

/** A loading row's spinner track against its arc, as an addon chip's. */
private const val ROW_SPINNER_TRACK_ALPHA = 0.25f

/** A value's count on a chip that is on (the mockup's `.ac.on .n`). */
private const val ON_COUNT_ALPHA = 0.7f

/** A sheet row's trailing count or status. */
@Composable
private fun SheetRowDetail(text: String, color: Color) {
    Text(text = text, style = NuvioTheme.textStyles.caption, color = color, maxLines = 1)
}

