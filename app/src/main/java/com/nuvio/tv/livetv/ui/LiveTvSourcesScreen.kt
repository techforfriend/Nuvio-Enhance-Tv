package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.data.GuideService
import com.nuvio.tv.livetv.source.ChannelPack
import com.nuvio.tv.livetv.source.GuidePreset
import com.nuvio.tv.livetv.source.PackScanRecord
import com.nuvio.tv.livetv.source.PackScanner
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeCentredPanel
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueeNameField
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.addon.RenamePanel
import com.nuvio.tv.ui.screens.addon.StripListRow
import com.nuvio.tv.ui.screens.addon.addonFocusKeyAfterRemoval
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Focus targets of the Add source URL and Hidden channels buttons (not source ids). */
private const val ADD_TARGET = "#add"
private const val HIDDEN_TARGET = "#hidden"
/** Focus target of the folded preset row's Choose guides button (Spec 55 D11). */
private const val PRESETS_TARGET = "#presets"
private const val PACK_TARGET = "#pack"

/** Focus target of preset [key]'s Add button. */
private fun presetTarget(key: String) = "#preset:$key"

/** Focus target of playlist [sourceId]'s header guides suggestion button. */
private fun headerTarget(sourceId: String) = "#header:$sourceId"

/** Longest source name the rename panel takes. */
private const val SOURCE_NAME_MAX = 40

/** Longest URL the add field takes (a query-configured addon URL can be long). */
private const val URL_MAX = 2048

/**
 * Live TV › Sources (approved sandbox `u34-live-tv-marquee` v3 stages `lv-sources`, `lv-add`; Spec
 * 34 D16, L7e): breadcrumb; title + "n channel sources · n guides · on this TV"; Add source URL,
 * Refresh all, Hidden channels; the suggested guide presets when they apply (Spec 55 S2, sandbox
 * `spec-55-guide-suggestions` v3: one row each, Pluto's and India's among them; more than 3 fold
 * into one Choose guides row, D11), then each playlist's own header guides (Spec 55 S1, v1: Add
 * guides for 3 or fewer, else Choose guides); one
 * row per source (type, name, what it gave, last refresh, health), then the iptv-org channel data
 * row. OK on a source row opens Refresh / Rename / Remove
 * under it (the Addons route's strip), plus Choose guides on a playlist with header guides not yet
 * added. [openAdd]: opened from the empty state's "Add source URL",
 * the Add source dialog shows at once. A suggestion's Add button sits at the row's end, which
 * spatial search from the left-aligned buttons above and the full-width rows below skipped
 * (Shield 2026-11-02, 12/N-2): Down from the buttons and Up from the first source row go to it
 * explicitly.
 */
@Composable
fun LiveTvSourcesScreen(
    openAdd: Boolean,
    viewModel: LiveTvSourcesViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {},
    openScan: Boolean = false,
    onOpenLiveTv: (country: String?) -> Unit = {}
) {
    val ui by viewModel.uiState.collectAsState()
    val packOffer by viewModel.packOffer.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val scanFacts by viewModel.scanFacts.collectAsState()
    // Spec 73 S3b: which scan panel shows ("" none, "pick", "world", "progress"); the countries the
    // list opens with; the ended scan's result panel.
    var scanPanel by rememberSaveable { mutableStateOf(if (openScan) SCAN_PICK else "") }
    var pickInitial by remember { mutableStateOf<Set<String?>?>(null) }
    var scanResult by remember { mutableStateOf<PackScanRecord?>(null) }
    val openPicker = { from: Set<String?>? ->
        pickInitial = from
        scanPanel = SCAN_PICK
    }
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val toast = LocalMarqueeToast.current
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val focusTargets = rememberCollectionFocusTargets()
    // Opened from the empty state: straight to the dialog (a new ViewModel starts with it empty).
    var addOpen by rememberSaveable { mutableStateOf(openAdd) }
    var hiddenOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SourceRow?>(null) }
    var removing by remember { mutableStateOf<SourceRow?>(null) }
    // The playlist whose header guides Choose guides lists, and the focus target it opened from.
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    // Choose guides of the folded preset row is open.
    var choosingPresets by rememberSaveable { mutableStateOf(false) }
    var choosingFrom by rememberSaveable { mutableStateOf("") }
    val openChooser = { sourceId: String, from: String ->
        choosingFrom = from
        choosing = sourceId
    }

    BackHandler { onBackPress() }

    // D14: the scan state is the one owner of its end. Whenever it is Idle, the progress panel
    // closes and focus returns to the pack row (also a scan ended with no ended event, D13).
    val scanIdle = scan is PackScanner.State.Idle
    LaunchedEffect(scanIdle) {
        if (scanIdle && scanPanel == SCAN_PROGRESS) {
            scanPanel = ""
            focusTargets.pending = ChannelPack.SOURCE_ID
        }
    }

    val openAddDialog = {
        viewModel.openAdd()
        addOpen = true
    }
    // First focus once the list has loaded: Add source URL (stage 14); the dialog's dismiss does it
    // when the route opened on the dialog.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(ui.loading) {
        if (!entered && !ui.loading) {
            entered = true
            if (!addOpen) focusTargets.pending = ADD_TARGET
        }
    }
    // No fallback: a just-added source's row may compose a frame after the Added event.
    focusTargets.ServePendingFocus(content = ui.rows)
    // The suggestion rows shown, in composition order; an override names only a composed row.
    val shownHeaders = ui.headers.filter { it.shown }
    val presetsFolded = ui.presets.size > LiveTvSourcesModel.FOLD_PRESETS
    val presetTargets = if (presetsFolded) listOf(PRESETS_TARGET) else ui.presets.map { presetTarget(it.preset.key) }
    val suggestions = listOfNotNull(PACK_TARGET.takeIf { packOffer != null }) + presetTargets +
        shownHeaders.map { headerTarget(it.sourceId) }
    val firstSuggestion = suggestions.firstOrNull()?.let(focusTargets::requester) ?: FocusRequester.Default
    val lastSuggestion = suggestions.lastOrNull()?.let(focusTargets::requester) ?: FocusRequester.Default
    val downToSuggestion = Modifier.focusProperties { down = firstSuggestion }

    val renameFailed = stringResource(R.string.live_tv_rename_failed)
    val removeFailed = stringResource(R.string.live_tv_remove_failed)
    val unhideFailed = stringResource(R.string.live_tv_unhide_failed)
    val headerFailed = stringResource(R.string.live_tv_header_add_failed)
    val packFailedFormat = stringResource(R.string.live_tv_pack_add_failed)
    val addedFormat = stringResource(R.string.live_tv_added)
    LaunchedEffect(viewModel) {
        viewModel.event.collect { event ->
            when (event) {
                is SourcesEvent.Added -> {
                    addOpen = false
                    focusTargets.pending = event.id
                    toast?.show(String.format(addedFormat, event.name))
                    // Spec 73 D3: the pack added here opens the country list.
                    if (event.id == ChannelPack.SOURCE_ID) openPicker(null)
                }
                // D14: the ended event only opens the result panel; the progress panel follows the state.
                is SourcesEvent.ScanEnded -> scanResult = event.record
                SourcesEvent.RenameFailed -> toast?.show(renameFailed)
                SourcesEvent.RemoveFailed -> toast?.show(removeFailed)
                SourcesEvent.UnhideFailed -> toast?.show(unhideFailed)
                SourcesEvent.PresetAddFailed, SourcesEvent.HeaderAddFailed -> toast?.show(headerFailed)
                // Spec 73 D11 (Q3): a failed pack add toasts its reason.
                is SourcesEvent.PackAddFailed -> toast?.show(String.format(packFailedFormat, event.reason))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop)
                .verticalScroll(rememberScrollState())
                .padding(start = safeHorizontal, end = safeHorizontal, bottom = NuvioTheme.layout.tvSafeVertical),
            verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.live_tv_sources),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                if (!ui.loading) {
                    Text(
                        text = listOf(
                            pluralStringResource(R.plurals.live_tv_channel_sources, ui.channelSources, ui.channelSources),
                            pluralStringResource(R.plurals.live_tv_guides, ui.guides, ui.guides),
                            stringResource(R.string.live_tv_on_this_tv)
                        ).joinToString(FACT_SEPARATOR),
                        style = NuvioTheme.textStyles.meta,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(bottom = tokens.rowGap),
                horizontalArrangement = Arrangement.spacedBy(live.sourcesButtonGap)
            ) {
                SourcesButton(
                    label = stringResource(R.string.live_tv_add_source_url),
                    icon = Icons.Filled.Add,
                    onClick = openAddDialog,
                    modifier = downToSuggestion.focusRequester(focusTargets.requester(ADD_TARGET))
                )
                SourcesButton(
                    label = stringResource(R.string.live_tv_refresh_all),
                    icon = Icons.Filled.Refresh,
                    onClick = viewModel::refreshAll,
                    modifier = downToSuggestion
                )
                SourcesButton(
                    label = if (ui.hidden.isEmpty()) stringResource(R.string.live_tv_hidden_channels)
                    else stringResource(R.string.live_tv_hidden_channels_count, ui.hidden.size),
                    icon = Icons.Filled.Visibility,
                    onClick = { hiddenOpen = true },
                    modifier = downToSuggestion.focusRequester(focusTargets.requester(HIDDEN_TARGET))
                )
            }
            packOffer?.let { offer ->
                // Spec 73 D6, stage 2: offered above the guide suggestions until the pack is added.
                GuideSuggestionRow(
                    title = stringResource(R.string.live_tv_pack_name),
                    facts = packOfferFacts(offer, numbers),
                    // Spec 73 D11 (Q3): Adding… while it downloads.
                    addLabel = stringResource(if (offer.adding) R.string.live_tv_adding else R.string.live_tv_pack_add),
                    onAdd = viewModel::addPack,
                    focusRequester = focusTargets.requester(PACK_TARGET)
                )
            }
            if (presetsFolded) {
                GuideSuggestionRow(
                    title = stringResource(R.string.live_tv_presets_title),
                    facts = foldedFacts(ui.presets, numbers),
                    addLabel = stringResource(R.string.live_tv_header_choose),
                    onAdd = { choosingPresets = true },
                    focusRequester = focusTargets.requester(PRESETS_TARGET)
                )
            } else {
                ui.presets.forEach { suggestion ->
                    key(suggestion.preset.key) {
                        GuideSuggestionRow(
                            title = presetTitle(suggestion.preset),
                            facts = presetFacts(suggestion, numbers),
                            addLabel = pluralStringResource(R.plurals.live_tv_preset_add, suggestion.preset.guides.size),
                            onAdd = { viewModel.addPresets(listOf(suggestion.preset.key)) },
                            focusRequester = focusTargets.requester(presetTarget(suggestion.preset.key))
                        )
                    }
                }
            }
            shownHeaders.forEach { suggestion ->
                key(suggestion.sourceId) {
                    GuideSuggestionRow(
                        title = stringResource(R.string.live_tv_header_title, suggestion.sourceName),
                        facts = headerFacts(suggestion, numbers),
                        addLabel = stringResource(
                            if (suggestion.addsAll) R.string.live_tv_header_add else R.string.live_tv_header_choose
                        ),
                        onAdd = {
                            if (suggestion.addsAll) {
                                viewModel.addHeaderGuides(suggestion.sourceId, suggestion.guides.map { it.url })
                            } else {
                                openChooser(suggestion.sourceId, headerTarget(suggestion.sourceId))
                            }
                        },
                        focusRequester = focusTargets.requester(headerTarget(suggestion.sourceId))
                    )
                }
            }
            ui.rows.forEachIndexed { index, row ->
                key(row.source.id) {
                    val header = ui.headers.firstOrNull { it.sourceId == row.source.id }
                    val isPack = row.source.kind == SourceKind.PACK
                    val running = scan as? PackScanner.State.Running
                    SourceListRow(
                        row = row,
                        position = index,
                        focusRequester = focusTargets.requester(row.source.id),
                        up = if (index == 0) lastSuggestion else FocusRequester.Default,
                        timeFormat = timeFormat,
                        numbers = numbers,
                        onRefresh = { viewModel.refresh(row.source.id) },
                        onRename = { renaming = row },
                        onRemove = { removing = row },
                        onChooseGuides = header?.let { { openChooser(it.sourceId, it.sourceId) } },
                        pack = if (isPack) PackRowScan(
                            line = packScanPhrase(scan, scanFacts, timeFormat, numbers),
                            status = running?.let { packScanStatus(it) },
                            summary = packScanSummary(scanFacts, numbers).takeIf { running == null },
                            // Stage 9: Scan again reopens the list with the last countries ticked.
                            // No facts (record without a readable pack file): no list to open; Refresh repairs it.
                            onScanAgain = { scanFacts?.let { openPicker(it.last?.countries?.toSet()) } },
                            // Stage 6: while a scan runs, OK reopens its progress panel.
                            onClick = if (running != null) ({ scanPanel = SCAN_PROGRESS }) else null
                        ) else null
                    )
                }
            }
            ui.metadataFetchedAtMs?.let { fetchedAt ->
                MetadataRow(fetchedAtMs = fetchedAt, position = ui.rows.size, timeFormat = timeFormat)
            }
        }
        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_live_tv), stringResource(R.string.live_tv_sources)))
    }

    if (addOpen) {
        AddSourcePanel(
            viewModel = viewModel,
            numbers = numbers,
            onDismiss = {
                viewModel.closeAdd()
                addOpen = false
                focusTargets.pending = ADD_TARGET
            }
        )
    }

    choosing?.let { sourceId ->
        val close = {
            choosing = null
            // Back where it opened, unless that suggestion row is gone (then its playlist row).
            focusTargets.pending = choosingFrom.takeIf { from ->
                from == sourceId || shownHeaders.any { headerTarget(it.sourceId) == from }
            } ?: sourceId
        }
        val suggestion = ui.headers.firstOrNull { it.sourceId == sourceId }
        if (suggestion == null) {
            // Every guide was added meanwhile, or the playlist removed (not while a restored route still loads).
            if (ui.loading) return@let
            LaunchedEffect(Unit) {
                choosing = null
                focusTargets.pending = if (ui.rows.any { it.source.id == sourceId }) sourceId else ADD_TARGET
            }
        } else {
            GuidesPickPanel(
                eyebrow = stringResource(R.string.live_tv_header_title, suggestion.sourceName),
                note = stringResource(
                    if (suggestion.byCountry) R.string.live_tv_header_pick_note_country else R.string.live_tv_header_pick_note
                ),
                items = suggestion.guides.map { guide ->
                    PickItem(
                        key = guide.url,
                        // No country: the file name, as several such guides may share a host.
                        title = guide.country?.let(LiveTvModel::countryName)
                            ?: guide.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
                            ?: guide.name,
                        end = guide.channels.takeIf { it > 0 }
                            ?.let { pluralStringResource(R.plurals.live_tv_header_channels, it, numbers.format(it)) },
                        guides = 1
                    )
                },
                onAdd = { urls ->
                    // The Added event then moves focus on to the first new guide row.
                    close()
                    viewModel.addHeaderGuides(sourceId, urls)
                },
                onDismiss = close
            )
        }
    }

    if (choosingPresets) {
        val close = {
            choosingPresets = false
            // Back to the folded row, else the first suggestion still shown, else Add source URL.
            focusTargets.pending = suggestions.firstOrNull() ?: ADD_TARGET
        }
        if (ui.presets.isEmpty()) {
            // Every preset was added meanwhile (not while a restored route still loads).
            if (!ui.loading) LaunchedEffect(Unit) { close() }
        } else {
            GuidesPickPanel(
                eyebrow = stringResource(R.string.live_tv_presets_title),
                note = stringResource(R.string.live_tv_presets_pick_note),
                items = ui.presets.map { suggestion ->
                    val guides = suggestion.preset.guides.size
                    PickItem(
                        key = suggestion.preset.key,
                        title = presetName(suggestion.preset),
                        end = listOfNotNull(
                            pluralStringResource(
                                R.plurals.live_tv_header_channels, suggestion.channels, numbers.format(suggestion.channels)
                            ),
                            guides.takeIf { it > 1 }?.let {
                                pluralStringResource(R.plurals.live_tv_header_guides, it, numbers.format(it))
                            }
                        ).joinToString(FACT_SEPARATOR),
                        guides = guides
                    )
                },
                onAdd = { keys ->
                    close()
                    viewModel.addPresets(keys)
                },
                onDismiss = close
            )
        }
    }

    PackScanPanels(
        panel = scanPanel,
        scan = scan,
        facts = scanFacts,
        pickInitial = pickInitial,
        result = scanResult,
        numbers = numbers,
        onPanel = { panel ->
            scanPanel = panel
            if (panel.isEmpty()) focusTargets.pending = ChannelPack.SOURCE_ID
        },
        // Whole world keeps the list's ticks for its Cancel / Back.
        onWorld = { ticked ->
            pickInitial = ticked
            scanPanel = SCAN_WORLD
        },
        onStart = { countries ->
            if (viewModel.startScan(countries)) {
                scanPanel = SCAN_PROGRESS
            } else {
                scanPanel = ""
                focusTargets.pending = ChannelPack.SOURCE_ID
            }
        },
        onCancel = viewModel::cancelScan,
        onResultDone = {
            scanResult = null
            focusTargets.pending = ChannelPack.SOURCE_ID
        },
        onScanAgain = { record ->
            scanResult = null
            // As the row's Scan again: no facts, no list (focus back on the pack row).
            if (scanFacts != null) openPicker(record.countries.toSet()) else focusTargets.pending = ChannelPack.SOURCE_ID
        },
        onOpenLiveTv = { record ->
            scanResult = null
            // D12 (Q4): one country filters the list to it; several or the whole world, All.
            onOpenLiveTv(record.countries.singleOrNull())
        }
    )

    if (hiddenOpen) {
        HiddenChannelsPanel(
            hidden = ui.hidden,
            onUnhide = viewModel::unhide,
            onDismiss = {
                hiddenOpen = false
                focusTargets.pending = HIDDEN_TARGET
            }
        )
    }

    renaming?.let { row ->
        val close = {
            renaming = null
            focusTargets.pending = row.source.id
        }
        RenamePanel(
            eyebrow = stringResource(R.string.live_tv_src_rename_eyebrow),
            current = row.source.name,
            originalName = row.source.name,
            resetLabel = null,
            note = stringResource(R.string.live_tv_src_rename_note),
            maxLength = SOURCE_NAME_MAX,
            onSave = { name ->
                // A blank name keeps the current one.
                if (name.isNotBlank()) viewModel.rename(row.source.id, name)
                close()
            },
            onDismiss = close
        )
    }

    removing?.let { row ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.live_tv_src_remove_eyebrow, row.source.name),
            body = stringResource(
                if (row.source.kind == SourceKind.XMLTV) R.string.live_tv_src_remove_guide_body
                else R.string.live_tv_src_remove_channels_body
            ),
            confirmLabel = stringResource(R.string.live_tv_src_remove),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                focusTargets.pending = addonFocusKeyAfterRemoval(ui.rows.map { it.source.id }, row.source.id)
                    ?: ADD_TARGET
                viewModel.remove(row.source.id)
                removing = null
            },
            onDismiss = {
                removing = null
                focusTargets.pending = row.source.id
            },
            destructive = true
        )
    }
}

/** `.lvsbt .btn`: a glass pill with its icon. */
@Composable
private fun SourcesButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val live = NuvioTheme.marquee.liveTv
    MarqueeGlassTextButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        height = live.buttonHeight,
        horizontalPadding = live.sourcesButtonPaddingHorizontal,
        textStyle = NuvioTheme.textStyles.panelButtonSmall,
        icon = icon
    )
}

/**
 * Stage 14 row (`.lvsr`): type, name over "what it gave · last refresh", health, chevron. While
 * focus is in the row a failed or partial refresh shows its reason under the line.
 */
@Composable
private fun SourceListRow(
    row: SourceRow,
    position: Int,
    focusRequester: FocusRequester,
    up: FocusRequester,
    timeFormat: DateFormat,
    numbers: NumberFormat,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onChooseGuides: (() -> Unit)?,
    pack: PackRowScan? = null
) {
    val marquee = NuvioTheme.marquee
    val source = row.source
    StripListRow(
        focusRequester = focusRequester,
        position = position,
        dimmedAtRest = false,
        up = up,
        onClick = pack?.onClick,
        actions = {
            pack?.let { CollectionRowAction(label = stringResource(R.string.live_tv_scan_again), onClick = it.onScanAgain) }
            CollectionRowAction(label = stringResource(R.string.live_tv_src_refresh), onClick = onRefresh)
            if (onChooseGuides != null) {
                CollectionRowAction(label = stringResource(R.string.live_tv_header_choose), onClick = onChooseGuides)
            }
            // The pack keeps its name (Spec 73 D6: no Rename).
            if (source.kind != SourceKind.PACK) {
                CollectionRowAction(label = stringResource(R.string.live_tv_src_rename), onClick = onRename)
            }
            CollectionRowAction(label = stringResource(R.string.live_tv_src_remove), onClick = onRemove, destructive = true)
        }
    ) { focusWithin ->
        SourceType(
            stringResource(
                when (source.kind) {
                    SourceKind.M3U -> R.string.live_tv_type_playlist
                    SourceKind.STREMIO -> R.string.live_tv_type_addon
                    SourceKind.XMLTV -> R.string.live_tv_type_guide
                    SourceKind.PACK -> R.string.live_tv_type_pack
                }
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            SourceName(source.name)
            SourceLine(listOfNotNull(sourceLine(row, timeFormat, numbers), pack?.line).joinToString(FACT_SEPARATOR))
            val summary = pack?.summary
            if (focusWithin && summary != null) {
                // Stage 9 (`.s73why`): the last scan summed up, and how many channels wait.
                Text(
                    text = summary,
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.stream.dialogBody,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val error = source.lastError
            if (focusWithin && error != null) {
                Text(
                    text = stringResource(R.string.live_tv_src_last_error, error),
                    style = NuvioTheme.textStyles.caption,
                    color = healthColor(row.health),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val status = pack?.status
        if (status != null) {
            Text(text = status, style = NuvioTheme.textStyles.techBadge, color = marquee.onImage3, maxLines = 1)
        } else {
            HealthLabel(row.health)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = marquee.onImage3,
            modifier = Modifier.size(NuvioTheme.marquee.liveTv.sourcesIconSize)
        )
    }
}

/**
 * The pack row's scan parts (Spec 73 stages 6, 9): [line] after the refresh, [status] in place of
 * the health while a scan runs, [summary] under the line while focused, the strip's Scan again,
 * and [onClick] replacing the strip while a scan runs.
 */
private class PackRowScan(
    val line: String,
    val status: String?,
    val summary: String?,
    val onScanAgain: () -> Unit,
    val onClick: (() -> Unit)?
)

private const val SCAN_PICK = "pick"
private const val SCAN_WORLD = "world"
private const val SCAN_PROGRESS = "progress"

/** Spec 73 S3b: whichever scan panel [panel] names, then the ended scan's [result] panel. */
@Composable
private fun PackScanPanels(
    panel: String,
    scan: PackScanner.State,
    facts: PackScanFacts?,
    pickInitial: Set<String?>?,
    result: PackScanRecord?,
    numbers: NumberFormat,
    onPanel: (String) -> Unit,
    onWorld: (ticked: Set<String?>) -> Unit,
    onStart: (Set<String?>) -> Unit,
    onCancel: () -> Unit,
    onResultDone: () -> Unit,
    onScanAgain: (PackScanRecord) -> Unit,
    onOpenLiveTv: (PackScanRecord) -> Unit
) {
    val world = facts?.world.orEmpty()
    when (panel) {
        // The list waits for the pack's figures (read once the pack is stored).
        SCAN_PICK -> if (facts != null) {
            PackScanPickPanel(
                facts = facts,
                initial = pickInitial ?: setOfNotNull(facts.thisTv?.country),
                numbers = numbers,
                onScan = onStart,
                onWorld = onWorld,
                onDismiss = { onPanel("") }
            )
        }
        SCAN_WORLD -> if (facts != null) {
            PackScanWorldPanel(facts, numbers, onScan = { onStart(facts.world) }, onDismiss = { onPanel(SCAN_PICK) })
        }
        // Never under the ended scan's result panel (it arrives a moment before Idle).
        SCAN_PROGRESS -> (scan as? PackScanner.State.Running)?.takeIf { result == null }?.let { running ->
            PackScanProgressPanel(running, world, numbers, onHide = { onPanel("") }, onCancel = onCancel)
        }
    }
    result?.let { record ->
        PackScanResultPanel(
            record = record,
            world = world,
            numbers = numbers,
            onOpenLiveTv = { onOpenLiveTv(record) },
            onScanAgain = { onScanAgain(record) },
            onDismiss = onResultDone
        )
    }
}

/**
 * Spec 38 stage 6 (`.lvsr.l38sug`; Spec 49 D27 F5 reuses it for the India guides): SUGGESTED, the
 * guide's name over who holds the channels it covers, then the add button (the row's one focus
 * stop, [focusRequester]). Gone once a source has the guide's URL; adding moves focus to the new
 * guide row (the Added event).
 */
@Composable
private fun GuideSuggestionRow(
    title: String,
    facts: List<String>,
    addLabel: String,
    onAdd: () -> Unit,
    focusRequester: FocusRequester
) {
    val live = NuvioTheme.marquee.liveTv
    val tokens = NuvioTheme.marquee.collections
    val shape = RoundedCornerShape(tokens.rowRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(live.suggestFill)
            .border(1.dp, live.suggestEdge, shape)
            .padding(horizontal = tokens.rowPaddingHorizontal, vertical = tokens.rowPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(tokens.rowInnerGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceType(stringResource(R.string.live_tv_type_suggested), NuvioTheme.marquee.readyText)
        Column(modifier = Modifier.weight(1f)) {
            SourceName(title)
            SourceLine(facts.joinToString(FACT_SEPARATOR))
        }
        MarqueePrimaryButton(
            label = addLabel,
            icon = null,
            onClick = onAdd,
            focusRequester = focusRequester,
            compact = true
        )
    }
}

/**
 * The SUGGESTED pack row's line (Spec 73 D6, stage 2): "21,037 free channels from 177 countries,
 * checked twice a day · 1.6 MB · Nuvio-KE-channels on GitHub"; without figures, no count or size.
 */
@Composable
private fun packOfferFacts(offer: PackOffer, numbers: NumberFormat): List<String> {
    val summary = offer.summary
        ?: return listOf(
            stringResource(R.string.live_tv_pack_offer_unknown), // Spec 73 D11 (Q2): no figures
            stringResource(R.string.live_tv_pack_home)
        )
    return listOf(
        stringResource(R.string.live_tv_pack_offer, numbers.format(summary.channels), numbers.format(summary.countries)),
        packSize(summary.bytes),
        stringResource(R.string.live_tv_pack_home)
    )
}

/** The pack's download size as the offers show it ("1.6 MB", decimal units). */
@Composable
internal fun packSize(bytes: Long): String {
    val context = LocalContext.current
    return remember(context, bytes) { android.text.format.Formatter.formatShortFileSize(context, bytes) }
}

/**
 * A header suggestion's line (`s55-few` / `s55-many`): "UK Freeview names 2 guides for its 74
 * channels · hosts", or by country "Free-TV names guides for 52 of its 92 countries · one guide
 * per country · hosts".
 */
@Composable
private fun headerFacts(suggestion: HeaderSuggestion, numbers: NumberFormat): List<String> {
    val hosts = suggestion.hosts.joinToString(", ")
    return if (suggestion.byCountry) {
        listOf(
            stringResource(
                R.string.live_tv_header_by_country,
                suggestion.sourceName,
                numbers.format(suggestion.guides.size),
                pluralStringResource(R.plurals.live_tv_header_countries, suggestion.countries, numbers.format(suggestion.countries))
            ),
            stringResource(R.string.live_tv_header_per_country),
            hosts
        )
    } else {
        listOf(
            stringResource(
                R.string.live_tv_header_few,
                suggestion.sourceName,
                pluralStringResource(R.plurals.live_tv_header_guides, suggestion.guides.size, numbers.format(suggestion.guides.size)),
                pluralStringResource(R.plurals.live_tv_header_channels, suggestion.channels, numbers.format(suggestion.channels))
            ),
            hosts
        )
    }
}

/** One row of [GuidesPickPanel]: [end] is its right-hand text, [guides] the guide records ticking it adds. */
private data class PickItem(val key: String, val title: String, val end: String?, val guides: Int)

/**
 * Choose guides (`s55-pick`, `s55-s2-pick`): a centred panel over Sources listing [items] in
 * order; OK ticks or unticks a row; the footer's button (below the last row) adds the ticked keys
 * through [onAdd], counting their guides, dimmed and inert while none is ticked. Back calls
 * [onDismiss] without adding.
 */
@Composable
private fun GuidesPickPanel(
    eyebrow: String,
    note: String,
    items: List<PickItem>,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val live = NuvioTheme.marquee.liveTv
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    // A row that left the list (added elsewhere) neither counts nor adds.
    val shownTicked = ticked.filter { key -> items.any { it.key == key } }
    val tickedGuides = items.filter { it.key in shownTicked }.sumOf { it.guides }
    val first = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.guidesPickVeil,
        width = live.guidesPickWidth,
        initialFocus = first,
        color = live.guidesPickFill
    ) {
        PanelEyebrow(text = eyebrow)
        Text(
            text = note,
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage3,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        )
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, item ->
                key(item.key) {
                    PlayerPanelRow(
                        title = item.title,
                        selected = item.key in shownTicked,
                        selectedFill = false,
                        titleEnd = item.end,
                        focusRequester = if (index == 0) first else null,
                        onClick = { ticked = if (item.key in ticked) ticked - item.key else ticked + item.key }
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .drawBehind {
                    drawLine(PANEL_RULE, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
                .padding(start = 12.dp, top = 12.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.live_tv_header_ticked, shownTicked.size),
                style = NuvioTheme.textStyles.caption,
                color = NuvioTheme.marquee.onImage3,
                modifier = Modifier.weight(1f)
            )
            MarqueePrimaryButton(
                label = if (shownTicked.isEmpty()) stringResource(R.string.live_tv_header_add)
                else pluralStringResource(R.plurals.live_tv_header_add_n, tickedGuides, tickedGuides),
                icon = null,
                onClick = { if (shownTicked.isNotEmpty()) onAdd(shownTicked) },
                modifier = Modifier.alpha(if (shownTicked.isEmpty()) DIM_ALPHA else 1f),
                compact = true
            )
        }
    }
}

/** A service preset's name (Plex TV), else its country's (India). */
@Composable
private fun presetName(preset: GuidePreset): String = when (preset.service) {
    GuideService.PLUTO -> stringResource(R.string.live_tv_service_pluto)
    GuideService.PLEX -> stringResource(R.string.live_tv_service_plex)
    GuideService.SAMSUNG -> stringResource(R.string.live_tv_service_samsung)
    null -> LiveTvModel.countryName(preset.country.orEmpty())
}

/** "Plex TV guide", "United Kingdom TV guide", "Australia TV guides" (`s55-s2-*`). */
@Composable
private fun presetTitle(preset: GuidePreset): String =
    if (preset.service != null) stringResource(R.string.live_tv_preset_service_title, presetName(preset))
    else pluralStringResource(R.plurals.live_tv_preset_country_title, preset.guides.size, presetName(preset))

/**
 * A preset row's line: who holds the channels it fits, then (services and India, whose one guide
 * since Spec 55 D16 matches what its old pair did) what the guides cover, then the hosts: "Plex US has 698 Plex channels · this
 * guide covers all of them · i.mjh.nz", "UK has 282 channels from the United Kingdom ·
 * open-epg.com". A new country row promises no cover (open-epg matched 81 of 282, D12).
 */
@Composable
private fun presetFacts(suggestion: PresetSuggestion, numbers: NumberFormat): List<String> {
    val preset = suggestion.preset
    val n = suggestion.channels
    val count = numbers.format(n)
    val holder = suggestion.sourceName
    val held = if (preset.service != null) {
        val what = if (preset.service == GuideService.PLEX) stringResource(R.string.live_tv_service_plex_channels)
        else presetName(preset)
        holder?.let { pluralStringResource(R.plurals.live_tv_preset_service_in_source, n, count, it, what) }
            ?: pluralStringResource(R.plurals.live_tv_preset_service_in_sources, n, count, what)
    } else {
        val place = if (preset.country == UK) stringResource(R.string.live_tv_preset_place_uk) else presetName(preset)
        holder?.let { pluralStringResource(R.plurals.live_tv_preset_country_in_source, n, count, it, place) }
            ?: pluralStringResource(R.plurals.live_tv_preset_country_in_sources, n, count, place)
    }
    val covers = when {
        // India's one guide (Spec 55 D16) matched as many channels as its old pair did.
        preset.service != null || preset.country == INDIA -> pluralStringResource(R.plurals.live_tv_preset_covers, n)
        else -> null
    }
    val hosts = preset.guides.mapNotNull { it.url.toHttpUrlOrNull()?.host?.removePrefix("www.") }.distinct()
    return listOfNotNull(held, covers, hosts.joinToString(", "))
}

/** The folded row's line (`s55-s2-many`): "8 guides fit your channels · Plex TV, ..., India and 4 more countries". */
@Composable
private fun foldedFacts(presets: List<PresetSuggestion>, numbers: NumberFormat): List<String> {
    val named = presets.take(FOLDED_NAMED).map { presetName(it.preset) }.joinToString(", ")
    val rest = presets.drop(FOLDED_NAMED)
    val names = when {
        rest.isEmpty() -> named
        rest.all { it.preset.country != null } ->
            pluralStringResource(R.plurals.live_tv_presets_more_countries, rest.size, named, numbers.format(rest.size))
        else -> pluralStringResource(R.plurals.live_tv_presets_more, rest.size, named, numbers.format(rest.size))
    }
    return listOf(pluralStringResource(R.plurals.live_tv_presets_fit, presets.size, numbers.format(presets.size)), names)
}

/** Presets the folded row names before "and N more". */
private const val FOLDED_NAMED = 4

private const val UK = "uk"
private const val INDIA = "in"

/** The Choose guides footer's top rule (`.s55foot`, white .12, as [PanelEyebrow]'s). */
private val PANEL_RULE = Color.White.copy(alpha = 0.12f)

/** The Choose guides button while nothing is ticked. */
private const val DIM_ALPHA = 0.45f

/** The DATA row: iptv-org channel data, refreshed on its own every 3 days (view only). */
@Composable
private fun MetadataRow(fetchedAtMs: Long, position: Int, timeFormat: DateFormat) {
    val focusRequester = remember { FocusRequester() }
    StripListRow(focusRequester = focusRequester, position = position, dimmedAtRest = false, actions = null) {
        SourceType(stringResource(R.string.live_tv_type_data))
        Column(modifier = Modifier.weight(1f)) {
            SourceName(stringResource(R.string.live_tv_src_metadata_name))
            SourceLine(
                listOf(
                    stringResource(R.string.live_tv_src_metadata_line),
                    refreshedPhrase(fetchedAtMs, timeFormat)
                ).joinToString(FACT_SEPARATOR)
            )
        }
    }
}

/**
 * `.lvsr .ty`: the type label in its fixed column, on one line. A label wider than the column
 * (SUGGESTED draws ~70 dp on the Shield) runs into the gap after it, as the mockup's unwrapped
 * `width:66px` box does, instead of breaking the word (12/N-1); the column stays put so
 * every row's name lines up.
 */
@Composable
private fun SourceType(text: String, color: Color = NuvioTheme.marquee.onImage3) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.techBadge,
        color = color,
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = 1,
        modifier = Modifier.width(NuvioTheme.marquee.liveTv.sourcesTypeWidth)
    )
}

@Composable
private fun SourceName(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.button,
        color = NuvioPrimitives.white,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SourceLine(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * "Built in · 7 channels · DW English, ...", "M3U · 103 channels · refreshed today 06:10",
 * "Stremio TV addon · 38 channels · ...", "XMLTV · 2,289 channels · 41 matched · ...".
 */
@Composable
private fun sourceLine(row: SourceRow, timeFormat: DateFormat, numbers: NumberFormat): String {
    val source = row.source
    val channels = row.listed?.let { stringResource(R.string.live_tv_src_channels, numbers.format(it)) }
    val refreshed = refreshedPhrase(source.lastRefreshMs, timeFormat)
    val parts = when {
        source.id == LiveSourceManager.PRESET_ID ->
            listOf(stringResource(R.string.live_tv_src_built_in), channels, row.preview.joinToString(", ").ifEmpty { null })
        source.kind == SourceKind.M3U -> listOf(stringResource(R.string.live_tv_src_m3u), channels, refreshed)
        source.kind == SourceKind.STREMIO -> listOf(stringResource(R.string.live_tv_src_addon), channels, refreshed)
        // Spec 73 D6, stage 9: every pack channel, listed or waiting for a scan (the scan's part: [PackRowScan.line]).
        source.kind == SourceKind.PACK -> listOf(channels, refreshed)
        else -> listOf(
            stringResource(R.string.live_tv_src_xmltv),
            channels,
            row.matched?.takeIf { row.listed != null }?.let { stringResource(R.string.live_tv_src_matched, numbers.format(it)) },
            refreshed
        )
    }
    return parts.filterNotNull().joinToString(FACT_SEPARATOR)
}

@Composable
private fun refreshedPhrase(lastMs: Long?, timeFormat: DateFormat): String =
    when (val age = LiveTvSourcesModel.refreshAge(lastMs, System.currentTimeMillis())) {
        RefreshAge.Never -> stringResource(R.string.live_tv_refreshed_never)
        else -> stringResource(R.string.live_tv_refreshed, liveTvAgePhrase(age, timeFormat))
    }

/** An age in words: "just now", "12 min ago", "today 14:02", "yesterday", "3 days ago" (Never reads as just now). */
@Composable
internal fun liveTvAgePhrase(age: RefreshAge, timeFormat: DateFormat): String = when (age) {
    RefreshAge.Never, RefreshAge.JustNow -> stringResource(R.string.live_tv_ago_just_now)
    is RefreshAge.Minutes -> stringResource(R.string.live_tv_ago_minutes, age.count)
    is RefreshAge.Today -> stringResource(R.string.live_tv_ago_today, timeFormat.format(Date(age.atMs)))
    is RefreshAge.Days -> if (age.count == 1) stringResource(R.string.live_tv_ago_yesterday)
    else stringResource(R.string.live_tv_ago_days, age.count)
}

@Composable
private fun healthColor(health: SourceHealth) = when (health) {
    SourceHealth.Ok -> NuvioTheme.marquee.readyText
    is SourceHealth.Lost, SourceHealth.Partial -> NuvioTheme.marquee.liveTv.warn
    SourceHealth.Failed -> NuvioPrimitives.errorOnImage
    SourceHealth.Waiting, SourceHealth.Refreshing -> NuvioTheme.marquee.onImage3
}

@Composable
private fun HealthLabel(health: SourceHealth) {
    Text(
        text = when (health) {
            SourceHealth.Ok -> stringResource(R.string.live_tv_health_ok)
            is SourceHealth.Lost -> stringResource(R.string.live_tv_health_lost, health.count)
            SourceHealth.Partial -> stringResource(R.string.live_tv_health_partial)
            SourceHealth.Failed -> stringResource(R.string.live_tv_health_failed)
            SourceHealth.Waiting -> stringResource(R.string.live_tv_health_waiting)
            SourceHealth.Refreshing -> stringResource(R.string.live_tv_health_refreshing)
        },
        style = NuvioTheme.textStyles.techBadge,
        color = healthColor(health),
        maxLines = 1
    )
}

/**
 * Stage 15: one URL field (OK opens the keyboard; its Done runs the check and moves to Add), the
 * check's line (type and count, or the refusal reason), Cancel / Add. Add stays dimmed until the
 * check passes. Back or Cancel calls [onDismiss].
 */
@Composable
private fun AddSourcePanel(viewModel: LiveTvSourcesViewModel, numbers: NumberFormat, onDismiss: () -> Unit) {
    val live = NuvioTheme.marquee.liveTv
    val stream = NuvioTheme.marquee.stream
    val url by viewModel.addUrl.collectAsState()
    val check by viewModel.addCheck.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    MarqueeCentredPanel(
        onDismissRequest = onDismiss,
        veil = live.addVeil,
        width = live.addPanelWidth,
        initialFocus = fieldFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_add_eyebrow))
        Text(
            text = stringResource(R.string.live_tv_add_body),
            style = NuvioTheme.textStyles.panelBody,
            color = stream.dialogBody,
            modifier = Modifier.padding(horizontal = stream.dialogTextInset)
        )
        // A URL reads left to right in RTL locales too.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.padding(horizontal = stream.dialogTextInset)) {
                MarqueeNameField(
                    value = url,
                    onValueChange = viewModel::onAddUrlChange,
                    maxLength = URL_MAX,
                    placeholder = stringResource(R.string.live_tv_add_placeholder),
                    focusRequester = fieldFocus,
                    onDone = {
                        viewModel.checkAdd()
                        addFocus.requestFocus()
                    },
                    keyboardType = KeyboardType.Uri,
                    showCounter = false
                )
            }
        }
        AddCheckLine(check, numbers)
        MarqueePanelActions {
            MarqueeGlassTextButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            val ready = check is AddCheck.Ready
            MarqueeGlassTextButton(
                label = stringResource(if (check == AddCheck.Adding) R.string.live_tv_adding else R.string.live_tv_add),
                // Dimmed but focusable until the check passes, so Done can land here.
                onClick = { if (ready) viewModel.confirmAdd() },
                restingLabelColor = if (ready) NuvioPrimitives.white else NuvioTheme.marquee.onImage3,
                modifier = Modifier.focusRequester(addFocus)
            )
        }
    }
}

/** `.lvdet`: what the check found (green with a tick), the refusal reason, or "Checking…". */
@Composable
private fun AddCheckLine(check: AddCheck, numbers: NumberFormat) {
    val marquee = NuvioTheme.marquee
    val (text, color) = when (check) {
        AddCheck.Idle, AddCheck.Adding -> return
        AddCheck.Checking -> stringResource(R.string.live_tv_add_checking) to marquee.onImage2
        is AddCheck.Refused -> check.reason to NuvioPrimitives.errorOnImage
        is AddCheck.Ready -> {
            val listed = numbers.format(check.checked.listed)
            when (check.checked.source.kind) {
                SourceKind.M3U -> stringResource(R.string.live_tv_add_ready_m3u, listed)
                // A guide's channelCount is the channels it matched (LiveSource).
                SourceKind.XMLTV -> stringResource(
                    R.string.live_tv_add_ready_xmltv,
                    listed,
                    numbers.format(check.checked.source.channelCount)
                )
                SourceKind.STREMIO -> stringResource(R.string.live_tv_add_ready_addon, listed)
                // The pack is added by Add pack, never through a URL check.
                SourceKind.PACK -> stringResource(R.string.live_tv_add_ready_m3u, listed)
            } to marquee.readyText
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = NuvioTheme.marquee.stream.dialogTextInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        if (check is AddCheck.Ready) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(NuvioTheme.marquee.liveTv.pillIconSize)
            )
        }
        Text(text = text, style = NuvioTheme.textStyles.caption, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Hidden channels (no v3 stage; the hold-OK panel's shape): one row per hidden channel (name, its
 * source); OK shows it again and focus moves to its neighbour. Back calls [onDismiss].
 */
@Composable
private fun HiddenChannelsPanel(hidden: List<HiddenRow>, onUnhide: (String) -> Unit, onDismiss: () -> Unit) {
    val live = NuvioTheme.marquee.liveTv
    val targets = rememberCollectionFocusTargets()
    val initial = remember { targets.requester(hidden.firstOrNull()?.key ?: EMPTY_KEY) }
    // After an unhide: its neighbour, else the empty row once the list has emptied.
    targets.ServePendingFocus(content = hidden)
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = live.optionsPanelWidth,
        insetVertical = live.panelInsetVertical,
        initialFocus = initial,
        fillHeight = false
    ) {
        PanelEyebrow(text = stringResource(R.string.live_tv_hidden_channels))
        if (hidden.isEmpty()) {
            PlayerPanelRow(
                title = stringResource(R.string.live_tv_hidden_empty),
                selected = false,
                enabled = false,
                focusRequester = targets.requester(EMPTY_KEY),
                onClick = {}
            )
        } else {
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                hidden.forEach { row ->
                    key(row.key) {
                        PlayerPanelRow(
                            title = row.name,
                            subtitle = row.sourceName,
                            selected = false,
                            trailingIcon = Icons.Filled.Visibility,
                            focusRequester = targets.requester(row.key),
                            onClick = {
                                targets.pending = addonFocusKeyAfterRemoval(hidden.map { it.key }, row.key) ?: EMPTY_KEY
                                onUnhide(row.key)
                            }
                        )
                    }
                }
            }
        }
        PanelNote(stringResource(R.string.live_tv_hidden_note))
    }
}

private const val EMPTY_KEY = "#empty"
