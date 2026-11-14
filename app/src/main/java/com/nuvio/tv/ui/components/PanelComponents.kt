package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** [CompactPanel]'s default fill. */
internal val COMPACT_PANEL_FILL = Color.Black.copy(alpha = 0.85f)

/**
 * Compact panel shell (approved F3 panel A; Audio, Subtitles and Speed through PlayerCompactPanel, the
 * subtitle delay strip and the Sync line directly; the other player panels still inline the same values
 * until their U6 slice): black .85,
 * radius 20 ([color] overrides the fill), [contentPadding] 16 / 14 by default. Content is usually a [PanelEyebrow] over [PlayerPanelRow]s;
 * it is clipped to the panel's shape (a list may run to the panel edge).
 */
@Composable
internal fun CompactPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    color: Color = COMPACT_PANEL_FILL,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(contentPadding),
        content = content
    )
}

@Composable
internal fun PanelEyebrow(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelEyebrowText(text = text, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
        PanelRule()
    }
}

/** The 1 dp white .12 rule under a compact panel's header, 10 dp above what follows (Column content). */
@Composable
internal fun PanelRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
    Spacer(modifier = Modifier.height(10.dp))
}

/** A compact panel's eyebrow text (uppercase, +2.2 tracking, white .55), without [PanelEyebrow]'s rule. */
@Composable
internal fun PanelEyebrowText(text: String, modifier: Modifier = Modifier) {
    PanelEyebrowText(text = AnnotatedString(text.uppercase()), modifier = modifier)
}

/**
 * [PanelEyebrowText] for text with coloured parts (the Sources panel's filter name). [text] is
 * drawn as given: the caller uppercases its parts. Longer than [maxLines], it ends in an ellipsis.
 */
@Composable
internal fun PanelEyebrowText(text: AnnotatedString, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 2.2.sp,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
internal fun PanelActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ROW_ALPHA
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.08f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(999.dp)),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.Transparent), shape = RoundedCornerShape(999.dp)),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(999.dp))
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = contentAlpha),
                    modifier = Modifier.width(16.dp).height(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Shared two-line selection row: the player panels (audio, subtitles, speed, chapters) and the
 * compact pickers (filter, catalog, player choice, library lists).
 *
 * Row states, per the 2026-08-10 panel mockups (Branch A):
 *  - resting: transparent surface, white title, tonal subtitle;
 *  - selected: white 0.16 fill, trailing tick;
 *  - focused: solid white pill with dark content — title, subtitle and tick all
 *    flip to the panel surface colour so they read on white.
 *
 * Focus drives both the pill fill and the content colour from a single [isFocused]
 * state (the AvatarPickerGrid idiom), so background and text animate together and
 * there is no white-on-white frame during fast D-pad scrolling. [titleEnd] sits at the row's end,
 * or right after the title with [titleEndInline] (the Discover catalog picker's addon name).
 * [subtitleMark] ends the subtitle in [markColor], semibold (the Audio panel's output mode, data st11),
 * or leads it with [markLeading] (the Subtitles panel's "Matches your file", st12). [titleTag] is a
 * small outlined tag right after the title (SDH, st12); [subtitleTags] are the same tags after the
 * subtitle, on its line (the Rating limit panel's ratings, Spec 64 K2); with them the subtitle is
 * one plain line and [subtitleMark] / [subtitleMaxLines] are ignored. [trailingIcon] ends an unselected row (a
 * row that opens its own list: the Library Sort & filter panel's chevron, u7 stage 5); [dimTitle]
 * rests the title at white .72 (that panel's Show shelves). [trailingIconTint] rests the trailing icon
 * in a colour (the collection Add catalog panel's green tick); [destructive] titles the row red (a
 * Remove action); [enabled] false dims the row and ignores OK, but it stays focusable so focus never
 * drops when an action reaches its limit (Move down on the last source, u8 stage 14).
 * [selectedFill] false keeps a selected row's tick but not its fill (a multi-select list's ticks, Spec 55
 * stage `s55-pick`). [titleFontFamily] draws the title in its own font (the App font picker); [subtitleMaxLines] lets a
 * picker option's description wrap (u11 stage 18). [leading] draws before the title (the Settings
 * colour panel's swatch chip).
 */
@Composable
internal fun PlayerPanelRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleEnd: String? = null,
    titleEndInline: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    subtitleMark: String? = null,
    markColor: () -> Color = { Color.White },
    markLeading: Boolean = false,
    titleTag: String? = null,
    trailingIcon: ImageVector? = null,
    dimTitle: Boolean = false,
    trailingIconTint: Color? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    titleFontFamily: FontFamily? = null,
    subtitleMaxLines: Int = 1,
    leading: (@Composable () -> Unit)? = null,
    selectedFill: Boolean = true,
    subtitleTags: List<String> = emptyList()
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val darkContent = Color.Black
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            selected && selectedFill -> Color.White.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "panelRowBg"
    )
    val titleColor by animateColorAsState(
        targetValue = when {
            destructive && isFocused -> NuvioPrimitives.errorOnLight
            destructive -> NuvioPrimitives.errorOnImage
            isFocused -> darkContent
            dimTitle -> Color.White.copy(alpha = 0.72f)
            else -> Color.White
        },
        animationSpec = tween(150),
        label = "panelRowTitle"
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (isFocused) darkContent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(150),
        label = "panelRowSubtitle"
    )
    val tickColor = if (isFocused) darkContent else trailingIconTint ?: Color.White

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (enabled) onClick() }
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Inline: the title keeps its width and titleEnd ellipsizes into what is left.
                Row(
                    modifier = if (titleEndInline) Modifier else Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = titleColor,
                        fontFamily = titleFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!titleTag.isNullOrBlank()) {
                        PanelRowTag(text = titleTag, color = titleColor, focused = isFocused, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (!titleEnd.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = titleEnd,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // At the end it is capped so a long (addon-supplied) name leaves the title room.
                        modifier = if (titleEndInline) {
                            Modifier.weight(1f, fill = false)
                        } else {
                            Modifier.widthIn(max = 120.dp)
                        }
                    )
                }
            }
            val subtitleText = subtitle?.takeIf { it.isNotBlank() }
            val markText = subtitleMark?.takeIf { it.isNotBlank() }
            if (subtitleTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subtitleText != null) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = subtitleColor,
                            maxLines = 1
                        )
                    }
                    subtitleTags.forEach { tag ->
                        PanelRowTag(text = tag, color = if (isFocused) darkContent else Color.White, focused = isFocused)
                    }
                }
            } else if (subtitleText != null || markText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildAnnotatedString {
                        fun appendMark() {
                            if (markText == null) return
                            // On the focused white pill the accent darkens to stay legible.
                            val mark = markColor().let { if (isFocused) lerp(it, darkContent, MARK_FOCUS_DARKEN) else it }
                            withStyle(SpanStyle(color = mark, fontWeight = FontWeight.SemiBold)) { append(markText) }
                        }
                        val separator = if (subtitleText != null && markText != null) " · " else ""
                        if (markLeading) {
                            appendMark()
                            append(separator)
                            if (subtitleText != null) append(subtitleText)
                        } else {
                            if (subtitleText != null) append(subtitleText)
                            append(separator)
                            appendMark()
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val endIcon = if (selected) Icons.Default.Check else trailingIcon
        if (endIcon != null) {
            Icon(
                imageVector = endIcon,
                contentDescription = null,
                tint = tickColor,
                modifier = Modifier.width(18.dp).height(18.dp)
            )
        }
    }
}

/** [PlayerPanelRow]'s small outlined tag (`.rt`): [color] text in a 1 dp edge that darkens on the [focused] pill. */
@Composable
private fun PanelRowTag(text: String, color: Color, focused: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (focused) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.38f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp)
    )
}

/** How far [PlayerPanelRow]'s subtitle mark darkens toward black on the focused white pill. */
private const val MARK_FOCUS_DARKEN = 0.45f

/** A disabled [PanelActionRow]'s content and a disabled [PlayerPanelRow] (u8 `.l8pk.dis`). */
private const val DISABLED_ROW_ALPHA = 0.45f

/**
 * Stepper row (approved sandbox `u6-player-panels-marquee` stage 5): [label], then [-] [value] [+]
 * (glass circles, focused = white) on one line, [helper] under. [enabled] false dims the value and
 * both steppers (a control that does not apply, e.g. gain during passthrough); a stepper at its
 * limit ([canDecrease]/[canIncrease] false) dims alone and leaves focus. The caller owns the focus
 * graph ([minusLeft]/[plusLeft], [up]/[down]; `FocusRequester.Default` = default search). Hold: [onDecreaseHold]/
 * [onIncreaseHold] tick while OK is held (see [runHoldRepeat]); the pair shares one [HoldState].
 */
@Composable
internal fun PanelStepperRow(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    enabled: Boolean = true,
    minusLeft: FocusRequester = FocusRequester.Default,
    plusLeft: FocusRequester = FocusRequester.Default,
    up: FocusRequester = FocusRequester.Default,
    down: FocusRequester = FocusRequester.Default,
    onDecreaseHold: ((heldMs: Long) -> Unit)? = null,
    onIncreaseHold: ((heldMs: Long) -> Unit)? = null,
    onHoldEnd: () -> Unit = {}
) {
    val tokens = NuvioTheme.marquee.playerPanel
    // Shared by the pair: a hold that hands focus over at the limit ends with its key-up on
    // the other button, which must swallow that click too.
    val holdState = remember { HoldState() }
    val minusEnabled = enabled && canDecrease
    val plusEnabled = enabled && canIncrease
    PanelAdjustRow(label = label, modifier = modifier, helper = helper) {
        PanelStepperButton(
            icon = Icons.Default.Remove,
            enabled = minusEnabled,
            focusRequester = minusFocusRequester,
            left = minusLeft,
            right = if (plusEnabled) plusFocusRequester else FocusRequester.Default,
            up = up,
            down = down,
            onClick = onDecrease,
            holdState = holdState,
            onHoldTick = onDecreaseHold,
            onHoldEnd = onHoldEnd
        )
        // Values are numbers with a sign ("+0.125s", "-3 dB"): always laid out LTR.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = NuvioTheme.textStyles.osdTime,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = tokens.valueMinWidth)
                    .alpha(if (enabled) 1f else tokens.disabledAlpha)
            )
        }
        PanelStepperButton(
            icon = Icons.Default.Add,
            enabled = plusEnabled,
            focusRequester = plusFocusRequester,
            left = plusLeft,
            right = FocusRequester.Default,
            up = up,
            down = down,
            onClick = onIncrease,
            holdState = holdState,
            onHoldTick = onIncreaseHold,
            onHoldEnd = onHoldEnd
        )
    }
}

/**
 * Adjust row (approved sandbox `u6-player-panels-marquee` stages 5 and 7, `.adj`): [label] then
 * [controls] on one line, an optional [helper] and [below] (a second control line, e.g. the
 * text-edge segments) under it. The controls dim themselves when they do not apply; the label
 * stays.
 */
@Composable
internal fun PanelAdjustRow(
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    below: (@Composable () -> Unit)? = null,
    controls: @Composable RowScope.() -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = tokens.adjustPaddingHorizontal,
                top = tokens.adjustPaddingTop,
                end = tokens.adjustPaddingHorizontal,
                bottom = tokens.adjustPaddingBottom
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.adjustGap)
        ) {
            Text(
                text = label,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                modifier = Modifier.weight(1f)
            )
            controls()
        }
        if (!helper.isNullOrBlank()) {
            Text(
                text = helper,
                style = NuvioTheme.textStyles.panelHelper,
                color = NuvioTheme.marquee.onImage3,
                modifier = Modifier.padding(top = tokens.helperTop)
            )
        }
        if (below != null) {
            Box(modifier = Modifier.padding(top = tokens.adjustBelowTop)) { below() }
        }
    }
}

/** On/off pill (stage 7 `.tgl`): [text] is the state ("On" / "Off"); OK flips it via [onClick]. */
@Composable
internal fun PanelToggle(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    PanelPill(
        text = text,
        selected = true,
        enabled = enabled,
        horizontalPadding = NuvioTheme.marquee.playerPanel.togglePaddingHorizontal,
        onClick = onClick,
        modifier = modifier
    )
}

/** One-of-n pills (stage 7 `.seg`): [options] in order, [selectedIndex] in glass. */
@Composable
internal fun PanelSegments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tokens = NuvioTheme.marquee.playerPanel
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(tokens.segmentGap)) {
        options.forEachIndexed { index, option ->
            PanelPill(
                text = option,
                selected = index == selectedIndex,
                enabled = enabled,
                horizontalPadding = tokens.segmentPaddingHorizontal,
                onClick = { onSelect(index) }
            )
        }
    }
}

/**
 * Colour dots (stage 7 `.dots`): one circle per [colors] entry, the one equal to [selectedArgb]
 * ringed. [small] = the 16 dp outline set. Disabled dots dim and leave focus.
 */
@Composable
internal fun PanelColorDots(
    colors: List<Color>,
    selectedArgb: Int,
    onSelect: (argb: Int) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true
) {
    val tokens = NuvioTheme.marquee.playerPanel
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) tokens.dotGapSmall else tokens.dotGap)
    ) {
        colors.forEach { color ->
            val argb = color.toArgb()
            PanelColorDot(
                color = color,
                size = if (small) tokens.dotSizeSmall else tokens.dotSize,
                selected = argb == selectedArgb,
                enabled = enabled,
                onClick = { onSelect(argb) }
            )
        }
    }
}

@Composable
private fun PanelColorDot(
    color: Color,
    size: Dp,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val ring = when {
        isFocused -> NuvioPrimitives.white
        selected -> tokens.dotSelectedRing
        else -> null
    }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val scale = if (isFocused) tokens.dotFocusScale else 1f
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else tokens.disabledAlpha
            }
            // The ring sits outside the dot (CSS box-shadow): it takes no layout space.
            .drawBehind {
                val radius = this.size.minDimension / 2f
                if (ring != null) {
                    drawCircle(ring, radius = radius + (tokens.dotRingGap + tokens.dotRingWidth).toPx())
                    drawCircle(NuvioPrimitives.black, radius = radius + tokens.dotRingGap.toPx())
                }
                drawCircle(color, radius = radius)
                val edge = 1.dp.toPx()
                drawCircle(tokens.dotEdge, radius = radius - edge / 2f, style = Stroke(width = edge))
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    )
}

/** One stage 7 pill (`.seg` span): white when focused, [selected] in glass. */
@Composable
internal fun PanelPill(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    horizontalPadding: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(tokens.pillHeight)
            .alpha(if (enabled) 1f else tokens.disabledAlpha)
            .clip(CircleShape)
            .background(
                when {
                    isFocused -> NuvioPrimitives.white
                    selected -> NuvioTheme.marquee.glassFill
                    else -> tokens.segmentFill
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = NuvioTheme.textStyles.panelPill,
            color = when {
                isFocused -> NuvioPrimitives.black
                selected -> NuvioPrimitives.white
                else -> NuvioTheme.marquee.onImage3
            },
            maxLines = 1
        )
    }
}

@Composable
private fun PanelStepperButton(
    icon: ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    onClick: () -> Unit,
    holdState: HoldState,
    onHoldTick: ((heldMs: Long) -> Unit)?,
    onHoldEnd: () -> Unit
) {
    val tokens = NuvioTheme.marquee.playerPanel
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    // tv-material 1.1.0 (no onLongClick): a held D-pad centre stays pressed from the first
    // key-down to key-up; key-up calls onClick (on whichever button holds focus then) before
    // the release reaches the pressed state.
    if (onHoldTick != null) {
        val isPressed by interactionSource.collectIsPressedAsState()
        val latestHoldTick by rememberUpdatedState(onHoldTick)
        val latestHoldEnd by rememberUpdatedState(onHoldEnd)
        val initialDelayMs = NuvioTheme.focus.longPressInitialDelayMillis
        val repeatMs = NuvioTheme.focus.longPressRepeatMillis
        LaunchedEffect(isPressed, enabled) {
            if (!isPressed || !enabled) return@LaunchedEffect
            runHoldRepeat(holdState, initialDelayMs, repeatMs, { latestHoldTick(it) }, { latestHoldEnd() })
        }
    }
    Card(
        onClick = {
            if (holdState.consumeClick() && enabled) {
                onClick()
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .size(tokens.stepperSize)
            .alpha(if (enabled) 1f else tokens.disabledAlpha)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                this.left = left
                this.right = right
                this.up = up
                this.down = down
            }
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.marquee.glassFill,
            focusedContainerColor = NuvioPrimitives.white,
            pressedContainerColor = NuvioPrimitives.white
        ),
        shape = CardDefaults.shape(CircleShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(modifier = Modifier.size(tokens.stepperSize), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) NuvioPrimitives.black else NuvioPrimitives.white,
                modifier = Modifier.size(tokens.stepperIconSize)
            )
        }
    }
}

/**
 * One held +/- pair. [swallowNextClick]: a hold stepped, so the key-up click that ends it (on
 * either button) is not a tap. [released]: the key-up click arrived; the repeat loop stops at
 * its next wake instead of waiting a frame for the pressed state to drop.
 */
internal class HoldState {
    var swallowNextClick = false
    var released = false
}

/** Key-up click: true = run the tap action, false = it ends a hold. */
internal fun HoldState.consumeClick(): Boolean {
    released = true
    if (!swallowNextClick) return true
    swallowNextClick = false
    return false
}

/**
 * Runs while the button is pressed: after [initialDelayMs] ticks every [repeatMs] with the
 * time held, until the release click or cancellation (release seen late, button disabled at the
 * range limit, panel closed); [onEnd] then runs once. A tap shorter than [initialDelayMs]
 * neither ticks nor ends.
 */
internal suspend fun runHoldRepeat(
    state: HoldState,
    initialDelayMs: Long,
    repeatMs: Long,
    onTick: (heldMs: Long) -> Unit,
    onEnd: () -> Unit
) {
    // A fresh press: drop a swallow left behind by a hold whose key-up went elsewhere.
    state.released = false
    state.swallowNextClick = false
    delay(initialDelayMs)
    if (state.released) return
    state.swallowNextClick = true
    var heldMs = initialDelayMs
    try {
        while (!state.released) {
            onTick(heldMs)
            delay(repeatMs)
            heldMs += repeatMs
        }
    } finally {
        onEnd()
    }
}
