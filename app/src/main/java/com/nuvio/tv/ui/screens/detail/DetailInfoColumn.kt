package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.SourcePrefetchSignal
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.ui.components.marquee.MarqueeCreditLine
import com.nuvio.tv.ui.components.marquee.MarqueeEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueeLogoTitle
import com.nuvio.tv.ui.components.marquee.MarqueeMetaRow
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.components.marquee.formatRatingScore
import com.nuvio.tv.ui.components.marquee.titleTypeEyebrow
import com.nuvio.tv.ui.components.marquee.topAward
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.extractYearText
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.formatPlaybackClock
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.requestFocusAfterFrames

private const val MAX_META_GENRES = 2

/**
 * Detail first frame, info column (Marquee spec §Screens "Detail as decided at the U2 slice"):
 * eyebrow (type · seasons · status · "Part of" the TMDB collection, ambient accent), title or
 * logo, one meta line, the reserved source line ("Last played" when it applies), a 3-line
 * description (focusable when truncated: OK opens the About sheet), the credit line (+ top award),
 * then the actions: primary (Resume shows the position) + glass Library / Watched (movies) /
 * Trailer / About (opens the About sheet; data-additions-marquee st2, st4).
 *
 * Focus: the primary takes focus on entry and on [restorePlayFocusToken]; Up from the actions
 * reaches the description only when it is truncated (else Up is blocked), Up from the
 * description is blocked. Every action reports [onActionFocused]; the primary also
 * [onPlayFocused] (it feeds the trailer idle timer).
 *
 * `DetailSkeleton` (DetailStates.kt) mirrors this stack's order, line boxes and gaps; change both.
 */
@Composable
internal fun DetailInfoColumn(
    meta: Meta,
    isSeries: Boolean,
    seasonCount: Int,
    collectionName: String?,
    nextToWatch: NextToWatch?,
    accent: () -> Color,
    sourceSignal: SourcePrefetchSignal?,
    showImdbRating: Boolean,
    showFullReleaseDate: Boolean,
    isInLibrary: Boolean,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    trailerAvailable: Boolean,
    playFocusRequester: FocusRequester,
    restorePlayFocusToken: Int,
    onPlayClick: () -> Unit,
    onPlayLongPress: (() -> Unit)?,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleMovieWatched: () -> Unit,
    onTrailerClick: () -> Unit,
    onActionFocused: () -> Unit,
    onPlayFocused: () -> Unit,
    onShowAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    val descriptionFocusRequester = remember { FocusRequester() }
    val description = meta.description?.takeIf { it.isNotBlank() }
    var descriptionTruncated by rememberSaveable(description) { mutableStateOf(false) }
    val upFromActions = if (descriptionTruncated) descriptionFocusRequester else FocusRequester.Cancel

    LaunchedEffect(restorePlayFocusToken) {
        if (restorePlayFocusToken > 0) playFocusRequester.requestFocusAfterFrames()
    }

    Column(
        modifier = modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = detail.infoTop)
            .width(detail.infoWidth),
        verticalArrangement = Arrangement.spacedBy(detail.infoGap)
    ) {
        // The collection arrives with TMDB's collection fetch (movies, TMDB on): the eyebrow grows then.
        MarqueeEyebrow(
            text = titleTypeEyebrow(meta.apiType, isSeries, seasonCount, meta.status, collectionName),
            accent = accent
        )
        MarqueeLogoTitle(
            title = meta.name,
            logo = meta.logo,
            logoHeight = detail.logoMaxHeight,
            logoMaxWidth = detail.logoMaxWidth,
            textStyle = NuvioTheme.textStyles.displayHero
        )
        DetailMetaLine(meta = meta, showImdbRating = showImdbRating, showFullReleaseDate = showFullReleaseDate)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(detail.sourceLineHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            DetailSourceLine(signal = sourceSignal, accent = accent)
        }
        if (description != null) {
            DetailDescription(
                text = description,
                truncated = descriptionTruncated,
                onTruncatedChange = { descriptionTruncated = it },
                focusRequester = descriptionFocusRequester,
                downFocusRequester = playFocusRequester,
                onFocused = onActionFocused,
                onClick = onShowAbout
            )
        }
        MarqueeCreditLine(
            directors = meta.director,
            writers = meta.writer,
            isSeries = isSeries,
            reserveHeight = true,
            award = topAward(meta.awards)
        )
        Row(
            modifier = Modifier.padding(top = detail.actionsExtraTop),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(detail.actionGap)
        ) {
            fun Modifier.action(onFocused: () -> Unit = onActionFocused): Modifier = this
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusProperties { up = upFromActions }

            MarqueePrimaryButton(
                label = nextToWatch?.displayText ?: stringResource(R.string.hero_play),
                icon = Icons.Rounded.PlayArrow,
                onClick = onPlayClick,
                focusRequester = playFocusRequester,
                suffix = primarySuffix(nextToWatch),
                glowColor = accent,
                onLongPress = onPlayLongPress,
                modifier = Modifier.action(onFocused = {
                    onActionFocused()
                    onPlayFocused()
                })
            )
            MarqueeGlassButton(
                icon = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                contentDescription = stringResource(
                    if (isInLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library
                ),
                onClick = onToggleLibrary,
                onLongPress = onLibraryLongPress,
                modifier = Modifier.action()
            )
            if (meta.apiType == "movie") {
                MarqueeGlassButton(
                    icon = if (isMovieWatched) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(
                        if (isMovieWatched) R.string.hero_mark_unwatched else R.string.hero_mark_watched
                    ),
                    onClick = onToggleMovieWatched,
                    enabled = !isMovieWatchedPending,
                    modifier = Modifier.action()
                )
            }
            if (trailerAvailable) {
                MarqueeGlassButton(
                    icon = Icons.Default.Movie,
                    contentDescription = stringResource(R.string.hero_play_trailer),
                    onClick = onTrailerClick,
                    modifier = Modifier.action()
                )
            }
            // About opens the About sheet (every title has facts to show); OK on a truncated
            // description does too.
            MarqueeGlassButton(
                icon = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.about_title),
                onClick = onShowAbout,
                modifier = Modifier.action()
            )
        }
    }
}

/**
 * The resume position, "7:22 of 12:14" (U5), when resuming with a known duration; null otherwise
 * (the label already says Play/Next).
 */
@Composable
private fun primarySuffix(nextToWatch: NextToWatch?): String? {
    val progress = nextToWatch?.takeIf { it.isResume }?.watchProgress?.takeIf { it.duration > 0L } ?: return null
    val position = progress.resolveResumePosition(progress.duration)
    return stringResource(
        R.string.detail_resume_position,
        formatPlaybackClock(position),
        formatPlaybackClock(progress.duration)
    )
}

/** One line: year · age rating · ≤2 genres · runtime · IMDb (country/language live in the About sheet). */
@Composable
private fun DetailMetaLine(meta: Meta, showImdbRating: Boolean, showFullReleaseDate: Boolean) {
    val parts = detailMetaParts(meta, showFullReleaseDate)
    val context = LocalContext.current
    // The genres give way first on a long line (Spec 118 D4).
    val genres = remember(meta.genres, context) { metaGenres(meta.genres, context) }
    MarqueeMetaRow(
        parts = parts,
        imdb = formatRatingScore(meta.imdbRating?.takeIf { showImdbRating }),
        tmdb = formatRatingScore(meta.tmdbRating?.takeIf { showImdbRating }),
        shrinkPart = genres
    )
}

/** The meta line's parts before IMDb: year, age rating, ≤2 genres, runtime (shared with the scrolled-state block). */
@Composable
internal fun detailMetaParts(meta: Meta, showFullReleaseDate: Boolean): List<String> {
    val context = LocalContext.current
    return remember(meta, showFullReleaseDate, context) {
        buildList {
            extractYearText(meta.type, meta.releaseInfo, meta.released, showFullReleaseDate)
                ?.takeIf { it.isNotBlank() }?.let(::add)
            meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            metaGenres(meta.genres, context)?.let(::add)
            formatHeroRuntime(meta.runtime)?.let(::add)
        }
    }
}

/** Up to two localized genres, comma-joined; null when there are none. */
internal fun metaGenres(genres: List<String>, context: android.content.Context): String? =
    genres.filter { it.isNotBlank() }.take(MAX_META_GENRES)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { localizedGenreLabel(context, it) }

/**
 * `bodyDetail` description clamped to [maxLines] in [width]. Truncated text is focusable (also
 * when it fits, with [focusableWhenFits]: the person page with no credits, Spec 29 stage 12): a
 * `glassFill` plate is drawn outside the text box when focused (no reflow) and OK runs [onClick]
 * (Detail: the About sheet). Up is blocked; Down goes to [downFocusRequester].
 */
@Composable
internal fun DetailDescription(
    text: String,
    truncated: Boolean,
    onTruncatedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    maxLines: Int = NuvioTheme.marquee.detail.descriptionMaxLines,
    width: Dp = NuvioTheme.marquee.detail.descriptionWidth,
    focusableWhenFits: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val detail = marquee.detail
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = NuvioTheme.textStyles.bodyDetail,
        color = marquee.onImageBody,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow != truncated) onTruncatedChange(result.hasVisualOverflow)
        },
        modifier = Modifier
            .widthIn(max = width)
            .then(
                if (!truncated && !focusableWhenFits) Modifier else Modifier
                    .focusRequester(focusRequester)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = downFocusRequester
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onFocused()
                    }
                    .drawBehind {
                        if (!focused) return@drawBehind
                        val dx = detail.descriptionFocusInsetHorizontal.toPx()
                        val dy = detail.descriptionFocusInsetVertical.toPx()
                        val radius = detail.descriptionFocusRadius.toPx()
                        drawRoundRect(
                            color = marquee.glassFill,
                            topLeft = Offset(-dx, -dy),
                            size = Size(size.width + dx * 2, size.height + dy * 2),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
    )
}
