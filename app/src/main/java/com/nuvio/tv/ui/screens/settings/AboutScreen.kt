package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.update.UpdateState
import com.nuvio.tv.core.update.toChangelogRelease
import com.nuvio.tv.core.update.noticeShown
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.formRowSurface
import com.nuvio.tv.ui.screens.detail.formatDayMonth
import com.nuvio.tv.ui.screens.tip.TipEntryDialog
import com.nuvio.tv.ui.screens.tip.TipEntryState
import com.nuvio.tv.ui.screens.tip.TipViewModel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Row key of About's first Updates row, which Settings' deep entry focuses ([SettingsEntry.UPDATE]). */
internal const val ABOUT_UPDATE_ROW_KEY = "about_update"

/**
 * About (Spec 31 mockup v6, stage 22): wordmark, edition, the fork credit, the upstream credit,
 * the TMDB attribution and version. The upstream line doubles as the GPL-3.0 section 5(a) notice
 * that this is a modified version. The TMDB row is what the TMDB API terms s3 ask for (Spec 52
 * D3): an approved logo, smaller than the app's wordmark, and the notice; private builds carry it
 * too.
 *
 * A build with the update check (Spec 52 T2, sandbox `spec-52-public-build-ui` stages t2-*) moves
 * the version into an Updates section under the credits: Check for updates (OK checks now; the
 * line under it says when the last check answered, or that it could not reach GitHub), which
 * turns into Update available while a newer release is known (OK opens [UpdateSidePanel]), and
 * Check automatically. [initialFocusRequester] goes on the first row; [openUpdatePanel]
 * (Settings' deep entry [SettingsEntry.UPDATE], once that row has focus) opens the panel, and
 * [onUpdatePanelOpened] clears it. Home (ON_STOP, SettingsScreen) closes the panel, which stops
 * a download; the panel's changelog row opens the update's page (Spec 80 B1) and the panel shows
 * again on Back. Spec 80: a Changelog row ends Updates (alone under it in a build without the
 * check, D13); OK swaps the pane to the changelog list ([ChangelogList]), Back returns to the row.
 * Spec 81 T3 (sandbox spec-81-donate v3, stage a-about): a Support section ends the pane, one Tip
 * the developer row that opens the tip card, while a tip link is set (D4; a locked profile never
 * reaches Settings).
 */
@Composable
fun AboutSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    openUpdatePanel: Boolean = false,
    onUpdatePanelOpened: () -> Unit = {},
    viewModel: UpdateViewModel = hiltViewModel(),
    changelog: ChangelogViewModel = hiltViewModel(),
    tipViewModel: TipViewModel = hiltViewModel()
) {
    val tipLink = tipViewModel.link.collectAsStateWithLifecycle().value
    val tip = remember { TipEntryState() }
    val update = viewModel.state.collectAsStateWithLifecycle().value
    val panelRelease = viewModel.panelRelease.collectAsStateWithLifecycle().value
    val changelogOpen = changelog.listOpen.collectAsStateWithLifecycle().value
    val changelogList = changelog.list.collectAsStateWithLifecycle().value
    val changelogRow = rememberSettingsReturnTarget(ABOUT_CHANGELOG_ROW_KEY)
    var backToChangelogRow by remember { mutableStateOf(false) }
    LaunchedEffect(changelogOpen) {
        if (!changelogOpen && backToChangelogRow) {
            backToChangelogRow = false
            changelogRow.requester.requestFocusAfterFrames()
        }
    }

    LaunchedEffect(openUpdatePanel) {
        if (openUpdatePanel) {
            viewModel.openPanel()
            onUpdatePanelOpened()
        }
    }

    if (changelogOpen) {
        ChangelogList(
            state = changelogList,
            focusOn = changelog.lastOpened,
            initialFocusRequester = initialFocusRequester,
            onRetry = changelog::retry,
            onOpenRelease = changelog::openPage,
            onBack = {
                backToChangelogRow = true
                changelog.closeList()
            }
        )
    } else Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsPaneHeader(
                title = stringResource(R.string.about_title),
                subtitle = stringResource(
                    if (update.supported) R.string.about_version_updates_credits else R.string.about_version_credits
                )
            )
            AboutCredits(showVersion = !update.supported)
        }
        if (update.supported) {
            // `.l11ab.s52c` bottom padding; the section label adds its own 10 above.
            Spacer(modifier = Modifier.height(4.dp))
            AboutUpdates(
                state = update,
                initialFocusRequester = initialFocusRequester,
                onCheck = viewModel::checkNow,
                onOpenPanel = viewModel::openPanel,
                onAutoCheckChange = viewModel::setAutoCheck,
                changelogRow = changelogRow,
                onOpenChangelog = changelog::openList
            )
        } else {
            // Spec 80 D13: a build without the update check still has the changelog, alone under Updates.
            Spacer(modifier = Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.settings.rowGap)) {
                SettingsSectionLabel(text = stringResource(R.string.update_section))
                AboutChangelogRow(
                    onOpen = changelog::openList,
                    modifier = Modifier
                        .optionalFocusRequester(changelogRow.requester)
                        .optionalFocusRequester(initialFocusRequester)
                )
            }
        }
        if (tipLink != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.settings.rowGap)) {
                SettingsSectionLabel(text = stringResource(R.string.tip_section))
                AboutTipRow(onOpen = { tip.open(tipLink) }, modifier = Modifier.focusRequester(tip.focus))
            }
        }
    }
    // The Changelog row, just above Support, is in every build's pane.
    TipEntryDialog(tip, fallback = changelogRow.requester)

    if (panelRelease != null) {
        UpdateSidePanel(
            release = panelRelease,
            download = update.download,
            onDownload = viewModel::download,
            onInstallReady = viewModel::installReady,
            onShown = viewModel::setPanelShown,
            onOpenChangelog = { changelog.openPage(panelRelease.toChangelogRelease(), forUpdate = true) },
            onCancel = viewModel::cancelDownload,
            onDismiss = viewModel::closePanel
        )
    }
}

/**
 * `.l8r` + `.s81lead`: the heart before Tip the developer and its line (both, so not
 * [MarqueeFormRow]'s in-label icon), the chevron at the end; lit like any form row.
 */
@Composable
private fun AboutTipRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val row = marquee.formRow
    val tip = marquee.tip
    var lit by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = marquee.settings.rowMinHeight)
            .onFocusChanged { lit = it.hasFocus }
            .formRowSurface(lit)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(horizontal = row.paddingHorizontal, vertical = row.paddingVertical),
        horizontalArrangement = Arrangement.spacedBy(row.innerGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = if (lit) tip.heartOnFocus else tip.heart,
            modifier = Modifier.padding(end = tip.rowHeartEnd).size(tip.rowHeart)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tip_row_title),
                style = NuvioTheme.textStyles.button,
                color = NuvioPrimitives.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.tip_row_subtitle),
                style = NuvioTheme.textStyles.panelHelper,
                color = marquee.onImage3
            )
        }
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

@Composable
private fun AboutCredits(showVersion: Boolean) {
    Column(
        modifier = Modifier.padding(start = 2.dp, top = NuvioTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        BrandWordmark(
            modifier = Modifier.height(30.dp),
            contentDescription = stringResource(R.string.cd_nuvio_logo)
        )
        Text(
            text = stringResource(R.string.about_edition),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp
            ),
            color = NuvioTheme.colors.TextTertiary
        )
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.about_fork_credit),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(R.string.about_upstream_credit),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.widthIn(max = 520.dp)
        )
        // 11 dp above the TMDB row as drawn (mockup: 3 px gap + 8 px margin): 3 + 5 + 3.
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.tmdb_alt_short_blue),
                contentDescription = stringResource(R.string.cd_tmdb_logo),
                modifier = Modifier.height(11.dp)
            )
            Text(
                text = stringResource(R.string.about_tmdb_notice),
                style = NuvioTheme.textStyles.profileLine,
                color = NuvioTheme.colors.TextTertiary
            )
        }
        if (showVersion) {
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = installedVersion(),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun installedVersion(): String =
    stringResource(R.string.about_version_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

/**
 * `.l11sec` Updates, then the first row and Check automatically. The first row is ONE row whose
 * content follows [state] (Check for updates / Update available), so focus stays on it when a
 * check finds a release.
 */
@Composable
private fun AboutUpdates(
    state: UpdateState,
    initialFocusRequester: FocusRequester?,
    onCheck: () -> Unit,
    onOpenPanel: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    changelogRow: SettingsReturnRow,
    onOpenChangelog: () -> Unit
) {
    val tokens = NuvioTheme.marquee.settings
    val available = state.available
    val status = updateStatus(state)
    val statusLine = updateStatusLine(status, state)
    Column(verticalArrangement = Arrangement.spacedBy(tokens.rowGap)) {
        SettingsSectionLabel(text = stringResource(R.string.update_section))
        val returnRow = rememberSettingsReturnTarget(ABOUT_UPDATE_ROW_KEY)
        MarqueeFormRow(
            label = stringResource(if (available != null) R.string.update_available else R.string.update_check_title),
            modifier = Modifier
                .optionalFocusRequester(returnRow.requester)
                .optionalFocusRequester(initialFocusRequester),
            onClick = if (available != null) onOpenPanel else onCheck,
            minHeight = tokens.rowMinHeight,
            helperContent = {
                Text(
                    text = statusLine,
                    style = NuvioTheme.textStyles.panelHelper,
                    color = if (status.isFailure) NuvioPrimitives.errorOnImage else NuvioTheme.marquee.onImage3
                )
            },
            labelBadge = if (state.noticeShown) {
                { UpdateNewBadge() }
            } else {
                null
            }
        ) { lit ->
            MarqueeFormRowValue(
                text = if (available != null) {
                    stringResource(R.string.update_you_have, BuildConfig.VERSION_NAME)
                } else {
                    installedVersion()
                },
                dimmed = false,
                lit = lit
            )
            if (available != null) MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
        }
        SettingsSwitchRow(
            title = stringResource(R.string.update_auto_title),
            subtitle = stringResource(R.string.update_auto_subtitle, updateFeedAddress()),
            checked = state.autoCheck,
            onCheckedChange = onAutoCheckChange
        )
        AboutChangelogRow(onOpen = onOpenChangelog, modifier = Modifier.optionalFocusRequester(changelogRow.requester))
    }
}

/** Which line the first Updates row shows, in this precedence (the line's text: [updateStatusLine]). */
internal enum class UpdateStatus { RELEASE, CHECKING, FAILED_AFTER_OK, FAILED, UP_TO_DATE, NEVER_CHECKED }

internal fun updateStatus(state: UpdateState): UpdateStatus = when {
    state.available != null -> UpdateStatus.RELEASE
    state.checking -> UpdateStatus.CHECKING
    state.lastCheckFailed && state.lastOkCheckMs > 0L -> UpdateStatus.FAILED_AFTER_OK
    state.lastCheckFailed -> UpdateStatus.FAILED
    state.lastOkCheckMs > 0L -> UpdateStatus.UP_TO_DATE
    else -> UpdateStatus.NEVER_CHECKED
}

/** A failure line is red. */
internal val UpdateStatus.isFailure: Boolean
    get() = this == UpdateStatus.FAILED || this == UpdateStatus.FAILED_AFTER_OK

/**
 * The first row's line for [status]: the known release ("Nuvio AK 0.9.0-ak51 (1392) · released 3 Nov ·
 * 41 MB"), Checking…, or the last check (failed: with when the last one answered).
 */
@Composable
private fun updateStatusLine(status: UpdateStatus, state: UpdateState): String = when (status) {
    UpdateStatus.RELEASE -> state.available?.let { available ->
        listOfNotNull(
            releaseName(available, withBuild = true),
            available.publishedDate()?.let { stringResource(R.string.update_released_on, formatDayMonth(it)) },
            Formatter.formatShortFileSize(LocalContext.current, available.sizeBytes)
        ).joinToString(" \u00b7 ")
    }.orEmpty()
    UpdateStatus.CHECKING -> stringResource(R.string.update_checking)
    UpdateStatus.FAILED_AFTER_OK -> stringResource(R.string.update_check_failed_last, checkedWhen(state.lastOkCheckMs))
    UpdateStatus.FAILED -> stringResource(R.string.update_check_failed)
    UpdateStatus.UP_TO_DATE -> stringResource(R.string.update_up_to_date, checkedWhen(state.lastOkCheckMs))
    UpdateStatus.NEVER_CHECKED -> stringResource(R.string.update_never_checked)
}

internal enum class CheckedDay { TODAY, YESTERDAY, OTHER }

/** The day [epochMs] falls on in [zone], against [today] (a day after today, a clock set back, is OTHER). */
internal fun checkedDay(epochMs: Long, today: LocalDate, zone: ZoneId): CheckedDay =
    when (Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()) {
        today -> CheckedDay.TODAY
        today.minusDays(1) -> CheckedDay.YESTERDAY
        else -> CheckedDay.OTHER
    }

/**
 * "today at 09:12", "yesterday at 09:12" or "31 Oct at 09:12": the device's zone, and its 12/24 h
 * setting as the top-bar clock reads it.
 */
@Composable
private fun checkedWhen(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(epochMs))
    return when (checkedDay(epochMs, LocalDate.now(zone), zone)) {
        CheckedDay.TODAY -> stringResource(R.string.update_when_today, time)
        CheckedDay.YESTERDAY -> stringResource(R.string.update_when_yesterday, time)
        CheckedDay.OTHER -> stringResource(
            R.string.update_when_date,
            formatDayMonth(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()),
            time
        )
    }
}

/** `.s52new`: NEW in black on amber, 8 dp after the label (also the changelog's rows). */
@Composable
internal fun UpdateNewBadge() {
    Text(
        text = stringResource(R.string.update_new_badge),
        style = NuvioTheme.textStyles.techBadge.copy(lineHeight = 14.sp, letterSpacing = 1.sp),
        color = NuvioPrimitives.black,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(NuvioPrimitives.warning, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
