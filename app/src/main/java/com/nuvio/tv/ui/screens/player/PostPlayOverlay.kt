package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.screens.detail.formatShortReleaseDate
import com.nuvio.tv.ui.util.formatRuntimeMinutes
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.core.player.NextEpisodeInfo
import com.nuvio.tv.core.player.PostPlayMode

/**
 * Post-play card in Marquee (wave U3d; sandbox `u3-player-osd-marquee` v5 stage 6): fixed width,
 * `cardRadius`, dark fill with a hairline edge; focused = white ring + `glowCard` in [accent].
 * Next episode: still at the start, then accent eyebrow (the countdown while one runs), episode
 * line and a facts line ([postPlayFactsLine]: runtime, then the status; 26/B5). Focused, the card
 * grows by `postPlayOverviewHeight` for the episode's 2-line overview and the facts line names
 * the air date (data-additions-marquee stage 14, T7); the card stays bottom-anchored. Still
 * watching: no still (its question needs the width), eyebrow, episode line, Play / Exit pills and
 * the stop countdown. Behaviour, focus and countdowns as built.
 */
@Composable
fun PostPlayOverlay(
    mode: PostPlayMode?,
    controlsVisible: Boolean,
    blurUnwatchedEpisodes: Boolean,
    accent: () -> Color,
    nextEpisodeFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester?,
    onPlayNext: () -> Unit,
    onContinueStillWatching: () -> Unit,
    onDismissStillWatching: () -> Unit,
    modifier: Modifier = Modifier,
    /** Spec 97 X6: the countdown's "Playing via <source>" line is left out. */
    hideSourceNames: Boolean = false,
) {
    val marquee = NuvioTheme.marquee
    val osd = marquee.osd
    val isAutoPlay = mode is PostPlayMode.AutoPlay
    val isAutoPlayPlayable = (mode as? PostPlayMode.AutoPlay)?.nextEpisode?.hasAired == true

    var placedFocused by remember(isAutoPlay, controlsVisible) { mutableStateOf(false) }
    var cardFocused by remember { mutableStateOf(false) }
    val hairline = NuvioTheme.spacing.hairline
    val edge = remember(marquee.cardRadius, hairline, osd.postPlayCardHairline) {
        Border(BorderStroke(hairline, osd.postPlayCardHairline), shape = RoundedCornerShape(marquee.cardRadius))
    }

    val transition = updateTransition(targetState = mode, label = "PostPlayMode")
    // One read drives both the card's growth and the text drawn into it.
    val overview = postPlayOverview((mode as? PostPlayMode.AutoPlay)?.nextEpisode, cardFocused)
    val cardHeight by animateDpAsState(
        targetValue = if (overview != null) osd.postPlayCardHeight + osd.postPlayOverviewHeight else osd.postPlayCardHeight,
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "postPlayCardHeight"
    )

    transition.AnimatedVisibility(
        visible = { it != null },
        enter = PostPlayCardEnter,
        exit = PostPlayCardExit,
        modifier = modifier,
    ) {
        val onCardClick: () -> Unit = when {
            isAutoPlay && isAutoPlayPlayable -> onPlayNext
            mode is PostPlayMode.StillWatching -> onContinueStillWatching
            else -> ({})
        }
        // The glow reads the accent only while the card is focused; the eyebrow leaf reads it itself.
        MarqueeCard(
            onClick = onCardClick,
            width = osd.postPlayCardWidth,
            height = cardHeight,
            glowColor = if (cardFocused) accent().copy(alpha = marquee.glowCardAlpha) else Color.Transparent,
            containerColor = osd.postPlayCardFill,
            edge = edge,
            focusedScale = 1f,
            modifier = Modifier
                .focusRequester(nextEpisodeFocusRequester)
                .onFocusChanged { cardFocused = it.isFocused }
                .onPlaced {
                    if (isAutoPlay && !controlsVisible && !placedFocused) {
                        placedFocused = true
                        runCatching { nextEpisodeFocusRequester.requestFocus() }
                    }
                }
                .then(
                    if (progressBarFocusRequester != null || leftFocusRequester != null) {
                        Modifier.focusProperties {
                            progressBarFocusRequester?.let { down = it }
                            leftFocusRequester?.let { left = it }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)) togetherWith
                        fadeOut(animationSpec = tween(120))
                },
                contentKey = { it?.let { current -> current::class } },
            ) { current ->
                when (current) {
                    is PostPlayMode.AutoPlay -> AutoPlayBody(
                        mode = current,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        focused = cardFocused,
                        overview = overview,
                        accent = accent,
                        hideSourceNames = hideSourceNames,
                    )
                    is PostPlayMode.StillWatching -> StillWatchingBody(
                        mode = current,
                        accent = accent,
                        onContinue = onContinueStillWatching,
                        onDismiss = onDismissStillWatching,
                    )
                    null -> Unit
                }
            }
        }
    }
}

/**
 * Spec 45 P1: while a Previous press finds a source, the post-play card's look with eyebrow "Previous
 * episode" and "Finding source" (runtime first, as Next's). Display only: not focusable or clickable,
 * so focus stays on the OSD's Previous button. Kept apart from [PostPlayOverlay], whose auto-play and
 * focus rules are Next's; the controller never sets both (a Previous press clears postPlayMode).
 */
@Composable
fun PreviousEpisodeSearchCard(
    episode: NextEpisodeInfo?,
    blurUnwatchedEpisodes: Boolean,
    accent: () -> Color,
    modifier: Modifier = Modifier,
) {
    val marquee = NuvioTheme.marquee
    val osd = marquee.osd
    // Keeps the last episode through the exit animation.
    var shown by remember { mutableStateOf(episode) }
    if (episode != null) shown = episode
    val shape = RoundedCornerShape(marquee.cardRadius)
    AnimatedVisibility(
        visible = episode != null,
        enter = PostPlayCardEnter,
        exit = PostPlayCardExit,
        modifier = modifier,
    ) {
        val info = shown ?: return@AnimatedVisibility
        val factsLine = postPlayFactsLine(
            runtime = info.runtimeMinutes?.let(::formatRuntimeMinutes),
            aired = null,
            status = stringResource(R.string.next_episode_finding_source),
            searching = true,
        )
        PostPlayEpisodeRow(
            episode = info,
            eyebrow = stringResource(R.string.player_previous_episode),
            factsLine = factsLine,
            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
            accent = accent,
            modifier = Modifier
                .size(osd.postPlayCardWidth, osd.postPlayCardHeight)
                .clip(shape)
                .background(osd.postPlayCardFill)
                .border(NuvioTheme.spacing.hairline, osd.postPlayCardHairline, shape),
        )
    }
}

private val PostPlayCardEnter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
    fadeIn(animationSpec = tween(220))
private val PostPlayCardExit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
    fadeOut(animationSpec = tween(160))

/** The episode card's row: still (when there is one), then eyebrow, episode line and [factsLine]. */
@Composable
private fun PostPlayEpisodeRow(
    episode: NextEpisodeInfo,
    eyebrow: String,
    factsLine: String?,
    blurUnwatchedEpisodes: Boolean,
    accent: () -> Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        if (episode.thumbnail != null) {
            NextEpisodeStill(thumbnail = episode.thumbnail, blurred = blurUnwatchedEpisodes)
        }
        PostPlayText(eyebrow = eyebrow, title = nextEpisodeDisplayLabel(episode), accent = accent) {
            if (factsLine != null) PostPlayStatusLine(text = factsLine)
        }
    }
}

@Composable
private fun AutoPlayBody(
    mode: PostPlayMode.AutoPlay,
    blurUnwatchedEpisodes: Boolean,
    focused: Boolean,
    overview: String?,
    accent: () -> Color,
    hideSourceNames: Boolean,
) {
    val osd = NuvioTheme.marquee.osd
    val nextEpisode = mode.nextEpisode
    // The auto-play countdown (as built: a source is picked and the episode is playable).
    val countdown = mode.countdownSec?.takeIf { nextEpisode.hasAired && !mode.sourceName.isNullOrBlank() }
    val statusText = when {
        !nextEpisode.hasAired -> nextEpisode.unairedMessage ?: stringResource(R.string.next_episode_unaired)
        mode.searching -> stringResource(R.string.next_episode_finding_source)
        countdown != null && !hideSourceNames -> stringResource(R.string.next_episode_via, mode.sourceName.orEmpty())
        else -> null
    }
    val aired = nextEpisode.takeIf { focused && it.hasAired }?.released
        ?.let(::formatShortReleaseDate)
        ?.let { stringResource(R.string.next_episode_aired, it) }
    val factsLine = postPlayFactsLine(
        runtime = nextEpisode.runtimeMinutes?.let(::formatRuntimeMinutes),
        aired = aired,
        status = statusText,
        searching = mode.searching
    )
    Column {
        PostPlayEpisodeRow(
            episode = nextEpisode,
            eyebrow = if (countdown != null) {
                stringResource(R.string.next_episode_in, countdown)
            } else {
                stringResource(R.string.next_episode_label)
            },
            factsLine = factsLine,
            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
            accent = accent,
            modifier = Modifier.height(osd.postPlayCardHeight),
        )
        if (overview != null) {
            Text(
                text = overview,
                style = NuvioTheme.textStyles.addonText,
                fontWeight = FontWeight.Normal,
                color = NuvioTheme.marquee.onImage2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = osd.postPlayTextPaddingH,
                    end = osd.postPlayTextPaddingH,
                    top = osd.postPlayOverviewTop
                ),
            )
        }
    }
}

@Composable
private fun StillWatchingBody(
    mode: PostPlayMode.StillWatching,
    accent: () -> Color,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val osd = NuvioTheme.marquee.osd
    val continueFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }
    var continueFocused by remember { mutableStateOf(false) }

    PostPlayText(
        eyebrow = stringResource(R.string.still_watching_title),
        title = nextEpisodeDisplayLabel(mode.nextEpisode),
        accent = accent,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(osd.pillRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OsdPill(
                label = stringResource(R.string.still_watching_continue),
                painter = rememberVectorPainter(Icons.Default.PlayArrow),
                onClick = onContinue,
                height = osd.postPlayPillHeight,
                modifier = Modifier
                    .focusRequester(continueFocusRequester)
                    .onPlaced {
                        if (!continueFocused) {
                            continueFocused = true
                            runCatching { continueFocusRequester.requestFocus() }
                        }
                    }
                    .focusProperties { right = exitFocusRequester },
            )
            OsdPill(
                label = stringResource(R.string.still_watching_exit),
                painter = rememberVectorPainter(Icons.Default.Close),
                onClick = onDismiss,
                height = osd.postPlayPillHeight,
                modifier = Modifier
                    .focusRequester(exitFocusRequester)
                    .focusProperties { left = continueFocusRequester },
            )
            if (mode.countdownSec != null) {
                PostPlayStatusLine(text = stringResource(R.string.still_watching_countdown, mode.countdownSec))
            }
        }
    }
}

/** Text block: accent eyebrow, episode line, then [bottom]. */
@Composable
private fun PostPlayText(
    eyebrow: String,
    title: String,
    accent: () -> Color,
    bottom: @Composable () -> Unit,
) {
    val osd = NuvioTheme.marquee.osd
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = osd.postPlayTextPaddingH, vertical = osd.postPlayTextPaddingV),
        verticalArrangement = Arrangement.spacedBy(osd.postPlayLineGap),
    ) {
        MarqueeEyebrow(text = eyebrow, accent = accent)
        Text(
            text = title,
            style = NuvioTheme.textStyles.bodyHome,
            fontWeight = FontWeight.SemiBold,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        bottom()
    }
}

/** The still: `postPlayStillWidth` x the card height at the card's start (the card clips its corners). */
@Composable
private fun NextEpisodeStill(
    thumbnail: String,
    blurred: Boolean,
) {
    val context = LocalContext.current
    val imageRequest = remember(context, thumbnail, blurred) {
        ImageRequest.Builder(context)
            .data(thumbnail)
            .crossfade(true)
            .apply {
                if (blurred) transformations(BlurTransformation())
            }
            .build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
        modifier = Modifier
            .width(NuvioTheme.marquee.osd.postPlayStillWidth)
            .fillMaxHeight(),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun PostPlayStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.caption,
        color = NuvioTheme.marquee.onImage2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun nextEpisodeDisplayLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val context = LocalContext.current
    val code = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode,
    )
    val localizedTitle = nextEpisode.title.localizeEpisodeTitle(context)
    return "$code • $localizedTitle"
}

/**
 * The card's facts line: [runtime], then the air date ([aired], set only while focused on an aired
 * episode) in place of [status], except while [searching] ("Finding source" stays visible).
 */
internal fun postPlayFactsLine(runtime: String?, aired: String?, status: String?, searching: Boolean): String? =
    listOfNotNull(runtime, if (searching) status else aired ?: status)
        .joinToString(" \u00b7 ")
        .takeIf { it.isNotEmpty() }

/** The overview a focused next-episode card grows for; null keeps the card at its base height. */
internal fun postPlayOverview(nextEpisode: NextEpisodeInfo?, focused: Boolean): String? =
    nextEpisode?.overview?.trim()?.takeIf { focused && it.isNotEmpty() }
