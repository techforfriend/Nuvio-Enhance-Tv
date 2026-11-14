@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeTechBadge
import com.nuvio.tv.core.player.AspectMode
import com.nuvio.tv.core.player.PlaybackChapter
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.components.marquee.drawFillGlow
import com.nuvio.tv.ui.components.rememberRawSvgPainter
import com.nuvio.tv.ui.theme.NuvioMarqueeOsdTokens
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatPlaybackClock
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import com.nuvio.tv.core.player.PlayerEvent
import com.nuvio.tv.core.player.PlayerScrubRates
import com.nuvio.tv.core.player.PlayerUiState
import com.nuvio.tv.core.player.PostPlayMode

// OSD show/hide: fade 200 + transport row rise 16 dp (Marquee spec §Motion).
private const val OSD_FADE_MS = 200
private val ROW_RISE = 16.dp
private const val TOP_BAND_FADE_MS = 150

// Playhead glow (spec: ambient accent, blur 14 + spread 2, 75%), the same stand-in, wider; also
// the subtitle delay strip's knob.
internal val THUMB_GLOW_LAYERS = listOf(2.dp to 0.30f, 4.dp to 0.20f, 7.dp to 0.10f)
private const val TIMES_ALPHA = 0.90f

/**
 * Player OSD in Marquee (wave U3b; sandbox `u3-player-osd-marquee` v5, Marquee spec §Player OSD).
 * Top band: title + episode line (start), tech badges centred, clock + "Ends at" (end). Bottom
 * block: More tray (when open), seek bar, elapsed/remaining, one centred transport row with
 * Previous / Next pinned to its ends (Spec 45). Focus: play/pause by default (PlayerScreen requests
 * it); Left from the first centre button reaches Previous, Right from More reaches Next; Up from
 * the row goes to the seek bar; Down from it hides the controls; the bar's Up goes to the tray while it is open, else to
 * [seekBarUpFocusRequester] (skip pill, else post-play card), else stays; the tray takes focus on
 * open (on the [trayOpenOn] pill after Back from the Speed or Chapters panel, Spec 25 Q4), and Down
 * there calls [onCloseTray] (Back closes it in PlayerScreen's handler). [accent]:
 * the backdrop's ambient accent (seek glow, play/pause glow, subtitle dot).
 */
@Composable
internal fun PlayerOsd(
    visible: Boolean,
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    accent: () -> Color,
    playPauseFocusRequester: FocusRequester,
    seekBarFocusRequester: FocusRequester,
    moreFocusRequester: FocusRequester,
    episodesButtonFocusRequester: FocusRequester,
    sourcesButtonFocusRequester: FocusRequester,
    seekBarUpFocusRequester: FocusRequester?,
    trayOpenOn: TrayPill?,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onShowSubtitles: () -> Unit,
    onShowAudio: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowSources: () -> Unit,
    onToggleTray: () -> Unit,
    onCloseTray: () -> Unit,
    onStartOver: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowChapters: () -> Unit,
    onToggleAspect: () -> Unit,
    onToggleStats: () -> Unit,
    onOpenExternalPlayer: () -> Unit,
    onSwitchEngine: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onPlayPreviousEpisode: () -> Unit,
    onInteraction: () -> Unit,
    onHideControls: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(OSD_FADE_MS)),
        exit = fadeOut(tween(OSD_FADE_MS))
    ) {
        val risePx = with(LocalDensity.current) { ROW_RISE.roundToPx() }
        val rowRise = Modifier.animateEnterExit(
            enter = slideInVertically(tween(OSD_FADE_MS)) { risePx },
            exit = slideOutVertically(tween(OSD_FADE_MS)) { risePx }
        )
        // Spec 62 H2d: the whole OSD (scrims, top band, seek bar, transport, More tray) dims as one.
        val osdDim = rememberPlayerControlsDim(uiState.playerControlsBrightness)
        Box(modifier = Modifier.fillMaxSize().then(osdDim)) {
            OsdScrims()
            OsdTopBand(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopStart)
            )
            // Seek bar, times and transport stay LTR in every locale (as built).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OsdBottomBlock(
                    uiState = uiState,
                    viewModel = viewModel,
                    accent = accent,
                    rowModifier = rowRise,
                    playPauseFocusRequester = playPauseFocusRequester,
                    seekBarFocusRequester = seekBarFocusRequester,
                    moreFocusRequester = moreFocusRequester,
                    episodesButtonFocusRequester = episodesButtonFocusRequester,
                    sourcesButtonFocusRequester = sourcesButtonFocusRequester,
                    seekBarUpFocusRequester = seekBarUpFocusRequester,
                    trayOpenOn = trayOpenOn,
                    onPlayPause = onPlayPause,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onShowSubtitles = onShowSubtitles,
                    onShowAudio = onShowAudio,
                    onShowEpisodes = onShowEpisodes,
                    onShowSources = onShowSources,
                    onToggleTray = onToggleTray,
                    onCloseTray = onCloseTray,
                    onStartOver = onStartOver,
                    onShowSpeed = onShowSpeed,
                    onShowChapters = onShowChapters,
                    onToggleAspect = onToggleAspect,
                    onToggleStats = onToggleStats,
                    onOpenExternalPlayer = onOpenExternalPlayer,
                    onSwitchEngine = onSwitchEngine,
                    onPlayNextEpisode = onPlayNextEpisode,
                    onPlayPreviousEpisode = onPlayPreviousEpisode,
                    onInteraction = onInteraction,
                    onHideControls = onHideControls,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun OsdScrims() {
    val osd = NuvioTheme.marquee.osd
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(osd.scrimTopHeight)
                .background(Brush.verticalGradient(listOf(osd.scrimTopStart, Color.Transparent)))
        )
        // The bottom stops start part-way down: draw only from there (no full-screen transparent
        // overdraw), re-basing the stops onto that box.
        val start = osd.scrimBottomStops.first().first
        val stops = remember(osd.scrimBottomStops) {
            osd.scrimBottomStops.map { (fraction, alpha) ->
                ((fraction - start) / (1f - start)) to NuvioPrimitives.black.copy(alpha = alpha)
            }.toTypedArray()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(1f - start)
                .background(Brush.verticalGradient(colorStops = stops))
        )
    }
}

/**
 * Title block at the start, badges centred, clock at the end. A long title ellipsizes
 * `titleEndGap` before the badges or the clock. With the stats panel open (top start) the title
 * and badges hide (they would sit under it; it shows the same facts); the clock stays.
 */
@Composable
private fun OsdTopBand(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val osd = NuvioTheme.marquee.osd
    val statsOpen = uiState.showPlaybackStatsOverlay
    val showBadges = uiState.osdTechBadges.isNotEmpty() && !statsOpen
    val showClock = uiState.osdClockEnabled && !uiState.showDisplayModeInfo
    val safe = NuvioTheme.layout.tvSafeHorizontal
    Layout(
        contents = listOf(
            {
                AnimatedVisibility(
                    visible = !statsOpen,
                    enter = fadeIn(tween(TOP_BAND_FADE_MS)),
                    exit = fadeOut(tween(TOP_BAND_FADE_MS))
                ) {
                    OsdTitleBlock(uiState)
                }
            },
            {
                AnimatedVisibility(
                    visible = showBadges,
                    enter = fadeIn(tween(TOP_BAND_FADE_MS)),
                    exit = fadeOut(tween(TOP_BAND_FADE_MS))
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(osd.badgeGap)) {
                        uiState.osdTechBadges.forEach { MarqueeTechBadge(text = it.text, tick = it.matched, warn = it.degraded) }
                    }
                }
            },
            {
                AnimatedVisibility(
                    visible = showClock,
                    enter = fadeIn(tween(TOP_BAND_FADE_MS)),
                    exit = fadeOut(tween(TOP_BAND_FADE_MS))
                ) {
                    OsdClockHost(viewModel = viewModel, playbackSpeed = uiState.playbackSpeed)
                }
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = osd.topInset)
    ) { (titleSlot, badgeSlot, clockSlot), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val safePx = safe.roundToPx()
        val gapPx = osd.titleEndGap.roundToPx()
        // A hidden AnimatedVisibility may emit nothing or a 0x0 node: treat both as absent.
        val badges = badgeSlot.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
        val clock = clockSlot.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
        var titleEnd = width - safePx
        if (badges != null) titleEnd = min(titleEnd, (width - badges.width) / 2 - gapPx)
        if (clock != null) titleEnd = min(titleEnd, width - safePx - clock.width - gapPx)
        val title = titleSlot.firstOrNull()?.measure(loose.copy(maxWidth = max(titleEnd - safePx, 0)))
        val badgesY = (osd.badgesTop - osd.topInset).roundToPx()
        val height = maxOf(title?.height ?: 0, clock?.height ?: 0, badges?.let { badgesY + it.height } ?: 0)
        layout(width, height) {
            title?.placeRelative(safePx, 0)
            badges?.placeRelative((width - badges.width) / 2, badgesY)
            clock?.placeRelative(width - safePx - clock.width, 0)
        }
    }
}

/** Text title (Q3): series name + "S1 E3 · Episode", or the movie title + its year. */
@Composable
private fun OsdTitleBlock(uiState: PlayerUiState) {
    val marquee = NuvioTheme.marquee
    val season = uiState.currentSeason
    val episode = uiState.currentEpisode
    val title: String
    val line: String?
    if (season != null && episode != null) {
        title = uiState.contentName ?: uiState.title
        val code = stringResource(R.string.season_episode_format, season, episode)
        val context = LocalContext.current
        val episodeTitle = uiState.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
        line = if (episodeTitle != null) "$code \u00b7 $episodeTitle" else code
    } else {
        title = uiState.title
        line = uiState.releaseYear?.takeIf { it.isNotBlank() }
    }
    Column {
        Text(
            text = title,
            style = NuvioTheme.textStyles.osdTitle,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (line != null) {
            Text(
                text = line,
                style = NuvioTheme.textStyles.meta,
                color = marquee.onImage2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The clock + "Ends at" (OSD top-end; the pause overlay's too). It reads the playback timeline,
 * so it lives in its own scope (1 Hz recomposition).
 */
@Composable
internal fun OsdClockHost(viewModel: PlayerViewModel, playbackSpeed: Float) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            delay((1_000L - (current % 1_000L)).coerceAtLeast(250L))
        }
    }

    val effectiveSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val remainingMediaMs = (playbackTimeline.duration - playbackTimeline.currentPosition).coerceAtLeast(0L)
    val remainingMs = ceil(remainingMediaMs.toDouble() / effectiveSpeed.toDouble()).toLong()
    val endTimeText = if (playbackTimeline.duration > 0L) timeFormatter.format(Date(nowMs + remainingMs)) else "--:--"

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = timeFormatter.format(Date(nowMs)),
            style = NuvioTheme.textStyles.osdTitle,
            color = NuvioPrimitives.white,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.player_ends_at, endTimeText),
            style = NuvioTheme.textStyles.caption,
            color = NuvioTheme.marquee.onImage2,
            maxLines = 1
        )
    }
}

@Composable
private fun OsdBottomBlock(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    accent: () -> Color,
    rowModifier: Modifier,
    playPauseFocusRequester: FocusRequester,
    seekBarFocusRequester: FocusRequester,
    moreFocusRequester: FocusRequester,
    episodesButtonFocusRequester: FocusRequester,
    sourcesButtonFocusRequester: FocusRequester,
    seekBarUpFocusRequester: FocusRequester?,
    trayOpenOn: TrayPill?,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onShowSubtitles: () -> Unit,
    onShowAudio: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowSources: () -> Unit,
    onToggleTray: () -> Unit,
    onCloseTray: () -> Unit,
    onStartOver: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowChapters: () -> Unit,
    onToggleAspect: () -> Unit,
    onToggleStats: () -> Unit,
    onOpenExternalPlayer: () -> Unit,
    onSwitchEngine: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onPlayPreviousEpisode: () -> Unit,
    onInteraction: () -> Unit,
    onHideControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    val osd = NuvioTheme.marquee.osd
    val safe = NuvioTheme.layout.tvSafeHorizontal
    val trayOpen = uiState.showMoreDialog
    val trayFirstFocusRequester = remember { FocusRequester() }
    val traySpeedFocusRequester = remember { FocusRequester() }
    val hasChaptersPill = uiState.chapters.isNotEmpty()
    val trayChaptersFocusRequester = remember { FocusRequester() }
    val hasSubtitleControl = uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()
    val subtitleOn = uiState.selectedSubtitleTrackIndex >= 0 || uiState.selectedAddonSubtitle != null
    val hasAudioControl = uiState.audioTracks.isNotEmpty()
    val hasEpisodeContext = uiState.currentSeason != null && uiState.currentEpisode != null
    // As built: hidden while the post-play card counts down or searches.
    val autoPlay = uiState.postPlayMode as? PostPlayMode.AutoPlay
    val showNextEpisode = uiState.nextEpisode?.hasAired == true &&
        (autoPlay == null || (!autoPlay.searching && autoPlay.countdownSec == null))
    // Spec 45: hidden once playback has ended (the end prompt / natural completion own that state;
    // a Previous search would hold it under the sources panel) and while a Next search, countdown
    // or still-watching prompt blocks (the controller refuses the press then). Stays shown during
    // its own search, so focus is not lost; a second press is ignored.
    val showPreviousEpisode = uiState.previousEpisode?.hasAired == true &&
        !uiState.playbackEnded &&
        uiState.postPlayMode?.blocksNaturalCompletion() != true
    val previousFocusRequester = remember { FocusRequester() }
    val nextFocusRequester = remember { FocusRequester() }
    // An edge button that leaves while focused (S1 E1 reached, a Next search starting, natural end)
    // hands focus to play/pause. Decided here, before the node detaches, since detaching may
    // report the focus loss first.
    var previousFocused by remember { mutableStateOf(false) }
    var nextFocused by remember { mutableStateOf(false) }
    if ((previousFocused && !showPreviousEpisode) || (nextFocused && !showNextEpisode)) {
        SideEffect {
            previousFocused = false
            nextFocused = false
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }
    // Keyed on the open state, not on the tray entering composition: reopening during the exit
    // fade reverses the AnimatedVisibility without recomposing the tray.
    LaunchedEffect(trayOpen) {
        if (trayOpen) {
            when (trayOpenOn) {
                TrayPill.SPEED -> traySpeedFocusRequester
                TrayPill.CHAPTERS -> if (hasChaptersPill) trayChaptersFocusRequester else trayFirstFocusRequester
                null -> trayFirstFocusRequester
            }.requestFocusAfterFrames()
        }
    }

    // Row and edge buttons: Up to the seek bar, Down hides the controls.
    val rowFocus = Modifier
        .focusProperties { up = seekBarFocusRequester }
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                onHideControls()
                true
            } else {
                false
            }
        }
        .onFocusChanged { if (it.isFocused) onInteraction() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = osd.bottomInset)
    ) {
        AnimatedVisibility(
            visible = trayOpen,
            enter = fadeIn(tween(NuvioTheme.motion.durations.fast)),
            exit = fadeOut(tween(NuvioTheme.motion.durations.fast))
        ) {
            Column {
                OsdTray(
                    uiState = uiState,
                    viewModel = viewModel,
                    hasChaptersPill = hasChaptersPill,
                    accent = accent,
                    firstFocusRequester = trayFirstFocusRequester,
                    speedFocusRequester = traySpeedFocusRequester,
                    chaptersFocusRequester = trayChaptersFocusRequester,
                    onStartOver = onStartOver,
                    onShowSpeed = onShowSpeed,
                    onShowChapters = onShowChapters,
                    onToggleAspect = onToggleAspect,
                    onToggleStats = onToggleStats,
                    onOpenExternalPlayer = onOpenExternalPlayer,
                    onSwitchEngine = onSwitchEngine,
                    onCloseTray = onCloseTray
                )
                Spacer(modifier = Modifier.height(osd.trayToBar))
            }
        }
        OsdSeekBarHost(
            viewModel = viewModel,
            chapters = uiState.chapters,
            previewOrigin = uiState.pendingPreviewSeekPosition?.let { uiState.previewSeekOriginMs },
            focusRequester = seekBarFocusRequester,
            upFocusRequester = if (trayOpen) trayFirstFocusRequester else seekBarUpFocusRequester,
            downFocusRequester = playPauseFocusRequester,
            onFocused = onInteraction,
            glow = accent,
            modifier = Modifier.padding(horizontal = safe)
        )
        Spacer(modifier = Modifier.height(osd.barToTimes))
        OsdTimesHost(viewModel = viewModel, modifier = Modifier.padding(horizontal = safe))
        Spacer(modifier = Modifier.height(osd.timesToRow))
        // Left from the first centre button (Subtitles, else Audio, else -10 s) reaches Previous;
        // Right from More reaches Next.
        val toPrevious = if (showPreviousEpisode) {
            Modifier.focusProperties { left = previousFocusRequester }
        } else {
            Modifier
        }
        val toNext = if (showNextEpisode) Modifier.focusProperties { right = nextFocusRequester } else Modifier
        // 0 Subtitles, 1 Audio, 2 -10 s: whichever leads the row.
        val firstCentre = when {
            hasSubtitleControl -> 0
            hasAudioControl -> 1
            else -> 2
        }
        val centreFocus = { index: Int -> if (index == firstCentre) rowFocus.then(toPrevious) else rowFocus }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(osd.playPauseSize)
                .then(rowModifier)
        ) {
            if (showPreviousEpisode) {
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.SkipPrevious),
                    contentDescription = stringResource(R.string.player_previous_episode),
                    onClick = onPlayPreviousEpisode,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = safe)
                        .focusRequester(previousFocusRequester)
                        .onFocusChanged { previousFocused = it.isFocused }
                        .then(rowFocus)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(osd.rowGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSubtitleControl) {
                    OsdIconButton(
                        painter = rememberVectorPainter(Icons.Filled.ClosedCaption),
                        contentDescription = stringResource(R.string.cd_subtitles),
                        onClick = onShowSubtitles,
                        modifier = centreFocus(0),
                        dot = if (subtitleOn) accent else null
                    )
                }
                if (hasAudioControl) {
                    OsdIconButton(
                        painter = rememberVectorPainter(Icons.AutoMirrored.Filled.VolumeUp),
                        contentDescription = stringResource(R.string.cd_audio_tracks),
                        onClick = onShowAudio,
                        modifier = centreFocus(1)
                    )
                }
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.Replay10),
                    contentDescription = stringResource(R.string.cd_seek_back_10),
                    onClick = onSeekBack,
                    modifier = centreFocus(2)
                )
                OsdPlayPauseButton(
                    isPlaying = uiState.isPlaying,
                    onClick = onPlayPause,
                    glow = accent,
                    modifier = Modifier
                        .focusRequester(playPauseFocusRequester)
                        .then(rowFocus)
                )
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.Forward10),
                    contentDescription = stringResource(R.string.cd_seek_forward_10),
                    onClick = onSeekForward,
                    modifier = rowFocus
                )
                if (hasEpisodeContext) {
                    OsdIconButton(
                        painter = rememberRawSvgPainter(R.raw.ic_player_episodes, osd.iconSize),
                        contentDescription = stringResource(R.string.player_pill_episodes),
                        onClick = onShowEpisodes,
                        modifier = Modifier
                            .focusRequester(episodesButtonFocusRequester)
                            .then(rowFocus)
                    )
                }
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.Cloud),
                    contentDescription = stringResource(R.string.cd_sources),
                    onClick = onShowSources,
                    modifier = Modifier
                        .focusRequester(sourcesButtonFocusRequester)
                        .then(rowFocus)
                )
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.MoreHoriz),
                    contentDescription = stringResource(R.string.cd_more_actions),
                    onClick = onToggleTray,
                    modifier = Modifier
                        .focusRequester(moreFocusRequester)
                        .then(rowFocus)
                        .then(toNext),
                    containerColor = if (trayOpen) osd.moreOpenFill else Color.Transparent
                )
            }
            if (showNextEpisode) {
                OsdIconButton(
                    painter = rememberVectorPainter(Icons.Filled.SkipNext),
                    contentDescription = stringResource(R.string.player_next_episode),
                    onClick = onPlayNextEpisode,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = safe)
                        .focusRequester(nextFocusRequester)
                        .onFocusChanged { nextFocused = it.isFocused }
                        .then(rowFocus)
                )
            }
        }
    }
}

/**
 * More tray (F3 stages 3-4): icon-only pills centred above the seek bar (Chapters second, only when
 * the source has chapters); the focused one shows its label (and Chapters' / Speed's / Aspect's
 * value), and a collapsed Speed / Aspect off its default carries the accent dot. OsdBottomBlock
 * requests the first, Speed or Chapters pill when it opens. Down closes it back to More; Up stays.
 */
@Composable
private fun OsdTray(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    hasChaptersPill: Boolean,
    accent: () -> Color,
    firstFocusRequester: FocusRequester,
    speedFocusRequester: FocusRequester,
    chaptersFocusRequester: FocusRequester,
    onStartOver: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowChapters: () -> Unit,
    onToggleAspect: () -> Unit,
    onToggleStats: () -> Unit,
    onOpenExternalPlayer: () -> Unit,
    onSwitchEngine: () -> Unit,
    onCloseTray: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onCloseTray()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.osd.trayIconGap, Alignment.CenterHorizontally)
    ) {
        OsdTrayPill(
            label = stringResource(R.string.player_pill_restart),
            painter = rememberVectorPainter(Icons.Filled.Replay),
            onClick = onStartOver,
            modifier = Modifier.focusRequester(firstFocusRequester)
        )
        if (hasChaptersPill) {
            OsdChaptersTrayPill(
                chapters = uiState.chapters,
                viewModel = viewModel,
                onClick = onShowChapters,
                modifier = Modifier.focusRequester(chaptersFocusRequester)
            )
        }
        OsdTrayPill(
            label = stringResource(R.string.player_tray_speed),
            painter = rememberVectorPainter(Icons.Filled.Speed),
            onClick = onShowSpeed,
            value = stringResource(R.string.player_speed_value, uiState.playbackSpeed.toString()),
            dot = accent.takeIf { speedValueMarked(uiState.playbackSpeed) },
            modifier = Modifier.focusRequester(speedFocusRequester)
        )
        OsdTrayPill(
            label = stringResource(R.string.player_tray_aspect),
            painter = rememberVectorPainter(Icons.Filled.AspectRatio),
            onClick = onToggleAspect,
            value = stringResource(uiState.aspectMode.labelResId),
            dot = accent.takeIf { aspectValueMarked(uiState.aspectMode) }
        )
        OsdTrayPill(
            label = stringResource(R.string.player_tray_stats),
            painter = rememberVectorPainter(Icons.Filled.Info),
            onClick = onToggleStats
        )
        OsdTrayPill(
            label = stringResource(R.string.player_tray_external_player),
            painter = rememberVectorPainter(Icons.AutoMirrored.Filled.OpenInNew),
            onClick = onOpenExternalPlayer
        )
        OsdTrayPill(
            label = stringResource(R.string.player_tray_switch_engine),
            painter = rememberVectorPainter(Icons.Filled.SwapHoriz),
            onClick = onSwitchEngine,
            // The engine in use (P11); AUTO is resolved at playback start.
            value = when (uiState.internalPlayerEngine) {
                InternalPlayerEngine.MVP_PLAYER -> stringResource(R.string.playback_engine_mvplayer)
                else -> stringResource(R.string.playback_engine_exoplayer)
            }
        )
    }
}

/** The More tray pill a panel's Back returns to (Spec 25 Q4). */
internal enum class TrayPill { SPEED, CHAPTERS }

/**
 * The Chapters pill, its value "7 / 11" = the chapter playing now. Reads the playback timeline
 * here (only while the tray is open); the value recomposes only when the chapter changes.
 */
@Composable
private fun OsdChaptersTrayPill(
    chapters: List<PlaybackChapter>,
    viewModel: PlayerViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeline = viewModel.playbackTimeline.collectAsState()
    val current by remember(chapters) {
        derivedStateOf { currentChapterIndex(chapters, timeline.value.currentPosition) }
    }
    OsdTrayPill(
        label = stringResource(R.string.player_tray_chapters),
        painter = rememberVectorPainter(Icons.AutoMirrored.Filled.List),
        onClick = onClick,
        value = if (current >= 0) {
            stringResource(R.string.player_chapter_position, current + 1, chapters.size)
        } else {
            null
        },
        modifier = modifier
    )
}

/** Speed off Normal: the collapsed Speed pill carries the accent dot (F3 Q6). */
internal fun speedValueMarked(speed: Float): Boolean = speed != 1f

/** Aspect off Fit: the collapsed Aspect pill carries the accent dot (F3 Q6). */
internal fun aspectValueMarked(mode: AspectMode): Boolean = mode != AspectMode.ORIGINAL

/**
 * Tray pill (F3 stages 3-4): a `pillHeight` glass circle with the icon (and [dot], the accent dot,
 * when given); focused, it widens to icon + label + [value] on white (dot hidden). The row
 * re-centres as the width changes.
 */
@Composable
private fun OsdTrayPill(
    label: String,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    dot: (() -> Color)? = null
) {
    val osd = NuvioTheme.marquee.osd
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(osd.pillHeight)
            .then(if (focused) Modifier else Modifier.width(osd.pillHeight))
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = osdGlassPillColors(),
        contentPadding = if (focused) {
            PaddingValues(start = osd.trayPillPaddingStart, end = osd.pillPadding)
        } else {
            PaddingValues()
        }
    ) {
        if (focused) {
            Icon(painter = painter, contentDescription = null, modifier = Modifier.size(osd.trayIconSize))
            Spacer(modifier = Modifier.width(osd.pillIconGap))
            Text(text = label, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
            if (value != null) {
                Spacer(modifier = Modifier.width(osd.pillIconGap))
                Text(
                    text = value,
                    style = NuvioTheme.textStyles.caption,
                    color = black.copy(alpha = SUFFIX_ALPHA),
                    maxLines = 1
                )
            }
        } else {
            Box(modifier = Modifier.size(osd.pillHeight), contentAlignment = Alignment.Center) {
                Icon(painter = painter, contentDescription = label, modifier = Modifier.size(osd.trayIconSize))
                if (dot != null) OsdAccentDot(dot)
            }
        }
    }
}

@Composable
private fun OsdSeekBarHost(
    viewModel: PlayerViewModel,
    chapters: List<PlaybackChapter>,
    previewOrigin: Long?,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    glow: () -> Color,
    modifier: Modifier = Modifier
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    ProgressBar(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration,
        chapters = chapters,
        previewOrigin = previewOrigin,
        onSeekPreview = { delta -> viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(delta)) },
        onSeekCommit = { viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek) },
        modifier = modifier,
        focusRequester = focusRequester,
        upFocusRequester = upFocusRequester,
        downFocusRequester = downFocusRequester,
        onFocused = onFocused,
        bufferedPosition = playbackTimeline.bufferedPosition,
        glow = glow
    )
}

@Composable
private fun OsdTimesHost(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val remainingMs = (playbackTimeline.duration - playbackTimeline.currentPosition).coerceAtLeast(0L)
    val style = NuvioTheme.textStyles.osdTime
    val color = NuvioPrimitives.white.copy(alpha = TIMES_ALPHA)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = formatPlaybackClock(playbackTimeline.currentPosition), style = style, color = color)
        Text(text = "-" + formatPlaybackClock(remainingMs), style = style, color = color)
    }
}

/**
 * Hidden-controls mini seek (Marquee stage 5): over a `miniScrimHeight` bottom scrim, the
 * `seekHeight` bar (played fill glowing in [glow], no playhead) and "current / duration" above its
 * end, cut at [chapters] as the OSD bar. PlayerScreen shows it during a hidden-controls seek (as built).
 */
@Composable
internal fun OsdMiniSeek(
    viewModel: PlayerViewModel,
    chapters: List<PlaybackChapter>,
    glow: () -> Color,
    modifier: Modifier = Modifier
) {
    val osd = NuvioTheme.marquee.osd
    val safe = NuvioTheme.layout.tvSafeHorizontal
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val density = LocalDensity.current
    val textStyles = NuvioTheme.textStyles
    // cardLabel size (13/18) with the OSD's tabular figures and the spec's text shadow.
    val labelStyle = remember(textStyles, density) {
        textStyles.cardLabel.copy(
            fontFeatureSettings = textStyles.osdTime.fontFeatureSettings,
            shadow = with(density) {
                Shadow(NuvioPrimitives.black.copy(alpha = 0.8f), Offset(0f, 1.dp.toPx()), 6.dp.toPx())
            }
        )
    }
    val scrim = remember(osd.miniScrimStops) {
        Brush.verticalGradient(
            colorStops = osd.miniScrimStops
                .map { (fraction, alpha) -> fraction to NuvioPrimitives.black.copy(alpha = alpha) }
                .toTypedArray()
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(osd.miniScrimHeight)
            .background(scrim)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = "${formatPlaybackClock(playbackTimeline.currentPosition)} / " +
                    formatPlaybackClock(playbackTimeline.duration),
                style = labelStyle,
                color = NuvioPrimitives.white.copy(alpha = TIMES_ALPHA),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = safe, bottom = osd.miniBarBottom + osd.seekHeight + osd.miniLabelGap)
            )
            ProgressBar(
                currentPosition = playbackTimeline.currentPosition,
                duration = playbackTimeline.duration,
                chapters = chapters,
                onSeekPreview = {},
                onSeekCommit = {},
                // The touch box centres the track: its bottom sits half the difference lower.
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = safe,
                        end = safe,
                        bottom = osd.miniBarBottom - (osd.seekTouchHeight - osd.seekHeight) / 2
                    ),
                bufferedPosition = playbackTimeline.bufferedPosition,
                glow = glow
            )
        }
    }
}

/**
 * Seek bar: a `seekTouchHeight` focus box drawing a `seekHeight` track (`seekFocusedHeight` when
 * focused), buffered and played fills, the played fill's [glow] when given, and the focused
 * playhead (ringed, glowing in [glow]) at [currentPosition], which is the preview while one is
 * pending. [previewOrigin] (the playing position when that preview began) stops the played fill
 * and starts the `seekGhost` span to the playhead. Left/Right accumulate a preview
 * (`PlayerScrubRates` ramp) committed on release; Up goes to [upFocusRequester] or stays; Down
 * goes to [downFocusRequester]. Everything is drawn: a position tick recomposes only this small
 * scope (no relayout), the focus height animates in draw only. Also the mini seek's bar.
 * [chapters] (F3): a `chapterGap` gap at each start inside the timeline, cut through track,
 * buffered, ghost and played fills; the glow and playhead stay whole. No marks draws no clip.
 */
@Composable
private fun ProgressBar(
    currentPosition: Long,
    duration: Long,
    chapters: List<PlaybackChapter>,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    modifier: Modifier = Modifier,
    previewOrigin: Long? = null,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    /** Position (ms) up to which content is buffered. Pass 0 to skip the overlay. */
    bufferedPosition: Long = 0L,
    glow: (() -> Color)? = null
) {
    val osd = NuvioTheme.marquee.osd
    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedProgress = if (duration > 0 && bufferedPosition > currentPosition) {
        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val originProgress = if (previewOrigin != null && duration > 0) {
        (previewOrigin.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val animatedProgress = animateFloatAsState(progress, tween(100), label = "progress")
    val animatedBuffered = animateFloatAsState(bufferedProgress, tween(200), label = "bufferedProgress")
    val chapterGaps = remember(chapters, duration) {
        chapterMarkFractions(chapters, duration).takeIf { it.isNotEmpty() }?.let(::ChapterGaps)
    }
    var isFocused by remember { mutableStateOf(false) }
    val trackHeight = animateDpAsState(
        targetValue = if (isFocused) osd.seekFocusedHeight else osd.seekHeight,
        animationSpec = tween(160),
        label = "trackHeight"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(osd.seekTouchHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekCommit()
                            true
                        }
                        else -> false
                    }
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (downFocusRequester != null) {
                            runCatching { downFocusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // The top band is display-only: no target means stay.
                        upFocusRequester?.let { runCatching { it.requestFocus() } }
                        true
                    }
                    // Seek F5a: the shared hold ramp, as the hidden-controls D-pad path.
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeekPreview(
                            PlayerScrubRates.deltaMsForHold(
                                holdDurationMs = native.eventTime - native.downTime,
                                forward = native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            )
                        )
                        true
                    }
                    else -> false
                }
            }
            .drawBehind {
                val height = trackHeight.value.toPx()
                val top = (size.height - height) / 2f
                val head = size.width * animatedProgress.value
                val origin = originProgress?.let { size.width * it }
                // A backward preview: the fill ends at the playhead, the ghost shows what it rewinds.
                val played = if (origin != null) min(origin, head) else head
                val glowColor = glow?.invoke()
                if (glowColor != null && played > 0f) {
                    drawFillGlow(width = played, top = top, height = height, color = glowColor, alpha = osd.seekGlowAlpha)
                }
                val buffered = size.width * animatedBuffered.value
                // From 0 under the white fill (as the buffered fill): no notch where two caps meet.
                val ghostEnd = origin?.let { max(it, head) }
                if (chapterGaps != null) {
                    clipPath(chapterGaps.pathFor(size, osd.chapterGap.toPx()), ClipOp.Difference) {
                        drawSeekFills(osd, top, height, buffered, ghostEnd, played)
                    }
                } else {
                    drawSeekFills(osd, top, height, buffered, ghostEnd, played)
                }
                if (isFocused) {
                    val center = Offset(head, size.height / 2f)
                    val headRadius = osd.seekThumbSize.toPx() / 2f
                    val ringed = headRadius + osd.seekThumbRing.toPx()
                    if (glowColor != null) {
                        for ((spread, share) in THUMB_GLOW_LAYERS) {
                            drawCircle(glowColor.copy(alpha = osd.seekThumbGlowAlpha * share), ringed + spread.toPx(), center)
                        }
                    }
                    drawCircle(NuvioPrimitives.black, ringed, center)
                    drawCircle(NuvioPrimitives.white, headRadius, center)
                }
            }
    )
}

/** The seek bar's track, buffered, ghost (to [ghostEnd] while a preview is pending) and played fills. */
private fun DrawScope.drawSeekFills(
    osd: NuvioMarqueeOsdTokens,
    top: Float,
    height: Float,
    buffered: Float,
    ghostEnd: Float?,
    played: Float
) {
    val radius = CornerRadius(height / 2f)
    drawRoundRect(osd.seekTrack, Offset(0f, top), Size(size.width, height), radius)
    if (buffered > 0f) drawRoundRect(osd.seekBuffered, Offset(0f, top), Size(buffered, height), radius)
    if (ghostEnd != null) drawRoundRect(osd.seekGhost, Offset(0f, top), Size(ghostEnd, height), radius)
    drawRoundRect(NuvioPrimitives.white, Offset(0f, top), Size(played, height), radius)
}

/**
 * The seek bar's chapter gaps as one clip path, rebuilt only when the bar's size (or the gap
 * width) changes: position ticks redraw against the same path (Spec 25 U6g, per-tick cost).
 */
private class ChapterGaps(private val fractions: List<Float>) {
    private val path = Path()
    private var builtSize = Size.Zero
    private var builtGap = 0f

    fun pathFor(size: Size, gapPx: Float): Path {
        if (size != builtSize || gapPx != builtGap) {
            path.reset()
            for (fraction in fractions) {
                val start = size.width * fraction
                path.addRect(Rect(start, 0f, start + gapPx, size.height))
            }
            builtSize = size
            builtGap = gapPx
        }
        return path
    }
}

/** OSD icon button (spec §Components): a transparent circle with a white icon; focused = white circle,
 *  black icon. [dot]: the accent dot under the icon (Subtitles while one is on). */
@Composable
private fun OsdIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    dot: (() -> Color)? = null
) {
    val osd = NuvioTheme.marquee.osd
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    IconButton(
        onClick = onClick,
        modifier = modifier.size(osd.iconButtonSize),
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        shape = IconButtonDefaults.shape(shape = CircleShape),
        colors = IconButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        )
    ) {
        Icon(painter = painter, contentDescription = contentDescription, modifier = Modifier.size(osd.iconSize))
        if (dot != null) OsdAccentDot(dot)
    }
}

/** Play/pause (spec §Components, Spec 45 Q5): unfocused a bare white icon in the 64 dp slot (no
 *  bubble); focused the white circle with a smaller black icon and the primary button's ring + glow. */
@Composable
private fun OsdPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    glow: () -> Color,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val osd = marquee.osd
    val primary = marquee.primaryButton
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    // Rasterised once at the larger (unfocused) size; focused draws it down to the smaller one.
    val playPainter = rememberRawSvgPainter(R.raw.ic_player_play, osd.playPauseIconSize)
    val pausePainter = rememberRawSvgPainter(R.raw.ic_player_pause, osd.playPauseIconSize)
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .size(osd.playPauseSize),
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        shape = IconButtonDefaults.shape(shape = CircleShape),
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(marquee.ringWidth, marquee.ring),
                inset = primary.ringGap,
                shape = CircleShape
            )
        ),
        glow = IconButtonDefaults.glow(
            focusedGlow = Glow(
                elevationColor = glow().copy(alpha = primary.glowAlpha),
                elevation = primary.glowElevation
            )
        )
    ) {
        Icon(
            painter = if (isPlaying) pausePainter else playPainter,
            contentDescription = stringResource(if (isPlaying) R.string.player_pill_pause else R.string.player_pill_play),
            modifier = Modifier.size(if (focused) osd.playPauseFocusedIconSize else osd.playPauseIconSize)
        )
    }
}

/**
 * Small glass pill (spec §Components): `glassFill`, white content; focused = white fill, black
 * content. Icon leads (still-watching) or trails ([trailingIcon]: skip); [height]
 * overrides `pillHeight`. (The More tray's pills are [OsdTrayPill].)
 */
@Composable
internal fun OsdPill(
    label: String,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: Boolean = false,
    height: Dp = NuvioTheme.marquee.osd.pillHeight
) {
    val osd = NuvioTheme.marquee.osd
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = osdGlassPillColors(),
        contentPadding = PaddingValues(horizontal = osd.pillPadding)
    ) {
        val icon = @Composable {
            Icon(painter = painter, contentDescription = null, modifier = Modifier.size(osd.pillIconSize))
        }
        if (!trailingIcon) {
            icon()
            Spacer(modifier = Modifier.width(osd.pillIconGap))
        }
        Text(text = label, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
        if (trailingIcon) {
            Spacer(modifier = Modifier.width(osd.pillIconGap))
            icon()
        }
    }
}

/** Glass pill colours (OsdPill, OsdTrayPill, pause cast chips): `glassFill` + white content; focused / pressed = white, black content. */
@Composable
internal fun osdGlassPillColors() = ButtonDefaults.colors(
    containerColor = NuvioTheme.marquee.glassFill,
    contentColor = NuvioPrimitives.white,
    focusedContainerColor = NuvioPrimitives.white,
    focusedContentColor = NuvioPrimitives.black,
    pressedContainerColor = NuvioPrimitives.white,
    pressedContentColor = NuvioPrimitives.black
)

/** The 4 dp accent dot centred under an icon, `dotBottom` above the button's edge (Subtitles on; F3 Q6). */
@Composable
private fun BoxScope.OsdAccentDot(color: () -> Color) {
    val osd = NuvioTheme.marquee.osd
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = osd.dotBottom)
            .size(osd.dotSize)
            .drawBehind { drawCircle(color()) }
    )
}
