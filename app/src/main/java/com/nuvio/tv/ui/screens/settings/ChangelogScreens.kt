package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.update.ChangelogRelease
import com.nuvio.tv.core.update.ReleaseVideo
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowIcon
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.screens.detail.formatShortDate
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.launch

/** Row key of About's Changelog row: focus returns to it from the list. */
internal const val ABOUT_CHANGELOG_ROW_KEY = "about_changelog"

// Release page layout (sandbox spec-80-release-changelog, stage a-release: `.s80body`, `.s80left`, `.s80vid`).
private val PAGE_COLUMN_GAP = 28.dp
private val PAGE_LEFT_WIDTH = 340.dp
private val VIDEO_HEIGHT = 191.dp
private val VIDEO_RADIUS = 14.dp
private val VIDEO_PLAY_SIZE = 52.dp
private val BOX_RADIUS = 12.dp
private val SINGLE_COLUMN_MAX = 640.dp
private const val NOTES_SCROLL_STEP_PX = 120f

/** "Nuvio KE 1.0.0" for a release with an APK, else GitHub's own name for it. */
@Composable
internal fun changelogTitle(release: ChangelogRelease): String =
    release.versionName?.let { stringResource(R.string.update_release_name, it) } ?: release.title

/** NEW: newer than this build (the update check's own rule: versionCode). */
internal fun ChangelogRelease.isNewer(installedCode: Int = BuildConfig.VERSION_CODE): Boolean =
    versionCode != null && versionCode > installedCode

internal fun ChangelogRelease.isInstalled(installedCode: Int = BuildConfig.VERSION_CODE): Boolean =
    versionCode == installedCode

/** The first line of [whatsNew] as plain text (list rows), empty without one. */
internal fun firstWhatsNewLine(whatsNew: String): String =
    releaseNotesText(whatsNew).lineSequence().map { it.removePrefix("\u2022").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** Spec 80 A1 `.l8r`: About › Updates › Changelog. */
@Composable
internal fun AboutChangelogRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    MarqueeFormRow(
        label = stringResource(R.string.changelog_title),
        helper = stringResource(R.string.changelog_row_subtitle),
        onClick = onOpen,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight,
        modifier = modifier
    ) { _ ->
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/**
 * Spec 80 A2 / A5 in About's pane: the header, then skeleton rows while [state] loads, the
 * failure line with Try again, or one row per release (NEW, Video, Installed). [focusOn] is the
 * release whose row takes focus once loaded (the one whose page was open), else the first row;
 * [initialFocusRequester] (the rail's way into the pane) rides on that same first focus. Back,
 * while focus is in the list, calls [onBack].
 */
@Composable
internal fun ChangelogList(
    state: ChangelogListState,
    focusOn: ChangelogRelease?,
    initialFocusRequester: FocusRequester?,
    onRetry: () -> Unit,
    onOpenRelease: (ChangelogRelease) -> Unit,
    onBack: () -> Unit
) {
    val tokens = NuvioTheme.marquee.settings
    val holder = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // Only from inside the list: with focus on the rail or the top bar, Back is theirs.
    BackHandler(enabled = hasFocus, onBack = onBack)
    val holding = state == ChangelogListState.Loading || (state as? ChangelogListState.Loaded)?.releases?.isEmpty() == true
    // The holder keeps focus until a row or Try again takes it: dropping it first would leave focus nowhere.
    var holderFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
    ) {
        SettingsPaneHeader(
            title = stringResource(R.string.changelog_title),
            subtitle = stringResource(R.string.changelog_list_subtitle, updateFeedAddress())
        )
        // Holds focus in the pane while nothing else here can take it (loading), so Back still works.
        Spacer(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(holder)
                .then(if (holding) Modifier.optionalFocusRequester(initialFocusRequester) else Modifier)
                .onFocusChanged { holderFocused = it.isFocused }
                .focusProperties { canFocus = holding || holderFocused }
                .focusable()
        )
        when (state) {
            ChangelogListState.Loading -> {
                LaunchedEffect(Unit) { holder.requestFocusAfterFrames() }
                repeat(SKELETON_ROWS) { ChangelogSkeletonRow() }
            }
            ChangelogListState.Failed -> {
                LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
                Spacer(modifier = Modifier.height(6.dp))
                SettingsPanelErrorLine(stringResource(R.string.changelog_failed))
                Spacer(modifier = Modifier.height(8.dp))
                MarqueeGlassTextButton(
                    label = stringResource(R.string.update_try_again),
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(first).optionalFocusRequester(initialFocusRequester)
                )
            }
            is ChangelogListState.Loaded -> {
                val target = state.releases.indexOf(focusOn).coerceAtLeast(0)
                LaunchedEffect(state) {
                    if (state.releases.isNotEmpty()) first.requestFocusAfterFrames() else holder.requestFocusAfterFrames()
                }
                if (state.releases.isEmpty()) {
                    SettingsNote(text = stringResource(R.string.changelog_empty))
                }
                state.releases.forEachIndexed { index, release ->
                    ChangelogListRow(
                        release = release,
                        onClick = { onOpenRelease(release) },
                        modifier = if (index == target) {
                            Modifier.focusRequester(first).optionalFocusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

private const val SKELETON_ROWS = 3

@Composable
private fun ChangelogSkeletonRow() {
    val tokens = NuvioTheme.marquee.settings
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(tokens.rowMinHeight)
            .background(NuvioPrimitives.white.copy(alpha = 0.04f), RoundedCornerShape(BOX_RADIUS))
    )
}

/** `.l8r` release row: title with NEW / Video / Installed, the date and What's new's first line under it. */
@Composable
private fun ChangelogListRow(release: ChangelogRelease, onClick: () -> Unit, modifier: Modifier) {
    val date = releaseDate(release.publishedAt)?.let(::formatShortDate)
    val helper = listOfNotNull(date, firstWhatsNewLine(release.whatsNew).takeIf { it.isNotEmpty() }).joinToString(" \u00b7 ")
    val newer = release.isNewer()
    val installed = release.isInstalled()
    MarqueeFormRow(
        label = changelogTitle(release),
        helper = helper.takeIf { it.isNotEmpty() },
        onClick = onClick,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight,
        modifier = modifier,
        labelBadge = if (newer || installed || release.video != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (newer) UpdateNewBadge()
                    if (installed) ChangelogChip(stringResource(R.string.changelog_installed), installed = true)
                    if (release.video != null) ChangelogChip(stringResource(R.string.changelog_video_tag), installed = false)
                }
            }
        } else {
            null
        }
    ) { lit ->
        if (newer) MarqueeFormRowValue(stringResource(R.string.changelog_not_installed), dimmed = false, lit = lit)
        MarqueeFormRowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}

/** `.s80chip`: Video (white 10 %) or Installed (green), 8 dp after the label. */
@Composable
private fun ChangelogChip(text: String, installed: Boolean) {
    val marquee = NuvioTheme.marquee
    Text(
        text = text,
        style = NuvioTheme.textStyles.techBadge.copy(lineHeight = 14.sp, letterSpacing = 0.3.sp),
        color = if (installed) marquee.readyText else marquee.onImage2,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(
                if (installed) marquee.readyText.copy(alpha = 0.16f) else NuvioPrimitives.white.copy(alpha = 0.1f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Spec 80 A3 / A4: one release over the whole Settings screen (the top bar stays). With a video,
 * the video tile (first focus; OK = [onPlayVideo]) and its notes on the left, What's new and the
 * scrolling full notes on the right; without one, the notes alone at reading width. A body
 * without a What's new section shows as one Notes block. [updateCard] (the update's download card,
 * Spec 80 B2/B3) replaces the two notes under the tile, or tops the notes without a video. Back
 * calls [onBack]. [anchor] (the top
 * bar's Down target) goes on the first focus.
 */
@Composable
internal fun ChangelogReleasePage(
    release: ChangelogRelease,
    onPlayVideo: (ReleaseVideo, String) -> Unit,
    onBack: () -> Unit,
    anchor: FocusRequester,
    updateCard: (@Composable () -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.settings
    val title = changelogTitle(release)
    val videoTitle = stringResource(R.string.changelog_video_title, release.versionName ?: release.title)
    val notesFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(release) {
        (if (release.video != null) videoFocus else notesFocus).requestFocusAfterFrames()
    }
    val meta = listOfNotNull(
        releaseDate(release.publishedAt)?.let { stringResource(R.string.update_panel_released, formatShortDate(it)) },
        stringResource(R.string.changelog_installed_here).takeIf { release.isInstalled() }
    ).joinToString(" \u00b7 ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = NuvioTheme.layout.tvSafeHorizontal,
                end = NuvioTheme.layout.tvSafeHorizontal,
                top = marquee.topBar.contentTop + tokens.topInset
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.changelog_breadcrumb).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3
            )
            SettingsPaneHeader(title = title, subtitle = meta.takeIf { it.isNotEmpty() })
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(PAGE_COLUMN_GAP)
        ) {
            val video = release.video
            if (video != null) {
                Column(modifier = Modifier.width(PAGE_LEFT_WIDTH), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChangelogVideoTile(
                        video = video,
                        title = videoTitle,
                        onClick = { onPlayVideo(video, videoTitle) },
                        modifier = Modifier.focusRequester(videoFocus).focusRequester(anchor)
                    )
                    if (updateCard != null) {
                        updateCard()
                    } else {
                        ChangelogPageNote(stringResource(R.string.changelog_video_note))
                        ChangelogPageNote(stringResource(R.string.changelog_also_at, updateFeedAddress()))
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = video != null)
                    .then(if (video == null) Modifier.widthIn(max = SINGLE_COLUMN_MAX) else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val parsedWhatsNew = remember(release) { releaseNotesText(release.whatsNew) }
                val parsedNotes = remember(release) { releaseNotesText(release.notes) }
                // A body that is only a What's new section shows it in the scrolling box: the page always
                // keeps that focus stop (and the bar's Down target).
                val whatsNew = parsedWhatsNew.takeIf { parsedNotes.isNotEmpty() }.orEmpty()
                val notes = parsedNotes.ifEmpty { parsedWhatsNew }
                if (video == null && updateCard != null) updateCard()
                if (whatsNew.isNotEmpty()) {
                    SettingsSectionLabel(text = stringResource(R.string.update_whats_new))
                    Text(
                        text = whatsNew,
                        style = NuvioTheme.textStyles.settingsNote.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = NuvioPrimitives.white,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NuvioPrimitives.white.copy(alpha = 0.04f), RoundedCornerShape(BOX_RADIUS))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                // With a video and no text at all, the tile is the page's focus stop: no empty box.
                if (video == null || notes.isNotEmpty()) {
                    SettingsSectionLabel(
                        text = stringResource(
                            when {
                                whatsNew.isNotEmpty() -> R.string.changelog_full_notes
                                parsedNotes.isEmpty() && parsedWhatsNew.isNotEmpty() -> R.string.update_whats_new
                                else -> R.string.changelog_notes
                            }
                        )
                    )
                    ChangelogNotes(
                        text = notes.ifEmpty { stringResource(R.string.changelog_no_notes) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(notesFocus)
                            .then(if (video == null) Modifier.focusRequester(anchor) else Modifier)
                    )
                    Text(
                        text = stringResource(R.string.changelog_notes_hint),
                        style = NuvioTheme.textStyles.caption,
                        color = marquee.onImage3,
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangelogPageNote(text: String) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.settingsNote,
        color = NuvioTheme.marquee.onImage2,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

/**
 * `.s80vid`: the release poster (when the release carries one) or a plain tile, a play disc and the
 * title; focused = white ring, a little larger, the disc white.
 */
@Composable
private fun ChangelogVideoTile(video: ReleaseVideo, title: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(VIDEO_RADIUS)
    val playLabel = stringResource(R.string.cd_changelog_play_video)
    Box(
        modifier = modifier
            .width(PAGE_LEFT_WIDTH)
            .height(VIDEO_HEIGHT)
            .scale(if (focused) 1.03f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = playLabel }
            .clip(shape)
            .background(NuvioPrimitives.neutral900)
            .clickable(onClick = onClick)
    ) {
        if (video.posterUrl != null) {
            AsyncImage(
                model = video.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = NuvioPrimitives.white.copy(alpha = 0.08f),
                modifier = Modifier.size(120.dp).align(Alignment.Center)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.35f to NuvioPrimitives.black.copy(alpha = 0.05f), 1f to NuvioPrimitives.black.copy(alpha = 0.82f)))
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(VIDEO_PLAY_SIZE)
                .background(if (focused) NuvioPrimitives.white else NuvioPrimitives.black.copy(alpha = 0.55f), CircleShape)
                .then(if (focused) Modifier else Modifier.border(1.5.dp, NuvioPrimitives.white.copy(alpha = 0.5f), CircleShape)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (focused) NuvioPrimitives.black else NuvioPrimitives.white,
                modifier = Modifier.size(28.dp)
            )
        }
        // `.s80vid.focus`: the white ring over the image and play disc.
        if (focused) Box(modifier = Modifier.fillMaxSize().border(3.dp, NuvioPrimitives.white, shape))
        Text(
            text = title,
            style = NuvioTheme.textStyles.settingsNote.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 11.dp)
        )
    }
}

/**
 * `.s80notes`: the notes filling the space they are given; Up / Down scroll them while they can
 * (then focus moves on), a white ring while focused.
 */
@Composable
private fun ChangelogNotes(text: String, modifier: Modifier) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BOX_RADIUS)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) NuvioPrimitives.white.copy(alpha = 0.9f) else NuvioPrimitives.white.copy(alpha = 0f), shape)
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
            .focusable()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = text,
            style = NuvioTheme.textStyles.settingsNote.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = NuvioPrimitives.white.copy(alpha = 0.85f)
        )
    }
}
