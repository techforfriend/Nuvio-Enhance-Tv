package com.nuvio.tv.ui.components.marquee

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.requestFocusAfterFrames

/**
 * Marquee primary button (spec §Components): white pill, black [icon] (optional) + label + optional [suffix]
 * (e.g. "31 min left"). Focused: centre scale, `marquee.ring` drawn
 * [com.nuvio.tv.ui.theme.NuvioMarqueePrimaryButtonTokens.ringGap] outside the pill (the page
 * shows through the gap) and a glow in [glowColor] at `glowAlpha` (neutral by default; the
 * ambient accent over artwork), read here so an animating accent recomposes only the button.
 * [onLongPress]: see [MarqueeLongPress]. [compact]: the smaller header variant (ring only, no
 * scale or glow, small label). Callers put
 * `onFocusChanged`/`focusProperties` in [modifier] (they precede the button's focus target).
 */
@Composable
internal fun MarqueePrimaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    suffix: String? = null,
    glowColor: () -> Color = NEUTRAL_GLOW,
    onLongPress: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.primaryButton
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val longPress = rememberMarqueeLongPress()
    Button(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .height(if (compact) tokens.compactHeight else tokens.height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(longPress.modifier(onLongPress)),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = white,
            contentColor = black,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ButtonDefaults.scale(focusedScale = if (compact) 1f else tokens.focusScale),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(marquee.ringWidth, marquee.ring),
                inset = tokens.ringGap,
                shape = CircleShape
            )
        ),
        glow = ButtonDefaults.glow(
            focusedGlow = Glow(
                elevationColor = glowColor().copy(alpha = if (compact) 0f else tokens.glowAlpha),
                elevation = if (compact) 0.dp else tokens.glowElevation
            )
        ),
        contentPadding = run {
            val padding = if (compact) tokens.compactPaddingHorizontal else tokens.horizontalPadding
            // The play triangle's ink sits right of its box centre; shift the content left by
            // half the imbalance, keeping the pill's width (detail Resume, Shield 2026-10-22).
            val nudge = if (icon == Icons.Rounded.PlayArrow) PLAY_ICON_OPTICAL_NUDGE else 0.dp
            PaddingValues(start = padding - nudge, end = padding + nudge)
        }
    ) {
        PillContentRow {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(tokens.iconSize))
                Spacer(modifier = Modifier.width(tokens.iconGap))
            }
            Text(
                text = label,
                style = if (compact) NuvioTheme.textStyles.panelButtonSmall else NuvioTheme.textStyles.button,
                maxLines = 1
            )
            if (!suffix.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(tokens.iconGap))
                Text(
                    text = suffix,
                    style = NuvioTheme.textStyles.caption,
                    color = black.copy(alpha = SUFFIX_ALPHA),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Centres a tv-material [Button]'s content in a pill taller than 40 dp. tv-material 1.1.0 puts the
 * content Row (min height 40 dp) in an inner Box that does not pass the pill's min height on, so on
 * a 48 dp pill the row sat at the top: content 4 dp high (Shield screencap 2026-10-22, detail
 * Resume). Filling the height re-centres it.
 */
@Composable
private fun PillContentRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Half the pill's ink imbalance with Rounded.PlayArrow leading: the triangle's ink starts 6 dp
 *  (8/24 of the 18 dp icon) inside its box, the trailing glyph's ink ends ~0.5 dp inside the text.
 *  Shield screencap (Resume + suffix): left margin 59 px vs right 49 px at 2x = 5 dp. */
private val PLAY_ICON_OPTICAL_NUDGE = 2.5.dp

/** Secondary content on a focused white pill: the primary button's suffix, the OSD tray's values,
 *  the Discover filter pill's label and chevron. */
internal const val SUFFIX_ALPHA = 0.55f
private val NEUTRAL_GLOW: () -> Color = { NuvioPrimitives.neutral600 }

/**
 * Marquee glass text button (approved sandbox `u5-stream-screen-marquee` stage 9 actions): a
 * [height] tall pill in `glassFill`, padded [horizontalPadding], a [restingLabelColor] label (white
 * by default, red for a destructive confirm, Spec 28 stage 7); focused = white fill, black label,
 * no scale. [highlighted] paints the focused look without focus (the subtitle delay strip tracks
 * its own focus target). [icon]: a leading icon in the label colour (Spec 28 `.l8add`), or in
 * [iconTint] resting and [focusedIconTint] focused when given (Spec 81's coral Tip heart). Callers put
 * `focusRequester`/`onFocusChanged` in [modifier].
 */
@Composable
internal fun MarqueeGlassTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = NuvioTheme.marquee.glassButton.size,
    horizontalPadding: Dp = NuvioTheme.marquee.glassButton.textPaddingHorizontal,
    textStyle: TextStyle = NuvioTheme.textStyles.button,
    highlighted: Boolean = false,
    restingLabelColor: Color = NuvioPrimitives.white,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    focusedIconTint: Color? = null
) {
    val marquee = NuvioTheme.marquee
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        interactionSource = interaction,
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = if (highlighted) white else marquee.glassFill,
            contentColor = if (highlighted) black else restingLabelColor,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        contentPadding = PaddingValues(horizontal = horizontalPadding)
    ) {
        PillContentRow {
            if (icon != null) {
                val tint = (if (focused || highlighted) focusedIconTint else iconTint) ?: LocalContentColor.current
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(marquee.glassButton.textIconSize)
                )
                Spacer(modifier = Modifier.width(marquee.glassButton.textIconGap))
            }
            Text(text = label, style = textStyle, maxLines = 1)
        }
    }
}

/**
 * Marquee glass icon button (spec §Components): a [size] circle (`glassButton.size` by default) in
 * `glassFill`, white `glassButton.iconSize` icon; focused = white fill, black icon, no scale. [onLongPress]: see [MarqueeLongPress].
 * Callers put `onFocusChanged`/`focusProperties` in [modifier].
 */
@Composable
internal fun MarqueeGlassButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = NuvioTheme.marquee.glassButton.size,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.glassButton
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    val longPress = rememberMarqueeLongPress()
    IconButton(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .size(size)
            .then(longPress.modifier(onLongPress)),
        enabled = enabled,
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        shape = IconButtonDefaults.shape(shape = CircleShape),
        colors = IconButtonDefaults.colors(
            containerColor = marquee.glassFill,
            contentColor = white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black,
            disabledContainerColor = marquee.glassFill,
            disabledContentColor = marquee.onImage3
        )
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(tokens.iconSize))
    }
}

/**
 * Empty / error message block (spec §Screens "Home states"; Detail error and the Discover states
 * reuse it): black, no accent but the optional button glow, left-aligned at (tvSafeHorizontal, `message.top`): [eyebrow] (when
 * set), title, body, and one primary button ([actionLabel] + [onAction], both or neither; optional
 * [actionIcon], focused glow [actionGlow]) that takes focus on entry through [focusRequester],
 * then an optional glass button ([secondaryLabel] + [onSecondary], e.g. Back; Spec 52 S4).
 * Without the button there is no focus grab (focus stays where it is, e.g. on a Discover filter
 * pill). With [backdropUrl] (the player's playback error, u6 stage 15) the black gains that backdrop
 * under `message.backdropVeil` and the block sits `message.bottomAnchor` above the bottom instead.
 * [leading]: drawn above the eyebrow (Detail's rating-limit lock disc, Spec 64 D13). [content]: drawn
 * between the body and the buttons, free of the block's `message.width` cap by its
 * own modifier if it needs to (the playback error's step line and Details, Spec 75 D4).
 */
@Composable
internal fun MarqueeMessage(
    eyebrow: String?,
    title: String,
    body: AnnotatedString?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    actionGlow: () -> Color = NEUTRAL_GLOW,
    backdropUrl: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.message
    val showAction = actionLabel != null && onAction != null
    val showSecondary = secondaryLabel != null && onSecondary != null
    if (showAction && focusRequester != null) {
        LaunchedEffect(focusRequester) { focusRequester.requestFocusAfterFrames() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NuvioPrimitives.black)
    ) {
        if (backdropUrl != null) {
            MarqueeBackdropImage(url = backdropUrl, focal = BACKDROP_FOCAL, modifier = Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().background(tokens.backdropVeil))
        }
        Column(
            modifier = Modifier
                .then(
                    if (backdropUrl != null) {
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = NuvioTheme.layout.tvSafeHorizontal, bottom = tokens.bottomAnchor)
                    } else {
                        Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = tokens.top)
                    }
                )
                .width(tokens.width),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            leading?.invoke()
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.onImage3
                )
            }
            Text(
                text = title,
                style = NuvioTheme.textStyles.stateTitle,
                color = NuvioTheme.colors.TextPrimary
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = NuvioTheme.textStyles.bodyHome,
                    color = marquee.onImageBody,
                    modifier = Modifier.widthIn(max = tokens.bodyWidth)
                )
            }
            content?.invoke()
            if (showAction || showSecondary) {
                // A wrapper, not button padding, so the focus scale and glow stay centred on the pill.
                Row(
                    modifier = Modifier.padding(top = tokens.actionTop),
                    horizontalArrangement = Arrangement.spacedBy(tokens.actionGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showAction) {
                        MarqueePrimaryButton(
                            label = actionLabel,
                            icon = actionIcon,
                            onClick = onAction,
                            focusRequester = focusRequester,
                            glowColor = actionGlow
                        )
                    }
                    if (showSecondary) {
                        MarqueeGlassTextButton(label = secondaryLabel, onClick = onSecondary)
                    }
                }
            }
        }
    }
}
