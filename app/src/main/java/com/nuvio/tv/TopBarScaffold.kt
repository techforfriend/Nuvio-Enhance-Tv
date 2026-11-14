package com.nuvio.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.marquee.MarqueeTab
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.settings.SettingsAccess
import com.nuvio.tv.ui.screens.settings.SettingsEntry
import com.nuvio.tv.ui.screens.settings.openSettingsAt
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberTabFocusRequesters
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import dagger.hilt.android.EntryPointAccessors
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** One top-bar tab. Every tab route is a root route: the bar shows only on these. */
data class TopBarTab(
    val route: String,
    val label: String
)

/** The active profile, shown as the bar's trailing avatar (OK = profile switch). */
data class TopBarProfile(
    val name: String,
    val colorHex: String,
    val avatarArt: AvatarArt?
)

/**
 * True while the top bar owns focus. Home reads it to hold hero trailers and folder hero videos
 * while the user is in the bar (#2815).
 */
val LocalTopBarFocused = compositionLocalOf { false }

/**
 * Marquee tab order (Live TV right after Home, Spec 34 D1); Discover is a tab only with "Show in Top Bar"
 * (stored IN_SIDEBAR); Live TV only when the active profile has it on (D17; null = not loaded yet, hidden).
 */
internal fun topBarTabRoutes(discoverLocation: DiscoverLocation?, liveTvEnabled: Boolean?): List<String> = buildList {
    add(Screen.Home.route)
    if (liveTvEnabled == true) add(Screen.LiveTv.route)
    add(Screen.Search.route)
    if (discoverLocation == DiscoverLocation.IN_SIDEBAR) add(Screen.Discover.route)
    add(Screen.Library.route)
    add(Screen.Settings.route)
}

/** Whether [route] is a Live TV screen (the tab or its Sources route), which D17 closes for a profile with it off. */
internal fun isLiveTvRoute(route: String?): Boolean =
    route == Screen.LiveTv.route || route == Screen.LiveTvSources.route

/**
 * Spec 64 D14: while [fellBack] says the active profile was removed and the id fell back to the
 * primary (deleted, or dropped by sync), [onSwitchProfile] sends the app back to Who's watching
 * instead of letting it carry on as the primary without its PIN. Picking a profile clears it.
 */
internal suspend fun returnToPickerOnFallback(fellBack: Flow<Boolean>, onSwitchProfile: () -> Unit) {
    fellBack.collect { if (it) onSwitchProfile() }
}

/** The tab that owns [currentRoute]: an exact match or one of its `tab/...` sub-routes. */
internal fun selectedTopBarRoute(tabRoutes: Collection<String>, currentRoute: String?): String? =
    currentRoute?.let { route -> tabRoutes.firstOrNull { route == it || route.startsWith("$it/") } }

/**
 * Whether the bar shows: over a tab root, except Settings pushed over a page (Spec 33 D7: the pause
 * screen's Network chip over the film; Spec 52 S4: a no-key page's Add a TMDB key). There Back must
 * return to that page, and a tab would pop it into the tab's saved state. Tab navigation leaves Home
 * (a tab) or nothing below a tab root.
 */
internal fun topBarShown(tabRoutes: Collection<String>, currentRoute: String?, previousRoute: String?): Boolean =
    when (currentRoute) {
        !in tabRoutes -> false
        Screen.Settings.route -> previousRoute == null || previousRoute in tabRoutes
        else -> previousRoute != Screen.Player.route
    }

/**
 * The current root screen's content anchor (Home rows, Live TV list, Search list, Library list,
 * Settings' selected rail item): Down from the bar and OK on the already-selected Home tab move focus here. A screen
 * without one (Discover) keeps the default spatial search.
 */
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

/**
 * Lets the current root screen hide the bar while it shows full-screen content (Live TV full
 * screen, Spec 34 L7c): true hides it on the current route until false or the next navigation.
 */
val LocalHideTopBar = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * App chrome around the main NavHost: the Marquee top bar (wave U slice U1b; spec
 * docs/design/marquee-design-system-2026-10-06.md §Components "Top bar"), drawn over the
 * content on root routes only. Root-screen content starts below it at
 * `NuvioTheme.marquee.topBar.contentTop` (NuvioNavHost passes the inset per route).
 *
 * D-pad: Up from content enters the bar on the selected tab; Left/Right walk the tabs and
 * the avatar; Down requests the screen's [LocalContentFocusRequester] (Home's restorers pick
 * the last card; Search and Library enter their top-left control: the field row, the selected
 * type tab; Settings its selected rail item), because spatial search cannot always get there:
 * Home's content draws under the bar, so its top focus group starts at y=0 and never counts as
 * "below" a tab (Search's and Library's lists start below it). OK navigates; focus alone never does, because every tab is a full destination with its own
 * loads (recorded deviation from CMP-TV-06). Back: screen-level handlers and the NavHost's
 * own pop are registered later and run first, so a non-Home tab (stack Home, tab) pops to
 * Home, linear Back as under the sidebars. On Home, Back focuses the selected tab and Back
 * again from the bar exits.
 *
 * [showUpdate] (Spec 52 T2: a newer release is known and Check automatically is on) puts the
 * Update pill before the clock, unless Settings is locked for this profile; OK opens Settings ›
 * About with the update panel open ([SettingsEntry.UPDATE]). [pulseUpdate]: the release the pill
 * pulses for once (Spec 80 D5); [onUpdatePulsed] reports the pulse started.
 */
@Composable
internal fun TopBarScaffold(
    navController: NavHostController,
    navViewModelStoreOwner: ViewModelStoreOwner,
    startDestination: String,
    currentRoute: String?,
    tabs: List<TopBarTab>,
    blockedRoutes: Set<String>,
    onBlockedRoute: () -> Unit,
    profile: TopBarProfile?,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit,
    showUpdate: Boolean = false,
    pulseUpdate: Int? = null,
    onUpdatePulsed: (Int) -> Unit = {}
) {
    val topBarRoutes = remember(tabs) { tabs.mapTo(HashSet()) { it.route } }
    // The route whose screen hid the bar (LocalHideTopBar); another route shows it again.
    val latestRoute by rememberUpdatedState(currentRoute)
    var barHiddenOn by remember { mutableStateOf<String?>(null) }
    val hideTopBar = remember { { hidden: Boolean -> barHiddenOn = if (hidden) latestRoute else null } }
    // Read when currentRoute changes (a navigation), which recomposes this.
    val showTopBar = topBarShown(topBarRoutes, currentRoute, navController.previousBackStackEntry?.destination?.route) &&
        barHiddenOn != currentRoute
    val selectedRoute = selectedTopBarRoute(topBarRoutes, currentRoute)
    val tabFocusRequesters = rememberTabFocusRequesters(tabs)
    val selectedTabRequester = selectedRoute?.let(tabFocusRequesters::get)
    val contentFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keyed on showTopBar: a bar that leaves composition while focused never reports the loss,
    // and a stale true would turn the next root-route Back into an exit.
    var topBarHasFocus by remember(showTopBar) { mutableStateOf(false) }
    val latestBlockedRoutes by rememberUpdatedState(blockedRoutes)
    val latestOnBlockedRoute by rememberUpdatedState(onBlockedRoute)
    // Home's empty state "Open addons": a locked profile gets the blocked-tab notice instead of
    // a navigate-then-eject bounce (AddonManager is settings-owned).
    val onOpenAddons = remember(navController) {
        {
            if (SettingsAccess.isBlockedFromTopBar(Screen.AddonManager.route, latestBlockedRoutes)) {
                latestOnBlockedRoute()
            } else {
                navController.navigate(Screen.AddonManager.route)
            }
        }
    }
    // A no-key page's Add a TMDB key (Spec 52 T1): Settings pushed over the page with the key panel
    // open (no bar there, topBarShown), so Back returns to it; a locked profile gets the notice.
    val onOpenTmdbKeySettings = remember(navController) {
        {
            if (Screen.Settings.route in latestBlockedRoutes) {
                latestOnBlockedRoute()
            } else {
                navController.openSettingsAt(SettingsEntry.TMDB_KEY) {
                    navigate(Screen.Settings.route) { launchSingleTop = true }
                }
            }
        }
    }

    ReturnToPickerWhenActiveProfileFallsBack(onSwitchProfile)

    BackHandler(enabled = showTopBar && !topBarHasFocus) {
        selectedTabRequester?.let { runCatching { it.requestFocus() } }
    }
    BackHandler(enabled = showTopBar && topBarHasFocus) {
        onExitApp()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OwnedNavHostSection(
            navController = navController,
            navViewModelStoreOwner = navViewModelStoreOwner,
            startDestination = startDestination,
            topBarRoutes = topBarRoutes,
            topBarHasFocus = topBarHasFocus,
            contentFocusRequester = contentFocusRequester,
            onHideTopBar = hideTopBar,
            onOpenAddons = onOpenAddons,
            onOpenTmdbKeySettings = onOpenTmdbKeySettings,
            settingsLocked = Screen.Settings.route in blockedRoutes,
            onSwitchProfile = onSwitchProfile
        )
        if (showTopBar) {
            TopBar(
                tabs = tabs,
                blockedRoutes = blockedRoutes,
                selectedRoute = selectedRoute,
                tabFocusRequesters = tabFocusRequesters,
                selectedTabRequester = selectedTabRequester,
                contentFocusRequester = contentFocusRequester,
                profile = profile,
                onFocusChanged = { topBarHasFocus = it },
                onTabClick = { route ->
                    keyboardController?.hide()
                    when {
                        route in blockedRoutes -> onBlockedRoute()
                        route != currentRoute -> {
                            onNavigate(route)
                            navigateToTopBarRoute(navController, route)
                        }
                        // OK on the selected Home tab: back to the top, focus into the rows.
                        route == Screen.Home.route -> if (requestHomeScrollToTop(navController)) {
                            coroutineScope.launch { contentFocusRequester.requestFocusAfterFrames() }
                        }
                    }
                },
                onProfileClick = onSwitchProfile,
                showUpdate = showUpdate && Screen.Settings.route !in blockedRoutes,
                pulseUpdate = pulseUpdate,
                onUpdatePulsed = onUpdatePulsed,
                onUpdateClick = {
                    keyboardController?.hide()
                    // On Settings already: hand the entry to it in place (a tab navigation to the
                    // current destination would pop and restore it).
                    navController.openSettingsAt(SettingsEntry.UPDATE) {
                        if (currentRoute != Screen.Settings.route) {
                            onNavigate(Screen.Settings.route)
                            navigateToTopBarRoute(navController, Screen.Settings.route)
                        }
                    }
                }
            )
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ActiveProfileEntryPoint {
    fun profileManager(): ProfileManager
}

/** D14: [returnToPickerOnFallback] over [ProfileManager.activeProfileFellBack]. */
@Composable
private fun ReturnToPickerWhenActiveProfileFallsBack(onSwitchProfile: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val fellBack = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ActiveProfileEntryPoint::class.java)
            .profileManager()
            .activeProfileFellBack
    }
    val latestOnSwitchProfile by rememberUpdatedState(onSwitchProfile)
    LaunchedEffect(fellBack) {
        returnToPickerOnFallback(fellBack) { latestOnSwitchProfile() }
    }
}

@Composable
private fun TopBar(
    tabs: List<TopBarTab>,
    blockedRoutes: Set<String>,
    selectedRoute: String?,
    tabFocusRequesters: Map<String, FocusRequester>,
    selectedTabRequester: FocusRequester?,
    contentFocusRequester: FocusRequester,
    profile: TopBarProfile?,
    onFocusChanged: (Boolean) -> Unit,
    onTabClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    showUpdate: Boolean,
    pulseUpdate: Int?,
    onUpdatePulsed: (Int) -> Unit,
    onUpdateClick: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val bar = marquee.topBar
    val scrimBrush = remember(marquee) {
        Brush.verticalGradient(listOf(marquee.scrimTopStart, Color.Transparent))
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        // media.scrimTop: keeps the bar legible over artwork and content scrolled beneath it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(marquee.scrimTopHeight)
                .background(scrimBrush)
        )
        Row(
            modifier = Modifier
                .padding(top = bar.top)
                .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
                .fillMaxWidth()
                .height(bar.height)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                // Down into the content (F-8, window 2026-10-08). A key handler, not
                // focusProperties { down = ... }: the anchor is unattached on screens without
                // one, and a directional override at an unattached requester fails the move.
                // A failed request falls through to spatial search.
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
                }
                .focusProperties {
                    // Directional entry (Up from content) always lands on the selected tab;
                    // programmatic requests (FocusDirection.Enter, e.g. the Back handler) keep
                    // their own target.
                    onEnter = {
                        if (requestedFocusDirection != FocusDirection.Enter) {
                            selectedTabRequester?.requestFocus()
                        }
                    }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(bar.itemGap)
        ) {
            Text(
                text = stringResource(R.string.top_bar_wordmark),
                style = NuvioTheme.textStyles.wordmark,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
            tabs.forEach { tab ->
                key(tab.route) {
                    MarqueeTab(
                        label = tab.label,
                        selected = tab.route == selectedRoute,
                        onClick = { onTabClick(tab.route) },
                        locked = tab.route in blockedRoutes,
                        modifier = Modifier.focusRequester(tabFocusRequesters.getValue(tab.route))
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(bar.trailingGap)
            ) {
                if (showUpdate) TopBarUpdatePill(pulse = pulseUpdate, onPulsed = onUpdatePulsed, onClick = onUpdateClick)
                TopBarClock()
                if (profile != null) {
                    TopBarAvatar(profile = profile, onClick = onProfileClick)
                }
            }
        }
    }
}

@Composable
private fun TopBarAvatar(
    profile: TopBarProfile,
    onClick: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val avatarSize = marquee.topBar.avatarSize
    Surface(
        onClick = onClick,
        modifier = Modifier.size(avatarSize),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(marquee.ringWidth, marquee.ring),
                shape = CircleShape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        ProfileAvatarCircle(
            name = profile.name,
            colorHex = profile.colorHex,
            size = avatarSize,
            art = profile.avatarArt,
            imageCrossfade = false
        )
    }
}

/**
 * Spec 52 T2 `.s52pill`: amber at rest, white with black content when focused. Spec 80 D5: given a
 * [pulse] release, the pill sends its rings out once (focus untouched) and reports [onPulsed] as
 * they start; the rings finish even though [pulse] then clears.
 */
@Composable
private fun TopBarUpdatePill(pulse: Int?, onPulsed: (Int) -> Unit, onClick: () -> Unit) {
    val bar = NuvioTheme.marquee.topBar
    // The release being pulsed: latched, so the pulse clearing (recorded) does not cut the rings;
    // a newer release that came meanwhile pulses after it, the same one never twice.
    var pulsing by remember { mutableStateOf<Int?>(null) }
    var pulsed by remember { mutableStateOf<Int?>(null) }
    var focused by remember { mutableStateOf(false) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(pulse, pulsing) {
        if (pulse != null && pulsing == null && pulse != pulsed) pulsing = pulse
    }
    LaunchedEffect(pulsing) {
        val release = pulsing ?: return@LaunchedEffect
        pulsed = release
        onPulsed(release)
        repeat(bar.updatePulseRings) { index ->
            if (index > 0) delay(bar.updatePulseGapMs.toLong())
            ring.snapTo(0f)
            ring.animateTo(1f, tween(bar.updatePulseRingMs, easing = EaseOut))
            ring.snapTo(0f)
        }
        pulsing = null
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(bar.updatePillHeight)
            .onFocusChanged { focused = it.isFocused }
            .updatePulseRing(progress = { ring.value }, brightenEdge = { !focused }),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bar.updatePillFill,
            contentColor = bar.updatePillText,
            focusedContainerColor = NuvioPrimitives.white,
            focusedContentColor = NuvioPrimitives.black,
            pressedContainerColor = NuvioPrimitives.white,
            pressedContentColor = NuvioPrimitives.black
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, bar.updatePillEdge), shape = CircleShape),
            focusedBorder = Border.None
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .height(bar.updatePillHeight)
                .padding(start = bar.updatePillPaddingStart, end = bar.updatePillPaddingEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(bar.updatePillIconSize)
            )
            Spacer(modifier = Modifier.width(bar.updatePillIconGap))
            Text(
                text = stringResource(R.string.update_label),
                style = NuvioTheme.textStyles.panelButtonSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * One `.s80pulse` ring at [progress] (0 = none): a pill-shaped band from the edge out to
 * `updatePulseSpread`, fading out, outside the pill only (the pill's fill is see-through), with the
 * resting edge brightened while [brightenEdge] (not focused: the focused pill has no edge).
 */
@Composable
private fun Modifier.updatePulseRing(progress: () -> Float, brightenEdge: () -> Boolean): Modifier {
    val bar = NuvioTheme.marquee.topBar
    val density = LocalDensity.current
    val spreadPx = with(density) { bar.updatePulseSpread.toPx() }
    val edgePx = with(density) { 1.dp.toPx() }
    return drawWithContent {
        val t = progress()
        if (t > 0f) {
            // A stroke centred half the spread outside the edge covers edge..edge+spread and nothing inside.
            val spread = spreadPx * t
            val half = spread / 2
            drawRoundRect(
                color = bar.updatePulseColor.copy(alpha = bar.updatePulseAlpha * (1f - t)),
                topLeft = Offset(-half, -half),
                size = Size(size.width + spread, size.height + spread),
                cornerRadius = CornerRadius(size.height / 2 + half),
                style = Stroke(width = spread)
            )
        }
        drawContent()
        if (t > 0f && brightenEdge()) {
            // Over the resting 1 dp edge: brighter toward updatePulseEdgeAlpha while the ring grows.
            val rest = bar.updatePillEdge.alpha
            val overlay = ((bar.updatePulseEdgeAlpha - rest) / (1f - rest)) * t
            drawRoundRect(
                color = bar.updatePulseColor.copy(alpha = overlay.coerceIn(0f, 1f)),
                topLeft = Offset(edgePx / 2, edgePx / 2),
                size = Size(size.width - edgePx, size.height - edgePx),
                cornerRadius = CornerRadius((size.height - edgePx) / 2),
                style = Stroke(width = edgePx)
            )
        }
    }
}

/** Wall clock in the device's 12/24 h format, ticking on the minute (top bar; profile picker, Spec 43). */
@Composable
internal fun TopBarClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            delay((MINUTE_MS - current % MINUTE_MS).coerceAtLeast(1_000L))
        }
    }
    Text(
        text = formatter.format(Date(nowMillis)),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.marquee.onImage2,
        maxLines = 1,
        modifier = modifier
    )
}

private const val MINUTE_MS = 60_000L

/**
 * NavHost plus its own composition-scoped ViewModelStore that makes teardown
 * deterministic on profile switch (G6 consolidation, 2026-08-30).
 *
 * Profile switching sets hasSelectedProfileThisSession = false, which removes
 * this whole subtree from composition. NavBackStackEntry ViewModelStores live in
 * NavControllerViewModel, scoped by default to the Activity - so nothing pops and
 * nothing clears. Measured 23 Jul 2026: HomeViewModel.onCleared() never fired
 * across 5 switches (0 CLEARED / 5 INIT) and catalogue loads grew as 1 + 2N,
 * because stale ViewModels kept collecting activeProfileId and installedAddons.
 * Owning the store in our own composition scope (instead of the Activity's
 * default) makes teardown deterministic.
 *
 * The store owner is created beside rememberNavController in MainActivity (crash
 * fix, 2026-09-17) and passed in: one owner per controller, at the controller's own
 * scope, so no scaffold-level recomposition can hand the surviving controller a fresh
 * store mid-flight (NavController.setViewModelStore rejects that on a non-empty backQueue).
 */
@Composable
private fun OwnedNavHostSection(
    navController: NavHostController,
    navViewModelStoreOwner: ViewModelStoreOwner,
    startDestination: String,
    topBarRoutes: Set<String>,
    topBarHasFocus: Boolean,
    contentFocusRequester: FocusRequester,
    onHideTopBar: (Boolean) -> Unit,
    onOpenAddons: () -> Unit,
    onOpenTmdbKeySettings: () -> Unit,
    settingsLocked: Boolean,
    onSwitchProfile: () -> Unit
) {
    CompositionLocalProvider(
        LocalTopBarFocused provides topBarHasFocus,
        LocalContentFocusRequester provides contentFocusRequester,
        LocalHideTopBar provides onHideTopBar,
        LocalViewModelStoreOwner provides navViewModelStoreOwner
    ) {
        NuvioNavHost(
            navController = navController,
            startDestination = startDestination,
            topBarRoutes = topBarRoutes,
            onOpenAddons = onOpenAddons,
            settingsLocked = settingsLocked,
            onOpenTmdbKeySettings = onOpenTmdbKeySettings,
            onSwitchProfile = onSwitchProfile
        )
    }
}

/** Clears Home's saved focus/scroll state; false when Home is not on the back stack yet. */
private fun requestHomeScrollToTop(navController: NavHostController): Boolean {
    val homeEntry = try {
        navController.getBackStackEntry(Screen.Home.route)
    } catch (_: IllegalArgumentException) {
        return false
    }
    ViewModelProvider(homeEntry)[HomeViewModel::class.java].requestScrollToTop()
    return true
}

/** A top-bar tab's navigation (also the welcome card's Review in Settings, MainActivity). */
internal fun navigateToTopBarRoute(navController: NavHostController, targetRoute: String) {
    navController.navigate(targetRoute) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
