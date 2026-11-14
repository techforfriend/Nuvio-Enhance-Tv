package com.nuvio.tv.ui.components.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.core.stream.resolutionLabel
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.marquee.MarqueeVeilDialog
import com.nuvio.tv.ui.components.rememberRawSvgPainter
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.holdReleaseGuard
import kotlin.math.roundToInt

/** The row a menu was opened on: its [stream], its window bounds, and whether it matches the show's preferred source. */
@Immutable
internal data class SourceRowMenuTarget(val stream: Stream, val anchor: Rect, val preferred: Boolean)

/** The focused item's sub-line (the mockup's `.p6it.focus small`). */
private const val FOCUSED_DETAIL_ALPHA = 0.6f

/** Lines up an item's icon with its first text line (the mockup's 2 px). */
private val ICON_TOP = 2.dp

/**
 * The menu hold OK opens on an episode's source row (Spec 65 P6, D16; sandbox
 * `spec-65-stream-filters` v5 stages 12, 14): Play, then "Prefer this source for the show" with
 * what it will match under it, or on a row that already matches ([SourceRowMenuTarget.preferred])
 * "Stop preferring this source". In its own dialog window under the row (`stream.rowMenu*`); focus
 * opens on the second item; Back calls [onDismiss]. The hold that opened it activates nothing
 * ([holdReleaseGuard]). [onPrefer] gets the stream to prefer, or null to stop.
 */
@Composable
internal fun SourceRowMenu(
    target: SourceRowMenuTarget,
    accent: () -> Color,
    onPlay: (Stream) -> Unit,
    onPrefer: (Stream?) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = NuvioTheme.marquee.stream
    val shape = RoundedCornerShape(tokens.rowMenuRadius)
    val actionFocus = remember { FocusRequester() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    MarqueeVeilDialog(onDismissRequest = onDismiss, veil = tokens.rowMenuVeil, initialFocus = actionFocus) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val menu = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val inset = tokens.rowMenuInset.roundToPx()
                            val start = if (rtl) target.anchor.right.roundToInt() - inset - menu.width else target.anchor.left.roundToInt() + inset
                            menu.place(
                                x = start.coerceIn(0, (constraints.maxWidth - menu.width).coerceAtLeast(0)),
                                y = rowMenuTop(
                                    rowTop = target.anchor.top.roundToInt(),
                                    rowBottom = target.anchor.bottom.roundToInt(),
                                    menuHeight = menu.height,
                                    offset = tokens.rowMenuOffset.roundToPx(),
                                    screenHeight = constraints.maxHeight,
                                    screenMargin = tokens.rowMenuScreenMargin.roundToPx()
                                )
                            )
                        }
                    }
                    .width(tokens.rowMenuWidth)
                    .holdReleaseGuard()
                    .clip(shape)
                    .background(tokens.rowMenuFill)
                    .border(1.dp, tokens.rowMenuEdge, shape)
                    .padding(tokens.rowMenuPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.rowMenuItemGap)
            ) {
                RowMenuItem(
                    icon = rememberRawSvgPainter(R.raw.ic_player_play, tokens.rowMenuIconSize),
                    label = stringResource(R.string.stream_row_menu_play),
                    accent = accent,
                    onClick = { onPlay(target.stream) }
                )
                if (target.preferred) {
                    RowMenuItem(
                        icon = rememberVectorPainter(Icons.Filled.Close),
                        label = stringResource(R.string.stream_stop_preferring),
                        detail = stringResource(R.string.stream_stop_preferring_detail),
                        accent = accent,
                        onClick = { onPrefer(null) },
                        modifier = Modifier.focusRequester(actionFocus)
                    )
                } else {
                    RowMenuItem(
                        icon = rememberVectorPainter(Icons.Filled.Check),
                        label = stringResource(R.string.stream_prefer_source),
                        detail = preferSourceDetail(target.stream),
                        accent = accent,
                        onClick = { onPrefer(target.stream) },
                        modifier = Modifier.focusRequester(actionFocus)
                    )
                }
            }
        }
    }
}

/**
 * The menu's top edge: [offset] under its row, or as far above the row when it would otherwise
 * end less than [screenMargin] from the screen bottom (a row low in the list); never above the
 * screen top.
 */
internal fun rowMenuTop(
    rowTop: Int,
    rowBottom: Int,
    menuHeight: Int,
    offset: Int,
    screenHeight: Int,
    screenMargin: Int
): Int {
    val below = rowBottom + offset
    if (below + menuHeight + screenMargin <= screenHeight) return below
    return (rowTop - offset - menuHeight).coerceAtLeast(0)
}

/**
 * What preferring [stream] will match, as the menu names it: "AIOStreams · 1080p · NTb release,
 * every episode"; the resolution and the release are left out when the row names none
 * ([com.nuvio.tv.core.stream.PreferredSource.of] reads the same facts).
 */
@Composable
private fun preferSourceDetail(stream: Stream): String {
    val resolution = remember(stream) { ReleaseFactsParser.resolutionOf(stream) }
    val release = remember(stream) { ReleaseFactsParser.parsedReleaseGroupOf(stream) }
    val parts = listOfNotNull(
        stream.addonName,
        resolution.takeIf { it != ReleaseResolution.UNKNOWN }?.let(::resolutionLabel),
        release?.let { stringResource(R.string.stream_prefer_source_release, it) }
    )
    return stringResource(R.string.stream_prefer_source_detail, parts.joinToString(" · "))
}

/** One menu item: [icon] in the ambient [accent], [label], and [detail] under it. Focused = white fill, black content. */
@Composable
private fun RowMenuItem(
    icon: Painter,
    label: String,
    accent: () -> Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(tokens.rowMenuItemRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = marquee.onImage2,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = tokens.rowMenuItemPaddingHorizontal,
                vertical = tokens.rowMenuItemPaddingVertical
            ),
            horizontalArrangement = Arrangement.spacedBy(tokens.rowMenuIconGap),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (focused) black else accent(),
                modifier = Modifier
                    .padding(top = ICON_TOP)
                    .size(tokens.rowMenuIconSize)
            )
            Column {
                Text(text = label, style = NuvioTheme.textStyles.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = NuvioTheme.textStyles.caption,
                        color = if (focused) black.copy(alpha = FOCUSED_DETAIL_ALPHA) else marquee.onImage3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
