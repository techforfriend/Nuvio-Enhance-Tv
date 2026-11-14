package com.nuvio.tv.ui.components.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.stream.FacetFilter
import com.nuvio.tv.core.stream.SavedStreamFilter
import com.nuvio.tv.core.stream.SavedStreamFilters
import com.nuvio.tv.core.stream.StreamPin
import com.nuvio.tv.core.stream.StreamListSelection
import com.nuvio.tv.core.stream.StreamListView
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.SourceChipStatus
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueePinnedChip
import com.nuvio.tv.ui.components.marquee.PinnedChipLook
import com.nuvio.tv.ui.components.marquee.MarqueeSpinner
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/** A sheet the Sources panel draws over itself (Spec 65 P1); each opens from its circle in the row. */
enum class SourcesPanelSheet { SOURCES, FILTERS, HIDDEN }

/** The focus targets of the three sheet circles; a sheet returns focus to its own on closing. */
internal class SourcesCircleFocus {
    val sources = FocusRequester()
    val filters = FocusRequester()
    val hidden = FocusRequester()

    fun of(sheet: SourcesPanelSheet): FocusRequester = when (sheet) {
        SourcesPanelSheet.SOURCES -> sources
        SourcesPanelSheet.FILTERS -> filters
        SourcesPanelSheet.HIDDEN -> hidden
    }
}

/** What the sources circle carries besides its icon. */
internal enum class SourcesCircleMark { NONE, ASKING, FAILED }

/**
 * The sources circle's mark (stage 10): the spinner while the list loads or an addon has not
 * answered, else the failed mark while an addon's chip reads ERROR (no stream from it; the view
 * models drop such a chip 1.6 s later), else nothing.
 */
internal fun sourcesCircleMark(isLoading: Boolean, sourceChips: List<SourceChipItem>): SourcesCircleMark = when {
    isLoading || sourceChips.any { it.status == SourceChipStatus.LOADING } -> SourcesCircleMark.ASKING
    sourceChips.any { it.status == SourceChipStatus.ERROR } -> SourcesCircleMark.FAILED
    else -> SourcesCircleMark.NONE
}

/** A chip the row shows for a pin: [active] while its filter is on or its addon's view shows. */
internal sealed interface PinnedChip {
    val pin: StreamPin
    val active: Boolean

    data class Filter(val saved: SavedStreamFilter, override val active: Boolean) : PinnedChip {
        override val pin: StreamPin get() = StreamPin.Filter(saved.id)

        /** The filter OK leads to: this one's values, or none when they are the ones on. */
        val filterOnClick: FacetFilter get() = if (active) FacetFilter.NONE else saved.filter
    }

    /** [count]: the streams the addon's view lists under the filter that is on. */
    data class Addon(val name: String, val count: Int?, override val active: Boolean) : PinnedChip {
        override val pin: StreamPin get() = StreamPin.Addon(name)

        /** The view OK leads to: this addon's, or All when it is the one showing. */
        val selectionOnClick: StreamListSelection
            get() = if (active) StreamListSelection.All else StreamListSelection.Addon(name)
    }
}

/**
 * The row's pinned chips (stage 2), in pin order: each pinned saved filter, and each pinned addon
 * that is among [availableAddons] (one with no stream for this title is left out).
 */
internal fun pinnedChips(
    saved: SavedStreamFilters,
    view: StreamListView,
    availableAddons: List<String>
): List<PinnedChip> {
    val filterOn = saved.matching(view.filter)?.id
    return saved.pins.mapNotNull { pin ->
        when (pin) {
            is StreamPin.Filter -> saved.filters.firstOrNull { it.id == pin.id }
                ?.let { PinnedChip.Filter(it, active = it.id == filterOn) }
            is StreamPin.Addon -> pin.name.takeIf { it in availableAddons }?.let { name ->
                PinnedChip.Addon(
                    name = name,
                    count = view.addonCounts[name],
                    active = view.selection == StreamListSelection.Addon(name)
                )
            }
        }
    }
}

/** The pinned chip holding focus, in a plain field: read when the chips change, no recomposition per move. */
private class PinFocusMemo {
    var focused: StreamPin? = null
}

/**
 * The panel's row of circles (Spec 65 D12, sandbox `spec-65-stream-filters` v5 stages 1-3, 5,
 * 10): refresh, sources ([mark]: asking spinner / failed mark), filters (filled, with the count of
 * values on in the ambient [accent], while [view] is filtered) and, while [view] hides streams,
 * the amber hidden circle (its count; an amber ring while the hidden view shows). The three after
 * the refresh open their sheet ([onOpenSheet]) and are focused through [focus]. Then [pins], which
 * scroll in the room the circles leave and act on OK ([onPinClick]), not on focus. Left/Right stay
 * inside the row. A focused hidden circle or pinned chip that leaves (nothing hidden any more; its
 * addon gave no stream after a refresh) hands focus to the sources circle.
 */
@Composable
internal fun SourcesCircleRow(
    view: StreamListView,
    mark: SourcesCircleMark,
    accent: () -> Color,
    focus: SourcesCircleFocus,
    onRefresh: () -> Unit,
    onOpenSheet: (SourcesPanelSheet) -> Unit,
    modifier: Modifier = Modifier,
    pins: List<PinnedChip> = emptyList(),
    onPinClick: (PinnedChip) -> Unit = {}
) {
    val tokens = NuvioTheme.marquee.stream
    val showHidden = view.hiddenCount > 0
    var hiddenHasFocus by remember { mutableStateOf(false) }
    // Read in the composition that removes the circle, before its disposal reports focus lost.
    val hiddenHeldFocus = remember(showHidden) { hiddenHasFocus }
    LaunchedEffect(showHidden) {
        if (showHidden || !hiddenHeldFocus) return@LaunchedEffect
        hiddenHasFocus = false
        focus.sources.requestFocusAfterFrames(frames = 1)
    }
    val pinFocus = remember { PinFocusMemo() }
    val pinKeys = pins.map { it.pin }
    // As above: read before the leaving chip's disposal reports its focus lost.
    val focusedPinLeft = remember(pinKeys) { pinFocus.focused.let { it != null && it !in pinKeys } }
    LaunchedEffect(pinKeys) {
        if (!focusedPinLeft) return@LaunchedEffect
        pinFocus.focused = null
        focus.sources.requestFocusAfterFrames(frames = 1)
    }
    Row(
        modifier = modifier
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left ||
                        requestedFocusDirection == FocusDirection.Right
                    ) cancelFocusChange()
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(tokens.chipGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MarqueeGlassButton(
            icon = Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.cd_refresh),
            onClick = onRefresh,
            size = tokens.refreshSize
        )
        SourcesCircle(
            icon = Icons.Rounded.Extension,
            contentDescription = stringResource(
                if (mark == SourcesCircleMark.ASKING) R.string.cd_stream_sources_asking else R.string.cd_stream_sources
            ),
            onClick = { onOpenSheet(SourcesPanelSheet.SOURCES) },
            modifier = Modifier.focusRequester(focus.sources),
            badge = "!".takeIf { mark == SourcesCircleMark.FAILED },
            badgeColor = { tokens.circleBadgeError },
            asking = mark == SourcesCircleMark.ASKING
        )
        val filtersOn = view.filter.count
        SourcesCircle(
            icon = Icons.Rounded.Tune,
            contentDescription = if (filtersOn > 0) {
                stringResource(R.string.cd_stream_filters_on, filtersOn)
            } else {
                stringResource(R.string.cd_stream_filters)
            },
            onClick = { onOpenSheet(SourcesPanelSheet.FILTERS) },
            modifier = Modifier.focusRequester(focus.filters),
            look = if (filtersOn > 0) SourcesCircleLook.ON else SourcesCircleLook.PLAIN,
            badge = filtersOn.takeIf { it > 0 }?.toString(),
            badgeColor = accent
        )
        if (showHidden) {
            SourcesCircle(
                icon = Icons.Filled.Warning,
                contentDescription = pluralStringResource(R.plurals.cd_stream_hidden, view.hiddenCount, view.hiddenCount),
                onClick = { onOpenSheet(SourcesPanelSheet.HIDDEN) },
                modifier = Modifier
                    .focusRequester(focus.hidden)
                    .onFocusChanged { hiddenHasFocus = it.isFocused },
                look = if (view.selection == StreamListSelection.Hidden) {
                    SourcesCircleLook.WARNING_SHOWN
                } else {
                    SourcesCircleLook.WARNING
                },
                badge = view.hiddenCount.toString(),
                badgeColor = { tokens.limitWarn }
            )
        }
        if (pins.isNotEmpty()) {
            val scroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    // More chips follow: the row's end fades out over them.
                    .drawWithContent {
                        drawContent()
                        if (scroll.canScrollForward) {
                            val width = tokens.pinFadeWidth.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, tokens.pinFade),
                                    startX = size.width - width,
                                    endX = size.width
                                ),
                                topLeft = Offset(size.width - width, 0f),
                                size = Size(width, size.height)
                            )
                        }
                    }
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(tokens.chipGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pins.forEach { chip ->
                    key(chip.pin) {
                        MarqueePinnedChip(
                            label = when (chip) {
                                is PinnedChip.Filter -> chip.saved.name
                                is PinnedChip.Addon -> chip.name
                            },
                            originIcon = when (chip) {
                                is PinnedChip.Filter -> Icons.Rounded.Tune
                                is PinnedChip.Addon -> Icons.Rounded.Extension
                            },
                            onClick = { onPinClick(chip) },
                            modifier = Modifier.onFocusChanged {
                                if (it.isFocused) {
                                    pinFocus.focused = chip.pin
                                } else if (pinFocus.focused == chip.pin) {
                                    pinFocus.focused = null
                                }
                            },
                            look = when {
                                !chip.active -> PinnedChipLook.PLAIN
                                chip is PinnedChip.Filter -> PinnedChipLook.FILLED
                                else -> PinnedChipLook.RINGED
                            },
                            count = (chip as? PinnedChip.Addon)?.count
                        )
                    }
                }
            }
        }
    }
}

/** A circle at rest: glass; filled while filters are on; amber for hidden streams, ringed while their view shows. */
private enum class SourcesCircleLook { PLAIN, ON, WARNING, WARNING_SHOWN }

/** The 1 dp edge of the amber hidden circle (the limit chip's edge before Spec 65). */
private val HIDDEN_EDGE_WIDTH = 1.dp

/**
 * One sheet circle: a `refreshSize` glass icon button in [look]; focused = white fill, black icon,
 * no scale. [badge] sits outside the top end corner on [badgeColor] (read when drawn, so an
 * animating accent repaints only the badge); [asking] runs a spinner ring around the circle. Both
 * draw over the focused button. Callers put `focusRequester` / `onFocusChanged` in [modifier].
 */
@Composable
private fun SourcesCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    look: SourcesCircleLook = SourcesCircleLook.PLAIN,
    badge: String? = null,
    badgeColor: () -> Color = { Color.Unspecified },
    asking: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val warning = look == SourcesCircleLook.WARNING || look == SourcesCircleLook.WARNING_SHOWN
    val restingBorder = when (look) {
        SourcesCircleLook.WARNING -> Border(BorderStroke(HIDDEN_EDGE_WIDTH, tokens.limitChipEdge), shape = CircleShape)
        SourcesCircleLook.WARNING_SHOWN ->
            Border(BorderStroke(tokens.chipSelectedRing, tokens.limitWarn), shape = CircleShape)
        SourcesCircleLook.PLAIN, SourcesCircleLook.ON -> Border.None
    }
    val restingFill = when (look) {
        SourcesCircleLook.PLAIN -> marquee.glassFill
        SourcesCircleLook.ON -> tokens.circleOnFill
        SourcesCircleLook.WARNING, SourcesCircleLook.WARNING_SHOWN -> tokens.limitChipFill
    }
    Box(modifier = Modifier.size(tokens.refreshSize)) {
        IconButton(
            onClick = onClick,
            modifier = modifier.fillMaxSize(),
            scale = IconButtonDefaults.scale(focusedScale = 1f),
            shape = IconButtonDefaults.shape(shape = CircleShape),
            colors = IconButtonDefaults.colors(
                containerColor = restingFill,
                contentColor = if (warning) tokens.limitWarn else white,
                focusedContainerColor = white,
                focusedContentColor = black,
                pressedContainerColor = white,
                pressedContentColor = black
            ),
            border = IconButtonDefaults.border(
                border = restingBorder,
                focusedBorder = Border.None,
                pressedBorder = Border.None
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(marquee.glassButton.iconSize)
            )
        }
        if (asking) {
            val ringSize = tokens.refreshSize + tokens.circleRingOutset * 2
            MarqueeSpinner(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(OVER_FOCUSED_BUTTON)
                    .requiredSize(ringSize),
                size = ringSize,
                trackColor = white.copy(alpha = ASKING_RING_TRACK_ALPHA),
                arcColor = white
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(OVER_FOCUSED_BUTTON)
                    .offset(x = tokens.circleBadgeOffset, y = -tokens.circleBadgeOffset)
                    .defaultMinSize(minWidth = tokens.circleBadgeSize)
                    .height(tokens.circleBadgeSize)
                    .drawBehind {
                        drawRoundRect(color = badgeColor(), cornerRadius = CornerRadius(size.height / 2))
                    }
                    .padding(horizontal = tokens.circleBadgePadding)
                    // The circle's own description already says the count.
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge,
                    style = NuvioTheme.textStyles.chip.copy(letterSpacing = 0.sp),
                    color = black,
                    maxLines = 1
                )
            }
        }
    }
}

/** The asking ring's track: fainter than a spinner's own, it circles a control. */
private const val ASKING_RING_TRACK_ALPHA = 0.15f

/** Above tv-material's focused surface, which raises itself over its siblings. */
private const val OVER_FOCUSED_BUTTON = 1f
