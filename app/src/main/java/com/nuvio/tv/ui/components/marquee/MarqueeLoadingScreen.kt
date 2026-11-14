package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.max

private const val FADE_IN_MS = 250
private const val FADE_OUT_MS = 200
private const val COMPLETE_AT = 0.999f
private const val COMPLETE_MS = 160

// Spec 75 failover notice (sandbox `spec-75-failure-reasons` `.hand .fo`): a 15 dp icon 8 dp from
// its text, the status line 2 dp under it.
private val NOTICE_ICON_SIZE = 15.dp
private val NOTICE_ICON_GAP = 8.dp
private val NOTICE_STATUS_TOP = 2.dp

/**
 * Loading hand-off (approved sandbox `u6-player-panels-marquee` stages 12-14, "B Hand-off"): the
 * Stream screen's backdrop, scrims and title block, the plot replaced by [MarqueeHandOffStatus].
 * Shown while the player starts a source and while an external player's next episode loads; the
 * Stream screen's direct auto-play draws the same block over its own backdrop at the same anchor.
 * [eyebrowStatus] ends the eyebrow ("Starting"); [reserveStatusLines] keeps the block still while
 * progress and the filename arrive (see [MarqueeHandOffStatus]). [headline] and [progressLabel] are
 * the Spec 33 network test hold's (sandbox `u33-welcome-setup` stage t-hold); [notice] and [footer]
 * the Spec 75 failover's (sandbox `spec-75-failure-reasons` stage 8).
 */
@Composable
internal fun MarqueeLoadingScreen(
    visible: Boolean,
    backdropUrl: String?,
    title: String,
    logo: String?,
    contentType: String?,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    year: String?,
    accent: () -> Color,
    modifier: Modifier = Modifier,
    runtimeMinutes: Int? = null,
    genres: String? = null,
    imdb: String? = null,
    tmdb: String? = null,
    eyebrowStatus: String? = null,
    headline: String? = null,
    progressLabel: String? = null,
    message: String? = null,
    sourceLine: String? = null,
    filename: String? = null,
    progress: Float? = null,
    reserveStatusLines: Boolean = false,
    notice: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(FADE_IN_MS)),
        exit = fadeOut(tween(FADE_OUT_MS)),
        modifier = modifier
    ) {
        val marquee = NuvioTheme.marquee
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioPrimitives.black)
        ) {
            MarqueeBackdropImage(url = backdropUrl, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
            MarqueeScrims(
                leftStops = marquee.scrimLeftStops,
                bottomStops = marquee.scrimBottomStops,
                drawTopScrim = true
            )
            MarqueeTitleBlock(
                title = title,
                logo = logo,
                contentType = contentType,
                season = season,
                episode = episode,
                episodeName = episodeName,
                year = year,
                accent = accent,
                runtimeMinutes = runtimeMinutes,
                genres = genres,
                imdb = imdb,
                tmdb = tmdb,
                eyebrowStatus = eyebrowStatus,
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                MarqueeHandOffStatus(
                    accent = accent,
                    headline = headline,
                    progressLabel = progressLabel,
                    progress = progress,
                    message = message,
                    sourceLine = sourceLine,
                    filename = filename,
                    reserveLines = reserveStatusLines,
                    notice = notice,
                    footer = footer
                )
            }
        }
    }
}

/**
 * The hand-off lines under a [MarqueeTitleBlock]: the [headline] when given, the [progress] bar
 * (white, accent glow) when there is progress, then the status line ([progressLabel] or the
 * percentage, or a spinner before any progress; the [message]; the [sourceLine] after a dot,
 * capped at `handOffSourceMaxWidth`), then the [filename]. Addon text ellipsizes. The block is bottom-anchored, so a line that appears pushes
 * the title up: [reserveLines] lays out the bar and the filename line while they are still empty.
 * [notice] is an amber line behind a warning icon between the bar and the status line (Spec 75: a
 * failover's reason); [footer] follows the filename (the failed source's step line).
 */
@Composable
internal fun ColumnScope.MarqueeHandOffStatus(
    accent: () -> Color,
    headline: String? = null,
    progressLabel: String? = null,
    progress: Float? = null,
    message: String? = null,
    sourceLine: String? = null,
    filename: String? = null,
    reserveLines: Boolean = false,
    notice: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.playerPanel
    if (!headline.isNullOrBlank()) {
        Text(
            text = headline,
            style = NuvioTheme.textStyles.episodeName,
            color = NuvioPrimitives.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (progress != null || reserveLines) {
        HandOffBar(progress = progress, accent = accent, modifier = Modifier.padding(top = tokens.handOffBarTop))
    }
    if (!notice.isNullOrBlank()) {
        Row(
            modifier = Modifier.padding(top = tokens.handOffStatusTop),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NOTICE_ICON_GAP)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = NuvioPrimitives.warning,
                modifier = Modifier.size(NOTICE_ICON_SIZE)
            )
            Text(
                text = notice,
                style = NuvioTheme.textStyles.meta,
                color = NuvioPrimitives.warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Row(
        modifier = Modifier.padding(top = if (notice.isNullOrBlank()) tokens.handOffStatusTop else NOTICE_STATUS_TOP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.handOffStatusGap)
    ) {
        if (progress != null) {
            Text(
                text = progressLabel ?: "${loadingPercent(progress)}%",
                style = NuvioTheme.textStyles.handOffPercent,
                color = NuvioPrimitives.white,
                maxLines = 1
            )
        } else {
            MarqueeSpinner(
                size = tokens.handOffSpinnerSize,
                trackColor = tokens.handOffSpinnerTrack,
                arcColor = NuvioPrimitives.white
            )
        }
        if (!message.isNullOrBlank()) {
            // Only the message is weighted: it takes what the capped source line leaves.
            HandOffStatusText(message, Modifier.weight(1f, fill = false))
        }
        if (!sourceLine.isNullOrBlank()) {
            if (!message.isNullOrBlank()) MarqueeMetaDot()
            HandOffStatusText(sourceLine, Modifier.widthIn(max = tokens.handOffSourceMaxWidth))
        }
    }
    if (!filename.isNullOrBlank() || reserveLines) {
        Text(
            text = filename.orEmpty(),
            style = NuvioTheme.textStyles.caption,
            color = marquee.onImage3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    footer?.invoke()
}

@Composable
private fun HandOffStatusText(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = NuvioTheme.textStyles.meta,
        color = NuvioTheme.marquee.onImage2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * `handOffBarWidth` bar: track, the fill's accent glow, then the white fill, animated and drawn in
 * the draw phase; nothing is drawn while [progress] is null (the slot stays laid out).
 */
@Composable
private fun HandOffBar(progress: Float?, accent: () -> Color, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.playerPanel
    val target = (progress ?: 0f).coerceIn(0f, 1f)
    val fill = animateFloatAsState(
        targetValue = target,
        // Completion (the controller snaps to 1 as the screen hides) runs faster, so the bar
        // fills before the 200 ms fade-out ends.
        animationSpec = tween(if (target >= COMPLETE_AT) COMPLETE_MS else NuvioTheme.motion.durations.overlay),
        label = "handOffProgress"
    )
    val drawn = progress != null
    Box(
        modifier = modifier
            .size(tokens.handOffBarWidth, tokens.handOffBarHeight)
            .drawBehind {
                if (!drawn) return@drawBehind
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = tokens.handOffBarTrack, cornerRadius = radius)
                val played = size.width * fill.value
                if (played <= 0f) return@drawBehind
                drawFillGlow(width = played, top = 0f, height = size.height, color = accent(), alpha = tokens.handOffGlowAlpha)
                drawRoundRect(
                    color = NuvioPrimitives.white,
                    size = Size(max(played, size.height), size.height),
                    cornerRadius = radius
                )
            }
    )
}

/** Whole percent of a 0..1 [progress] for the status line, rounded down so 100 % means done. */
internal fun loadingPercent(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100f).toInt()
