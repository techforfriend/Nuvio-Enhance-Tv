package com.nuvio.tv.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.update.DownloadState
import com.nuvio.tv.core.update.KnownRelease
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueePanelActions
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanelDialog
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.detail.formatShortDate
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** `.l8p` gap between the panel's parts. */
private val PANEL_GAP = 6.dp

/** What's new scrolls past this many lines. */
private const val NOTES_MAX_LINES = 8
private const val NOTES_SCROLL_STEP_PX = 100f

/** Where the releases live, as the About row and the panel name it: `github.com/<owner>/<repo>`. */
internal fun updateFeedAddress(): String = "github.com/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}"

/** The day the release was published, in [zone]; null when GitHub sent none or it does not parse. */
internal fun KnownRelease.publishedDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    publishedAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }

private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*?)(\s+#+)?$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
/** A link or an image, its URL allowed one level of parentheses. */
private val LINK = Regex("""!?\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)""")

/** Bold and code marks (not `__`, which identifiers carry). */
private val MARKUP = Regex("""\*\*|`""")
private val BLANK_RUNS = Regex("""\n{3,}""")

/**
 * The release body (Markdown, as GitHub stores it) as plain text for the panel: headings lose
 * their `#`, list items get a bullet, links and images keep their text, bold and code marks go;
 * nothing is rendered or followed (the body is untrusted text).
 */
internal fun releaseNotesText(notes: String): String =
    notes.lineSequence()
        .map { raw ->
            val line = raw.trimEnd()
            HEADING.matchEntire(line)?.let { return@map it.groupValues[1] }
            BULLET.matchEntire(line)?.let { return@map "${it.groupValues[1]}\u2022 ${it.groupValues[2]}" }
            line
        }
        .joinToString("\n")
        .replace(LINK, "\$1")
        .replace(MARKUP, "")
        .replace(BLANK_RUNS, "\n\n")
        .trim()

/** "Nuvio AK 0.9.0-ak51" (the panel title), or with its build, "Nuvio AK 0.9.0-ak51 (1392)" (the About row). */
@Composable
internal fun releaseName(release: KnownRelease, withBuild: Boolean): String = stringResource(
    R.string.update_release_name,
    if (withBuild) stringResource(R.string.update_release_build, release.versionName, release.versionCode) else release.versionName
)

/**
 * Spec 52 T2 update panel (sandbox `spec-52-public-build-ui`, stages t2-panel and t2-downloading):
 * the release's name, date, size and the APK picked for this TV; What's new as plain text,
 * scrolling past eight lines (a focus stop only then); where the full notes are and what Android
 * will ask. Download and install ([onDownload]) and Not now ([onDismiss]); while [download] runs,
 * its progress and Cancel ([onCancel]: the panel stays); after a failure, the reason and Try again.
 * Running with no total yet is the fresh check before the download: 0 of the release's size. Back
 * calls [onDismiss].
 */
@Composable
internal fun UpdateSidePanel(
    release: KnownRelease,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val context = LocalContext.current
    val actionFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val running = download as? DownloadState.Running
    val failed = download == DownloadState.Failed
    val notes = remember(release.notes) { releaseNotesText(release.notes) }
    val meta = listOfNotNull(
        release.publishedDate()?.let { stringResource(R.string.update_panel_released, formatShortDate(it)) },
        Formatter.formatShortFileSize(context, release.sizeBytes),
        stringResource(R.string.update_panel_abi, release.abi)
    ).joinToString(" \u00b7 ")
    MarqueeSidePanelDialog(
        onDismissRequest = onDismiss,
        width = marquee.collections.sidePanelWidth,
        insetVertical = marquee.collections.sidePanelInset,
        initialFocus = if (running != null) cancelFocus else actionFocus
    ) {
        PanelEyebrow(text = stringResource(R.string.update_label))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = marquee.settings.panelTextInset),
            verticalArrangement = Arrangement.spacedBy(PANEL_GAP)
        ) {
            Text(
                text = releaseName(release, withBuild = false),
                style = NuvioTheme.textStyles.panelStateTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = NuvioPrimitives.white
            )
            Text(text = meta, style = NuvioTheme.textStyles.caption, color = marquee.onImage3)
            if (notes.isNotEmpty()) {
                UpdatePanelSection(stringResource(R.string.update_whats_new), top = 4.dp)
                UpdateNotes(notes)
            }
            if (running != null) {
                UpdatePanelSection(stringResource(R.string.update_downloading), top = 10.dp)
                UpdateProgress(running, release.sizeBytes)
            } else {
                UpdatePanelNote(stringResource(R.string.update_full_notes, updateFeedAddress()), top = 4.dp)
                UpdatePanelNote(stringResource(R.string.update_install_note))
            }
        }
        if (failed) SettingsPanelErrorLine(stringResource(R.string.update_failed))
        MarqueePanelActions {
            if (running != null) {
                CollectionsHeaderButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus)
                )
            } else {
                MarqueePrimaryButton(
                    label = stringResource(if (failed) R.string.update_try_again else R.string.update_download_install),
                    icon = if (failed) null else Icons.Filled.FileDownload,
                    onClick = onDownload,
                    focusRequester = actionFocus,
                    compact = true
                )
                CollectionsHeaderButton(label = stringResource(R.string.update_not_now), onClick = onDismiss)
            }
        }
    }
}

/** `.s52sec`: an uppercase section label, [top] above it on top of the panel gap. */
@Composable
private fun UpdatePanelSection(text: String, top: Dp) {
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow,
        color = NuvioTheme.marquee.onImage3,
        modifier = Modifier.padding(top = top)
    )
}

/** `.l8p .note`. */
@Composable
private fun UpdatePanelNote(text: String, top: Dp = 0.dp) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(top = top)
    )
}

/**
 * `.s52notes`: the notes at most [NOTES_MAX_LINES] lines tall; past that, Up / Down scroll them and
 * the box is a focus stop (P2pConsentDialog's body pattern).
 */
@Composable
private fun UpdateNotes(text: String) {
    val style = NuvioTheme.textStyles.settingsNote.copy(fontSize = 12.5.sp)
    val maxHeight = with(LocalDensity.current) { (style.lineHeight * NOTES_MAX_LINES).toDp() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(scrollState)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionDown -> NOTES_SCROLL_STEP_PX.takeIf { scrollState.canScrollForward }
                    Key.DirectionUp -> (-NOTES_SCROLL_STEP_PX).takeIf { scrollState.canScrollBackward }
                    else -> null
                } ?: return@onPreviewKeyEvent false
                scope.launch { scrollState.animateScrollBy(delta) }
                true
            }
            .focusable(enabled = scrollState.maxValue > 0)
    ) {
        Text(text = text, style = style, color = NuvioPrimitives.white.copy(alpha = 0.85f))
    }
}

/** The download's size: the fresh check before it (no total yet) counts against the release's own. */
internal fun progressTotal(running: DownloadState.Running, releaseSize: Long): Long =
    running.totalBytes.takeIf { it > 0L } ?: releaseSize

/** How much of [total] has arrived, 0 to 1. */
internal fun progressFraction(bytes: Long, total: Long): Float =
    (bytes.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f)

/** `.s52prog` + `.s52pct`: a 4 dp bar, then "18 MB of 41 MB" and the percentage. */
@Composable
private fun UpdateProgress(running: DownloadState.Running, releaseSize: Long) {
    val context = LocalContext.current
    val total = progressTotal(running, releaseSize)
    val fraction = progressFraction(running.bytes, total)
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(NuvioPrimitives.white.copy(alpha = 0.18f), shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(NuvioPrimitives.white, shape)
        )
    }
    // Tabular figures, so the numbers do not jitter as they count.
    val style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(
                R.string.update_progress,
                Formatter.formatShortFileSize(context, running.bytes),
                Formatter.formatShortFileSize(context, total)
            ),
            style = style,
            color = NuvioTheme.marquee.onImage2
        )
        Text(
            text = stringResource(R.string.update_percent, (fraction * 100).toInt()),
            style = style,
            color = NuvioTheme.marquee.onImage2
        )
    }
}
