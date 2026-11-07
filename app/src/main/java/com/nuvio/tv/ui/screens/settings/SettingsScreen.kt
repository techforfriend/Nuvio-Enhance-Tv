@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.account.AccountSettingsInline
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector
)

/** Frames a return target's pane gets to compose its launching row before focus falls back. */
private const val RETURN_ROW_WAIT_FRAMES = 8

/**
 * The same for a deep entry ([SettingsEntry]): its pane may open cold, and the Playback pane
 * draws no rows until its stores load (about a second at 60 fps).
 */
private const val ENTRY_ROW_WAIT_FRAMES = 60

@Composable
private fun settingsSectionSpec(category: SettingsCategory): SettingsSectionSpec = when (category) {
    SettingsCategory.ACCOUNT -> SettingsSectionSpec(category, stringResource(R.string.settings_account), Icons.Default.Person)
    SettingsCategory.PROFILES -> SettingsSectionSpec(category, stringResource(R.string.settings_profiles), Icons.Default.People)
    SettingsCategory.APPEARANCE -> SettingsSectionSpec(category, stringResource(R.string.appearance_title), Icons.Default.Palette)
    SettingsCategory.LAYOUT -> SettingsSectionSpec(category, stringResource(R.string.settings_layout), Icons.Default.GridView)
    SettingsCategory.CONTENT_DISCOVERY ->
        SettingsSectionSpec(category, stringResource(R.string.settings_content_discovery), Icons.Default.Explore)
    SettingsCategory.INTEGRATION -> SettingsSectionSpec(category, stringResource(R.string.settings_integration), Icons.Default.Link)
    SettingsCategory.PLAYBACK -> SettingsSectionSpec(category, stringResource(R.string.settings_playback), Icons.Rounded.PlayArrow)
    SettingsCategory.ABOUT -> SettingsSectionSpec(category, stringResource(R.string.about_title), Icons.Default.Info)
    SettingsCategory.ADVANCED -> SettingsSectionSpec(category, stringResource(R.string.settings_advanced), Icons.Default.Build)
    SettingsCategory.DEBUG -> SettingsSectionSpec(category, stringResource(R.string.settings_debug), Icons.Default.BugReport)
}

/** Where focus may be: the rail, a pane entered with Right/OK, or a pane row restored on return. */
private enum class PaneFocus { RAIL, ENTERED, RESTORED }

/**
 * Rows that open a sub-route register here (through [rememberSettingsReturnTarget]); a launch
 * records the row so focus goes back to it when Settings composes again (NavHost disposes Settings under a sub-route, and an Activity
 * restart rebuilds it).
 */
internal class SettingsReturnFocus(private val onMark: (rowKey: String) -> Unit) {
    private val rows = mutableMapOf<String, FocusRequester>()

    fun register(rowKey: String, requester: FocusRequester) {
        rows[rowKey] = requester
    }

    fun unregister(rowKey: String, requester: FocusRequester) {
        if (rows[rowKey] === requester) rows.remove(rowKey)
    }

    fun requester(rowKey: String): FocusRequester? = rows[rowKey]

    fun markLaunch(rowKey: String) = onMark(rowKey)
}

/** Provided by the Settings shell; outside it, marks go nowhere. */
internal val LocalSettingsReturnFocus = staticCompositionLocalOf { SettingsReturnFocus(onMark = {}) }

/** A row the shell can return focus to: attach [requester], open the sub-route through [launch]. */
internal class SettingsReturnRow(
    val requester: FocusRequester,
    private val rowKey: String,
    private val host: SettingsReturnFocus
) {
    /** Records this row as the return target, then runs [navigate]. */
    fun launch(navigate: () -> Unit) {
        mark()
        navigate()
    }

    /** Records this row without navigating (the language change restarts the Activity). */
    fun mark() = host.markLaunch(rowKey)
}

@Composable
internal fun rememberSettingsReturnTarget(rowKey: String): SettingsReturnRow {
    val host = LocalSettingsReturnFocus.current
    val row = remember(host, rowKey) { SettingsReturnRow(FocusRequester(), rowKey, host) }
    DisposableEffect(row) {
        host.register(rowKey, row.requester)
        onDispose { host.unregister(rowKey, row.requester) }
    }
    return row
}

private suspend fun SettingsReturnFocus.awaitRow(
    rowKey: String,
    frames: Int = RETURN_ROW_WAIT_FRAMES
): FocusRequester? {
    repeat(frames) {
        requester(rowKey)?.let { return it }
        withFrameNanos { }
    }
    return requester(rowKey)
}

/**
 * Settings (Spec 31 S10, Q2): a category rail beside the selected category's pane. About carries
 * an amber dot while an update waits (Spec 52 T2, [UpdateViewModel.showNotice]).
 *
 * D-pad: a rail item selects [SETTINGS_SELECT_ON_FOCUS_MS] after focus lands on it; Right (Left in
 * RTL) or OK enters the pane through the requester the shell hands that pane, and only then: a
 * pane never focuses itself while the rail browses. About is entered only in a build with the
 * update check (its Updates rows); elsewhere it has nothing to focus. Left from a plain row returns to the rail's
 * selected item. Back in the pane returns to the rail; Back on the rail is left to the scaffold
 * (to the top bar) or, pushed over a page (no bar: the player, a no-key page), the NavHost (back
 * to that page). Down from
 * the top bar lands on the selected rail item.
 *
 * [entryName] is a one-shot deep entry ([SettingsEntry] name, openSettingsAt): [onEntryConsumed]
 * clears it at once, then its row takes focus (its Playback chip selected first) and, for an
 * entry that opens a panel, its pane opens that panel.
 */
@Composable
fun SettingsScreen(
    entryName: String? = null,
    onEntryConsumed: () -> Unit = {},
    onNavigateToAddons: () -> Unit = {},
    onNavigateToCloudStream: () -> Unit = {},
    onNavigateToCatalogOrder: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToDpadComboSetup: (String) -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel(),
    // The same instance About's pane resolves (this back-stack entry's store).
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()
    val showUpdateDot by updateViewModel.showNotice.collectAsStateWithLifecycle()
    val visibleCategories = remember(isPrimaryProfileActive) {
        visibleSettingsCategories(isPrimaryProfileActive, BuildConfig.IS_DEBUG_BUILD)
    }

    var selectedCategory by rememberSaveable { mutableStateOf(defaultSettingsCategory(visibleCategories)) }
    var savedReturnTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember {
        SettingsReturnFocus { rowKey ->
            savedReturnTarget = SettingsReturnTarget(selectedCategory, rowKey).encode()
        }
    }
    val railRequesters = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val paneRequesters = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    // ENTERED: the pane gets its entry requester. RAIL: focus landing in the pane bounces back.
    var paneFocus by remember { mutableStateOf(PaneFocus.RAIL) }
    var paneHasFocus by remember { mutableStateOf(false) }
    var focusedRailCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // A deep entry is taken off the SavedStateHandle at once and handled from here, so leaving
    // Settings mid-wait never replays it; an Activity restart drops it (normal entry instead).
    var entry by remember { mutableStateOf(SettingsEntry.fromName(entryName)) }
    // The deep entry's chip, held until its row has focus (the pane may compose late).
    var playbackSectionRequest by remember { mutableStateOf<PlaybackSection?>(null) }
    // A deep entry's panel (SettingsEntry.opensPanel), set once its row has focus; the pane clears it
    // when it opens the panel.
    var panelRequest by remember { mutableStateOf<SettingsCategory?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val toPaneKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
    val toPaneDirection = if (isRtl) FocusDirection.Left else FocusDirection.Right
    val contentAnchor = LocalContentFocusRequester.current
    // Saved with this back-stack entry: groups stay open over a sub-route, close on leaving Settings.
    val advancedGroups = rememberSettingsAdvancedGroups()

    val focusRail: () -> Unit = {
        runCatching { railRequesters.getValue(selectedCategory).requestFocus() }
    }
    val enterPane: (SettingsCategory) -> Unit = { category ->
        focusedRailCategory = null
        selectedCategory = category
        // About has nothing to focus without the update check.
        if (category != SettingsCategory.ABOUT || updateViewModel.supported) {
            paneFocus = PaneFocus.ENTERED
            scope.launch {
                val entered = paneRequesters.getValue(category).requestFocusAfterFrames() ||
                    focusManager.moveFocus(toPaneDirection)
                if (!entered) paneFocus = PaneFocus.RAIL
            }
        }
    }

    LaunchedEffect(visibleCategories) {
        if (selectedCategory !in visibleCategories) selectedCategory = defaultSettingsCategory(visibleCategories)
    }

    LaunchedEffect(Unit) {
        val saved = decodeSettingsReturnTarget(savedReturnTarget)
        savedReturnTarget = null
        // A deep entry decides focus (the effect below).
        if (entry != null) return@LaunchedEffect
        when (val focus = settingsEntryFocus(saved, selectedCategory, visibleCategories)) {
            is SettingsEntryFocus.Row -> {
                selectedCategory = focus.target.category
                paneFocus = PaneFocus.RESTORED
                val restored = returnFocus.awaitRow(focus.target.rowKey)
                    ?.requestFocusAfterFrames(frames = 0) == true
                if (!restored) {
                    paneFocus = PaneFocus.RAIL
                    railRequesters.getValue(selectedCategory).requestFocusAfterFrames()
                }
            }
            is SettingsEntryFocus.Rail -> {
                selectedCategory = focus.category
                railRequesters.getValue(focus.category).requestFocusAfterFrames()
            }
        }
    }

    LaunchedEffect(entryName) {
        if (entryName == null) return@LaunchedEffect
        SettingsEntry.fromName(entryName)?.let { entry = it }
        onEntryConsumed()
    }

    LaunchedEffect(entry) {
        val requested = entry ?: return@LaunchedEffect
        focusedRailCategory = null
        selectedCategory = requested.target.category
        playbackSectionRequest = requested.playbackSection
        paneFocus = PaneFocus.RESTORED
        val focused = returnFocus.awaitRow(requested.target.rowKey, ENTRY_ROW_WAIT_FRAMES)
            ?.requestFocusAfterFrames(frames = 0) == true
        if (!focused) {
            // The row is not shown (e.g. Buffer & network is hidden for the MPV engine).
            paneFocus = PaneFocus.RAIL
            railRequesters.getValue(selectedCategory).requestFocusAfterFrames()
        } else if (requested.opensPanel) {
            panelRequest = requested.target.category
        }
        playbackSectionRequest = null
        entry = null
    }

    // A panel request is for the pane it was made in: leaving that pane first drops it.
    LaunchedEffect(selectedCategory) {
        if (panelRequest != null && panelRequest != selectedCategory) panelRequest = null
    }

    LaunchedEffect(focusedRailCategory) {
        val category = focusedRailCategory ?: return@LaunchedEffect
        if (category == selectedCategory) return@LaunchedEffect
        delay(SETTINGS_SELECT_ON_FOCUS_MS)
        selectedCategory = category
    }

    BackHandler(enabled = paneHasFocus) { focusRail() }

    val tokens = NuvioTheme.marquee.settings
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = NuvioTheme.layout.tvSafeHorizontal,
                end = NuvioTheme.layout.tvSafeHorizontal,
                // Settings is a top-bar tab: rail and pane start below the bar (pushed over the
                // player, Spec 33 D7, the bar is hidden and its band stays).
                top = NuvioTheme.marquee.topBar.contentTop + tokens.topInset
            ),
        horizontalArrangement = Arrangement.spacedBy(tokens.railToPane)
    ) {
        Column(
            modifier = Modifier
                .width(tokens.railWidth)
                .fillMaxHeight()
                // Ten items (debug build) at the largest interface scale outgrow the screen.
                .verticalScroll(rememberScrollState())
                .onFocusChanged { state ->
                    if (state.hasFocus) paneFocus = PaneFocus.RAIL
                }
                // Directional entry (Left from the pane) lands on the selected item; programmatic
                // requests keep their own target.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection != FocusDirection.Enter) {
                            railRequesters.getValue(selectedCategory).requestFocus()
                        }
                    }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(tokens.railItemGap)
        ) {
            visibleCategories.forEach { category ->
                key(category) {
                    val section = settingsSectionSpec(category)
                    val selected = category == selectedCategory
                    SettingsRailItem(
                        title = section.title,
                        icon = section.icon,
                        selected = selected,
                        dot = category == SettingsCategory.ABOUT && showUpdateDot,
                        onClick = { enterPane(category) },
                        modifier = Modifier
                            .focusRequester(railRequesters.getValue(category))
                            .then(if (selected) Modifier.focusRequester(contentAnchor) else Modifier)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedRailCategory = category
                                } else if (focusedRailCategory == category) {
                                    focusedRailCategory = null
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == toPaneKey) {
                                    enterPane(category)
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { state ->
                    paneHasFocus = state.hasFocus
                    if (state.hasFocus && paneFocus == PaneFocus.RAIL) focusRail()
                }
        ) {
            CompositionLocalProvider(
                LocalSettingsAdvancedGroups provides advancedGroups,
                LocalSettingsReturnFocus provides returnFocus
            ) {
                SettingsDetailPane(
                    selectedCategory = selectedCategory,
                    playbackSection = playbackSectionRequest,
                    openPanel = panelRequest == selectedCategory,
                    onPanelOpened = { panelRequest = null },
                    initialFocusRequester = paneRequesters.getValue(selectedCategory).takeIf { paneFocus == PaneFocus.ENTERED },
                    onNavigateToManageProfiles = onNavigateToManageProfiles,
                    onNavigateToDpadComboSetup = onNavigateToDpadComboSetup,
                    onNavigateToAddons = onNavigateToAddons,
                    onNavigateToCloudStream = onNavigateToCloudStream,
                    onNavigateToCatalogOrder = onNavigateToCatalogOrder,
                    onNavigateToCollections = onNavigateToCollections,
                    onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
                )
            }
        }
    }
}

/**
 * `.l11ri`: icon + label (+ the amber [dot], Spec 52 `.s52dot`); selected = white with a bar at its
 * start, focused = white pill.
 */
@Composable
private fun SettingsRailItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    dot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.settings
    var focused by remember { mutableStateOf(false) }
    val contentColor = when {
        focused -> NuvioPrimitives.black
        selected -> NuvioPrimitives.white
        else -> NuvioTheme.marquee.onImage3
    }
    val barColor = NuvioPrimitives.white
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.railItemHeight)
            .semantics { this.selected = selected }
            .onFocusChanged { focused = it.isFocused }
            .drawBehind {
                if (selected && !focused) {
                    val barWidth = tokens.railBarWidth.toPx()
                    val barHeight = tokens.railBarHeight.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, (size.height - barHeight) / 2),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2)
                    )
                }
            }
            .background(if (focused) NuvioPrimitives.white else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = tokens.railItemPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.railIconGap)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(tokens.railIconSize)
        )
        Text(
            text = title,
            style = NuvioTheme.textStyles.tab,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (dot) {
            // The Row spaces children railIconGap apart; the dot sits closer, as drawn.
            Box(
                modifier = Modifier
                    .offset(x = tokens.railDotGap - tokens.railIconGap)
                    .size(tokens.railDotSize)
                    .background(NuvioPrimitives.warning, CircleShape)
            )
        }
    }
}

@Composable
private fun SettingsDetailPane(
    selectedCategory: SettingsCategory,
    playbackSection: PlaybackSection?,
    openPanel: Boolean,
    onPanelOpened: () -> Unit,
    initialFocusRequester: FocusRequester?,
    onNavigateToManageProfiles: () -> Unit,
    onNavigateToDpadComboSetup: (String) -> Unit,
    onNavigateToAddons: () -> Unit,
    onNavigateToCloudStream: () -> Unit,
    onNavigateToCatalogOrder: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToAuthQrSignIn: () -> Unit
) {
    when (selectedCategory) {
        SettingsCategory.ACCOUNT -> AccountSettingsInline(
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
            initialFocusRequester = initialFocusRequester
        )
        SettingsCategory.PROFILES -> ProfileSettingsContent(
            onManageProfiles = onNavigateToManageProfiles,
            onNavigateToDpadComboSetup = onNavigateToDpadComboSetup,
            initialFocusRequester = initialFocusRequester
        )
        SettingsCategory.APPEARANCE -> ThemeSettingsContent(initialFocusRequester = initialFocusRequester)
        SettingsCategory.LAYOUT -> LayoutSettingsContent(initialFocusRequester = initialFocusRequester)
        SettingsCategory.CONTENT_DISCOVERY -> ContentDiscoverySettingsContent(
            onNavigateToAddons = onNavigateToAddons,
            onNavigateToCatalogOrder = onNavigateToCatalogOrder,
            onNavigateToCollections = onNavigateToCollections,
            initialFocusRequester = initialFocusRequester
        )
        SettingsCategory.INTEGRATION -> TmdbSettingsContent(
            initialFocusRequester = initialFocusRequester,
            openKeyPanel = openPanel,
            onKeyPanelOpened = onPanelOpened,
            onNavigateToCloudStream = onNavigateToCloudStream
        )
        SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
            initialFocusRequester = initialFocusRequester,
            initialSection = playbackSection
        )
        SettingsCategory.ABOUT -> AboutSettingsContent(
            initialFocusRequester = initialFocusRequester,
            openUpdatePanel = openPanel,
            onUpdatePanelOpened = onPanelOpened
        )
        SettingsCategory.ADVANCED -> AdvancedSettingsContent(initialFocusRequester = initialFocusRequester)
        SettingsCategory.DEBUG -> DebugSettingsContent(initialFocusRequester = initialFocusRequester)
    }
}
