package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.health.AddonHealthLevel
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.network.addonGuardKeyOf
import com.nuvio.tv.core.network.tls.addonServerLabel
import com.nuvio.tv.core.util.addonUrlMatchKey
import com.nuvio.tv.core.util.canonicalizeAddonUrl
import com.nuvio.tv.core.util.safeHost
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeQrPanel
import com.nuvio.tv.ui.screens.collection.CollectionBadge
import com.nuvio.tv.ui.screens.collection.CollectionFieldPill
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.collection.ServePendingFocus
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.delay


/** Longest addon name the rename panel takes. */
private const val ADDON_NAME_MAX = 40

/** Focus target of the install field (not an addon base URL). */
private const val FIELD_TARGET = "#field"

/**
 * The focused addon's background follows focus after this long (a quick D-pad pass loads nothing);
 * the first one shows at once.
 */
private const val BACKDROP_FOLLOW_MS = 150L

/**
 * Spec 31, Addons route (sandbox u11-settings-marquee v6, stages `addons`, `addon-remove`): breadcrumb,
 * Manage from phone + Refresh top-right, the install field, then one row per addon (logo, name +
 * version, provides · types, marks, health; the focused one shows its description and its
 * background fills the page). OK on a row opens its actions (Configure, Rename, Search: as you
 * type / on Enter, Move up / down, Disable, Remove; stages `addon-configure` and `addon-rename`
 * are their side panels; the search action is Spec 40, sandbox `u40-search-on-enter` stages 1-2).
 * Read-only profiles see the rows only. Spec 68 X7 (sandbox `s068-x7-tls-confirm` v1): a row whose
 * server's certificate was refused shows CERTIFICATE, PAUSED and a why line, with Review certificate
 * first in its strip; Install or Review on such a server opens [AddonCertificatePanel].
 */
@Composable
fun AddonManagerScreen(
    viewModel: AddonManagerViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val readOnly = viewModel.isReadOnly
    val addons = uiState.installedAddons
    val toast = LocalMarqueeToast.current
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val focusTargets = rememberCollectionFocusTargets()
    val phoneButton = remember { FocusRequester() }
    val installButton = remember { FocusRequester() }
    var addonPendingRemoval by remember { mutableStateOf<Addon?>(null) }
    var addonConfiguring by remember { mutableStateOf<Pair<Addon, String>?>(null) }
    var addonRenaming by remember { mutableStateOf<Addon?>(null) }
    /** Bumped when the panel opened by Install closes: focus goes back to the Install button. */
    var installFocusRequests by remember { mutableStateOf(0) }
    LaunchedEffect(installFocusRequests) {
        if (installFocusRequests > 0) installButton.requestFocusAfterFrames()
    }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var backdropUrl by remember { mutableStateOf<String?>(null) }
    var backdropShown by remember { mutableStateOf(false) }

    BackHandler { onBackPress() }

    // First focus once the list has loaded: the first addon, else the install field.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoading) {
        if (!entered && !uiState.isLoading) {
            entered = true
            focusTargets.pending = addons.firstOrNull()?.baseUrl ?: FIELD_TARGET
        }
    }
    focusTargets.ServePendingFocus(content = addons, fallback = FIELD_TARGET)

    LaunchedEffect(focusedKey, addons) {
        val target = addons.firstOrNull { it.baseUrl == focusedKey } ?: addons.firstOrNull()
        if (backdropShown) delay(BACKDROP_FOLLOW_MS)
        backdropUrl = target?.background?.takeIf { it.isNotBlank() }
        backdropShown = true
    }

    LaunchedEffect(uiState.transientMessage) {
        val message = uiState.transientMessage ?: return@LaunchedEffect
        toast?.show(message)
        viewModel.clearTransientMessage()
    }
    // Closing the phone QR hands focus back to its button (the overlay is its own window).
    var phoneOpen by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isQrModeActive) {
        if (uiState.isQrModeActive) {
            phoneOpen = true
        } else if (phoneOpen) {
            phoneOpen = false
            phoneButton.requestFocusAfterFrames()
        }
    }

    val refreshedMessage = stringResource(R.string.addon_refresh_done_subtitle)
    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        MarqueeBackdropImage(url = backdropUrl, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(tokens.veil))
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
                    text = stringResource(R.string.addon_title),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                if (!uiState.isLoading || addons.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(R.plurals.content_addons_installed, addons.size, addons.size),
                        style = NuvioTheme.textStyles.meta,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                    )
                }
            }
            if (readOnly) {
                Text(
                    text = stringResource(R.string.addon_readonly_notice),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            } else {
                // The URL field reads left to right in RTL locales too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    CollectionFieldPill(
                        icon = Icons.Default.Add,
                        value = uiState.installUrl,
                        onValueChange = viewModel::onInstallUrlChange,
                        placeholder = stringResource(R.string.addon_install_field_placeholder),
                        actionLabel = stringResource(
                            if (uiState.isInstalling) R.string.addon_installing else R.string.addon_install_btn
                        ),
                        onAction = { if (!uiState.isInstalling) viewModel.installAddon() },
                        actionFocus = installButton,
                        fieldFocus = focusTargets.requester(FIELD_TARGET),
                        keyboardType = KeyboardType.Uri
                    )
                }
            }
            Text(
                text = stringResource(R.string.addon_installed_section).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
            if (addons.isEmpty() && !uiState.isLoading) {
                Text(
                    text = stringResource(R.string.addon_empty),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            }
            addons.forEachIndexed { index, addon ->
                // Keyed so a moved row keeps its composition, and with it the focused action.
                key(addon.baseUrl) {
                    val health = uiState.healthByUrl[AddonHealthStore.addonKey(canonicalizeAddonUrl(addon.baseUrl))]
                    val searchOnEnter = addonUrlMatchKey(addon.baseUrl) in uiState.searchOnEnterKeys
                    val certificateFailed = addonGuardKeyOf(addon.baseUrl)?.let { it in uiState.certificateFailureKeys } == true
                    val facts = addonRowFacts(addon, health ?: AddonHealthLevel.UNKNOWN, searchOnEnter, certificateFailed)
                    AddonRow(
                        addon = addon,
                        facts = facts,
                        readOnly = readOnly,
                        position = index,
                        focusRequester = focusTargets.requester(addon.baseUrl),
                        onFocused = { focusedKey = addon.baseUrl },
                        actions = if (readOnly) {
                            null
                        } else {
                            {
                                AddonRowActions(
                                    addon = addon,
                                    isFirst = index == 0,
                                    isLast = index == addons.lastIndex,
                                    viewModel = viewModel,
                                    onReviewCertificate = if (facts.certificatePaused) {
                                        { viewModel.reviewCertificate(addon) }
                                    } else {
                                        null
                                    },
                                    onConfigure = facts.configureUrl?.let { url -> { addonConfiguring = addon to url } },
                                    onRename = { addonRenaming = addon },
                                    searchOnEnter = searchOnEnter.takeIf { facts.searchable },
                                    onRemove = { addonPendingRemoval = addon }
                                )
                            }
                        }
                    )
                }
            }
        }

        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_settings), stringResource(R.string.addon_title)))
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = tokens.headerTop, end = safeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.headerButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CollectionsHeaderButton(
                label = stringResource(R.string.addon_manage_from_phone_title),
                onClick = { viewModel.startQrMode() },
                modifier = Modifier.focusRequester(phoneButton)
            )
            CollectionsHeaderButton(
                label = stringResource(R.string.addon_header_refresh),
                onClick = {
                    viewModel.requestAddonSyncNow()
                    toast?.show(refreshedMessage)
                }
            )
        }

        PhoneManageHost(viewModel)
    }

    addonConfiguring?.let { (addon, url) ->
        // Closing hands focus back to the row (the panel is its own window).
        val close = {
            addonConfiguring = null
            focusTargets.pending = addon.baseUrl
        }
        MarqueeQrPanel(
            eyebrow = stringResource(R.string.addon_configure_eyebrow, addon.displayName),
            url = url,
            hostLabel = stringResource(R.string.addon_configure_host, url.safeHost()),
            body = stringResource(R.string.addon_configure_body, addon.displayName),
            onDismiss = close,
            actionLabel = stringResource(R.string.addon_configure_reload),
            // Closes first: the result toast draws in the main window, under the panel's veil.
            onAction = {
                close()
                viewModel.reloadAddon(addon)
            }
        )
    }

    addonRenaming?.let { addon ->
        val close = {
            addonRenaming = null
            focusTargets.pending = addon.baseUrl
        }
        RenamePanel(
            eyebrow = stringResource(R.string.addon_rename_eyebrow),
            current = addon.displayName,
            originalName = addon.name,
            resetLabel = stringResource(R.string.addon_rename_reset),
            note = stringResource(R.string.addon_rename_note),
            maxLength = ADDON_NAME_MAX,
            onSave = { name ->
                viewModel.renameAddon(addon, name)
                close()
            },
            onDismiss = close
        )
    }

    uiState.certificatePanel?.let { panel ->
        // Closing hands focus back to the reviewed row, or to Install (the panel is its own window).
        val close = { trust: Boolean ->
            viewModel.onCertificateDecision(trust)
            val returnKey = panel.addonUrl
            if (returnKey != null) focusTargets.pending = returnKey else installFocusRequests++
        }
        AddonCertificatePanel(
            state = panel,
            onTrust = { close(true) },
            onDismiss = { close(false) }
        )
    }

    addonPendingRemoval?.let { addon ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.addon_remove_eyebrow, addon.displayName),
            body = stringResource(R.string.addon_remove_body),
            confirmLabel = stringResource(R.string.addon_remove),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                focusTargets.pending = addonFocusKeyAfterRemoval(addons.map { it.baseUrl }, addon.baseUrl)
                    ?: FIELD_TARGET
                viewModel.removeAddon(addon.baseUrl)
                addonPendingRemoval = null
            },
            onDismiss = { addonPendingRemoval = null },
            destructive = true
        )
    }
}

/**
 * Stage 11 row: logo tile, name + version, "Streams · Movies, Series" with the marks, health at
 * the end; while focus is in the row its description shows and [actions] (if any) open under it
 * on OK. OK on a view-only row does nothing.
 */
@Composable
private fun AddonRow(
    addon: Addon,
    facts: AddonRowFacts,
    readOnly: Boolean,
    position: Int,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)?
) {
    val marquee = NuvioTheme.marquee
    StripListRow(
        focusRequester = focusRequester,
        position = position,
        onFocused = onFocused,
        dimmedAtRest = !addon.enabled,
        actions = actions
    ) { focusWithin ->
        AddonLogo(addon)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = addon.displayName,
                    style = NuvioTheme.textStyles.button,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                facts.version?.let {
                    Text(text = it, style = NuvioTheme.textStyles.caption, color = marquee.onImage3, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = addonProvidesLine(facts),
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                facts.marks.forEach { mark ->
                    CollectionBadge(
                        text = addonMarkLabel(mark),
                        color = when (mark) {
                            AddonMark.ADULT -> NuvioPrimitives.errorOnImage
                            AddonMark.CERTIFICATE -> NuvioPrimitives.warning
                            else -> marquee.onImage2
                        }
                    )
                }
            }
            val description = addon.description?.takeIf { it.isNotBlank() }
            if (facts.certificatePaused) {
                // Stage 3: the why line replaces the description, focused or not.
                val server = addonServerLabel(addon.baseUrl).orEmpty()
                Text(
                    text = stringResource(
                        // Spec 68 D11 Q8: a read-only profile has no Review action.
                        if (readOnly) R.string.addon_cert_row_line_read_only else R.string.addon_cert_row_line,
                        server
                    ),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)
                )
            } else if (focusWithin && description != null) {
                Text(
                    text = description,
                    style = NuvioTheme.textStyles.caption,
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (facts.certificatePaused) {
            Text(
                text = stringResource(R.string.addon_cert_paused).uppercase(),
                style = NuvioTheme.textStyles.techBadge,
                color = NuvioPrimitives.warning,
                maxLines = 1
            )
        } else {
            facts.health?.let { AddonHealthLabel(it) }
        }
    }
}

/**
 * Stage 11 strip (Q8): Review certificate first (Spec 68 X7, only when [onReviewCertificate] is set,
 * i.e. the row is paused on its certificate), Configure (only when [onConfigure] is set, i.e. the addon has a configure
 * page), Rename, Search: as you type / on Enter (Spec 40; only when [searchOnEnter] is set, i.e.
 * the addon has a search catalog; OK flips it in place), Move up / down (dimmed at the ends),
 * Disable / Enable, Remove (red).
 */
@Composable
private fun RowScope.AddonRowActions(
    addon: Addon,
    isFirst: Boolean,
    isLast: Boolean,
    viewModel: AddonManagerViewModel,
    onReviewCertificate: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
    onRename: () -> Unit,
    searchOnEnter: Boolean?,
    onRemove: () -> Unit
) {
    if (onReviewCertificate != null) {
        CollectionRowAction(label = stringResource(R.string.addon_action_review_certificate), onClick = onReviewCertificate)
    }
    if (onConfigure != null) {
        CollectionRowAction(label = stringResource(R.string.addon_action_configure), onClick = onConfigure)
    }
    CollectionRowAction(label = stringResource(R.string.addon_action_rename), onClick = onRename)
    if (searchOnEnter != null) {
        CollectionRowAction(
            label = stringResource(
                if (searchOnEnter) R.string.addon_action_search_on_enter else R.string.addon_action_search_as_you_type
            ),
            onClick = { viewModel.setSearchOnEnter(addon.baseUrl, !searchOnEnter) }
        )
    }
    CollectionRowAction(
        label = stringResource(R.string.cd_move_up),
        onClick = { viewModel.moveAddonUp(addon.baseUrl) },
        dimmed = isFirst
    )
    CollectionRowAction(
        label = stringResource(R.string.cd_move_down),
        onClick = { viewModel.moveAddonDown(addon.baseUrl) },
        dimmed = isLast
    )
    CollectionRowAction(
        label = stringResource(if (addon.enabled) R.string.addon_action_disable else R.string.addon_action_enable),
        onClick = { viewModel.setAddonEnabled(addon.baseUrl, !addon.enabled) }
    )
    CollectionRowAction(label = stringResource(R.string.addon_remove), onClick = onRemove, destructive = true)
}

/** The addon's logo fitted in a glass tile, else its initials. */
@Composable
private fun AddonLogo(addon: Addon) {
    val tokens = NuvioTheme.marquee.collections
    var failed by remember(addon.logo) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(tokens.thumbHeight)
            .clip(RoundedCornerShape(tokens.thumbRadius))
            .background(tokens.thumbFill),
        contentAlignment = Alignment.Center
    ) {
        val logo = addon.logo?.takeIf { it.isNotBlank() }
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xs)
            )
        } else {
            Text(
                text = addonInitials(addon.displayName),
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AddonHealthLabel(level: AddonHealthLevel) {
    val (label, color) = when (level) {
        AddonHealthLevel.HEALTHY -> R.string.addon_health_ok to NuvioTheme.marquee.readyText
        AddonHealthLevel.DEGRADED -> R.string.addon_health_slow to NuvioPrimitives.warning
        AddonHealthLevel.DOWN -> R.string.addon_health_down to NuvioPrimitives.errorOnImage
        // addonRowFacts maps "no samples yet" to no label.
        AddonHealthLevel.UNKNOWN -> return
    }
    Text(
        text = stringResource(label).uppercase(),
        style = NuvioTheme.textStyles.techBadge,
        color = color,
        maxLines = 1
    )
}

/** "Streams · Movies, Series": what it provides, then its types (known ones labelled). */
@Composable
private fun addonProvidesLine(facts: AddonRowFacts): String {
    val provides = facts.provides.map { kind ->
        stringResource(
            when (kind) {
                AddonProvides.METADATA -> R.string.addon_provides_metadata
                AddonProvides.CATALOGS -> R.string.addon_provides_catalogs
                AddonProvides.STREAMS -> R.string.addon_provides_streams
                AddonProvides.SUBTITLES -> R.string.addon_provides_subtitles
            }
        )
    }.joinToString(", ").replaceFirstChar { it.titlecase() }
    val types = facts.types.map { type ->
        when (type) {
            "movie" -> stringResource(R.string.type_movies)
            "series" -> stringResource(R.string.type_series)
            "anime" -> stringResource(R.string.addon_type_anime)
            "channel" -> stringResource(R.string.addon_type_channels)
            "tv" -> stringResource(R.string.addon_type_tv)
            else -> type
        }
    }.joinToString(", ")
    return listOf(provides, types).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
}

@Composable
private fun addonMarkLabel(mark: AddonMark): String = stringResource(
    when (mark) {
        AddonMark.CERTIFICATE -> R.string.addon_mark_certificate
        AddonMark.P2P -> R.string.addon_mark_p2p
        AddonMark.ADULT -> R.string.addon_mark_adult
        AddonMark.SETUP -> R.string.addon_mark_setup
        AddonMark.ON_ENTER -> R.string.addon_mark_on_enter
        AddonMark.DISABLED -> R.string.addons_badge_disabled
    }
)
