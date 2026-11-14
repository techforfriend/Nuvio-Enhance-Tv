package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.ui.components.marquee.FACT_SEPARATOR
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

// Live TV layout A list pieces (sandbox u34-live-tv-marquee v3 `.lvpills`, `.lvr`, `.lvlogo`,
// `.lvlive`, `.lvtag`; Spec 34 D16).

private const val FOCUSED_NOW_ALPHA = 0.75f
private const val FOCUSED_TAG_ALPHA = 0.7f
/** Outlined keys and tags: a white (black when focused) .30 hairline. */
internal const val TAG_EDGE_ALPHA = 0.3f
internal const val ROW_TRACK_ALPHA = 0.22f
internal const val FOCUSED_ROW_TRACK_ALPHA = 0.18f

/**
 * A filter's label: Favorites, Recents, or a Browse's picker values joined ("Canada · News"; Spec 38
 * stage 7), "All channels" when every picker is All. [categoryLabels]: [LiveTvUiState.categoryLabels].
 */
@Composable
internal fun liveTvFilterLabel(filter: LiveTvFilter, categoryLabels: Map<String, String> = emptyMap()): String =
    when (filter) {
        LiveTvFilter.Favorites -> stringResource(R.string.live_tv_filter_favorites)
        LiveTvFilter.Recents -> stringResource(R.string.live_tv_filter_recents)
        is LiveTvFilter.Browse -> liveTvBrowseLabels(filter, categoryLabels).labels()
            .joinToString(FACT_SEPARATOR).ifEmpty { stringResource(R.string.live_tv_filter_all) }
    }

/** A Browse's picker values worded; null = All. */
internal data class BrowseLabels(val country: String?, val category: String?, val language: String?) {
    fun labels(): List<String> = listOfNotNull(country, category, language)
}

@Composable
internal fun liveTvBrowseLabels(browse: LiveTvFilter.Browse, categoryLabels: Map<String, String>) = BrowseLabels(
    country = browse.country?.let { liveTvCountryLabel(it) },
    category = browse.category?.let { liveTvCategoryLabel(it, categoryLabels) },
    language = browse.language?.let(LiveTvModel::languageName)
)

/** A Country picker value: the country's English name, or "No country". */
@Composable
internal fun liveTvCountryLabel(key: String): String =
    if (key == LiveTvFilter.NO_COUNTRY) stringResource(R.string.live_tv_no_country) else LiveTvModel.countryName(key)

/** A Category picker value: its label as first written, or "Other". */
@Composable
internal fun liveTvCategoryLabel(key: String, categoryLabels: Map<String, String>): String =
    if (key == LiveTvFilter.OTHER) stringResource(R.string.live_tv_category_other)
    else categoryLabels[key] ?: key.replaceFirstChar(Char::uppercaseChar)

/**
 * One `.lvpills` pill: [pillHeight] tall, label + optional [count]; [selected] = glass + white,
 * [icon] (Sources) = glass with a leading icon; focused = white, black text (the pickers draw
 * their own, LiveTvPickers.kt). Callers put `focusRequester`/`onFocusChanged` in [modifier].
 */
@Composable
internal fun LiveTvPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    val glass = selected || icon != null
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(live.pillHeight)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (glass) marquee.glassFill else Color.Transparent,
            contentColor = if (glass) white else marquee.onImage3,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = live.pillPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(live.pillIconSize))
                Box(modifier = Modifier.width(live.pillIconGap))
            }
            Text(text = label, style = NuvioTheme.textStyles.cardLabel, maxLines = 1)
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = NuvioTheme.textStyles.caption,
                    color = when {
                        focused -> black.copy(alpha = SUFFIX_ALPHA)
                        selected -> marquee.onImage2
                        else -> marquee.onImage3
                    },
                    maxLines = 1,
                    modifier = Modifier.padding(start = live.pillCountGap)
                )
            }
        }
    }
}

/**
 * One channel row (`.lvr`, D16): number, tile, name (+ favorite star, LIVE, UNAVAILABLE or SOURCE
 * LOST), now + progress and next, else "category · country". Focus = white row with the accent glow;
 * [selected] (its preview shows while focus is elsewhere) = `selectedFill`; [playing] = accent
 * number; [muted] (the channel gave up) = faded text and tile + the UNAVAILABLE tag; lost (L8-D4) =
 * number, tile and text at `lostAlpha` + the SOURCE LOST tag and "[sourceName] no longer lists it".
 * Hold OK or MENU = [onLongPress] when set (the list's side panel, L7d; see
 * [MarqueeLongPress][com.nuvio.tv.ui.components.marquee.MarqueeLongPress]).
 */
@Composable
internal fun LiveTvRow(
    row: LiveRow,
    selected: Boolean,
    playing: Boolean,
    muted: Boolean,
    timeFormat: DateFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceName: String? = null,
    onLongPress: (() -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val live = marquee.liveTv
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberMarqueeLongPress()
    val shape = RoundedCornerShape(live.rowRadius)
    Surface(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .fillMaxWidth()
            .height(live.rowHeight)
            .onFocusChanged { focused = it.isFocused }
            .then(longPress.modifier(onLongPress)),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) live.selectedFill else Color.Transparent,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = live.accent.copy(alpha = marquee.glowCardAlpha),
                elevation = marquee.glowCardElevation
            )
        )
    ) {
        val secondary = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3
        val dimAlpha = when {
            row.lost -> if (focused) live.lostFocusedAlpha else live.lostAlpha
            muted -> live.mutedAlpha
            else -> 1f
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = live.rowPaddingStart, end = live.rowPaddingEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(live.rowInnerGap)
        ) {
            Text(
                text = row.number,
                style = NuvioTheme.textStyles.cardLabel.copy(fontFeatureSettings = "tnum"),
                color = when {
                    focused -> secondary
                    playing -> live.accent
                    else -> marquee.onImage3
                },
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = live.numberWidth)
                    // Lost dims the number too (`.lvr.lost .no`); muted keeps it.
                    .alpha(if (row.lost) dimAlpha else 1f)
            )
            LiveTvChannelTile(
                channel = row.channel,
                size = live.logoSize,
                radius = live.logoRadius,
                modifier = Modifier.alpha(dimAlpha)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(dimAlpha),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = row.channel.shownName,
                        style = NuvioTheme.textStyles.button,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (row.favorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = live.favorite,
                            modifier = Modifier.size(live.favoriteSize)
                        )
                    }
                    if (row.live) LiveTvLiveBadge()
                    if (row.lost) {
                        LiveTvTag(stringResource(R.string.live_tv_tag_source_lost), focused, warn = true)
                    } else if (muted) {
                        LiveTvTag(stringResource(R.string.live_tv_tag_unavailable), focused)
                    }
                }
                val now = row.now
                if (row.lost) {
                    sourceName?.let { name ->
                        Text(
                            text = stringResource(R.string.live_tv_row_lost, name),
                            style = NuvioTheme.textStyles.caption,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (now != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = now.title,
                            style = NuvioTheme.textStyles.caption,
                            color = if (focused) black.copy(alpha = FOCUSED_NOW_ALPHA) else marquee.onImage2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        LiveTvRowBar(fraction = row.progress ?: 0f, focused = focused)
                    }
                    row.next?.let { next ->
                        Text(
                            text = listOf(
                                stringResource(R.string.live_tv_row_next, timeFormat.format(Date(next.startMs))),
                                next.title
                            ).joinToString(FACT_SEPARATOR),
                            style = NuvioTheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    val facts = LiveTvModel.facts(row)
                    if (facts.isNotEmpty()) {
                        Text(
                            text = facts.joinToString(FACT_SEPARATOR),
                            style = NuvioTheme.textStyles.caption,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** The row's 40 dp programme bar: white on white .22, black on black .18 when focused. */
@Composable
private fun LiveTvRowBar(fraction: Float, focused: Boolean) {
    val live = NuvioTheme.marquee.liveTv
    val ink = if (focused) NuvioPrimitives.black else NuvioPrimitives.white
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .width(live.rowProgressWidth)
            .height(NuvioTheme.marquee.progressHeight)
            .clip(shape)
            .background(ink.copy(alpha = if (focused) FOCUSED_ROW_TRACK_ALPHA else ROW_TRACK_ALPHA))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(ink)
        )
    }
}

/** `.lvlive`: red badge, white dot + LIVE. */
@Composable
internal fun LiveTvLiveBadge(modifier: Modifier = Modifier) {
    val live = NuvioTheme.marquee.liveTv
    Row(
        modifier = modifier
            .height(live.badgeHeight)
            .background(live.liveRed, RoundedCornerShape(live.badgeRadius))
            .padding(horizontal = live.badgePaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(live.liveDotSize)
    ) {
        Box(
            modifier = Modifier
                .size(live.liveDotSize)
                .background(NuvioPrimitives.white, CircleShape)
        )
        Text(
            text = stringResource(R.string.live_tv_live),
            style = NuvioTheme.textStyles.chip,
            color = NuvioPrimitives.white,
            maxLines = 1
        )
    }
}

/**
 * `.lvtag`: a state tag after the name; dark on a focused row. Outlined (UNAVAILABLE), or [warn]
 * (`.lvtag.warn`, SOURCE LOST): amber text on an amber fill, no outline.
 */
@Composable
private fun LiveTvTag(text: String, focused: Boolean, warn: Boolean = false) {
    val live = NuvioTheme.marquee.liveTv
    val ink = if (focused) NuvioPrimitives.black else NuvioPrimitives.white
    val shape = RoundedCornerShape(live.badgeRadius)
    Text(
        text = text,
        style = NuvioTheme.textStyles.chip,
        color = when {
            warn -> if (focused) live.warnTagFocusedText else live.warn
            focused -> ink.copy(alpha = FOCUSED_TAG_ALPHA)
            else -> NuvioTheme.marquee.onImage2
        },
        maxLines = 1,
        modifier = Modifier
            .then(
                if (warn) Modifier.background(if (focused) live.warnTagFocusedFill else live.warnTagFill, shape)
                else Modifier.border(1.dp, ink.copy(alpha = TAG_EDGE_ALPHA), shape)
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** Monogram tile colours (the sandbox's), picked by channel key. */
private val TILE_COLOURS = listOf(
    0xFF0B5FA5, 0xFF1C3D8C, 0xFFC4142F, 0xFFC98A0B, 0xFF2E7D32, 0xFF7B1FA2, 0xFFB3263A, 0xFF5B3CC4,
    0xFFC62828, 0xFF00838F, 0xFFE8711A, 0xFFAD1457, 0xFF1E6E5A, 0xFFE2631B, 0xFF37474F, 0xFF1565C0
).map { Color(it) }

/**
 * `.lvlogo`: the channel's logo fitted on a white tile, else (none, or it failed) its monogram on
 * a solid colour. [size] x [size], corner [radius].
 */
@Composable
internal fun LiveTvChannelTile(channel: Channel, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    var logoFailed by remember(channel.logo) { mutableStateOf(false) }
    val logo = channel.logo?.takeIf { it.isNotBlank() && !logoFailed }
    val colour = TILE_COLOURS[Math.floorMod(channel.key.hashCode(), TILE_COLOURS.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(if (logo != null) NuvioPrimitives.white else colour),
        contentAlignment = Alignment.Center
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = channel.shownName,
                onError = { logoFailed = true },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size / 10)
            )
        } else {
            val mono = LiveTvModel.monogram(channel.shownName)
            Text(
                text = mono,
                style = NuvioTheme.textStyles.cardLabel.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (size < 36.dp) 10.sp else if (mono.length <= 2) 13.sp else 12.sp,
                    letterSpacing = 0.2.sp
                ),
                color = NuvioPrimitives.white,
                maxLines = 1
            )
        }
    }
}
