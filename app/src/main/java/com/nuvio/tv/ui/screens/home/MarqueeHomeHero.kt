package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.marquee.BACKDROP_FOCAL
import com.nuvio.tv.ui.components.marquee.MarqueeBackdropImage
import com.nuvio.tv.ui.components.marquee.MarqueeCreditLine
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueeProgress
import com.nuvio.tv.ui.components.marquee.MarqueeScrims
import com.nuvio.tv.ui.components.marquee.marqueeStaggered
import com.nuvio.tv.ui.components.marquee.hasCredit
import com.nuvio.tv.ui.components.marquee.rememberMarqueeEnter
import com.nuvio.tv.ui.components.marquee.titleTypeEyebrow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedGenreLabel

private const val TRAILER_CROSSFADE_MS = 480

/** Eyebrow, title, meta, progress, description + credit: the staggered children of the hero block. */
private const val HERO_CHILDREN = 5

/** What plays behind the hero in place of the backdrop: a catalog trailer or a folder hero video. */
internal data class HeroMediaState(
    val url: String?,
    val audioUrl: String?,
    val playbackKey: String?,
    val muted: Boolean,
    val play: Boolean
)

/**
 * Full-bleed backdrop of the settled item, crossfading on change, with the hero media fading in
 * over it once its first frame renders, under `media.scrimLeft` + `media.scrimBottom`. The top
 * bar owns `media.scrimTop` on Home; pushed screens pass [drawTopScrim] for their top line.
 */
@Composable
internal fun MarqueeBackdrop(
    backdropUrl: String?,
    media: () -> HeroMediaState,
    drawTopScrim: Boolean,
    onMediaEnded: () -> Unit
) {
    var mediaFirstFrame by remember { mutableStateOf(false) }
    val mediaState = media()
    // A stopped-then-resumed player (same key) must not fade in before its new first frame.
    LaunchedEffect(mediaState.playbackKey, mediaState.play) { mediaFirstFrame = false }
    val mediaAlpha by animateFloatAsState(
        targetValue = if (mediaState.play && mediaFirstFrame) 1f else 0f,
        animationSpec = tween(TRAILER_CROSSFADE_MS),
        label = "heroMediaAlpha"
    )
    // Seeds this hero on return; the Detail hand-off is written at click time (P32).
    LaunchedEffect(backdropUrl) {
        if (!backdropUrl.isNullOrBlank()) HeroBackdropState.lastDisplayedUrl = backdropUrl
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MarqueeBackdropImage(
            url = backdropUrl,
            focal = BACKDROP_FOCAL,
            modifier = Modifier.fillMaxSize(),
            alpha = { 1f - mediaAlpha }
        )
        if (mediaState.play && !mediaState.url.isNullOrBlank()) {
            key(mediaState.playbackKey ?: mediaState.url) {
                TrailerPlayer(
                    trailerUrl = mediaState.url,
                    trailerAudioUrl = mediaState.audioUrl,
                    isPlaying = true,
                    onEnded = onMediaEnded,
                    onFirstFrameRendered = { mediaFirstFrame = true },
                    muted = mediaState.muted,
                    cropToFill = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = mediaAlpha }
                )
            }
        }
        MarqueeScrims(
            leftStops = NuvioTheme.marquee.scrimLeftStops,
            bottomStops = NuvioTheme.marquee.scrimBottomStops,
            drawTopScrim = drawTopScrim
        )
    }
}

/**
 * Everything the hero block shows for one settled item. [eyebrow] null: the item's own type eyebrow
 * from [preview] (titles: "Series · 2 seasons · Ended"; nothing for a preview without an api type).
 */
internal data class MarqueeHeroContent(
    val preview: HeroPreview,
    val eyebrow: String?,
    val progressFraction: Float?
)

/**
 * Hero block (Marquee spec §Screens "Home"; sandbox data-additions-marquee st1): eyebrow, title or
 * logo, meta row, Continue Watching progress, two-line description. With a credit ("Created by",
 * "Directed by", "Written by") the description keeps one line and the credit line takes the second,
 * so the block never grows. Bottom-anchored by the caller; when the block is taller than
 * [Constraints.maxHeight] the description and credit are dropped (top never above y 80).
 * [titleStyle]: Search/Discover's results hero passes `stateTitle`. Search's compact hero (Spec 38
 * Q4 B) also passes its own [gap], [width], [descriptionWidth] and [eyebrowContext] (joined after the
 * eyebrow: the catalog it came from).
 */
@Composable
internal fun MarqueeHeroBlock(
    content: MarqueeHeroContent,
    accent: () -> Color,
    showImdbRatings: Boolean,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = NuvioTheme.textStyles.heroTitle,
    gap: Dp = NuvioTheme.marquee.home.heroGap,
    width: Dp = NuvioTheme.marquee.home.heroWidth,
    descriptionWidth: Dp = NuvioTheme.marquee.home.heroDescriptionWidth,
    eyebrowContext: String? = null
) {
    val home = NuvioTheme.marquee.home
    val preview = content.preview
    val ownEyebrow = content.eyebrow ?: preview.apiType?.let {
        titleTypeEyebrow(it, preview.isSeries, preview.seasonCount, preview.status)
    }
    val eyebrow = if (eyebrowContext.isNullOrEmpty()) ownEyebrow else listOfNotNull(ownEyebrow, eyebrowContext).joinToString(" · ")
    val enter = rememberMarqueeEnter(HERO_CHILDREN, preview.title, eyebrow)
    fun Modifier.staggered(index: Int): Modifier = marqueeStaggered(enter, index)

    val progress = content.progressFraction?.takeIf { it > 0f }
    val progressLabel = preview.secondaryHighlightText?.takeIf { it.isNotBlank() }
    val description = preview.description?.takeIf { it.isNotBlank() }
    val showCredit = hasCredit(preview.directors, preview.writers)
    // The description + credit block is the last child; both lambdas must agree on it.
    val hasBody = description != null || showCredit
    Layout(
        modifier = modifier.width(width),
        content = {
            Text(
                text = eyebrow.orEmpty().uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = accent(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.staggered(0)
            )
            MarqueeLogoTitle(
                title = preview.title,
                logo = preview.logo,
                logoHeight = home.heroLogoMaxHeight,
                logoMaxWidth = home.heroLogoMaxWidth,
                textStyle = titleStyle,
                modifier = Modifier.staggered(1)
            )
            HeroMetaRow(preview = preview, showImdbRatings = showImdbRatings, modifier = Modifier.staggered(2))
            if (progress != null || progressLabel != null) {
                MarqueeProgress(
                    fraction = progress,
                    label = progressLabel,
                    accent = accent,
                    width = home.heroProgressWidth,
                    modifier = Modifier.staggered(3)
                )
            }
            if (hasBody) {
                Column(modifier = Modifier.widthIn(max = descriptionWidth).staggered(4)) {
                    if (description != null) {
                        Text(
                            text = description,
                            style = NuvioTheme.textStyles.bodyHome,
                            color = NuvioTheme.marquee.onImageBody,
                            maxLines = if (showCredit) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MarqueeCreditLine(directors = preview.directors, writers = preview.writers, isSeries = preview.isSeries)
                }
            }
        }
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        fun stackHeight(count: Int) =
            placeables.take(count).sumOf { it.height } + gapPx * (count - 1).coerceAtLeast(0)
        val shown = if (hasBody && constraints.hasBoundedHeight &&
            stackHeight(placeables.size) > constraints.maxHeight
        ) placeables.size - 1 else placeables.size
        val height = stackHeight(shown)
        layout(constraints.maxWidth, height) {
            var y = 0
            placeables.take(shown).forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height + gapPx
            }
        }
    }
}

/** One line: episode label, year, age rating, genres, runtime, IMDb, separated by 3 dp dots. */
@Composable
private fun HeroMetaRow(preview: HeroPreview, showImdbRatings: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val genres = remember(preview, context) {
        preview.genres.list.take(2)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { localizedGenreLabel(context, it) }
    }
    val parts = remember(preview, genres) {
        buildList {
            preview.episodeLabel?.takeIf { it.isNotBlank() }?.let(::add)
            preview.yearText?.takeIf { it.isNotBlank() }?.let(::add)
            preview.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            genres?.let(::add)
            preview.runtimeText?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
    MarqueeMetaRow(
        parts = parts,
        imdb = preview.imdbText?.takeIf { showImdbRatings && it.isNotBlank() },
        tmdb = preview.tmdbText?.takeIf { showImdbRatings },
        truncateFirst = !preview.episodeLabel.isNullOrBlank(),
        shrinkPart = genres,
        modifier = modifier
    )
}

/**
 * Breadcrumb in place of the top bar on pushed screens (FolderDetail: collection › folder), at
 * the bar's y; the last part is white. Placed here so every screen draws it at one position.
 */
@Composable
internal fun MarqueeBreadcrumb(parts: List<String>) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = marquee.home.breadcrumbTop),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(text = "\u203A", style = NuvioTheme.textStyles.tab, color = marquee.onImage2)
            }
            Text(
                text = part,
                style = NuvioTheme.textStyles.tab,
                color = if (index == parts.lastIndex) NuvioTheme.colors.TextPrimary else marquee.onImage2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "Mira Halden · Acting" / "Blender Studio · Production company" at the top of a scrolled page
 * (Spec 29 stages 3, 8, 9; u6 stage 3's shape), `tab` style at `detail.contextTop`: the name white,
 * " · [detail]" in `onImage2`. [modifier] applies to the text (fade, width cap).
 */
@Composable
internal fun MarqueeContextLine(name: String, detail: String?, modifier: Modifier = Modifier) {
    val marquee = NuvioTheme.marquee
    val text = remember(name, detail, marquee.onImage2) {
        buildAnnotatedString {
            append(name)
            if (detail != null) withStyle(SpanStyle(color = marquee.onImage2)) { append(" \u00B7 $detail") }
        }
    }
    Text(
        text = text,
        style = NuvioTheme.textStyles.tab,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = marquee.detail.contextTop)
            .then(modifier)
    )
}
