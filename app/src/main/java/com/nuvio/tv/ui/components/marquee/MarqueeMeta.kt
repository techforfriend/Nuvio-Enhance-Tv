package com.nuvio.tv.ui.components.marquee

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

// Text blocks over artwork (spec §Screens hero/info column, §Motion): meta row, progress bar
// and the fade + rise stagger used when the block's item changes.

// A fill's accent glow (spec: blur 10): three widening rounded rects stand in for the blur (no
// RenderEffect on the Shield, API 30), as (spread, share of the caller's alpha).
private val FILL_GLOW_LAYERS = listOf(1.5.dp to 0.25f, 3.dp to 0.15f, 5.dp to 0.10f)

/**
 * The accent glow around a bar fill [width] long, its track [height] tall at [top], in [color] at
 * [alpha] (the OSD seek bar's played fill, the loading hand-off bar). Draw it under the fill.
 */
internal fun DrawScope.drawFillGlow(width: Float, top: Float, height: Float, color: Color, alpha: Float) {
    for ((spread, share) in FILL_GLOW_LAYERS) {
        val s = spread.toPx()
        drawRoundRect(
            color = color.copy(alpha = alpha * share),
            topLeft = Offset(-s, top - s),
            size = Size(width + 2f * s, height + 2f * s),
            cornerRadius = CornerRadius(height / 2f + s)
        )
    }
}

// TMDB's mark as the data-additions mockup draws it: the brand name in TMDB green (a name, not copy).
internal const val TMDB_MARK = "TMDB"

// Hero text on item change: fade + rise 12 dp, 250 ms, children staggered 40 ms (§Motion).
private const val ENTER_MS = 250
private const val STAGGER_MS = 40
private val RISE = 12.dp

/** Drives [marqueeStaggered] children; restarts whenever the remember keys change. */
@Stable
internal class MarqueeEnter(
    internal val progress: Animatable<Float, AnimationVector1D>,
    internal val totalMs: Float,
    internal val risePx: Float
)

/** Enter animation for a block of [children] staggered children, replayed when [keys] change. */
@Composable
internal fun rememberMarqueeEnter(children: Int, vararg keys: Any?): MarqueeEnter {
    val risePx = with(LocalDensity.current) { RISE.toPx() }
    val progress = remember { Animatable(1f) }
    val totalMs = ENTER_MS + (children - 1).coerceAtLeast(0) * STAGGER_MS
    LaunchedEffect(*keys) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(totalMs, easing = LinearEasing))
    }
    return remember(progress, totalMs, risePx) { MarqueeEnter(progress, totalMs.toFloat(), risePx) }
}

/** Child [index] of a [MarqueeEnter] block: fades in and rises, [index] x 40 ms late. */
internal fun Modifier.marqueeStaggered(enter: MarqueeEnter, index: Int): Modifier = graphicsLayer {
    val elapsed = enter.progress.value * enter.totalMs - index * STAGGER_MS
    val p = (elapsed / ENTER_MS).coerceIn(0f, 1f)
    alpha = p
    translationY = (1f - p) * enter.risePx
}

/** Eyebrow in the ambient accent; [accent] is read here so its tween recomposes only this leaf. */
@Composable
internal fun MarqueeEyebrow(text: String, accent: () -> Color, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = NuvioTheme.textStyles.eyebrow,
        color = accent(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** A score as shown next to its IMDb or TMDB mark (one decimal); null hides the mark. */
internal fun formatRatingScore(rating: Number?): String? =
    rating?.toDouble()?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }

/**
 * One meta line: [parts] then the IMDb mark + [imdb], `meta` style in `onImage2`, separated by
 * 3 dp dots at `marquee.metaGap`. Without an IMDb score a TMDB one ([tmdb]) stands in under its
 * own mark (26/B2: a TMDB vote average is never labelled IMDb).
 *
 * The score is never cut (Spec 118 R2): on a line too wide for its space, [shrinkPart] (the
 * genres, the long nice-to-have part) gives way first, then the first part when [truncateFirst]
 * (an episode label), then the other parts from the end ([metaShrinkOrder], [fitMetaParts]).
 *
 * [shadow] (Spec 123 D5) lets the line stand on a bright picture with no gradient behind it: every
 * text carries it and each dot gets a dark disc under it.
 */
@Composable
internal fun MarqueeMetaRow(
    parts: List<String>,
    imdb: String?,
    modifier: Modifier = Modifier,
    truncateFirst: Boolean = false,
    tmdb: String? = null,
    shrinkPart: String? = null,
    shadow: Shadow? = null
) {
    val score = imdb ?: tmdb
    if (parts.isEmpty() && score == null) return
    val marquee = NuvioTheme.marquee
    val metaStyle = NuvioTheme.textStyles.meta.let { if (shadow == null) it else it.copy(shadow = shadow) }
    val order = remember(parts, truncateFirst, shrinkPart) { metaShrinkOrder(parts, truncateFirst, shrinkPart) }
    Layout(
        modifier = modifier,
        content = {
            parts.forEach { text ->
                Text(
                    text = text,
                    style = metaStyle,
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (score != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(marquee.metaGap)
                ) {
                    if (imdb != null) {
                        ImdbRatingSourceLabel(style = metaStyle)
                    } else {
                        Text(
                            text = TMDB_MARK,
                            style = metaStyle,
                            fontWeight = FontWeight.Bold,
                            color = NuvioPrimitives.tmdb,
                            maxLines = 1
                        )
                    }
                    Text(text = score, style = metaStyle, color = marquee.onImage2, maxLines = 1)
                }
            }
            // One dot per gap between shown items: at most parts.size of them.
            repeat(parts.size) { MarqueeMetaDot(shadow) }
        }
    ) { measurables, constraints ->
        val gap = marquee.metaGap.roundToPx()
        val scoreItem = if (score != null) measurables[parts.size] else null
        val dots = measurables.subList(if (score != null) parts.size + 1 else parts.size, measurables.size)
        val dotSlot = 2 * gap + META_DOT_SIZE.roundToPx()
        val fixed = scoreItem?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val natural = IntArray(parts.size) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) }
        val widths = fitMetaParts(
            natural, fixed, scoreItem != null, constraints.maxWidth, dotSlot, META_MIN_PART.roundToPx(), order
        )
        val shown = buildList {
            parts.indices.forEach { i ->
                if (widths[i] > 0) add(measurables[i].measure(Constraints(maxWidth = widths[i])))
            }
            scoreItem?.let { add(it.measure(Constraints(maxWidth = constraints.maxWidth))) }
        }
        val dotPlaceables = dots.take((shown.size - 1).coerceAtLeast(0)).map { it.measure(Constraints()) }
        val height = (shown + dotPlaceables).maxOfOrNull { it.height } ?: 0
        val width = (shown.sumOf { it.width } + dotPlaceables.size * dotSlot)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        // Centred and mirrored for RTL exactly as the Row this replaced (CenterVertically, placeRelative).
        val center = { h: Int -> Alignment.CenterVertically.align(h, height) }
        layout(width, height) {
            var x = 0
            shown.forEachIndexed { index, placeable ->
                if (index > 0) {
                    val dot = dotPlaceables[index - 1]
                    dot.placeRelative(x + gap, center(dot.height))
                    x += dotSlot
                }
                placeable.placeRelative(x, center(placeable.height))
                x += placeable.width
            }
        }
    }
}

/**
 * The order [MarqueeMetaRow]'s parts give way in: [shrinkPart] (by value), then part 0 when
 * [truncateFirst], then the rest from the end.
 */
internal fun metaShrinkOrder(parts: List<String>, truncateFirst: Boolean, shrinkPart: String?): List<Int> =
    buildList {
        shrinkPart?.let { parts.indexOf(it) }?.takeIf { it >= 0 }?.let(::add)
        if (truncateFirst && parts.isNotEmpty()) add(0)
        addAll(parts.indices.reversed())
    }.distinct()

/**
 * Widths for a meta line's parts ([natural] widths; 0 = hidden) so the line, plus a [fixed]-wide
 * score when [hasScore], fits [available]. Each gap between two shown items costs [dotSlot]. Parts
 * give way in [order]: one is cut to what is left (it ellipsizes), or hidden with its dot when that
 * would leave it under [minPart]. The score is never cut.
 */
internal fun fitMetaParts(
    natural: IntArray,
    fixed: Int,
    hasScore: Boolean,
    available: Int,
    dotSlot: Int,
    minPart: Int,
    order: List<Int>
): IntArray {
    val widths = natural.copyOf()
    fun total(): Int {
        val items = widths.count { it > 0 } + if (hasScore) 1 else 0
        return widths.sum() + fixed + (items - 1).coerceAtLeast(0) * dotSlot
    }
    for (i in order) {
        val over = total() - available
        if (over <= 0) break
        val cut = widths[i] - over
        widths[i] = if (cut >= minPart) cut else 0
    }
    return widths
}

/**
 * Tech badge (spec §Components): `techBadge` text in white, 1 dp outline white .38, radius `xs`.
 * [filled] (the release-group chip) swaps the outline for a white .08 fill at the same size.
 * [tick] ends the text with a green check (the OSD frame rate the display matched, data st7);
 * [warn] is the amber degraded style (data st8).
 */
@Composable
internal fun MarqueeTechBadge(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tick: Boolean = false,
    warn: Boolean = false
) {
    val tokens = NuvioTheme.marquee.techBadge
    val shape = RoundedCornerShape(NuvioTheme.radii.xs)
    val tickColor = NuvioTheme.marquee.readyText
    Text(
        text = if (!tick) AnnotatedString(text) else buildAnnotatedString {
            append(text)
            append(' ')
            withStyle(SpanStyle(color = tickColor)) { append(TICK) }
        },
        style = NuvioTheme.textStyles.techBadge,
        color = if (warn) tokens.warnText else NuvioPrimitives.white,
        maxLines = 1,
        modifier = modifier
            .then(
                when {
                    warn -> Modifier
                        .background(tokens.warnFill, shape)
                        .border(tokens.outlineWidth, tokens.warnOutline, shape)
                    filled -> Modifier.background(tokens.groupFill, shape)
                    else -> Modifier.border(tokens.outlineWidth, tokens.outline, shape)
                }
            )
            // The outline sits inside the box, so every variant keeps one height.
            .padding(horizontal = tokens.horizontalPadding, vertical = tokens.outlineWidth)
    )
}

private const val TICK = "\u2713"

private val META_DOT_SIZE = 3.dp

// A part cut narrower than this is dropped with its dot rather than shown as a stub ("Sc…").
private val META_MIN_PART = 40.dp

/** 3 dp separator dot, white .50; with [shadow], a dark disc under it (the meta text's shadow, D5). */
@Composable
internal fun MarqueeMetaDot(shadow: Shadow? = null) {
    Box(
        modifier = Modifier
            .size(META_DOT_SIZE)
            .then(
                if (shadow == null) Modifier
                else Modifier.drawBehind {
                    // Drawn outside the 3 dp box (no clip before it): reaching half the blur past the dot, offset as the text's.
                    drawCircle(
                        color = shadow.color.copy(alpha = shadow.color.alpha * DOT_SHADOW_SHARE),
                        radius = size.minDimension / 2f + shadow.blurRadius / 2f,
                        center = center + shadow.offset
                    )
                }
            )
            .clip(CircleShape)
            .background(NuvioTheme.marquee.onImage3)
    )
}

// The dot's shadow disc is solid, not blurred: half the text shadow's alpha reads as the same weight.
private const val DOT_SHADOW_SHARE = 0.5f

/** [width] x `progressHeight` bar (track white .22, fill [accent]) + an optional label after it. */
@Composable
internal fun MarqueeProgress(
    fraction: Float?,
    label: String?,
    accent: () -> Color,
    width: Dp,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(marquee.metaGap)
    ) {
        if (fraction != null) {
            val shape = RoundedCornerShape(marquee.progressHeight)
            Box(
                modifier = Modifier
                    .width(width)
                    .height(marquee.progressHeight)
                    .clip(shape)
                    .background(NuvioPrimitives.white.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .drawBehind { drawRect(accent()) }
                )
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = NuvioTheme.textStyles.metadata,
                color = marquee.onImage2,
                maxLines = 1
            )
        }
    }
}
